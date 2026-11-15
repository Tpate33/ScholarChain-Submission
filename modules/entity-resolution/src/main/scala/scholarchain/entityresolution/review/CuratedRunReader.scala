package scholarchain.entityresolution.review

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scholarchain.core.{Json, MatchCandidate, RecordContract, SourceAuthorProfile, WorkRecord}

/**
 * The curated records one review queue needs from a completed pipeline run: the normalized works
 * (with their author mentions), the source author profiles, and the final match candidates.
 */
final case class CuratedRun(
    runDir: Path,
    works: Vector[WorkRecord],
    profiles: Vector[SourceAuthorProfile],
    candidates: Vector[MatchCandidate]
)

/**
 * Reads a completed curated run directory as DATA (pipeline stages exchange data, not code; this
 * module never imports the Hadoop module). Layout (Hadoop `job-contracts.md`): `normalize/works`,
 * `normalize/profiles`, and `candidates/final/{researcher,work}` hold JSON Lines part files (plain
 * or gzip, `part-*`), and `normalize/_SUCCESS` and `candidates/_SUCCESS` mark completed stages.
 * Every line is decoded and validated through its core contract; any failure refuses the run.
 */
object CuratedRunReader {

  private def partFiles(dir: Path): Vector[Path] =
    if (!Files.isDirectory(dir)) Vector.empty
    else {
      val s = Files.list(dir)
      try
        s.iterator.asScala.toVector
          .filter(p => p.getFileName.toString.startsWith("part-") && Files.isRegularFile(p))
          .sortBy(_.getFileName.toString)
      finally s.close()
    }

  private def lines(p: Path): Vector[String] = {
    val raw: InputStream = Files.newInputStream(p)
    val in = if (p.getFileName.toString.endsWith(".gz")) new GZIPInputStream(raw) else raw
    val r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
    try Iterator.continually(r.readLine()).takeWhile(_ != null).filter(_.nonEmpty).toVector
    finally r.close()
  }

  private def decodeDir[T](dir: Path)(using RecordContract[T]): Either[String, Vector[T]] = {
    val decoded = partFiles(dir).flatMap(f => lines(f).map(l => f -> Json.decode[T](l)))
    decoded.collectFirst { case (f, Left(e)) => (f, e) } match {
      case Some((f, e)) => Left(s"$f: ${e.map(_.message).mkString("; ")}")
      case None => Right(decoded.collect { case (_, Right(t)) => t })
    }
  }

  def read(runDir: Path): Either[String, CuratedRun] =
    try {
      val missing = Vector("normalize/_SUCCESS", "candidates/_SUCCESS")
        .filterNot(m => Files.isRegularFile(runDir.resolve(m)))
      if (missing.nonEmpty)
        Left(s"$runDir is not a completed run (missing ${missing.mkString(", ")})")
      else
        for {
          works <- decodeDir[WorkRecord](runDir.resolve("normalize/works"))
          profiles <- decodeDir[SourceAuthorProfile](runDir.resolve("normalize/profiles"))
          researcher <- decodeDir[MatchCandidate](runDir.resolve("candidates/final/researcher"))
          work <- decodeDir[MatchCandidate](runDir.resolve("candidates/final/work"))
        } yield CuratedRun(runDir, works, profiles, researcher ++ work)
    } catch {
      case NonFatal(e) => Left(s"cannot read $runDir: ${e.getClass.getName}: ${e.getMessage}")
    }

  /**
   * Every run directory (`.../run=<id>`) below `root`, sorted. Empty when `root` is absent or
   * changes while it is listed (for example, deleted by a concurrently running pipeline test).
   */
  def runDirs(root: Path): Vector[Path] =
    try
      if (!Files.isDirectory(root)) Vector.empty
      else {
        val s = Files.walk(root, 4)
        try
          s.iterator.asScala.toVector
            .filter(p => Files.isDirectory(p) && p.getFileName.toString.startsWith("run="))
            .sortBy(_.toString)
        finally s.close()
      }
    catch { case NonFatal(_) => Vector.empty }
}
