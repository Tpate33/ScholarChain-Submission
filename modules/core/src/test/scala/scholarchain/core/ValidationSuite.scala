package scholarchain.core

import scala.collection.immutable.SortedMap

import Fixtures.*

class ValidationSuite extends munit.FunSuite {

  private def has[E <: ValidationError](errs: Vector[ValidationError])(using
      ct: scala.reflect.ClassTag[E]
  ): Boolean = errs.exists(ct.runtimeClass.isInstance)

  // ------------------------------------------------------------------ provenance

  test("missing provenance in JSON is rejected") {
    val json = Json.write(topic)
    val i = json.indexOf(",\"provenance\":")
    val without = json.substring(0, i) + "}"
    assertEquals(
      Json.decode[Topic](without),
      Left(Vector(ValidationError.MissingProvenance("Topic", "provenance")))
    )
    val nulled = json.substring(0, i) + ",\"provenance\":null}"
    assertEquals(
      Json.decode[Topic](nulled),
      Left(Vector(ValidationError.MissingProvenance("Topic", "provenance")))
    )
  }

  test("null provenance in memory is rejected, including on nested records") {
    assert(
      has[ValidationError.MissingProvenance](Topic.contract.validate(topic.copy(provenance = null)))
    )
    val nested = work.copy(authors = Vector(mention0.copy(provenance = null), mention1))
    assert(
      WorkRecord.contract
        .validate(nested)
        .contains(ValidationError.MissingProvenance("AuthorMention", "authors[0].provenance"))
    )
  }

  test("every data record type rejects null provenance") {
    def rejects[T](t: T)(using c: RecordContract[T]): Unit =
      assert(has[ValidationError.MissingProvenance](c.validate(t)), c.recordType)
    rejects(work.copy(provenance = null))
    rejects(mention0.copy(provenance = null))
    rejects(researcher.copy(provenance = null))
    rejects(authorship.copy(provenance = null))
    rejects(team.copy(provenance = null))
    rejects(pairCandidate.copy(provenance = null))
    rejects(coauthorEdge.copy(provenance = null))
    rejects(metrics.copy(provenance = null))
    rejects(venue.copy(provenance = null))
    rejects(institution.copy(provenance = null))
    rejects(topic.copy(provenance = null))
    rejects(matchDecision.copy(provenance = null))
    rejects(graphNode.copy(provenance = null))
    rejects(graphEdge.copy(provenance = null))
    rejects(evidenceEdge.copy(provenance = null))
    rejects(bundle.copy(provenance = null))
    rejects(queryPlan.copy(provenance = null))
    rejects(claim.copy(provenance = null))
    rejects(answer.copy(provenance = null))
  }

  test("incomplete provenance fields are rejected") {
    val p = dblpProv
    assert(
      has[ValidationError.MissingField](
        Topic.contract.validate(topic.copy(provenance = p.copy(sourceRecordId = "")))
      )
    )
    assert(
      has[ValidationError.InvalidValue](
        Topic.contract.validate(topic.copy(provenance = p.copy(snapshot = "")))
      )
    )
    assert(
      has[ValidationError.MalformedId](
        Topic.contract.validate(topic.copy(provenance = p.copy(runId = "run-1")))
      )
    )
    assert(
      has[ValidationError.MissingField](
        Topic.contract.validate(topic.copy(provenance = p.copy(sourceSchemaVersion = " ")))
      )
    )
    assert(
      has[ValidationError.MissingField](
        Topic.contract.validate(topic.copy(provenance = p.copy(modelVersion = Some(""))))
      )
    )
  }

  test("sourceSchemaVersion: dataset sources need <tag>-raw/<semver> with their own tag") {
    def errs(p: Provenance) = Provenance.check("Topic", "provenance", p)
    val dblp = dblpProv
    assertEquals(errs(dblp), Vector.empty)
    assertEquals(errs(openAlexProv("T1")), Vector.empty)
    assertEquals(
      errs(
        dblp.copy(sourceDataset = SourceType.Crossref, sourceSchemaVersion = "crossref-raw/1.0.0")
      ),
      Vector.empty
    )
    assertEquals(
      errs(dblp.copy(sourceDataset = SourceType.Orcid, sourceSchemaVersion = "orcid-raw/10.0.2")),
      Vector.empty
    )
    val rejected = Vector(
      "dblp.schema.v1", // the pre-repair fixture value
      "openalex-raw/1.0.0", // another dataset's schema
      "dblp-raw/1.0", // not three components
      "dblp-raw/01.0.0", // leading zero
      "dblp-raw/v1.0.0",
      "DBLP-raw/1.0.0",
      "scholarchain.work-record.v1" // a record schema is not a raw schema
    )
    rejected.foreach { v =>
      val e = errs(dblp.copy(sourceSchemaVersion = v))
      assert(
        e.exists {
          case ValidationError.InvalidValue("provenance.sourceSchemaVersion", _) => true
          case _ => false
        },
        s"'$v' should be rejected for dblp, got $e"
      )
    }
  }

  test("sourceSchemaVersion: derived and runtime records need a scholarchain record schema") {
    def errs(p: Provenance) = Provenance.check("CoauthorEdge", "provenance", p)
    assertEquals(errs(derivedProv(graphPolicy)), Vector.empty)
    assertEquals(errs(runtimeProv), Vector.empty)
    Vector("dblp-raw/1.0.0", "scholarchain.WorkRecord.v1", "scholarchain.work-record.v0", "v1")
      .foreach { v =>
        assertEquals(
          errs(derivedProv(graphPolicy).copy(sourceSchemaVersion = v)).size,
          1,
          s"'$v' should be rejected for scholarchain-derived"
        )
      }
  }

  test("sourceSchemaVersion format is enforced on decode, and blank stays a MissingField") {
    val json = Json.write(topic).replace("\"openalex-raw/1.0.0\"", "\"openalex.schema.v1\"")
    assert(Json.decode[Topic](json).left.exists(has[ValidationError.InvalidValue]))
    assertEquals(
      Provenance.check("Topic", "provenance", dblpProv.copy(sourceSchemaVersion = "")),
      Vector(ValidationError.MissingField("provenance.sourceSchemaVersion"))
    )
  }

  test("a work ID namespace must match the work's source") {
    def namespaceErr(errs: Vector[ValidationError]) = errs.exists {
      case ValidationError.InvalidValue("workId", why) => why.contains("namespace")
      case _ => false
    }
    assert(!namespaceErr(WorkRecord.contract.validate(work)))
    val doiWork = ok(WorkId.doi("10.1234/example.2021.001"))
    val asDoi = work.copy(
      workId = doiWork,
      authors = Vector(
        mention0.copy(workId = doiWork, mentionId = ok(MentionId.of(doiWork, 0))),
        mention1.copy(workId = doiWork, mentionId = ok(MentionId.of(doiWork, 1)))
      )
    )
    assert(namespaceErr(WorkRecord.contract.validate(asDoi)), "dblp source with a doi work ID")
    val crossrefProv =
      dblpProv.copy(sourceDataset = SourceType.Crossref, sourceSchemaVersion = "crossref-raw/1.0.0")
    val crossrefWork = asDoi.copy(
      source = SourceType.Crossref,
      sourceRecordId = "10.1234/example.2021.001",
      provenance = crossrefProv,
      authors = asDoi.authors.map(_.copy(provenance = crossrefProv))
    )
    assertEquals(WorkRecord.contract.validate(crossrefWork), Vector.empty)
    assertEquals(WorkRecord.NamespaceOf.keySet, SourceType.values.filter(_.isDataset).toSet)
  }

  test("records whose IDs depend on a policy version require provenance.policyVersion") {
    val noPolicy = derivedProv(graphPolicy).copy(policyVersion = None)
    assert(
      CoauthorEdge.contract
        .validate(coauthorEdge.copy(provenance = noPolicy))
        .contains(
          ValidationError.MissingField("provenance.policyVersion")
        )
    )
    assert(
      MatchDecision.contract
        .validate(matchDecision.copy(provenance = noPolicy))
        .contains(
          ValidationError.MissingField("provenance.policyVersion")
        )
    )
    assert(
      GraphEdge.contract
        .validate(graphEdge.copy(provenance = noPolicy))
        .contains(
          ValidationError.MissingField("provenance.policyVersion")
        )
    )
  }

  // ------------------------------------------------------------------ confidence

  test("confidence outside [0,1] is rejected; the bounds are accepted") {
    def errs(c: Double) = MatchDecision.contract.validate(matchDecision.copy(confidence = c))
    assert(errs(0.0).isEmpty)
    assert(errs(1.0).isEmpty)
    assertEquals(errs(1.01), Vector(ValidationError.ConfidenceOutOfRange("confidence", 1.01)))
    assertEquals(errs(-0.01), Vector(ValidationError.ConfidenceOutOfRange("confidence", -0.01)))
    assert(has[ValidationError.ConfidenceOutOfRange](errs(Double.NaN)))
  }

  test("every confidence field is range-checked") {
    assert(
      has[ValidationError.ConfidenceOutOfRange](
        CanonicalResearcher.contract.validate(researcher.copy(identityConfidence = 1.5))
      )
    )
    assert(
      has[ValidationError.ConfidenceOutOfRange](
        AuthorshipRecord.contract.validate(authorship.copy(matchConfidence = -1.0))
      )
    )
    assert(
      has[ValidationError.ConfidenceOutOfRange](
        AnswerClaim.contract.validate(claim.copy(confidence = Some(2.0)))
      )
    )
  }

  test("confidence outside [0,1] in JSON is rejected on decode") {
    val json = Json.write(matchDecision).replace("\"confidence\":0.97", "\"confidence\":1.5")
    assert(Json.decode[MatchDecision](json).left.exists(has[ValidationError.ConfidenceOutOfRange]))
  }

  // ------------------------------------------------------------------ malformed IDs

  test("malformed stable IDs inside records are rejected") {
    assert(
      has[ValidationError.MalformedId](
        WorkRecord.contract.validate(work.copy(workId = "work:dblp:bad key"))
      )
    )
    assert(
      has[ValidationError.MalformedId](
        CoauthorEdge.contract.validate(
          coauthorEdge.copy(researcherA = "researcher:orcid:0000-0000-0000-0002")
        )
      )
    )
    assert(
      has[ValidationError.MalformedId](
        ResearcherMetrics.contract.validate(metrics.copy(maximumTeamWorkIds = Vector("W123")))
      )
    )
    assert(
      has[ValidationError.MalformedId](
        AnswerClaim.contract.validate(claim.copy(evidenceIds = Vector("ev:xyz")))
      )
    )
    assert(
      has[ValidationError.MalformedId](
        GraphNode.contract.validate(graphNode.copy(nodeId = researcherA))
      ),
      "ID kind must match label"
    )
    assert(
      has[ValidationError.MalformedId](
        GraphEdge.contract.validate(graphEdge.copy(edgeType = EdgeLabel.PublishedIn))
      ),
      "endpoint kinds must match edge class"
    )
  }

  test("well-formed IDs that do not match their recomputed value are rejected") {
    val otherEdgeId = "coauthor:" + ("0" * 64)
    assertEquals(
      CoauthorEdge.contract.validate(coauthorEdge.copy(edgeId = otherEdgeId)),
      Vector(ValidationError.InvalidValue("edgeId", s"expected ${coauthorEdge.edgeId}"))
    )
    assert(MatchDecision.contract.validate(matchDecision.copy(targetId = researcherB)).nonEmpty)
    assert(
      EvidenceItem.contract
        .validate(evidenceWork.copy(sourceUri = "https://example.org/other"))
        .nonEmpty
    )
    assert(EvidenceBundle.contract.validate(bundle.copy(items = bundle.items.take(1))).nonEmpty)
    assert(AuthorMention.contract.validate(mention0.copy(position = 5)).nonEmpty)
    assert(AnswerResponse.contract.validate(answer.copy(answerText = "changed")).nonEmpty)
  }

  test("an ORCID-based researcher ID must be backed by the same ORCID external ID") {
    val r = researcher.copy(externalIds = Vector(ExternalId(ExternalIdScheme.DblpPid, "00/0001")))
    assert(has[ValidationError.InvalidValue](CanonicalResearcher.contract.validate(r)))
  }

  // ------------------------------------------------------------------ record rules

  test("work authors must belong to the work and be in position order") {
    val swapped = work.copy(authors = Vector(mention1, mention0))
    assert(has[ValidationError.InvalidValue](WorkRecord.contract.validate(swapped)))
    assert(
      has[ValidationError.InvalidValue](
        WorkRecord.contract.validate(work.copy(source = SourceType.Crossref))
      )
    )
    assert(
      has[ValidationError.MalformedId](
        WorkRecord.contract.validate(work.copy(doi = Some("10.1234/UPPER")))
      )
    )
  }

  test("CONFLICT decisions need conflicting targets; others must not have them") {
    val conflictNoTargets = matchDecision.copy(decision = MatchOutcome.Conflict)
    assert(has[ValidationError.InvalidValue](MatchDecision.contract.validate(conflictNoTargets)))
    val acceptedWithTargets = matchDecision.copy(conflictingTargetIds = Vector(researcherB))
    assert(has[ValidationError.InvalidValue](MatchDecision.contract.validate(acceptedWithTargets)))
    val conflict = matchDecision.copy(
      decision = MatchOutcome.Conflict,
      conflictingTargetIds = Vector(researcherB)
    )
    assertEquals(MatchDecision.contract.validate(conflict), Vector.empty)
  }

  test("match decisions reject non-finite features, missing reason codes, and non-UTC times") {
    assert(
      MatchDecision.contract
        .validate(matchDecision.copy(features = SortedMap("x" -> Double.NaN)))
        .nonEmpty
    )
    assert(MatchDecision.contract.validate(matchDecision.copy(reasonCodes = Vector.empty)).nonEmpty)
    assert(
      MatchDecision.contract.validate(matchDecision.copy(decidedAt = "2026-10-29 12:00")).nonEmpty
    )
  }

  test("metrics keep P, C, T, A, M, W consistent") {
    assert(
      ResearcherMetrics.contract.validate(metrics.copy(distinctCoauthorCount = 30)).nonEmpty,
      "C > T"
    )
    assert(
      ResearcherMetrics.contract.validate(metrics.copy(maximumCoauthorsOnOnePaper = 26)).nonEmpty,
      "M > T"
    )
    assert(
      ResearcherMetrics.contract.validate(metrics.copy(maximumTeamWorkIds = Vector.empty)).nonEmpty
    )
    assert(ResearcherMetrics.contract.validate(metrics.copy(publicationCount = -1)).nonEmpty)
    assert(
      ResearcherMetrics.contract
        .validate(metrics.copy(weightedDegree = Double.PositiveInfinity))
        .nonEmpty
    )
    assert(
      ResearcherMetrics.contract.validate(metrics.copy(bridgeMeasure = None)).nonEmpty,
      "score needs a measure"
    )
  }

  test("publication teams reject duplicate researchers and undersized teams") {
    assert(
      PublicationTeam.contract
        .validate(team.copy(researcherIds = Vector(researcherA, researcherA)))
        .nonEmpty
    )
    assert(PublicationTeam.contract.validate(team.copy(teamSize = 1)).nonEmpty)
  }

  test("claims must cite evidence that resolves to the answer's bundle") {
    val unsupportedNoEvidence =
      claim.copy(evidenceIds = Vector.empty, verificationStatus = VerificationStatus.Unsupported)
    assertEquals(AnswerClaim.contract.validate(unsupportedNoEvidence), Vector.empty)
    val supportedNoEvidence = claim.copy(evidenceIds = Vector.empty)
    assert(has[ValidationError.InvalidValue](AnswerClaim.contract.validate(supportedNoEvidence)))
    val dangling = "ev:" + ("1" * 64)
    val badAnswer = answer.copy(claims = Vector(claim.copy(evidenceIds = Vector(dangling))))
    assert(AnswerResponse.contract.validate(badAnswer).exists {
      case ValidationError.InvalidValue(f, why) =>
        f == "claims[0].evidenceIds" && why.contains(dangling)
      case _ => false
    })
  }

  test("evidence items enforce type-specific references") {
    assert(EvidenceItem.contract.validate(evidenceWork.copy(workId = None)).nonEmpty)
    assert(EvidenceItem.contract.validate(evidenceWork.copy(sourceUri = "not a uri")).nonEmpty)
    assert(EvidenceItem.contract.validate(evidenceWork.copy(score = Double.NaN)).nonEmpty)
  }

  test("query plans need ordered steps") {
    val reordered = queryPlan.copy(steps = queryPlan.steps.reverse)
    assert(QueryPlan.contract.validate(reordered).nonEmpty)
    assert(QueryPlan.contract.validate(queryPlan.copy(steps = Vector.empty)).nonEmpty)
  }

  // ------------------------------------------------------------------ JSON shape

  test("malformed JSON and non-object JSON are rejected with typed errors") {
    assert(Json.decode[Topic]("{not json").left.exists(has[ValidationError.MalformedJson]))
    assert(Json.decode[Topic]("[]").left.exists(has[ValidationError.MalformedJson]))
  }

  test("encode refuses invalid records") {
    assert(Json.encode(matchDecision.copy(confidence = 3.0)).isLeft)
    assertEquals(Json.encode(topic).map(_.nonEmpty), Right(true))
  }
}
