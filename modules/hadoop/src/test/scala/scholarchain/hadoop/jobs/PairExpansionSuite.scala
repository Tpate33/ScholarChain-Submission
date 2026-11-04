package scholarchain.hadoop.jobs

import scholarchain.core.{
  AuthorMention,
  Canonical,
  CoauthorPairCandidate,
  Json,
  MentionId,
  PublicationTeam,
  SchemaVersions,
  SourceType,
  WorkId,
  WorkRecord
}
import scholarchain.hadoop.TestSupport
import scholarchain.hadoop.normalize.Build

class PairExpansionSuite extends munit.FunSuite {

  val run = TestSupport.run(SourceType.Dblp)

  /** A synthetic DBLP work with `n` authors named A0..A(n-1). */
  def work(n: Int, year: Option[Int] = Some(2021)): WorkRecord = {
    val key = s"journals/example/Team$n"
    val workId = WorkId.dblp(key).toOption.get
    val prov = Build.provenance(run, key, "dblp-raw/1.0.0")
    val authors = (0 until n).toVector.map { i =>
      Build
        .mention(workId, i, s"Author A$i", None, None, Vector.empty, Vector.empty, prov)
        .toOption
        .get
    }
    WorkRecord(
      SchemaVersions.WorkRecord,
      workId,
      SourceType.Dblp,
      key,
      s"Team $n.",
      s"team $n",
      year,
      None,
      None,
      None,
      authors,
      None,
      prov
    )
  }

  def pairs(w: WorkRecord): Vector[CoauthorPairCandidate] =
    PairExpansion.iterator(w).map(_.fold(e => fail(e.map(_.message).mkString), identity)).toVector

  /** Position pairs (i, j), i < j, as carried on each pair record. */
  def positions(ps: Vector[CoauthorPairCandidate]): Vector[(Int, Int)] =
    ps.map(p => (math.min(p.positionA, p.positionB), math.max(p.positionA, p.positionB)))

  /** Every pair's positions belong to its endpoints (checked against the work's mentions). */
  def positionsFollowEndpoints(w: WorkRecord, ps: Vector[CoauthorPairCandidate]): Boolean = {
    val pos = w.authors.map(m => m.mentionId -> m.position).toMap
    ps.forall(p => pos(p.endpointA) == p.positionA && pos(p.endpointB) == p.positionB)
  }

  test("pair count formula n(n-1)/2") {
    assertEquals((0 to 5).map(PairExpansion.pairCount).toVector, Vector(0L, 0L, 1L, 3L, 6L, 10L))
    assertEquals(PairExpansion.pairCount(26), 325L)
    assertEquals(PairExpansion.pairCount(100000), 4999950000L)
  }

  test("0 and 1 authors: no pairs; 1 author still has a team of size 1") {
    assertEquals(pairs(work(0)), Vector.empty)
    val w1 = work(1)
    assertEquals(pairs(w1), Vector.empty)
    val t = PairExpansion.team(w1)
    assertEquals(t.teamSize, 1)
    assertEquals(t.unresolvedMentionIds, Vector(w1.authors.head.mentionId))
    assertEquals(PublicationTeam.contract.validate(t), Vector.empty)
  }

  test("2 authors: exactly one canonical pair carrying work ID, year, team size, provenance") {
    val w = work(2)
    val ps = pairs(w)
    assertEquals(ps.size, 1)
    val p = ps.head
    assert(Canonical.compareUtf8(p.endpointA, p.endpointB) < 0, "endpoints in canonical order")
    assertEquals(Set(p.endpointA, p.endpointB), w.authors.map(_.mentionId).toSet)
    assertEquals(p.workId, w.workId)
    assertEquals(p.year, Some(2021))
    assertEquals(p.teamSize, 2)
    assertEquals(Set(p.positionA, p.positionB), Set(0, 1))
    assert(positionsFollowEndpoints(w, ps), "each position travels with its endpoint")
    assertEquals(p.provenance, w.provenance)
    assertEquals(p.provenance.sourceDataset, SourceType.Dblp)
    assertEquals(p.provenance.sourceRecordId, "journals/example/Team2")
    assertEquals(CoauthorPairCandidate.contract.validate(p), Vector.empty)
  }

  test("3 authors: pairs (0,1), (0,2), (1,2) in that order") {
    val w = work(3)
    val ps = pairs(w)
    assertEquals(positions(ps), Vector((0, 1), (0, 2), (1, 2)))
    assert(positionsFollowEndpoints(w, ps))
    assert(ps.forall(_.teamSize == 3))
  }

  test("26 authors (the Spanner team size): 325 distinct pairs covering every position pair once") {
    val w = work(26)
    val ps = pairs(w)
    assertEquals(ps.size, 325)
    assertEquals(ps.map(p => (p.endpointA, p.endpointB)).distinct.size, 325)
    val expected = for { i <- 0 until 26; j <- i + 1 until 26 } yield (i, j)
    assertEquals(positions(ps), expected.toVector)
    assert(positionsFollowEndpoints(w, ps))
    assert(ps.forall(p => CoauthorPairCandidate.contract.validate(p).isEmpty))
  }

  test("a repeated author name keeps both mentions and their pair (distinct mention IDs)") {
    val w = work(3)
    val same =
      w.copy(authors = w.authors.map(_.copy(rawName = "Wang Yi", normalizedName = "wang yi")))
    val ps = pairs(same)
    assertEquals(ps.size, 3)
    assertEquals(positions(ps), Vector((0, 1), (0, 2), (1, 2)))
    assert(ps.forall(p => p.endpointA != p.endpointB))
  }

  test("many authors (1000): positional builder, 499500 pairs, all valid") {
    val w = work(1000)
    var count = 0L
    val start = System.nanoTime()
    PairExpansion.iterator(w).foreach { e =>
      assert(e.isRight)
      count += 1
    }
    val seconds = (System.nanoTime() - start) / 1e9
    assertEquals(count, PairExpansion.pairCount(1000))
    assertEquals(count, 499500L)
    // The positional builder costs O(1) hashes per pair. The former position-recovering bridge
    // needed about n^3/3 = 3.3e8 SHA-256 computations here (minutes); this bound catches a relapse.
    assert(seconds < 60.0, s"1000-author expansion took $seconds s")
  }

  test("many authors (200): lazily produced, 19900 pairs, all valid") {
    val w = work(200)
    var count = 0L
    PairExpansion.iterator(w).foreach { e =>
      assert(e.isRight)
      count += 1
    }
    assertEquals(count, PairExpansion.pairCount(200))
  }

  test("year is preserved when absent and pair JSON round-trips through the core contract") {
    val w = work(2, year = None)
    val p = pairs(w).head
    assertEquals(p.year, None)
    assertEquals(Json.decode[CoauthorPairCandidate](Json.write(p)), Right(p))
  }

  test("mention IDs are the core MentionId of (workId, position)") {
    val w = work(4)
    w.authors.foreach(m =>
      assertEquals(m.mentionId, MentionId.of(w.workId, m.position).toOption.get)
    )
    assertEquals(w.authors.map((m: AuthorMention) => m.position), Vector(0, 1, 2, 3))
  }
}
