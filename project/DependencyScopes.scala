import java.io.File

import sbt._
import sbt.internal.util.MessageOnlyException
import sbt.librarymanagement.{
  DependencyResolution,
  UnresolvedWarningConfiguration,
  UpdateConfiguration
}

/**
 * Dependency-scope check (work orders BE-P7-01, BE-P7-01 repair 01, BE-P7-05 and BE-P7-06). Owner:
 * build-engineer. Run by `check` as `verifyDependencyScopes`, before markTestRun.
 *
 * It inspects what sbt actually resolved, never the declared libraryDependencies:
 *   - the attributed entries (module IDs, else the jar path) of
 *     `storage / {Compile, Runtime, Test} / fullClasspath`,
 *     `rag / {Compile, Runtime, Test} / fullClasspath`, `services / Runtime / fullClasspath`,
 *     `hadoop / {Runtime, Test} / fullClasspath`, `sparkAnalytics / {Runtime, Test, SparkIT} /
 *     fullClasspath` and `tests / Test / fullClasspath`;
 *   - the source files and destinations of `services / Universal / mappings` and
 *     `services / Docker / mappings` (sbt-native-packager, the services packaging);
 *   - the module revisions of the watched library families (Jackson, Guava, Netty, HttpComponents,
 *     SLF4J, commons-*) across those classpaths (information only; sbt's Coursier update report
 *     carries no eviction details);
 *   - one resolution of the AWS SDK v2 BOM POM at the ledger version (existence only).
 *
 * It fails when Parquet or Hadoop is missing from storage or tests Test, when any
 * org.apache.parquet or org.apache.hadoop jar reaches rag (Compile, Runtime or Test) or services
 * (classpath or packaging), when any software.amazon.awssdk or com.amazonaws (SDK v1; CR-P7-01 F3)
 * jar reaches rag, when an AWS SDK module is missing from services (classpath or packaging), or
 * when any software.amazon.awssdk jar on the services runtime classpath differs from the ledger BOM
 * version.
 *
 * Native compression codecs (BE-P7-05, DU-P7-02; BE-P7-06, DU-P7-04). It fails when any native
 * codec jar (org.xerial.snappy, io.airlift:aircompressor, com.github.luben) is on storage Compile
 * or Runtime, on rag, on services (Runtime classpath, Universal or Docker packaging), or on hadoop
 * Test (decision H1: snappy-java excluded from the Hadoop client); when any native codec or
 * lz4-java jar is on hadoop or sparkAnalytics Runtime; unless storage Test, sparkAnalytics Test,
 * SparkIT and tests Test each hold exactly one org.xerial.snappy jar, and it is snappy-java at the
 * ledger `snappy-java` version (a second snappy jar, another version or another artifact of that
 * organization fails); when aircompressor or zstd-jni is on any of those four; and, while
 * [[Lz4ExcludedFromSparkTests]] holds (decision L1), when lz4-java is on sparkAnalytics Test,
 * SparkIT or tests Test (otherwise an INFO line records it as present and unused, R2).
 *
 * Netty (BE-P7-06, DU-P7-04; BE-P7-08, CR-P7-17 m2). A Netty jar is one whose organization is
 * `io.netty` or starts with `io.netty.` (for example `io.netty.incubator`), or, unattributed, whose
 * path holds `/io/netty/` or whose file name starts with `netty-`. Every io.netty jar on storage
 * Compile, Runtime and Test, services Runtime, Universal and Docker, sparkAnalytics Test and
 * SparkIT and tests Test must be at the ledger `netty` version, named `<artifact>-<netty>.jar` or
 * `<artifact>-<netty>-<classifier>.jar` with a classifier from [[NettyClassifiers]] (packaged:
 * `io.netty.` + that name), and of the allowed module set of its scope: on storage Runtime and Test
 * and on services exactly the ten modules of the AWS SDK's netty-nio-client ([[NettySdkModules]];
 * storage Compile at most those ten), on the Spark and tests classpaths exactly the sixteen of
 * [[NettySparkModules]]. No io.netty jar may be on rag, hadoop Test, or hadoop or sparkAnalytics
 * Runtime. Compatibility of the pinned Netty with the AWS SDK and with Spark is NOT asserted by any
 * upstream project: the only evidence is this build's own test runs.
 *
 * Hadoop client jar contents (BE-P7-06 addendum, DU-P7-05 option (d); coverage stated per CR-P7-17
 * m1). The task opens every resolved hadoop-client-api and hadoop-client-runtime jar on storage
 * Compile, Runtime and Test, hadoop Test, sparkAnalytics Test and SparkIT and tests Test, reads the
 * entry names of its central directory, and fails when an entry path contains `io/netty/` (at the
 * root, relocated under any prefix, or multi-release under `META-INF/versions/<n>/`), when an entry
 * starts with `META-INF/maven/io.netty/`, or when an entry is a Netty native library (a
 * `META-INF/native/` entry or a `.so`, `.jnilib`, `.dylib` or `.dll` file whose name contains
 * `netty`, any case). It also fails when either client jar is missing from one of those classpaths,
 * or a client jar is not a regular file, cannot be opened or read, or holds no entries. It does NOT
 * detect: Netty relocated to a path without `io/netty/`; Netty nested inside an inner jar; a
 * standalone `META-INF/io.netty.versions.properties`; corrupt entry data behind a valid central
 * directory (entry data is never read); or Hadoop distributions outside the build (EMR, the pinned
 * Spark distribution used by sparkSubmitLocal), which are not inspected.
 *
 * A built-in self-test first feeds deliberately broken synthetic inputs and fails unless each one
 * fails the check it targets (CR-P7-01 F4). The source-level rag boundary is checked by
 * verifyRagSourceImports (project/RagSourceImports.scala). INFO lines (watched-family revisions,
 * `codec-revisions`, and the storage-only jar lists) are information only and can never mask a
 * FAIL.
 *
 * Output: one line per check, sorted file names and counts, no absolute path, no time stamp. Files:
 * the Hadoop client self-test creates synthetic jars in the root project's
 * target/dependency-scopes-selftest/ and deletes that directory before it returns (it is also
 * deleted first if a previous run left it); the scan itself only reads jars. Evaluating the
 * packaging mappings builds the project jars under each project's target/, and resolution uses
 * sbt's dependency cache.
 */
object DependencyScopes {
  val Task = "verifyDependencyScopes"

  val ParquetOrg = "org.apache.parquet"
  val HadoopOrg = "org.apache.hadoop"
  val AwsOrg = "software.amazon.awssdk"
  val ForbiddenOrgs: Seq[String] = Seq(ParquetOrg, HadoopOrg)

  /** The AWS SDK v2 service modules storage declares; services must receive each one. */
  val AwsModules: Seq[String] = Seq("dynamodb", "neptunedata", "s3", "s3vectors")

  /** Concrete production authentication/model transports, declared by services only. */
  val ServicesOnlyAwsModules: Seq[String] = Seq("secretsmanager", "bedrockruntime")

  /**
   * Native compression codec families (BE-P7-05, DU-P7-02): never on storage Compile or Runtime,
   * never on rag or services (classpath or packaging); on storage Test only snappy-java, exactly
   * once, at the ledger `snappy-java` version. Each family is matched by module ID, by Maven-layout
   * repository path, by native-packager file name, or by jar file name prefix.
   */
  final case class Codec(label: String, org: String, name: Option[String], filePrefix: String) {
    private def pathMark: String = orgPath(org) + name.map(_ + "/").getOrElse("")
    private def packagedPrefix: String = org + name.map("." + _ + "-").getOrElse(".")
    def jar(j: Jar): Boolean =
      (j.org == org && name.forall(_ == j.name)) || j.path.contains(pathMark) ||
        j.fileName.startsWith(filePrefix)
    def mapped(m: Mapped): Boolean =
      m.isJar && (m.destinationName.startsWith(packagedPrefix) || m.source.contains(pathMark) ||
        m.destinationName.startsWith(filePrefix))
  }
  val SnappyOrg = "org.xerial.snappy"
  val SnappyName = "snappy-java"
  val AircompressorOrg = "io.airlift"
  val AircompressorName = "aircompressor"
  val ZstdOrg = "com.github.luben"
  val ZstdName = "zstd-jni"
  val Snappy = Codec("org.xerial.snappy", SnappyOrg, None, SnappyName + "-")
  val Aircompressor =
    Codec(
      "io.airlift:aircompressor",
      AircompressorOrg,
      Some(AircompressorName),
      AircompressorName + "-"
    )
  val Zstd = Codec("com.github.luben", ZstdOrg, None, ZstdName + "-")
  val NativeCodecs: Seq[Codec] = Seq(Snappy, Aircompressor, Zstd)
  // BE-P7-06 (DU-P7-03 R2, DU-P7-04 L1): lz4-java. The file-name prong also catches the relocated
  // at.yawk.lz4:lz4-java coordinates.
  val Lz4Org = "org.lz4"
  val Lz4Name = "lz4-java"
  val Lz4 = Codec("org.lz4:lz4-java", Lz4Org, Some(Lz4Name), Lz4Name + "-")
  val RuntimeCodecs: Seq[Codec] = NativeCodecs :+ Lz4

  /**
   * Decision L1 (BE-P7-06): lz4-java is excluded from spark-sql (and from the Hadoop client and
   * parquet-hadoop on tests Test), because the Spark test codec is Snappy (SP-P7-01). build.sbt
   * reads this flag for its exclusions, and the check enforces the outcome. false = R2 (present,
   * unused).
   */
  val Lz4ExcludedFromSparkTests: Boolean = true

  // --- Netty (BE-P7-06, DU-P7-04) ----------------------------------------------------------------
  val NettyOrg = "io.netty"

  /** A Netty-family organization outside io.netty itself (self-test input only). */
  val NettyIncubatorOrg = "io.netty.incubator"
  val NettyIncubatorName = "netty-incubator-codec-native-quic"

  /** The io.netty modules of the AWS SDK v2 netty-nio-client (storage and services). */
  val NettySdkModules: Seq[String] = Seq(
    "netty-buffer",
    "netty-codec",
    "netty-codec-http",
    "netty-codec-http2",
    "netty-common",
    "netty-handler",
    "netty-resolver",
    "netty-transport",
    "netty-transport-classes-epoll",
    "netty-transport-native-unix-common"
  )

  /** The io.netty modules of Spark 3.5 (sparkAnalytics Test, SparkIT; tests Test). */
  val NettySparkModules: Seq[String] = (NettySdkModules ++ Seq(
    "netty-all",
    "netty-codec-socks",
    "netty-handler-proxy",
    "netty-transport-classes-kqueue",
    "netty-transport-native-epoll",
    "netty-transport-native-kqueue"
  )).sorted

  /** Native-transport classifiers accepted on an io.netty jar name. */
  val NettyClassifiers: Seq[String] =
    Seq("linux-aarch_64", "linux-riscv64", "linux-x86_64", "osx-aarch_64", "osx-x86_64")

  /** Library families whose evictions are printed (information only). */
  val WatchedFamilies: Seq[String] = Seq(
    "com.fasterxml.jackson",
    "com.google.guava",
    "commons-codec",
    "commons-logging",
    "io.netty",
    "org.apache.httpcomponents",
    "org.slf4j"
  )

  /** One resolved jar: module coordinates when sbt attributed them, and the jar file name. */
  final case class Jar(org: String, name: String, rev: String, fileName: String, path: String) {
    def coordinates: String = s"$org:$name:$rev"
  }

  /** One packaging mapping: the source file and its destination inside the package. */
  final case class Mapped(source: String, destination: String) {
    def isJar: Boolean = destination.endsWith(".jar")
    def destinationName: String = destination.split('/').last
  }

  final case class Required(org: String, name: String, rev: String) {
    def coordinates: String = s"$org:$name:$rev"
  }

  final case class Inputs(
      storageCompile: Seq[Jar],
      storageRuntime: Seq[Jar],
      storageTest: Seq[Jar],
      ragCompile: Seq[Jar],
      ragRuntime: Seq[Jar],
      ragTest: Seq[Jar],
      servicesRuntime: Seq[Jar],
      servicesUniversal: Seq[Mapped],
      servicesDocker: Seq[Mapped],
      // BE-P7-06: the hadoop, sparkAnalytics and tests classpaths.
      hadoopRuntime: Seq[Jar],
      hadoopTest: Seq[Jar],
      sparkRuntime: Seq[Jar],
      sparkTest: Seq[Jar],
      sparkItTest: Seq[Jar],
      testsTest: Seq[Jar]
  )

  /** The ledger versions the checks compare against. */
  final case class Pins(
      aws: String,
      parquet: String,
      hadoop: String,
      snappy: String,
      netty: String
  )

  final case class Line(pass: Boolean, info: Boolean, id: String, detail: String) {
    def render: String = {
      val tag = if (info) "INFO" else if (pass) "PASS" else "FAIL"
      s"$Task: [$tag] $id: $detail"
    }
  }

  private def sep(p: String): String = p.replace(File.separatorChar, '/')

  /** Classpath entries: module ID from sbt's attributes, else no coordinates (project output). */
  def jars(classpath: Seq[Attributed[File]]): Seq[Jar] =
    classpath.map { a =>
      val f = a.data
      a.get(Keys.moduleID.key) match {
        case Some(m) => Jar(m.organization, m.name, m.revision, f.getName, sep(f.getPath))
        case None => Jar("", "", "", f.getName, sep(f.getPath))
      }
    }

  def mappings(ms: Seq[(File, String)]): Seq[Mapped] =
    ms.map { case (f, d) => Mapped(sep(f.getPath), sep(d)) }

  private def orgPath(org: String): String = "/" + org.replace('.', '/') + "/"

  /** A classpath jar of a forbidden organization (by module ID, or by repository path). */
  def forbiddenJar(j: Jar): Boolean =
    ForbiddenOrgs.exists(o => j.org == o || j.path.contains(orgPath(o)))

  /** The AWS SDK for Java v1 organization (CR-P7-01 F3): never on rag either. */
  val AwsV1Org = "com.amazonaws"
  val SdkOrgs: Seq[String] = Seq(AwsOrg, AwsV1Org)

  /** A classpath jar of the AWS SDK v2 or v1 (by module ID, or by repository path). */
  def sdkJar(j: Jar): Boolean =
    SdkOrgs.exists(o => j.org == o || j.path.contains(orgPath(o)))

  /** A packaged jar of a forbidden organization (by package name or by source path). */
  def forbiddenMapped(m: Mapped): Boolean =
    m.isJar && ForbiddenOrgs.exists(o =>
      m.destinationName.startsWith(o + ".") || m.source.contains(orgPath(o))
    )

  /** The packaged jar of one module: Maven-layout source path or native-packager file name. */
  def mappedModule(m: Mapped, r: Required): Boolean =
    m.isJar && (m.source.endsWith(
      s"${orgPath(r.org)}${r.name}/${r.rev}/${r.name}-${r.rev}.jar"
    ) || m.destinationName == s"${r.org}.${r.name}-${r.rev}.jar")

  private def names(xs: Seq[String]): String =
    if (xs.isEmpty) "none" else xs.distinct.sorted.mkString(", ")

  private def present(id: String, cp: Seq[Jar], required: Seq[Required]): Line = {
    val missing =
      required.filterNot(r => cp.exists(j => j.org == r.org && j.name == r.name && j.rev == r.rev))
    val forbidden = cp.filter(forbiddenJar)
    Line(
      missing.isEmpty,
      info = false,
      id,
      s"required ${required.size}, found ${required.size - missing.size}, " +
        s"missing [${names(missing.map(_.coordinates))}]; org.apache.parquet/org.apache.hadoop " +
        s"jars ${forbidden.map(_.fileName).distinct.size}: ${names(forbidden.map(_.fileName))}"
    )
  }

  private def absentCp(id: String, cp: Seq[Jar]): Line = {
    val bad = cp.filter(forbiddenJar)
    Line(
      bad.isEmpty,
      info = false,
      id,
      s"org.apache.parquet/org.apache.hadoop jars ${bad.map(_.fileName).distinct.size}: " +
        s"${names(bad.map(_.fileName))} (classpath entries ${cp.size})"
    )
  }

  private def absentMapped(id: String, ms: Seq[Mapped]): Line = {
    val bad = ms.filter(forbiddenMapped)
    Line(
      bad.isEmpty,
      info = false,
      id,
      s"org.apache.parquet/org.apache.hadoop jars ${bad.size}: " +
        s"${names(bad.map(_.destinationName))} (packaged jars ${ms.count(_.isJar)})"
    )
  }

  private def noSdk(id: String, cp: Seq[Jar]): Line = {
    val bad = cp.filter(sdkJar)
    Line(
      bad.isEmpty,
      info = false,
      id,
      s"$AwsOrg/$AwsV1Org jars ${bad.map(_.fileName).distinct.size}: " +
        s"${names(bad.map(_.fileName))} " +
        s"(classpath entries ${cp.size})"
    )
  }

  private def codecNames(codecs: Seq[Codec]): String = codecs.map(_.label).mkString("/")

  /** No jar of the given codec families on a classpath. */
  private def noCodecCp(id: String, cp: Seq[Jar], codecs: Seq[Codec]): Line = {
    val bad = cp.filter(j => codecs.exists(_.jar(j)))
    Line(
      bad.isEmpty,
      info = false,
      id,
      s"${codecNames(codecs)} jars ${bad.map(_.fileName).distinct.size}: " +
        s"${names(bad.map(_.fileName))} (classpath entries ${cp.size})"
    )
  }

  /** No packaged jar of the native codec families. */
  private def noCodecMapped(id: String, ms: Seq[Mapped]): Line = {
    val bad = ms.filter(m => NativeCodecs.exists(_.mapped(m)))
    Line(
      bad.isEmpty,
      info = false,
      id,
      s"${codecNames(NativeCodecs)} jars ${bad.size}: " +
        s"${names(bad.map(_.destinationName))} (packaged jars ${ms.count(_.isJar)})"
    )
  }

  /** Exactly one org.xerial.snappy jar, and it is snappy-java at the ledger version. */
  private def snappyOnly(id: String, cp: Seq[Jar], snappyVersion: String): Line = {
    val found = cp.filter(Snappy.jar)
    val ok = found.size == 1 && found.forall(j =>
      j.org == SnappyOrg && j.name == SnappyName && j.rev == snappyVersion
    )
    Line(
      ok,
      info = false,
      id,
      s"required exactly one $SnappyOrg:$SnappyName:$snappyVersion (ledger snappy-java); " +
        s"$SnappyOrg jars ${found.size}: " +
        s"${names(found.map(j => if (j.org.nonEmpty) j.coordinates else j.fileName))}"
    )
  }

  /**
   * The native codec coordinates each labelled classpath resolved (information only), so the
   * absence on storage Compile/Runtime and the one Test snappy-java are visible next to the
   * projects outside this check's scope (hadoop, sparkAnalytics, tests).
   */
  def codecRevisions(classpaths: Seq[(String, Seq[Jar])]): Line = {
    val rendered = classpaths.map { case (label, cp) =>
      val hits = cp.filter(j => RuntimeCodecs.exists(_.jar(j)))
      s"$label=[${names(hits.map(j => if (j.org.nonEmpty) j.coordinates else j.fileName))}]"
    }
    Line(pass = true, info = true, "codec-revisions", rendered.mkString("; "))
  }

  /** L1 not taken (R2): lz4-java stays on the Spark and tests Test classpaths, unused. */
  private def lz4PresentInfo(classpaths: Seq[(String, Seq[Jar])]): Line = {
    val rendered = classpaths.map { case (label, cp) =>
      s"$label=[${names(
          cp.filter(Lz4.jar).map(j => if (j.org.nonEmpty) j.coordinates else j.fileName)
        )}]"
    }
    Line(
      pass = true,
      info = true,
      "lz4-java-present-unused",
      "lz4-java present, unused (R2, not accepted): " + rendered.mkString("; ")
    )
  }

  // --- Netty (BE-P7-06) ----------------------------------------------------------------------------

  /** The Netty family (CR-P7-17 m2): `io.netty` and every `io.netty.*` organization. */
  def nettyOrg(org: String): Boolean = org == NettyOrg || org.startsWith(NettyOrg + ".")

  /**
   * A Netty-family jar: by module ID (organization `io.netty` or `io.netty.*`), or (unattributed)
   * by repository path or jar file name.
   */
  def nettyJar(j: Jar): Boolean =
    nettyOrg(j.org) ||
      (j.org.isEmpty && (j.path.contains(orgPath(NettyOrg)) || j.fileName.startsWith("netty-")))

  /** A packaged io.netty jar: by native-packager file name, by source path, or by jar file name. */
  def nettyMapped(m: Mapped): Boolean =
    m.isJar && (m.destinationName.startsWith(NettyOrg + ".") || m.source.contains(
      orgPath(NettyOrg)
    ) || m.destinationName.startsWith("netty-"))

  /** The accepted file names of one io.netty module at the ledger version. */
  def nettyFileNames(prefix: String, module: String, netty: String): Seq[String] =
    s"$prefix$module-$netty.jar" +: NettyClassifiers.map(c => s"$prefix$module-$netty-$c.jar")

  private def nettyDetail(
      found: Int,
      modules: Seq[String],
      revs: Seq[String],
      allowed: Seq[String],
      exact: Boolean,
      netty: String,
      bad: Seq[String],
      missing: Seq[String]
  ): String =
    s"io.netty jars $found, revisions [${names(revs)}], modules ${modules.size}; required " +
      s"${if (exact) "exactly" else "at most"} the ${allowed.size} allowed modules at ledger netty " +
      s"$netty; not allowed, off-version or misnamed [${names(bad)}]; missing [${names(missing)}]"

  /**
   * Every io.netty jar at the ledger version, well named, of the allowed modules (all if exact).
   */
  private def nettyCp(
      id: String,
      cp: Seq[Jar],
      allowed: Seq[String],
      exact: Boolean,
      netty: String
  ): Line = {
    val found = cp.filter(nettyJar)
    val bad = found.filterNot(j =>
      j.org == NettyOrg && allowed.contains(j.name) && j.rev == netty &&
        nettyFileNames("", j.name, netty).contains(j.fileName)
    )
    val modules = found.filter(_.org == NettyOrg).map(_.name).distinct.sorted
    val missing = if (exact) allowed.filterNot(modules.contains) else Nil
    Line(
      bad.isEmpty && missing.isEmpty,
      info = false,
      id,
      nettyDetail(
        found.size,
        modules,
        found.map(_.rev).filter(_.nonEmpty),
        allowed,
        exact,
        netty,
        bad.map(j => if (j.org.nonEmpty) s"${j.coordinates} (${j.fileName})" else j.fileName),
        missing
      )
    )
  }

  /** The packaged io.netty jars: each `io.netty.<allowed module>-<netty>[-<classifier>].jar`. */
  private def nettyPackaged(
      id: String,
      ms: Seq[Mapped],
      allowed: Seq[String],
      netty: String
  ): Line = {
    val accepted: Map[String, String] =
      allowed.flatMap(m => nettyFileNames(NettyOrg + ".", m, netty).map(_ -> m)).toMap
    val found = ms.filter(nettyMapped)
    val bad = found.filterNot(f => accepted.contains(f.destinationName))
    val modules = found.flatMap(f => accepted.get(f.destinationName)).distinct.sorted
    val missing = allowed.filterNot(modules.contains)
    Line(
      bad.isEmpty && missing.isEmpty,
      info = false,
      id,
      nettyDetail(
        found.size,
        modules,
        Nil,
        allowed,
        exact = true,
        netty,
        bad.map(_.destinationName),
        missing
      )
    )
  }

  /** No io.netty jar on a classpath. */
  private def noNettyCp(id: String, cp: Seq[Jar]): Line = {
    val bad = cp.filter(nettyJar)
    Line(
      bad.isEmpty,
      info = false,
      id,
      s"io.netty jars ${bad.map(_.fileName).distinct.size}: ${names(bad.map(_.fileName))} " +
        s"(classpath entries ${cp.size})"
    )
  }

  private def awsCp(id: String, cp: Seq[Jar], required: Seq[Required]): Line = {
    val found =
      required.filter(r => cp.exists(j => j.org == r.org && j.name == r.name && j.rev == r.rev))
    Line(
      found.size == required.size,
      info = false,
      id,
      s"required ${required.size}, found ${found.size}: ${names(found.map(_.coordinates))}; " +
        s"missing [${names(required.diff(found).map(_.coordinates))}]"
    )
  }

  private def awsMapped(id: String, ms: Seq[Mapped], required: Seq[Required]): Line = {
    val found = required.flatMap(r => ms.find(mappedModule(_, r)).map(m => r -> m))
    Line(
      found.size == required.size,
      info = false,
      id,
      s"required ${required.size}, found ${found.size}: " +
        s"${names(found.map(_._2.destinationName))}; " +
        s"missing [${names(required.diff(found.map(_._1)).map(_.coordinates))}]"
    )
  }

  private def awsAligned(id: String, cp: Seq[Jar], bomVersion: String): Line = {
    val sdk = cp.filter(_.org == AwsOrg)
    val off = sdk.filter(_.rev != bomVersion)
    Line(
      sdk.nonEmpty && off.isEmpty,
      info = false,
      id,
      s"$AwsOrg jars ${sdk.map(_.name).distinct.size}, all at BOM $bomVersion: ${off.isEmpty}; " +
        s"off-version [${names(off.map(_.coordinates))}]"
    )
  }

  /** Every check on the given inputs, in a fixed order. */
  def evaluate(in: Inputs, pins: Pins, lz4Excluded: Boolean = Lz4ExcludedFromSparkTests) = {
    val awsVersion = pins.aws
    val snappyVersion = pins.snappy
    val netty = pins.netty
    val storageRequired = Seq(
      Required(ParquetOrg, "parquet-hadoop", pins.parquet),
      Required(HadoopOrg, "hadoop-client-api", pins.hadoop),
      Required(HadoopOrg, "hadoop-client-runtime", pins.hadoop)
    )
    val aws = (AwsModules ++ ServicesOnlyAwsModules).map(Required(AwsOrg, _, awsVersion))
    // BE-P7-06: the Spark-world test classpaths (sparkAnalytics Test and SparkIT, tests Test).
    val sparkWorld = Seq(
      "spark-test" -> in.sparkTest,
      "sparkit-test" -> in.sparkItTest,
      "tests-test" -> in.testsTest
    )
    val sparkWorldCodecs = sparkWorld.flatMap { case (label, cp) =>
      Seq(
        snappyOnly(s"$label-snappy-java-ledger-only", cp, snappyVersion),
        noCodecCp(s"$label-no-aircompressor-zstd-jni", cp, Seq(Aircompressor, Zstd))
      ) ++ (if (lz4Excluded) Seq(noCodecCp(s"$label-no-lz4-java", cp, Seq(Lz4))) else Nil)
    }
    val lz4Info = if (lz4Excluded) Nil else Seq(lz4PresentInfo(sparkWorld))
    val nettyChecks = Seq(
      nettyCp("storage-compile-netty-ledger", in.storageCompile, NettySdkModules, false, netty),
      nettyCp("storage-runtime-netty-ledger", in.storageRuntime, NettySdkModules, true, netty),
      nettyCp("storage-test-netty-ledger", in.storageTest, NettySdkModules, true, netty),
      nettyCp("services-runtime-netty-ledger", in.servicesRuntime, NettySdkModules, true, netty),
      nettyPackaged(
        "services-universal-netty-ledger",
        in.servicesUniversal,
        NettySdkModules,
        netty
      ),
      nettyPackaged("services-docker-netty-ledger", in.servicesDocker, NettySdkModules, netty),
      nettyCp("spark-test-netty-ledger", in.sparkTest, NettySparkModules, true, netty),
      nettyCp("sparkit-test-netty-ledger", in.sparkItTest, NettySparkModules, true, netty),
      nettyCp("tests-test-netty-ledger", in.testsTest, NettySparkModules, true, netty),
      noNettyCp("rag-compile-no-netty", in.ragCompile),
      noNettyCp("rag-runtime-no-netty", in.ragRuntime),
      noNettyCp("rag-test-no-netty", in.ragTest),
      noNettyCp("hadoop-runtime-no-netty", in.hadoopRuntime),
      noNettyCp("hadoop-test-no-netty", in.hadoopTest),
      noNettyCp("spark-runtime-no-netty", in.sparkRuntime)
    )
    val projectCodecs = Seq(
      // Decision H1: snappy-java excluded from the Hadoop client on hadoop.
      noCodecCp("hadoop-test-no-native-codecs", in.hadoopTest, NativeCodecs),
      noCodecCp("hadoop-runtime-no-codecs", in.hadoopRuntime, RuntimeCodecs),
      noCodecCp("spark-runtime-no-codecs", in.sparkRuntime, RuntimeCodecs)
    )
    def missingFrom(cp: Seq[Jar]) = in.storageRuntime
      .filter(j => j.org.nonEmpty && !cp.exists(_.coordinates == j.coordinates))
    val storageOnly = missingFrom(in.servicesRuntime)
    val notOnRag = missingFrom(in.ragRuntime)
    Seq(
      present("storage-compile-parquet-hadoop", in.storageCompile, storageRequired),
      present("storage-runtime-parquet-hadoop", in.storageRuntime, storageRequired),
      present("storage-test-parquet-hadoop", in.storageTest, storageRequired),
      // BE-P7-06 (SE-P7-13): the cross-module loader tests need the Parquet reader on tests Test.
      present("tests-test-parquet-hadoop", in.testsTest, storageRequired),
      absentCp("rag-compile-no-parquet-hadoop", in.ragCompile),
      absentCp("rag-runtime-no-parquet-hadoop", in.ragRuntime),
      absentCp("rag-test-no-parquet-hadoop", in.ragTest),
      absentCp("services-runtime-no-parquet-hadoop", in.servicesRuntime),
      absentMapped("services-universal-no-parquet-hadoop", in.servicesUniversal),
      absentMapped("services-docker-no-parquet-hadoop", in.servicesDocker),
      awsCp("services-runtime-aws-sdk", in.servicesRuntime, aws),
      awsMapped("services-universal-aws-sdk", in.servicesUniversal, aws),
      awsMapped("services-docker-aws-sdk", in.servicesDocker, aws),
      awsAligned("services-runtime-aws-sdk-bom-aligned", in.servicesRuntime, awsVersion),
      noSdk("rag-compile-no-aws-sdk", in.ragCompile),
      noSdk("rag-runtime-no-aws-sdk", in.ragRuntime),
      noSdk("rag-test-no-aws-sdk", in.ragTest),
      // BE-P7-05 (DU-P7-02): native compression codecs.
      noCodecCp("storage-compile-no-native-codecs", in.storageCompile, NativeCodecs),
      noCodecCp("storage-runtime-no-native-codecs", in.storageRuntime, NativeCodecs),
      snappyOnly("storage-test-snappy-java-ledger-only", in.storageTest, snappyVersion),
      noCodecCp("storage-test-no-aircompressor-zstd-jni", in.storageTest, Seq(Aircompressor, Zstd)),
      noCodecCp("rag-compile-no-native-codecs", in.ragCompile, NativeCodecs),
      noCodecCp("rag-runtime-no-native-codecs", in.ragRuntime, NativeCodecs),
      noCodecCp("rag-test-no-native-codecs", in.ragTest, NativeCodecs),
      noCodecCp("services-runtime-no-native-codecs", in.servicesRuntime, NativeCodecs),
      noCodecMapped("services-universal-no-native-codecs", in.servicesUniversal),
      noCodecMapped("services-docker-no-native-codecs", in.servicesDocker)
    ) ++ projectCodecs ++ sparkWorldCodecs ++ nettyChecks ++ lz4Info ++ Seq(
      Line(
        pass = true,
        info = true,
        "storage-runtime-not-on-rag-runtime",
        s"on storage Runtime but not rag Runtime ${notOnRag.map(_.fileName).distinct.size}: " +
          names(notOnRag.map(_.fileName))
      ),
      Line(
        pass = true,
        info = true,
        "storage-runtime-only-jars",
        s"on storage Runtime but not services Runtime ${storageOnly.map(_.fileName).distinct.size}: " +
          names(storageOnly.map(_.fileName))
      )
    )
  }

  /**
   * Resolved revisions of the watched library families across labelled classpaths (information
   * only), one line per family. sbt's Coursier update report carries no eviction details (its
   * `details` are empty in this build), so conflicts are shown as the revision each classpath
   * actually resolved: `name=rev` when every classpath agrees, else `name=rev[labels]|rev[labels]`.
   */
  def familyRevisions(classpaths: Seq[(String, Seq[Jar])]): Seq[Line] = {
    val entries = for {
      (label, cp) <- classpaths
      j <- cp
      if j.org.nonEmpty
      family <- WatchedFamilies.find(j.org.startsWith)
    } yield (family, s"${j.org}:${j.name}", j.rev, label)
    val divergent = entries.groupBy(_._2).count(_._2.map(_._3).distinct.size > 1)
    val perFamily = WatchedFamilies.map { family =>
      val inFamily = entries.filter(_._1 == family)
      val rendered = inFamily.groupBy(_._2).toSeq.sortBy(_._1).map { case (module, es) =>
        val byRev = es.groupBy(_._3).toSeq.sortBy(_._1)
        if (byRev.size == 1) s"$module=${byRev.head._1}"
        else
          s"$module=" + byRev
            .map { case (rev, rs) => s"$rev[${rs.map(_._4).distinct.sorted.mkString(",")}]" }
            .mkString("|")
      }
      Line(pass = true, info = true, s"revisions $family", names(rendered))
    }
    val labels = classpaths.map(_._1).mkString(", ")
    Line(
      pass = true,
      info = true,
      "revision-divergences",
      s"$divergent watched module(s) resolve to different revisions across: $labels"
    ) +: perFamily
  }

  // --- Self-test on synthetic inputs ------------------------------------------------------------

  private def jar(org: String, name: String, rev: String): Jar = {
    val file = s"$name-$rev.jar"
    Jar(org, name, rev, file, s"/cache/${org.replace('.', '/')}/$name/$rev/$file")
  }

  private def mapped(j: Jar): Mapped = Mapped(j.path, s"lib/${j.org}.${j.name}-${j.rev}.jar")

  /**
   * Failures of the self-test (empty when every broken input is detected), and the number of broken
   * inputs fed.
   */
  /** Matched by module ID only (CR-P7-14 n1): file name and path match no other prong. */
  private def idOnly(org: String, name: String, rev: String): Jar =
    Jar(org, name, rev, "x.jar", "/lib/x.jar")

  def selfTestProblems(pins: Pins): (Seq[String], Int) = {
    val aws = pins.aws
    val parquet = pins.parquet
    val hadoop = pins.hadoop
    val snappy = pins.snappy
    val netty = pins.netty
    val storageSide = Seq(
      jar(ParquetOrg, "parquet-hadoop", parquet),
      jar(HadoopOrg, "hadoop-client-api", hadoop),
      jar(HadoopOrg, "hadoop-client-runtime", hadoop)
    )
    val sdk = AwsModules.map(jar(AwsOrg, _, aws)) :+ jar(AwsOrg, "sdk-core", aws)
    val servicesSdk = sdk ++ ServicesOnlyAwsModules.map(jar(AwsOrg, _, aws))
    val ragSide = Seq(jar("com.lihaoyi", "upickle_3", "0.0.1"))
    val snappyLedger = jar(SnappyOrg, SnappyName, snappy)
    val snappyOld = jar(SnappyOrg, SnappyName, "0.0.1")
    val aircompressor = jar(AircompressorOrg, AircompressorName, "0.0.1")
    val zstd = jar(ZstdOrg, ZstdName, "0.0.1")
    val lz4 = jar(Lz4Org, Lz4Name, "0.0.1")
    // BE-P7-06: the netty sets at the ledger version (one native jar with a classifier).
    def nettyJars(modules: Seq[String], rev: String): Seq[Jar] = modules.map { m =>
      val j = jar(NettyOrg, m, rev)
      if (m == "netty-transport-native-epoll")
        j.copy(
          fileName = s"$m-$rev-linux-x86_64.jar",
          path = j.path.replace(".jar", "-linux-x86_64.jar")
        )
      else j
    }
    val sdkNetty = nettyJars(NettySdkModules, netty)
    val sparkNetty = nettyJars(NettySparkModules, netty)
    val servicesPackaged = (servicesSdk ++ sdkNetty).map(mapped)
    val good = Inputs(
      storageSide ++ sdk,
      storageSide ++ sdk ++ sdkNetty,
      storageSide ++ sdk ++ sdkNetty :+ snappyLedger,
      ragSide,
      ragSide,
      ragSide,
      servicesSdk ++ sdkNetty,
      servicesPackaged,
      servicesPackaged.map(m => m.copy(destination = "/opt/docker/" + m.destination)),
      hadoopRuntime = ragSide,
      hadoopTest = storageSide.filter(_.org == HadoopOrg) ++ ragSide,
      sparkRuntime = ragSide,
      sparkTest = storageSide ++ sparkNetty :+ snappyLedger,
      sparkItTest = storageSide ++ sparkNetty :+ snappyLedger,
      testsTest = storageSide ++ sdk ++ sparkNetty :+ snappyLedger
    )
    val sdkV1 = jar(AwsV1Org, "aws-java-sdk-s3", "0.0.1")
    // CR-P7-01 F4: each broken input names the check that must fail on it.
    val broken: Seq[(String, String, Inputs)] = Seq(
      (
        "parquet on rag runtime (Compile scope)",
        "rag-runtime-no-parquet-hadoop",
        good.copy(ragRuntime = good.ragRuntime ++ storageSide)
      ),
      (
        "SDK on rag compile (no rag exclusion)",
        "rag-compile-no-aws-sdk",
        good.copy(ragCompile = good.ragCompile ++ sdk)
      ),
      (
        "SDK on rag runtime (no rag exclusion)",
        "rag-runtime-no-aws-sdk",
        good.copy(ragRuntime = good.ragRuntime ++ sdk)
      ),
      (
        "SDK jar on rag test by path only",
        "rag-test-no-aws-sdk",
        good.copy(ragTest =
          good.ragTest :+ Jar("", "", "", "x-1.jar", "/cache/software/amazon/awssdk/x/1/x-1.jar")
        )
      ),
      (
        "SDK v1 on rag compile by module ID only (F3)",
        "rag-compile-no-aws-sdk",
        good.copy(ragCompile = good.ragCompile :+ idOnly(AwsV1Org, "aws-java-sdk-s3", "0.0.1"))
      ),
      (
        "SDK v1 jar on rag runtime by path only (F3)",
        "rag-runtime-no-aws-sdk",
        good.copy(ragRuntime =
          good.ragRuntime :+ Jar("", "", "", "y-1.jar", "/cache/com/amazonaws/y/1/y-1.jar")
        )
      ),
      (
        "SDK v1 on rag test by module ID (F3)",
        "rag-test-no-aws-sdk",
        good.copy(ragTest = good.ragTest :+ sdkV1)
      ),
      (
        "hadoop jar packaged by path only",
        "services-universal-no-parquet-hadoop",
        good.copy(servicesUniversal =
          good.servicesUniversal :+ Mapped("/cache/org/apache/hadoop/x/1/x-1.jar", "lib/x.jar")
        )
      ),
      (
        "hadoop in Docker mappings",
        "services-docker-no-parquet-hadoop",
        good.copy(servicesDocker =
          good.servicesDocker :+ mapped(jar(HadoopOrg, "hadoop-client-api", hadoop))
        )
      ),
      (
        "hadoop on services runtime",
        "services-runtime-no-parquet-hadoop",
        good.copy(servicesRuntime = good.servicesRuntime ++ storageSide)
      ),
      (
        "parquet missing from storage runtime (Provided scope)",
        "storage-runtime-parquet-hadoop",
        good.copy(storageRuntime = good.storageRuntime.filterNot(_.org == ParquetOrg))
      ),
      (
        "hadoop-client-runtime missing from storage test",
        "storage-test-parquet-hadoop",
        good.copy(storageTest = good.storageTest.filterNot(_.name == "hadoop-client-runtime"))
      ),
      (
        "s3vectors missing from services runtime",
        "services-runtime-aws-sdk",
        good.copy(servicesRuntime = good.servicesRuntime.filterNot(_.name == "s3vectors"))
      ),
      (
        "secretsmanager missing from services runtime",
        "services-runtime-aws-sdk",
        good.copy(servicesRuntime = good.servicesRuntime.filterNot(_.name == "secretsmanager"))
      ),
      (
        "bedrockruntime missing from services packaging",
        "services-universal-aws-sdk",
        good.copy(servicesUniversal =
          good.servicesUniversal.filterNot(_.destination.contains("bedrockruntime"))
        )
      ),
      (
        "neptunedata missing from services packaging",
        "services-universal-aws-sdk",
        good.copy(servicesUniversal =
          good.servicesUniversal.filterNot(_.destination.contains("neptunedata"))
        )
      ),
      (
        "dynamodb missing from Docker packaging",
        "services-docker-aws-sdk",
        good.copy(servicesDocker =
          good.servicesDocker.filterNot(_.destination.contains("dynamodb"))
        )
      ),
      (
        "SDK jar off the BOM version",
        "services-runtime-aws-sdk-bom-aligned",
        good.copy(servicesRuntime = good.servicesRuntime :+ jar(AwsOrg, "apache-client", "0.0.1"))
      ),
      (
        "hadoop-client-api at a version other than the ledger's on storage compile",
        "storage-compile-parquet-hadoop",
        good.copy(storageCompile =
          good.storageCompile.filterNot(_.name == "hadoop-client-api") :+
            jar(HadoopOrg, "hadoop-client-api", "0.0.1")
        )
      ),
      // BE-P7-05 (DU-P7-02): native codec families.
      (
        "snappy-java on storage compile (no exclusion on parquet-hadoop)",
        "storage-compile-no-native-codecs",
        good.copy(storageCompile = good.storageCompile :+ snappyOld)
      ),
      (
        "aircompressor on storage runtime by repository path only",
        "storage-runtime-no-native-codecs",
        good.copy(storageRuntime =
          good.storageRuntime :+ Jar(
            "",
            "",
            "",
            "a-1.jar",
            "/cache/io/airlift/aircompressor/1/a-1.jar"
          )
        )
      ),
      (
        "zstd-jni on storage runtime by module ID only",
        "storage-runtime-no-native-codecs",
        good.copy(storageRuntime = good.storageRuntime :+ idOnly(ZstdOrg, ZstdName, "0.0.1"))
      ),
      (
        "snappy-java missing from storage test",
        "storage-test-snappy-java-ledger-only",
        good.copy(storageTest = good.storageTest.filterNot(_.org == SnappyOrg))
      ),
      (
        "snappy-java on storage test at a version other than the ledger's",
        "storage-test-snappy-java-ledger-only",
        good.copy(storageTest = good.storageTest.filterNot(_.org == SnappyOrg) :+ snappyOld)
      ),
      (
        "two snappy-java jars on storage test (ledger and transitive)",
        "storage-test-snappy-java-ledger-only",
        good.copy(storageTest = good.storageTest :+ snappyOld)
      ),
      (
        "aircompressor on storage test by module ID only",
        "storage-test-no-aircompressor-zstd-jni",
        good.copy(storageTest =
          good.storageTest :+ idOnly(AircompressorOrg, AircompressorName, "0.0.1")
        )
      ),
      (
        "zstd-jni on storage test by file name only",
        "storage-test-no-aircompressor-zstd-jni",
        good.copy(storageTest =
          good.storageTest :+ Jar("", "", "", "zstd-jni-1.jar", "/lib/zstd-jni-1.jar")
        )
      ),
      (
        "snappy-java on rag compile",
        "rag-compile-no-native-codecs",
        good.copy(ragCompile = good.ragCompile :+ snappyLedger)
      ),
      (
        "aircompressor on rag runtime",
        "rag-runtime-no-native-codecs",
        good.copy(ragRuntime = good.ragRuntime :+ aircompressor)
      ),
      (
        "zstd-jni on rag test by repository path only",
        "rag-test-no-native-codecs",
        good.copy(ragTest =
          good.ragTest :+ Jar("", "", "", "z-1.jar", "/cache/com/github/luben/zstd-jni/1/z-1.jar")
        )
      ),
      (
        "snappy-java on services runtime by file name only",
        "services-runtime-no-native-codecs",
        good.copy(servicesRuntime =
          good.servicesRuntime :+ Jar("", "", "", "snappy-java-1.jar", "/lib/snappy-java-1.jar")
        )
      ),
      (
        "aircompressor packaged by native-packager file name only",
        "services-universal-no-native-codecs",
        good.copy(servicesUniversal =
          good.servicesUniversal :+ Mapped("/x/a.jar", "lib/io.airlift.aircompressor-0.0.1.jar")
        )
      ),
      (
        "zstd-jni in Docker mappings by source path only",
        "services-docker-no-native-codecs",
        good.copy(servicesDocker =
          good.servicesDocker :+
            Mapped("/cache/com/github/luben/zstd-jni/1/z-1.jar", "/opt/docker/lib/z.jar")
        )
      ),
      (
        "snappy-java in Docker mappings by native-packager file name only",
        "services-docker-no-native-codecs",
        good.copy(servicesDocker =
          good.servicesDocker :+
            Mapped("/x/s.jar", s"/opt/docker/lib/$SnappyOrg.$SnappyName-$snappy.jar")
        )
      ),
      // BE-P7-06 (SE-P7-13): Parquet on tests Test, never on rag.
      (
        "parquet-hadoop missing from tests test",
        "tests-test-parquet-hadoop",
        good.copy(testsTest = good.testsTest.filterNot(_.org == ParquetOrg))
      ),
      (
        "parquet on rag compile",
        "rag-compile-no-parquet-hadoop",
        good.copy(ragCompile = good.ragCompile ++ storageSide)
      ),
      (
        "hadoop jar on rag test by path only",
        "rag-test-no-parquet-hadoop",
        good.copy(ragTest =
          good.ragTest :+ Jar("", "", "", "h-1.jar", "/cache/org/apache/hadoop/h/1/h-1.jar")
        )
      ),
      // BE-P7-06 (DU-P7-04): codecs on hadoop, sparkAnalytics and tests.
      (
        "snappy-java on hadoop test (H1 exclusion missing)",
        "hadoop-test-no-native-codecs",
        good.copy(hadoopTest = good.hadoopTest :+ snappyOld)
      ),
      (
        "aircompressor on hadoop runtime",
        "hadoop-runtime-no-codecs",
        good.copy(hadoopRuntime = good.hadoopRuntime :+ aircompressor)
      ),
      (
        "lz4-java on spark runtime by file name only",
        "spark-runtime-no-codecs",
        good.copy(sparkRuntime =
          good.sparkRuntime :+ Jar("", "", "", "lz4-java-1.jar", "/lib/lz4-java-1.jar")
        )
      ),
      (
        "snappy-java on spark test at Spark's own version",
        "spark-test-snappy-java-ledger-only",
        good.copy(sparkTest = good.sparkTest.filterNot(_.org == SnappyOrg) :+ snappyOld)
      ),
      (
        "two snappy-java jars on sparkit test",
        "sparkit-test-snappy-java-ledger-only",
        good.copy(sparkItTest = good.sparkItTest :+ snappyOld)
      ),
      (
        "snappy-java missing from tests test",
        "tests-test-snappy-java-ledger-only",
        good.copy(testsTest = good.testsTest.filterNot(_.org == SnappyOrg))
      ),
      (
        "aircompressor on spark test by module ID only",
        "spark-test-no-aircompressor-zstd-jni",
        good.copy(sparkTest =
          good.sparkTest :+ idOnly(AircompressorOrg, AircompressorName, "0.0.1")
        )
      ),
      (
        "zstd-jni on sparkit test by file name only",
        "sparkit-test-no-aircompressor-zstd-jni",
        good.copy(sparkItTest =
          good.sparkItTest :+ Jar("", "", "", "zstd-jni-1.jar", "/lib/zstd-jni-1.jar")
        )
      ),
      (
        "zstd-jni on tests test by repository path only",
        "tests-test-no-aircompressor-zstd-jni",
        good.copy(testsTest =
          good.testsTest :+ Jar("", "", "", "z-1.jar", "/cache/com/github/luben/zstd-jni/1/z-1.jar")
        )
      ),
      // BE-P7-06 (DU-P7-04): Netty at the ledger version, allowed modules only.
      (
        "netty-handler at the SDK's own version on storage runtime",
        "storage-runtime-netty-ledger",
        good.copy(storageRuntime =
          good.storageRuntime.filterNot(_.name == "netty-handler") :+
            jar(NettyOrg, "netty-handler", "0.0.1.Final")
        )
      ),
      (
        "netty jar by repository path only on storage compile",
        "storage-compile-netty-ledger",
        good.copy(storageCompile =
          good.storageCompile :+ Jar("", "", "", "n-1.jar", "/cache/io/netty/n/1/n-1.jar")
        )
      ),
      (
        "netty-codec-socks (outside the SDK set) on storage test",
        "storage-test-netty-ledger",
        good.copy(storageTest = good.storageTest :+ jar(NettyOrg, "netty-codec-socks", netty))
      ),
      (
        "netty-common missing from services runtime",
        "services-runtime-netty-ledger",
        good.copy(servicesRuntime = good.servicesRuntime.filterNot(_.name == "netty-common"))
      ),
      (
        "netty-buffer packaged at another version",
        "services-universal-netty-ledger",
        good.copy(servicesUniversal =
          good.servicesUniversal.filterNot(_.destination.contains("netty-buffer")) :+
            mapped(jar(NettyOrg, "netty-buffer", "0.0.1.Final"))
        )
      ),
      (
        "netty-codec-http2 missing from Docker packaging",
        "services-docker-netty-ledger",
        good.copy(servicesDocker =
          good.servicesDocker.filterNot(_.destination.contains("netty-codec-http2"))
        )
      ),
      (
        "native epoll jar with an unknown classifier on spark test",
        "spark-test-netty-ledger",
        good.copy(sparkTest =
          good.sparkTest :+ jar(NettyOrg, "netty-transport-native-epoll", netty)
            .copy(fileName = s"netty-transport-native-epoll-$netty-linux-s390x.jar")
        )
      ),
      (
        "netty-all missing from sparkit test",
        "sparkit-test-netty-ledger",
        good.copy(sparkItTest = good.sparkItTest.filterNot(_.name == "netty-all"))
      ),
      (
        "mixed netty versions on tests test (SPARK-53775 pattern)",
        "tests-test-netty-ledger",
        good.copy(testsTest = good.testsTest :+ jar(NettyOrg, "netty-common", "0.0.1.Final"))
      ),
      (
        "netty-tcnative (outside the Spark set) on tests test",
        "tests-test-netty-ledger",
        good.copy(testsTest = good.testsTest :+ jar(NettyOrg, "netty-tcnative-classes", netty))
      ),
      // BE-P7-08 (CR-P7-17 m2): the whole Netty family, not only organization io.netty.
      (
        "io.netty.incubator jar on services runtime (Netty family, not organization io.netty)",
        "services-runtime-netty-ledger",
        good.copy(servicesRuntime =
          good.servicesRuntime :+ jar(NettyIncubatorOrg, NettyIncubatorName, "0.0.1.Final")
        )
      ),
      (
        "io.netty.incubator jar on rag runtime by module ID only",
        "rag-runtime-no-netty",
        good.copy(ragRuntime =
          good.ragRuntime :+ idOnly(NettyIncubatorOrg, NettyIncubatorName, "0.0.1.Final")
        )
      ),
      (
        "netty on rag compile by module ID only",
        "rag-compile-no-netty",
        good.copy(ragCompile = good.ragCompile :+ idOnly(NettyOrg, "netty-common", netty))
      ),
      (
        "netty on rag runtime by file name only",
        "rag-runtime-no-netty",
        good.copy(ragRuntime = good.ragRuntime :+ Jar("", "", "", "netty-x-1.jar", "/lib/y.jar"))
      ),
      (
        "netty on rag test",
        "rag-test-no-netty",
        good.copy(ragTest = good.ragTest ++ sdkNetty)
      ),
      (
        "netty on hadoop test",
        "hadoop-test-no-netty",
        good.copy(hadoopTest = good.hadoopTest :+ jar(NettyOrg, "netty-common", netty))
      ),
      (
        "netty on hadoop runtime",
        "hadoop-runtime-no-netty",
        good.copy(hadoopRuntime = good.hadoopRuntime :+ jar(NettyOrg, "netty-buffer", netty))
      ),
      (
        "netty on spark runtime",
        "spark-runtime-no-netty",
        good.copy(sparkRuntime = good.sparkRuntime ++ sparkNetty)
      )
    ) ++ (if (!Lz4ExcludedFromSparkTests) Nil
          else
            Seq(
              (
                "lz4-java on spark test (L1 exclusion missing)",
                "spark-test-no-lz4-java",
                good.copy(sparkTest = good.sparkTest :+ lz4)
              ),
              (
                "lz4-java on sparkit test by module ID only",
                "sparkit-test-no-lz4-java",
                good.copy(sparkItTest = good.sparkItTest :+ idOnly(Lz4Org, Lz4Name, "0.0.1"))
              ),
              (
                "lz4-java on tests test by file name only (relocated coordinates)",
                "tests-test-no-lz4-java",
                good.copy(testsTest =
                  good.testsTest :+ Jar("", "", "", "lz4-java-1.jar", "/cache/at/yawk/lz4/x.jar")
                )
              )
            ))
    def eval(in: Inputs) = evaluate(in, pins)
    val goodProblems = eval(good).filterNot(l => l.pass || l.info)
    val passProblem =
      if (goodProblems.isEmpty) Nil
      else Seq(s"well-formed synthetic input failed: ${goodProblems.map(_.id).mkString(", ")}")
    val knownIds = eval(good).map(_.id).toSet
    val undetected = broken.collect {
      case (label, id, in)
          if !knownIds.contains(id) ||
            !eval(in).exists(l => l.id == id && !l.pass && !l.info) =>
        val failed = eval(in).filterNot(l => l.pass || l.info)
        s"broken synthetic input not detected by $id: $label (failed instead: " +
          s"${names(failed.map(_.id))})"
    }
    (passProblem ++ undetected, broken.size)
  }

  // --- Hadoop client jar contents: bundled Netty (BE-P7-06 addendum, DU-P7-05 option (d)) ---------

  val HadoopClientArtifacts: Seq[String] = Seq("hadoop-client-api", "hadoop-client-runtime")
  private val HadoopClientFile = """^(hadoop-client-(?:api|runtime))-(.+)\.jar$""".r

  /** A Hadoop client jar (artifact, revision): by module ID, else by Maven path or file name. */
  def hadoopClient(j: Jar): Option[(String, String)] =
    if (j.org == HadoopOrg && HadoopClientArtifacts.contains(j.name)) Some(j.name -> j.rev)
    else if (j.org.isEmpty || j.org == HadoopOrg)
      j.fileName match {
        case HadoopClientFile(a, rev) => Some(a -> rev)
        case _ =>
          HadoopClientArtifacts
            .find(a => j.path.contains(s"${orgPath(HadoopOrg)}$a/"))
            .map(a => a -> j.path.split('/').dropRight(1).lastOption.getOrElse("?"))
      }
    else None

  /**
   * A jar entry that is a bundled Netty class or resource, ordinary or relocated: `io/netty/` at
   * the root or under any prefix (`org/apache/hadoop/shaded/io/netty/`, `*\/thirdparty/io/netty/`,
   * `META-INF/versions/<n>/io/netty/`), Netty's Maven metadata `META-INF/maven/io.netty/`, and any
   * native library or `META-INF/native/` entry whose name contains `netty` (any case).
   */
  def nettyEntry(name: String): Boolean = {
    val lower = name.toLowerCase(java.util.Locale.ROOT)
    name.startsWith("io/netty/") || name.contains("/io/netty/") ||
    name.startsWith("META-INF/maven/io.netty/") ||
    (lower.contains("netty") && (lower.startsWith("meta-inf/native/") || lower.endsWith(".so") ||
      lower.endsWith(".jnilib") || lower.endsWith(".dylib") || lower.endsWith(".dll")))
  }

  final case class JarScan(entries: Int, nettyEntries: Seq[String])

  /** Reads every entry name of a jar (read-only); Left when it cannot be opened or read fully. */
  def scanJar(f: File): Either[String, JarScan] =
    if (!f.isFile) Left(s"${f.getName}: not a regular file (cannot be opened)")
    else
      scala.util
        .Try {
          val z = new java.util.zip.ZipFile(f)
          try {
            val it = z.entries()
            var n = 0
            val hits = scala.collection.mutable.ListBuffer.empty[String]
            while (it.hasMoreElements) {
              val e = it.nextElement()
              n += 1
              if (nettyEntry(e.getName)) hits += e.getName
            }
            JarScan(n, hits.toList.sorted)
          } finally z.close()
        }
        .toEither
        .left
        .map(e => s"${f.getName}: cannot be read (${e.getClass.getSimpleName}: ${e.getMessage})")
        .flatMap(s => if (s.entries == 0) Left(s"${f.getName}: holds no entries") else Right(s))

  /**
   * One FAIL-capable line per classpath: both Hadoop client jars are expected on every inspected
   * classpath; each must open and contain no Netty entry. Plus one INFO line of the revisions.
   */
  def hadoopNettyLines(label: String, cp: Seq[Jar], files: Jar => File): Seq[Line] = {
    val clients = cp.flatMap(j => hadoopClient(j).map(ar => (j, ar._1, ar._2)))
    val scans = clients.map { case (j, a, rev) => (j, a, rev, scanJar(files(j))) }
    val missing = HadoopClientArtifacts.filterNot(a => clients.exists(_._2 == a))
    val unreadable = scans.collect { case (_, _, _, Left(m)) => m }
    val withNetty = scans.collect { case (j, a, rev, Right(s)) if s.nettyEntries.nonEmpty => j }
    val perJar = scans.map {
      case (j, a, rev, Right(s)) =>
        s"${j.fileName} ($a:$rev) entries ${s.entries} netty ${s.nettyEntries.size}" +
          (if (s.nettyEntries.isEmpty) ""
           else s" first [${s.nettyEntries.take(5).mkString(", ")}]")
      case (j, a, rev, Left(m)) => s"${j.fileName} ($a:$rev) UNREADABLE: $m"
    }
    Seq(
      Line(
        pass = true,
        info = true,
        s"hadoop-client-revisions-$label",
        names(clients.map { case (_, a, rev) => s"$a:$rev" })
      ),
      Line(
        missing.isEmpty && unreadable.isEmpty && withNetty.isEmpty,
        info = false,
        s"hadoop-client-no-bundled-netty-$label",
        s"expected ${HadoopClientArtifacts.mkString(" and ")}, missing [${names(missing)}]; " +
          s"jars ${clients.size}: ${if (perJar.isEmpty) "none" else perJar.sorted.mkString("; ")}"
      )
    )
  }

  /** The content scan on synthetic jars in `scratch` (under target/); Left lists what slipped. */
  def hadoopNettySelfTest(scratch: File): (Seq[String], Int) = {
    IO.delete(scratch)
    IO.createDirectory(scratch)
    try {
      def mk(file: String, entries: Seq[String]): File = {
        val f = new File(scratch, file)
        val out = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(f))
        try
          entries.foreach { e => out.putNextEntry(new java.util.zip.ZipEntry(e)); out.closeEntry() }
        finally out.close()
        f
      }
      val api = "hadoop-client-api-0.0.1.jar"
      val rt = "hadoop-client-runtime-0.0.1.jar"
      val clean = Seq("org/apache/hadoop/fs/FileSystem.class", "META-INF/MANIFEST.MF")
      val corrupt = new File(scratch, "corrupt-" + rt)
      IO.write(corrupt, "this is not a zip file")
      val unreadable = mk("unreadable-" + rt, clean)
      unreadable.setReadable(false, false)
      def cp(apiFile: File, rtFile: Option[File]): (Seq[Jar], Map[Jar, File]) = {
        val a = Jar(HadoopOrg, "hadoop-client-api", "0.0.1", api, "/c/" + api) -> apiFile
        // The runtime jar unattributed: found by file name, revision parsed from it.
        val r = rtFile.map(f => Jar("", "", "", rt, "/c/" + rt) -> f)
        val m = (a +: r.toSeq).toMap
        (m.keys.toSeq, m)
      }
      val cleanApi = mk("clean-" + api, clean)
      def withRt(entries: Seq[String], tag: String) = cp(cleanApi, Some(mk(s"$tag-$rt", entries)))
      val good = withRt(clean, "clean")
      val broken: Seq[(String, (Seq[Jar], Map[Jar, File]))] = Seq(
        "relocated Netty class" -> withRt(
          clean :+ "org/apache/hadoop/shaded/io/netty/Foo.class",
          "shaded"
        ),
        "ordinary Netty class" -> withRt(clean :+ "io/netty/Bar.class", "plain"),
        "Netty native library" -> withRt(
          clean :+ "META-INF/native/libnetty_transport_native_epoll_x86_64.so",
          "native"
        ),
        "Netty under a thirdparty relocation" -> withRt(
          clean :+ "org/apache/hadoop/thirdparty/io/netty/Baz.class",
          "thirdparty"
        ),
        "Netty multi-release class" -> withRt(
          clean :+ "META-INF/versions/11/io/netty/Q.class",
          "mr"
        ),
        "Netty Maven metadata" -> withRt(
          clean :+ "META-INF/maven/io.netty/netty-common/pom.xml",
          "maven"
        ),
        "corrupt jar" -> cp(cleanApi, Some(corrupt)),
        "unreadable jar" -> cp(cleanApi, Some(unreadable)),
        "missing jar file" -> cp(cleanApi, Some(new File(scratch, "absent-" + rt))),
        "hadoop-client-runtime absent from the classpath" -> cp(cleanApi, None),
        "no Hadoop client jar at all" -> (Seq.empty[Jar], Map.empty[Jar, File])
      )
      def failed(c: (Seq[Jar], Map[Jar, File])): Boolean =
        hadoopNettyLines("selftest", c._1, c._2).exists(l => !l.pass && !l.info)
      // A file the OS user can still read (for example when run as root) is not a valid case.
      val unreadableValid = !unreadable.canRead
      val problems =
        (if (failed(good)) Seq("clean synthetic Hadoop client jars failed") else Nil) ++
          (if (!unreadableValid) Seq("could not make the synthetic jar unreadable") else Nil) ++
          broken.collect { case (label, c) if !failed(c) => s"not detected: $label" }
      (problems, broken.size)
    } finally {
      new File(scratch, "unreadable-hadoop-client-runtime-0.0.1.jar").setReadable(true, false)
      IO.delete(scratch)
    }
  }

  /** Runs the self-test, then every check; prints one line per check; fails on any FAIL. */
  def run(
      in: Inputs,
      revisionLines: Seq[Line],
      bomLine: Line,
      pins: Pins,
      hadoopClasspaths: Seq[(String, Seq[Attributed[File]])],
      scratch: File,
      log: Logger
  ): Unit = {
    val (self, brokenCount) = selfTestProblems(pins)
    val selfLine = Line(
      self.isEmpty,
      info = false,
      "self-test",
      if (self.isEmpty)
        s"$brokenCount broken synthetic inputs each failed the intended check, 1 well-formed " +
          "input passed"
      else self.mkString("; ")
    )
    val (hSelf, hCount) = hadoopNettySelfTest(scratch)
    val hSelfLine = Line(
      hSelf.isEmpty,
      info = false,
      "hadoop-client-netty-self-test",
      if (hSelf.isEmpty)
        s"$hCount broken synthetic jar sets each failed (relocated, ordinary, thirdparty, " +
          "multi-release, Maven-metadata and native-library Netty entries; corrupt, unreadable and " +
          "missing jar files; a missing client jar; no client jar), 1 clean set passed"
      else hSelf.mkString("; ")
    )
    val hadoopLines = hadoopClasspaths.flatMap { case (label, cp) =>
      val byJar = cp.map(a => jars(Seq(a)).head -> a.data)
      hadoopNettyLines(label, byJar.map(_._1), byJar.toMap)
    }
    val lines = (selfLine +: hSelfLine +: bomLine +: evaluate(in, pins)) ++ hadoopLines ++
      revisionLines
    lines.foreach(l => if (l.pass || l.info) log.info(l.render) else log.error(l.render))
    val failed = lines.filterNot(l => l.pass || l.info)
    if (failed.nonEmpty)
      throw new MessageOnlyException(
        s"$Task: ${failed.size} check(s) failed: ${failed.map(_.id).mkString(", ")}"
      )
    log.info(s"$Task: OK, ${lines.count(l => l.pass && !l.info)} checks passed")
  }

  /**
   * Resolves the BOM POM through the build's resolver (Coursier). Coursier returns Left when the
   * POM does not exist at that version. A POM-packaged module has no classpath artifact, so the
   * Coursier report lists no module for it (observed: every configuration empty); success is the
   * Right result, and the line says so.
   */
  def bomLine(lm: DependencyResolution, awsVersion: String, log: Logger): Line = {
    val bom = (AwsOrg % "bom" % awsVersion).pomOnly()
    val module = lm.wrapDependencyInModule(bom)
    lm.update(module, UpdateConfiguration(), UnresolvedWarningConfiguration(), log) match {
      case Right(_) =>
        Line(
          pass = true,
          info = false,
          "aws-sdk-bom-resolved",
          s"$AwsOrg:bom:$awsVersion POM resolved by Coursier (no classpath artifact)"
        )
      case Left(w) =>
        Line(
          pass = false,
          info = false,
          "aws-sdk-bom-resolved",
          s"$AwsOrg:bom:$awsVersion unresolved: ${w.resolveException.getMessage}"
        )
    }
  }
}
