package scholarchain.hadoop

import java.text.Normalizer
import java.util.Locale

import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce.Partitioner

import scholarchain.core.Canonical

/**
 * Pipeline-wide constants. `TransformVersion` is recorded as `provenance.policyVersion` on every
 * record this module produces; change it whenever a mapping rule changes.
 */
object HadoopPipeline {

  /**
   * v2 (Phase 4 work order 3): work-record v3 fields, source author profiles, identity links,
   * candidate blocks and match candidates.
   */
  val TransformVersion = "hadoop-transform.v2"

  /** Source schema versions this code parses (the `schema_version` values of the manifest). */
  val DblpSchema = "dblp-raw/1.0.0"
  val OpenAlexSchema = "openalex-raw/1.0.0"
  val CrossrefSchema = "crossref-raw/1.0.0"
  val OrcidSchema = "orcid-raw/1.0.0"
}

/**
 * Mechanical text normalization (no matching policy): Unicode NFC, whitespace runs collapsed to one
 * space, trimmed, lowercased with the root locale. Titles also lose one trailing `.` (the DBLP
 * title convention). Diacritics, punctuation, and name order are deliberately kept: how names and
 * titles are compared is entity-resolution policy.
 */
object TextNormalization {
  def collapse(s: String): String = s.trim.replaceAll("\\s+", " ")

  def name(raw: String): String =
    collapse(Normalizer.normalize(raw, Normalizer.Form.NFC)).toLowerCase(Locale.ROOT)

  def title(raw: String): String = {
    val c = collapse(Normalizer.normalize(raw, Normalizer.Form.NFC))
    (if (c.endsWith(".")) c.dropRight(1).trim else c).toLowerCase(Locale.ROOT)
  }
}

/** Kind of a normalized record in the normalize shuffle; `output` is its named output. */
enum RecordKind(val tag: String, val output: String) {
  case Work extends RecordKind("work", NamedOutputs.Works)
  case Venue extends RecordKind("venue", NamedOutputs.Venues)
  case Institution extends RecordKind("institution", NamedOutputs.Institutions)
  case Topic extends RecordKind("topic", NamedOutputs.Topics)
  case Profile extends RecordKind("profile", NamedOutputs.Profiles)
}

object RecordKind {
  def key(kind: RecordKind, id: String): Text = new Text(s"${kind.tag}\t$id")

  def parse(key: Text): (RecordKind, String) = {
    val s = key.toString
    val i = s.indexOf('\t')
    val kind = RecordKind.values
      .find(_.tag == s.substring(0, math.max(i, 0)))
      .getOrElse(throw new IllegalStateException(s"unknown record kind in shuffle key '$s'"))
    (kind, s.substring(i + 1))
  }
}

/** Rank of a record in the normalize shuffle: primary records win over embedded references. */
object Rank {

  /**
   * A record read from its own entity file (works and author profiles of every source, OpenAlex
   * institutions).
   */
  val Primary = 0

  /** A reference embedded in another record (venues, topics, institutions inside works). */
  val Reference = 1
}

/**
 * Normalize-shuffle value. The merge is associative and commutative (it is the combiner): the
 * lowest rank wins; at equal rank copies add up, the kept JSON is the smallest `(contentHash,
 * json)` pair, and `conflicting` records that at least two copies had different content.
 *
 * @param contentHash
 *   SHA-256 of the record JSON without its provenance (references embedded in different source
 *   records differ only in provenance and are not conflicts)
 */
final case class Envelope(
    rank: Int,
    copies: Long,
    conflicting: Boolean,
    contentHash: String,
    json: String
) {
  def encode: Text =
    new Text(s"$rank\t$copies\t${if (conflicting) 1 else 0}\t$contentHash\t$json")
}

object Envelope {
  def of(rank: Int, json: String): Envelope =
    Envelope(rank, 1L, conflicting = false, contentHash(json), json)

  def contentHash(json: String): String = {
    val v = ujson.read(json)
    v match {
      case o: ujson.Obj => o.value.remove("provenance")
      case _ => ()
    }
    Canonical.sha256HexUtf8(ujson.write(v))
  }

  def decode(t: Text): Envelope = {
    val parts = t.toString.split("\t", 5)
    if (parts.length != 5) throw new IllegalStateException("malformed normalize envelope")
    Envelope(parts(0).toInt, parts(1).toLong, parts(2) == "1", parts(3), parts(4))
  }

  private def before(a: Envelope, b: Envelope): Boolean = {
    val c = a.contentHash.compareTo(b.contentHash)
    if (c != 0) c < 0 else Canonical.compareUtf8(a.json, b.json) <= 0
  }

  def merge(a: Envelope, b: Envelope): Envelope =
    if (a.rank != b.rank) { if (a.rank < b.rank) a else b }
    else {
      val keep = if (before(a, b)) a else b
      Envelope(
        a.rank,
        a.copies + b.copies,
        a.conflicting || b.conflicting || a.contentHash != b.contentHash,
        keep.contentHash,
        keep.json
      )
    }
}

/** FNV-1a 32-bit over raw bytes: identical on every JVM, platform, and run. */
object StableHash {
  def of(bytes: Array[Byte], length: Int): Int = {
    var h = 0x811c9dc5
    var i = 0
    while (i < length) {
      h ^= (bytes(i) & 0xff)
      h *= 0x01000193
      i += 1
    }
    h
  }

  def partition(key: Text, numPartitions: Int): Int =
    (of(key.getBytes, key.getLength) & Int.MaxValue) % numPartitions
}

/**
 * Explicit partition strategy of every job: FNV-1a of the UTF-8 key bytes modulo the reducer count.
 * Independent of `Object.hashCode`, JVM, and run, so the same key always lands in the same part
 * file for a given reducer count.
 */
final class StableHashPartitioner extends Partitioner[Text, Text] {
  def getPartition(key: Text, value: Text, numPartitions: Int): Int =
    StableHash.partition(key, numPartitions)
}

/** One per-task statistic line of the `stats` named output; the driver keeps the maximum. */
final case class StatLine(metric: String, value: Long, id: String)
    derives upickle.default.ReadWriter

object StatLine {
  def toJson(s: StatLine): String = upickle.default.write(s)
  def fromJson(s: String): StatLine = upickle.default.read[StatLine](s)

  /** Larger value wins; ties go to the smaller id (deterministic). */
  def better(a: StatLine, b: StatLine): StatLine =
    if (a.value != b.value) { if (a.value > b.value) a else b }
    else if (Canonical.compareUtf8(a.id, b.id) <= 0) a
    else b
}
