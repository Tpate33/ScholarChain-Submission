package scholarchain.core

import Fixtures.*

/** C3: author positions on every coauthor pair record (README Phase 4 job 6, line 1154). */
class PairPositionSuite extends munit.FunSuite {

  private def invalidAt(errs: Vector[ValidationError], field: String): Boolean =
    errs.exists {
      case ValidationError.InvalidValue(f, _) => f == field
      case _ => false
    }

  /** A synthetic DBLP work with n authors (mentions only; enough for pair tests). */
  private def teamWork(n: Int): WorkRecord = {
    val key = s"journals/example/Team$n"
    val wid = ok(WorkId.dblp(key))
    val prov = dblpProv.copy(sourceRecordId = key)
    work.copy(
      workId = wid,
      sourceRecordId = key,
      authors = (0 until n).toVector.map { i =>
        mention(i, s"Author A$i", None).copy(
          workId = wid,
          mentionId = ok(MentionId.of(wid, i)),
          provenance = prov
        )
      },
      provenance = prov
    )
  }

  test("positions follow their endpoints under canonical ordering, whatever the input order") {
    val forward =
      CoauthorPairCandidate.of(
        mention0.mentionId,
        0,
        mention1.mentionId,
        1,
        workId,
        Some(2021),
        2,
        dblpProv
      )
    val backward =
      CoauthorPairCandidate.of(
        mention1.mentionId,
        1,
        mention0.mentionId,
        0,
        workId,
        Some(2021),
        2,
        dblpProv
      )
    assertEquals(forward, backward)
    val p = ok(forward)
    assertEquals(p, pairCandidate)
    // mention1's ID sorts first, so endpointA is the author at position 1.
    assertEquals((p.endpointA, p.positionA), (mention1.mentionId, 1))
    assertEquals((p.endpointB, p.positionB), (mention0.mentionId, 0))
  }

  test("every pair of a 26-author team carries the positions of its two mentions") {
    val w = teamWork(26)
    val ms = w.authors
    val pairs = for {
      i <- ms.indices
      j <- i + 1 until ms.size
    } yield ok(
      CoauthorPairCandidate
        .of(ms(i).mentionId, i, ms(j).mentionId, j, w.workId, w.year, 26, w.provenance)
    )
    assertEquals(pairs.size, 325)
    val byId = ms.map(m => m.mentionId -> m.position).toMap
    pairs.foreach { p =>
      assertEquals(byId(p.endpointA), p.positionA)
      assertEquals(byId(p.endpointB), p.positionB)
    }
    assertEquals(
      pairs
        .map(p => (math.min(p.positionA, p.positionB), math.max(p.positionA, p.positionB)))
        .toVector,
      (for { i <- 0 until 26; j <- i + 1 until 26 } yield (i, j)).toVector
    )
  }

  test("positional builder: argument order is irrelevant and positions reproduce MentionId.of") {
    val w = teamWork(5)
    val ms = w.authors
    for {
      i <- ms.indices
      j <- i + 1 until ms.size
    } {
      val forward = CoauthorPairCandidate.of(
        ms(i).mentionId,
        i,
        ms(j).mentionId,
        j,
        w.workId,
        w.year,
        5,
        w.provenance
      )
      val backward = CoauthorPairCandidate.of(
        ms(j).mentionId,
        j,
        ms(i).mentionId,
        i,
        w.workId,
        w.year,
        5,
        w.provenance
      )
      assertEquals(forward, backward)
      val p = ok(forward)
      // Positions recomputed independently of the builder: each must regenerate its endpoint.
      assertEquals(ok(MentionId.of(w.workId, p.positionA)), p.endpointA)
      assertEquals(ok(MentionId.of(w.workId, p.positionB)), p.endpointB)
    }
  }

  test("positional builder refuses mentions of another work and positions at or above teamSize") {
    val otherWork = teamWork(3)
    val foreign = CoauthorPairCandidate.of(
      otherWork.authors(0).mentionId,
      0,
      mention1.mentionId,
      1,
      workId,
      Some(2021),
      2,
      dblpProv
    )
    assert(foreign.left.exists(e => invalidAt(e, "positionA") || invalidAt(e, "positionB")))
    // position 2 exists only if teamSize > 2
    val w = teamWork(3)
    val outOfRange = CoauthorPairCandidate.of(
      w.authors(0).mentionId,
      0,
      w.authors(2).mentionId,
      2,
      w.workId,
      w.year,
      2,
      w.provenance
    )
    assert(outOfRange.left.exists(e => invalidAt(e, "positionA") || invalidAt(e, "positionB")))
  }

  test("positions are validated: non-negative, distinct, below teamSize, matching the mention") {
    def errs(c: CoauthorPairCandidate) = CoauthorPairCandidate.contract.validate(c)
    assert(invalidAt(errs(pairCandidate.copy(positionA = -1)), "positionA"))
    assert(invalidAt(errs(pairCandidate.copy(positionB = 2)), "positionB"), "not below teamSize 2")
    assert(invalidAt(errs(pairCandidate.copy(positionB = 1)), "positionA,positionB"))
    val swapped = pairCandidate.copy(positionA = 0, positionB = 1)
    assert(invalidAt(errs(swapped), "positionA"), "positions must follow their endpoints")
    assert(invalidAt(errs(swapped), "positionB"))
  }

  test("researcher endpoints carry positions too (range-checked; no mention to recompute)") {
    val p = ok(
      CoauthorPairCandidate.of(
        researcherB,
        3,
        researcherA,
        0,
        workId,
        Some(2021),
        4,
        derivedProv(erPolicy)
      )
    )
    assertEquals(
      (p.endpointA, p.positionA, p.endpointB, p.positionB),
      (researcherA, 0, researcherB, 3)
    )
    assert(
      CoauthorPairCandidate
        .of(researcherA, 0, researcherB, 4, workId, Some(2021), 4, derivedProv(erPolicy))
        .isLeft
    )
  }

  test("JSON whose positions do not match the endpoints is rejected on decode") {
    val json = Json
      .write(pairCandidate)
      .replace("\"positionA\":1,\"positionB\":0", "\"positionA\":0,\"positionB\":1")
    assert(Json.decode[CoauthorPairCandidate](json).left.exists(e => invalidAt(e, "positionA")))
  }
}
