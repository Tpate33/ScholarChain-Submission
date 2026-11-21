package scholarchain.storage.load

import scholarchain.core.{PartFormat, SchemaVersions, TargetStore}
import scholarchain.storage.model.{
  ArtifactRecord,
  Attrs,
  Bytes,
  ItemCodecs,
  LoadState,
  ObjectRef,
  PutOutcome,
  S3Layout,
  StoreError,
  Zone
}
import scholarchain.storage.port.{Clock, KeyValueStore, ObjectStore, ObjectWriter}

/**
 * Result of one ordinary-S3 load call (counts are per element: object and registry row together).
 *
 * @param objects
 *   every element of the load: the s3 load manifest's parts plus the run files
 * @param runFiles
 *   SE-P7-13 item 2: of `objects`, the run's manifest files (`_parts.json`, `_run-manifest.json`)
 */
final case class ObjectLoadReport(
    runId: String,
    loadId: String,
    objects: Int,
    written: Long,
    alreadyPresent: Long,
    finalState: LoadState,
    skipped: Boolean,
    registryRows: Int,
    runFiles: Int
)

/**
 * Loads the `s3` store of a verified run into ordinary S3 (SE-P7-06 item 7; checklist S1-S3, L2,
 * L3). It runs only inside [[LoadGate.proceed]] with a [[LoadPermit]] for `s3`, and reads nothing
 * but the permit's items and run files (re-read and re-checked against the verified manifest at
 * write time).
 *
 *   - Elements: every item of the s3 load manifest, then (SE-P7-13 item 2, README L1369 "versioned
 *     object keys and run manifests") the run's `_parts.json` and `_run-manifest.json`
 *     ([[LoadPermit.runFileNames]]), so the stored run carries its own integrity manifest (every
 *     object's SHA-256) and lineage next to its objects. Load manifests are not stored as files:
 *     each load's identity (load ID, store, `_parts.json` SHA-256, item count, state and counts) is
 *     its RunLedger row, and each stored object's path, SHA-256 and schema is its registry row.
 *   - Key (S1, S2): [[S3Layout.runObject]] of the element's path under the run's ID, in the zone of
 *     its record schema ([[zoneOf]]; the run files are in the graph zone); bucket and prefixes come
 *     from the layout settings (configuration), never a literal. Every key is built (and every run
 *     file read and checked) before any write; a key the layout refuses or a run file that no
 *     longer verifies stops the load with zero writes.
 *   - Probe (CR-P7-09 M1): before the first write, every object key is HEADed and every registry
 *     row read; any different element refuses the whole load with zero writes.
 *   - Write: create-once (`putCreateOnce`): equal bytes are `AlreadyPresent`, other bytes under the
 *     key are a `Conflict` (never an overwrite) and fail the load.
 *   - Registry (S3, L3): one ArtifactRegistry row per object (`putIfAbsent`), with the object's
 *     SHA-256 and byte count, the run, the load, the source path and its record schema.
 *   - Ledger: [[LoadLedger]] (store-owner rows of `s3` AND `dynamodb` first, CR-P7-11 M1;
 *     publication claim, decided by `partManifestSha256`; resume without duplicates; a loaded load
 *     is skipped with zero writes).
 */
object ObjectLoad {

  private val artifacts = ItemCodecs.ArtifactCodec

  /**
   * CR-P7-11 M1: the physical stores this loader writes: the object store (`s3`) and the key-value
   * store (`dynamodb`) that holds its ArtifactRegistry rows. Both owner rows are checked, then
   * claimed, before any write.
   */
  val WrittenStores: Vector[TargetStore] = Vector(TargetStore.S3, TargetStore.DynamoDb)

  /** The media type of a run file (each is one JSON document on one LF-terminated line). */
  val RunFileContentType: String = "application/json"

  /**
   * The zone of a part: summary inputs are semantic context; every other analysis output, graph.
   */
  def zoneOf(recordSchemaVersion: String): Zone =
    if (
      recordSchemaVersion == SchemaVersions.SemanticSummaryInput ||
      recordSchemaVersion == SchemaVersions.EntitySummaryInput
    ) Zone.SemanticContext
    else Zone.Graph

  /** The media type of a part format. */
  def contentTypeOf(f: PartFormat): String = f match {
    case PartFormat.Parquet => "application/vnd.apache.parquet"
    case PartFormat.Csv => "text/csv"
    case PartFormat.Jsonl => "application/x-ndjson"
  }

  /** One object to store: where, what it must be, and how to re-read its verified bytes. */
  private final case class Element(
      path: String,
      recordSchemaVersion: String,
      contentType: String,
      zone: Zone,
      ref: ObjectRef,
      sha256: String,
      byteCount: Long,
      read: () => Either[StoreError, Bytes]
  )

  private def first[A](xs: Vector[Either[StoreError, A]]): Either[StoreError, Vector[A]] =
    xs.collectFirst { case Left(e) => e }.toLeft(xs.collect { case Right(x) => x })

  /** The run files of `permit`, read and checked now (no write), as elements of the graph zone. */
  private def runFileElements(
      permit: LoadPermit,
      layout: S3Layout
  ): Either[StoreError, Vector[Element]] =
    first(permit.runFileNames.map { name =>
      for {
        schema <- permit
          .runFileSchema(name)
          .toRight(StoreError.Corrupt(s"$name: no recorded schema in the verified run"))
        bytes <- permit.readRunFile(name)
        ref <- layout.runObject(Zone.Graph, permit.runId, name)
      } yield Element(
        name,
        schema,
        RunFileContentType,
        Zone.Graph,
        ref,
        bytes.sha256,
        bytes.size.toLong,
        () => permit.readRunFile(name)
      )
    })

  def load(
      permit: LoadPermit,
      layout: S3Layout,
      objects: ObjectStore,
      registry: KeyValueStore,
      ledger: KeyValueStore,
      clock: Clock,
      progress: LoadProgress = new LoadProgress
  ): Either[StoreError, ObjectLoadReport] =
    if (Vector(permit, layout, objects, registry, ledger, clock, progress).contains(null))
      Left(StoreError.Refused("object load: every argument is required"))
    else if (permit.target != TargetStore.S3)
      Left(StoreError.Refused(s"object load: the permit is for ${permit.target.tag}"))
    else {
      val parts: Either[StoreError, Vector[Element]] = first(permit.items.map { it =>
        val z = zoneOf(it.recordSchemaVersion)
        layout
          .runObject(z, permit.runId, it.path)
          .map(r =>
            Element(
              it.path,
              it.recordSchemaVersion,
              contentTypeOf(it.format),
              z,
              r,
              it.sha256,
              it.byteCount,
              () => permit.readItem(it.path)
            )
          )
      })
      for {
        ps <- parts
        rs <- runFileElements(permit, layout)
        elements = ps ++ rs
        r <- LoadLedger
          .run(permit, ledger, clock, elements, WrittenStores, progress)(
            probe(permit, objects, registry)
          )(write(permit, objects, registry))
        rows <- registryRowsPresent(permit, registry, elements)
      } yield ObjectLoadReport(
        permit.runId,
        permit.load.loadId,
        elements.size,
        r.written,
        r.alreadyPresent,
        r.finalState,
        r.skipped,
        rows,
        rs.size
      )
    }

  /**
   * CR-P7-10 n2: the registry rows of this load that are actually stored now, each read back
   * (GetItem) and equal to the row the load writes; measured, never assumed from the object count.
   */
  private def registryRowsPresent(
      permit: LoadPermit,
      registry: KeyValueStore,
      elements: Vector[Element]
  ): Either[StoreError, Int] =
    elements.foldLeft[Either[StoreError, Int]](Right(0)) { (acc, e) =>
      for {
        n <- acc
        row = artifactRow(permit, e, e.sha256, e.byteCount)
        item <- artifacts.encode(row)
        key <- artifacts.key(row)
        stored <- registry.get(artifacts.table.role, key)
      } yield n + stored.count(_.get(Attrs.RecordSha256) == item.get(Attrs.RecordSha256))
    }

  private def artifactRow(
      permit: LoadPermit,
      e: Element,
      sha256: String,
      byteCount: Long
  ): ArtifactRecord =
    ArtifactRecord(
      ArtifactRecord.SchemaVersion,
      permit.runId,
      Some(permit.load.loadId),
      e.zone.tag,
      e.ref.bucket,
      e.ref.key,
      sha256,
      byteCount,
      e.contentType,
      Some(e.path),
      Some(e.recordSchemaVersion)
    )

  /**
   * CR-P7-09 M1: HEAD of the object key and GetItem of its registry row, before any write. Absent
   * or equal (the verified SHA-256 and byte count; the same registry body) is fine; anything else
   * is a `Conflict`.
   */
  private def probe(permit: LoadPermit, objects: ObjectStore, registry: KeyValueStore)(
      e: Element
  ): Either[StoreError, Unit] =
    for {
      meta <- objects.head(e.ref)
      _ <- meta match {
        case Some(m) if m.sha256 != e.sha256 || m.byteCount != e.byteCount =>
          Left(
            StoreError.Conflict(
              s"${e.ref.uri}: holds sha256 ${m.sha256}, the load has ${e.sha256} (pre-write probe)"
            )
          )
        case _ => Right(())
      }
      row <- artifacts.encode(artifactRow(permit, e, e.sha256, e.byteCount))
      key <- artifacts.key(artifactRow(permit, e, e.sha256, e.byteCount))
      stored <- registry.get(artifacts.table.role, key)
      _ <- stored match {
        case Some(s) if s.get(Attrs.RecordSha256) != row.get(Attrs.RecordSha256) =>
          Left(
            StoreError.Conflict(
              s"${artifacts.table.role.tag} ${key.pk} ${key.sk}: holds a different row (pre-write probe)"
            )
          )
        case _ => Right(())
      }
    } yield ()

  private def write(permit: LoadPermit, objects: ObjectWriter, registry: KeyValueStore)(
      e: Element
  ): Either[StoreError, PutOutcome] =
    for {
      bytes <- e.read()
      _ <-
        if (bytes.sha256 == e.sha256 && bytes.size.toLong == e.byteCount) Right(())
        else
          Left(
            StoreError.Corrupt(
              s"${e.path}: changed after the load was planned (planned sha256 ${e.sha256}); " +
                "nothing is written from it"
            )
          )
      obj <- objects.putCreateOnce(e.ref, bytes, e.contentType)
      row <- artifacts.encode(artifactRow(permit, e, bytes.sha256, bytes.size.toLong))
      reg <- registry.putIfAbsent(artifacts.table.role, row)
    } yield
      if (obj == PutOutcome.Written || reg == PutOutcome.Written) PutOutcome.Written
      else PutOutcome.AlreadyPresent
}
