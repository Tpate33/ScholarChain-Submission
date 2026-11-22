package scholarchain.tests.rag

import java.nio.charset.StandardCharsets
import java.util.Locale

import scala.collection.mutable

import scholarchain.rag.agents.{
  AbstainReason,
  AbstentionReason,
  AuditRecord,
  FactFields,
  ModelAssertion,
  Verdict,
  WorkflowOutcome,
  WorkflowResult
}
import scholarchain.rag.benchmark.{BenchmarkReport, BenchmarkRunner, BenchmarkSet, FamilySummary}
import scholarchain.storage.model.{EntityLinkSurfaceForm, TableKeys, TableRole}

/**
 * TE-P9-01 item 2 (README Phase 9 Gate: "Ambiguous researcher names do not silently resolve to one
 * identity", "Every numerical and path claim has evidence IDs", "Deterministic graph retrieval and
 * semantic retrieval are evaluated separately"; work order "Run sbt check and the benchmark-query
 * suite").
 *
 * The rag [[BenchmarkRunner]] runs [[scholarchain.rag.agents.CollaborationWorkflow]] over the LOCAL
 * CORPUS (OpeningLoad with `OpeningFixture.configWithEntityLinks`) with the committed set
 * `benchmarks/collaboration-corpus-run1.v1.json`. Every alias and every expectation is computed
 * from source records by [[Phase9Corpus]], never from workflow output. Graph retrieval and semantic
 * retrieval are reported and asserted SEPARATELY; the semantic family is NON-PRODUCTION PLUMBING
 * ONLY (hashed-token stand-in embedder, template stand-in synthesizer): it proves filters and
 * pointers, never retrieval or answer quality.
 */
class CollaborationWorkflowCorpusBenchmarkSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")

  private val C = Phase9Corpus
  private val SetFile = "/benchmarks/collaboration-corpus-run1.v1.json"

  private lazy val world = new CorpusWorld(C.Dim)

  private lazy val set: BenchmarkSet = {
    val in = getClass.getResourceAsStream(SetFile)
    assert(in != null, s"$SetFile missing from the test classpath")
    val text =
      try new String(in.readAllBytes(), StandardCharsets.UTF_8)
      finally in.close()
    BenchmarkRunner.parse(text).fold(e => fail(e), identity)
  }

  /** The runner's report and every (queryId, result) it produced. */
  private lazy val run: (BenchmarkReport, Vector[(String, WorkflowResult)]) = {
    val wf = world.workflow()
    val seen = mutable.ArrayBuffer.empty[(String, WorkflowResult)]
    val report = BenchmarkRunner.run(set, C.aliases, "te-p9-corpus") { (q, text) =>
      val r = wf.answer(q, text)
      seen += (q -> r)
      r
    }
    // Report lines name applicable checks ("checks n/m passed") and not-applicable checks apart;
    // the semantic line covers NON-PRODUCTION plumbing only (stand-in embedder and synthesizer).
    report.lines.foreach(l => println(s"TE-P9-02 corpus benchmark: $l"))
    println(
      "TE-P9-02 corpus benchmark: the semantic family is NON-PRODUCTION plumbing only " +
        "(filters, pointers, labels); it measures no retrieval or answer quality"
    )
    (report, seen.toVector)
  }

  private def resultOf(caseId: String): WorkflowResult = {
    val i = set.cases.indexWhere(_.id == caseId)
    assert(i >= 0, caseId)
    run._2(i)._2
  }

  private def answered(r: WorkflowResult): WorkflowOutcome.Answered = r.outcome match {
    case a: WorkflowOutcome.Answered => a
    case other => fail(s"expected an answer, got $other")
  }

  private def verifiedAssertions(r: WorkflowResult): Vector[ModelAssertion] =
    answered(r).verification.results.filter(_.verdict == Verdict.Verified).flatMap(_.assertions)

  // ------------------------------------------------------------------ source data

  test("corpus source counts (no silent coverage loss) and the derived aliases") {
    assertEquals(C.surfaces.size, 148, "surface forms of identified mentions")
    assertEquals(C.ambiguous.size, 9, "surface forms naming two or more researchers")
    assertEquals(C.surfaces.map(_.researchers.size).sum, 157, "one link row per (form, researcher)")
    assertEquals(C.windows.size, 2, "analysis windows of the run")
    assert(C.edgesW.nonEmpty && C.metricsW.nonEmpty)
    // The stored link rows agree with the source upstream, form by form.
    C.surfaces.foreach { s =>
      assertEquals(C.linkRows(world.kvStore, s.sha).map(_.researcherId).toSet, s.researchers)
    }
    println(
      s"TE-P9-01 corpus: window ${C.W}, ${C.edgesW.size} edges, ${C.metricsW.size} researcher rows; " +
        s"aliases ${C.aliases.toVector.sortBy(_._1).map((k, v) => s"$k=$v").mkString(", ")}"
    )
    assertEquals(set.cases.size, 9)
    assertEquals(set.cases.count(_.category == "graph"), 8)
    assertEquals(set.cases.count(_.category == "semantic"), 1)
  }

  // ------------------------------------------------------------------ the two families, separately

  /**
   * TE-P9-02 (CR-P9-01 m6): which checks of a case have an expectation to inspect, derived from the
   * committed set (and, for the two invariants, from whether there is an answer or semantic context
   * to inspect), independently of the runner's own `applicable` flag.
   */
  private def expectedApplicable(
      c: scholarchain.rag.benchmark.BenchmarkCase,
      r: WorkflowResult
  ): Set[String] = {
    val e = c.expect
    val answeredCase = r.outcome.tag == "answered"
    val semanticItems = r.outcome match {
      case WorkflowOutcome.Answered(resp, _, _) =>
        resp.evidenceBundle.items.exists(_.evidenceType == scholarchain.core.EvidenceType.TextChunk)
      case _ => false
    }
    Vector(
      "outcome" -> true,
      "reason" -> e.reason.isDefined,
      "operations" -> e.operations.nonEmpty,
      "resolved" -> true,
      "candidates" -> e.candidates.nonEmpty,
      "verified-facts" -> e.verifiedFacts.nonEmpty,
      "verdicts" -> e.allowedVerdicts.nonEmpty,
      "min-verified" -> (e.minVerified > 0),
      "forbidden-text" -> e.forbiddenText.nonEmpty,
      "claims-cite-evidence" -> answeredCase,
      "semantic-top-subject" -> (e.semanticIndex.isDefined && e.semanticTopSubject.isDefined),
      "semantic-not-proof" -> semanticItems
    ).collect { case (n, true) => n }.toSet
  }

  test("check applicability: only checks with an expectation count; not-applicable never passes") {
    val (report, results) = run
    assertEquals(report.results.size, set.cases.size)
    set.cases.zip(report.results).zip(results).foreach { case ((c, cr), (_, r)) =>
      assertEquals(cr.id, c.id)
      assertEquals(
        cr.checks.filter(_.applicable).map(_.name).toSet,
        expectedApplicable(c, r),
        s"${c.id}: applicable checks"
      )
      cr.checks.filterNot(_.applicable).foreach { ch =>
        // A not-applicable check cannot fail a case, and is never counted as passed coverage.
        assert(ch.passed && ch.detail.startsWith("not applicable"), s"${c.id}: $ch")
      }
    }
    // Per-family totals: counted from the per-case results, then compared with the summaries.
    def tally(f: String) = {
      val all = report.results.flatMap(_.checks.filter(_.family == f))
      (all.count(_.applicable), all.count(c => c.applicable && c.passed), all.count(!_.applicable))
    }
    assertEquals(
      tally("graph"),
      (report.graph.checks, report.graph.checksPassed, report.graph.checksNotApplicable)
    )
    assertEquals(
      tally("semantic"),
      (report.semantic.checks, report.semantic.checksPassed, report.semantic.checksNotApplicable)
    )
  }

  test(
    "GRAPH (deterministic retrieval) family: 8/8 cases; 55 applicable checks passed, 35 not applicable"
  ) {
    val (report, _) = run
    val failed = report.lines.filter(_.startsWith("FAILED"))
    assertEquals(failed, Vector.empty[String])
    assertEquals(report.graph, FamilySummary("graph", 8, 8, 55, 55, 35))
    assert(report.lines.contains("graph: cases 8/8 passed, checks 55/55 passed, 35 not applicable"))
  }

  test(
    "SEMANTIC family, NON-PRODUCTION PLUMBING ONLY: 1/1 case; 2 applicable checks passed, 16 not applicable"
  ) {
    val (report, _) = run
    assertEquals(report.semantic, FamilySummary("semantic", 1, 1, 2, 2, 16))
    assert(
      report.lines.contains("semantic: cases 1/1 passed, checks 2/2 passed, 16 not applicable")
    )
    val r = resultOf("nonproduction-semantic-plumbing-profile-filter")
    val a = answered(r)
    assert(a.nonProductionModel, "the template stand-in is labelled non-production")
    // Every semantic item is the filtered profile of the resolved researcher, in the window.
    val items = r.semantic.flatMap(_.retrievals).flatMap(_.context.items)
    assert(items.nonEmpty, "no semantic item retrieved")
    items.foreach { it =>
      assertEquals(it.subjectId, C.s)
      assertEquals(it.window, C.W)
      assert(it.modelVersion.contains("nonprod"), it.modelVersion)
    }
    // Semantic items never support a deterministic claim.
    val semIds = r.evidence.get.semanticIds.toSet
    a.verification.results.filter(_.verdict == Verdict.Verified).foreach { c =>
      if (c.kept.exists(_.evidenceIds.forall(semIds)))
        assert(c.assertions.forall(x => FactFields.descriptive(x.field)), c.toString)
    }
  }

  // ------------------------------------------------------------------ exact values against source

  test("graph: direct coauthors are exactly the stored coauthors of u, with stored weights") {
    val r = resultOf("unique-name-direct-coauthors")
    val va = verifiedAssertions(r)
    val sub = s"${C.u}@${C.W}"
    val expected = C.adj(C.u).map(x => x._1 -> x._2.jointPublicationCount).toMap
    assertEquals(
      va.filter(a => a.subject == sub && a.field == FactFields.Coauthor).map(_.value).toSet,
      expected.keySet
    )
    expected.foreach { case (c, j) =>
      val p = scholarchain.rag.agents.BundleFacts.pair(C.u, c, C.W)
      assert(
        va.exists(a =>
          a.subject == p && a.field == FactFields.JointPublicationCount && a.value == j.toString
        ),
        s"$p joint $j"
      )
    }
    println(s"TE-P9-01 direct coauthors of ${C.u}: ${expected.size} (source edges)")
    assert(expected.size >= 1)
  }

  test("graph: metric lookup equals the stored researcher-metrics record of u") {
    val va = verifiedAssertions(resultOf("unique-name-metric-lookup"))
    val m = C.metricsW(C.u)
    val sub = s"${C.u}@${C.W}"
    Vector(
      FactFields.PublicationCount -> m.publicationCount.toString,
      FactFields.DistinctCoauthorCount -> m.distinctCoauthorCount.toString,
      FactFields.TotalCoauthorOccurrences -> m.totalCoauthorOccurrences.toString,
      FactFields.AverageCoauthorsPerPaper -> java.lang.Double.toString(m.averageCoauthorsPerPaper),
      FactFields.WeightedDegree -> m.weightedDegree.toString
    ).foreach { case (f, v) =>
      assert(va.exists(a => a.subject == sub && a.field == f && a.value == v), s"$f = $v")
    }
  }

  test("graph: the path has the source BFS hop count and every hop is a stored edge of W") {
    val (pa, pb, hops) = C.pathPair
    val va = verifiedAssertions(resultOf("shortest-path-found"))
    val sub = s"$pa|$pb@${C.W}"
    val nodes = (0 to hops).map { i =>
      va.find(a => a.subject == sub && a.field == s"${FactFields.PathNodePrefix}$i")
        .getOrElse(fail(s"path-node-$i not verified"))
        .value
    }.toVector
    assert(!va.exists(_.field == s"${FactFields.PathNodePrefix}${hops + 1}"))
    assertEquals(nodes.head, pa)
    assertEquals(nodes.last, pb)
    nodes.zip(nodes.tail).foreach { case (x, y) =>
      assert(C.adj(x).exists(_._1 == y), s"$x -> $y is not a stored coauthor edge in ${C.W}")
    }
    // M1: a VERIFIED shortest-path claim rests on an untruncated traversal (proven shortest).
    assert(va.exists(a => a.subject == sub && a.field == FactFields.ShortestPathHops))
    val trav = C.pathTraversals(resultOf("shortest-path-found"))
    assertEquals(
      trav.map(t => (t.outcome, t.searchTruncated, t.hopCount)),
      Vector(("found", false, Some(hops)))
    )
    println(s"TE-P9-01 path $pa -> $pb: $hops hops (source BFS)")
  }

  test("graph: the not-connected pair has no source path; the claim states the configured bound") {
    val (na, nb) = C.disconnected
    assert(!C.bfs(na).contains(nb))
    val va = verifiedAssertions(resultOf("shortest-path-not-connected"))
    assert(
      va.exists(a =>
        a.subject == s"$na|$nb@${C.W}" && a.field == FactFields.NotConnectedWithin &&
          a.value == C.MaxHops.toString
      )
    )
  }

  test("graph: the ranking is the stored C_r order (C desc, P desc, ID asc) for all top-k") {
    val va = verifiedAssertions(resultOf("ranking-distinct-coauthors"))
    val field = s"${FactFields.RankPrefix}distinct-coauthor-count"
    val got = va.filter(_.field == field).map(a => a.value.toInt -> a.subject).toMap
    val k = math.min(C.RankK, C.rankingC.size)
    assertEquals(got.size, k)
    (1 to k).foreach(i =>
      assertEquals(got(i), s"${C.rankingC(i - 1).researcherId}@${C.W}", s"rank $i")
    )
  }

  // ------------------------------------------------------------------ all ambiguous names

  test("every one of the 9 ambiguous names abstains with ALL its candidates, under variants") {
    val wf = world.workflow()
    var asked = 0
    var formsAsked = Set.empty[String]
    C.ambiguous.foreach { s =>
      val spellings = s.spellings.filter(C.askable)
      assert(spellings.nonEmpty, s"ambiguous form ${s.sha} has no askable spelling")
      val variants = spellings.flatMap { sp =>
        Vector(sp, "  " + sp.replace(" ", "   ") + "  ", sp.toUpperCase(Locale.ROOT))
          .filter(v =>
            C.askable(v) &&
              EntityLinkSurfaceForm.normalize(v) == EntityLinkSurfaceForm.normalize(sp)
          )
      }.distinct
      variants.foreach { v =>
        val q = s"query:te-p9-amb-$asked"
        asked += 1
        val r = wf.answer(q, s"Who are the coauthors of \"$v\"?")
        r.outcome match {
          case WorkflowOutcome.Abstained(AbstentionReason.IdentityUnresolved, _, names, _) =>
            assertEquals(names.size, 1, v)
            assertEquals(names.head.reason, AbstainReason.MultipleCandidates, v)
            assertEquals(names.head.candidates.map(_.researcherId).toSet, s.researchers, v)
            names.head.candidates.foreach { c =>
              assertEquals(c.link.surfaceSha256, s.sha)
              assertEquals(c.link.sourceRunId, C.RunId)
            }
          case other => fail(s"'$v' did not abstain with its candidates: $other")
        }
        assertEquals(r.audit.resolvedResearcherIds, Vector.empty[String], v)
        assertEquals(r.audit.abstainedNames, 1)
        assertEquals(
          C.auditRow(world.kvStore, q).map(_.auditSha256),
          Some(AuditRecord.sha256(r.audit))
        )
        formsAsked += s.sha
      }
    }
    println(
      s"TE-P9-01 ambiguity: ${formsAsked.size} forms, $asked questions (spellings and variants)"
    )
    assertEquals(formsAsked.size, 9)
    assert(asked >= 18, s"only $asked ambiguous questions")
  }

  // ------------------------------------------------------------------ all unique names

  test("every askable unique name resolves to exactly its researcher (full sweep)") {
    val wf = world.workflow()
    val askable = C.unique.flatMap(s => C.askName(s).map(_ -> s.researchers.head))
    val unaskable = C.unique.filter(C.askName(_).isEmpty)
    println(
      s"TE-P9-01 unique sweep: ${C.unique.size} unique forms, ${askable.size} askable, " +
        s"${unaskable.size} unaskable ${unaskable.map(_.spellings).mkString("; ")}"
    )
    assertEquals(C.unique.size, 139)
    assertEquals(unaskable.size, 0, "every unique corpus name can be asked")
    val failures = mutable.ArrayBuffer.empty[String]
    askable.zipWithIndex.foreach { case ((name, rid), i) =>
      val r = wf.answer(s"query:te-p9-uniq-$i", s"What is the publication count of \"$name\"?")
      val ok = r.audit.resolvedResearcherIds == Vector(rid) && (r.outcome match {
        case _: WorkflowOutcome.Answered => C.metricsW.contains(rid)
        case WorkflowOutcome.Abstained(AbstentionReason.InsufficientEvidence, _, _, _) =>
          !C.metricsW.contains(rid)
        case _ => false
      })
      if (!ok)
        failures += s"'$name' -> expected $rid, got ${r.audit.resolvedResearcherIds} ${r.outcome}"
    }
    assertEquals(failures.toVector, Vector.empty[String])
  }

  // ------------------------------------------------------------------ TE-P9-03 F1: rendered text

  /** The corpus questions of the F1 sweep beyond the benchmark set (every graph intent). */
  private def sweepQuestions: Vector[String] = {
    val uName = C.uniqueNames(C.u)
    val (pa, pb, _) = C.pathPair
    val (na, nb) = C.disconnected
    Vector(
      s"Who are the coauthors of \"$uName\"?",
      s"How many publications does \"$uName\" have?",
      s"What is the shortest path between \"${C.uniqueNames(pa)}\" and \"${C.uniqueNames(pb)}\"?",
      s"What is the shortest path between \"${C.uniqueNames(na)}\" and \"${C.uniqueNames(nb)}\"?",
      "Who has the most distinct coauthors?",
      "Which researchers bridge communities?",
      s"Which community is \"$uName\" in?",
      s"What is the coreness of \"$uName\"?",
      s"What is the largest team of \"$uName\"?",
      s"Which component is \"$uName\" in?",
      s"Explain the research of \"${C.uniqueNames(C.s)}\"."
    )
  }

  /** Every (queryId, result) of the benchmark run and of the sweep questions. */
  private lazy val sweep: Vector[(String, WorkflowResult)] = {
    val wf = world.workflow()
    run._2 ++ sweepQuestions.zipWithIndex.map { case (t, i) =>
      val q = s"query:te-p9-f1-sweep-$i"
      q -> wf.answer(q, t)
    }
  }

  test(
    "F1 corpus: every kept claim's text is the oracle rendering of its verified assertions, values from source"
  ) {
    var answeredN = 0
    var claims = 0
    var verified = 0
    var labelled = 0
    var sourceChecked = 0
    var sourceUndecided = 0
    sweep.foreach { case (q, r) =>
      r.outcome match {
        case WorkflowOutcome.Answered(resp, report, _) =>
          answeredN += 1
          val facts = scholarchain.rag.agents.BundleFacts
            .of(resp.evidenceBundle)
            .fold(e => fail(s"$q: $e"), identity)
          val windows = RenderingOracle.windows(r.plan.get)
          assertEquals(RenderingOracle.foreignLines(resp.answerText, report), Vector.empty, q)
          assertEquals(resp.claims, report.claims, q)
          report.results.foreach { c =>
            c.kept.foreach { k =>
              claims += 1
              assert(scholarchain.rag.agents.ClaimRendering.safe(k.text), s"$q: ${k.text}")
              RenderingOracle.expected(c, facts, windows) match {
                case Some(t) => assertEquals(k.text, t, s"$q: claim ${c.ordinal} (${c.kind})")
                case None => fail(s"$q: a ${c.verdict} claim is kept in a template-model answer")
              }
              if (c.verdict == Verdict.Verified) verified += 1 else labelled += 1
            }
            if (c.verdict == Verdict.Verified) {
              // A deterministic claim is never VERIFIED from descriptive support.
              if (!scholarchain.rag.agents.Prompts.DescriptiveKinds(c.kind))
                assert(c.assertions.forall(a => !FactFields.descriptive(a.field)), s"$q: $c")
              c.assertions.foreach { a =>
                C.sourceAgrees(a.subject, a.field, a.value) match {
                  case Some(ok) =>
                    sourceChecked += 1
                    assert(ok, s"$q: ${a.subject} ${a.field}=${a.value} disagrees with the source")
                  case None => sourceUndecided += 1
                }
              }
            }
          }
        case _ => ()
      }
    }
    println(
      s"TE-P9-03 F1 corpus: ${sweep.size} questions, $answeredN answered, $claims kept claims " +
        s"($verified VERIFIED, $labelled labelled) equal to the oracle rendering; " +
        s"$sourceChecked verified assertions equal to source records, $sourceUndecided not decidable from source"
    )
    assertEquals(sweep.size, set.cases.size + sweepQuestions.size)
    assert(answeredN >= 11, s"only $answeredN answered")
    assert(verified >= 40, s"only $verified verified claims")
    assert(sourceChecked >= 80, s"only $sourceChecked source-checked assertions")
  }

  test(
    "F1 corpus: model claim text is withheld: drafts replaced by sentinels change no kept text"
  ) {
    val marker = "MODEL-DRAFT-WORDING-5e1"
    val drafted = new ScriptedModel(Vector(ScriptedModel.template { (_, o) =>
      o.copy(claims = o.claims.zipWithIndex.map { case (c, i) => c.copy(text = s"$marker $i") })
    }))
    val wfT = world.workflow()
    val wfD = world.workflow(C.config(model = drafted))
    var compared = 0
    sweepQuestions.zipWithIndex.foreach { case (t, i) =>
      val a = wfT.answer(s"query:te-p9-f1-tmpl-$i", t)
      val b = wfD.answer(s"query:te-p9-f1-draft-$i", t)
      (a.outcome, b.outcome) match {
        case (x: WorkflowOutcome.Answered, y: WorkflowOutcome.Answered) =>
          assert(!y.response.answerText.contains(marker), t)
          assertEquals(y.response.claims.map(_.text), x.response.claims.map(_.text), t)
          assertEquals(
            y.verification.results.map(_.verdict),
            x.verification.results.map(_.verdict),
            t
          )
          compared += y.response.claims.size
        case (x, y) => assertEquals(y.tag, x.tag, t)
      }
    }
    println(s"TE-P9-03 F1 corpus: $compared kept claims identical with sentinel drafts")
    // Exact coverage (observed TE-P9-03 sbt test, 2026-11-21): a loss of compared claims fails.
    assertEquals(compared, 37, "kept claims compared over the 11 sweep questions")
  }

  // ------------------------------------------------------------------ audit of every question

  test("every benchmark question is audited: row hash, outcome, session and bundle rows") {
    val (_, results) = run
    assertEquals(results.size, set.cases.size)
    results.foreach { case (q, r) =>
      val row = C.auditRow(world.kvStore, q).getOrElse(fail(s"$q: no audit row"))
      assertEquals(row.auditSha256, AuditRecord.sha256(r.audit), q)
      assertEquals(upickle.default.read[AuditRecord](row.auditJson).outcome, r.outcome.tag, q)
      val session =
        C.present(world.kvStore, TableRole.QuerySession, TableKeys.QuerySession.session(q))
      assertEquals(session, r.outcome.tag != "refused", s"$q: session row")
      val bundle = r.audit.bundleId.exists(b =>
        C.present(world.kvStore, TableRole.EvidenceBundle, TableKeys.EvidenceBundle.bundle(b))
      )
      assertEquals(bundle, r.outcome.tag == "answered", s"$q: bundle row")
      // m3: an abstention or refusal names no bundle at all (none is written for it).
      if (r.outcome.tag != "answered") assertEquals(r.audit.bundleId, None, q)
      assertEquals(
        r.persistence.toOption.get.notPersisted
          .exists(_.startsWith(scholarchain.rag.agents.AuditStage.SessionNotPersisted)),
        false,
        q
      )
    }
    assertEquals(
      results.map(_._2.outcome.tag).groupBy(identity).map((k, v) => k -> v.size),
      Map("answered" -> 6, "abstained" -> 2, "refused" -> 1)
    )
  }
}
