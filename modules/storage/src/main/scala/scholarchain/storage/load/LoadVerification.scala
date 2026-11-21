package scholarchain.storage.load

import scala.collection.mutable

import scholarchain.core.{
  Json,
  LoadItem,
  LoadRoles,
  ObservedFile,
  PartFileV2,
  PartFormat,
  PartManifest,
  PartManifestV2,
  PartManifests,
  RunId,
  RunManifest,
  StoreLoadManifest,
  StructureValidation,
  TargetStore,
  ValidationError
}
import scholarchain.storage.model.{Bytes, ItemCodecs, StoreError, TableKeys}
import scholarchain.storage.port.{KeyValueReader, RunSource}

/** The verification step a rejection comes from, in the order they run. Frozen tags. */
enum VerificationCheck(val tag: String) {

  /** The run or load-manifest directory could not be listed within its bounds. */
  case SourceListing extends VerificationCheck("source-listing")

  /** `_SUCCESS` is missing: the directory is not a published run. */
  case SuccessMarker extends VerificationCheck("success-marker")

  /** `_parts.json` is missing, unreadable, or fails its core contract. */
  case PartManifestFile extends VerificationCheck("part-manifest")

  /** A different `_parts.json` was already published for the run ID (SE-P7-01 R1). */
  case Publication extends VerificationCheck("publication")

  /** `_run-manifest.json` is missing, malformed, or not the run the part manifest names. */
  case RunManifestFile extends VerificationCheck("run-manifest")

  /** A file is missing, extra, or differs in bytes, SHA-256 or LF rows from `_parts.json`. */
  case PartIntegrity extends VerificationCheck("part-integrity")

  /** A CSV part is not strict RFC 4180, or its record count differs from the declared rows. */
  case CsvRecords extends VerificationCheck("csv-records")

  /** The load-manifest directory holds a file that is not a target store's manifest. */
  case LoadManifestSet extends VerificationCheck("load-manifest-set")

  /** A load manifest is unreadable, malformed, or fails the core load-manifest checks. */
  case LoadManifestFile extends VerificationCheck("load-manifest")

  /** A role shared by several datasets is not the item's dataset directory. */
  case RoleDataset extends VerificationCheck("role-dataset")

  /** A Neptune CSV header is not the core layout of its role, byte for byte. */
  case NeptuneHeader extends VerificationCheck("neptune-header")

  /** A Neptune CSV row fails its row contract ([[NeptuneRows]]; deferred check row-contracts). */
  case NeptuneRowContract extends VerificationCheck("neptune-row-contract")

  /** Neptune graph IDs: a duplicate `~id` or a dangling endpoint (deferred check graph-ids). */
  case NeptuneGraphIds extends VerificationCheck("neptune-graph-ids")

  /** SE-P7-06: the supplied analysis settings are not the run's configuration (config digest). */
  case RunSettings extends VerificationCheck("run-settings")

  /**
   * SE-P7-06: a Parquet part does not decode, or its footer or decoded rows differ (parquet-rows).
   */
  case ParquetRowCount extends VerificationCheck("parquet-rows")

  /** SE-P7-06: a decoded Parquet record or the store's records fail a contract (row-contracts). */
  case ParquetRowContract extends VerificationCheck("parquet-row-contract")

  /** SE-P7-06: the Neptune CSV differs from the graph-nodes / graph-edges records. */
  case GraphIdParity extends VerificationCheck("graph-id-parity")

  /** SE-P7-06: a COAUTHORED_WITH edge or evidence-path hop does not trace to its evidence. */
  case EvidenceTrace extends VerificationCheck("evidence-trace")

  /** SE-P7-06: a summary input is not grounded in the run's records (summary-evidence). */
  case SummaryEvidence extends VerificationCheck("summary-evidence")

  /** SE-P7-07: the upstream grouping or curated run is not the analysis run's (by run IDs). */
  case UpstreamBinding extends VerificationCheck("upstream-binding")

  /** SE-P7-07: an analysis output differs from its core recomputation (analysis-recompute). */
  case AnalysisRecompute extends VerificationCheck("analysis-recompute")

  /** SE-P7-07: the canonical graph tables differ from their recomputation (canonical-tables). */
  case CanonicalTables extends VerificationCheck("canonical-tables")

  /**
   * SE-P7-08: the benchmark pair list is not this run's: its core digest differs from the run
   * manifest's `benchmarkPairsDigest`, it names another grouping run, or a pair is not two
   * identified researchers of the grouping run.
   */
  case BenchmarkPairs extends VerificationCheck("benchmark-pairs")

  /** SE-P7-07: evidence paths or the run chain fail checkPaths / checkRunChain. */
  case PathsRunChain extends VerificationCheck("paths-run-chain")

  /**
   * SE-P7-08, [[OpeningLoad]] only: an upstream input (grouping run, curated run, benchmark pair
   * list) could not be read and verified, so no store is written.
   */
  case UpstreamRead extends VerificationCheck("upstream-read")

  /**
   * Load gate only (SE-P7-05 F4): a report with no rejection and no verified run (hand-built; the
   * verifier never produces one), so there is nothing to load.
   */
  case NotVerified extends VerificationCheck("not-verified")

  /**
   * Load gate only (SE-P7-04 m1): a deferred check that applies to the target store has not been
   * performed and passed, so the store may not be written although the run is structurally valid.
   */
  case DeferredChecks extends VerificationCheck("deferred-checks")

  /** Load gate only: the run has no load manifest for the target store. */
  case TargetLoad extends VerificationCheck("target-load")

  /**
   * SE-P7-13, [[OpeningLoad]] only: a Parquet reader library class is not on the classpath
   * ([[OpeningLoad.ReaderClasses]], checked first: nothing is read or written), or (SE-P7-14,
   * CR-P7-16 m1) a `LinkageError` was raised while the inputs were read and verified (nothing is
   * written). A `LinkageError` inside one store's loader is that store's `Failed` outcome instead
   * (see [[OpeningLoad.run]]).
   */
  case ReaderClasspath extends VerificationCheck("reader-classpath")
}

/**
 * A check the structural verifier does not make yet (SE-P7-04 m1). Frozen tags. A store may be
 * written only after every check that [[DeferredCheck.appliesTo]] it has been performed and passed
 * ([[LoadGate]]); until a later increment implements a check, the gate refuses every store it
 * applies to.
 */
enum DeferredCheck(val tag: String, val description: String) {

  /** Parquet row counts re-derived from each part's footer (Parquet-reader increment 4). */
  case ParquetRows
      extends DeferredCheck(
        "parquet-rows",
        "Parquet row counts are the declared parquet-footer values; re-derived from the footer by the Parquet-reader increment (4)"
      )

  /** Every row decoded through its core record contract, with StableId checks of its IDs. */
  case RowContracts
      extends DeferredCheck(
        "row-contracts",
        "every row decoded through its core record contract, with StableId checks of its IDs (Neptune CSV rows: NeptuneRows, increment 3; Parquet rows: increment 4)"
      )

  /** Neptune CSV: unique `~id` among vertices and among edges; every endpoint a run vertex. */
  case GraphIds
      extends DeferredCheck(
        "graph-ids",
        "Neptune CSV IDs valid core IDs copied verbatim, no duplicate ~id, every ~from and ~to a vertex of the run with an allowed label (increment 3)"
      )

  /**
   * The Neptune CSV rows equal the Spark `graph-nodes` / `graph-edges` Parquet records (SE-P7-05:
   * split from graph-ids, because this half needs the Parquet row reader).
   */
  case GraphIdParity
      extends DeferredCheck(
        "graph-id-parity",
        "Neptune CSV vertex and edge rows equal the graph-nodes and graph-edges Parquet records (IDs, labels, endpoints, properties, run), which pass their GraphNodeV2 / GraphEdgeV2 contracts including the edge-ID and coauthor-ID formulas (Parquet-reader increment)"
      )

  /** COAUTHORED_WITH `sourceEvidence` resolves to its coauthor-edge record and supporting works. */
  case EvidenceTrace
      extends DeferredCheck(
        "evidence-trace",
        "COAUTHORED_WITH sourceEvidence resolves to its coauthor-edge record and supporting works (increment 5)"
      )

  /**
   * Summary inputs cite only stored records and state their values (SE-P7-05 A1, CR-P7-05 F1):
   * [[SummaryEvidenceCheck]], backed by the core validators.
   */
  case SummaryEvidence
      extends DeferredCheck(
        "summary-evidence",
        "summary-inputs and entity-summary-inputs resolve to stored records and state their values (AnalysisValidation.checkSummaryInputs, StructureValidation.checkEntitySummaries)"
      )

  /**
   * SE-P7-06: the core cross-record validators that recompute the analysis outputs from the
   * grouping run's teams and assignments (routed by CR-P7-05 to the DynamoDB records). They need
   * the grouping run, which is not in the analysis run directory, so this check is split from
   * row-contracts (which runs every validator that needs no upstream record).
   */
  case AnalysisRecompute
      extends DeferredCheck(
        "analysis-recompute",
        "the analysis outputs equal the core recomputation from the grouping run's counted teams (AnalysisValidation.checkEdges, checkMetrics, checkComponents, checkBridges, checkCoreness, checkWindows; StructureValidation.checkTemporal, checkPathLengthEstimates); needs a bound upstream"
      )

  /**
   * SE-P7-07 (CR-P7-08 M2): the canonical graph tables (graph-nodes, graph-edges, which the Neptune
   * CSV must equal) are exactly their recomputation from the grouping and curated runs.
   */
  case CanonicalTables
      extends DeferredCheck(
        "canonical-tables",
        "the graph-nodes and graph-edges tables equal CanonicalGraph.expected of the grouping and curated runs and the coauthor edges (CanonicalGraphValidation.checkTables); needs a bound upstream"
      )

  /**
   * SE-P7-07: split from analysis-recompute because both validators need the run's benchmark pair
   * list, which is not in the fixtures.
   */
  case PathsRunChain
      extends DeferredCheck(
        "paths-run-chain",
        "evidence paths are exactly the shortest paths of the benchmark pairs and every record's lineage is the run chain of both manifests (AnalysisValidation.checkPaths, checkRunChain); needs a bound upstream with the benchmark pair list"
      )
}

object DeferredCheck {

  /**
   * The deferred checks that must pass before a store is written. Fail closed: a check is listed
   * for a store whenever the store loads data the check is about.
   *
   *   - `neptune-bulk-load` (CSV vertices and edges): row-contracts, graph-ids, graph-id-parity
   *     (law 3: Neptune IDs equal the Spark IDs), evidence-trace (`coauthored-with-edges` carries
   *     `sourceEvidence`); never parquet-rows (Neptune loads CSV only,
   *     [[TargetStore.NeptuneBulkLoad]]).
   *   - `dynamodb` (Parquet analytics records): parquet-rows, row-contracts, evidence-trace (it
   *     loads `coauthor-edges` and `evidence-paths`).
   *   - `s3` (every Parquet dataset): parquet-rows, row-contracts, graph-id-parity (`graph-nodes`
   *     and `graph-edges` are the records the Neptune rows must equal), evidence-trace,
   *     summary-evidence (it stores `summary-inputs` and `entity-summary-inputs`). Not graph-ids:
   *     that check is about the Neptune CSV, which the s3 load does not hold (SE-P7-05).
   *   - `s3-vectors` (summary inputs): parquet-rows, row-contracts, summary-evidence.
   *   - SE-P7-06: `dynamodb` and `s3` (they store the analysis outputs) also analysis-recompute.
   *   - SE-P7-07 (CR-P7-08 M2, fail closed until the human decides otherwise): `neptune-bulk-load`
   *     also analysis-recompute (checkEdges recomputes the COAUTHORED_WITH counts and years) and
   *     canonical-tables (checkTables recomputes RESOLVES_TO, AUTHORED and every other vertex and
   *     edge); `s3` also canonical-tables (it stores graph-nodes and graph-edges); `dynamodb` and
   *     `s3` also paths-run-chain (they store the evidence paths and every record's lineage).
   *     `s3-vectors` holds summary inputs only: summary-evidence binds their upstream and lineage.
   */
  def appliesTo(t: TargetStore): Vector[DeferredCheck] = t match {
    case TargetStore.NeptuneBulkLoad =>
      Vector(
        RowContracts,
        GraphIds,
        GraphIdParity,
        EvidenceTrace,
        AnalysisRecompute,
        CanonicalTables
      )
    case TargetStore.DynamoDb =>
      Vector(ParquetRows, RowContracts, EvidenceTrace, AnalysisRecompute, PathsRunChain)
    case TargetStore.S3 =>
      Vector(
        ParquetRows,
        RowContracts,
        GraphIdParity,
        EvidenceTrace,
        SummaryEvidence,
        AnalysisRecompute,
        CanonicalTables,
        PathsRunChain
      )
    case TargetStore.S3Vectors => Vector(ParquetRows, RowContracts, SummaryEvidence)
  }

  /** The checks applying to `t` that are not in `passed`, in declaration order. */
  def outstanding(t: TargetStore, passed: Set[DeferredCheck]): Vector[DeferredCheck] =
    appliesTo(t).filterNot(passed.contains)
}

/** One reason a run is refused; `subject` names a file, run or store, never content. */
final case class Rejection(check: VerificationCheck, subject: String, error: StoreError)

/** How the run's `_parts.json` relates to the RunLedger publication row. */
enum PublicationStatus(val tag: String) {

  /** No publication of this run ID is recorded yet. */
  case Unrecorded extends PublicationStatus("unrecorded")

  /** The recorded publication has exactly this `_parts.json` SHA-256 (a rerun or resume). */
  case SameAsRecorded extends PublicationStatus("same-as-recorded")
}

/** Declared totals of one role of one load manifest. */
final case class RoleSummary(
    role: String,
    recordSchemaVersion: String,
    items: Int,
    declaredRows: Long,
    bytes: Long
)

/** Declared totals of one load manifest. */
final case class StoreSummary(
    targetStore: TargetStore,
    loadId: String,
    items: Int,
    declaredRows: Long,
    bytes: Long,
    roles: Vector[RoleSummary]
)

/**
 * A run that passed every structural verification step of [[LoadVerifier]], bound to the
 * [[RunSource]] it was verified from (SE-P7-04 m2). It is NOT permission to write: only
 * [[LoadGate.proceed]] turns it into a [[LoadPermit]], and only once the deferred checks of the
 * target store have passed.
 *
 * It holds no part bytes. `readVerified` (package `load` only, SE-P7-05 A2) is a re-checking
 * handle: it re-reads one part from the bound source at the moment of the call, never more than the
 * manifest byte count + 1 bytes, and returns the bytes only when their byte count and SHA-256 equal
 * the part's entry in the verified `_parts.json`; a part replaced, truncated, grown or removed
 * after verification is refused (`Corrupt`, `Refused` or the source's error). The returned
 * [[Bytes]] are immutable and are exactly the bytes that were hashed. No method takes a source: the
 * handle can read only the source it verified. Outside package `load` a VerifiedRun exposes
 * metadata only; part bytes are reachable only through a [[LoadPermit]], which reads only its own
 * store's load items.
 *
 * A plain class (not a case class): no `copy`, `unapply` or `Mirror`; the constructor is
 * `private[load]`, so only [[LoadVerifier]] creates one. Equality is by verified content (run,
 * manifests, passed checks), not by the source binding.
 *
 * @param passedChecks
 *   deferred checks performed and passed for this run, per target store (a check passed for one
 *   store says nothing about another)
 */
final class VerifiedRun private[load] (
    val runId: String,
    val partManifestSha256: String,
    val partManifest: PartManifestV2,
    val runManifest: RunManifest,
    val loads: Vector[StoreLoadManifest],
    val passedChecks: Map[TargetStore, Set[DeferredCheck]],
    source: RunSource,
    val partManifestSchemaVersion: String
) {
  private val entries: Map[String, PartFileV2] = partManifest.files.map(f => f.path -> f).toMap

  def load(t: TargetStore): Option[StoreLoadManifest] = loads.find(_.targetStore == t)

  /** The deferred checks performed and passed for `t`. */
  def passedFor(t: TargetStore): Set[DeferredCheck] = passedChecks.getOrElse(t, Set.empty)

  /**
   * The bytes of the listed part `path`, re-read from the bound source now (at most its manifest
   * byte count + 1 bytes), only if they still equal the verified manifest entry (byte count and
   * SHA-256). Unlisted paths are refused.
   */
  private[load] def readVerified(path: String): Either[StoreError, Bytes] =
    entries.get(path) match {
      case None =>
        Left(StoreError.Refused(s"$path: not a file of the verified run $runId"))
      case Some(f) =>
        source.readAtMost(path, f.byteCount).flatMap { b =>
          if (b.size.toLong == f.byteCount && b.sha256 == f.sha256) Right(b)
          else
            Left(
              StoreError.Corrupt(
                s"$path: changed since verification (verified ${f.byteCount} bytes, sha256 ${f.sha256}; " +
                  s"read ${b.size} bytes, sha256 ${b.sha256}); nothing may be written from it"
              )
            )
        }
    }

  /**
   * SE-P7-13 item 2: the bytes of `_parts.json` (which lists no entry for itself), re-read from the
   * bound source now (within the source's own bounds), only if their SHA-256 is still the verified
   * [[partManifestSha256]].
   */
  private[load] def readPartManifest(): Either[StoreError, Bytes] =
    source.read(PartManifest.FileName).flatMap { b =>
      if (b.sha256 == partManifestSha256) Right(b)
      else
        Left(
          StoreError.Corrupt(
            s"${PartManifest.FileName}: changed since verification (verified sha256 " +
              s"$partManifestSha256; read ${b.sha256}); nothing may be written from it"
          )
        )
    }

  private def key = (
    runId,
    partManifestSha256,
    partManifestSchemaVersion,
    partManifest,
    runManifest,
    loads,
    passedChecks
  )

  override def equals(other: Any): Boolean = other match {
    case v: VerifiedRun => key == v.key
    case _ => false
  }
  override def hashCode: Int = key.hashCode
  override def toString: String = {
    val passed = TargetStore.values.toVector.filter(t => passedFor(t).nonEmpty).map { t =>
      s"${t.tag}:" + passedFor(t).map(_.tag).toVector.sorted.mkString("[", ",", "]")
    }
    s"VerifiedRun($runId, _parts.json sha256 $partManifestSha256, ${loads.size} loads, " +
      s"passed ${passed.mkString("{", " ", "}")})"
  }
}

/**
 * Permission to write one target store from one verified run (SE-P7-04 m1). Only [[LoadGate]]
 * creates one, and only when the run is structurally valid, has a load manifest for `target`, and
 * every deferred check of `target` has passed. A plain class with a `private[load]` constructor.
 *
 * It exposes exactly what a writer needs (SE-P7-05 A2, CR-P7-05 F2): the run ID, the `_parts.json`
 * SHA-256 (for the publication row), the target store's load manifest and items, [[readItem]] and,
 * for an `s3` permit only (SE-P7-13 item 2), [[readRunFile]]. The [[VerifiedRun]] itself is
 * private. Through a permit a writer can read exactly: the items of its own store's load manifest
 * ([[readItem]]), and, on an `s3` permit, the run's `_parts.json` and `_run-manifest.json`
 * ([[readRunFile]], each re-read and re-verified at the call). Nothing else: no other store's item,
 * no load manifest, and no other listed file; a permit for any other store reads no run file.
 */
final class LoadPermit private[load] (run: VerifiedRun, val target: TargetStore) {

  def runId: String = run.runId

  /** SHA-256 of the verified `_parts.json` bytes (the publication identity of the run). */
  def partManifestSha256: String = run.partManifestSha256

  /** The load manifest of `target` (present: the gate checked it). */
  def load: StoreLoadManifest = run.load(target).get

  /** The items of `target`'s load manifest, in manifest (path) order. */
  def items: Vector[LoadItem] = load.items

  /**
   * The verified bytes of one item of this store's load manifest (re-read and re-checked now, at
   * most its byte count + 1 bytes); any other path, including another store's item and
   * `_run-manifest.json`, is refused without a read.
   */
  def readItem(path: String): Either[StoreError, Bytes] =
    if (load.items.exists(_.path == path)) run.readVerified(path)
    else Left(StoreError.Refused(s"$path: not an item of the ${target.tag} load"))

  /**
   * SE-P7-13 item 2 (README L1369 "versioned object keys and run manifests"; checklist S2): the
   * run's two manifest files, `_parts.json` and `_run-manifest.json`, which the ordinary-S3 store
   * keeps next to the run's objects. Only an `s3` permit names them (empty for every other store),
   * so this widens no other loader's reach: no other listed file, no other store's item and no load
   * manifest becomes readable (CR-P7-05 F2 stays closed).
   */
  def runFileNames: Vector[String] =
    if (target == TargetStore.S3) LoadPermit.RunFiles else Vector.empty

  /**
   * The record schema of one of [[runFileNames]] as the verified run records it: the
   * `schemaVersion` of `_parts.json` AS DECODED (v1 or v2; SE-P7-14, CR-P7-16 n1: not the v2 view
   * the verifier works on) for `_parts.json`, the `_parts.json` entry's `recordSchemaVersion` for
   * `_run-manifest.json`. None for any other path.
   */
  def runFileSchema(path: String): Option[String] =
    if (!runFileNames.contains(path)) None
    else if (path == PartManifest.FileName) Some(run.partManifestSchemaVersion)
    else run.partManifest.files.find(_.path == path).map(_.recordSchemaVersion)

  /**
   * The verified bytes of one of [[runFileNames]], re-read and re-checked now: `_run-manifest.json`
   * against its `_parts.json` entry (byte count and SHA-256), `_parts.json` against the verified
   * part-manifest SHA-256. Any other path, and any path on a permit for another store, is refused
   * without a read.
   */
  def readRunFile(path: String): Either[StoreError, Bytes] =
    if (!runFileNames.contains(path))
      Left(StoreError.Refused(s"$path: not a run file the ${target.tag} load may read"))
    else if (path == PartManifest.FileName) run.readPartManifest()
    else run.readVerified(path)

  override def toString: String = s"LoadPermit(${target.tag}, ${run.runId})"
}

object LoadPermit {

  /** The run files an `s3` permit reads (SE-P7-13 item 2), in this order. */
  val RunFiles: Vector[String] = Vector(PartManifest.FileName, PartManifest.RunManifestFile)
}

/**
 * The deterministic result of verifying one run directory and its load manifests: equal inputs give
 * an equal report. `rejections` are in check order, then file order; `verified` is present exactly
 * when there is none.
 *
 * Structural validity is not load acceptance (SE-P7-04 m1): [[structurallyValid]] says only that
 * the structural checks passed; [[acceptedFor]] adds the deferred checks of one store, and only
 * [[LoadGate]] grants a write.
 *
 * @param runId
 *   the run named by `_parts.json`, once it decodes
 * @param observedFiles
 *   regular files of the run directory (markers included)
 * @param csvRowsCounted
 *   data records counted in CSV parts whose bytes matched the part manifest
 * @param deferred
 *   every deferred check (all of [[DeferredCheck]]); which of them passed, per store, is
 *   [[VerifiedRun.passedFor]], and what is still open for a store is [[outstandingFor]]
 */
final case class VerificationReport(
    runId: Option[String],
    partManifestSha256: Option[String],
    publication: Option[PublicationStatus],
    observedFiles: Int,
    observedBytes: Long,
    csvPartsCounted: Int,
    csvRowsCounted: Long,
    stores: Vector[StoreSummary],
    deferred: Vector[DeferredCheck],
    rejections: Vector[Rejection],
    verified: Option[VerifiedRun]
) {

  /** Every structural check passed. Never sufficient for a write: see [[acceptedFor]]. */
  def structurallyValid: Boolean = rejections.isEmpty && verified.isDefined

  /** The deferred checks of `t` not yet performed and passed (all of them unless verified). */
  def outstandingFor(t: TargetStore): Vector[DeferredCheck] =
    DeferredCheck.outstanding(t, verified.map(_.passedFor(t)).getOrElse(Set.empty))

  /** The run may be loaded into `t`: exactly when [[LoadGate.proceed]] would call its writer. */
  def acceptedFor(t: TargetStore): Boolean = LoadGate.refusal(this, t).isEmpty
}

/** Reads the one recorded publication of a run ID (SE-P7-01 R1); read-only. */
object Publications {
  private val codec = ItemCodecs.RunPublicationCodec

  def check(
      ledger: KeyValueReader,
      runId: String,
      partManifestSha256: String
  ): Either[StoreError, PublicationStatus] =
    for {
      key <- TableKeys.RunLedger.publication(runId)
      item <- ledger.get(codec.table.role, key)
      status <- item match {
        case None => Right(PublicationStatus.Unrecorded)
        case Some(i) =>
          codec.decode(i).flatMap { p =>
            if (p.runId != runId)
              Left(StoreError.Corrupt(s"publication row of $runId names run ${p.runId}"))
            else if (p.partManifestSha256 == partManifestSha256)
              Right(PublicationStatus.SameAsRecorded)
            else
              Left(
                StoreError.Conflict(
                  s"$runId is already published with _parts.json sha256 ${p.partManifestSha256}; " +
                    s"this directory has $partManifestSha256 (one publication per run ID)"
                )
              )
          }
      }
    } yield status
}

/**
 * Verification of a published run before any store write (SE-P7-03; README L1350-1432, checklist
 * L1). Every check uses the core contracts as they are:
 *
 *   1. list the run directory within its bounds, never following links;
 *   2. require `_SUCCESS` (nothing else is read without it);
 *   3. read and decode `_parts.json` ([[PartManifests.decode]]) and take the SHA-256 of its bytes;
 *   4. one publication per run ID: a recorded publication with another SHA-256 is a `Conflict`;
 *   5. decode `_run-manifest.json` (one LF-terminated row) and bind it to the part manifest
 *      ([[PartManifestV2.checkRunManifest]]); the expected run ID is [[RunId.of]] of it;
 *   6. observe every file and run [[PartManifests.verify]]: exact file set, byte counts, SHA-256,
 *      LF rows of `jsonl` files;
 *   7. CSV parts with matching bytes: strict RFC 4180 scan, record count equal to the declared rows
 *      ([[CsvRecords]]);
 *   8. the load-manifest directory holds only `<store tag>.json` files, each one LF-terminated row
 *      decoding through the core contract for its own store;
 *   9. [[StructureValidation.checkLoadManifests]]: one manifest per store, each
 *      [[StoreLoadManifest.checkAgainst]] the part manifest and observed SHA-256, roles from
 *      [[LoadRoles]] with their record schemas and formats, no loadable part left out;
 *   10. storage rules: where several roles of a store share a record schema and format (the Neptune
 *       vertex and edge roles), the item's dataset directory is its role; every Neptune CSV header
 *       equals the core [[scholarchain.core.NeptuneCsv]] layout of its role byte for byte;
 *   11. (SE-P7-05) only when steps 1-10 found nothing: the `neptune-bulk-load` row contracts and
 *       graph IDs ([[NeptuneRows]]) over the CSV bytes read in step 6. A failure rejects the run
 *       (`neptune-row-contract`, `neptune-graph-ids`); a pass marks deferred checks row-contracts
 *       and graph-ids passed for `neptune-bulk-load` only.
 *
 * Every part listed in `_parts.json` is read with its manifest byte count as the bound
 * ([[RunSource.readAtMost]]), so an oversized part costs at most that many bytes + 1. Record and ID
 * checks (`StableId.check`) apply to the manifests through their core contracts (run ID, load ID,
 * schema versions); the remaining per-row checks are deferred ([[Deferred]]), and [[LoadGate]]
 * refuses every store whose deferred checks have not passed. The verifier gets only a
 * [[KeyValueReader]], so it cannot write. Each file of the run directory is read exactly once
 * (SE-P7-04 n1); only CSV bytes are kept, and the resulting [[VerifiedRun]] holds no bytes (it
 * re-reads and re-checks on demand).
 */
object LoadVerifier {

  /** Every deferred check; listed in every report. */
  val Deferred: Vector[DeferredCheck] = DeferredCheck.values.toVector

  /** Deferred checks step 11 performs for the Neptune store when the run's CSV passes them. */
  val NeptunePerformed: Set[DeferredCheck] = Set(DeferredCheck.RowContracts, DeferredCheck.GraphIds)

  private def message(errors: Vector[ValidationError]): Vector[String] = errors.map(_.message)

  /**
   * SE-P7-14 (CR-P7-16 n1): the `schemaVersion` of the decoded `_parts.json` (v1 or v2), not of the
   * v2 view ([[PartManifests.asV2]] always reports v2).
   */
  private[load] def decodedSchemaVersion(pm: Either[PartManifest, PartManifestV2]): String =
    pm.fold(_.schemaVersion, _.schemaVersion)

  /** One LF-terminated row, as the producers write manifests. */
  private def oneRow(b: Bytes): Either[String, String] = {
    val text = b.utf8
    if (text.endsWith("\n") && !text.stripSuffix("\n").contains('\n')) Right(text.stripSuffix("\n"))
    else Left("is not one LF-terminated row")
  }

  /** Steps 1-11 only: no record-level Parquet check is performed (step 12 needs a context). */
  def verify(run: RunSource, loads: RunSource, ledger: KeyValueReader): VerificationReport =
    verify(run, loads, ledger, None)

  /**
   * Steps 1-11 and, with a context and only when they found nothing, step 12 (SE-P7-06): the
   * record-level checks of [[ParquetChecks]] over the run's Parquet parts and Neptune CSV.
   */
  def verify(
      run: RunSource,
      loads: RunSource,
      ledger: KeyValueReader,
      context: Option[VerificationContext]
  ): VerificationReport = {
    val rejections = mutable.ArrayBuffer.empty[Rejection]
    def reject(c: VerificationCheck, subject: String, e: StoreError): Unit =
      rejections += Rejection(c, subject, e)
    def report(
        runId: Option[String] = None,
        sha: Option[String] = None,
        publication: Option[PublicationStatus] = None,
        files: Int = 0,
        bytes: Long = 0L,
        csvParts: Int = 0,
        csvRows: Long = 0L,
        stores: Vector[StoreSummary] = Vector.empty,
        verified: Option[VerifiedRun] = None
    ) =
      VerificationReport(
        runId,
        sha,
        publication,
        files,
        bytes,
        csvParts,
        csvRows,
        stores,
        Deferred,
        rejections.toVector,
        if (rejections.isEmpty) verified else None
      )

    run.listFiles() match {
      case Left(e) =>
        reject(VerificationCheck.SourceListing, "run", e)
        report()
      case Right(files) if !files.contains(PartManifest.SuccessMarker) =>
        reject(
          VerificationCheck.SuccessMarker,
          PartManifest.SuccessMarker,
          StoreError.Refused("the run directory has no _SUCCESS marker: not a published run")
        )
        report(files = files.size)
      case Right(files) =>
        val partsBytes =
          if (!files.contains(PartManifest.FileName))
            Left(StoreError.Refused("the run directory has no _parts.json"))
          else run.read(PartManifest.FileName)
        partsBytes match {
          case Left(e) =>
            reject(VerificationCheck.PartManifestFile, PartManifest.FileName, e)
            report(files = files.size)
          case Right(pb) =>
            PartManifests.decode(pb.utf8) match {
              case Left(errors) =>
                message(errors).foreach(m =>
                  reject(
                    VerificationCheck.PartManifestFile,
                    PartManifest.FileName,
                    StoreError.Refused(m)
                  )
                )
                report(sha = Some(pb.sha256), files = files.size)
              case Right(pm) =>
                verifyRun(
                  run,
                  loads,
                  ledger,
                  context,
                  files,
                  pb,
                  pm,
                  reject,
                  () => rejections.nonEmpty,
                  report
                )
            }
        }
    }
  }

  private def verifyRun(
      run: RunSource,
      loads: RunSource,
      ledger: KeyValueReader,
      context: Option[VerificationContext],
      files: Vector[String],
      partsBytes: Bytes,
      pm: Either[PartManifest, PartManifestV2],
      reject: (VerificationCheck, String, StoreError) => Unit,
      hasRejections: () => Boolean,
      report: (
          Option[String],
          Option[String],
          Option[PublicationStatus],
          Int,
          Long,
          Int,
          Long,
          Vector[StoreSummary],
          Option[VerifiedRun]
      ) => VerificationReport
  ): VerificationReport = {
    val sha = partsBytes.sha256
    val v2 = PartManifests.asV2(pm)
    val runId = v2.runId

    // 4. One publication per run ID.
    val publication = Publications.check(ledger, runId, sha) match {
      case Left(e) => reject(VerificationCheck.Publication, runId, e); None
      case Right(s) => Some(s)
    }

    // Every listed file is read with its manifest byte count as the bound (SE-P7-05, risk T1).
    val listedBytes: Map[String, Long] = v2.files.map(f => f.path -> f.byteCount).toMap
    def readListed(path: String): Either[StoreError, Bytes] =
      listedBytes.get(path) match {
        case Some(n) => run.readAtMost(path, n)
        case None => run.read(path) // not listed: refused by step 6 whatever its bytes
      }

    // 5. The run manifest (read once: step 6 observes these same bytes, SE-P7-04 n1).
    val runManifestBytes: Option[Either[StoreError, Bytes]] =
      if (files.contains(PartManifest.RunManifestFile))
        Some(readListed(PartManifest.RunManifestFile))
      else None
    val runManifest: Option[RunManifest] =
      (runManifestBytes match {
        case None =>
          Left(Vector(StoreError.Refused("the run directory has no _run-manifest.json")))
        case Some(read) =>
          read.left
            .map(Vector(_))
            .flatMap(b => oneRow(b).left.map(w => Vector(StoreError.Refused(w))))
            .flatMap(text =>
              Json
                .decode[RunManifest](text)
                .left
                .map(es => message(es).map(StoreError.Refused(_)))
            )
            .flatMap { rm =>
              val bound = PartManifestV2.checkRunManifest(v2, rm)
              if (bound.isEmpty) Right(rm) else Left(message(bound).map(StoreError.Refused(_)))
            }
      }) match {
        case Left(es) =>
          es.foreach(reject(VerificationCheck.RunManifestFile, PartManifest.RunManifestFile, _))
          None
        case Right(rm) => Some(rm)
      }
    val expectedRunId =
      runManifest.flatMap(rm => RunId.of(rm).toOption).getOrElse(runId)

    // 6. Every file against the part manifest; each file is read once, only CSV bytes are kept.
    val csvPaths = v2.files.filter(_.format == PartFormat.Csv).map(_.path).toSet
    val content = mutable.Map.empty[String, Bytes]
    val observed = files.flatMap { path =>
      val bytes =
        if (path == PartManifest.FileName) Right(partsBytes)
        else if (path == PartManifest.RunManifestFile)
          runManifestBytes.getOrElse(readListed(path))
        else readListed(path)
      bytes match {
        case Left(e) => reject(VerificationCheck.PartIntegrity, path, e); None
        case Right(b) =>
          if (csvPaths.contains(path)) content(path) = b
          Some(ObservedFile.of(path, b.toArray))
      }
    }
    message(PartManifests.verify(pm, expectedRunId, observed)).foreach(m =>
      reject(VerificationCheck.PartIntegrity, "run", StoreError.Corrupt(m))
    )
    val observedByPath = observed.map(o => o.path -> o).toMap
    def intact(f: PartFileV2): Option[Bytes] =
      observedByPath
        .get(f.path)
        .filter(o => o.sha256 == f.sha256 && o.byteCount == f.byteCount)
        .flatMap(_ => content.get(f.path))

    // 7. CSV record counts.
    val headers = mutable.Map.empty[String, Bytes]
    var csvParts = 0
    var csvRows = 0L
    v2.files.filter(_.format == PartFormat.Csv).foreach { f =>
      intact(f).foreach { b =>
        CsvRecords.scan(b) match {
          case Left(why) => reject(VerificationCheck.CsvRecords, f.path, StoreError.Corrupt(why))
          case Right(s) =>
            headers(f.path) = s.header
            if (s.dataRows != f.rowCount)
              reject(
                VerificationCheck.CsvRecords,
                f.path,
                StoreError.Corrupt(s"rowCount: listed ${f.rowCount}, counted ${s.dataRows}")
              )
            else { csvParts += 1; csvRows += s.dataRows }
        }
      }
    }

    // 8-10. Load manifests.
    val manifests = readLoadManifests(loads, reject)
    message(StructureValidation.checkLoadManifests(manifests, pm, sha)).foreach(m =>
      reject(VerificationCheck.LoadManifestFile, "load", StoreError.Refused(m))
    )
    manifests.foreach { m =>
      val roles = LoadRoles.all.filter(_.target == m.targetStore)
      m.items.foreach { it =>
        val subject = s"${m.targetStore.tag}:${it.path}"
        val sharing = roles.filter(r =>
          r.recordSchemaVersion == it.recordSchemaVersion && r.formats.contains(it.format)
        )
        val dataset = it.path.takeWhile(_ != '/')
        if (sharing.size > 1 && dataset != it.targetRole)
          reject(
            VerificationCheck.RoleDataset,
            subject,
            StoreError.Refused(
              s"role ${it.targetRole} is shared by ${sharing.size} datasets of ${it.recordSchemaVersion}; this part is in dataset $dataset"
            )
          )
        if (m.targetStore == TargetStore.NeptuneBulkLoad)
          (NeptuneHeaders.byRole.get(it.targetRole), headers.get(it.path)) match {
            case (Some(expected), Some(got)) if got != expected =>
              reject(
                VerificationCheck.NeptuneHeader,
                subject,
                StoreError.Corrupt(
                  if (got.size == 0) "no header line"
                  else s"header differs from the NeptuneCsv layout of ${it.targetRole}"
                )
              )
            case _ => () // unknown role or unscanned part: rejected by an earlier check
          }
      }
    }

    val stores = manifests.sortBy(_.targetStore.ordinal).map { m =>
      StoreSummary(
        m.targetStore,
        m.loadId,
        m.items.size,
        m.items.map(_.rowCount).sum,
        m.items.map(_.byteCount).sum,
        m.items
          .groupBy(i => (i.targetRole, i.recordSchemaVersion))
          .toVector
          .sortBy(_._1)
          .map { case ((r, s), xs) =>
            RoleSummary(r, s, xs.size, xs.map(_.rowCount).sum, xs.map(_.byteCount).sum)
          }
      )
    }
    // 11. Neptune row contracts and graph IDs, only on an otherwise clean run (SE-P7-05 B1, B2).
    val neptuneStep: (Set[DeferredCheck], Option[NeptuneGraph]) =
      manifests.find(_.targetStore == TargetStore.NeptuneBulkLoad) match {
        case Some(m) if !hasRejections() =>
          val read: String => Either[StoreError, Bytes] = p =>
            content.get(p).toRight(StoreError.Refused(s"$p: not a verified CSV part"))
          NeptuneRows.parse(m, runId, read) match {
            case Left(problems) =>
              problems.foreach(p =>
                reject(
                  VerificationCheck.NeptuneRowContract,
                  p.subject,
                  StoreError.Corrupt(p.reason)
                )
              )
              (Set.empty, None)
            case Right(g) =>
              val ids = NeptuneRows.graphIds(g)
              ids.foreach(p =>
                reject(VerificationCheck.NeptuneGraphIds, p.subject, StoreError.Corrupt(p.reason))
              )
              if (ids.isEmpty) (NeptunePerformed, Some(g)) else (Set.empty, None)
          }
        case _ => (Set.empty, None)
      }
    val (neptunePassed, neptuneGraph) = neptuneStep

    // 12. Record-level checks (SE-P7-06), only with a context and on an otherwise clean run.
    // Parquet parts are re-read here (their bytes were not kept in step 6), each with its manifest
    // byte count as the bound, and used only when byte count and SHA-256 still equal the entry.
    val sorted = manifests.sortBy(_.targetStore.ordinal)
    val recordPassed: Map[TargetStore, Set[DeferredCheck]] =
      (context, runManifest) match {
        case (Some(ctx), Some(rm)) if !hasRejections() =>
          val entries = v2.files.map(f => f.path -> f).toMap
          def verifiedRead(it: LoadItem): Either[StoreError, Bytes] =
            entries.get(it.path) match {
              case None => Left(StoreError.Refused(s"${it.path}: not listed in _parts.json"))
              case Some(f) =>
                run.readAtMost(f.path, f.byteCount).flatMap { b =>
                  if (b.size.toLong == f.byteCount && b.sha256 == f.sha256) Right(b)
                  else
                    Left(StoreError.Corrupt(s"${f.path}: changed since its bytes were verified"))
                }
            }
          val outcome = ParquetChecks.run(runId, rm, sorted, neptuneGraph, verifiedRead, ctx)
          outcome.rejections.foreach(r => reject(r.check, r.subject, r.error))
          outcome.passed
        case _ => Map.empty
      }

    val passedChecks: Map[TargetStore, Set[DeferredCheck]] = {
      val base =
        if (neptunePassed.isEmpty) Map.empty[TargetStore, Set[DeferredCheck]]
        else Map(TargetStore.NeptuneBulkLoad -> neptunePassed)
      (base.keySet ++ recordPassed.keySet).toVector.map { t =>
        t -> (base.getOrElse(t, Set.empty) ++ recordPassed.getOrElse(t, Set.empty))
      }.toMap
    }

    val verified = runManifest.map(rm =>
      new VerifiedRun(
        runId,
        sha,
        v2,
        rm,
        sorted,
        passedChecks,
        run,
        decodedSchemaVersion(pm)
      )
    )
    report(
      Some(runId),
      Some(sha),
      publication,
      files.size,
      observed.map(_.byteCount).sum,
      csvParts,
      csvRows,
      stores,
      verified
    )
  }

  /** Step 8: only `<store tag>.json` files, each one row of its own store's manifest. */
  private def readLoadManifests(
      loads: RunSource,
      reject: (VerificationCheck, String, StoreError) => Unit
  ): Vector[StoreLoadManifest] =
    loads.listFiles() match {
      case Left(e) =>
        reject(VerificationCheck.SourceListing, "load", e)
        Vector.empty
      case Right(names) =>
        val byName = TargetStore.values.toVector.map(t => s"${t.tag}.json" -> t).toMap
        names.filterNot(byName.contains).foreach { n =>
          reject(
            VerificationCheck.LoadManifestSet,
            n,
            StoreError.Refused("not the load manifest of a target store (<store tag>.json)")
          )
        }
        TargetStore.values.toVector.flatMap { t =>
          val name = s"${t.tag}.json"
          if (!names.contains(name)) None // reported by checkLoadManifests: no manifest for t
          else
            loads
              .read(name)
              .left
              .map(Vector(_))
              .flatMap(b => oneRow(b).left.map(w => Vector(StoreError.Refused(w))))
              .flatMap(text =>
                Json
                  .decode[StoreLoadManifest](text)
                  .left
                  .map(es => message(es).map(StoreError.Refused(_)))
              )
              .flatMap(m =>
                if (m.targetStore == t) Right(m)
                else
                  Left(Vector(StoreError.Refused(s"holds the ${m.targetStore.tag} load manifest")))
              ) match {
              case Left(es) =>
                es.foreach(reject(VerificationCheck.LoadManifestFile, name, _))
                None
              case Right(m) => Some(m)
            }
        }
    }
}

/**
 * The only way from verification to writes (SE-P7-03, SE-P7-04 m1). `write` runs, with a
 * [[LoadPermit]] for `target`, exactly when all of these hold; otherwise it is never called:
 *
 *   1. the report has no rejection and holds a [[VerifiedRun]] (structurally valid);
 *   2. the run has a load manifest for `target`;
 *   3. every [[DeferredCheck.appliesTo]] `target` check has been performed and passed for `target`
 *      ([[VerifiedRun.passedFor]]).
 *
 * Fail closed. With a [[VerificationContext]] the verifier performs parquet-rows, row-contracts,
 * graph-id-parity and evidence-trace; summary-evidence, analysis-recompute and canonical-tables
 * need a bound [[SummaryUpstream]] (SE-P7-07), and paths-run-chain also its benchmark pair list.
 * Without a context only steps 1-11 run and every store keeps an open check.
 */
object LoadGate {

  /** Why `report` may not be loaded into `target`, or None when [[proceed]] would write. */
  def refusal(report: VerificationReport, target: TargetStore): Option[Vector[Rejection]] =
    if (report.rejections.nonEmpty) Some(report.rejections)
    else
      report.verified match {
        case None =>
          Some(
            Vector(
              Rejection(
                VerificationCheck.NotVerified,
                "run",
                StoreError.Refused("no rejection and no verified run: nothing was verified")
              )
            )
          )
        case Some(v) if v.load(target).isEmpty =>
          Some(
            Vector(
              Rejection(
                VerificationCheck.TargetLoad,
                target.tag,
                StoreError.Refused(s"the run has no ${target.tag} load manifest")
              )
            )
          )
        case Some(v) =>
          val open = DeferredCheck.outstanding(target, v.passedFor(target))
          if (open.isEmpty) None
          else
            Some(
              Vector(
                Rejection(
                  VerificationCheck.DeferredChecks,
                  target.tag,
                  StoreError.Refused(
                    s"deferred checks not yet performed and passed for ${target.tag}: " +
                      open.map(_.tag).mkString(", ")
                  )
                )
              )
            )
      }

  def proceed[A](report: VerificationReport, target: TargetStore)(
      write: LoadPermit => Either[StoreError, A]
  ): Either[Vector[Rejection], Either[StoreError, A]] =
    refusal(report, target) match {
      case Some(rs) => Left(rs)
      case None => Right(write(new LoadPermit(report.verified.get, target)))
    }
}
