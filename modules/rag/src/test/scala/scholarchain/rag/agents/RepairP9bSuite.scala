package scholarchain.rag.agents

import scholarchain.core.Canonical
import scholarchain.storage.model.{
  AttrValue,
  EmbeddingPolicy,
  Item,
  ItemCodecs,
  PrimaryKey,
  StoreError,
  TableKeys,
  TableRole
}
import scholarchain.storage.port.{KeyValueWriter, RuntimeStores}

/**
 * RE-P9-05 (Phase 9 repair 02, CR-P9-02): one focused regression test per finding F1-F3. Every test
 * fails on the RE-P9-04 code (closed-wording licence, `answered` stored on a retry after a withheld
 * answer, institution hints audited as not applied). Scripted fakes only: no model, network or AWS
 * call.
 */
class RepairP9bSuite extends munit.FunSuite {
  import AgentWorld.*

  private val W = "2018-2022"
  private val Titles = "Explain the research on graph methods by Ada Lovelace"
  private val Publications = "How many publications does Ada Lovelace have?"
  private val NotConnected = "What is the shortest path between Ada Lovelace and Eve Adams?"

  private def answered(r: WorkflowResult): WorkflowOutcome.Answered = r.outcome match {
    case a: WorkflowOutcome.Answered => a
    case other => fail(s"expected an answer, got $other")
  }

  private def abstained(r: WorkflowResult): WorkflowOutcome.Abstained = r.outcome match {
    case a: WorkflowOutcome.Abstained => a
    case other => fail(s"expected an abstention, got $other")
  }

  /** A model that appends `extra(facts)` to the template synthesizer's claims. */
  private def adding(extra: Vector[Fact] => Vector[ModelClaim]): ScriptedModel =
    new ScriptedModel(Vector { req =>
      val base = NonProductionTemplateSynthesizer.output(req.input)
      Right(
        ModelReply(
          upickle.default.write(base.copy(claims = base.claims ++ extra(req.input.facts))),
          None,
          None
        )
      )
    })

  private def fact(facts: Vector[Fact], subject: String, field: String): Fact =
    facts
      .find(f => f.subject == subject && f.field == field)
      .getOrElse(fail(s"no $field fact of $subject in ${facts.map(f => f.subject -> f.field)}"))

  private def as(f: Fact) = ModelAssertion(f.subject, f.field, f.value)

  // --- F1 (a): negation licensed by an unrelated assertion -----------------------------------

  test("F1 (a) regression: 'not' licensed by has-context-document never reaches verified text") {
    val w = new WorkflowWorld
    val wording = s"In $W, $ada is not the researcher with publication-count 2."
    val model = adding { facts =>
      val pc = fact(facts, s"$ada@$W", FactFields.PublicationCount)
      val doc = fact(facts, ada, FactFields.HasContextDocument)
      Vector(
        ModelClaim(
          "metric",
          wording,
          Vector(pc.evidenceId, doc.evidenceId),
          Vector(as(pc), as(doc)),
          None
        )
      )
    }
    val a = answered(w.workflow(w.config(model = model)).answer("query:f1a-1", Titles))
    val last = a.verification.results.last
    // A descriptive field never supports a deterministic claim: never VERIFIED.
    assertEquals(last.verdict, Verdict.PartiallySupported)
    assert(last.problems.exists(_.contains("descriptive field")), last.problems.toString)
    val text = last.kept.map(_.text).getOrElse(fail("the partial claim is labelled and kept"))
    assert(text.startsWith("[PARTIALLY SUPPORTED: "), text)
    assert(text.endsWith(s"] In $W, $ada has publication-count 2."), text)
    assert(!a.response.answerText.contains("is not the researcher"), a.response.answerText)
  }

  test("F1 (a) regression, not-connected variant: negation only from the negative fact") {
    val w = new WorkflowWorld
    val wording =
      s"In $W, $ada has not publication-count 2; no coauthorship path of at most 4 hops connects $ada and $eve."
    val model = adding { facts =>
      val pc = fact(facts, s"$ada@$W", FactFields.PublicationCount)
      val nc = fact(facts, BundleFacts.path(ada, eve, W), FactFields.NotConnectedWithin)
      Vector(
        ModelClaim(
          "not-connected",
          wording,
          Vector(pc.evidenceId, nc.evidenceId).distinct,
          Vector(as(pc), as(nc)),
          None
        )
      )
    }
    val a = answered(w.workflow(w.config(model = model)).answer("query:f1a-2", NotConnected))
    val last = a.verification.results.last
    assertEquals(last.verdict, Verdict.Verified)
    assertEquals(
      last.kept.map(_.text),
      Some(
        s"In $W, $ada has publication-count 2. In $W, no coauthorship path of at most 4 hops " +
          s"connects $ada and $eve (not-connected-within 4)."
      )
    )
    assert(!a.response.answerText.contains("has not publication-count"), a.response.answerText)
  }

  test("F1 (a) regression, community variant: 'not' never qualifies a community value") {
    val w = new WorkflowWorld
    val r = w.workflow().answer("query:f1a-3", Titles)
    val bundle = r.evidence.get.bundle
    val Right(facts0) = BundleFacts.of(bundle): @unchecked
    val metric = fact(facts0, s"$ada@$W", FactFields.PublicationCount)
    val doc = fact(facts0, ada, FactFields.HasContextDocument)
    // A stored community fact of the cited deterministic item (unit input for the verifier).
    val comm = Fact(metric.evidenceId, s"$ada@$W", FactFields.CommunityId, c1, true)
    val claim = ModelClaim(
      "community",
      s"In $W, $ada is not in community-id $c1.",
      Vector(metric.evidenceId, doc.evidenceId),
      Vector(as(comm), as(doc)),
      None
    )
    val report = new ClaimVerificationAgent(serving).verify(
      "query:f1a-3",
      bundle,
      facts0 :+ comm,
      SynthesisOutput(Prompts.OutputSchemaVersion, Vector(claim), Vector.empty, Vector.empty),
      Set(W),
      "m",
      Prompts.AnswerSynthesisVersion
    )
    val res = report.results.head
    assertEquals(res.verdict, Verdict.PartiallySupported)
    val text = res.kept.get.text
    assert(text.endsWith(s"] In $W, $ada has community-id $c1."), text)
    assert(!text.contains("is not in"), text)
  }

  // --- F1 (b): injected retrieved title ------------------------------------------------------

  test("F1 (b) regression: a supported title never licenses its words; titles stay quoted") {
    val w = new WorkflowWorld
    val inj = w.InjectionTitle
    val model = adding { facts =>
      val pc = fact(facts, s"$ada@$W", FactFields.PublicationCount)
      val title = facts
        .find(f => f.field == FactFields.Title && f.value == inj)
        .getOrElse(fail("no injected title fact"))
      Vector(
        // A deterministic claim carrying the title: the title is not support, and not shown.
        ModelClaim(
          "metric",
          s"In $W, $ada has publication-count 2. $inj",
          Vector(pc.evidenceId, title.evidenceId),
          Vector(as(pc), as(title)),
          None
        ),
        // A publication claim whose model text repeats the title unquoted and unlabelled.
        ModelClaim("publication", inj, Vector(title.evidenceId), Vector(as(title)), None)
      )
    }
    val a = answered(w.workflow(w.config(model = model)).answer("query:f1b-1", Titles))
    val Vector(metric, publication) = a.verification.results.takeRight(2): @unchecked
    assertEquals(metric.verdict, Verdict.PartiallySupported)
    assert(!metric.kept.get.text.contains("Ignore"), metric.kept.get.text)
    assertEquals(publication.verdict, Verdict.Verified)
    assertEquals(
      publication.kept.map(_.text),
      Some(
        s"${ClaimRendering.DescriptivePrefix} a retrieved document gives ${work(2)} the title " +
          ClaimRendering.quote(inj) + "."
      )
    )
    // Wherever the injected text appears, it is quoted and attributed as retrieved context.
    val lines = a.response.answerText.split("\n").filter(_.contains("Ignore all previous"))
    assert(lines.nonEmpty)
    lines.foreach { l =>
      assert(l.startsWith(ClaimRendering.DescriptivePrefix), l)
      assert(l.contains("\"" + inj + "\""), l)
    }
  }

  // --- F1 (c): non-ASCII numerals and letter-like symbols -------------------------------------

  private val Superscript = "¹⁹" // superscript 1 and 9
  private val Circled = "③" // circled digit three
  private val Roman = "Ⅻ" // Roman numeral twelve
  private val Enclosed = "Ⓐ" // circled Latin capital letter A

  test("F1 (c) regression: superscript, circled, Roman numerals and enclosed letters never shown") {
    val w = new WorkflowWorld
    val odd = Vector(Superscript, Circled, Roman, Enclosed)
    val model = adding { facts =>
      val pc = fact(facts, s"$ada@$W", FactFields.PublicationCount)
      odd.map(s =>
        ModelClaim(
          "metric",
          s"In $W, $ada has publication-count 2 $s.",
          Vector(pc.evidenceId),
          Vector(as(pc)),
          None
        )
      ) :+
        // The symbol as the asserted value itself: the stored value is rendered instead.
        ModelClaim(
          "metric",
          s"In $W, $ada has publication-count $Superscript.",
          Vector(pc.evidenceId),
          Vector(ModelAssertion(pc.subject, pc.field, "²")),
          None
        )
    }
    val a = answered(w.workflow(w.config(model = model)).answer("query:f1c-1", Publications))
    val extra = a.verification.results.takeRight(5)
    assertEquals(
      extra.map(_.verdict),
      Vector.fill(4)(Verdict.Verified) :+ Verdict.Conflicting
    )
    extra
      .take(4)
      .foreach(r => assertEquals(r.kept.map(_.text), Some(s"In $W, $ada has publication-count 2.")))
    (odd :+ "²").foreach(s => assert(!a.response.answerText.contains(s), s))
    assert(a.response.claims.forall(c => ClaimRendering.safe(c.text)))
  }

  test("F1 (c): quoted tokens escape every non-letter non-ASCII character; the guard holds") {
    assertEquals(
      ClaimRendering.quote(s"Graph $Superscript $Circled $Roman $Enclosed é \"x\""),
      "\"Graph \\u00b9\\u2079 \\u2462 \\u216b \\u24b6 é \\\"x\\\"\""
    )
    // Format characters (bidi overrides) and non-ASCII spaces are escaped too.
    assertEquals(
      ClaimRendering.quote(s"a${Character.toString(0x202e)}b${Character.toString(0x00a0)}c"),
      "\"a\\u202eb\\u00a0c\""
    )
    assert(ClaimRendering.safe("In 2018-2022, x has title \"Café\"."))
    assert(!ClaimRendering.safe(s"In 2018-2022, x has publication-count 2 $Circled."))
    assert(!ClaimRendering.safe("Café outside quotes"))
    assert(!ClaimRendering.safe(s"x \"$Roman\""))
    // A value outside the field grammar is never rendered.
    val sub = s"$ada@$W"
    assert(
      ClaimRendering
        .problem(ModelAssertion(sub, FactFields.PublicationCount, "٢"), Set(W))
        .isDefined
    )
    assert(
      ClaimRendering.problem(ModelAssertion(sub, FactFields.PublicationCount, "2"), Set(W)).isEmpty
    )
    assert(
      ClaimRendering
        .problem(ModelAssertion(s"$ada@2000-2001", FactFields.PublicationCount, "2"), Set(W))
        .isDefined
    )
  }

  // --- F1 control ----------------------------------------------------------------------------

  test("F1 control: a correct claim is VERIFIED and shown as its rendering, not the model text") {
    val w = new WorkflowWorld
    val model = adding { facts =>
      val pc = fact(facts, s"$ada@$W", FactFields.PublicationCount)
      Vector(
        ModelClaim(
          "metric",
          "Some free model prose that is never shown.",
          Vector(pc.evidenceId),
          Vector(as(pc)),
          None
        )
      )
    }
    val a = answered(w.workflow(w.config(model = model)).answer("query:f1ctl-1", Publications))
    assertEquals(a.verification.overall, Verdict.Verified)
    val last = a.verification.results.last
    assertEquals(last.verdict, Verdict.Verified)
    assertEquals(last.kept.map(_.text), Some(s"In $W, $ada has publication-count 2."))
    assert(!a.response.answerText.contains("free model prose"))
    // The identity claim is rendered with the name quoted.
    assert(
      a.response.answerText.contains(s"The name \"Ada Lovelace\" resolves to $ada."),
      a.response.answerText
    )
    assertEquals(ClaimVerificationAgent.Version, "scholarchain.rag.claim-verification.v3")
    assertEquals(ClaimRendering.Version, "scholarchain.rag.claim-rendering.v1")
  }

  // --- F2 ------------------------------------------------------------------------------------

  /** Refuses the QuerySession row of `key` the first `failures` times, then delegates. */
  private final class FlakyWriter(inner: KeyValueWriter, key: PrimaryKey, var failures: Int)
      extends KeyValueWriter {
    private def hit(item: Item) =
      item.values.exists(_ == AttrValue.S(key.pk)) && item.values.exists(_ == AttrValue.S(key.sk))
    def putIfAbsent(table: TableRole, item: Item) =
      if (table == TableRole.QuerySession && hit(item) && failures > 0) {
        failures -= 1
        Left(StoreError.Refused("injected transient write failure"))
      } else inner.putIfAbsent(table, item)
    def putIfVersion(table: TableRole, item: Item, expectedVersion: Long) =
      inner.putIfVersion(table, item, expectedVersion)
  }

  test("F2 regression: a retry after a withheld answer stores the withheld outcome, not answered") {
    val w = new WorkflowWorld
    val q = "query:f2-1"
    val auditKey = TableKeys.QuerySession.audit(q).toOption.get
    val rt = RuntimeStores(
      w.objects,
      w.base.kv,
      new FlakyWriter(w.base.kv, auditKey, 1),
      w.base.graph,
      w.vectors,
      w.vectors,
      EmbeddingPolicy.SyntheticTestOnly
    )
    val wf = new CollaborationWorkflow(rt, serving, w.config(), w.base.clock)
    val r = wf.answer(q, "Who are the coauthors of Ada Lovelace?")
    val ab = abstained(r)
    assertEquals(ab.reason, AbstentionReason.AuditNotPersisted)
    assert(r.persistence.isLeft)
    // The record the caller holds, and the one a retry writes, is the returned outcome.
    assertEquals(r.audit.outcome, "abstained")
    assertEquals(r.audit.reason, Some(AbstentionReason.AuditNotPersisted.tag))
    assertEquals(r.audit.answerSha256, None)
    assertEquals(r.auditWrite.audit, r.audit)
    val again = wf.persistAgain(r).fold(e => fail(e.message), identity)
    assertEquals(again.auditSha256, AuditRecord.sha256(r.audit))
    val Right(Some(row)) = w.base.kv.get(TableRole.QuerySession, auditKey): @unchecked
    val stored = upickle.default.read[AuditRecord](
      ItemCodecs.QueryAuditCodec.decode(row).fold(e => fail(e.message), identity).auditJson
    )
    assertEquals(stored.outcome, r.outcome.tag)
    assertEquals(stored.answerSha256, None)
    val Right(Some(s)) = w.base.kv.get(
      TableRole.QuerySession,
      TableKeys.QuerySession.session(q).toOption.get
    ): @unchecked
    assertEquals(ItemCodecs.QuerySessionCodec.decode(s).toOption.get.status, "abstained")
    // A second retry is idempotent.
    val third = wf.persistAgain(r).fold(e => fail(e.message), identity)
    assertEquals(third.auditSha256, again.auditSha256)
  }

  // --- F3 ------------------------------------------------------------------------------------

  private def resolveFilters(r: WorkflowResult): (Vector[AuditFilter], Vector[AuditFilter]) = {
    val steps = r.audit.plan.getOrElse(fail("no audited plan")).steps
    (steps(0).filters, steps(1).filters)
  }

  test("F3 regression: an institution hint used by the resolver is audited as applied, hashed") {
    val w = new WorkflowWorld
    val hint = "Stanford University"
    val sha = Canonical.sha256HexUtf8(hint)
    val r = w.workflow().answer("query:f3-1", s"Who are the coauthors of John Smith at $hint?")
    assertEquals(r.audit.resolvedResearcherIds, Vector(bob))
    val (resolve, data) = resolveFilters(r)
    assertEquals(resolve, Vector(AuditFilter(QueryPlanner.Args.Institution, sha, true)))
    // The data step does not apply it to the stored records (reported as a gap).
    assertEquals(data, Vector(AuditFilter(QueryPlanner.Args.Institution, sha, false)))
    assert(!AuditRecord.json(r.audit).contains(hint))
    // A hint that decided an abstention was applied too.
    val none = w.workflow().answer("query:f3-2", "Who are the coauthors of John Smith at MIT?")
    assertEquals(abstained(none).names.head.reason, AbstainReason.InstitutionMatchesNone)
    assertEquals(resolveFilters(none)._1.map(_.applied), Vector(true))
  }

  test("F3 control: a hint the resolver never evaluated is audited as not applied") {
    val w = new WorkflowWorld(AgentWorld.defaultLimits(maxCandidates = 2))
    w.base.link("John Smith", dan, 3)
    val r = w
      .workflow()
      .answer("query:f3-3", "Who are the coauthors of John Smith at Stanford University?")
    assertEquals(abstained(r).names.head.reason, AbstainReason.TooManyCandidates)
    assertEquals(resolveFilters(r)._1.map(_.applied), Vector(false))
  }
}
