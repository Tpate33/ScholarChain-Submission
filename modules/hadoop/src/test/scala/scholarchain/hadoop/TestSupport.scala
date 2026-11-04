package scholarchain.hadoop

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import scala.collection.immutable.SortedMap

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileUtil

import scholarchain.core.{RunId, RunManifest, SchemaVersions, SnapshotRef, SourceType}

/**
 * Test fixtures. Every file a test writes lives under `modules/hadoop/target/test-work/` (git
 * ignored). Snapshot tokens are the manifest snapshot IDs of `datasets/dataset-manifest.yaml`.
 */
object TestSupport {

  lazy val repoRoot: JPath = {
    var dir = new File(".").getCanonicalFile.toPath
    while (
      dir != null && !(Files.isDirectory(dir.resolve("datasets/samples")) &&
        Files.isRegularFile(dir.resolve("build.sbt")))
    ) dir = dir.getParent
    require(dir != null, "repository root (build.sbt + datasets/samples) not found")
    dir
  }

  def sample(rel: String): JPath = repoRoot.resolve("datasets/samples").resolve(rel)

  def readSample(rel: String): String =
    new String(Files.readAllBytes(sample(rel)), StandardCharsets.UTF_8)

  /** A fresh, empty directory under target/test-work. */
  def workDir(name: String): JPath = {
    val d = repoRoot.resolve("modules/hadoop/target/test-work").resolve(name)
    if (Files.exists(d)) FileUtil.fullyDelete(d.toFile)
    Files.createDirectories(d)
    d
  }

  /** Hadoop configuration for the local job runner, with all scratch space under `work`. */
  def localConf(work: JPath): Configuration = {
    val c = new Configuration()
    c.set("fs.defaultFS", "file:///")
    c.set("mapreduce.framework.name", "local")
    c.set("hadoop.tmp.dir", work.resolve("hadoop-tmp").toString)
    c
  }

  val snapshots: Vector[SnapshotRef] = Vector(
    SnapshotRef(SourceType.Dblp, "dblp-2026-10-03"),
    SnapshotRef(SourceType.OpenAlex, "openalex-2026-10-14"),
    SnapshotRef(SourceType.Crossref, "crossref-public-data-file-2026-03"),
    SnapshotRef(SourceType.Orcid, "ORCID_2025_10")
  )

  /** Parameters of the local bounded pipeline (test values, not production choices). */
  val baseParams: Map[String, String] = Map(
    JobParams.DblpRecordTypes -> "article,inproceedings",
    JobParams.DblpYearRange -> "*",
    JobParams.DblpVenues -> "*",
    JobParams.DblpEncoding -> "UTF-8",
    JobParams.DblpMaxRecordBytes -> "1048576",
    JobParams.OrcidMaxRecordBytes -> "1048576",
    JobParams.RejectMaxExcerptChars -> "256",
    JobParams.TeamSources -> "dblp",
    JobParams.TeamMaxSize -> "100",
    JobParams.ReducersNormalize -> "3",
    JobParams.ReducersExactIds -> "2",
    JobParams.ReducersTeams -> "2",
    JobParams.CodecIntermediate -> "org.apache.hadoop.io.compress.DefaultCodec",
    JobParams.CodecOutput -> "org.apache.hadoop.io.compress.GzipCodec"
  )

  /**
   * Blocking with the REAL entity-resolution keys (the bounded local pipeline). maxBlockSize 4 is a
   * test value small enough that the samples exercise refinement over several levels.
   */
  val blockingParams: Map[String, String] = Map(
    JobParams.BlockingKeySourceClass -> classOf[jobs.EntityResolutionKeySource].getName,
    JobParams.BlockingKeySourceVersion -> scholarchain.entityresolution.EntityResolution.blockingKeysVersion,
    JobParams.BlockingMaxBlockSize -> "4",
    JobParams.BlockingOversizePolicyKey -> "drop",
    JobParams.ReducersBlocking -> "2"
  )

  val candidateParams: Map[String, String] = Map(
    JobParams.CandidatesFeatureVersion -> scholarchain.entityresolution.EntityResolution.featuresVersion,
    JobParams.ReducersCandidates -> "2"
  )

  /** Blocking with a fixture key source (plumbing tests only). */
  def fixtureBlocking(cls: Class[? <: jobs.BlockingKeySource]): Map[String, String] =
    blockingParams ++ Map(
      JobParams.BlockingKeySourceClass -> cls.getName,
      JobParams.BlockingKeySourceVersion -> jobs.BlockingKeySource.load(cls.getName).version
    )

  def manifest(params: Map[String, String], attempt: Int = 1): RunManifest =
    RunManifest(
      schemaVersion = SchemaVersions.RunManifest,
      stage = "hadoop.pipeline",
      codeVersion = "0123456789abcdef0123456789abcdef01234567",
      configDigest = "a" * 64,
      inputSnapshots = snapshots,
      parameters = SortedMap.from(params),
      attempt = attempt
    )

  val runId: String = RunId.of(manifest(baseParams)).fold(e => sys.error(e.message), identity)

  def run(source: SourceType): RunContext =
    RunContext(runId, snapshots.find(_.source == source).get.snapshot, source, 256)

  def conf(params: Map[String, String]): Configuration = {
    val c = new Configuration()
    params.foreach { case (k, v) => c.set(k, v) }
    c
  }
}

/**
 * Fixture blocking keys for exercising the blocking plumbing only. NOT a blocking policy (that is
 * entity-resolution-engineer's): level 0 = first character of the normalized name, level 1 = the
 * whole normalized name.
 */
final class FixtureKeySource extends scholarchain.hadoop.jobs.BlockingKeySource {
  def version: String = "fixture-keys.v1"
  def levels: Int = 2
  def researcherKeys(
      work: scholarchain.core.WorkRecord,
      mention: scholarchain.core.AuthorMention
  ): Vector[String] =
    Vector(mention.normalizedName.take(1), mention.normalizedName)
  def profileKeys(profile: scholarchain.core.SourceAuthorProfile): Vector[String] =
    profile.displayName.toVector.map(_.toLowerCase(java.util.Locale.ROOT)).flatMap { n =>
      Vector(n.take(1), n)
    }
  def workKeys(work: scholarchain.core.WorkRecord): Vector[String] =
    Vector(work.normalizedTitle.take(1), work.normalizedTitle)
}

/** Fixture with a single level (no fallback): oversize blocks can only be dropped. */
final class SingleLevelKeySource extends scholarchain.hadoop.jobs.BlockingKeySource {
  def version: String = "fixture-single-level.v1"
  def levels: Int = 1
  def researcherKeys(
      work: scholarchain.core.WorkRecord,
      mention: scholarchain.core.AuthorMention
  ): Vector[String] = Vector(mention.normalizedName.take(1))
  def profileKeys(profile: scholarchain.core.SourceAuthorProfile): Vector[String] =
    profile.displayName.toVector.map(_.toLowerCase(java.util.Locale.ROOT).take(1))
  def workKeys(work: scholarchain.core.WorkRecord): Vector[String] =
    Vector(work.normalizedTitle.take(1))
}
