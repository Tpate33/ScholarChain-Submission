import java.io.{File, FileInputStream}
import java.util.{Locale, Properties}

import scala.collection.JavaConverters._
import scala.io.Source

/**
 * Reader for the single version ledger `project/versions.properties`.
 *
 * The ledger is resolved from sbt's build base (`ThisBuild / baseDirectory`), never from the JVM
 * working directory. Loading fails (and with it the build load) when the ledger is missing, a key
 * is absent, or a value is floating (latest, SNAPSHOT, RELEASE, ranges, `+`, wildcards,
 * whitespace).
 */
object Ledger {
  val relativePath: String = "project/versions.properties"

  // Keep identical to the meta-build copy in project/plugins.sbt.
  private val forbiddenChars: Set[Char] = "+[](),*{}<>$? \t".toSet

  def floating(value: String): Boolean = {
    val lower = value.toLowerCase(Locale.ROOT)
    value.isEmpty || lower.contains("latest") || lower.contains("snapshot") ||
    lower == "release" || value.exists(forbiddenChars.contains)
  }

  final case class Versions(file: File, entries: Map[String, String]) {
    def apply(key: String): String =
      entries.getOrElse(key, sys.error(s"Version ledger ${file.getPath} has no value for '$key'"))
  }

  def load(buildBase: File): Versions = {
    val file = new File(buildBase, relativePath).getCanonicalFile
    if (!file.isFile) sys.error(s"Version ledger not found: ${file.getPath}")
    val props = new Properties()
    val in = new FileInputStream(file)
    try props.load(in)
    finally in.close()
    val all = props.stringPropertyNames.asScala.toSeq.map(k => k -> props.getProperty(k).trim).toMap
    val bad = all.toSeq.sorted.collect { case (k, v) if floating(v) => s"$k=$v" }
    if (bad.nonEmpty)
      sys.error(
        s"Version ledger ${file.getPath} has floating or invalid values: ${bad.mkString(", ")}"
      )
    Versions(file, all)
  }

  private val ScalafmtVersionLine = """^\s*version\s*=\s*"?([^"\s]+)"?\s*$""".r

  /** Problems with the tool-mandated mirrors of ledger values (empty when consistent). */
  def mirrorProblems(
      versions: Versions,
      buildBase: File,
      runningSbtVersion: String
  ): Seq[String] = {
    val sbtProblem =
      if (runningSbtVersion == versions("sbt")) Nil
      else
        Seq(
          s"running sbt $runningSbtVersion != ledger sbt=${versions("sbt")} (project/build.properties must mirror the ledger)"
        )
    val conf = new File(buildBase, ".scalafmt.conf")
    val confVersions =
      if (!conf.isFile) Nil
      else {
        val src = Source.fromFile(conf, "UTF-8")
        try src.getLines().collect { case ScalafmtVersionLine(v) => v }.toList
        finally src.close()
      }
    val fmtProblem =
      if (confVersions == List(versions("scalafmt"))) Nil
      else
        Seq(
          s".scalafmt.conf version ${confVersions.mkString(",")} != ledger scalafmt=${versions("scalafmt")}"
        )
    sbtProblem ++ fmtProblem
  }

  // A string literal in the version position of a ModuleID: `% "name" % "1.2.3"` (or `%%`),
  // possibly split across lines. Versions must come from the ledger instead.
  private val LiteralVersion = """%%?\s*"[^"\n]*"\s*%\s*"([^"\n]*)"""".r

  /** Drops a `//` line comment that is not inside a double-quoted string literal. */
  private def stripLineComment(line: String): String = {
    var inString = false
    var i = 0
    var cut = line.length
    while (i < line.length && cut == line.length) {
      val c = line.charAt(i)
      if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) inString = !inString
      else if (!inString && c == '/' && i + 1 < line.length && line.charAt(i + 1) == '/') cut = i
      i += 1
    }
    line.substring(0, cut)
  }

  /** `file:line` reports of literal dependency versions in the given build files. */
  def literalVersionProblems(buildBase: File, files: Seq[File]): Seq[String] =
    files.filter(_.isFile).sortBy(_.getPath).flatMap { f =>
      val src = Source.fromFile(f, "UTF-8")
      val text =
        try src.getLines().map(stripLineComment).mkString("\n")
        finally src.close()
      val name = buildBase.toPath.relativize(f.toPath).toString
      LiteralVersion
        .findAllMatchIn(text)
        .map { m =>
          val line = text.substring(0, m.start).count(_ == '\n') + 1
          s"$name:$line literal dependency version '${m.group(1)}' (take it from the ledger)"
        }
        .toList
    }
}
