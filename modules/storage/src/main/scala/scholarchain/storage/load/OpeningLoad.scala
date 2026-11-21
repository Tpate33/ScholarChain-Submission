package scholarchain.storage.load

import scholarchain.core.TargetStore
import scholarchain.core.config.AnalysisRunSettings
import scholarchain.storage.model.{S3Layout, StoreError}
import scholarchain.storage.parquet.ParquetReadBounds
import scholarchain.storage.port.{
  Clock,
  Embedder,
  GraphReader,
  GraphWriter,
  Jitter,
  KeyValueReader,
  KeyValueStore,
  ObjectStore,
  RunSource,
  RuntimeStores,
  Sleeper,
  VectorStore
}

/**
 * Where one opening load reads from (SE-P7-08 item 3). Every source is bounded and never follows
 * links ([[RunSource]]).
 *
 * @param run
 *   the analysis run directory (`run=<hex>`)
 * @param loads
 *   its load-manifest directory (`load=<hex>`)
 * @param grouping
 *   the grouping run directory the analysis read
 * @param curated
 *   the curated Hadoop run directory of that grouping run
 * @param benchmark
 *   the directory holding the benchmark pair list
 * @param benchmarkFile
 *   the list's file name in `benchmark`
 */
final case class OpeningLoadSources(
    run: RunSource,
    loads: RunSource,
    grouping: RunSource,
    curated: RunSource,
    benchmark: RunSource,
    benchmarkFile: String
)

/**
 * SE-P7-13 item 1: the bounds of one Parquet part read, as plain configuration values in package
 * `load`, so a caller outside storage (rag, services, tests) can configure [[OpeningLoad]] without
 * naming the reader package `scholarchain.storage.parquet` (which rag may not reference,
 * `verifyRagSourceImports`). Validated exactly like the reader's own bounds, to which it is
 * converted inside storage only. No default.
 *
 * @param maxFooterBytes
 *   the largest declared Parquet footer length
 * @param maxPartBytes
 *   the largest part (bytes) decoded at all
 * @param maxRowsPerPart
 *   the largest footer row count decoded
 * @param maxPageBytes
 *   the largest uncompressed page size accepted from a page header
 */
final case class ParquetReadLimits private (
    maxFooterBytes: Int,
    maxPartBytes: Long,
    maxRowsPerPart: Long,
    maxPageBytes: Int
) {

  /** The reader's bounds (storage only). Valid by construction: [[ParquetReadLimits.of]]. */
  private[storage] def bounds: ParquetReadBounds =
    ParquetReadBounds
      .of(maxFooterBytes, maxPartBytes, maxRowsPerPart, maxPageBytes)
      .fold(e => throw new IllegalStateException(e.message), identity)
}

object ParquetReadLimits {

  /** The limits, or the first refusal of the reader's own validation. */
  def of(
      maxFooterBytes: Int,
      maxPartBytes: Long,
      maxRowsPerPart: Long,
      maxPageBytes: Int
  ): Either[StoreError, ParquetReadLimits] =
    ParquetReadBounds
      .of(maxFooterBytes, maxPartBytes, maxRowsPerPart, maxPageBytes)
      .map(_ => new ParquetReadLimits(maxFooterBytes, maxPartBytes, maxRowsPerPart, maxPageBytes))
}

/**
 * Configuration of one opening load. No default anywhere: every bound, limit and the layout
 * (buckets, prefixes) come from the caller's configuration. SE-P7-13 item 1: no field type is in
 * the reader package `scholarchain.storage.parquet` (pinned by `OpeningLoadSurfaceSuite`).
 *
 * @param settings
 *   the analysis settings; refused by the verifier unless their config digest is the run's
 * @param vectors
 *   the vector loader's configuration (SE-P7-09; S3 Vectors limits UNVERIFIED, see
 *   [[VectorLoadConfig]])
 */
final case class OpeningLoadConfig(
    settings: AnalysisRunSettings,
    parquet: ParquetReadLimits,
    upstream: UpstreamBounds,
    benchmarkMaxBytes: Long,
    keyValueLimits: KeyValueLoadLimits,
    layout: S3Layout,
    vectors: VectorLoadConfig
)

/**
 * The stores one opening load writes (loader side) and the runtime reads from.
 *
 * @param keyValues
 *   the analysis-results and ArtifactRegistry tables (and the runtime tables)
 * @param ledger
 *   the RunLedger table (store-owner, publication, load and rejected-record rows); separate so a
 *   rerun against a fresh ledger can be shown
 * @param embedder
 *   the embedding model (Phase 8); local tests pass the synthetic test embedder, which only a
 *   `SyntheticTestOnly` vector configuration accepts
 */
final case class OpeningLoadTargets(
    graph: GraphReader & GraphWriter,
    keyValues: KeyValueStore,
    ledger: KeyValueStore,
    objects: ObjectStore,
    vectors: VectorStore,
    embedder: Embedder,
    clock: Clock,
    sleeper: Sleeper,
    jitter: Jitter
)

/** What happened to one target store. Frozen tags. */
enum StoreLoadStatus(val tag: String) {

  /** The gate opened and the loader finished: every element written or already present. */
  case Loaded extends StoreLoadStatus("loaded")

  /** The gate opened; the RunLedger already records this load as loaded: zero writes. */
  case Skipped extends StoreLoadStatus("skipped-already-loaded")

  /** The gate refused: nothing was written (see the rejections). */
  case Refused extends StoreLoadStatus("refused")

  /** The gate opened and the loader stopped with an error (a rerun resumes). */
  case Failed extends StoreLoadStatus("failed")
}

/**
 * The outcome of one target store (deterministic: no time, no randomness).
 *
 * @param performed
 *   deferred checks performed and passed for the store, in declaration order (tags)
 * @param open
 *   deferred checks of the store not performed or not passed (tags); non-empty means refused
 * @param written
 *   elements THIS call created (CR-P7-10 m2: also on a failed call, the partial count)
 * @param alreadyPresent
 *   elements this call found equal (a resume, a rerun against a fresh ledger, a concurrent loader)
 * @param conflicts
 *   1 when the loader stopped at a different element under an existing key or at another run's
 *   store-owner row (never overwritten), else 0
 * @param counts
 *   what the load holds, by name, in a fixed order (neptune: vertices, edges; dynamodb: items, rows
 *   per role; s3: objects, measured registry rows; s3-vectors: parts, rows, per index routed /
 *   written / present / rejected / stored, text objects, measured registry rows, rejected, embedded
 *   texts). On a FAILED call (CR-P7-11 m3): the writes this call made to the other stores, by name
 *   (s3-vectors: `text-written`, `text-present`, `registry-written`, `registry-present`,
 *   `rejected-rows-written`, `rejects-object-written`; only names that occurred), so the partial
 *   state of every store is reported
 * @param rejectedRecords
 *   rejected records of this call (s3-vectors; 0 elsewhere)
 */
final case class StoreLoadOutcome(
    target: TargetStore,
    status: StoreLoadStatus,
    performed: Vector[String],
    open: Vector[String],
    written: Long,
    alreadyPresent: Long,
    conflicts: Long,
    counts: Vector[(String, Long)],
    rejections: Vector[Rejection],
    error: Option[StoreError],
    rejectedRecords: Long
)

/**
 * The deterministic report of one opening load: equal inputs and equal store contents give an equal
 * report.
 *
 * @param benchmarkDigest
 *   [[scholarchain.core.BenchmarkPairList.digest]] of the list read, when it was read
 * @param recordedBenchmarkDigest
 *   the analysis run manifest's `benchmarkPairsDigest`, when the run verified far enough to read it
 */
final case class OpeningLoadReport(
    runId: Option[String],
    partManifestSha256: Option[String],
    benchmarkDigest: Option[String],
    recordedBenchmarkDigest: Option[String],
    upstreamProblems: Vector[String],
    rejections: Vector[Rejection],
    stores: Vector[StoreLoadOutcome]
) {
  def store(t: TargetStore): StoreLoadOutcome = stores.find(_.target == t).get

  /**
   * Every store is loaded (now or earlier): the only state in which a runtime view is handed out.
   */
  def complete: Boolean =
    stores.nonEmpty &&
      stores.forall(s => s.status == StoreLoadStatus.Loaded || s.status == StoreLoadStatus.Skipped)
}

/**
 * The report and, only when [[OpeningLoadReport.complete]], the runtime view of the loaded stores
 * (read-only facades, role law 4). CR-P7-10 R5: a partly loaded, refused or failed load yields no
 * runtime view at all, so no caller can serve queries over stores that did not load.
 */
final case class OpeningLoadResult(report: OpeningLoadReport, runtime: Option[RuntimeStores])

/**
 * The single production entry point of a local load (SE-P7-08 item 3; SE-P7-09; WP7 handoff): it
 * reads the upstream runs and the benchmark pair list through the production readers, verifies the
 * analysis run with the full [[VerificationContext]], and loads every store whose gate opens
 * through [[LoadGate.proceed]] and the real loaders, in [[TargetStore]] order:
 *
 *   - `neptune-bulk-load`: [[GraphLoad]] into `targets.graph`;
 *   - `dynamodb`: [[KeyValueLoad]] into `targets.keyValues`;
 *   - `s3`: [[ObjectLoad]] into `targets.objects`, registry rows into `targets.keyValues`;
 *   - `s3-vectors`: [[VectorLoad]] into `targets.vectors`, context text into `targets.objects`, its
 *     registry rows into `targets.keyValues`, rejected records into `targets.ledger`.
 *
 * Nothing is marked passed by the caller: the only inputs are sources, configuration and stores.
 * Any upstream read problem refuses every store with zero writes (fail closed). A store's failure
 * does not stop the others (each has its own ledger row); a rerun resumes or skips.
 *
 * LIMITATION (human decision 2026-11-20, option b): ONE analysis run per local store instance. What
 * is guaranteed (CR-P7-11 M1), given ONE RunLedger paired with the stores:
 *
 *   - before any store is loaded, the store-owner rows of all four stores are read from
 *     `targets.ledger`; if any names another run (another run ID or `_parts.json` SHA-256), every
 *     store whose gate opens fails with that `Conflict` and NOTHING is written (no data, no ledger
 *     row), even when the other run's keys would not collide (another window set);
 *   - each loader also checks, then claims (`putIfAbsent`), the owner rows of every physical store
 *     it writes ([[GraphLoad.WrittenStores]], [[KeyValueLoad.WrittenStores]],
 *     [[ObjectLoad.WrittenStores]], [[VectorLoad.WrittenStores]]) before its first data write, so
 *     the guarantee also holds for a loader called on its own, and a store left unclaimed by a
 *     refused load (for example s3-vectors when the embedder is refused) cannot be claimed by
 *     another run while that run would write into this run's object or key-value store;
 *   - the owning run can always resume or rerun.
 *
 * What is NOT guaranteed: the owner rows live in the RunLedger, so a FRESH ledger in front of
 * populated stores has no owner rows and the stores are then protected only by the per-key
 * pre-write probes (a different element under a key this run writes is a `Conflict`; a run whose
 * keys collide with none of the stored ones would load and mix); the check, the claims, the probe
 * and the writes are not one atomic step, so a concurrent second run is caught only by the
 * create-once writes. Load another run into fresh stores with a fresh RunLedger; Spark's stable IDs
 * are kept as they are. Multi-run coexistence and active-run switching are deferred.
 */
object OpeningLoad {

  /**
   * SE-P7-13 item 1, SE-P7-14 (CR-P7-16 m1): one representative class per library jar the Parquet
   * reader links against, as (jar artifact, class). They are Optional-scope dependencies of
   * storage, so they are on storage's own classpaths but on no dependent module's (rag, services,
   * tests) unless that module adds them itself. Hadoop is listed conservatively: whether parquet-mr
   * 1.13.1 touches a Hadoop class on the in-memory read path is not observed (see the ParquetRows
   * notes), and storage tests always run with it. `OpeningLoadSuite` checks that each class is
   * loaded from its named jar.
   *
   * What the check guarantees: each class is loaded WITHOUT initialisation before anything is read;
   * any one missing refuses every store with zero writes and names the class (check
   * `reader-classpath`). What it does NOT guarantee: that the reader is usable. One class per jar
   * does not prove every class the reader needs links (a jar can be partial or of another version).
   * Such a failure surfaces later as a `LinkageError`, which [[run]] handles (see there); it is
   * never thrown out of [[run]].
   */
  val ReaderJars: Vector[(String, String)] = Vector(
    "parquet-hadoop" -> "org.apache.parquet.hadoop.ParquetFileReader",
    "parquet-common" -> "org.apache.parquet.io.InputFile",
    "parquet-column" -> "org.apache.parquet.io.ColumnIOFactory",
    "parquet-encoding" -> "org.apache.parquet.column.values.bitpacking.BytePacker",
    "parquet-format-structures" -> "org.apache.parquet.format.FileMetaData",
    "hadoop-client-api" -> "org.apache.hadoop.conf.Configuration"
  )

  /** The classes of [[ReaderJars]], in that order. */
  val ReaderClasses: Vector[String] = ReaderJars.map(_._2)

  /** The classes of `names` that do not load (without initialisation) from storage's loader. */
  private[storage] def missingClasses(names: Vector[String]): Vector[String] = {
    val loader = Option(getClass.getClassLoader).getOrElse(ClassLoader.getSystemClassLoader)
    names.filter { n =>
      try { Class.forName(n, false, loader); false }
      catch { case _: ClassNotFoundException | _: LinkageError => true }
    }
  }

  /**
   * One opening load. Classpath failures (SE-P7-13 item 1; SE-P7-14, CR-P7-16 m1) are never thrown
   * out of this method:
   *
   *   - a [[ReaderJars]] class that does not load refuses every store before anything is read
   *     (check `reader-classpath`, zero writes, runtime None);
   *   - a `LinkageError` (for example `NoClassDefFoundError` from a partial Parquet or Hadoop
   *     classpath) raised while the upstream runs and the benchmark list are read, the run is
   *     verified (where Parquet is first decoded) or the store owners are checked refuses every
   *     store the same way (`reader-classpath`, zero writes: that phase only reads, the RunLedger
   *     through a read-only view; runtime None);
   *   - a `LinkageError` raised inside one store's loader (after its gate opened) fails that store
   *     (`Failed`, a `Permanent` error naming `reader-classpath` and the error class, with the
   *     writes this call made so far as for any other failure; its RunLedger row may stay
   *     `writing`, which the next call moves to `failed` and resumes); the other stores continue,
   *     and runtime is None because the load is not complete.
   *
   * Other fatal errors (`VirtualMachineError`, `InterruptedException`, ...) are not caught.
   */
  def run(
      sources: OpeningLoadSources,
      config: OpeningLoadConfig,
      targets: OpeningLoadTargets
  ): OpeningLoadResult = runWith(sources, config, targets, ReaderClasses)

  /** [[run]] with the reader classes to require (tests name an absent class). */
  private[storage] def runWith(
      sources: OpeningLoadSources,
      config: OpeningLoadConfig,
      targets: OpeningLoadTargets,
      readerClasses: Vector[String]
  ): OpeningLoadResult = {
    val missing = missingClasses(readerClasses)
    if (missing.nonEmpty)
      OpeningLoadResult(
        classpathRefused(
          StoreError.Refused(
            "the Parquet reader is not on this classpath (missing " + missing.mkString(", ") +
              "): parquet-hadoop and hadoop-client-api/-runtime are Optional dependencies of " +
              "storage and must be added to the calling module's (Test) classpath; nothing was " +
              "read or written"
          )
        ),
        None
      )
    else load(sources, config, targets)
  }

  /** Every store refused with one `reader-classpath` rejection; nothing read or written. */
  private def classpathRefused(why: StoreError): OpeningLoadReport = {
    val rs = Vector(Rejection(VerificationCheck.ReaderClasspath, "classpath", why))
    OpeningLoadReport(None, None, None, None, Vector.empty, rs, refusedAll(rs))
  }

  /** The error class and message of a link failure (class names only, never record content). */
  private def linkage(e: LinkageError): String =
    e.getClass.getName + Option(e.getMessage).map(": " + _).getOrElse("")

  private def refusedAll(rs: Vector[Rejection]): Vector[StoreLoadOutcome] =
    TargetStore.values.toVector.map { t =>
      StoreLoadOutcome(
        t,
        StoreLoadStatus.Refused,
        Vector.empty,
        DeferredCheck.appliesTo(t).map(_.tag),
        0L,
        0L,
        0L,
        Vector.empty,
        rs,
        None,
        0L
      )
    }

  private def load(
      sources: OpeningLoadSources,
      config: OpeningLoadConfig,
      targets: OpeningLoadTargets
  ): OpeningLoadResult = {
    val report = prepare(sources, config, targets) match {
      case Left(refused) => refused
      case Right(p) =>
        OpeningLoadReport(
          p.verification.runId,
          p.verification.partManifestSha256,
          Some(p.benchmarkDigest),
          p.recordedBenchmarkDigest,
          Vector.empty,
          p.verification.rejections,
          TargetStore.values.toVector.map(t =>
            loadOne(p.verification, t, config, targets, p.foreign)
          )
        )
    }
    // CR-P7-11 m2: the runtime reads only vectors of the configured embedding policy.
    val runtime =
      Option.when(report.complete)(
        RuntimeStores(
          targets.objects,
          targets.keyValues,
          targets.keyValues,
          targets.graph,
          targets.vectors,
          targets.vectors,
          config.vectors.embedding
        )
      )
    OpeningLoadResult(report, runtime)
  }

  /** What the read-only phase before any write established (see [[prepare]]). */
  private final case class Prepared(
      verification: VerificationReport,
      benchmarkDigest: String,
      recordedBenchmarkDigest: Option[String],
      foreign: Option[StoreError]
  )

  /**
   * The read-only phase: upstream runs and benchmark list, verification (Parquet is first decoded
   * here), and the store-owner check (CR-P7-11 M1). Nothing here writes: the verifier gets a
   * read-only RunLedger view and `checkOwners` only reads. Left is the report of a load refused in
   * every store: an upstream problem (`upstream-read`) or, SE-P7-14 (CR-P7-16 m1), a `LinkageError`
   * (`reader-classpath`).
   */
  private def prepare(
      sources: OpeningLoadSources,
      config: OpeningLoadConfig,
      targets: OpeningLoadTargets
  ): Either[OpeningLoadReport, Prepared] =
    try {
      val upstream: Either[Vector[String], (SummaryUpstream, VerifiedBenchmarkPairs)] =
        for {
          g <- UpstreamRuns
            .grouping(sources.grouping, config.upstream)
            .left
            .map(_.map("grouping: " + _))
          c <- UpstreamRuns
            .curated(sources.curated, config.upstream)
            .left
            .map(_.map("curated: " + _))
          b <- BenchmarkPairsReader.read(
            sources.benchmark,
            sources.benchmarkFile,
            config.benchmarkMaxBytes
          )
        } yield (UpstreamRuns.upstream(g, c, Some(b)), b)
      upstream match {
        case Left(problems) =>
          val rs = problems.map(p =>
            Rejection(VerificationCheck.UpstreamRead, "upstream", StoreError.Refused(p))
          )
          Left(OpeningLoadReport(None, None, None, None, problems, rs, refusedAll(rs)))
        case Right((up, bp)) =>
          val verification = LoadVerifier.verify(
            sources.run,
            sources.loads,
            KeyValueReader.readOnly(targets.ledger),
            Some(VerificationContext(config.settings, config.parquet.bounds, Some(up)))
          )
          val recorded = verification.verified.flatMap(v =>
            Option(v.runManifest.parameters).flatMap(
              _.get(scholarchain.core.AnalysisValidation.AnalysisManifestKeys.BenchmarkPairsDigest)
            )
          )
          // CR-P7-11 M1: one run per store set: any store owned by another run stops them all.
          val foreign: Option[StoreError] =
            (verification.runId, verification.partManifestSha256) match {
              case (Some(r), Some(sha)) =>
                LoadLedger
                  .checkOwners(targets.ledger, r, sha, TargetStore.values.toVector)
                  .left
                  .toOption
              case _ => None
            }
          Right(Prepared(verification, bp.digest, recorded, foreign))
      }
    } catch {
      case e: LinkageError =>
        Left(
          classpathRefused(
            StoreError.Refused(
              "a class failed to link while the inputs were read and verified (" + linkage(e) +
                "): a library the Parquet reader needs at run time is missing or incompatible " +
                "on this classpath; nothing was written"
            )
          )
        )
    }

  /**
   * SE-P7-14 (CR-P7-16 m1): one loader's `LinkageError` as that store's failure (see [[run]]).
   */
  private def linkGuarded[A](t: TargetStore)(
      body: => Either[StoreError, A]
  ): Either[StoreError, A] =
    try body
    catch {
      case e: LinkageError =>
        Left(
          StoreError.Permanent(
            s"${VerificationCheck.ReaderClasspath.tag}: the ${t.tag} loader failed to link a " +
              s"class (${linkage(e)}); this call's writes so far are counted in the outcome"
          )
        )
    }

  private def loadOne(
      report: VerificationReport,
      t: TargetStore,
      config: OpeningLoadConfig,
      targets: OpeningLoadTargets,
      foreign: Option[StoreError]
  ): StoreLoadOutcome = {
    val performed =
      DeferredCheck.appliesTo(t).filter(c => report.verified.exists(_.passedFor(t).contains(c)))
    val open = report.outstandingFor(t)
    val progress = new LoadProgress
    def outcome(
        status: StoreLoadStatus,
        written: Long = 0L,
        present: Long = 0L,
        counts: Vector[(String, Long)] = Vector.empty,
        rejections: Vector[Rejection] = Vector.empty,
        error: Option[StoreError] = None,
        rejected: Long = 0L
    ) =
      StoreLoadOutcome(
        t,
        status,
        performed.map(_.tag),
        open.map(_.tag),
        written,
        present,
        error.count(_.isInstanceOf[StoreError.Conflict]).toLong,
        counts,
        rejections,
        error,
        rejected
      )

    /** (written, alreadyPresent, skipped, counts, rejected records) of a finished load. */
    type Done = (Long, Long, Boolean, Vector[(String, Long)], Long)

    /** The production gate, then the one-run-per-store-set preflight, then the loader. */
    def gate(
        f: LoadPermit => Either[StoreError, Done]
    ): Either[Vector[Rejection], Either[StoreError, Done]] =
      LoadGate.proceed(report, t)(p => foreign.toLeft(()).flatMap(_ => linkGuarded(t)(f(p))))
    val attempt: Either[Vector[Rejection], Either[StoreError, Done]] = t match {
      case TargetStore.NeptuneBulkLoad =>
        gate(p =>
          GraphLoad
            .load(p, targets.graph, targets.ledger, targets.clock, progress)
            .map(r =>
              (
                r.written,
                r.alreadyPresent,
                r.skipped,
                Vector("vertices" -> r.vertices.toLong, "edges" -> r.edges.toLong),
                0L
              )
            )
        )
      case TargetStore.DynamoDb =>
        gate(p =>
          KeyValueLoad
            .load(
              p,
              config.parquet.bounds,
              config.keyValueLimits,
              targets.keyValues,
              targets.ledger,
              targets.clock,
              progress
            )
            .map(r =>
              (
                r.written,
                r.alreadyPresent,
                r.skipped,
                ("items" -> r.items.toLong) +: r.rowsByRole.toVector.sortBy(_._1).map {
                  case (role, n) => s"role:$role" -> n.toLong
                },
                0L
              )
            )
        )
      case TargetStore.S3 =>
        gate(p =>
          ObjectLoad
            .load(
              p,
              config.layout,
              targets.objects,
              targets.keyValues,
              targets.ledger,
              targets.clock,
              progress
            )
            .map(r =>
              (
                r.written,
                r.alreadyPresent,
                r.skipped,
                Vector(
                  "objects" -> r.objects.toLong,
                  "run-files" -> r.runFiles.toLong,
                  "registry-rows" -> r.registryRows.toLong
                ),
                0L
              )
            )
        )
      case TargetStore.S3Vectors =>
        gate(p =>
          VectorLoad
            .load(
              p,
              config.parquet.bounds,
              config.layout,
              config.vectors,
              VectorLoadTargets(
                targets.vectors,
                targets.objects,
                targets.keyValues,
                targets.ledger,
                targets.embedder,
                targets.clock,
                targets.sleeper,
                targets.jitter
              ),
              progress
            )
            .map(r =>
              (
                r.written,
                r.alreadyPresent,
                r.skipped,
                Vector("parts" -> p.items.size.toLong, "rows" -> r.rows.toLong) ++
                  r.indexes.flatMap(i =>
                    Vector(
                      s"index:${i.index}:routed" -> i.routed.toLong,
                      s"index:${i.index}:written" -> i.written,
                      s"index:${i.index}:present" -> i.alreadyPresent,
                      s"index:${i.index}:rejected" -> i.rejected.toLong,
                      s"index:${i.index}:stored" -> i.stored.toLong
                    )
                  ) ++ Vector(
                    "text-objects" -> r.textObjects.toLong,
                    "text-written" -> r.textWritten,
                    "text-present" -> r.textPresent,
                    "registry-rows" -> r.registryRows.toLong,
                    "rejected" -> r.rejected.size.toLong,
                    "embedded" -> r.embedded
                  ),
                r.rejected.size.toLong
              )
            )
        )
    }
    attempt match {
      case Left(rs) => outcome(StoreLoadStatus.Refused, rejections = rs)
      case Right(Left(e)) =>
        // CR-P7-10 m2: the partial counts this call actually made; CR-P7-11 m3: with its writes to
        // the other stores (text objects, registry rows, rejected-record evidence) by name.
        outcome(
          StoreLoadStatus.Failed,
          progress.written,
          progress.alreadyPresent,
          counts = progress.sideCounts,
          error = Some(e),
          rejected = progress.rejected
        )
      case Right(Right((w, a, skipped, counts, rejected))) =>
        val status = if (skipped) StoreLoadStatus.Skipped else StoreLoadStatus.Loaded
        outcome(status, w, a, counts, rejected = rejected)
    }
  }
}
