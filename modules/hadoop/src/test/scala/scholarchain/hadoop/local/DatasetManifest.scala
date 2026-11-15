package scholarchain.hadoop.local

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import scholarchain.core.{SourceType, StableId}

/**
 * Snapshot tokens of the ER candidate samples, as recorded by dataset-engineer in
 * `datasets/dataset-manifest.yaml` (work order HD-P5-04; human decision D13 option D).
 *
 * @param dblp
 *   `datasets.dblp.snapshot.id` (the dblp excerpts are cut from the pinned release)
 * @param api
 *   per API-fetched source, `er_candidates.api_snapshots.<tag>.snapshot_id`
 * @param bulk
 *   per API-fetched source, the bulk snapshot ID `datasets.<tag>.snapshot.id` that must NOT be used
 *   for these records
 */
final case class ErSnapshotTokens(
    dblp: String,
    api: Map[SourceType, String],
    bulk: Map[SourceType, String]
)

/**
 * Read-only lookup of scalar values in `datasets/dataset-manifest.yaml` (owner dataset-engineer).
 * The manifest is written in a strict YAML subset (block mappings and lists, double-quoted strings,
 * bare scalars; see its header), so this reader supports exactly that: `key: value` and `key:`
 * lines at increasing two-space-or-more indentation, `- ...` list items (everything inside a list
 * item is unreachable by a dotted path, so a list entry never shadows a mapping key), and `#`
 * comment lines. Values are returned by dotted path, for example `datasets.dblp.snapshot.id`. Any
 * line it cannot read, a tab in the indentation, or a path that occurs twice is an error: the
 * reader fails closed instead of guessing.
 */
object DatasetManifest {
  val File = "datasets/dataset-manifest.yaml"

  /** The API-fetched sources of the ER candidate samples (decision D13 option D). */
  val ApiSources: Vector[SourceType] = Vector(SourceType.OpenAlex, SourceType.Crossref)

  def apiSnapshotKey(s: SourceType): String = s"er_candidates.api_snapshots.${s.tag}.snapshot_id"
  def apiSourceKey(s: SourceType): String = s"er_candidates.api_snapshots.${s.tag}.source"
  def bulkSnapshotKey(s: SourceType): String = s"datasets.${s.tag}.snapshot.id"
  val DblpSnapshotKey: String = bulkSnapshotKey(SourceType.Dblp)

  private val KeyLine = "^([A-Za-z0-9_][A-Za-z0-9_.-]*):(?:[ ]+(.*))?$".r
  private val ListItem = "^-(?:[ ].*)?$".r

  /** Dotted path -> scalar value (quoted strings unescaped). */
  def parse(text: String): Either[String, Map[String, String]] = {
    val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
    // stack of (indent, key); key "[]" marks a list item, below which no path is recorded
    var stack = List.empty[(Int, String)]
    val lines = text.split("\n", -1).toVector.map(_.stripSuffix("\r"))
    var error = Option.empty[String]
    val it = lines.iterator.zipWithIndex
    while (error.isEmpty && it.hasNext) {
      val (raw, i) = it.next()
      val body = raw.dropWhile(_ == ' ')
      val indent = raw.length - body.length
      if (body.isEmpty || body.startsWith("#")) ()
      else if (body.startsWith("\t")) error = Some(s"$File line ${i + 1}: tab in indentation")
      else {
        stack = stack.dropWhile(_._1 >= indent)
        body match {
          case ListItem() => stack = (indent, "[]") :: stack
          case KeyLine(key, rest) =>
            val value = Option(rest).map(_.trim).filter(v => v.nonEmpty && !v.startsWith("#"))
            if (!stack.exists(_._2 == "[]")) {
              val path = (key :: stack.map(_._2)).reverse.mkString(".")
              value match {
                case None => ()
                case Some(v) =>
                  scalar(v) match {
                    case Left(why) => error = Some(s"$File line ${i + 1}: $path: $why")
                    case Right(_) if out.contains(path) =>
                      error = Some(s"$File line ${i + 1}: $path occurs twice")
                    case Right(s) => out.update(path, s)
                  }
              }
            }
            if (value.isEmpty) stack = (indent, key) :: stack
          case _ =>
            if (!stack.exists(_._2 == "[]"))
              error = Some(s"$File line ${i + 1}: not a 'key: value', 'key:' or list line")
        }
      }
    }
    error.toLeft(out.toMap)
  }

  /**
   * A double-quoted string (escapes \" \\ \/ \n \t) or a bare scalar (trailing ` #...` removed).
   */
  private def scalar(v: String): Either[String, String] =
    if (!v.startsWith("\"")) Right(v.split(" #", 2)(0).trim)
    else {
      val sb = new StringBuilder
      var j = 1
      var closed = -1
      var bad = Option.empty[String]
      while (closed < 0 && bad.isEmpty && j < v.length) {
        v.charAt(j) match {
          case '"' => closed = j
          case '\\' if j + 1 < v.length =>
            v.charAt(j + 1) match {
              case '"' => sb += '"'
              case '\\' => sb += '\\'
              case '/' => sb += '/'
              case 'n' => sb += '\n'
              case 't' => sb += '\t'
              case c => bad = Some(s"unsupported escape \\$c")
            }
            j += 1
          case c => sb += c
        }
        j += 1
      }
      val tail = if (closed < 0) "" else v.substring(closed + 1).trim
      bad
        .orElse(Option.when(closed < 0)("unterminated double-quoted string"))
        .orElse(
          Option.when(tail.nonEmpty && !tail.startsWith("#"))(s"text after the string: $tail")
        )
        .toLeft(sb.result())
    }

  def read(repoRoot: JPath): Either[String, Map[String, String]] = {
    val p = repoRoot.resolve(File)
    if (!Files.isRegularFile(p)) Left(s"$File not found")
    else parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
  }

  /**
   * The ER sample snapshot tokens. Fails when a token is missing or not a stable-ID token, when an
   * API entry names another source, or when an API token equals the source's bulk snapshot ID.
   */
  def erSnapshots(values: Map[String, String]): Either[String, ErSnapshotTokens] = {
    def token(key: String): Either[String, String] =
      values.get(key) match {
        case None => Left(s"$File has no $key")
        case Some(v) if !StableId.isToken(v) => Left(s"$File $key '$v' is not a snapshot token")
        case Some(v) => Right(v)
      }
    val problems = Vector.newBuilder[String]
    val dblp = token(DblpSnapshotKey).fold(e => { problems += e; "" }, identity)
    val api = ApiSources.flatMap { s =>
      token(apiSnapshotKey(s)).fold(e => { problems += e; None }, v => Some(s -> v))
    }.toMap
    val bulk = ApiSources.flatMap(s => values.get(bulkSnapshotKey(s)).map(s -> _)).toMap
    ApiSources.foreach { s =>
      if (api.contains(s) && !values.get(apiSourceKey(s)).contains(s.tag))
        problems += s"$File ${apiSourceKey(s)} is ${values.getOrElse(apiSourceKey(s), "absent")}, expected ${s.tag}"
      if (api.contains(s) && bulk.get(s) == api.get(s))
        problems += s"$File ${apiSnapshotKey(s)} equals the bulk snapshot ID ${bulkSnapshotKey(s)} " +
          s"(${api(s)}); API-fetched records must not carry a bulk snapshot ID (decision D13 option D)"
    }
    val ps = problems.result()
    if (ps.nonEmpty) Left(ps.mkString("; ")) else Right(ErSnapshotTokens(dblp, api, bulk))
  }

  def erSnapshots(repoRoot: JPath): Either[String, ErSnapshotTokens] =
    read(repoRoot).flatMap(erSnapshots)
}
