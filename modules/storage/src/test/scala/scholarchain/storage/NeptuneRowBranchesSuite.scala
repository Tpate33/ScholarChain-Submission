package scholarchain.storage

import scholarchain.storage.load.{NeptuneRows, RowProblem}
import scholarchain.storage.model.Bytes
import Fixtures.*

/**
 * CR-P7-07 m1, n4: every NeptuneRows rule branch the review found untested, each on one real
 * fixture row changed in exactly one field (IDs are never quoted in the fixture CSV, so a row
 * splits on commas). A control parse of the unchanged row passes.
 */
class NeptuneRowBranchesSuite extends munit.FunSuite {

  private final case class Part(
      role: String,
      path: String,
      columns: Vector[String],
      rows: Vector[Vector[String]]
  )

  private def part(path: String): Part = {
    val lines = text(runDir(run1Root).resolve(path)).split("\n").toVector
    Part(
      path.takeWhile(_ != '/'),
      path,
      lines.head.split(",", -1).toVector,
      lines.tail.map(_.split(",", -1).toVector)
    )
  }

  private def col(p: Part, name: String): Int = {
    val i = p.columns.indexWhere(c => c == name || c.startsWith(name + ":"))
    assert(i >= 0, s"$name not in ${p.columns}")
    i
  }

  private def parse(p: Part, row: Vector[String]): Either[Vector[RowProblem], Int] = {
    val bytes = Bytes.utf8((p.columns +: Vector(row)).map(_.mkString(",")).mkString("", "\n", "\n"))
    if (NeptuneRows.vertexRoles.contains(p.role))
      NeptuneRows.vertices(p.role, p.path, bytes, RunId).map(_.size)
    else NeptuneRows.edges(p.role, p.path, bytes, RunId).map(_.size)
  }

  private def refused(p: Part, row: Vector[String], fragment: String): Unit = {
    assertEquals(parse(p, p.rows.head), Right(1), "control: the unchanged row passes")
    parse(p, row) match {
      case Left(ps) => assert(ps.exists(_.reason.contains(fragment)), s"'$fragment' not in $ps")
      case Right(_) => fail(s"accepted a row that should be refused for '$fragment'")
    }
  }

  private lazy val coauthored = part("coauthored-with-edges/part-00000.csv")
  private lazy val authored = part("authored-edges/part-00000.csv")
  private lazy val resolves = part("resolves-to-edges/part-00000.csv")
  private lazy val mentions = part("author-mention-vertices/part-00000.csv")

  test("COAUTHORED_WITH stored in non-canonical order is refused") {
    val r = coauthored.rows.head
    val (f, t) = (col(coauthored, "~from"), col(coauthored, "~to"))
    val swapped = r.updated(f, r(t)).updated(t, r(f))
    assert(parse(coauthored, swapped).isLeft, "a reversed undirected pair is refused")
  }

  test("a directed edge that joins a vertex to itself is refused") {
    val r = authored.rows.head
    refused(
      authored,
      r.updated(col(authored, "~to"), r(col(authored, "~from"))),
      "~from and ~to are the same vertex"
    )
  }

  test("an endpoint ID of a kind the label does not allow is refused") {
    val r = coauthored.rows.head
    val work = authored.rows.head(col(authored, "~to"))
    refused(coauthored, r.updated(col(coauthored, "~to"), work), "~to")
  }

  test("sourceEvidence: missing, or of the wrong kind, is refused") {
    val r = coauthored.rows.head
    val ev = col(coauthored, "sourceEvidence")
    refused(coauthored, r.updated(ev, ""), "sourceEvidence: missing")
    val work = authored.rows.head(col(authored, "~to"))
    refused(coauthored, r.updated(ev, work), "sourceEvidence")
  }

  test("RESOLVES_TO: the evidence must be the mention itself") {
    val r = resolves.rows.head
    val other = resolves.rows.find(_(col(resolves, "~from")) != r(col(resolves, "~from"))).get
    refused(
      resolves,
      r.updated(col(resolves, "sourceEvidence"), other(col(resolves, "~from"))),
      "the evidence is the mention itself"
    )
  }

  test("an AuthorMention ~id that is not MentionId.of(workId, position) is refused") {
    val r = mentions.rows.head
    val pos = col(mentions, "position")
    refused(mentions, r.updated(pos, (r(pos).toInt + 1).toString), "is not the mention at position")
  }

  test("a missing runId is refused") {
    val r = coauthored.rows.head
    refused(coauthored, r.updated(col(coauthored, "runId"), ""), "runId: missing")
  }

  test("n4: a non-canonical Int (leading zero, -0, +) is refused") {
    val r = coauthored.rows.head
    val c = col(coauthored, "jointPublicationCount")
    Vector("0" + r(c), "+" + r(c), "-0").foreach { v =>
      refused(coauthored, r.updated(c, v), "is not a 32-bit decimal Int")
    }
  }
}
