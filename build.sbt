// ScholarChain build definition. Owner: build-engineer.
// Every version comes from project/versions.properties via Ledger (project/Ledger.scala),
// resolved from the build base directory.
// The internal dependency graph mirrors CLAUDE.md "Module dependency boundaries" exactly.
// Environment variables: the build uses only the NAMES of inherited AWS_* variables, to
// blank them in forked test JVMs (see commonSettings). No other variable is read.

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

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-release:17", "-Werror"),
  javacOptions ++= Seq("--release", "17"),
  libraryDependencies += munit.value % Test,
  // Best-effort AWS isolation for forked test JVMs. The network is NOT blocked.
  // sbt can add or override variables of the forked JVM but cannot unset inherited
  // ones, so every AWS_* variable present in sbt's environment (by name; values are
  // not used) and the named credential, profile, role, container-credential, and
  // endpoint variables are set to "". Per-service AWS_ENDPOINT_URL_<SERVICE>
  // variables cannot all be enumerated; only those inherited by sbt are blanked.
  // Config and credentials files point at nonexistent paths; IMDS is disabled.
  Test / fork := true,
  Test / envVars ++= {
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
      "AWS_CONFIG_FILE" -> (target.value / "no-aws" / "config").getAbsolutePath,
      "AWS_SHARED_CREDENTIALS_FILE" -> (target.value / "no-aws" / "credentials").getAbsolutePath
    )
  },
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

// --- Projects ----------------------------------------------------------------------

lazy val core = project
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "scholarchain-core",
    libraryDependencies += upickle.value
  )

lazy val entityResolution = project
  .in(file("modules/entity-resolution"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "scholarchain-entity-resolution")

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
    Test / javaOptions ++= java17Opens
  )

lazy val sparkAnalytics = project
  .in(file("modules/spark"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(jobAssembly("spark-jobs"))
  .settings(
    name := "scholarchain-spark-analytics",
    libraryDependencies += sparkSql.value % Provided,
    Test / javaOptions ++= java17Opens
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
    )
  )

// --- Aliases -----------------------------------------------------------------------

addCommandAlias("fmt", "; scalafmtSbt ; scalafmtAll")
addCommandAlias(
  "check",
  "; verifyBuildSources ; verifyVersionLedger ; scalafmtSbtCheck ; scalafmtCheckAll ; Test/compile ; markTestRun"
)
addCommandAlias("packageJobs", "; hadoop/assembly ; sparkAnalytics/assembly")
addCommandAlias("dockerLocal", "; services/Docker/publishLocal")
