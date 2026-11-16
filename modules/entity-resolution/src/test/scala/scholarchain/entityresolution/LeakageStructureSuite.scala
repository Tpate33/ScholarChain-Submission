package scholarchain.entityresolution

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import scholarchain.entityresolution.policy.{MatchPolicy, PolicyFixtures}

/**
 * Structural no-leakage check (README Phase 5 gate: no data leakage from evaluation labels into
 * matching logic). The matching path (root package, `identifiers`, `policy`, `adjudication`) must
 * not import or name the review-queue or evaluation packages, and nothing outside `evaluation` may
 * name gold-label types. Comments are stripped before scanning, so documentation may mention them.
 */
class LeakageStructureSuite extends munit.FunSuite {

  private val mainRoot: Path =
    PolicyFixtures.repoRoot.resolve(
      "modules/entity-resolution/src/main/scala/scholarchain/entityresolution"
    )

  private def sources: Vector[(String, String)] = {
    val s = Files.walk(mainRoot)
    try
      s.iterator.asScala.toVector
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
        .map(p =>
          mainRoot.relativize(p).toString.replace('\\', '/') -> Files
            .readString(p, StandardCharsets.UTF_8)
        )
        .sortBy(_._1)
    finally s.close()
  }

  private def code(src: String): String =
    src
      .replaceAll("(?s)/\\*.*?\\*/", " ")
      .linesIterator
      .map(l => l.replaceAll("//.*$", ""))
      .mkString("\n")

  private val goldNames =
    Vector(
      "GoldLabel",
      "GoldLabels",
      "GoldSetEvaluation",
      "EvaluationReport",
      "LabelProblem",
      "er-gold-label"
    )

  private def inPackage(rel: String, pkg: String): Boolean = rel.startsWith(pkg + "/")

  test("the scan sees every package of the module") {
    val rels = sources.map(_._1)
    Vector("policy/", "adjudication/", "identifiers/", "review/", "evaluation/").foreach { p =>
      assert(rels.exists(_.startsWith(p)), s"no sources under $p")
    }
    assert(rels.contains("MatchFeatures.scala"))
  }

  test("the matching path never references the review queue or the evaluation packages") {
    val matching = sources.filterNot { case (rel, _) =>
      inPackage(rel, "review") || inPackage(rel, "evaluation")
    }
    matching.foreach { case (rel, src) =>
      val c = code(src)
      assert(!"""\bevaluation\b""".r.findFirstIn(c).isDefined, s"$rel references evaluation")
      assert(!"""\breview\b""".r.findFirstIn(c).isDefined, s"$rel references review")
      Vector("ReviewQueue", "ReviewQueueRow", "SystemDecision").foreach(n =>
        assert(!c.contains(n), s"$rel names $n")
      )
    }
  }

  test("only the evaluation package names gold-label types") {
    sources.filterNot { case (rel, _) => inPackage(rel, "evaluation") }.foreach { case (rel, src) =>
      val c = code(src)
      goldNames.foreach(n => assert(!c.contains(n), s"$rel names $n"))
    }
  }

  test("the review queue does not import evaluation either (labels flow only into evaluation)") {
    sources.filter { case (rel, _) => inPackage(rel, "review") }.foreach { case (rel, src) =>
      assert(
        !"""\bevaluation\b""".r.findFirstIn(code(src)).isDefined,
        s"$rel references evaluation"
      )
    }
  }

  /** Membership code: everything that decides which pairs are gold members. */
  private val membershipFiles = Vector("review/GoldMembership.scala", "review/SelectionUnits.scala")

  /**
   * Identifiers that would let membership depend on matching: the policy and resolver, decisions
   * and their parts, scores and bands, rule and feature values, plausibility, LLM adjudication, and
   * the outcome-stratified queue.
   */
  private val decisionWords = Vector(
    "policy",
    "MatchPolicy",
    "Resolver",
    "ResolutionOutcome",
    "decide",
    "decision",
    "SystemDecision",
    "outcome",
    "score",
    "ScoreBand",
    "band",
    "confidence",
    "matchPath",
    "rulesFired",
    "reasonCodes",
    "conflicts",
    "features",
    "exactIdAgreements",
    "blockKeys",
    "Plausibility",
    "plausible",
    "adjudication",
    "Adjudication",
    "llm",
    "ReviewQueue",
    "ReviewQueueRow",
    "stratumOf",
    "RunQueue"
  )

  private def decisionReferences(c: String): Vector[String] =
    decisionWords.filter(w => s"(?i)\\b$w\\b".r.findFirstIn(c).isDefined) ++
      Vector("scholarchain.entityresolution.policy", "entityresolution.adjudication")
        .filter(c.contains)

  test("membership code never references policy, Resolver, score, or any decision value") {
    val bySrc = sources.toMap
    membershipFiles.foreach { rel =>
      val src = bySrc.getOrElse(rel, fail(s"$rel not found"))
      assertEquals(decisionReferences(code(src)), Vector.empty, rel)
    }
    // The only candidate access is the CandidatePair projection of kind, pair, blocking and run.
    val m = code(bySrc("review/GoldMembership.scala"))
    val candidateFields = """\bc\.(\w+)""".r.findAllMatchIn(m).map(_.group(1)).toSet
    assertEquals(
      candidateFields,
      Set("entityKind", "sourceIdA", "sourceIdB", "blockingVersion", "provenance", "runId")
    )
  }

  test("the membership structural check fires on a decision reference") {
    Vector(
      "import scholarchain.entityresolution.policy.Resolver",
      "val s = o.score.probability",
      "m.copy(selectionRule = outcome.tag)",
      "x.features.get(k)",
      "ScoreBand.of(p, s)"
    ).foreach(l => assertNotEquals(decisionReferences(l), Vector.empty, l))
    assertEquals(decisionReferences("val sampleKey = 1; m.criterion; m.unitIds"), Vector.empty)
  }

  test("the versioned policy resource mentions no labels, gold data, or review decisions") {
    val text =
      MatchPolicy.resourceText(MatchPolicy.DefaultResource).fold(e => fail(e), identity).toLowerCase
    Vector("gold", "label", "review").foreach(w => assert(!text.contains(w), w))
  }

  // ---- ER-P6-05: the grouping layer (extends the checks above; none is relaxed) ----

  private val groupingPackages = Vector("grouping", "decisions")

  test("the scan sees the grouping and decision packages, and they are on the matching path") {
    val rels = sources.map(_._1)
    groupingPackages.foreach(p =>
      assert(rels.exists(_.startsWith(p + "/")), s"no sources under $p/")
    )
    assert(rels.contains("grouping/Grouping.scala"))
    // The matching-path test above covers every package except review and evaluation, so it
    // scans grouping and decisions; this pins that they are not exempt.
    assert(groupingPackages.forall(p => p != "review" && p != "evaluation"))
  }

  test("the grouping package never names review, evaluation, gold data or labels") {
    sources.filter { case (rel, _) => inPackage(rel, "grouping") }.foreach { case (rel, src) =>
      val c = code(src)
      Vector("""\breview\b""", """\bevaluation\b""", """(?i)\bgold""", """(?i)\blabels?\b""")
        .foreach(p => assert(p.r.findFirstIn(c).isEmpty, s"$rel matches $p"))
      goldNames.foreach(n => assert(!c.contains(n), s"$rel names $n"))
    }
  }

  test("grouping and decision sources read no clock and no randomness") {
    val forbidden = Vector(
      "Instant.now",
      "currentTimeMillis",
      "nanoTime",
      "LocalDate.now",
      "LocalDateTime.now",
      "ZonedDateTime.now",
      "Clock.",
      "Random",
      "UUID"
    )
    sources
      .filter { case (rel, _) => groupingPackages.exists(p => inPackage(rel, p)) }
      .foreach { case (rel, src) =>
        val c = code(src)
        forbidden.foreach(f => assert(!c.contains(f), s"$rel uses $f"))
      }
  }

  test("the grouping policy resource mentions no labels, gold data, or review decisions") {
    val text = scholarchain.entityresolution.grouping.GroupingPolicy.resourceText
      .fold(e => fail(e), identity)
      .toLowerCase
    Vector("gold", "label", "review").foreach(w => assert(!text.contains(w), w))
  }
}
