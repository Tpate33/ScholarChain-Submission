package scholarchain.hadoop.dblp

import java.io.{BufferedInputStream, DataInput, DataOutput, InputStream}

import scala.collection.mutable

import org.apache.hadoop.fs.{FSDataInputStream, Path}
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.io.{Text, Writable}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.apache.hadoop.mapreduce.{InputSplit, JobContext, RecordReader, TaskAttemptContext}

import scholarchain.hadoop.io.Locator
import scholarchain.hadoop.{DblpRecordTypes, JobParams}

/** Status values of [[DblpRawRecord]]. */
object DblpRawRecord {

  /** Start tag through the matching end tag (or a self-closing start tag). */
  val Complete: Int = 0

  /** Cut off by end of input or by `maxRecordBytes`; rejected `unterminated-record`. */
  val Unterminated: Int = 1

  /**
   * No end tag before the next top-level record start tag; the bytes stop just before that tag
   * (`nextOffset`) and the reader resynchronizes there. Rejected `malformed-xml`.
   */
  val Interrupted: Int = 2

  /**
   * Not a record: a comment, CDATA section, processing instruction, or `<!` declaration whose
   * closer is not within `maxRecordBytes` bytes of its opener. The opener is read as text (see
   * [[DblpScanner]]); `recordType` names the construct (`#comment`, `#cdata`, `#pi`,
   * `#declaration`). Rejected `malformed-xml`.
   */
  val UnclosedMarkup: Int = 3

  val MarkupComment = "#comment"
  val MarkupCdata = "#cdata"
  val MarkupPi = "#pi"
  val MarkupDeclaration = "#declaration"
}

/**
 * One raw DBLP record: the bytes from the `<` of its start tag through the `>` of its end tag
 * (status [[DblpRawRecord.Complete]]), or a prefix of them for the other statuses (see the
 * companion). Records that are not complete are rejected by the mapper, never parsed.
 */
final class DblpRawRecord(
    var offset: Long,
    var recordType: String,
    var status: Int,
    var bytes: Array[Byte],
    var nextOffset: Long
) extends Writable {
  def this() = this(0L, "", DblpRawRecord.Complete, Array.emptyByteArray, -1L)

  def complete: Boolean = status == DblpRawRecord.Complete

  override def write(out: DataOutput): Unit = {
    out.writeLong(offset)
    out.writeUTF(recordType)
    out.writeByte(status)
    out.writeLong(nextOffset)
    out.writeInt(bytes.length)
    out.write(bytes)
  }

  override def readFields(in: DataInput): Unit = {
    offset = in.readLong()
    recordType = in.readUTF()
    status = in.readByte().toInt
    nextOffset = in.readLong()
    bytes = new Array[Byte](in.readInt())
    in.readFully(bytes)
  }
}

object DblpScanner {

  /** Longest markup opener prefix (`<![CDATA[` is 9 bytes) plus slack. */
  val OpenerSlack: Int = 16

  /** Bytes a split reader re-reads before its split start: `maxRecordBytes + OpenerSlack`. */
  def lookback(maxRecordBytes: Int): Long = maxRecordBytes.toLong + OpenerSlack

  /** Where a reader of a split starting at `start` begins lexing (and must position its stream). */
  def streamStart(start: Long, maxRecordBytes: Int): Long =
    math.max(0L, start - lookback(maxRecordBytes))

  private val ExcerptBytes = 256

  // Token kinds.
  private val TokEof = 0
  private val TokText = 1
  private val TokTag = 2
  private val TokStart = 3
  private val TokEnd = 4
  private val TokMarkup = 5
  private val TokMarkupUnclosed = 6

  // Markup kinds for the closer search.
  private val KComment = 0
  private val KCdata = 1
  private val KPi = 2
  private val KDecl = 3

  private def isSpace(c: Int): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

  private def isNameStart(c: Int): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':' || c >= 0x80

  private def isNameChar(c: Int): Boolean =
    c >= 0 && (isNameStart(c) || (c >= '0' && c <= '9') || c == '-' || c == '.')

  /** Bounded capture buffer of one record; `total` keeps counting past the limit. */
  private final class Capture(limit: Int) {
    private var arr = new Array[Byte](512)
    private var stored = 0
    private var total = 0L
    def size: Long = total
    def overflow: Boolean = total > limit
    def write(b: Int): Unit = {
      if (stored <= limit) {
        if (stored == arr.length)
          arr = java.util.Arrays.copyOf(arr, math.min(arr.length * 2, limit + 1))
        arr(stored) = b.toByte
        stored += 1
      }
      total += 1
    }
    def truncateTo(n: Long): Unit = if (n < total) {
      total = n
      if (stored > n) stored = n.toInt
    }
    def bytes: Array[Byte] = java.util.Arrays.copyOf(arr, stored)
  }
}

/**
 * Split-safe scanner over DBLP XML bytes.
 *
 * '''Lexical model.''' The scanner is a byte-level lexer (never decodes, so any ASCII-compatible
 * encoding and multi-byte UTF-8 across split boundaries are safe). Outside markup constructs, a `<`
 * starts a start tag, an end tag, or a markup construct: comment `<!-- ... -->`, CDATA section
 * `<![CDATA[ ... ]]>`, processing instruction `<? ... ?>`, or declaration `<! ... >` (DOCTYPE with
 * quotes, a bracketed internal subset, and comments/PIs nested in it). Nothing inside a markup
 * construct is a tag. Attribute values cannot contain `<` (XML), so tags need no quote tracking for
 * synchronization; only an owned record's own start tag is read quote-aware (to find its `>`).
 *
 * '''Markup bound (W = `maxRecordBytes`).''' A markup opener opens a construct only if its closer
 * lies within W bytes after the opener prefix; otherwise it is an unclosed construct: the opener is
 * read as text (lexing continues right after the prefix) and the split that owns the opener's `<`
 * reports it once as a [[DblpRawRecord.UnclosedMarkup]] reject. This makes every construct at most
 * W + 9 bytes long by definition, so the lexical state at any offset is decided by the bytes within
 * the preceding W + 9 bytes and the following W bytes.
 *
 * '''Record starts.''' A record start is `<` + one of [[DblpRecordTypes.All]] + whitespace, `>` or
 * `/`, lexed outside markup. DBLP records are direct children of `<dblp>` and no DBLP field element
 * is named like a record type, so every such tag is a top-level record start.
 *
 * '''Record extent.''' From its start tag to the first `</name\s*>` outside markup
 * ([[DblpRawRecord.Complete]]); a self-closing start tag is complete. If another record start comes
 * first, the record is [[DblpRawRecord.Interrupted]] just before it and the scanner resynchronizes
 * at that start tag, so an unclosed record never swallows the records after it. End of input or
 * more than `maxRecordBytes` bytes make it [[DblpRawRecord.Unterminated]].
 *
 * '''Ownership rule.''' A record (or unclosed-markup report) belongs to the split `[start, end)`
 * containing the offset of its `<`. The scanner reads past `end` as far as needed to finish its
 * last record.
 *
 * '''Synchronization rule.''' A reader of a split starting at `start > 0` begins lexing in the
 * outside-markup state at `streamStart = max(0, start - (W + 16))` and emits nothing before
 * `start`. Any construct containing `start` has its opener after `streamStart` (constructs are at
 * most W + 9 bytes), so the lexer sees it and classifies `start` exactly as a whole-file read does.
 * Residual limitation: if `streamStart` itself lies inside a construct whose remaining bytes
 * contain a markup-opener look-alike (for example the text `<?` or `<![CDATA[` inside a comment),
 * and that look-alike's closer lies within W bytes, the two reads can disagree. Cost: at most W +
 * 16 extra bytes read per split.
 *
 * @param in
 *   positioned at byte `streamStart`
 */
final class DblpScanner(
    in: InputStream,
    streamStart: Long,
    start: Long,
    end: Long,
    maxRecordBytes: Int
) {
  import DblpScanner.*

  require(streamStart >= 0 && streamStart <= start && start <= end, "invalid scanner range")
  require(maxRecordBytes > 0, "maxRecordBytes must be positive")

  private val markupBound: Int = maxRecordBytes
  private val buf = new BufferedInputStream(in, 64 * 1024)
  private var pos: Long = streamStart
  private var pushed: Int = -2
  private val names: Set[String] = DblpRecordTypes.All.toSet
  private val maxName: Int = DblpRecordTypes.All.map(_.length).max

  private var capture: Capture = null

  /** The bytes of the current token (for unclosed-markup excerpts), at most 16. */
  private val tokBytes = new Array[Byte](OpenerSlack)
  private var tokLen = 0

  private val reports = mutable.Queue.empty[DblpRawRecord]
  private var pendingPos: Long = -1L
  private var pendingName: String = null
  private var pendingTerm: Int = 0
  private var finished = false

  // Results of token().
  private var tokPos: Long = 0L
  private var tokName: String = null
  private var tokTerm: Int = 0

  def position: Long = pos

  private def read(): Int = {
    val b =
      if (pushed != -2) { val p = pushed; pushed = -2; p }
      else buf.read()
    if (b >= 0) {
      pos += 1
      if (capture != null) capture.write(b)
      if (tokLen >= 0 && tokLen < tokBytes.length) tokBytes(tokLen) = b.toByte
      tokLen += 1
    }
    b
  }

  private def unread(b: Int): Unit =
    if (b >= 0) {
      pushed = b
      pos -= 1
      tokLen = math.max(0, tokLen - 1)
      if (capture != null) capture.truncateTo(capture.size - 1)
    }

  /** The next record or report owned by this scanner, or None. */
  def next(): Option[DblpRawRecord] = {
    var result: Option[DblpRawRecord] = None
    while (result.isEmpty && !(finished && reports.isEmpty)) {
      if (reports.nonEmpty) result = Some(reports.dequeue())
      else if (finished) ()
      else if (pendingName != null) {
        val (p, n, t) = (pendingPos, pendingName, pendingTerm)
        pendingName = null
        if (p >= end) finished = true
        else result = Some(readRecord(n, p, t))
      } else if (pos >= end) finished = true
      else
        token() match {
          case TokEof => finished = true
          case TokStart if tokPos >= start => result = Some(readRecord(tokName, tokPos, tokTerm))
          case _ => ()
        }
    }
    result
  }

  /** Lexes one token from the outside-markup state. */
  private def token(): Int = {
    tokPos = pos
    tokLen = 0
    val b = read()
    if (b < 0) TokEof
    else if (b != '<') TokText
    else {
      val c = read()
      if (c == '!') bang()
      else if (c == '?') markup(KPi, 0)
      else if (c == '/') endTag()
      else if (isNameStart(c)) startTag(c)
      else { unread(c); TokText }
    }
  }

  private def readName(first: Int): (String, Int) = {
    val sb = new StringBuilder
    if (first >= 0) sb.append(first.toChar)
    var d = read()
    while (isNameChar(d) && sb.length <= maxName) {
      sb.append(d.toChar)
      d = read()
    }
    (sb.toString, d)
  }

  private def startTag(first: Int): Int = {
    val (name, d) = readName(first)
    if (names.contains(name) && (isSpace(d) || d == '>' || d == '/')) {
      tokName = name
      tokTerm = d
      TokStart
    } else { unread(d); TokTag }
  }

  private def endTag(): Int = {
    val (name, d0) = readName(-1)
    var d = d0
    while (isSpace(d)) d = read()
    if (d == '>' && name.nonEmpty) { tokName = name; TokEnd }
    else { unread(d); TokTag }
  }

  /** After `<!`. */
  private def bang(): Int = {
    val d = read()
    if (d == '-') {
      val e = read()
      if (e == '-') markup(KComment, 0)
      else { unread(e); markup(KDecl, 0) }
    } else if (d == '[') {
      val want = "CDATA["
      var i = 0
      var ok = true
      while (ok && i < want.length) {
        val x = read()
        if (x == want.charAt(i)) i += 1
        else { unread(x); ok = false }
      }
      if (ok) markup(KCdata, 0) else markup(KDecl, 1)
    } else { unread(d); markup(KDecl, 0) }
  }

  /** The opener prefix has been consumed; consumes the construct or reports it unclosed. */
  private def markup(kind: Int, depth: Int): Int = {
    val prefix = java.util.Arrays.copyOf(tokBytes, math.min(tokLen, tokBytes.length))
    val o = tokPos
    val (n, head) = measure(kind, depth)
    if (n >= 0) {
      var i = 0
      while (i < n) { read(); i += 1 }
      TokMarkup
    } else {
      if (o >= start && o < end) {
        val label = kind match {
          case KComment => DblpRawRecord.MarkupComment
          case KCdata => DblpRawRecord.MarkupCdata
          case KPi => DblpRawRecord.MarkupPi
          case _ => DblpRawRecord.MarkupDeclaration
        }
        reports.enqueue(
          new DblpRawRecord(o, label, DblpRawRecord.UnclosedMarkup, prefix ++ head, -1L)
        )
      }
      TokMarkupUnclosed
    }
  }

  /**
   * Looks ahead (without consuming) for the construct's closer. Returns the number of bytes after
   * the opener prefix up to and including the closer, or -1 when it is not within `markupBound`
   * bytes (or input ends), plus the first bytes seen (excerpt of a report).
   */
  private def measure(kind: Int, initDepth: Int): (Int, Array[Byte]) = {
    val limit = markupBound
    buf.mark(limit + OpenerSlack)
    var pushedLeft = pushed != -2
    var n = 0
    var back = -2
    val head = new java.io.ByteArrayOutputStream(ExcerptBytes)
    def nx(): Int =
      if (back != -2) { val b = back; back = -2; b }
      else if (n >= limit) -1
      else {
        val b =
          if (pushedLeft) { pushedLeft = false; pushed }
          else buf.read()
        if (b >= 0) {
          n += 1
          if (head.size < ExcerptBytes) head.write(b)
        }
        b
      }
    def skipComment(): Boolean = {
      var dashes = 0
      var r = 0
      while (r == 0) {
        val c = nx()
        if (c < 0) r = -1
        else if (c == '-') dashes += 1
        else if (c == '>' && dashes >= 2) r = 1
        else dashes = 0
      }
      r > 0
    }
    def skipPi(): Boolean = {
      var q = false
      var r = 0
      while (r == 0) {
        val c = nx()
        if (c < 0) r = -1
        else if (c == '>' && q) r = 1
        else q = c == '?'
      }
      r > 0
    }
    val result: Int = kind match {
      case KComment => if (skipComment()) n else -1
      case KPi => if (skipPi()) n else -1
      case KCdata =>
        var br = 0
        var r = 0
        while (r == 0) {
          val c = nx()
          if (c < 0) r = -1
          else if (c == '>' && br >= 2) r = 1
          else if (c == ']') br += 1
          else br = 0
        }
        if (r > 0) n else -1
      case _ =>
        var depth = initDepth
        var quote = 0
        var r = 0
        var res = -1
        while (r == 0) {
          val c = nx()
          if (c < 0) r = -1
          else if (quote != 0) { if (c == quote) quote = 0 }
          else if (c == '"' || c == '\'') quote = c
          else if (c == '[') depth += 1
          else if (c == ']') depth = math.max(0, depth - 1)
          else if (c == '>' && depth == 0) { res = n; r = 1 }
          else if (c == '<') {
            if (depth == 0) { res = n - 1; r = 1 } // malformed: the '<' starts the next token
            else {
              val d = nx()
              if (d == '?') { if (!skipPi()) r = -1 }
              else if (d == '!') {
                val e = nx()
                if (e == '-') {
                  val f = nx()
                  if (f == '-') { if (!skipComment()) r = -1 }
                  else back = f
                } else back = e
              } else back = d
            }
          }
        }
        if (r > 0) res else -1
    }
    buf.reset()
    (result, head.toByteArray)
  }

  private def readRecord(name: String, tagPos: Long, afterName: Int): DblpRawRecord = {
    val cap = new Capture(maxRecordBytes)
    capture = cap
    cap.write('<')
    name.getBytes("US-ASCII").foreach(b => cap.write(b.toInt))
    cap.write(afterName)
    var status = -1
    var next = -1L

    // 1. Finish the start tag (quote-aware); a '<' always ends it (attribute values cannot hold one).
    var inStartTag = afterName != '>'
    var prev = afterName
    var quote = 0
    var selfClosed = false
    while (inStartTag && status < 0) {
      val b = read()
      if (b < 0) status = DblpRawRecord.Unterminated
      else if (b == '<') { unread(b); inStartTag = false }
      else if (cap.overflow) status = DblpRawRecord.Unterminated
      else {
        if (quote != 0) { if (b == quote) quote = 0 }
        else if (b == '"' || b == '\'') quote = b
        else if (b == '>') {
          inStartTag = false
          selfClosed = prev == '/'
        }
        prev = b
      }
    }
    if (status < 0 && selfClosed) status = DblpRawRecord.Complete

    // 2. Content up to the matching end tag outside markup, or the next record start.
    while (status < 0) {
      token() match {
        case TokEof => status = DblpRawRecord.Unterminated
        case TokEnd if tokName == name => status = DblpRawRecord.Complete
        case TokStart =>
          cap.truncateTo(tokPos - tagPos)
          pendingPos = tokPos
          pendingName = tokName
          pendingTerm = tokTerm
          next = tokPos
          status = DblpRawRecord.Interrupted
        case _ => ()
      }
      if (cap.overflow) status = DblpRawRecord.Unterminated
    }
    capture = null
    new DblpRawRecord(tagPos, name, status, cap.bytes, next)
  }
}

/**
 * Input format for the DBLP XML dump. Uncompressed files are split by byte range and every split is
 * read with the ownership and synchronization rules of [[DblpScanner]] (the reader seeks to
 * [[DblpScanner.streamStart]]), so a record is never split across mappers and the records and
 * rejects do not depend on the split size. Compressed files (the gzip dump) are not splittable and
 * are read whole. Key: locator `<path>@<record offset>`; value: [[DblpRawRecord]].
 */
final class DblpInputFormat extends FileInputFormat[Text, DblpRawRecord] {
  override def createRecordReader(
      split: InputSplit,
      ctx: TaskAttemptContext
  ): RecordReader[Text, DblpRawRecord] = new DblpRecordReader

  override protected def isSplitable(context: JobContext, file: Path): Boolean =
    new CompressionCodecFactory(context.getConfiguration).getCodec(file) == null
}

final class DblpRecordReader extends RecordReader[Text, DblpRawRecord] {
  private var scanner: DblpScanner = null
  private var stream: InputStream = null
  private var path: Path = null
  private var start = 0L
  private var end = 0L
  private var compressed = false
  private var key: Text = null
  private var value: DblpRawRecord = null

  override def initialize(s: InputSplit, ctx: TaskAttemptContext): Unit = {
    val split = s.asInstanceOf[FileSplit]
    val conf = ctx.getConfiguration
    val max = JobParams.orThrow(JobParams.int(conf, JobParams.DblpMaxRecordBytes, 1024))
    path = split.getPath
    val fs = path.getFileSystem(conf)
    val codec = new CompressionCodecFactory(conf).getCodec(path)
    val raw: FSDataInputStream = fs.open(path)
    if (codec != null) {
      compressed = true
      start = 0L
      end = Long.MaxValue
      stream = codec.createInputStream(raw)
      scanner = new DblpScanner(stream, 0L, start, end, max)
    } else {
      start = split.getStart
      end = split.getStart + split.getLength
      val from = DblpScanner.streamStart(start, max)
      raw.seek(from)
      stream = raw
      scanner = new DblpScanner(stream, from, start, end, max)
    }
  }

  override def nextKeyValue(): Boolean =
    scanner.next() match {
      case Some(r) =>
        key = new Text(Locator.of(path, r.offset))
        value = r
        true
      case None => false
    }

  override def getCurrentKey: Text = key
  override def getCurrentValue: DblpRawRecord = value

  override def getProgress: Float =
    if (compressed || end <= start) 0.0f
    else
      math.max(0.0f, math.min(1.0f, (scanner.position - start).toFloat / (end - start).toFloat))

  override def close(): Unit = if (stream != null) stream.close()
}
