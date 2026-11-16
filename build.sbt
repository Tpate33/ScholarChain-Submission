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

// SparkIT: the integration-test configuration of sparkAnalytics. It extends Test (test classes,
// munit/JUnit and the Provided Spark jars are on its classpath) but has its own source directory
// modules/spark/src/it/. No routine command compiles or runs it: `Test/compile`, `test` and
// `sparkAnalytics/test` are Test-scoped, markTestRun reads Test/executeTests only, and the SparkIT
// routes test, testOnly, testQuick, executeTests, run, runMain, bgRun, bgRunMain, fgRun, fgRunMain,
// console, consoleQuick and consoleProject refuse. Only `sbt sparkIT` compiles and runs it; `check`
// only format-checks its sources (sparkAnalytics/SparkIT/scalafmtCheck, which does not compile).
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
  )
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
    libraryDependencies ++= Seq(
      hadoopClientApi.value % Provided,
      hadoopClientRuntime.value % Provided
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
    libraryDependencies += sparkSql.value % Provided,
    Test / javaOptions ++= java17Opens
  )
  // BE-P6-03: the opt-in SparkIT configuration (see its definition above). Compiling and running
  // happen only in sparkIT.
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
  .settings(name := "scholarchain-storage")

lazy val rag = project
  .in(file("modules/rag"))
  .dependsOn(core, storage)
  .settings(commonSettings)
  .settings(name := "scholarchain-rag")

lazy val services = project
  .in(file("modules/services"))
  .dependsOn(core, rag, storage)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-services",
    libraryDependencies ++= Seq(slf4jApi.value, logbackClassic.value % Runtime),
    // Local container image (`sbt dockerLocal`): pinned JRE, non-root user,
    // percentage-based heap limits. No registry, tag `latest`, or cloud settings.
    dockerBaseImage := versionLedger.value("docker.java-runtime-image"),
    dockerUpdateLatest := false,
    dockerRepository := None,
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
    storage % Test,
    rag % Test,
    services % Test
  )
  .settings(commonSettings)
  .settings(
    name := "scholarchain-tests",
    publish / skip := true,
    // Provided dependencies are not transitive; cross-module tests need them directly.
    libraryDependencies ++= Seq(
      hadoopClientApi.value % Test,
      hadoopClientRuntime.value % Test,
      sparkSql.value % Test
    ),
    Test / javaOptions ++= java17Opens
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
          (sparkAnalytics / SparkIT / fullClasspath).value.map(_.data),
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
  "scalafmtSbtCheck",
  "scalafmtCheckAll",
  SparkItRuns.FormatCheckStep, // CR-P6-16 F2: modules/spark/src/it/scala
  "Test/compile",
  "verifyHeldoutOutputRoute",
  "verifyHeldoutGuards",
  "verifyErDevRunGuards",
  "verifySparkItGuards",
  "markTestRun"
)
addCommandAlias("check", checkSteps.mkString("; ", " ; ", ""))
addCommandAlias("packageJobs", "; hadoop/assembly ; sparkAnalytics/assembly")
addCommandAlias("dockerLocal", "; services/Docker/publishLocal")
// localPipelineDev/Heldout and goldSetEvalDev/Heldout are root tasks, not aliases: see the
// root project settings and project/LocalRuns.scala.
