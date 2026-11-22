package scholarchain.rag.agents

import java.nio.file.{Files, Path, Paths}

import scholarchain.core.Canonical
import scholarchain.rag.benchmark.{
  BenchmarkCase,
  BenchmarkExpectation,
  BenchmarkRunner,
  BenchmarkSet
}
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
 * RE-P9-04 (Phase 9 repair 01, CR-P9-01): one focused regression test per repaired finding (M2, M3,
 * m1-m6 here; M1 in PathFinderSuite, M4 in ResearcherResolverSuite and here at workflow level).
 * Every test fails on the pre-repair code. Scripted fakes only: no model, network or AWS call.
 */
class RepairP9Suite extends munit.FunSuite {
  import AgentWorld.*

  private val W = "2018-2022"
  private val Coauthors = "Who are the coauthors of Ada Lovelace?"
  private val Community = "Which community is Ada Lovelace in?"
  private val Titles = "Explain the research on graph methods by Ada Lovelace"

  private def answered(r: WorkflowResult): WorkflowOutcome.Answered = r.outcome match {
    case a: WorkflowOutcome.Answered => a
    case other => fail(s"expected an answer, got $other")
  }

  private def abstained(r: WorkflowResult): WorkflowOutcome.Abstained = r.outcome match {
    case a: WorkflowOutcome.Abstained => a
    case other => fail(s"expected an abstention, got $other")
  }

  private def root: Path = {
    var dir = Paths.get("").toAbsolutePath
    while (dir != null && !Files.isDirectory(dir.resolve("prompts/schemas"))) dir = dir.getParent
    assert(dir != null, "prompts/schemas not found above the working directory")
    dir
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

  // --- M2 -----------------------------------------------------------------------------------

  test("M2 regression: the reviewer's wording (unasserted names, superlative) is never shown") {
    val w = new WorkflowWorld
    val reviewer =
      "Alice Smith and Bob Jones are in the same community and Alice is the strongest bridge."
    val model = adding { facts =>
      val cid = facts
        .find(f => f.deterministic && f.subject == s"$ada@$W" && f.field == FactFields.CommunityId)
        .getOrElse(fail("no community fact"))
      Vector(
        ModelClaim(
          "community",
          reviewer,
          Vector(cid.evidenceId),
          Vector(ModelAssertion(cid.subject, cid.field, cid.value)),
          None
        ),
        ModelClaim(
          "community",
          s"In $W, $ada is in community-id ${cid.value}, the leading community.",
          Vector(cid.evidenceId),
          Vector(ModelAssertion(cid.subject, cid.field, cid.value)),
          None
        )
      )
    }
    val r = w.workflow(w.config(model = model)).answer("query:m2-1", Community)
    val a = answered(r)
    // RE-P9-05 (F1): the supported assertion is VERIFIED, but the text is rendered from it; the
    // model's names and superlatives never reach the answer.
    assert(a.verification.results.forall(_.verdict == Verdict.Verified))
    Vector("Alice", "Bob Jones", "strongest", "leading").foreach(s =>
      assert(!a.response.answerText.contains(s), s)
    )
    assertEquals(a.verification.overall, Verdict.Verified)
  }

  test("M2/F1: every fixed fact field has a rendering template; the prompt has no WORDING line") {
    assertEquals(ClaimRendering.templatedFields, FactFields.fixed)
    val w = new WorkflowWorld
    val plan = w.workflow().answer("query:m2-2", Coauthors).plan.get
    val prompt = new AnswerSynthesisAgent(w.config().synthesis)
      .render(SynthesisInput(plan, Vector.empty, Vector.empty, Vector.empty))
    assert(!prompt.contains("WORDING"), prompt)
    assert(prompt.contains("never shown"), prompt)
  }

  test("M2/F1: a digit glued to a word in the model text is withheld; the rendering is shown") {
    val w = new WorkflowWorld
    val model = adding { facts =>
      val pc = facts
        .find(f =>
          f.deterministic && f.subject == s"$ada@$W" && f.field == FactFields.PublicationCount
        )
        .get
      Vector(
        ModelClaim(
          "metric",
          s"In $W, $ada has publication-count 2 and 99coauthors.",
          Vector(pc.evidenceId),
          Vector(ModelAssertion(pc.subject, pc.field, pc.value)),
          None
        )
      )
    }
    val a = answered(
      w.workflow(w.config(model = model))
        .answer("query:m2-3", "How many publications does Ada Lovelace have?")
    )
    assertEquals(a.verification.results.last.verdict, Verdict.Verified)
    assertEquals(
      a.verification.results.last.kept.map(_.text),
      Some(s"In $W, $ada has publication-count 2.")
    )
    assert(!a.response.answerText.contains("99coauthors"))
  }

  // --- M3 and m2 ----------------------------------------------------------------------------

  test("M3 regression: model missing-evidence text never reaches the answer; it is counted") {
    val w = new WorkflowWorld
    val evil = "the evidence shows Alice Smith is the leading collaborator in Europe"
    val model =
      new ScriptedModel(Vector(ScriptedModel.template(_.copy(missingEvidence = Vector(evil)))))
    val r = w.workflow(w.config(model = model)).answer("query:m3-1", Coauthors)
    val a = answered(r)
    assert(!a.response.answerText.contains("Alice Smith"), a.response.answerText)
    assertEquals(a.verification.missingEvidence, Vector.empty)
    assertEquals(a.verification.droppedMissingEvidence, 1)
    assertEquals(r.audit.droppedMissingEvidence, 1)
    assert(a.response.answerText.contains(CollaborationWorkflow.droppedNotice(1)))
  }

  test("m2 regression: workflow gaps with version-like tokens are kept verbatim, never dropped") {
    val gap = "index researcher-profile-v1 is not configured"
    val bundle =
      new WorkflowWorld().workflow().answer("query:m2gap-1", Coauthors).evidence.get.bundle
    val report = new ClaimVerificationAgent(serving).verify(
      "query:m2gap-1",
      bundle,
      Vector.empty,
      SynthesisOutput(Prompts.OutputSchemaVersion, Vector.empty, Vector(gap), Vector.empty),
      Set(W),
      "m",
      "p",
      Vector(gap)
    )
    assertEquals(report.missingEvidence, Vector(gap))
    assertEquals(report.droppedMissingEvidence, 0)
    // End to end: every gap the workflow detected is in the answer, even when the model omits it.
    val ww = new WorkflowWorld
    val c = ww.config(
      model =
        new ScriptedModel(Vector(ScriptedModel.template(_.copy(missingEvidence = Vector.empty)))),
      semantic =
        ww.semanticConfig(Vector(scholarchain.storage.model.VectorIndexes.ResearcherProfile))
    )
    val r = ww.workflow(c).answer("query:m2gap-2", Titles)
    val a = answered(r)
    val gaps = r.semantic.flatMap(_.gaps)
    assert(gaps.nonEmpty)
    gaps.foreach { g =>
      assert(a.verification.missingEvidence.contains(g), g)
      assert(a.response.answerText.contains("Missing evidence: " + g), g)
    }
  }

  // --- m1 -----------------------------------------------------------------------------------

  test("m1 regression: a failed synthesis audits real attempts, versions, tokens and model kind") {
    val w = new WorkflowWorld
    val bad = new ScriptedModel(
      Vector(_ => Right(ModelReply("""{"schemaVersion":"x","claims":[]}""", Some(5L), Some(7L))))
    )
    val r = w.workflow(w.config(model = bad, maxAttempts = 3)).answer("query:m1a-1", Coauthors)
    assertEquals(abstained(r).reason, AbstentionReason.SynthesisFailed)
    val a = r.audit
    assertEquals(a.synthesisAttempts, 3)
    assertEquals(a.promptVersion, Some(Prompts.AnswerSynthesisVersion))
    assertEquals(a.outputSchemaVersion, Some(Prompts.OutputSchemaVersion))
    assertEquals(a.modelVersion, Some("test-scripted-model.v1"))
    assert(a.nonProductionModel)
    assertEquals((a.inputTokens, a.outputTokens), (Some(15L), Some(21L)))
    assertEquals(a.synthesisOutputSha256, None)
    val prod = new ScriptedModel(Vector(ScriptedModel.text("{")), nonProduction = false)
    val p = w.workflow(w.config(model = prod, maxAttempts = 2)).answer("query:m1a-2", Coauthors)
    assertEquals((p.audit.synthesisAttempts, p.audit.nonProductionModel), (2, false))
    val tiny = w.workflow(w.config(maxPromptBytes = 100)).answer("query:m1a-3", Coauthors)
    assertEquals(tiny.audit.synthesisAttempts, 0)
    assertEquals(tiny.audit.promptVersion, Some(Prompts.AnswerSynthesisVersion))
    assert(tiny.audit.nonProductionModel)
  }

  // --- m3 -----------------------------------------------------------------------------------

  test("m3 regression: an abstention after evidence names no bundle; a forged name is refused") {
    val w = new WorkflowWorld
    val wf = w.workflow(
      w.config(model = new ScriptedModel(Vector(ScriptedModel.text("{"))), maxAttempts = 1)
    )
    val r = wf.answer("query:m3b-1", Coauthors)
    abstained(r)
    assert(r.evidence.isDefined)
    assertEquals(r.audit.bundleId, None)
    val p = r.persistence.fold(e => fail(e.message), identity)
    assertEquals(p.bundleWritten, None)
    val Right(Some(item)) = w.base.kv.get(
      TableRole.QuerySession,
      TableKeys.QuerySession.session("query:m3b-1").toOption.get
    ): @unchecked
    assertEquals(ItemCodecs.QuerySessionCodec.decode(item).toOption.get.bundleId, None)
    val forged = r.copy(auditWrite =
      r.auditWrite.copy(audit = r.audit.copy(bundleId = Some(r.evidence.get.bundle.bundleId)))
    )
    val Left(err) = wf.persistAgain(forged): @unchecked
    assert(err.message.contains("bundleId"), err.message)
  }

  // --- m4 -----------------------------------------------------------------------------------

  /** A runtime-table writer that refuses the row of one primary key, delegating the rest. */
  private final class FailingWriter(inner: KeyValueWriter, key: PrimaryKey) extends KeyValueWriter {
    private def hit(item: Item) =
      item.values.exists(_ == AttrValue.S(key.pk)) && item.values.exists(_ == AttrValue.S(key.sk))
    def putIfAbsent(table: TableRole, item: Item) =
      if (table == TableRole.QuerySession && hit(item))
        Left(StoreError.Refused("injected write failure"))
      else inner.putIfAbsent(table, item)
    def putIfVersion(table: TableRole, item: Item, expectedVersion: Long) =
      inner.putIfVersion(table, item, expectedVersion)
  }

  private def failing(w: WorkflowWorld, key: PrimaryKey): CollaborationWorkflow = {
    val rt = RuntimeStores(
      w.objects,
      w.base.kv,
      new FailingWriter(w.base.kv, key),
      w.base.graph,
      w.vectors,
      w.vectors,
      EmbeddingPolicy.SyntheticTestOnly
    )
    new CollaborationWorkflow(rt, serving, w.config(), w.base.clock)
  }

  private def auditOutcome(w: WorkflowWorld, q: String): Option[String] = {
    val Right(got) =
      w.base.kv
        .get(TableRole.QuerySession, TableKeys.QuerySession.audit(q).toOption.get): @unchecked
    got.map(i =>
      upickle.default
        .read[AuditRecord](ItemCodecs.QueryAuditCodec.decode(i).toOption.get.auditJson)
        .outcome
    )
  }

  test("m4 regression: the stored audit outcome is always the outcome the caller receives") {
    // Session row fails AFTER the audit row: the audit (answered) is stored, so the answer is
    // returned and the failure is reported.
    val w1 = new WorkflowWorld
    val q1 = "query:m4-1"
    val r1 = failing(w1, TableKeys.QuerySession.session(q1).toOption.get).answer(q1, Coauthors)
    answered(r1)
    val p1 = r1.persistence.fold(e => fail(e.message), identity)
    assertEquals(p1.sessionWritten, None)
    assert(
      p1.notPersisted.exists(_.startsWith(AuditStage.SessionNotPersisted)),
      p1.notPersisted.toString
    )
    assertEquals(auditOutcome(w1, q1), Some("answered"))
    // Audit row fails: the answer is withheld and NO audit row says answered.
    val w2 = new WorkflowWorld
    val q2 = "query:m4-2"
    val r2 = failing(w2, TableKeys.QuerySession.audit(q2).toOption.get).answer(q2, Coauthors)
    assertEquals(abstained(r2).reason, AbstentionReason.AuditNotPersisted)
    assert(r2.persistence.isLeft)
    assertEquals(auditOutcome(w2, q2), None)
    assertEquals(
      w2.base.kv.get(TableRole.QuerySession, TableKeys.QuerySession.session(q2).toOption.get),
      Right(None)
    )
  }

  // --- m5 -----------------------------------------------------------------------------------

  test(
    "m5 regression: the audit persists the structured plan with IDs, never names or question text"
  ) {
    val w = new WorkflowWorld
    val r = w.workflow().answer("query:m5-1", Coauthors)
    answered(r)
    val plan = r.audit.plan.getOrElse(fail("no audited plan"))
    assertEquals(plan.planVersion, QueryPlanner.Version)
    assertEquals(plan.window, W)
    assertEquals(plan.steps.map(_.operation), r.plan.get.steps.map(_.operation.tag))
    val resolvedArg = AuditResearcherArg(0, Some(ada), AuditResearcherArg.Resolved)
    assertEquals(plan.steps(0).researchers, Vector(resolvedArg))
    assertEquals(plan.steps(1).researchers, Vector(resolvedArg))
    assertEquals(plan.steps(1).intent, Some("direct-coauthors"))
    assertEquals(plan.steps(1).metrics, Vector("joint-publication-count"))
    assert(plan.steps.forall(_.unresolvedArguments.isEmpty))
    val json = AuditRecord.json(r.audit)
    assert(!json.contains("Ada Lovelace") && !json.contains("coauthors of"))
    // Unresolved arguments are explicit.
    val amb = w.workflow().answer("query:m5-2", "Who are the coauthors of John Smith?")
    val ap = amb.audit.plan.get
    assertEquals(
      ap.steps(0).researchers,
      Vector(AuditResearcherArg(0, None, AuditResearcherArg.Unresolved))
    )
    assertEquals(ap.steps(0).unresolvedArguments, Vector(QueryPlanner.Args.Name))
    assertEquals(ap.steps(1).unresolvedArguments, Vector(s"${QueryPlanner.Args.Researchers}[0]"))
    assert(!AuditRecord.json(amb.audit).contains("John Smith"))
    // Filters: key and value hash only, marked not applied; ranking rule recorded.
    val t = w.workflow().answer("query:m5-3", Titles)
    val semStep = t.audit.plan.get.steps.find(_.operation == "semantic-context").get
    assertEquals(
      semStep.filters,
      Vector(AuditFilter(QueryPlanner.Args.Topic, Canonical.sha256HexUtf8("graph methods"), false))
    )
    assert(!AuditRecord.json(t.audit).contains("graph methods"))
    val rank = w.workflow().answer("query:m5-4", "Who has the most distinct coauthors?")
    assertEquals(
      rank.audit.plan.get.steps.find(_.operation == "rank-researchers").flatMap(_.rankingRule),
      Some(QueryPlanner.RankingRules.DistinctCoauthors)
    )
    // The schema (v3 since RG-P11-02, which adds verifierVersion; nested keys unchanged since v2)
    // pins the nested step and argument keys; the storage row carries the current version.
    val schema = ujson.read(Files.readString(root.resolve(AuditRecord.SchemaFile)))
    assertEquals(schema("$id").str, AuditRecord.SchemaVersion)
    val stepJs = ujson.read(upickle.default.write(plan.steps(1)))
    assertEquals(stepJs.obj.keys.toSet, schema("$defs")("step")("required").arr.map(_.str).toSet)
    assertEquals(
      ujson.read(upickle.default.write(resolvedArg)).obj.keys.toSet,
      schema("$defs")("researcherArg")("required").arr.map(_.str).toSet
    )
    assertEquals(
      ujson.read(upickle.default.write(semStep.filters.head)).obj.keys.toSet,
      schema("$defs")("filter")("required").arr.map(_.str).toSet
    )
    val Right(Some(row)) = w.base.kv.get(
      TableRole.QuerySession,
      TableKeys.QuerySession.audit("query:m5-1").toOption.get
    ): @unchecked
    assertEquals(
      ItemCodecs.QueryAuditCodec.decode(row).toOption.get.auditSchemaVersion,
      "scholarchain.rag.audit-record.v3"
    )
  }

  // --- M4 at workflow level -----------------------------------------------------------------

  test("M4: an over-bound name abstains with bounded candidates and a truncation marker") {
    val w = new WorkflowWorld(AgentWorld.defaultLimits(maxCandidates = 2))
    w.base.link("John Smith", dan, 3)
    val ab = abstained(w.workflow().answer("query:m4c-1", "Who are the coauthors of John Smith?"))
    assertEquals(ab.reason, AbstentionReason.IdentityUnresolved)
    assertEquals(ab.names.size, 1)
    assert(ab.names.head.truncated)
    assertEquals(ab.names.head.reason, AbstainReason.TooManyCandidates)
    assertEquals(ab.names.head.candidates.size, 2)
  }

  // --- m6 -----------------------------------------------------------------------------------

  test("m6 regression: checks without an expectation are not applicable, never counted passed") {
    val w = new WorkflowWorld
    val set = BenchmarkSet(
      BenchmarkRunner.SchemaVersion,
      "repair-m6",
      "one case with only an outcome and resolved expectation",
      Vector(
        BenchmarkCase(
          "only-outcome",
          "graph",
          Coauthors,
          BenchmarkExpectation(
            "answered",
            None,
            Vector.empty,
            Vector("{ada}"),
            Vector.empty,
            Vector.empty,
            Vector.empty,
            0,
            Vector.empty,
            None,
            None
          )
        )
      )
    )
    val report = BenchmarkRunner.run(set, w.aliases, "m6")((q, t) => w.workflow().answer(q, t))
    assert(report.passed)
    // outcome, resolved, claims-cite-evidence apply; the 7 expectation-less graph checks do not.
    assertEquals((report.graph.checks, report.graph.checksPassed), (3, 3))
    assertEquals(report.graph.checksNotApplicable, 7)
    // No semantic expectation and no semantic evidence: nothing semantic is counted.
    assertEquals((report.semantic.checks, report.semantic.checksNotApplicable), (0, 2))
    assert(report.lines.head.contains("7 not applicable"), report.lines.head)
    val na = report.results.head.checks.filterNot(_.applicable)
    assert(na.forall(_.passed) && na.forall(_.detail.startsWith("not applicable")))
  }
}
