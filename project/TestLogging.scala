import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.Try

import sbt._
import sbt.internal.util.MessageOnlyException

/**
 * Bounded test logging (work order BE-P10-01). Owner: build-engineer. Run by `check` as
 * `verifyTestLogging`, before markTestRun.
 *
 * Cause it addresses: the `tests` Test classpath holds two SLF4J 2 providers, logback-classic
 * (through `services % Test`, whose Runtime carries logback) ahead of log4j-slf4j2-impl (through
 * Spark). SLF4J binds the first one found, and logback without a configuration file logs every
 * logger at DEBUG to the console, so Spark, Hadoop, Netty, Parquet and the AWS SDK filled the
 * `sbt check` output with DEBUG lines.
 *
 * The fix (build.sbt, [[javaOptions]]): forked Test JVMs of `tests` and `services` get system
 * properties that select the provider (`-Dslf4j.provider`, where two are present) and point the
 * backend at a build-owned configuration under project/test-logging/ (root level WARN), unless the
 * module owner ships its own test configuration (`logback-test.xml`, `log4j2-test.*`) in that
 * project's Test resources, which then takes precedence. Test results, failures and the sbt
 * summaries come from the test framework through sbt's test events, not from these loggers.
 *
 * The check:
 *   1. a self-test feeds broken synthetic inputs to the static rules and to the probe evaluation
 *      and fails unless each is detected;
 *   2. static rules on every project's Test classpath: two or more SLF4J providers need
 *      `-Dslf4j.provider` naming one of them; a logback or log4j2 provider in a project with test
 *      sources needs a configuration (the build property pointing at an existing file, or an owner
 *      test configuration file); a build property pointing at a missing file fails;
 *   3. the build-owned configuration files have root level WARN, ERROR or OFF and no logger at INFO
 *      or finer;
 *   4. an end-to-end probe: for `tests` and `services`, a small Java program (source launcher,
 *      written to target/test-logging-probe/) runs on the project's real Test classpath with its
 *      real Test javaOptions and must report the expected SLF4J factory, DEBUG and TRACE disabled
 *      (and INFO, under the build configuration) on the root and the named third-party loggers,
 *      WARN and ERROR enabled, exactly one WARN and one ERROR line of its own nonce, none at a
 *      finer level, and at most [[MaxNoise]] other lines. A counter-probe with a generated DEBUG
 *      configuration must be detected; a baseline probe without the build's logging options is
 *      reported (INFO) as the diagnosis evidence.
 *
 * Not covered: SLF4J 1.x bindings (StaticLoggerBinder; storage and hadoop resolve slf4j-api 1.7.36
 * with no binding), JUL and commons-logging routes that bypass SLF4J, the level of owner
 * configuration files (owner responsibility), log volume of code that logs at WARN or ERROR, and
 * the services runtime or container logging (not test scope).
 */
object TestLogging {
  val Task = "verifyTestLogging"
  val ConfigDir = "project/test-logging"
  val ProviderProperty = "slf4j.provider"
  val VerbosityOption = "-Dslf4j.internal.verbosity=WARN"
  val MaxNoise = 5
  val ProbeTimeoutSeconds = 60L
  val ProbeClass = "TestLoggingProbe"
  val ProbeLoggerName = "scholarchain.build.TestLoggingProbe"

  /** Root (SLF4J name) and the third-party loggers whose DEBUG output flooded the runs. */
  val ProbeLoggers: Seq[String] = Seq(
    "ROOT",
    "org.apache.spark",
    "org.apache.hadoop",
    "org.apache.parquet",
    "io.netty",
    "software.amazon.awssdk",
    "org.sparkproject",
    "org.eclipse.jetty",
    "scholarchain"
  )

  sealed abstract class Backend(
      val label: String,
      val providerClass: String,
      val factoryClass: String,
      val configProperties: Seq[String],
      val testConfigNames: Seq[String],
      val mainConfigNames: Seq[String],
      val buildConfigName: String
  ) {
    def configProperty: String = configProperties.head
  }

  // The case objects take only literals: their initialization must not touch this enclosing
  // object, whose backend-dependent values are lazy for the same reason.
  case object Logback
      extends Backend(
        "logback",
        "ch.qos.logback.classic.spi.LogbackServiceProvider",
        "ch.qos.logback.classic.LoggerContext",
        Seq("logback.configurationFile"),
        Seq("logback-test.xml"),
        Seq("logback.xml"),
        "services-logback.xml"
      )

  case object Log4j2
      extends Backend(
        "log4j2",
        "org.apache.logging.slf4j.SLF4JServiceProvider",
        "org.apache.logging.slf4j.Log4jLoggerFactory",
        Seq("log4j2.configurationFile", "log4j.configurationFile"),
        Seq(
          "log4j2-test.properties",
          "log4j2-test.yaml",
          "log4j2-test.yml",
          "log4j2-test.json",
          "log4j2-test.jsn",
          "log4j2-test.xml"
        ),
        Seq(
          "log4j2.properties",
          "log4j2.yaml",
          "log4j2.yml",
          "log4j2.json",
          "log4j2.jsn",
          "log4j2.xml"
        ),
        "tests-log4j2.properties"
      )

  lazy val Backends: Seq[Backend] = Seq(Logback, Log4j2)

  /** An SLF4J 2 provider artifact; `backend` is set for the two that need a configuration. */
  final case class Provider(
      label: String,
      org: String,
      name: String,
      providerClass: String,
      backend: Option[Backend]
  )

  lazy val Providers: Seq[Provider] = Seq(
    Provider(
      "logback-classic",
      "ch.qos.logback",
      "logback-classic",
      Logback.providerClass,
      Some(Logback)
    ),
    Provider(
      "log4j-slf4j2-impl",
      "org.apache.logging.log4j",
      "log4j-slf4j2-impl",
      Log4j2.providerClass,
      Some(Log4j2)
    ),
    Provider(
      "slf4j-simple",
      "org.slf4j",
      "slf4j-simple",
      "org.slf4j.simple.SimpleServiceProvider",
      None
    ),
    Provider("slf4j-nop", "org.slf4j", "slf4j-nop", "org.slf4j.nop.NOPServiceProvider", None),
    Provider("slf4j-jdk14", "org.slf4j", "slf4j-jdk14", "org.slf4j.jul.JULServiceProvider", None),
    Provider(
      "slf4j-reload4j",
      "org.slf4j",
      "slf4j-reload4j",
      "org.slf4j.reload4j.Reload4jServiceProvider",
      None
    )
  )

  /** The build-owned configuration file of a backend. */
  def buildConfig(buildBase: File, backend: Backend): File =
    buildBase / ConfigDir / backend.buildConfigName

  /** Owner test configuration files of a backend present in the given resource directories. */
  def ownerTestConfigs(backend: Backend, dirs: Seq[File], exists: File => Boolean): Seq[File] =
    for {
      d <- dirs
      n <- backend.testConfigNames
      f = d / n
      if exists(f)
    } yield f

  /**
   * The forked Test JVM options of a project (build.sbt): the provider selection when
   * `forceProvider`, the build configuration unless an owner test configuration exists in
   * `testResourceDirs`, and SLF4J's internal reporter at WARN.
   */
  def javaOptions(
      backend: Backend,
      buildBase: File,
      forceProvider: Boolean,
      testResourceDirs: Seq[File]
  ): Seq[String] = {
    val provider =
      if (forceProvider) Seq(s"-D$ProviderProperty=${backend.providerClass}") else Nil
    val config =
      if (ownerTestConfigs(backend, testResourceDirs, _.isFile).nonEmpty) Nil
      else Seq(s"-D${backend.configProperty}=${buildConfig(buildBase, backend).getAbsolutePath}")
    provider ++ config :+ VerbosityOption
  }

  /** The SLF4J 2 providers on a classpath, by module ID or else by jar file name. */
  def providersOn(classpath: Seq[Attributed[File]]): Seq[Provider] =
    Providers.filter(p =>
      classpath.exists { a =>
        a.get(Keys.moduleID.key) match {
          case Some(m) => m.organization == p.org && m.name == p.name
          case None => a.data.getName.startsWith(p.name + "-") && a.data.getName.endsWith(".jar")
        }
      }
    )

  /** One project's inputs to the static rules (collected by `testLoggingInputs`). */
  final case class ProjectInputs(
      project: String,
      testSources: Int,
      providers: Seq[Provider],
      javaOptions: Seq[String],
      testResourceDirs: Seq[File],
      mainResourceDirs: Seq[File]
  )

  final case class Line(tag: String, id: String, detail: String) {
    def failed: Boolean = tag == "FAIL"
    def render: String = s"$Task: [$tag] $id: $detail"
  }

  private def sysProp(options: Seq[String], key: String): Option[String] =
    options.reverse.collectFirst {
      case o if o.startsWith(s"-D$key=") => o.stripPrefix(s"-D$key=")
    }

  private def rel(base: File, f: File): String =
    IO.relativize(base, f).getOrElse(f.getName)

  private def names(xs: Seq[String]): String = if (xs.isEmpty) "none" else xs.mkString(", ")

  /** The static rules for one project. */
  def staticLine(p: ProjectInputs, buildBase: File, exists: File => Boolean): Line = {
    val id = s"${p.project}-test-logging"
    val provs = p.providers
    val found = s"providers [${names(provs.map(_.label))}], test sources ${p.testSources}"
    if (provs.isEmpty)
      return Line("PASS", id, s"$found; no SLF4J 2 provider (SLF4J uses its no-op logger)")
    val explicit = sysProp(p.javaOptions, ProviderProperty)
    val effective: Either[String, Provider] = explicit match {
      case Some(cls) =>
        provs
          .find(_.providerClass == cls)
          .toRight(s"-D$ProviderProperty=$cls names no provider on the Test classpath")
      case None if provs.size == 1 => Right(provs.head)
      case None =>
        Left(
          s"${provs.size} SLF4J providers and no -D$ProviderProperty: the binding depends on " +
            "classpath order"
        )
    }
    effective match {
      case Left(problem) => Line("FAIL", id, s"$found; $problem")
      case Right(provider) =>
        val chosen = s"$found; provider ${provider.label}"
        provider.backend match {
          case None =>
            val simpleLevel = sysProp(p.javaOptions, "org.slf4j.simpleLogger.defaultLogLevel")
              .map(_.toLowerCase)
            if (provider.label == "slf4j-simple" && simpleLevel.exists(Set("debug", "trace")))
              Line("FAIL", id, s"$chosen; slf4j-simple default level ${simpleLevel.get}")
            else Line("PASS", id, s"$chosen; default level INFO or less verbose")
          case Some(b) =>
            val prop = b.configProperties.flatMap(k => sysProp(p.javaOptions, k)).headOption
            val ownerTest = ownerTestConfigs(b, p.testResourceDirs, exists)
            val ownerMain = for {
              d <- p.testResourceDirs ++ p.mainResourceDirs
              n <- b.mainConfigNames
              f = d / n
              if exists(f)
            } yield f
            val owner = ownerTest ++ ownerMain
            prop match {
              case Some(path) if !exists(file(path)) =>
                Line(
                  "FAIL",
                  id,
                  s"$chosen; -D${b.configProperty} names a missing file ${rel(buildBase, file(path))}"
                )
              case Some(path) =>
                Line("PASS", id, s"$chosen; configuration ${rel(buildBase, file(path))} (build)")
              case None if owner.nonEmpty =>
                Line(
                  "PASS",
                  id,
                  s"$chosen; owner configuration ${names(owner.map(rel(buildBase, _)))}"
                )
              case None if p.testSources == 0 =>
                Line("INFO", id, s"$chosen; no configuration, not enforced without test sources")
              case None =>
                Line(
                  "FAIL",
                  id,
                  s"$chosen; no ${b.label} configuration for a project with test sources (logback " +
                    "defaults to DEBUG; Spark replaces a default log4j2 configuration with INFO): " +
                    s"add ${b.testConfigNames.head} to the project's Test resources (module owner) " +
                    "or the build options (build-engineer)"
                )
            }
        }
    }
  }

  private val Bounded = Set("warn", "error", "off")
  private val Verbose = Set("info", "debug", "trace", "all")

  /** Root level and finer-level entries of a build-owned configuration text. */
  def buildConfigProblems(backend: Backend, text: String): Seq[String] = {
    val body = text.linesIterator.filterNot(_.trim.startsWith("#")).mkString("\n")
    val noComments = body.replaceAll("(?s)<!--.*?-->", "")
    backend match {
      case Log4j2 =>
        val root = """(?m)^\s*rootLogger\.level\s*=\s*(\w+)\s*$""".r
          .findFirstMatchIn(noComments)
          .map(_.group(1).toLowerCase)
        val verbose = """(?m)^\s*[\w.]*level\s*=\s*(\w+)\s*$""".r
          .findAllMatchIn(noComments)
          .map(_.group(1).toLowerCase)
          .filter(Verbose)
          .toSeq
        root.filterNot(Bounded).map(l => s"root level $l").toSeq ++
          (if (root.isEmpty) Seq("no rootLogger.level") else Nil) ++
          verbose.map(l => s"a level entry at $l")
      case Logback =>
        val root = """<root\s+level="(\w+)"""".r
          .findFirstMatchIn(noComments)
          .map(_.group(1).toLowerCase)
        val verbose = """level="(\w+)"""".r
          .findAllMatchIn(noComments)
          .map(_.group(1).toLowerCase)
          .filter(Verbose)
          .toSeq
        root.filterNot(Bounded).map(l => s"root level $l").toSeq ++
          (if (root.isEmpty) Seq("no <root level=...>") else Nil) ++
          verbose.map(l => s"a level attribute at $l")
    }
  }

  // --- End-to-end probe --------------------------------------------------------------------------

  /** The probe program (Java source launcher; uses only the SLF4J API). */
  def probeSource: String = {
    val array = ProbeLoggers.map(n => "\"" + n + "\"").mkString(", ")
    s"""import org.slf4j.Logger;
       |import org.slf4j.LoggerFactory;
       |
       |public final class $ProbeClass {
       |  private static final String[] NAMES = { $array };
       |
       |  public static void main(String[] args) {
       |    String nonce = args[0];
       |    System.out.println("PROBE factory=" + LoggerFactory.getILoggerFactory().getClass().getName());
       |    for (String name : NAMES) {
       |      Logger l = LoggerFactory.getLogger(name);
       |      System.out.println("PROBE logger=" + name + " trace=" + l.isTraceEnabled()
       |          + " debug=" + l.isDebugEnabled() + " info=" + l.isInfoEnabled()
       |          + " warn=" + l.isWarnEnabled() + " error=" + l.isErrorEnabled());
       |    }
       |    Logger p = LoggerFactory.getLogger("$ProbeLoggerName");
       |    p.trace(nonce + "-TRACE");
       |    p.debug(nonce + "-DEBUG");
       |    p.info(nonce + "-INFO");
       |    p.warn(nonce + "-WARN");
       |    p.error(nonce + "-ERROR");
       |    System.err.flush();
       |    System.out.println("PROBE done");
       |    System.out.flush();
       |  }
       |}
       |""".stripMargin
  }

  final case class ProbeVerdict(
      problems: Seq[String],
      factory: String,
      rootDebug: Option[Boolean],
      noise: Int
  )

  private def loggerFlags(lines: Seq[String]): Map[String, Map[String, String]] =
    lines
      .filter(_.startsWith("PROBE logger="))
      .map { l =>
        val kv = l
          .stripPrefix("PROBE ")
          .split(' ')
          .toSeq
          .flatMap { t =>
            t.split("=", 2) match {
              case Array(k, v) => Some(k -> v)
              case _ => None
            }
          }
          .toMap
        kv.getOrElse("logger", "") -> kv
      }
      .toMap

  /** Evaluates one probe run (exit code and merged stdout/stderr lines). */
  def evaluateProbe(
      exit: Int,
      lines: Seq[String],
      nonce: String,
      expectedFactory: String,
      allowInfo: Boolean
  ): ProbeVerdict = {
    val problems = ListBuffer.empty[String]
    if (exit != 0) problems += s"probe exit code $exit"
    if (!lines.contains("PROBE done")) problems += "probe did not complete"
    val factory = lines
      .collectFirst { case l if l.startsWith("PROBE factory=") => l.stripPrefix("PROBE factory=") }
      .getOrElse("none")
    if (factory != expectedFactory) problems += s"SLF4J factory $factory, expected $expectedFactory"
    val flags = loggerFlags(lines)
    ProbeLoggers.foreach { n =>
      flags.get(n) match {
        case None => problems += s"logger $n not reported"
        case Some(f) =>
          val on = (k: String) => f.get(k).contains("true")
          val off = (k: String) => f.get(k).contains("false")
          val finer = Seq("trace", "debug") ++ (if (allowInfo) Nil else Seq("info"))
          finer.filterNot(off).foreach(k => problems += s"logger $n: $k enabled")
          Seq("warn", "error").filterNot(on).foreach(k => problems += s"logger $n: $k disabled")
      }
    }
    def count(level: String) = lines.count(_.contains(s"$nonce-$level"))
    Seq("TRACE", "DEBUG").foreach { l =>
      if (count(l) != 0) problems += s"${count(l)} $l line(s) of the probe logger"
    }
    if (count("INFO") > (if (allowInfo) 1 else 0))
      problems += s"${count("INFO")} INFO line(s) of the probe logger"
    Seq("WARN", "ERROR").foreach { l =>
      if (count(l) != 1) problems += s"${count(l)} $l line(s) of the probe logger, expected 1"
    }
    val noise = lines.count(l => l.nonEmpty && !l.startsWith("PROBE ") && !l.contains(nonce))
    if (noise > MaxNoise) problems += s"$noise other output line(s), at most $MaxNoise allowed"
    ProbeVerdict(
      problems.toList,
      factory,
      flags.get("ROOT").flatMap(_.get("debug")).map(_ == "true"),
      noise
    )
  }

  /** One project whose forked Test JVM the probe reproduces. */
  final case class ProbeTarget(
      project: String,
      classpath: Seq[File],
      javaOptions: Seq[String],
      backend: Backend
  )

  private def isLoggingOption(o: String): Boolean =
    o.startsWith(s"-D$ProviderProperty=") || o.startsWith("-Dslf4j.internal.") ||
      Backends.exists(b => b.configProperties.exists(k => o.startsWith(s"-D$k=")))

  private def runProbe(
      java: File,
      dir: File,
      run: String,
      source: File,
      t: ProbeTarget,
      options: Seq[String],
      env: Map[String, String],
      nonce: String
  ): (Int, Seq[String]) = {
    val out = dir / s"$run.out"
    val cmd = (java.getAbsolutePath +: options) ++ Seq(
      "-cp",
      t.classpath.map(_.getAbsolutePath).mkString(File.pathSeparator),
      source.getAbsolutePath,
      nonce
    )
    val pb = new ProcessBuilder(cmd.asJava)
      .directory(dir)
      .redirectErrorStream(true)
      .redirectOutput(out)
      .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
    pb.environment().putAll(env.asJava)
    val proc = pb.start()
    val exit =
      if (proc.waitFor(ProbeTimeoutSeconds, TimeUnit.SECONDS)) proc.exitValue()
      else {
        proc.destroyForcibly()
        proc.waitFor(10, TimeUnit.SECONDS)
        -1
      }
    val lines =
      if (out.isFile) Files.readAllLines(out.toPath, StandardCharsets.UTF_8).asScala.toList
      else Nil
    (exit, lines)
  }

  private lazy val DebugConfigs: Map[Backend, (String, String)] = Map(
    Log4j2 -> ("debug-log4j2.properties" ->
      Seq(
        "rootLogger.level = debug",
        "rootLogger.appenderRef.out.ref = console",
        "appender.console.type = Console",
        "appender.console.name = console",
        "appender.console.layout.type = PatternLayout",
        "appender.console.layout.pattern = %p %c: %m%n"
      ).mkString("", "\n", "\n")),
    Logback -> ("debug-logback.xml" ->
      ("<configuration><appender name=\"OUT\" class=\"ch.qos.logback.core.ConsoleAppender\">" +
        "<encoder><pattern>%level %logger: %msg%n</pattern></encoder></appender>" +
        "<root level=\"DEBUG\"><appender-ref ref=\"OUT\"/></root></configuration>\n"))
  )

  // --- Self-test on synthetic inputs ------------------------------------------------------------

  def selfTestProblems(buildBase: File): (Seq[String], Int) = {
    val res = buildBase / "synthetic" / "src" / "test" / "resources"
    val main = buildBase / "synthetic" / "src" / "main" / "resources"
    val buildFile = buildConfig(buildBase, Log4j2)
    val present = Set(buildFile, res / "logback-test.xml")
    val exists = (f: File) => present.contains(f)
    val logback = Providers.find(_.label == "logback-classic").get
    val log4j = Providers.find(_.label == "log4j-slf4j2-impl").get
    val simple = Providers.find(_.label == "slf4j-simple").get
    val good = ProjectInputs(
      "synthetic",
      3,
      Seq(logback, log4j),
      Seq(
        s"-D$ProviderProperty=${Log4j2.providerClass}",
        s"-D${Log4j2.configProperty}=${buildFile.getAbsolutePath}"
      ),
      Seq(buildBase / "synthetic" / "no-resources"),
      Seq(main)
    )
    val staticCases: Seq[(String, ProjectInputs)] = Seq(
      "two providers, no -Dslf4j.provider" -> good.copy(javaOptions = good.javaOptions.drop(1)),
      "-Dslf4j.provider names an absent provider" -> good.copy(providers = Seq(logback)),
      "logback, test sources, no configuration (the BE-P10-01 regression)" ->
        good.copy(providers = Seq(logback), javaOptions = Nil),
      "log4j2, test sources, no configuration" ->
        good.copy(providers = Seq(log4j), javaOptions = Nil),
      "configuration property names a missing file" -> good.copy(javaOptions =
        good.javaOptions.take(
          1
        ) :+ s"-D${Log4j2.configProperty}=${(res / "missing").getAbsolutePath}"
      ),
      "slf4j-simple at debug" -> good.copy(
        providers = Seq(simple),
        javaOptions = Seq("-Dorg.slf4j.simpleLogger.defaultLogLevel=debug")
      )
    )
    val passCases: Seq[(String, ProjectInputs)] = Seq(
      "well-formed" -> good,
      "owner logback-test.xml" -> good.copy(
        providers = Seq(logback),
        javaOptions = Nil,
        testResourceDirs = Seq(res)
      ),
      "no test sources" -> good.copy(providers = Seq(logback), javaOptions = Nil, testSources = 0),
      "no provider" -> good.copy(providers = Nil, javaOptions = Nil)
    )
    val problems = ListBuffer.empty[String]
    staticCases.foreach { case (label, in) =>
      if (!staticLine(in, buildBase, exists).failed) problems += s"static rule missed: $label"
    }
    passCases.foreach { case (label, in) =>
      if (staticLine(in, buildBase, exists).failed) problems += s"static rule rejected: $label"
    }
    val configCases = Seq(
      "log4j2 root debug" -> (Log4j2, "rootLogger.level = debug\n"),
      "log4j2 logger at info" -> (Log4j2, "rootLogger.level = warn\nlogger.x.level = info\n"),
      "log4j2 no root level" -> (Log4j2, "status = warn\n"),
      "logback root DEBUG" -> (Logback, "<configuration><root level=\"DEBUG\"/></configuration>"),
      "logback logger at TRACE" -> (
        Logback,
        "<configuration><logger name=\"a\" level=\"TRACE\"/><root level=\"WARN\"/></configuration>"
      )
    )
    configCases.foreach { case (label, (b, text)) =>
      if (buildConfigProblems(b, text).isEmpty) problems += s"config rule missed: $label"
    }
    if (buildConfigProblems(Log4j2, "# level = debug\nrootLogger.level = warn\n").nonEmpty)
      problems += "config rule rejected a commented level"
    val nonce = "n0"
    val goodLines = Seq(s"PROBE factory=${Log4j2.factoryClass}") ++ ProbeLoggers.map(n =>
      s"PROBE logger=$n trace=false debug=false info=false warn=true error=true"
    ) ++ Seq(s"WARN x: $nonce-WARN", s"ERROR x: $nonce-ERROR", "PROBE done")
    def probe(lines: Seq[String], exit: Int = 0, allowInfo: Boolean = false) =
      evaluateProbe(exit, lines, nonce, Log4j2.factoryClass, allowInfo).problems
    val probeCases: Seq[(String, Seq[String])] = Seq(
      "root debug enabled" -> probe(
        goodLines.map(l =>
          if (l.startsWith("PROBE logger=ROOT ")) l.replace("debug=false", "debug=true") else l
        )
      ),
      "netty info enabled" -> probe(
        goodLines.map(l =>
          if (l.startsWith("PROBE logger=io.netty ")) l.replace("info=false", "info=true") else l
        )
      ),
      "warn disabled (warnings hidden)" -> probe(
        goodLines.map(_.replace("warn=true", "warn=false"))
      ),
      "debug line printed" -> probe(goodLines :+ s"DEBUG x: $nonce-DEBUG"),
      "warn line missing" -> probe(goodLines.filterNot(_.contains(s"$nonce-WARN"))),
      "wrong factory" -> probe(goodLines.map(_.replace(Log4j2.factoryClass, Logback.factoryClass))),
      "logger missing" -> probe(goodLines.filterNot(_.startsWith("PROBE logger=scholarchain "))),
      "non-zero exit" -> probe(goodLines, exit = 1),
      "incomplete" -> probe(goodLines.filterNot(_ == "PROBE done")),
      "noise over the bound" -> probe(goodLines ++ (0 to MaxNoise).map(i => s"chatter $i"))
    )
    probeCases.foreach { case (label, ps) =>
      if (ps.isEmpty) problems += s"probe evaluation missed: $label"
    }
    if (probe(goodLines).nonEmpty) problems += "probe evaluation rejected a well-formed run"
    if (probe(goodLines :+ s"INFO x: $nonce-INFO", allowInfo = true).nonEmpty)
      problems += "probe evaluation rejected INFO under an owner configuration"
    val fed = staticCases.size + passCases.size + configCases.size + 1 + probeCases.size + 2
    (problems.toList, fed)
  }

  // --- The task ---------------------------------------------------------------------------------

  def run(
      buildBase: File,
      inputs: Seq[ProjectInputs],
      targets: Seq[ProbeTarget],
      javaHome: Option[File],
      env: Map[String, String],
      workDir: File,
      log: Logger
  ): Unit = {
    val lines = ListBuffer.empty[Line]
    val (selfProblems, fed) = selfTestProblems(buildBase)
    lines += Line(
      if (selfProblems.isEmpty) "PASS" else "FAIL",
      "self-test",
      s"$fed synthetic inputs (static rules, configuration rules, probe evaluation); " +
        s"problems [${names(selfProblems)}]"
    )
    inputs.sortBy(_.project).foreach(p => lines += staticLine(p, buildBase, _.isFile))
    Backends.foreach { b =>
      val f = buildConfig(buildBase, b)
      val text = Try(IO.read(f, StandardCharsets.UTF_8)).toOption
      val ps = text.map(buildConfigProblems(b, _)).getOrElse(Seq("missing or unreadable"))
      lines += Line(
        if (ps.isEmpty) "PASS" else "FAIL",
        s"build-config-${b.label}",
        s"${rel(buildBase, f)}: root level WARN or less verbose required; problems [${names(ps)}]"
      )
    }

    val java = javaHome.getOrElse(file(sys.props("java.home"))) / "bin" / "java"
    IO.delete(workDir)
    IO.createDirectory(workDir)
    val source = workDir / s"$ProbeClass.java"
    IO.write(source, probeSource, StandardCharsets.UTF_8)
    targets.foreach { t =>
      val nonce = UUID.randomUUID().toString.replace("-", "")
      val usesBuildConfig =
        t.backend.configProperties.exists(k => sysProp(t.javaOptions, k).nonEmpty)
      // 1. The project's real forked Test JVM options: must be bounded.
      val (exit, out) =
        runProbe(java, workDir, s"${t.project}-enforced", source, t, t.javaOptions, env, nonce)
      val v = evaluateProbe(exit, out, nonce, t.backend.factoryClass, allowInfo = !usesBuildConfig)
      lines += Line(
        if (v.problems.isEmpty) "PASS" else "FAIL",
        s"${t.project}-probe",
        s"factory ${v.factory}; ${ProbeLoggers.size} loggers DEBUG/TRACE off" +
          s"${if (usesBuildConfig) ", INFO off" else ""}, WARN/ERROR on; other lines ${v.noise}; " +
          s"configuration ${if (usesBuildConfig) "build" else "owner"}; problems [${names(v.problems)}]"
      )
      // 2. Counter-probe: the same JVM with a DEBUG configuration must be detected.
      val (debugName, debugText) = DebugConfigs(t.backend)
      val debugFile = workDir / s"${t.project}-$debugName"
      IO.write(debugFile, debugText, StandardCharsets.UTF_8)
      val counterOptions = t.javaOptions.filterNot(isLoggingOption) ++
        sysProp(t.javaOptions, ProviderProperty).map(c => s"-D$ProviderProperty=$c").toSeq ++
        Seq(s"-D${t.backend.configProperty}=${debugFile.getAbsolutePath}", VerbosityOption)
      val (cExit, cOut) =
        runProbe(java, workDir, s"${t.project}-counter", source, t, counterOptions, env, nonce)
      val c = evaluateProbe(cExit, cOut, nonce, t.backend.factoryClass, allowInfo = false)
      val detected = c.problems.nonEmpty && c.rootDebug.contains(true)
      lines += Line(
        if (detected) "PASS" else "FAIL",
        s"${t.project}-counter-probe",
        s"generated DEBUG ${t.backend.label} configuration detected: $detected " +
          s"(ROOT debug ${c.rootDebug.getOrElse("unknown")}, problems ${c.problems.size})"
      )
      // 3. Baseline (diagnosis evidence only): the same JVM without the build's logging options.
      val (bExit, bOut) = runProbe(
        java,
        workDir,
        s"${t.project}-baseline",
        source,
        t,
        t.javaOptions.filterNot(isLoggingOption),
        env,
        nonce
      )
      val b = evaluateProbe(bExit, bOut, nonce, t.backend.factoryClass, allowInfo = false)
      lines += Line(
        "INFO",
        s"${t.project}-baseline",
        s"without the build logging options: exit $bExit, factory ${b.factory}, ROOT debug " +
          s"${b.rootDebug.getOrElse("unknown")}, other lines ${b.noise}, deviations ${b.problems.size}"
      )
    }

    lines.foreach(l => log.info(l.render))
    val failed = lines.filter(_.failed)
    if (failed.nonEmpty)
      throw new MessageOnlyException(
        s"$Task: ${failed.size} check(s) failed: ${failed.map(_.id).mkString(", ")}"
      )
    log.info(s"$Task: OK, ${lines.count(_.tag == "PASS")} checks passed")
  }
}
