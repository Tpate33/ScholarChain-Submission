package scholarchain.storage

import java.nio.file.{Path, Paths}

import scholarchain.core.config.*
import scholarchain.storage.load.{
  SummaryUpstream,
  UpstreamBounds,
  UpstreamRuns,
  VerificationContext
}
import scholarchain.storage.model.SourceBounds
import scholarchain.storage.parquet.ParquetReadBounds

/**
 * The analysis configuration of the fixture run (SE-P7-06; SE-P7-07: read from the human-copied
 * fixture file `phase06-analysis-settings/analysis-dev.conf`, byte-identical to spark-engineer's
 * committed dev configuration, sha256 pinned by [[UpstreamFixtureIntegritySuite]]). It is parsed by
 * the core [[ConfigLoader.resolve]] with an empty environment, exactly as the analysis run read it,
 * and accepted as the run's configuration only because its config digest equals the `configDigest`
 * the fixture run manifest records (a SHA-256 binding, not a claim). SE-P7-06 rebuilt the same
 * values in test code; no difference remains (the file sets no `community.randomSeed`, so the
 * rebuilt `None` was the file's value; the digests are equal).
 */
object RunSettingsFixture {

  /** The fixture run manifest's `configDigest`. */
  val RunConfigDigest = "e37c32b115c82cbedc56e2407b8a322b52505eabbd800dfb5dade372b47800df"

  /** The pinned SHA-256 of the settings fixture (human copy record, 2026-11-20). */
  val SettingsSha256 = "1f408973834c532fb2d5d3931aaf8d0446d7afbb06c2bcf55878f9257306b6a1"

  lazy val settingsFile: Path =
    Paths.get(
      getClass.getResource("/fixtures/phase06-analysis-settings/analysis-dev.conf").toURI
    )

  lazy val config: ScholarChainConfig =
    ConfigLoader
      .resolve(Vector(settingsFile.toString -> Fixtures.text(settingsFile)), Map.empty)
      .fold(e => throw new AssertionError(e.map(_.message).mkString("; ")), identity)

  lazy val settings: AnalysisRunSettings =
    AnalysisRunSettings.from(config).fold(e => throw new AssertionError(e.toString), identity)

  /** Parquet read bounds for the fixture (test configuration values). */
  val parquetBounds: ParquetReadBounds =
    ParquetReadBounds.of(1024 * 1024, 8L * 1024 * 1024, 100000L, 8 * 1024 * 1024).toOption.get

  /** The context without upstream records: the upstream-dependent checks stay NOT performed. */
  lazy val context: VerificationContext = VerificationContext(settings, parquetBounds, None)
}

/**
 * The upstream fixtures (human copy, 2026-11-20): the Phase 6 dev grouping run and the curated
 * Hadoop run, read ONLY through the production readers [[UpstreamRuns.grouping]] and
 * [[UpstreamRuns.curated]] over [[scholarchain.storage.local.LocalRunSource]]: nothing is built or
 * marked by hand.
 */
object UpstreamFixture {
  val GroupingHex = "84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2"
  val CuratedHex = "2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa"

  private def resource(name: String): Path =
    Paths.get(getClass.getResource(s"/fixtures/$name").toURI)

  lazy val groupingDir: Path = resource(s"phase06-grouping-dev/run=$GroupingHex")

  /**
   * The curated run is read from the SOURCE tree, not the test classpath: sbt's resource copy
   * leaves out hidden files (its default exclude filter), so the copy under `target/` lacks the
   * Hadoop `.crc` sidecars, and the production reader (correctly) refuses it. Read only.
   */
  lazy val curatedDir: Path = {
    val rel = s"fixtures/phase06-curated-dev/snapshot=dblp-2026-10-03/run=$CuratedHex"
    val cwd = Paths.get(sys.props("user.dir")).toAbsolutePath
    // SE-P7-13 item 1: the third candidate serves a sibling sbt project whose forked tests run in
    // its own base directory (for example tests/), once build-engineer maps storage's test
    // classes and resources into it.
    Vector(
      cwd.resolve("modules/storage/src/test/resources").resolve(rel),
      cwd.resolve("src/test/resources").resolve(rel),
      cwd.resolveSibling("modules").resolve("storage/src/test/resources").resolve(rel)
    ).find(p => java.nio.file.Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
      .getOrElse(throw new AssertionError(s"the curated fixture is not under $cwd"))
  }

  /** Test configuration values chosen for the fixtures. */
  val groupingBounds: SourceBounds = SourceBounds.of(20, 4L * 1024 * 1024, 3).toOption.get
  val curatedBounds: SourceBounds = SourceBounds.of(200, 4L * 1024 * 1024, 5).toOption.get
  val upstreamBounds: UpstreamBounds = UpstreamBounds.of(16L * 1024 * 1024, 100000).toOption.get

  lazy val grouping =
    UpstreamRuns
      .grouping(Fixtures.source(groupingDir, groupingBounds), upstreamBounds)
      .fold(e => throw new AssertionError(e.mkString("\n")), identity)

  lazy val curated =
    UpstreamRuns
      .curated(Fixtures.source(curatedDir, curatedBounds), upstreamBounds)
      .fold(e => throw new AssertionError(e.mkString("\n")), identity)

  /** Without the benchmark pair list (not in the fixtures): paths-run-chain stays open. */
  lazy val upstream: SummaryUpstream = UpstreamRuns.upstream(grouping, curated, None)

  lazy val context: VerificationContext =
    VerificationContext(
      RunSettingsFixture.settings,
      RunSettingsFixture.parquetBounds,
      Some(upstream)
    )
}
