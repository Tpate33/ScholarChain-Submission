package scholarchain.storage.load

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

import scholarchain.core.{CanonicalGraph, LoadRoles, NeptuneCsv}
import scholarchain.storage.model.Bytes

/**
 * Result of scanning one CSV file.
 *
 * @param records
 *   every record, the header included
 * @param fieldsPerRecord
 *   the field count every record has (0 for an empty file)
 * @param header
 *   the exact bytes of the first record including its terminator (empty for an empty file)
 */
final case class CsvScan(records: Long, fieldsPerRecord: Int, header: Bytes) {

  /** Data records: every record after the header. */
  def dataRows: Long = math.max(0L, records - 1L)
}

/**
 * A strict RFC 4180 record scanner over raw bytes (SE-P7-03), enough to count records without
 * decoding them: fields separated by `,`; records terminated by LF or CRLF (the last one may lack
 * its terminator); a field that starts with `"` is quoted, may contain `,`, CR and LF, and doubles
 * each inner `"`. Refused: a `"` inside an unquoted field, any byte other than `,` or a terminator
 * after a closing quote, an unterminated quoted field, a bare CR outside quotes, an empty record,
 * and a record whose field count differs from the header's. Text encoding is not decoded here (row
 * decoding is increment 3).
 */
object CsvRecords {
  private val Quote: Byte = '"'.toByte
  private val Comma: Byte = ','.toByte
  private val Lf: Byte = '\n'.toByte
  private val Cr: Byte = '\r'.toByte

  def scan(bytes: Bytes): Either[String, CsvScan] = scan(bytes.toArray)

  def scan(bytes: Array[Byte]): Either[String, CsvScan] = {
    val n = bytes.length
    var i = 0
    var records = 0L
    var fields = 0
    var expected = -1
    var headerEnd = 0
    var started = false
    // 0 field start, 1 unquoted, 2 quoted, 3 a quote seen inside a quoted field
    var state = 0
    var error: Option[String] = None

    def endRecord(next: Int): Unit = {
      fields += 1
      records += 1
      if (expected < 0) { expected = fields; headerEnd = next }
      else if (fields != expected)
        error = Some(s"record $records has $fields fields; the header has $expected")
      fields = 0
      started = false
      state = 0
    }

    /** Index after a terminator at `i` (LF, or CR followed by LF), or None for a bare CR. */
    def terminator(at: Int): Option[Int] =
      if (bytes(at) == Lf) Some(at + 1)
      else if (at + 1 < n && bytes(at + 1) == Lf) Some(at + 2)
      else None

    while (i < n && error.isEmpty) {
      val b = bytes(i)
      state match {
        case 0 | 1 if b == Lf || b == Cr =>
          terminator(i) match {
            case None => error = Some(s"bare CR at byte $i")
            case Some(next) =>
              if (state == 0 && !started) error = Some(s"empty record at byte $i")
              else { endRecord(next); i = next - 1 }
          }
        case 0 =>
          started = true
          if (b == Quote) state = 2
          else if (b == Comma) fields += 1
          else state = 1
        case 1 =>
          if (b == Quote) error = Some(s"a quote inside an unquoted field at byte $i")
          else if (b == Comma) { fields += 1; state = 0 }
        case 2 =>
          if (b == Quote) state = 3
        case _ =>
          if (b == Quote) state = 2
          else if (b == Comma) { fields += 1; state = 0 }
          else if (b == Lf || b == Cr)
            terminator(i) match {
              case None => error = Some(s"bare CR at byte $i")
              case Some(next) => endRecord(next); i = next - 1
            }
          else error = Some(s"byte after a closing quote at byte $i")
      }
      i += 1
    }
    if (error.isEmpty && state == 2)
      error = Some("unterminated quoted field at the end of the file")
    if (error.isEmpty && started) endRecord(n)
    error match {
      case Some(e) => Left(e)
      case None =>
        Right(CsvScan(records, math.max(expected, 0), Bytes.of(bytes.slice(0, headerEnd))))
    }
  }

  /**
   * Every record of a strict RFC 4180 file as decoded fields (SE-P7-05, increment 3), the header
   * included: [[scan]] first (so the same rules refuse the same files), then strict UTF-8 decoding
   * (malformed or unmappable input is refused, never replaced), then field splitting with each
   * doubled inner quote undone. The separators `,`, `"`, CR and LF are ASCII and never occur inside
   * a multi-byte UTF-8 sequence, so splitting the decoded text equals splitting the bytes.
   */
  def parse(bytes: Bytes): Either[String, Vector[Vector[String]]] = {
    val raw = bytes.toArray
    scan(raw).flatMap { s =>
      val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      val text =
        try Right(decoder.decode(ByteBuffer.wrap(raw)).toString)
        catch { case _: CharacterCodingException => Left("not valid UTF-8") }
      text.flatMap { t =>
        val records = split(t)
        if (records.size.toLong == s.records) Right(records)
        else Left(s"split ${records.size} records, scanned ${s.records}") // never expected
      }
    }
  }

  /** Splits text that [[scan]] accepted. */
  private def split(s: String): Vector[Vector[String]] = {
    val out = Vector.newBuilder[Vector[String]]
    val fields = Vector.newBuilder[String]
    var any = false
    val n = s.length
    var i = 0
    def endRecord(): Unit = { out += fields.result(); fields.clear(); any = false }
    while (i < n) {
      any = true
      if (s.charAt(i) == '"') {
        val sb = new java.lang.StringBuilder
        i += 1
        var closed = false
        while (!closed) {
          val c = s.charAt(i)
          if (c == '"') {
            if (i + 1 < n && s.charAt(i + 1) == '"') { sb.append('"'); i += 2 }
            else { closed = true; i += 1 }
          } else { sb.append(c); i += 1 }
        }
        fields += sb.toString
      } else {
        var j = i
        while (j < n && s.charAt(j) != ',' && s.charAt(j) != '\n' && s.charAt(j) != '\r') j += 1
        fields += s.substring(i, j)
        i = j
      }
      if (i < n) {
        s.charAt(i) match {
          case ',' =>
            i += 1
            if (i == n) { fields += ""; endRecord() }
          case '\r' => i += 2; endRecord() // scan accepted only CRLF
          case _ => i += 1; endRecord()
        }
      }
    }
    if (any) endRecord()
    out.result()
  }
}

/**
 * The exact header line (with its LF) of every Neptune bulk-load role, from the core layout
 * ([[NeptuneCsv]], [[LoadRoles]]); storage never restates the columns.
 */
object NeptuneHeaders {
  private def line(columns: Vector[String]): Bytes =
    Bytes.of((columns.map(NeptuneCsv.field).mkString(",") + "\n").getBytes(StandardCharsets.UTF_8))

  val byRole: Map[String, Bytes] =
    CanonicalGraph.NodeLabels
      .map(l => LoadRoles.vertexRole(l) -> line(NeptuneCsv.vertexColumns(l)))
      .toMap ++
      CanonicalGraph.EdgeLabels.map(l => LoadRoles.edgeRole(l) -> line(NeptuneCsv.edgeColumns(l)))
}
