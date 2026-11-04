package scholarchain.core

import Fixtures.*

class PairAndWindowSuite extends munit.FunSuite {

  private def isNonCanonical(errs: Vector[ValidationError]): Boolean =
    errs.exists(_.isInstanceOf[ValidationError.NonCanonicalPair])
  private def isSelfPair(errs: Vector[ValidationError]): Boolean =
    errs.exists(_.isInstanceOf[ValidationError.SelfPair])
  private def isInvalidWindow(errs: Vector[ValidationError]): Boolean =
    errs.exists(_.isInstanceOf[ValidationError.InvalidWindow])

  // ------------------------------------------------------------------ pairs

  test("canonical pair orders endpoints first < second whatever the input order") {
    val p1 = ok(CanonicalPair.of(researcherB, researcherA))
    val p2 = ok(CanonicalPair.of(researcherA, researcherB))
    assertEquals(p1, p2)
    assertEquals(p1.first, researcherA)
    assertEquals(p1.second, researcherB)
  }

  test("canonical pair uses UTF-8 byte order") {
    val p = ok(CanonicalPair.of("😀", "￿"))
    assertEquals(p.first, "￿")
  }

  test("equal and empty endpoints are rejected") {
    assert(
      CanonicalPair
        .of(researcherA, researcherA)
        .left
        .exists(_.isInstanceOf[ValidationError.SelfPair])
    )
    assert(
      CanonicalPair.of("", researcherA).left.exists(_.isInstanceOf[ValidationError.MissingField])
    )
    assert(CanonicalPair.researchers(researcherA, workId).isLeft, "endpoints must be researchers")
  }

  test("stored pairs must already be canonical") {
    assert(CanonicalPair.checkStored("p", researcherA, researcherB).isEmpty)
    assert(isNonCanonical(CanonicalPair.checkStored("p", researcherB, researcherA)))
    assert(isSelfPair(CanonicalPair.checkStored("p", researcherA, researcherA)))
  }

  test("coauthor edge builder canonicalizes, so both input orders give the same edge") {
    def build(x: String, y: String) =
      CoauthorEdge.build(x, y, window, 3, 2017, 2021, Vector(workId), derivedProv(graphPolicy))
    assertEquals(build(researcherA, researcherB), build(researcherB, researcherA))
    assertEquals(ok(build(researcherB, researcherA)).researcherA, researcherA)
    assert(build(researcherA, researcherA).left.exists(isSelfPair))
  }

  test("a coauthor edge with reversed endpoints is rejected") {
    val reversed = coauthorEdge.copy(researcherA = researcherB, researcherB = researcherA)
    assert(isNonCanonical(CoauthorEdge.contract.validate(reversed)))
    val json = Json.write(reversed)
    assert(Json.decode[CoauthorEdge](json).left.exists(isNonCanonical))
  }

  test("a coauthor edge with equal endpoints is rejected") {
    val self = coauthorEdge.copy(researcherB = researcherA)
    assert(isSelfPair(CoauthorEdge.contract.validate(self)))
  }

  test("pair candidates are canonicalized by construction and validated when stored") {
    val c = ok(
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
    )
    assertEquals(c, pairCandidate)
    val reversed = c.copy(endpointA = c.endpointB, endpointB = c.endpointA)
    assert(isNonCanonical(CoauthorPairCandidate.contract.validate(reversed)))
    assert(
      CoauthorPairCandidate
        .of(mention0.mentionId, 0, mention0.mentionId, 0, workId, None, 2, dblpProv)
        .isLeft
    )
  }

  test("undirected graph edges must be canonical; directed ones keep their direction") {
    val reversed = graphEdge.copy(fromId = researcherB, toId = researcherA)
    assert(isNonCanonical(GraphEdge.contract.validate(reversed)))
  }

  // ------------------------------------------------------------------ windows

  test("time windows: start <= end is accepted, a single year is a window") {
    assertEquals(TimeWindow.of(2000, 2005), Right(TimeWindow(2000, 2005)))
    assertEquals(TimeWindow.of(2010, 2010), Right(TimeWindow(2010, 2010)))
  }

  test("time windows: start > end is rejected") {
    assert(TimeWindow.of(2006, 2005).left.exists(_.isInstanceOf[ValidationError.InvalidWindow]))
  }

  test("years: [0, 9999] inclusive is accepted, including years below 1000") {
    assertEquals(TimeWindow.of(0, 9999), Right(TimeWindow(0, 9999)))
    assertEquals(TimeWindow.of(999, 999), Right(TimeWindow(999, 999)))
    assertEquals(Validated.year("year", 0), Vector.empty)
    assertEquals(Validated.year("year", 9999), Vector.empty)
  }

  test("years: outside [0, 9999] are rejected with a message that states the rule") {
    assertEquals(
      TimeWindow.of(-1, 2005),
      Left(ValidationError.InvalidValue("window.startYear", "year -1 is outside [0, 9999]"))
    )
    assertEquals(
      TimeWindow.of(2000, 10000),
      Left(ValidationError.InvalidValue("window.endYear", "year 10000 is outside [0, 9999]"))
    )
    assert(
      WorkRecord.contract
        .validate(work.copy(year = Some(10000)))
        .contains(ValidationError.InvalidValue("year", "year 10000 is outside [0, 9999]"))
    )
  }

  test("time windows: contains is inclusive at both ends") {
    val w = TimeWindow(2000, 2005)
    assert(w.contains(2000) && w.contains(2005))
    assert(!w.contains(1999) && !w.contains(2006))
  }

  test("records with an inverted window are rejected") {
    assert(
      isInvalidWindow(
        ResearcherMetrics.contract.validate(metrics.copy(window = TimeWindow(2024, 2015)))
      )
    )
    assert(
      isInvalidWindow(QueryPlan.contract.validate(queryPlan.copy(window = TimeWindow(2024, 2015))))
    )
    assert(
      isInvalidWindow(
        AnswerResponse.contract.validate(answer.copy(window = TimeWindow(2024, 2015)))
      )
    )
    assert(
      isInvalidWindow(
        CoauthorEdge.contract.validate(coauthorEdge.copy(window = TimeWindow(2024, 2015)))
      )
    )
  }

  test("coauthor edge collaboration years must be ordered and inside the window") {
    val inverted = coauthorEdge.copy(firstCollaborationYear = 2022, lastCollaborationYear = 2018)
    assert(isInvalidWindow(CoauthorEdge.contract.validate(inverted)))
    val outside = coauthorEdge.copy(firstCollaborationYear = 2010)
    assert(CoauthorEdge.contract.validate(outside).nonEmpty)
  }

  test("JSON with an inverted window is rejected on decode") {
    val json = Json
      .write(metrics)
      .replace(
        "\"window\":{\"startYear\":2015,\"endYear\":2024}",
        "\"window\":{\"startYear\":2024,\"endYear\":2015}"
      )
    assert(Json.decode[ResearcherMetrics](json).left.exists(isInvalidWindow))
  }
}
