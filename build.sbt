// ScholarChain build definition. Owner: build-engineer.
// Every version comes from project/versions.properties via Ledger (project/Ledger.scala),
// resolved from the build base directory.
// The internal dependency graph mirrors CLAUDE.md "Module dependency boundaries" exactly.
// Environment variables (role law 7; the complete list):
//   - AWS_*: only the NAMES of inherited variables are used: blanked in forked test and
//     runMain JVMs (awsIsolationEnv) and removed from the local-run JVMs
//     (project/LocalRuns.scala). Values are never read.
//   - SCHOLARCHAIN_CFG_*: only the NAMES are checked, by goldSetEvalDev / goldSetEvalHeldout,
//     erDecisionGroupingDev and sparkIT, which refuse to run while any is set. Local-run JVMs
//     and the sparkIT JVM never inherit any SCHOLARCHAIN_*.
//   - SCHOLARCHAIN_CONFIG_FILES: never read by the build; the two evaluation tasks set it in
//     their forked JVM to the committed gold/entity-resolution/<split>-v1/er-config.conf, and
//     erDecisionGroupingDev to the committed modules/entity-resolution/local-runs/er-dev.conf
//     followed by its generated target/local-runs/dev/er-run-roots.conf.
//   - SCHOLARCHAIN_ROUTE_PROBE_MARKER: never read by the build; set by heldoutRouteProbe in
//     its synthetic child JVM only (random marker, see verifyHeldoutOutputRoute).
//   - PATH: used only to locate `git` (markTestRun and the local runs).
//   - GIT_*: only the NAMES are used: project/LocalRuns.scala removes every inherited GIT_*
//     variable from its git processes (so none can redirect the held-out checks), and
//     erDecisionGroupingDev and sparkIT also remove them from their forked JVMs. The
//     verifyHeldoutGuards and verifyErDevRunGuards fixtures set GIT_CONFIG_GLOBAL,
//     GIT_CONFIG_NOSYSTEM and GIT_CEILING_DIRECTORIES in their own git processes on a
//     temporary repository only.
//   - MUNIT_*: only the NAMES are used: sparkIT removes every inherited MUNIT_* (for example
//     MUNIT_FLAKY_OK) from its forked JVM, and its runner refuses to run while any is present.
//   - sparkSubmitLocal (BE-P6-09) passes sbt's environment to its spark-submit children minus,
//     by NAME, every AWS_*, SCHOLARCHAIN_*, GIT_*, MUNIT_*, SPARK_*, HADOOP_*, YARN_*, PYSPARK_*
//     and JAVA_TOOL_OPTIONS, _JAVA_OPTIONS, JDK_JAVA_OPTIONS, JAVA_OPTS, SBT_OPTS, CLASSPATH;
//     it sets JAVA_HOME, SPARK_HOME, SPARK_CONF_DIR, SPARK_LOCAL_IP=127.0.0.1 and
//     SPARK_WORKER_DIR for them, plus the AWS isolation variables. Its comparison JVM gets
//     sbt's environment minus the same prefixes and exact names, plus the AWS isolation
//     variables (CR-P6-27 m3). Values are never read.
// No other variable is read. (sparkIT and verifySparkItGuards also read the `user.home` JVM
// system property of sbt, not an environment variable, to resolve the archive root configured
// in project/spark-it.properties.)

// Build-policy guard (review F1). A strict val: it runs while sbt initializes this file
// to discover projects, before any subproject *.sbt file is evaluated, and aborts the
// load if any *.sbt file exists outside /build.sbt and /project/. See project/BuildGuard.scala.
val buildSourceGuard: Unit = BuildGuard.enforceAtLoad()

// --- Custom keys -----------------------------------------------------------------

lazy val versionLedger =
  settingKey[Ledger.Versions]("The parsed version ledger, resolved from the build base")
lazy val testOutcome =
  taskKey[TestMarker.Outcome]("Runs this project's tests and returns the raw outcome")
lazy val markTestRun =
  taskKey[File]("Runs all tests and writes .claude/.last-test-run only if they all pass")
lazy val verifyVersionLedger =
  taskKey[Unit]("Checks the version ledger, its tool-mandated mirrors, and literal versions")
lazy val verifyBuildSources =
  taskKey[Unit]("Fails if any *.sbt file exists outside /build.sbt and /project/")
// Fixed-shape local runs (BE-P5-02, BE-P5-04): no arguments; see project/LocalRuns.scala.
lazy val localPipeline =
  taskKey[Unit]("Refuses: run localPipelineDev or localPipelineHeldout")
lazy val localPipelineDev =
  taskKey[Unit]("Bounded local Hadoop pipeline, dev split (ErSplitPipelineMain dev)")
lazy val localPipelineHeldout =
  taskKey[Unit](
    "Bounded local Hadoop pipeline, held-out split (ErSplitPipelineMain heldout); plain batch " +
      "`sbt localPipelineHeldout` in an interactive Terminal only: refuses under sbt --client/sbtn, " +
      "an sbt server or shell, BSP/IDE, or redirected stdout/stderr, and on a D3(b)-unclean tree"
  )
lazy val goldSetEval =
  taskKey[Unit]("Refuses: run goldSetEvalDev or goldSetEvalHeldout")
lazy val goldSetEvalDev =
  taskKey[Unit](
    "Gold-set evaluation, dev split (gold/entity-resolution/dev-v1/): DEV diagnostics only"
  )
lazy val goldSetEvalHeldout =
  taskKey[Unit](
    "Gold-set evaluation, held-out split (gold/entity-resolution/heldout-v1/); human Terminal " +
      "only, as plain batch sbt: refuses under sbt --client/sbtn, an sbt server or shell, " +
      "BSP/IDE, or redirected stdout/stderr, and on a D3(b)-unclean tree"
  )
// Dev decision and grouping run (BE-P6-01): no arguments, dev split only; see LocalRuns.
lazy val erDecisionGroupingDev =
  taskKey[Unit](
    "Bounded dev-only ER decision run then grouping run (DecisionRunMain, GroupingRunMain) on the " +
      "complete localPipelineDev attempt, into target/local-runs/dev/er-{decisions,assignments}/"
  )
lazy val verifyErDevRunGuards =
  taskKey[Unit](
    "Tests the fixed arguments, paths, held-out refusals, curated-run selection, code-scope " +
      "check, preflight refusals and stage states of erDecisionGroupingDev on constants, " +
      "temporary directories and a temporary git repository"
  )
// Synthetic held-out output-route check (decisions-17/19), part of `check`. No key name may
// contain the held-out evaluation task's name (guard rule heldout-eval-human-only).
lazy val heldoutRouteProbe =
  taskKey[LocalRuns.RouteProbe](
    "Forks a synthetic child on the held-out output route and on the sbt-log route"
  )
lazy val verifyHeldoutOutputRoute =
  taskKey[Unit](
    "Fails unless held-out-route output is absent from every sbt-persisted file (with counter-test)"
  )
lazy val verifyHeldoutGuards =
  taskKey[Unit](
    "Tests the held-out launch and clean-tree refusals on synthetic signals and a temporary git repo"
  )
// Opt-in local Spark integration tests (BE-P6-03): no arguments; see project/SparkItRuns.scala.
lazy val sparkIT =
  taskKey[Unit](
    "Bounded, opt-in Spark integration tests (modules/spark/src/it/scala) over the verified dev " +
      "archive configured in project/spark-it.properties; output under modules/spark/target/spark-it/"
  )
lazy val verifySparkItGuards =
  taskKey[Unit](
    "Tests the sparkIT refusals, pass rule and SparkIT route/format wiring on constants, " +
      "temporary directories and the loaded build; never reads the archive"
  )
lazy val verifySparkItRunner =
  taskKey[Unit](
    "Forks the real sparkIT JUnit runner on the SparkIT classpath against compiled fixture " +
      "classes (pass, fail, ignore, assume, exit, mixed); never reads the archive"
  )
// Dependency scopes on resolved classpaths and packaging (BE-P7-01): see project/DependencyScopes.scala.
lazy val verifyDependencyScopes =
  taskKey[Unit](
    "Checks the resolved classpaths and services packaging: Parquet/Hadoop on storage (and tests " +
      "Test) only, the AWS SDK modules (one BOM version) on services and never on rag; native " +
      "codecs off storage Compile/Runtime, rag, services, hadoop Test and the job Runtimes, " +
      "snappy-java once at the ledger version on the storage, Spark and tests Test classpaths; " +
      "io.netty (and io.netty.*) at the ledger version and module set; opens every resolved " +
      "hadoop-client-api/-runtime jar on the storage Compile/Runtime/Test, hadoop Test, " +
      "sparkAnalytics Test/SparkIT and tests Test classpaths and fails on entry paths containing " +
      "io/netty/ (root, relocated under any prefix, multi-release), META-INF/maven/io.netty/ or " +
      "Netty native libraries; does not detect Netty relocated without io/netty/ in its path, " +
      "nested inside an inner jar, a standalone META-INF/io.netty.versions.properties, corrupt " +
      "entry data behind a valid central directory, or Hadoop outside the build (EMR, the pinned " +
      "Spark distribution); prints watched-family, codec and Hadoop client revisions; part of check"
  )
lazy val hadoopJobAssemblyCheck =
  taskKey[Unit](
    "Checks the hadoop job assembly holds no class under the forbidden prefixes (Spark, Hadoop, " +
      "Parquet, AWS SDK, logging, Netty, native codecs) and prints its sha256; run by packageJobs"
  )
// rag ports-only boundary in source (BE-P7-02, CR-P7-01 F1): see project/RagSourceImports.scala.
lazy val verifyRagSourceImports =
  taskKey[Unit](
    "Scans modules/rag/src for references to the AWS SDK (v1, v2), Parquet, Hadoop and the storage " +
      "adapter packages scholarchain.storage.aws and scholarchain.storage.parquet; part of check"
  )
// EMR Option A packaging and local runtime gate (BE-P6-09): see project/SparkSubmitLocal.scala.
lazy val sparkJobAssemblyCheck =
  taskKey[Unit](
    "Checks the sparkAnalytics job assembly (Main-Class; no Spark, Hadoop, AWS SDK, logging or " +
      "scala-library 2.13 class) and prints its sha256; run by packageJobs"
  )
lazy val sparkSubmitLocal =
  taskKey[Unit](
    "Opt-in: runs the pinned Apache Spark distribution's spark-submit with the job assembly over " +
      "the verified dev archive at local[2] and local-cluster[2,1,1024]; output under " +
      "target/spark-submit-local/; never part of check"
  )
lazy val verifySparkSubmitLocalGuards =
  taskKey[Unit](
    "Tests the sparkSubmitLocal pins, tar reader, cache, assembly, command and verdict rules on " +
      "constants and temporary directories; no network, no fork"
  )
// Bounded test logging (BE-P10-01): see project/TestLogging.scala.
lazy val testLoggingInputs =
  taskKey[TestLogging.ProjectInputs](
    "This project's SLF4J providers, Test javaOptions and resource directories"
  )
lazy val verifyTestLogging =
  taskKey[Unit](
    "Checks that forked Test JVMs log at WARN or less verbose: SLF4J provider selection and " +
      "configuration per project, the build-owned configurations, and a probe on the tests and " +
      "services Test classpaths (with counter-probe); part of check"
  )

// SparkIT: the integration-test configuration of sparkAnalytics. It extends Test (test classes and
// munit/JUnit are on its classpath) and has its own source directory modules/spark/src/it/.
// Extending Test does NOT bring the Provided jars: sbt resolves a configuration's classpath from
// `classpathConfiguration`, and only Compile, Test and Runtime are mapped to their internal
// configurations (Test -> TestInternal = Test + Optional + Provided); a custom configuration maps to
// itself, and sparkit -> test -> runtime -> compile never reaches Provided. BE-P6-05 therefore sets
// `SparkIT / classpathConfiguration := Configurations.TestInternal` below, so SparkIT compiles
// and forks against exactly the classpath Test uses (Provided Spark and its Hadoop client jars
// included). Compile and Runtime are untouched, so the job jars (assembly on Runtime) still
// exclude Provided.
// No routine command runs it: `test` and `sparkAnalytics/test` are Test-scoped, markTestRun reads
// Test/executeTests only, and the SparkIT routes test, testOnly, testQuick, executeTests, run,
// runMain, bgRun, bgRunMain, fgRun, fgRunMain, console, consoleQuick and consoleProject refuse.
// `check` format-checks its sources (sparkAnalytics/SparkIT/scalafmtCheck) and compiles them
// (sparkAnalytics/SparkIT/compile, BE-P6-05: no archive read, no fork, no test run). Only
// `sbt sparkIT` reads the archive and runs the suites.
lazy val SparkIT = config("sparkit").extend(Test)

ThisBuild / versionLedger := Ledger.load((ThisBuild / baseDirectory).value)
ThisBuild / organization := "scholarchain"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := versionLedger.value("scala")
ThisBuild / versionScheme := Some("early-semver")

Global / onChangedBuildSource := ReloadOnSourceChanges

// --- Dependencies (versions only from the ledger) -------------------------------

def scalaDep(org: String, name: String, key: String) =
  Def.setting(org %% name % versionLedger.value(key))
def javaDep(org: String, name: String, key: String) =
  Def.setting(org % name % versionLedger.value(key))

val munit = scalaDep("org.scalameta", "munit", "munit")
val upickle = scalaDep("com.lihaoyi", "upickle", "upickle")
val slf4jApi = javaDep("org.slf4j", "slf4j-api", "slf4j")
val logbackClassic = javaDep("ch.qos.logback", "logback-classic", "logback")
val typesafeConfig = javaDep("com.typesafe", "config", "typesafe-config")
val hadoopClientApi = javaDep("org.apache.hadoop", "hadoop-client-api", "hadoop")
val hadoopClientRuntime = javaDep("org.apache.hadoop", "hadoop-client-runtime", "hadoop")
val parquetHadoop = javaDep("org.apache.parquet", "parquet-hadoop", "parquet-hadoop")
val snappyJava =
  javaDep(DependencyScopes.SnappyOrg, DependencyScopes.SnappyName, "snappy-java")
// BE-P7-05 (DU-P7-02): native compression codecs kept off storage Compile/Runtime.
val nativeCodecExclusions = Seq(
  ExclusionRule(DependencyScopes.AircompressorOrg, DependencyScopes.AircompressorName),
  ExclusionRule(DependencyScopes.ZstdOrg, DependencyScopes.ZstdName),
  ExclusionRule(DependencyScopes.SnappyOrg, DependencyScopes.SnappyName)
)
// BE-P7-06 (DU-P7-04): decision L1, lz4-java excluded where the Spark test codec is Snappy
// (SP-P7-01); the flag lives in DependencyScopes so the check enforces the same outcome.
val lz4Exclusions =
  if (DependencyScopes.Lz4ExcludedFromSparkTests)
    Seq(ExclusionRule(DependencyScopes.Lz4Org, DependencyScopes.Lz4Name))
  else Nil
// spark-sql on sparkAnalytics (Provided) and tests (Test): aircompressor and zstd-jni (and lz4-java
// under L1) removed; Spark's own snappy-java stays and is pinned by dependencyOverrides below.
val sparkCodecExclusions = Seq(
  ExclusionRule(DependencyScopes.AircompressorOrg, DependencyScopes.AircompressorName),
  ExclusionRule(DependencyScopes.ZstdOrg, DependencyScopes.ZstdName)
) ++ lz4Exclusions
// The Hadoop client and parquet-hadoop on tests Test: every codec (Spark supplies snappy-java).
val testsCodecExclusions = nativeCodecExclusions ++ lz4Exclusions
// BE-P7-06 (DU-P7-04): every io.netty module of DependencyScopes.NettySparkModules at the ledger
// `netty` version. An override only changes the version of a module that is already resolved; it
// adds nothing and is not published. Classifier jars (native transports) follow their module.
val nettyOverrides = Def.setting {
  val netty = versionLedger.value("netty")
  DependencyScopes.NettySparkModules.map(module => DependencyScopes.NettyOrg % module % netty)
}
// AWS SDK for Java v2 service modules, all at the one BOM version (see the ledger).
val awsSdkModules = Def.setting {
  val bom = versionLedger.value("aws-sdk-v2.bom")
  DependencyScopes.AwsModules.map(module => DependencyScopes.AwsOrg % module % bom)
}
// Spark publishes no Scala 3 artifacts: use the _2.13 artifacts from Scala 3.
// Runtime target pending human decision (review F2) before Phase 6: see the ledger.
val sparkSql = Def.setting {
  ("org.apache.spark" %% "spark-sql" % versionLedger.value("spark"))
    .cross(CrossVersion.for3Use2_13)
}

// --- Shared settings ---------------------------------------------------------------

// Spark 3.5 and Hadoop 3.4 on Java 17 need these opens in forked local test JVMs.
val java17Opens = Seq(
  "java.lang",
  "java.lang.invoke",
  "java.lang.reflect",
  "java.io",
  "java.net",
  "java.nio",
  "java.util",
  "java.util.concurrent",
  "java.util.concurrent.atomic",
  "sun.nio.ch",
  "sun.nio.cs",
  "sun.security.action",
  "sun.util.calendar"
).map(p => s"--add-opens=java.base/$p=ALL-UNNAMED") :+ "-Djdk.reflect.useDirectMethodHandle=false"

// Best-effort AWS isolation for forked JVMs (tests, runMain, local runs). The network is NOT
// blocked. sbt can add or override variables of a forked JVM but cannot unset inherited ones,
// so every AWS_* variable present in sbt's environment (by name; values are not used) and the
// named credential, profile, role, container-credential, and endpoint variables are set to "".
// Per-service AWS_ENDPOINT_URL_<SERVICE> variables cannot all be enumerated; only those
// inherited by sbt are blanked. Config and credentials files point at nonexistent paths; IMDS
// is disabled. (The local runs additionally remove every inherited AWS_* first.)
def awsIsolationEnv(noAws: File): Map[String, String] = {
  val named = Seq(
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "AWS_PROFILE",
    "AWS_DEFAULT_PROFILE",
    "AWS_ROLE_ARN",
    "AWS_ROLE_SESSION_NAME",
    "AWS_WEB_IDENTITY_TOKEN_FILE",
    "AWS_CONTAINER_CREDENTIALS_FULL_URI",
    "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI",
    "AWS_CONTAINER_AUTHORIZATION_TOKEN",
    "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
    "AWS_ENDPOINT_URL"
  )
  val inherited = sys.env.keySet.filter(_.startsWith("AWS_"))
  (named ++ inherited).map(_ -> "").toMap ++ Map(
    "AWS_EC2_METADATA_DISABLED" -> "true",
    "AWS_CONFIG_FILE" -> (noAws / "config").getAbsolutePath,
    "AWS_SHARED_CREDENTIALS_FILE" -> (noAws / "credentials").getAbsolutePath
  )
}

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-release:17", "-Werror"),
  javacOptions ++= Seq("--release", "17"),
  libraryDependencies += munit.value % Test,
  // AWS isolation for forked test JVMs: see awsIsolationEnv (behaviour unchanged).
  Test / fork := true,
  Test / envVars ++= awsIsolationEnv(target.value / "no-aws"),
  testOutcome := TestMarker.Outcome(
    thisProject.value.id,
    (Test / executeTests).value,
    (Compile / sources).value.size,
    (Test / sources).value.size
  ),
  testLoggingInputs := TestLogging.ProjectInputs(
    thisProject.value.id,
    (Test / sources).value.size,
    TestLogging.providersOn((Test / fullClasspath).value),
    (Test / javaOptions).value,
    (Test / resourceDirectories).value,
    (Compile / resourceDirectories).value
  )
)

// BE-P10-01: bounded logging in a forked Test JVM (project/TestLogging.scala). Selects the SLF4J
// provider when `forceProvider` (two are on the classpath) and points the backend at the
// build-owned WARN configuration under project/test-logging/, unless the project's Test resources
// hold the owner's own logback-test.xml / log4j2-test.* (which then applies).
def boundedTestLogging(backend: TestLogging.Backend, forceProvider: Boolean) =
  Test / javaOptions ++= TestLogging.javaOptions(
    backend,
    (ThisBuild / baseDirectory).value,
    forceProvider,
    (Test / resourceDirectories).value
  )

// Fat job jars for EMR: Provided (EMR-supplied Hadoop/Spark) jars are excluded.
def jobAssembly(jarBase: String) = Seq(
  assembly / assemblyJarName := s"scholarchain-$jarBase-${version.value}.jar",
  assembly / assemblyMergeStrategy := {
    val default = (assembly / assemblyMergeStrategy).value
    (path: String) =>
      path match {
        case "module-info.class" => MergeStrategy.discard
        case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
        case other => default(other)
      }
  }
)

// Fixed-shape local runs (BE-P5-02, BE-P5-04). Each forks one JVM from the repository root;
// inputs, bounds, refusals, output routing, and exit-code messages are in project/LocalRuns.scala.
def pipelineRun(split: LocalRuns.Split) = Def.task {
  LocalRuns.localPipeline(
    split,
    (ThisBuild / baseDirectory).value,
    javaHome.value,
    // ErSplitPipelineMain is in the hadoop test tree; this classpath also carries the Provided
    // Hadoop jars, as `hadoop/Test/runMain` does. Same JVM options as Test / javaOptions.
    (hadoop / Test / fullClasspath).value.map(_.data),
    java17Opens,
    awsIsolationEnv(target.value / "no-aws"),
    LocalRuns.observeLaunch(state.value),
    target.all(ScopeFilter(inAnyProject)).value,
    streams.value.log
  )
}
def goldSetEvalRun(split: LocalRuns.Split) = Def.task {
  LocalRuns.goldSetEval(
    split,
    (ThisBuild / baseDirectory).value,
    javaHome.value,
    (entityResolution / Compile / fullClasspath).value.map(_.data),
    awsIsolationEnv(target.value / "no-aws"),
    sys.env,
    LocalRuns.observeLaunch(state.value),
    target.all(ScopeFilter(inAnyProject)).value,
    streams.value.log
  )
}

// --- Projects ----------------------------------------------------------------------

lazy val core = project
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "scholarchain-core",
    libraryDependencies ++= Seq(upickle.value, typesafeConfig.value)
  )

lazy val entityResolution = project
  .in(file("modules/entity-resolution"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-entity-resolution",
    // decisions-15 item 4: runMain (the human's Terminal generators and evaluation) forks a JVM
    // in the repository root, so sys.exit ends only that JVM and relative paths resolve from the
    // root. AWS isolation as in the tests; inherited SCHOLARCHAIN_* variables still reach it.
    Compile / run / fork := true,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
    Compile / run / envVars ++= awsIsolationEnv(target.value / "no-aws")
  )

lazy val hadoop = project
  .in(file("modules/hadoop"))
  .dependsOn(core, entityResolution)
  .settings(commonSettings)
  .settings(jobAssembly("hadoop-jobs"))
  .settings(
    name := "scholarchain-hadoop",
    // BE-P7-06 decision H1 (DU-P7-03/-04): snappy-java excluded from the Hadoop client; the hadoop
    // tests use DefaultCodec/GzipCodec. verifyDependencyScopes fails on any codec on hadoop Test.
    libraryDependencies ++= Seq(
      hadoopClientApi.value.excludeAll(
        ExclusionRule(DependencyScopes.SnappyOrg, DependencyScopes.SnappyName)
      ) % Provided,
      hadoopClientRuntime.value.excludeAll(
        ExclusionRule(DependencyScopes.SnappyOrg, DependencyScopes.SnappyName)
      ) % Provided
    ),
    Test / javaOptions ++= java17Opens,
    // decisions-15 item 4: Test/runMain already forks (Test / run / fork delegates to
    // Test / fork) with Test / javaOptions and Test / envVars; it now starts in the repository
    // root instead of modules/hadoop. Test working directories are unchanged.
    Test / run / baseDirectory := (ThisBuild / baseDirectory).value
  )

lazy val sparkAnalytics = project
  .in(file("modules/spark"))
  .dependsOn(core)
  .configs(SparkIT)
  .settings(commonSettings)
  .settings(jobAssembly("spark-jobs"))
  .settings(
    name := "scholarchain-spark-analytics",
    // BE-P7-06 (DU-P7-04): codec exclusions and the snappy-java and Netty pins. All are on the
    // Provided Spark tree only (Test, SparkIT and Compile/dependencyClasspath see them; the job
    // assembly, built from Runtime, contains none of them; sparkJobAssemblyCheck verifies).
    libraryDependencies += sparkSql.value.excludeAll(sparkCodecExclusions: _*) % Provided,
    dependencyOverrides ++= nettyOverrides.value :+ snappyJava.value,
    Test / javaOptions ++= java17Opens,
    // BE-P6-09 (EMR Option A): the job entry point; scala-library is supplied by the runtime (the
    // ledger's spark.runtime.scala-library first on the driver and executor classpath), never by
    // the jar; scala3-library_3 stays inside. Spark and Hadoop are Provided and absent.
    // sparkJobAssemblyCheck verifies the content. Repeatable build: sorted entries, fixed times.
    assembly / mainClass := Some(SparkSubmitLocal.JobMainClass),
    assembly / assemblyExcludedJars := (assembly / fullClasspath).value.filter(
      _.get(moduleID.key).exists(m =>
        m.organization == "org.scala-lang" && m.name == "scala-library"
      )
    ),
    assembly / assemblyRepeatableBuild := true
  )
  // BE-P6-03: the opt-in SparkIT configuration (see its definition above). Running happens only in
  // sparkIT; `check` also compiles it (BE-P6-05).
  .settings(inConfig(SparkIT)(Defaults.testSettings))
  // CR-P6-16 F2: the sbt-scalafmt 2.5.5 per-configuration settings (signature
  // `scalafmtConfigSettings(configuration: Configuration)`, as the compiler reports it), so
  // `sparkAnalytics/SparkIT/scalafmtCheck` checks modules/spark/src/it/scala; `check` runs it.
  .settings(inConfig(SparkIT)(ScalafmtPlugin.scalafmtConfigSettings(SparkIT)))
  // CR-P6-16 F3: every SparkIT route that would compile and run the suites outside sparkIT
  // refuses (SparkItRuns.RefusedRoutes; verifySparkItGuards checks this wiring in the loaded
  // build). `testFull` is not a key in sbt 1.11.7. One setting per line: the self-test reads it.
  .settings(
    SparkIT / sourceDirectory := baseDirectory.value / "src" / "it",
    // BE-P6-05: SparkIT uses Test's classpath, Provided jars included (see the SparkIT definition).
    SparkIT / classpathConfiguration := Configurations.TestInternal,
    SparkIT / test := SparkItRuns.refuseSbtRoute("test"),
    SparkIT / executeTests := SparkItRuns.refuseSbtRoute("executeTests"),
    SparkIT / console := SparkItRuns.refuseSbtRoute("console"),
    SparkIT / consoleQuick := SparkItRuns.refuseSbtRoute("consoleQuick"),
    SparkIT / consoleProject := SparkItRuns.refuseSbtRoute("consoleProject"),
    SparkIT / testOnly := SparkItRuns.refusedInput[Unit]("testOnly").evaluated,
    SparkIT / testQuick := SparkItRuns.refusedInput[Unit]("testQuick").evaluated,
    SparkIT / run := SparkItRuns.refusedInput[Unit]("run").evaluated,
    SparkIT / runMain := SparkItRuns.refusedInput[Unit]("runMain").evaluated,
    SparkIT / fgRun := SparkItRuns.refusedInput[Unit]("fgRun").evaluated,
    SparkIT / fgRunMain := SparkItRuns.refusedInput[Unit]("fgRunMain").evaluated,
    SparkIT / bgRun := SparkItRuns.refusedInput[JobHandle]("bgRun").evaluated,
    SparkIT / bgRunMain := SparkItRuns.refusedInput[JobHandle]("bgRunMain").evaluated
  )

lazy val storage = project
  .in(file("modules/storage"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-storage",
    // BE-P7-01. AWS SDK v2 at Compile: services (dependsOn storage) receives it transitively; rag
    // excludes it (see rag below; CLAUDE.md: rag uses storage ports only, never concrete clients).
    // Parquet and Hadoop at Optional: on storage's Compile, Runtime and Test classpaths (sbt's
    // compile-internal, runtime-internal and test-internal all extend Optional), so a storage
    // loader runs locally and storage tests read the fixture Parquet; Optional dependencies are
    // not transitive, so rag, services and the services package do not receive them. Provided
    // would leave them off storage / Runtime. verifyDependencyScopes (part of check) proves all of
    // this on the resolved classpaths and the services packaging mappings.
    // BE-P7-05 (DU-P7-02): snappy-java, aircompressor and zstd-jni are excluded from the
    // transitive trees of the three Optional dependencies (excludeAll on each dependency, not
    // excludeDependencies, which would also remove the direct Test snappy-java). Storage main reads
    // Parquet through its own SnappyRaw decoder; storage tests that write or decode Snappy through
    // parquet's SnappyCodec get snappy-java at Test only, at the ledger `snappy-java` version.
    // verifyDependencyScopes checks both on the resolved classpaths.
    libraryDependencies ++= awsSdkModules.value ++ Seq(
      parquetHadoop.value.excludeAll(nativeCodecExclusions: _*) % Optional,
      hadoopClientApi.value.excludeAll(nativeCodecExclusions: _*) % Optional,
      hadoopClientRuntime.value.excludeAll(nativeCodecExclusions: _*) % Optional,
      snappyJava.value % Test
    ),
    // BE-P7-06 (DU-P7-04): the AWS SDK's netty-nio-client modules at the ledger `netty` version.
    dependencyOverrides ++= nettyOverrides.value
  )

lazy val rag = project
  .in(file("modules/rag"))
  .dependsOn(core, storage)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-rag",
    // BE-P7-01 repair 01: rag sees storage's ports and models, never a concrete AWS client. Every
    // software.amazon.awssdk artifact (and whatever only it pulls in) is excluded from rag's
    // resolution in every configuration. services still resolves the SDK through its own direct
    // dependency on storage. verifyDependencyScopes fails if any SDK jar reaches rag's Compile,
    // Runtime or Test classpath.
    excludeDependencies += ExclusionRule(organization = DependencyScopes.AwsOrg)
  )

lazy val services = project
  .in(file("modules/services"))
  .dependsOn(core, rag, storage)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-services",
    libraryDependencies ++= Seq(slf4jApi.value, logbackClassic.value % Runtime),
    // BE-P7-06 (DU-P7-04): services resolves the SDK (and its Netty) itself; same pin as storage.
    dependencyOverrides ++= nettyOverrides.value,
    // Local container image (`sbt dockerLocal`): pinned JRE, non-root user,
    // percentage-based heap limits. No registry, tag `latest`, or cloud settings.
    dockerBaseImage := versionLedger.value("docker.java-runtime-image"),
    dockerUpdateLatest := false,
    dockerRepository := None,
    // BE-P10-01: logback (Runtime) is on the Test classpath with no configuration; WARN in tests.
    boundedTestLogging(TestLogging.Logback, forceProvider = false),
    Docker / packageName := "scholarchain-services",
    Docker / daemonUser := "scholarchain",
    Docker / daemonUserUid := Some("10001"),
    Docker / daemonGroup := "scholarchain",
    Docker / daemonGroupGid := Some("10001"),
    Universal / javaOptions ++= Seq(
      "-J-XX:InitialRAMPercentage=50.0",
      "-J-XX:MaxRAMPercentage=75.0",
      "-J-XX:+ExitOnOutOfMemoryError"
    )
  )

// Cross-module and adversarial tests (owner: test-engineer). Test scope only;
// nothing depends on this project.
lazy val tests = project
  .in(file("tests"))
  .dependsOn(
    core % Test,
    entityResolution % Test,
    hadoop % Test,
    sparkAnalytics % Test,
    // BE-P7-06 (SE-P7-13): tests Test also sees storage's Test classes and resources (the fixture
    // helpers); test scope only, so the CLAUDE.md module graph is unchanged.
    storage % "test->test",
    rag % Test,
    services % Test
  )
  .settings(commonSettings)
  .settings(
    name := "scholarchain-tests",
    publish / skip := true,
    // Provided and Optional dependencies are not transitive; cross-module tests need them directly.
    // BE-P7-06: parquet-hadoop for storage's loader (SE-P7-13); codecs excluded from the Hadoop
    // client and parquet-hadoop, aircompressor/zstd-jni (and lz4-java under L1) from spark-sql;
    // Spark's snappy-java and every io.netty module pinned to the ledger.
    libraryDependencies ++= Seq(
      hadoopClientApi.value.excludeAll(testsCodecExclusions: _*) % Test,
      hadoopClientRuntime.value.excludeAll(testsCodecExclusions: _*) % Test,
      parquetHadoop.value.excludeAll(testsCodecExclusions: _*) % Test,
      sparkSql.value.excludeAll(sparkCodecExclusions: _*) % Test
    ),
    dependencyOverrides ++= nettyOverrides.value :+ snappyJava.value,
    Test / javaOptions ++= java17Opens,
    // BE-P10-01: logback-classic (through services) and log4j-slf4j2-impl (through Spark) are both
    // on this Test classpath; logback bound first and, unconfigured, logged everything at DEBUG.
    // Select the log4j2 provider (the one Spark expects) and its build WARN configuration.
    boundedTestLogging(TestLogging.Log4j2, forceProvider = true)
  )

lazy val root = project
  .in(file("."))
  .aggregate(core, entityResolution, hadoop, sparkAnalytics, storage, rag, services, tests)
  .settings(commonSettings)
  .settings(
    name := "scholarchain",
    publish / skip := true,
    verifyBuildSources := {
      val log = streams.value.log
      val base = (ThisBuild / baseDirectory).value.getCanonicalFile
      // The load-time guard scans the launch directory; it must be sbt's build base.
      if (base != BuildGuard.launchBase)
        throw new sbt.internal.util.MessageOnlyException(
          s"Build base $base differs from launch directory ${BuildGuard.launchBase}; " +
            "start sbt from the repository root"
        )
      BuildGuard.enforce(base)
      log.info("Build sources OK: only /build.sbt and /project/ define the build")
    },
    verifyVersionLedger := {
      val log = streams.value.log
      val base = (ThisBuild / baseDirectory).value
      val ledger = versionLedger.value
      val buildFiles = (base / "build.sbt") +: (base / "project" * "*.sbt").get
      val problems = Ledger.mirrorProblems(ledger, base, sbtVersion.value) ++
        Ledger.literalVersionProblems(base, buildFiles)
      if (problems.nonEmpty)
        throw new sbt.internal.util.MessageOnlyException(problems.mkString("; "))
      log.info(
        s"Version ledger OK: ${ledger.entries.size} pinned entries in ${ledger.file.getPath}; " +
          s"no literal versions in ${buildFiles.size} build file(s)"
      )
    },
    markTestRun := TestMarker.record(
      (ThisBuild / baseDirectory).value,
      testOutcome.all(ScopeFilter(inAnyProject)).value,
      streams.value.log
    ),
    // Fixed-shape local runs (BE-P5-02, BE-P5-04). None is part of `check`; none writes the
    // test marker. Held-out child output goes straight to the terminal (LocalRuns.outputRoute).
    localPipeline := LocalRuns.chooseSplit("localPipeline"),
    localPipelineDev := pipelineRun(LocalRuns.Dev).value,
    localPipelineHeldout := pipelineRun(LocalRuns.Heldout).value,
    goldSetEval := LocalRuns.chooseSplit("goldSetEval"),
    goldSetEvalDev := goldSetEvalRun(LocalRuns.Dev).value,
    goldSetEvalHeldout := goldSetEvalRun(LocalRuns.Heldout).value,
    // BE-P6-01: dev decision run, then dev grouping run. Not part of `check`; writes no marker.
    // BE-P6-02: a dynamic task, so the source and code-scope checks run before sbt compiles the
    // entityResolution classpath; the preflight repeats them after compiling, before any fork.
    erDecisionGroupingDev := Def.taskDyn {
      val buildBase = (ThisBuild / baseDirectory).value
      val inherited = sys.env
      LocalRuns.erDevBeforeCompile(buildBase, inherited, streams.value.log)
      Def.task {
        LocalRuns.erDecisionGroupingDev(
          buildBase,
          javaHome.value,
          (entityResolution / Compile / fullClasspath).value.map(_.data),
          awsIsolationEnv(target.value / "no-aws"),
          inherited,
          streams.value.log
        )
      }
    }.value,
    verifyErDevRunGuards := LocalRuns.erDevGuardSelfTest(
      (ThisBuild / baseDirectory).value,
      streams.value.log
    ),
    // BE-P6-03: opt-in Spark integration tests. Not part of `check`; writes no marker; uses only
    // the sparkAnalytics project (core and Spark). A dynamic task, so the configuration and archive
    // checks run before sbt compiles the SparkIT classpath; they run again before the fork.
    sparkIT := Def.taskDyn {
      val buildBase = (ThisBuild / baseDirectory).value
      val home = sys.props.get("user.home")
      val inherited = sys.env
      val targets = target.all(ScopeFilter(inAnyProject)).value
      val sparkTarget = (sparkAnalytics / target).value
      SparkItRuns.beforeCompile(buildBase, home, inherited, targets, sparkTarget, streams.value.log)
      Def.task {
        SparkItRuns.sparkIT(
          buildBase,
          home,
          inherited,
          targets,
          sparkTarget,
          javaHome.value,
          // BE-P6-06: Attributed entries (module IDs), so sparkIT can check the one JUnit jar.
          (sparkAnalytics / SparkIT / fullClasspath).value,
          versionLedger.value("junit"),
          (sparkAnalytics / SparkIT / definedTestNames).value,
          java17Opens,
          awsIsolationEnv(target.value / "no-aws"),
          streams.value.log
        )
      }
    }.value,
    verifySparkItGuards := SparkItRuns.guardSelfTest(
      (ThisBuild / baseDirectory).value,
      sys.props.get("user.home"),
      (sparkAnalytics / thisProject).value.dependencies.map(_.project.project),
      checkSteps,
      java17Opens,
      Project.extract(state.value).structure,
      (sparkAnalytics / thisProjectRef).value,
      javaHome.value,
      streams.value.log
    ),
    // BE-P6-06, run by `check`: the real runner (SparkItRuns.runRunner, as sparkIT uses it) forked
    // on the SparkIT classpath plus fixture classes compiled into target/sparkit-runner-check/.
    // No archive read; nothing under modules/spark/target/spark-it/; no sparkIT or SparkIT route.
    verifySparkItRunner := SparkItRuns.runnerCheck(
      (ThisBuild / baseDirectory).value,
      javaHome.value,
      (sparkAnalytics / SparkIT / fullClasspath).value,
      versionLedger.value("junit"),
      java17Opens,
      awsIsolationEnv(target.value / "no-aws"),
      streams.value.log
    ),
    // BE-P6-09: the job assembly content check, run by packageJobs (and inside sparkSubmitLocal).
    sparkJobAssemblyCheck := {
      SparkSubmitLocal.checkAssembly(
        SparkSubmitLocal.AssemblyCheckTask,
        (sparkAnalytics / assembly).value,
        (sparkAnalytics / Compile / dependencyClasspath).value,
        versionLedger.value("scala"),
        versionLedger.value("spark.runtime.scala-library"),
        streams.value.log
      )
      ()
    },
    // BE-P7-06: the hadoop job assembly carries none of the EMR-supplied or codec/Netty packages.
    hadoopJobAssemblyCheck := SparkSubmitLocal.checkPrefixesOnly(
      "hadoopJobAssemblyCheck",
      (hadoop / assembly).value,
      streams.value.log
    ),
    // BE-P6-09: opt-in local runtime gate. Not part of `check`; writes no marker. A dynamic task, so
    // the Java, ledger, code-scope and archive checks run before sbt builds the assembly and the
    // sparkAnalytics Test classpath; they run again before anything is downloaded or forked.
    sparkSubmitLocal := Def.taskDyn {
      val buildBase = (ThisBuild / baseDirectory).value
      val home = sys.props.get("user.home")
      val inherited = sys.env
      val targets = target.all(ScopeFilter(inAnyProject)).value
      val sparkTarget = (sparkAnalytics / target).value
      val ledger = versionLedger.value
      val jdk = javaHome.value
      // CR-P6-27 m2: the HEAD seen before compiling; the task refuses if HEAD differs after.
      val headBefore = SparkSubmitLocal.beforeCompile(
        buildBase,
        home,
        inherited,
        targets,
        sparkTarget,
        ledger,
        jdk,
        streams.value.log
      )
      Def.task {
        SparkSubmitLocal.sparkSubmitLocal(
          buildBase,
          home,
          inherited,
          targets,
          sparkTarget,
          ledger,
          jdk,
          headBefore,
          (sparkAnalytics / assembly).value,
          (sparkAnalytics / Compile / dependencyClasspath).value,
          (sparkAnalytics / Test / fullClasspath).value.map(_.data),
          (sparkAnalytics / Test / discoveredMainClasses).value,
          dependencyResolution.value,
          java17Opens,
          awsIsolationEnv(target.value / "no-aws"),
          streams.value.log
        )
      }
    }.value,
    verifySparkSubmitLocalGuards := SparkSubmitLocal.guardSelfTest(
      versionLedger.value,
      checkSteps,
      java17Opens,
      streams.value.log
    ),
    // BE-P7-01, run by `check` before markTestRun: resolved classpaths (module IDs), the services
    // Universal and Docker mappings; never the declared libraryDependencies.
    verifyDependencyScopes := {
      val log = streams.value.log
      val ledger = versionLedger.value
      val aws = ledger("aws-sdk-v2.bom")
      // Attributed classpaths (BE-P7-06 addendum: the Hadoop client jars are opened from these).
      val storageCompileCp = (storage / Compile / fullClasspath).value
      val storageRuntimeCp = (storage / Runtime / fullClasspath).value
      val storageTestCp = (storage / Test / fullClasspath).value
      val hadoopTestCp = (hadoop / Test / fullClasspath).value
      val sparkTestCp = (sparkAnalytics / Test / fullClasspath).value
      val sparkItCp = (sparkAnalytics / SparkIT / fullClasspath).value
      val testsTestCp = (tests / Test / fullClasspath).value
      val in = DependencyScopes.Inputs(
        DependencyScopes.jars(storageCompileCp),
        DependencyScopes.jars(storageRuntimeCp),
        DependencyScopes.jars(storageTestCp),
        DependencyScopes.jars((rag / Compile / fullClasspath).value),
        DependencyScopes.jars((rag / Runtime / fullClasspath).value),
        DependencyScopes.jars((rag / Test / fullClasspath).value),
        DependencyScopes.jars((services / Runtime / fullClasspath).value),
        DependencyScopes.mappings((services / Universal / mappings).value),
        DependencyScopes.mappings((services / Docker / mappings).value),
        hadoopRuntime = DependencyScopes.jars((hadoop / Runtime / fullClasspath).value),
        hadoopTest = DependencyScopes.jars(hadoopTestCp),
        sparkRuntime = DependencyScopes.jars((sparkAnalytics / Runtime / fullClasspath).value),
        sparkTest = DependencyScopes.jars(sparkTestCp),
        sparkItTest = DependencyScopes.jars(sparkItCp),
        testsTest = DependencyScopes.jars(testsTestCp)
      )
      val labelled = Seq(
        "storage-compile" -> in.storageCompile,
        "storage-runtime" -> in.storageRuntime,
        "storage-test" -> in.storageTest,
        "rag-runtime" -> in.ragRuntime,
        "services-runtime" -> in.servicesRuntime,
        "hadoop-runtime" -> in.hadoopRuntime,
        "hadoop-test" -> in.hadoopTest,
        "spark-runtime" -> in.sparkRuntime,
        "spark-test" -> in.sparkTest,
        "sparkit-test" -> in.sparkItTest,
        "tests-test" -> in.testsTest
      )
      // Conflict evidence: what each classpath resolved for the watched families (Jackson,
      // Guava, Netty, HttpComponents, SLF4J, commons-*), next to the Spark and Hadoop worlds.
      val revisions = DependencyScopes.familyRevisions(labelled)
      // Codec coordinates (snappy-java, aircompressor, zstd-jni, lz4-java) per classpath (INFO).
      val codecLine = DependencyScopes.codecRevisions(labelled)
      DependencyScopes.run(
        in,
        revisions :+ codecLine,
        DependencyScopes.bomLine(dependencyResolution.value, aws, log),
        DependencyScopes.Pins(
          aws,
          ledger("parquet-hadoop"),
          ledger("hadoop"),
          ledger("snappy-java"),
          ledger("netty")
        ),
        // BE-P7-06 addendum (DU-P7-05 option (d)): Hadoop client jar contents, read-only.
        Seq(
          "storage-compile" -> storageCompileCp,
          "storage-runtime" -> storageRuntimeCp,
          "storage-test" -> storageTestCp,
          "hadoop-test" -> hadoopTestCp,
          "spark-test" -> sparkTestCp,
          "sparkit-test" -> sparkItCp,
          "tests-test" -> testsTestCp
        ),
        target.value / "dependency-scopes-selftest",
        log
      )
    },
    // BE-P10-01, run by `check` before markTestRun: static rules on every project, the build-owned
    // configurations, and probes forked on the tests and services Test classpaths and javaOptions
    // (files only under target/test-logging-probe/; no network call, no AWS call).
    verifyTestLogging := TestLogging.run(
      (ThisBuild / baseDirectory).value,
      testLoggingInputs.all(ScopeFilter(inAnyProject)).value,
      Seq(
        TestLogging.ProbeTarget(
          "tests",
          (tests / Test / fullClasspath).value.map(_.data),
          (tests / Test / javaOptions).value,
          TestLogging.Log4j2
        ),
        TestLogging.ProbeTarget(
          "services",
          (services / Test / fullClasspath).value.map(_.data),
          (services / Test / javaOptions).value,
          TestLogging.Logback
        )
      ),
      javaHome.value,
      awsIsolationEnv(target.value / "no-aws"),
      target.value / "test-logging-probe",
      streams.value.log
    ),
    // BE-P7-02, run by `check` before Test/compile (so a forbidden import is reported even when it
    // would not compile): a lexical scan of modules/rag/src, and sbt's own rag source roots. Reads
    // only repository source; the self-test fixture lives under target/rag-source-imports/.
    verifyRagSourceImports := RagSourceImports.run(
      (ThisBuild / baseDirectory).value,
      target.value / "rag-source-imports",
      (rag / Compile / unmanagedSourceDirectories).value ++
        (rag / Test / unmanagedSourceDirectories).value,
      (rag / Compile / unmanagedSources).value ++ (rag / Test / unmanagedSources).value,
      (rag / Compile / managedSources).value ++ (rag / Test / managedSources).value,
      streams.value.log
    ),
    // Synthetic held-out output-route check (decisions-17/19), run by `check`. The probe's task
    // streams are closed when it completes, before the scan reads every sbt target directory.
    heldoutRouteProbe := LocalRuns.routeProbe(
      (ThisBuild / baseDirectory).value,
      javaHome.value,
      streams.value.log
    ),
    verifyHeldoutOutputRoute := LocalRuns.routeScan(
      (ThisBuild / baseDirectory).value,
      target.all(ScopeFilter(inAnyProject)).value,
      heldoutRouteProbe.value,
      streams.value.log
    ),
    // BE-P5-05 (CR-P5-05 BE-1, BE-2), run by `check`: the held-out launch and clean-tree
    // refusals on synthetic inputs and a temporary git repository; reports, never enforces, the
    // real launch signals and the real D3(b) scope.
    verifyHeldoutGuards := LocalRuns.guardSelfTest(
      (ThisBuild / baseDirectory).value,
      LocalRuns.observeLaunch(state.value),
      target.all(ScopeFilter(inAnyProject)).value,
      streams.value.log
    )
  )

// --- Aliases -----------------------------------------------------------------------

addCommandAlias("fmt", "; scalafmtSbt ; scalafmtAll")
// The steps of `check`, in order (verifySparkItGuards reads them: sparkIT is never one of them).
lazy val checkSteps = Seq(
  "verifyBuildSources",
  "verifyVersionLedger",
  "verifyRagSourceImports", // BE-P7-02: rag sources name no SDK, Parquet, Hadoop or storage adapter
  "scalafmtSbtCheck",
  "scalafmtCheckAll",
  SparkItRuns.FormatCheckStep, // CR-P6-16 F2: modules/spark/src/it/scala
  "Test/compile",
  SparkItRuns.CompileCheckStep, // BE-P6-05: compiles modules/spark/src/it/scala; no archive, no run
  "verifyDependencyScopes", // BE-P7-01: resolved classpaths and services packaging
  "verifyTestLogging", // BE-P10-01: forked Test JVMs log at WARN or less verbose
  "verifyHeldoutOutputRoute",
  "verifyHeldoutGuards",
  "verifyErDevRunGuards",
  "verifySparkItGuards",
  "verifySparkItRunner", // BE-P6-06: real runner on fixtures; no archive, no sparkIT
  "verifySparkSubmitLocalGuards", // BE-P6-09: no network, no fork, never sparkSubmitLocal
  "markTestRun"
)
addCommandAlias("check", checkSteps.mkString("; ", " ; ", ""))
addCommandAlias(
  "packageJobs",
  "; hadoop/assembly ; sparkAnalytics/assembly ; sparkJobAssemblyCheck ; hadoopJobAssemblyCheck"
)
addCommandAlias("dockerLocal", "; services/Docker/publishLocal")
// localPipelineDev/Heldout and goldSetEvalDev/Heldout are root tasks, not aliases: see the
// root project settings and project/LocalRuns.scala.
