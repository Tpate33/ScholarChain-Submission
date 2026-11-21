package scholarchain.storage.load

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.util.zip.{CRC32, GZIPInputStream}

import scala.util.control.NonFatal

import scholarchain.core.*
import scholarchain.storage.model.{Bytes, StoreError}
import scholarchain.storage.port.RunSource

/**
 * Bounds of the upstream readers (SE-P7-07 item 1). Configuration: passed in, no default.
 *
 * @param maxInflatedBytes
 *   the most bytes one gzip part may inflate to; a part that would inflate further is refused after
 *   reading at most this many + 1 inflated bytes
 * @param maxRecordsPerPart
 *   the most lines (records) one part may hold
 */
final case class UpstreamBounds private (maxInflatedBytes: Long, maxRecordsPerPart: Int)

object UpstreamBounds {
  def of(maxInflatedBytes: Long, maxRecordsPerPart: Int): Either[StoreError, UpstreamBounds] =
    if (maxInflatedBytes < 1 || maxInflatedBytes > Int.MaxValue - 8)
      Left(
        StoreError.Refused(s"maxInflatedBytes: $maxInflatedBytes is outside 1..${Int.MaxValue - 8}")
      )
    else if (maxRecordsPerPart < 1)
      Left(StoreError.Refused(s"maxRecordsPerPart: $maxRecordsPerPart is below 1"))
    else Right(new UpstreamBounds(maxInflatedBytes, maxRecordsPerPart))
}

/**
 * The grouping run, read and verified by [[UpstreamRuns.grouping]] exactly like an analysis run
 * directory: `_SUCCESS`, `_parts.json` (its SHA-256 recorded), the run manifest bound to it by run
 * ID, every file observed with its manifest byte count as the read bound and compared by
 * [[PartManifests.verify]] (file set, bytes, SHA-256, LF rows), at least one part of each grouping
 * schema ([[AnalysisValidation.checkGroupingRunParts]]), and every row decoded through its core
 * contract. Only that reader creates one.
 */
final class GroupingRun private[load] (
    val runId: String,
    val partManifestSha256: String,
    val manifest: RunManifest,
    val graphInput: GraphInput,
    val authorships: Vector[AuthorshipRecordV2]
)

/**
 * The curated Hadoop run, read and verified by [[UpstreamRuns.curated]]: the Hadoop layout has no
 * `_parts.json`, so the checks are the ones the layout allows, each fail-closed:
 *   - every regular file has its Hadoop checksum sidecar `.<name>.crc` and every sidecar its file;
 *     no other hidden file; each sidecar verifies (CRC32 per chunk). CRC32 detects accidental
 *     corruption only: it is NOT a cryptographic check, and a deliberate change with a recomputed
 *     sidecar passes it (no historical SHA-256 exists for these files);
 *   - the run manifest decodes (stage `hadoop.pipeline`, output codec gzip) and its run ID is the
 *     run's identity (bound to the analysis run's `inputRunId` by the verifier);
 *   - every stage read has its `_SUCCESS` and `_counters.json`, and every output read has exactly
 *     the records its `EMITTED.<output>` counter states;
 *   - every gzip part is inflated by the JDK only, at most `maxInflatedBytes`, decoded as strict
 *     UTF-8 LF-terminated lines, each through its core record contract.
 */
final class CuratedRun private[load] (
    val runId: String,
    val manifest: RunManifest,
    val works: Vector[WorkRecord],
    val mentions: Vector[AuthorMention],
    val venues: Vector[Venue],
    val institutions: Vector[Institution],
    val topics: Vector[Topic],
    val checkedFiles: Int
)

/** Strict, bounded decoding of Hadoop gzip parts and checksum sidecars (no Hadoop code). */
object HadoopFiles {

  private val CrcMagic: Array[Byte] = Array('c'.toByte, 'r'.toByte, 'c'.toByte, 0.toByte)

  /** The sidecar path of `path` (`dir/.<name>.crc`). */
  def sidecarOf(path: String): String = {
    val i = path.lastIndexOf('/')
    val (dir, name) = if (i < 0) ("", path) else (path.substring(0, i + 1), path.substring(i + 1))
    s"$dir.$name.crc"
  }

  /**
   * Why `crc` is not the Hadoop `ChecksumFileSystem` sidecar of `data`, or None: magic `crc\0`, a
   * big-endian bytes-per-checksum > 0, then exactly one big-endian CRC32 per chunk of `data`. CRC32
   * is not cryptographic.
   */
  def crcProblem(data: Array[Byte], crc: Array[Byte]): Option[String] =
    if (crc.length < 8 || !java.util.Arrays.equals(crc, 0, 4, CrcMagic, 0, 4))
      Some("not a Hadoop checksum file")
    else {
      val bb = ByteBuffer.wrap(crc)
      bb.position(4)
      val per = bb.getInt
      if (per <= 0) Some(s"bytes per checksum $per")
      else {
        val chunks = (data.length.toLong + per - 1) / per
        if (crc.length.toLong != 8L + 4L * chunks)
          Some(s"${(crc.length - 8) / 4} checksums for $chunks chunks")
        else {
          var i = 0L
          var bad: Option[String] = None
          while (bad.isEmpty && i < chunks) {
            val from = (i * per).toInt
            val len = math.min(per.toLong, data.length.toLong - from).toInt
            val c = new CRC32
            c.update(data, from, len)
            if (c.getValue.toInt != bb.getInt) bad = Some(s"chunk $i differs from its CRC32")
            i += 1
          }
          bad
        }
      }
    }

  /** The JDK-inflated content of a gzip part, refused beyond `maxInflated` bytes. */
  def gunzip(path: String, gz: Array[Byte], maxInflated: Long): Either[StoreError, Array[Byte]] =
    try {
      val in = new GZIPInputStream(new ByteArrayInputStream(gz))
      try {
        val out = new ByteArrayOutputStream()
        val buf = new Array[Byte](8192)
        var total = 0L
        var done = false
        var over = false
        while (!done && !over) {
          val want = math.min(buf.length.toLong, maxInflated + 1 - total).toInt
          val n = in.read(buf, 0, math.max(want, 1))
          if (n < 0) done = true
          else {
            total += n
            if (total > maxInflated) over = true else out.write(buf, 0, n)
          }
        }
        if (over) Left(StoreError.Refused(s"$path: inflates to more than $maxInflated bytes"))
        else Right(out.toByteArray)
      } finally in.close()
    } catch {
      case e: IOException =>
        Left(StoreError.Corrupt(s"$path: not a readable gzip part (${e.getClass.getSimpleName})"))
    }

  /** Strict UTF-8 text (CR-P7-09 m3): malformed or unmappable input is refused, never replaced. */
  def strictText(path: String, content: Array[Byte]): Either[StoreError, String] =
    try
      Right(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content))
          .toString
      )
    catch {
      case _: CharacterCodingException => Left(StoreError.Corrupt(s"$path: not valid UTF-8"))
    }

  /** Strict UTF-8 LF-terminated lines (an empty content has none), at most `maxLines`. */
  def lines(path: String, content: Array[Byte], maxLines: Int): Either[StoreError, Vector[String]] =
    try {
      val text = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(content))
        .toString
      if (text.isEmpty) Right(Vector.empty)
      else if (!text.endsWith("\n")) Left(StoreError.Corrupt(s"$path: does not end in LF"))
      else {
        val ls = text.stripSuffix("\n").split("\n", -1).toVector
        if (ls.size > maxLines)
          Left(StoreError.Refused(s"$path: more than $maxLines records"))
        else if (ls.exists(_.isEmpty)) Left(StoreError.Corrupt(s"$path: an empty line"))
        else Right(ls)
      }
    } catch {
      case _: CharacterCodingException => Left(StoreError.Corrupt(s"$path: not valid UTF-8"))
    }
}

/**
 * The readers of the upstream runs a load needs (SE-P7-07 item 1; CR-P7-08 m2): the grouping run
 * and the curated Hadoop run, each through a [[RunSource]] (bounded, never following links). The
 * only way to a [[SummaryUpstream]] is [[upstream]], from two verified runs.
 */
object UpstreamRuns {

  private def decodeLines[A](path: String, ls: Vector[String])(using
      c: RecordContract[A]
  ): Either[Vector[String], Vector[A]] = {
    val out = Vector.newBuilder[A]
    val errors = Vector.newBuilder[String]
    ls.zipWithIndex.foreach { case (l, i) =>
      Json.decode[A](l) match {
        case Right(a) => out += a
        case Left(es) => errors ++= es.map(e => s"$path#${i + 1}: ${e.message}")
      }
    }
    val e = errors.result()
    if (e.isEmpty) Right(out.result()) else Left(e)
  }

  /** One LF-terminated row of strict UTF-8 (CR-P7-10 n4: never a lenient decode). */
  private def oneRow(path: String, b: Bytes): Either[Vector[String], String] =
    HadoopFiles.strictText(path, b.toArray).left.map(err).flatMap { t =>
      if (t.endsWith("\n") && !t.stripSuffix("\n").contains('\n')) Right(t.stripSuffix("\n"))
      else Left(Vector(s"$path: is not one LF-terminated row"))
    }

  private def err(e: StoreError): Vector[String] = Vector(e.message)

  // ------------------------------------------------------------------ grouping run

  /** Reads and verifies a grouping run directory (see [[GroupingRun]]). */
  def grouping(src: RunSource, bounds: UpstreamBounds): Either[Vector[String], GroupingRun] =
    for {
      files <- src.listFiles().left.map(err)
      _ <-
        if (files.contains(PartManifest.SuccessMarker)) Right(())
        else Left(Vector("grouping run: no _SUCCESS marker: not a published run"))
      _ <-
        if (files.contains(PartManifest.FileName)) Right(())
        else Left(Vector("grouping run: no _parts.json"))
      partsBytes <- src.read(PartManifest.FileName).left.map(err)
      partsText <- HadoopFiles
        .strictText(PartManifest.FileName, partsBytes.toArray)
        .left
        .map(e => Vector("grouping run: " + e.message))
      pm <- PartManifests.decode(partsText).left.map(_.map(_.message))
      v2 = PartManifests.asV2(pm)
      listed = v2.files.map(f => f.path -> f).toMap
      read = (p: String) =>
        listed.get(p) match {
          case Some(f) => src.readAtMost(p, f.byteCount)
          case None => src.read(p) // unlisted: refused below whatever its bytes
        }
      observedBytes <- files.foldLeft[Either[Vector[String], Map[String, Bytes]]](
        Right(Map.empty)
      ) { (acc, p) =>
        acc.flatMap { m =>
          (if (p == PartManifest.FileName) Right(partsBytes) else read(p)) match {
            case Left(e) => Left(Vector(s"$p: ${e.message}"))
            case Right(b) => Right(m.updated(p, b))
          }
        }
      }
      rmText <- observedBytes
        .get(PartManifest.RunManifestFile)
        .toRight(Vector("grouping run: no _run-manifest.json"))
        .flatMap(oneRow(PartManifest.RunManifestFile, _))
      rm <- Json.decode[RunManifest](rmText).left.map(_.map(_.message))
      runId <- RunId.of(rm).left.map(e => Vector(e.message))
      _ <-
        if (runId == v2.runId) Right(())
        else
          Left(
            Vector(s"grouping run: _parts.json names ${v2.runId}, the run manifest gives $runId")
          )
      observed = files.map(p => ObservedFile.of(p, observedBytes(p).toArray))
      _ <- {
        val problems = AnalysisValidation.checkGroupingRunParts(pm, runId, observed)
        if (problems.isEmpty) Right(()) else Left(problems.map(_.message))
      }
      parts = v2.files.filter(_.path != PartManifest.RunManifestFile)
      decoded <- parts.foldLeft[Either[Vector[String], Vector[(String, Vector[String])]]](
        Right(Vector.empty)
      ) { (acc, f) =>
        acc.flatMap(xs =>
          HadoopFiles
            .lines(f.path, observedBytes(f.path).toArray, bounds.maxRecordsPerPart)
            .left
            .map(err)
            .map(ls => xs :+ (f.recordSchemaVersion -> ls))
        )
      }
      researchers <- all[ResearcherAssignment](parts, decoded, SchemaVersions.ResearcherAssignment)
      works <- all[WorkAssignment](parts, decoded, SchemaVersions.WorkAssignment)
      authorships <- all[AuthorshipRecordV2](parts, decoded, SchemaVersions.AuthorshipRecordV2)
      teams <- all[PublicationTeam](parts, decoded, SchemaVersions.PublicationTeam)
      _ <-
        if (teams.nonEmpty && works.nonEmpty && researchers.nonEmpty) Right(())
        else
          Left(
            Vector(
              "grouping run: no team, work assignment or researcher assignment: an empty graph input is never accepted"
            )
          )
    } yield new GroupingRun(
      runId,
      partsBytes.sha256,
      rm,
      GraphInput(teams, works, researchers),
      authorships
    )

  private def all[A](
      parts: Vector[PartFileV2],
      decoded: Vector[(String, Vector[String])],
      schema: String
  )(using c: RecordContract[A]): Either[Vector[String], Vector[A]] =
    parts
      .zip(decoded)
      .filter(_._1.recordSchemaVersion == schema)
      .foldLeft[
        Either[Vector[String], Vector[A]]
      ](Right(Vector.empty)) { case (acc, (f, (_, ls))) =>
        acc.flatMap(xs => decodeLines[A](f.path, ls).map(xs ++ _))
      }

  // ------------------------------------------------------------------ curated run

  /** The stage and output directories the canonical graph and summary checks read. */
  val CuratedOutputs: Vector[(String, String)] = Vector(
    "normalize" -> "works",
    "normalize" -> "mentions",
    "normalize" -> "venues",
    "normalize" -> "institutions",
    "normalize" -> "topics"
  )

  private val HadoopStage = "hadoop.pipeline"
  private val CodecParameter = "scholarchain.codec.output"
  private val GzipCodec = "org.apache.hadoop.io.compress.GzipCodec"
  private val CountersFile = "_counters.json"
  private val PartName = "^part-[mr]-[0-9]{5}\\.gz$".r

  /** Every file of `files` with a verified checksum sidecar; the number of data files checked. */
  def checkSidecars(src: RunSource, files: Vector[String]): Either[Vector[String], Int] = {
    def name(p: String) = p.substring(p.lastIndexOf('/') + 1)
    val (hidden, data) = files.partition(p => name(p).startsWith("."))
    val sidecars = hidden.toSet
    val orphans = hidden.filterNot(h =>
      name(h).endsWith(".crc") && data.exists(d => HadoopFiles.sidecarOf(d) == h)
    )
    val missing = data.filterNot(d => sidecars.contains(HadoopFiles.sidecarOf(d)))
    if (orphans.nonEmpty)
      Left(orphans.map(o => s"$o: a hidden file that is not the checksum sidecar of a run file"))
    else if (missing.nonEmpty) Left(missing.map(m => s"$m: no checksum sidecar"))
    else {
      val problems = data.flatMap { d =>
        (src.read(d), src.read(HadoopFiles.sidecarOf(d))) match {
          case (Right(b), Right(c)) =>
            HadoopFiles.crcProblem(b.toArray, c.toArray).map(w => s"$d: CRC32 sidecar: $w")
          case (Left(e), _) => Some(s"$d: ${e.message}")
          case (_, Left(e)) => Some(s"${HadoopFiles.sidecarOf(d)}: ${e.message}")
        }
      }
      if (problems.isEmpty) Right(data.size) else Left(problems)
    }
  }

  /** The `counters` object of `<stage>/_counters.json`, after `<stage>/_SUCCESS`. */
  def stageCounters(
      src: RunSource,
      files: Vector[String],
      stage: String
  ): Either[Vector[String], Map[String, Long]] =
    if (!files.contains(s"$stage/${PartManifest.SuccessMarker}"))
      Left(Vector(s"$stage: no _SUCCESS: the stage is not complete"))
    else if (!files.contains(s"$stage/$CountersFile"))
      Left(Vector(s"$stage: no $CountersFile"))
    else
      src.read(s"$stage/$CountersFile").left.map(err).flatMap { b =>
        HadoopFiles.strictText(s"$stage/$CountersFile", b.toArray).left.map(err).flatMap { text =>
          try {
            val v = ujson.read(text)
            if (v("stage").str != stage)
              Left(Vector(s"$stage/$CountersFile: names stage ${v("stage").str}"))
            else
              Right(v("counters").obj.toVector.map { case (k, n) =>
                val d = n.num
                if (d < 0 || d != math.rint(d) || d > 9.0e15)
                  throw new IllegalArgumentException(s"counter $k is not a count")
                k -> d.toLong
              }.toMap)
          } catch {
            case NonFatal(e) =>
              Left(Vector(s"$stage/$CountersFile: unreadable (${e.getClass.getSimpleName})"))
          }
        }
      }

  /** Every line of the gzip parts of `<stage>/<output>/`, in part-name order. */
  def outputLines(
      src: RunSource,
      files: Vector[String],
      stage: String,
      output: String,
      bounds: UpstreamBounds
  ): Either[Vector[String], Vector[String]] = {
    val dir = s"$stage/$output/"
    val inDir = files.filter(p => p.startsWith(dir) && !p.substring(dir.length).contains('/'))
    val parts = inDir.filterNot(p => p.substring(dir.length).startsWith("."))
    parts.find(p => !PartName.matches(p.substring(dir.length))) match {
      case Some(p) => Left(Vector(s"$p: not a gzip part file name"))
      case None =>
        parts.sorted.foldLeft[Either[Vector[String], Vector[String]]](Right(Vector.empty)) {
          (acc, p) =>
            acc.flatMap { xs =>
              (for {
                gz <- src.read(p)
                text <- HadoopFiles.gunzip(p, gz.toArray, bounds.maxInflatedBytes)
                ls <- HadoopFiles.lines(p, text, bounds.maxRecordsPerPart)
              } yield xs ++ ls).left.map(err)
            }
        }
    }
  }

  /** Reads and verifies a curated Hadoop run directory (see [[CuratedRun]]). */
  def curated(source: RunSource, bounds: UpstreamBounds): Either[Vector[String], CuratedRun] = {
    // CR-P7-09 m3: every file is read once; its CRC is checked and it is decoded from those bytes.
    val src = new ReadOnce(source)
    for {
      files <- src.listFiles().left.map(err)
      checked <- checkSidecars(src, files)
      rmBytes <-
        if (files.contains(PartManifest.RunManifestFile))
          src.read(PartManifest.RunManifestFile).left.map(err)
        else Left(Vector("curated run: no _run-manifest.json"))
      // Strict UTF-8 and exactly one row. SE-P7-08 finding: the Hadoop pipeline writes its run
      // manifest WITHOUT a final LF (the fixture's), so one optional final LF is accepted; any
      // other line break, an empty row or invalid UTF-8 is refused.
      rmAll <- HadoopFiles.strictText(PartManifest.RunManifestFile, rmBytes.toArray).left.map(err)
      rmText <- {
        val t = rmAll.stripSuffix("\n")
        if (t.nonEmpty && !t.exists(c => c == '\n' || c == '\r')) Right(t)
        else Left(Vector(s"${PartManifest.RunManifestFile}: is not exactly one row"))
      }
      rm <- Json.decode[RunManifest](rmText).left.map(_.map(_.message))
      _ <-
        if (rm.stage == HadoopStage) Right(())
        else Left(Vector(s"curated run: stage ${rm.stage} is not $HadoopStage"))
      _ <-
        if (Option(rm.parameters).flatMap(_.get(CodecParameter)).contains(GzipCodec)) Right(())
        else
          Left(
            Vector(
              s"curated run: $CodecParameter is not $GzipCodec (the reader inflates gzip only)"
            )
          )
      runId <- RunId.of(rm).left.map(e => Vector(e.message))
      byOutput <- CuratedOutputs.foldLeft[Either[Vector[String], Map[String, Vector[String]]]](
        Right(Map.empty)
      ) { case (acc, (stage, output)) =>
        for {
          m <- acc
          counters <- stageCounters(src, files, stage)
          ls <- outputLines(src, files, stage, output, bounds)
          _ <- counters.get(s"EMITTED.$output") match {
            case Some(n) if n == ls.size.toLong => Right(())
            case Some(n) =>
              Left(Vector(s"$stage/$output: EMITTED.$output is $n, decoded ${ls.size}"))
            case None => Left(Vector(s"$stage/$output: no EMITTED.$output counter"))
          }
        } yield m.updated(output, ls)
      }
      works <- decodeLines[WorkRecord]("normalize/works", byOutput("works"))
      mentions <- decodeLines[AuthorMention]("normalize/mentions", byOutput("mentions"))
      venues <- decodeLines[Venue]("normalize/venues", byOutput("venues"))
      institutions <- decodeLines[Institution]("normalize/institutions", byOutput("institutions"))
      topics <- decodeLines[Topic]("normalize/topics", byOutput("topics"))
      _ <-
        if (works.nonEmpty) Right(())
        else Left(Vector("curated run: no work record: an empty curated input is never accepted"))
    } yield new CuratedRun(runId, rm, works, mentions, venues, institutions, topics, checked)
  }

  /**
   * A source that reads each path at most once and returns the same bytes on every later read
   * (CR-P7-09 m3), so a file's CRC check and its decoding see identical bytes. Errors are cached
   * too. The underlying source's bounds still apply to the one read.
   */
  private final class ReadOnce(underlying: RunSource) extends RunSource {
    private val cache = scala.collection.mutable.Map.empty[String, Either[StoreError, Bytes]]
    def listFiles() = underlying.listFiles()
    def read(relativePath: String) =
      cache.getOrElseUpdate(relativePath, underlying.read(relativePath))
    def readAtMost(relativePath: String, maxBytes: Long) =
      read(relativePath).flatMap(b =>
        if (maxBytes < 0) Left(StoreError.Refused(s"maxBytes $maxBytes is below 0"))
        else if (b.size.toLong > maxBytes)
          Left(StoreError.Refused(s"$relativePath: more than $maxBytes bytes"))
        else Right(b)
      )
  }

  // ------------------------------------------------------------------ the bound upstream

  /**
   * The upstream of an analysis run, from a verified grouping run and a verified curated run (the
   * only constructor of [[SummaryUpstream]]). The binding to the analysis run (its run manifest's
   * `groupingRunId` and `inputRunId`) is checked by the verifier ([[SummaryEvidenceCheck.bound]]).
   *
   * @param benchmarkPairs
   *   the run's benchmark pair list as read by [[BenchmarkPairsReader]] (SE-P7-08: only a read
   *   list, never a caller-built one), when available; checkPaths and checkRunChain need it, and
   *   the verifier binds its core digest to the analysis run manifest (check `benchmark-pairs`)
   */
  def upstream(
      grouping: GroupingRun,
      curated: CuratedRun,
      benchmarkPairs: Option[VerifiedBenchmarkPairs]
  ): SummaryUpstream =
    new SummaryUpstream(grouping, curated, benchmarkPairs)
}
