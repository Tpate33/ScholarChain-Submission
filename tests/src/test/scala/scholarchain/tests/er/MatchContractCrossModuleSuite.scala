package scholarchain.tests.er

import scholarchain.core.*
import scholarchain.core.ExactIdScheme as S
import scholarchain.core.config.{ConfigError, ConfigLoader, EntityResolutionConfig}
import scholarchain.entityresolution.adjudication.*
import scholarchain.entityresolution.identifiers.{ExactIdentifiers, IdAgreement}
import scholarchain.entityresolution.policy.*

import ErFixtures.*

/**
 * TE-P5-01 areas 4 and 8: the core `match-decision.v2` contract exercised with decisions that the
 * entity-resolution Resolver actually produces (cross-module), configuration band ordering through
 * both the core loader and `MatchPolicy.withConfig`, policy versioning, and the Semantic Scholar
 * and repository-URL normalizers of core as used by entity resolution. SYNTHETIC values only; the
 * adjudicator is a deterministic fake.
 */
class MatchContractCrossModuleSuite extends munit.FunSuite {

  private def decision(o: ResolutionOutcome): MatchDecision =
    Resolver.toMatchDecision(o, ctx).fold(e => fail(s"no valid decision: $e"), identity)

  private lazy val (template, schema) =
    AdjudicationProtocol.loadDefault().fold(e => throw new AssertionError(e), identity)

  private def adjudicated(verdict: String): ResolutionOutcome = {
    val o = researcher(nameOnly).decide()
    val ev = if (verdict == "abstain") "[]" else s"""["${o.sourceIdA}"]"""
    val raw =
      s"""{"schemaVersion":"er-adjudication-response.v1","candidateId":"${o.candidateId}",""" +
        s""""decision":"$verdict","evidence":$ev,"reasonCodes":[]}"""
    val fake = new Adjudicator {
      def complete(modelId: String, prompt: String): Either[String, String] = Right(raw)
    }
    Adjudication
      .adjudicate(
        policy,
        o,
        EndpointEvidence(o.sourceIdA, "dblp", Vector("synthetic")),
        EndpointEvidence(o.sourceIdB, "dblp", Vector("synthetic")),
        "fake-model.te-p5-01",
        template,
        schema,
        fake,
        new InMemoryAdjudicationCache
      )
      .fold(e => fail(e.mkString), identity)
  }

  private lazy val produced: Vector[(String, ResolutionOutcome)] = Vector(
    "exact-id ACCEPTED" -> researcher(
      nameOnly,
      Vector(id(S.Orcid, orcid(1))),
      Vector(id(S.Orcid, orcid(1)))
    ).decide(),
    "exact-id CONFLICT" -> researcher(
      nameOnly,
      Vector(id(S.Orcid, orcid(1))),
      Vector(id(S.Orcid, orcid(2)))
    ).decide(),
    "exact-id REJECTED" -> work(nothingWork, Vector(id(S.Doi, doiA)), Vector(id(S.Doi, doiB)))
      .decide(),
    "agreement-contradicted CONFLICT" -> researcher(
      sameWork,
      Vector(id(S.DblpPid, pidA)),
      Vector(id(S.DblpPid, pidA))
    ).decide(),
    "deterministic REJECTED" -> researcher(sameWork).decide(),
    // er-policy.v4: an inferred work ACCEPTED needs an established same stage (here, same venue).
    "deterministic ACCEPTED" -> work(
      titleYearAuthors ++ Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0)
    ).decide(),
    "stage-blocked UNRESOLVED" -> work(
      titleYearAuthors,
      Vector(id(S.Arxiv, arxivA)),
      Vector(id(S.Doi, doiA))
    ).decide(),
    "arxiv cross-stage UNRESOLVED" -> work(
      nothingWork,
      Vector(id(S.Arxiv, arxivA)),
      Vector(id(S.Arxiv, arxivA), id(S.Doi, doiA))
    ).decide(),
    // er-policy.v5 (decisions-07): a DOI agreement on the preprint DOI alone decides only for two
    // pure preprints; here side B is a published record that also lists the preprint DOI.
    "preprint-doi cross-stage UNRESOLVED" -> work(
      nothingWork,
      Vector(id(S.Doi, preprintDoi)),
      Vector(id(S.Doi, doiA), id(S.Doi, preprintDoi))
    ).decide(),
    "probabilistic ACCEPTED" -> researcher(strongResearcher).decide(),
    "probabilistic REJECTED" -> researcher(nothing).decide(),
    "probabilistic UNRESOLVED (band)" -> researcher(nameOnly).decide(),
    "blocked UNRESOLVED" -> work(
      titleYearAuthors,
      Vector(id(S.DblpKey, dblpKeyA)),
      Vector(id(S.DblpKey, dblpKeyB))
    ).decide(),
    "cluster UNRESOLVED" -> work(
      titleYearAuthors,
      Vector(id(S.Mag, magA)),
      Vector(id(S.Mag, magA))
    ).decide(),
    "llm accept" -> adjudicated("accept"),
    "llm reject" -> adjudicated("reject"),
    "llm abstain" -> adjudicated("abstain")
  )

  test(
    "every Resolver outcome kind maps to a valid match-decision.v2 that round-trips byte-stably"
  ) {
    val expected = Map(
      "exact-id ACCEPTED" -> (MatchOutcome.Accepted, MatchDecisionPath.ExactId),
      "exact-id CONFLICT" -> (MatchOutcome.Conflict, MatchDecisionPath.ExactId),
      "exact-id REJECTED" -> (MatchOutcome.Rejected, MatchDecisionPath.ExactId),
      "agreement-contradicted CONFLICT" -> (MatchOutcome.Conflict, MatchDecisionPath.ExactId),
      "deterministic REJECTED" ->
        (MatchOutcome.Rejected, MatchDecisionPath.DeterministicBibliographic),
      "deterministic ACCEPTED" ->
        (MatchOutcome.Accepted, MatchDecisionPath.DeterministicBibliographic),
      "stage-blocked UNRESOLVED" ->
        (MatchOutcome.Unresolved, MatchDecisionPath.DeterministicBibliographic),
      "arxiv cross-stage UNRESOLVED" -> (MatchOutcome.Unresolved, MatchDecisionPath.ExactId),
      "preprint-doi cross-stage UNRESOLVED" -> (MatchOutcome.Unresolved, MatchDecisionPath.ExactId),
      "probabilistic ACCEPTED" -> (MatchOutcome.Accepted, MatchDecisionPath.Probabilistic),
      "probabilistic REJECTED" -> (MatchOutcome.Rejected, MatchDecisionPath.Probabilistic),
      "probabilistic UNRESOLVED (band)" ->
        (MatchOutcome.Unresolved, MatchDecisionPath.Probabilistic),
      "blocked UNRESOLVED" ->
        (MatchOutcome.Unresolved, MatchDecisionPath.DeterministicBibliographic),
      "cluster UNRESOLVED" -> (MatchOutcome.Unresolved, MatchDecisionPath.ExactId),
      "llm accept" -> (MatchOutcome.Accepted, MatchDecisionPath.LlmAdjudicated),
      "llm reject" -> (MatchOutcome.Rejected, MatchDecisionPath.LlmAdjudicated),
      "llm abstain" -> (MatchOutcome.Unresolved, MatchDecisionPath.LlmAdjudicated)
    )
    assertEquals(
      expected.keySet,
      produced.map(_._1).toSet,
      "every produced kind has an expectation"
    )
    produced.foreach { case (clue, o) =>
      val d = decision(o)
      assertEquals(d.schemaVersion, "scholarchain.match-decision.v2", clue)
      assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
      val json = Json.write(d)
      assertEquals(Json.decode[MatchDecision](json), Right(d), clue)
      assertEquals(Json.write(Json.decode[MatchDecision](json).toOption.get), json, clue)
      assertEquals(d.candidateId, o.candidateId, clue)
      assertEquals(d.provenance.sourceRecordId, o.candidateId, clue)
      assertEquals(d.provenance.policyVersion, Some(activePolicy), clue)
      assertEquals(d.matchPath.tag, o.path.tag, clue)
      assertEquals((d.decision, d.matchPath), expected(clue), clue)
    }
    val m = produced.toMap
    assert(
      m("stage-blocked UNRESOLVED").reasonCodes.contains("publication-stage-cross-stage"),
      s"${m("stage-blocked UNRESOLVED").reasonCodes}"
    )
    assert(
      m("arxiv cross-stage UNRESOLVED").reasonCodes.contains("arxiv-agreement-cross-stage"),
      s"${m("arxiv cross-stage UNRESOLVED").reasonCodes}"
    )
    val pdoi = m("preprint-doi cross-stage UNRESOLVED")
    assert(
      pdoi.reasonCodes.contains("preprint-doi-agreement-cross-stage") &&
        pdoi.reasonCodes.contains("publication-stage-uncertain") &&
        !pdoi.reasonCodes.contains("exact-id-agreement.doi"),
      s"${pdoi.reasonCodes}"
    )
    assert(
      pdoi.rulesFired.contains("exact-id.unresolved.preprint-doi-agreement-cross-stage"),
      s"${pdoi.rulesFired}"
    )
    assertEquals(
      decision(produced.toMap.apply("agreement-contradicted CONFLICT")).conflicts.map(_.kind),
      Vector(IdentifierConflictKind.ContradictedAgreement)
    )
  }

  private def mutate(d: MatchDecision)(f: ujson.Obj => Unit): String = {
    val o = ujson.read(Json.write(d)).obj
    val obj = ujson.Obj.from(o)
    f(obj)
    ujson.write(obj)
  }

  private def refused(json: String, clue: String)(using munit.Location): Vector[ValidationError] =
    Json.decode[MatchDecision](json) match {
      case Right(_) => fail(s"accepted an inconsistent decision: $clue")
      case Left(e) => e
    }

  test("inconsistent combinations of Resolver-produced decisions are refused on decode") {
    val m = produced.toMap
    val conflict = decision(m("exact-id CONFLICT"))
    val accepted = decision(m("exact-id ACCEPTED"))
    val prob = decision(m("probabilistic UNRESOLVED (band)"))
    val llm = decision(m("llm accept"))
    def set(d: MatchDecision, k: String, v: ujson.Value): String =
      mutate(d)(j => j.value.update(k, v))
    def setProv(d: MatchDecision, k: String, v: ujson.Value): String =
      mutate(d)(j => j.value("provenance").obj.update(k, v))
    refused(set(conflict, "conflicts", ujson.Arr()), "CONFLICT without conflicts")
    refused(
      set(accepted, "conflicts", ujson.read(upickle.default.write(conflict.conflicts))),
      "ACCEPTED with conflicts"
    )
    refused(
      set(prob, "adjudication", ujson.read(upickle.default.write(llm.adjudication.get))),
      "adjudication on the probabilistic path"
    )
    refused(set(llm, "adjudication", ujson.Null), "LLM path without adjudication")
    refused(set(llm, "decision", ujson.Str("CONFLICT")), "LLM path yielding CONFLICT")
    refused(set(llm, "decision", ujson.Str("REJECTED")), "verdict accept with outcome REJECTED")
    refused(set(prob, "score", ujson.Num(1.0000001)), "score above 1")
    refused(set(prob, "score", ujson.Num(-0.5)), "score below 0")
    refused(set(prob, "score", ujson.Null), "probabilistic without a score")
    refused(set(prob, "blockingVersion", ujson.Str("")), "blank blocking version")
    refused(set(prob, "featureVersion", ujson.Str(" ")), "blank feature version")
    refused(setProv(prob, "policyVersion", ujson.Null), "no policy version")
    refused(
      setProv(prob, "modelVersion", ujson.Str("fake-model")),
      "model provenance without an adjudication"
    )
    refused(set(prob, "conflictingTargetIds", ujson.Arr()), "the retired v1 key")
    refused(set(prob, "unknownKey", ujson.Num(1)), "an unknown key")
    refused(set(prob, "candidateId", ujson.Str("mcand:" + "0" * 64)), "candidate ID mismatch")
    refused(set(prob, "confidence", ujson.Num(1.5)), "confidence above 1")
  }

  test("v1 and unknown match-decision schema versions are refused before decoding") {
    val d = decision(produced.head._2)
    for (v <- Vector("scholarchain.match-decision.v1", "scholarchain.match-decision.v3")) {
      val e = refused(mutate(d)(j => j.value.update("schemaVersion", ujson.Str(v))), v)
      assert(e.exists(_.isInstanceOf[ValidationError.UnsupportedSchemaVersion]), s"$v: $e")
    }
    assert(SchemaVersions.retired("MatchDecision").contains("scholarchain.match-decision.v1"))
  }

  // ------------------------------------------------------------------ configuration

  /** The active policy and prompt versions (er-policy.v8, decisions-11), pinned literally. */
  private val activePolicy = "er-policy.v8"
  private val activePrompt = "er-adjudication-prompt.v2"

  private def er(accept: Double, reject: Double, lower: Double, upper: Double) =
    EntityResolutionConfig(
      activePolicy,
      accept,
      reject,
      lower,
      upper,
      activePrompt
    )

  test(
    "config band ordering reject < bandLower < bandUpper < accept is strict; withConfig refuses breaks"
  ) {
    val bad = Vector(
      "reject = lower" -> er(0.85, 0.4, 0.4, 0.7),
      "lower = upper" -> er(0.85, 0.2, 0.5, 0.5),
      "upper = accept" -> er(0.7, 0.2, 0.4, 0.7),
      "lower > upper" -> er(0.85, 0.2, 0.7, 0.4),
      "reject > accept" -> er(0.2, 0.85, 0.4, 0.7),
      "band below reject" -> er(0.85, 0.5, 0.3, 0.4),
      "band above accept" -> er(0.6, 0.2, 0.7, 0.8),
      "NaN" -> er(Double.NaN, 0.2, 0.4, 0.7),
      "above 1" -> er(1.0000001, 0.2, 0.4, 0.7),
      "infinite" -> er(0.85, 0.2, 0.4, Double.PositiveInfinity)
    )
    bad.foreach { case (clue, c) =>
      assert(EntityResolutionConfig.problems(c).nonEmpty, clue)
      assert(MatchPolicy.withConfig(policy, c).isLeft, clue)
    }
    // Positive control first, so the refusals below are caused by the version alone.
    assert(MatchPolicy.withConfig(policy, er(0.85, 0.2, 0.4, 0.7)).isRight)
    // Every retired version (v1-v7) and an unknown future version (v9) are refused.
    for (
      v <- Vector(
        "er-policy.v1",
        "er-policy.v2",
        "er-policy.v3",
        "er-policy.v4",
        "er-policy.v5",
        "er-policy.v6",
        "er-policy.v7",
        "er-policy.v9"
      )
    )
      assert(
        MatchPolicy.withConfig(policy, er(0.85, 0.2, 0.4, 0.7).copy(policyVersion = v)).isLeft,
        v
      )
    for (v <- Vector("er-adjudication-prompt.v1", "er-adjudication-prompt.v3"))
      assert(
        MatchPolicy.withConfig(policy, er(0.85, 0.2, 0.4, 0.7).copy(promptVersion = v)).isLeft,
        v
      )
    assert(MatchPolicy.withConfig(policy, null).isLeft)
    val tight = MatchPolicy.withConfig(policy, er(0.4, 0.1, 0.2, 0.3))
    assertEquals(
      tight.map(p => (p.thresholds, p.llmBand)),
      Right((Thresholds(0.4, 0.1), LlmBand(0.2, 0.3)))
    )
  }

  /**
   * A complete SYNTHETIC scholarchain.config.v2 document (no real account, bucket, or endpoint).
   */
  private def hocon(
      version: String = "scholarchain.config.v2",
      erPolicy: String = activePolicy,
      erPrompt: String = activePrompt
  ): String =
    s"""configSchemaVersion = "$version"
       |environment = synthetic
       |aws { region = "us-east-1", accountId = "000000000000" }
       |storage {
       |  rawBucket = "synthetic-raw-bucket", curatedBucket = "synthetic-curated-bucket"
       |  contextBucket = "synthetic-context-bucket", vectorBucket = "synthetic-vector-bucket"
       |  vectorIndex = synthetic-index, dynamoTablePrefix = synthetic
       |  neptuneEndpoint = "synthetic-neptune.invalid", openSearchEndpoint = null
       |}
       |models { llmModelId = "fake-model.te-p5-01", embeddingModelId = "fake-embed", embeddingDimension = 8 }
       |graph { windows = [{ startYear = 2000, endYear = 2020 }], policyVersion = synthetic-graph, identityVersion = v1 }
       |bridge { algorithm = synthetic, sampleSize = 10, randomSeed = 7, maxIterations = 5 }
       |community { algorithm = synthetic, maxIterations = 5 }
       |entityResolution {
       |  policyVersion = $erPolicy, acceptThreshold = 0.85, rejectThreshold = 0.2
       |  llmBandLower = 0.4, llmBandUpper = 0.7, promptVersion = $erPrompt
       |}
       |runtime { maxPathHops = 3, retrievalTopK = 5, defaultWindow = { startYear = 2000, endYear = 2020 } }
       |""".stripMargin

  test(
    "config loader: a valid v2 file feeds withConfig; env overrides that break the band are refused; v1 is refused"
  ) {
    val loaded = ConfigLoader.resolve(Vector("synthetic.conf" -> hocon()), Map.empty)
    val cfg = loaded.fold(e => fail(e.map(_.message).mkString("; ")), identity)
    assertEquals(
      (cfg.entityResolution.policyVersion, cfg.entityResolution.promptVersion),
      (activePolicy, activePrompt)
    )
    assert(MatchPolicy.withConfig(policy, cfg.entityResolution).isRight)
    // A file that loads but names a retired policy or prompt is refused by withConfig.
    for (
      (p, q) <- Vector(
        ("er-policy.v3", activePrompt),
        ("er-policy.v4", activePrompt),
        ("er-policy.v5", activePrompt),
        ("er-policy.v6", activePrompt),
        ("er-policy.v7", activePrompt),
        ("er-policy.v9", activePrompt),
        (activePolicy, "er-adjudication-prompt.v1")
      )
    ) {
      val old = ConfigLoader
        .resolve(Vector("old-er.conf" -> hocon(erPolicy = p, erPrompt = q)), Map.empty)
        .fold(e => fail(e.map(_.message).mkString("; ")), identity)
      assert(MatchPolicy.withConfig(policy, old.entityResolution).isLeft, s"$p / $q")
    }
    val env = "SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__LLM_BAND_UPPER"
    for (v <- Vector("0.9", "0.85", "0.3", "0.2")) {
      val r = ConfigLoader.resolve(Vector("synthetic.conf" -> hocon()), Map(env -> v))
      assert(
        r.left.exists(_.exists {
          case ConfigError.InvalidValue(k, _, _) => k.startsWith("entityResolution.")
          case _ => false
        }),
        s"band upper $v: $r"
      )
    }
    val v1 = ConfigLoader.resolve(Vector("old.conf" -> hocon("scholarchain.config.v1")), Map.empty)
    assert(v1.left.exists(_.exists(_.isInstanceOf[ConfigError.UnsupportedSchemaVersion])), s"$v1")
  }

  // ------------------------------------------------------------------ policy versioning

  private def resource(path: String): String = {
    val in = classOf[MatchPolicy].getResourceAsStream(path)
    assert(in != null, s"missing resource $path")
    try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally in.close()
  }

  test(
    "policy versioning: v8 and prompt v2 are the default; v1-v7 and prompt v1 are refused by file schema and by name; v9 is refused"
  ) {
    assertEquals(policy.policyVersion, activePolicy)
    assertEquals(policy.adjudication.promptVersion, activePrompt)
    assertEquals(policy.calibrated, false)
    assertEquals(
      MatchPolicy.DefaultResource,
      "/scholarchain/entityresolution/policy/er-policy.v8.json"
    )
    assertEquals(MatchPolicy.FileSchemaVersion, "er-policy-file.v8")
    val retiredResources = Vector(
      "v1" -> MatchPolicy.RetiredV1Resource,
      "v2" -> MatchPolicy.RetiredV2Resource,
      "v3" -> MatchPolicy.RetiredV3Resource,
      "v4" -> MatchPolicy.RetiredV4Resource,
      "v5" -> MatchPolicy.RetiredV5Resource,
      "v6" -> MatchPolicy.RetiredV6Resource,
      "v7" -> MatchPolicy.RetiredV7Resource
    )
    for ((n, r) <- retiredResources)
      assert(MatchPolicy.parse(resource(r)).isLeft, s"$n resource")
    val active = resource(MatchPolicy.DefaultResource)
    assertEquals(MatchPolicy.parse(active).map(_.policyVersion), Right(activePolicy))
    val retiredNames = (1 to 7).toVector.map(i => s"er-policy.v$i")
    val renamed =
      retiredNames.map(n =>
        n -> active.replace(s"\"policyVersion\": \"$activePolicy\"", s"\"policyVersion\": \"$n\"")
      )
    renamed.foreach { case (n, text) =>
      assert(text.contains(n), s"replacement did not apply for $n")
      assert(MatchPolicy.parse(text).isLeft, s"$n inside a v8 file")
    }
    for (old <- (1 to 7).toVector.map(i => s"er-policy-file.v$i") :+ "er-policy-file.v9") {
      val oldSchema = active.replace("\"er-policy-file.v8\"", "\"" + old + "\"")
      assert(oldSchema != active, s"schema replacement did not apply for $old")
      assert(MatchPolicy.parse(oldSchema).isLeft, old)
    }
    // An unknown future policy v9 parses only as a document; a run configured for the active
    // policy refuses it, and a v9 configuration is refused against the active policy.
    val v9 =
      active.replace(s"\"policyVersion\": \"$activePolicy\"", "\"policyVersion\": \"er-policy.v9\"")
    assert(v9 != active, "v9 replacement did not apply")
    MatchPolicy.parse(v9).foreach { p9 =>
      assert(MatchPolicy.withConfig(p9, er(0.85, 0.2, 0.4, 0.7)).isLeft, "v9 policy, v8 config")
    }
    assert(
      MatchPolicy
        .withConfig(policy, er(0.85, 0.2, 0.4, 0.7).copy(policyVersion = "er-policy.v9"))
        .isLeft,
      "v9 config"
    )
    // The retired resources are kept as history and are different documents from the default.
    for ((n, r) <- retiredResources)
      assertNotEquals(resource(r), active, s"the default is the $n file")
    // A v8 file that names the retired prompt v1 is refused.
    val promptV1 =
      active.replace(
        s"\"promptVersion\": \"$activePrompt\"",
        "\"promptVersion\": \"er-adjudication-prompt.v1\""
      )
    assert(promptV1 != active, "prompt replacement did not apply")
    assert(MatchPolicy.parse(promptV1).isLeft, "prompt v1 inside a v8 file")
    // The prompt templates: v2 loads and is the declared active version; v1 is refused.
    val (template, _) =
      AdjudicationProtocol.loadDefault().fold(e => fail(e), identity)
    assertEquals(template.version, activePrompt)
    assertEquals(AdjudicationProtocol.PromptVersion, activePrompt)
    val v1Prompt = resource(AdjudicationProtocol.RetiredV1PromptResource)
    assert(v1Prompt.startsWith("prompt-version: er-adjudication-prompt.v1"), "v1 header")
    assert(AdjudicationProtocol.parseTemplate(v1Prompt).isLeft, "v1 prompt template")
    val v2Prompt = resource(AdjudicationProtocol.PromptResource)
    val relabelled = v2Prompt.replaceFirst(activePrompt, "er-adjudication-prompt.v1")
    assert(relabelled != v2Prompt, "header replacement did not apply")
    assert(AdjudicationProtocol.parseTemplate(relabelled).isLeft, "v2 body declaring v1")
    val extraKey = active.replaceFirst("\\{", "{ \"injected\": 1,")
    assert(MatchPolicy.parse(extraKey).isLeft, "unknown keys are refused")
    val swappedBand =
      active.replace(
        "\"llmBand\": { \"lower\": 0.4, \"upper\": 0.7 }",
        "\"llmBand\": { \"lower\": 0.7, \"upper\": 0.4 }"
      )
    assert(swappedBand != active, "band replacement did not apply")
    assert(MatchPolicy.parse(swappedBand).isLeft)
    val calibrationWording =
      active.replace("policy confidence constant (uncalibrated)", "estimated probability")
    assert(MatchPolicy.parse(calibrationWording).isLeft, "P-4 wording is required")
  }

  // ------------------------------------------------------------------ Semantic Scholar

  test("Semantic Scholar normalizers: canonical forms, refused look-alikes, and distinct schemes") {
    val hex = "0123456789abcdef0123456789abcdef01234567"
    val n = Identifiers.normalizeExact
    assertEquals(n(S.SemanticScholarCorpus, "CorpusId:215416146"), Right("215416146"))
    assertEquals(n(S.SemanticScholarCorpus, "CORPUSID:215416146"), Right("215416146"))
    assertEquals(n(S.SemanticScholarCorpus, "215416146"), Right("215416146"))
    Vector("0215416146", "1" * 19, "", "CorpusId:", "CorpusId: 1", "-1", "１２３").foreach(v =>
      assert(n(S.SemanticScholarCorpus, v).isLeft, v)
    )
    assertEquals(
      n(
        S.SemanticScholarPaper,
        s"https://www.semanticscholar.org/paper/Synthetic-Title/${hex.toUpperCase}"
      ),
      Right(hex)
    )
    assertEquals(
      n(S.SemanticScholarPaper, s"https://semanticscholar.org/paper/$hex?utm=x"),
      Right(hex)
    )
    Vector(
      hex.dropRight(1),
      hex + "0",
      s"https://api.semanticscholar.org/paper/$hex",
      s"https://www.semanticscholar.org/author/Synthetic/$hex",
      s"https://www.semanticscholar.org.evil.example/paper/$hex"
    ).foreach(v => assert(n(S.SemanticScholarPaper, v).isLeft, v))
    assertEquals(
      n(S.SemanticScholarAuthor, "https://www.semanticscholar.org/author/Synthetic-Name/12345"),
      Right("12345")
    )
    assert(n(S.SemanticScholarAuthor, s"https://www.semanticscholar.org/paper/x/12345").isLeft)
    assertEquals(S.SemanticScholarAuthor.entityKind, MatchEntityKind.Researcher)
    assertEquals(S.SemanticScholarCorpus.entityKind, MatchEntityKind.Work)
    // entity resolution trims surrounding whitespace before delegating to core, never relaxing it.
    assertEquals(ExactIdentifiers.normalize(S.SemanticScholarCorpus, " CorpusId:42 "), Right("42"))
    assert(ExactIdentifiers.normalize(S.SemanticScholarCorpus, "CorpusId:042").isLeft)
  }

  // ------------------------------------------------------------------ repository URL

  test(
    "repository URLs: gitlab subgroups never merge; scp and https forms agree; only github folds case"
  ) {
    def norm(s: String) = ExactIdentifiers.normalize(S.RepositoryUrl, s)
    val a = norm("https://gitlab.com/group/sub/repoA")
    val b = norm("https://gitlab.com/group/sub/repoB")
    assertEquals(a, Right("https://gitlab.com/group/sub/repoA"))
    assertEquals(b, Right("https://gitlab.com/group/sub/repoB"))
    assertEquals(
      ExactIdentifiers.compare(
        S.RepositoryUrl,
        Vector("https://gitlab.com/group/sub/repoA"),
        Vector("https://gitlab.com/group/sub/repoB")
      ),
      IdAgreement.Conflict
    )
    assertNotEquals(norm("https://gitlab.com/group/sub"), a)
    assertEquals(norm("git@gitlab.com:group/sub/repoA.git"), a)
    assertEquals(norm("git+ssh://git@gitlab.com/group/sub/repoA.git/"), a)
    assertEquals(norm("http://www.gitlab.com:80/group/sub/repoA#readme"), a)
    assertEquals(
      norm("https://github.com/Synthetic-Owner/Repo.git"),
      Right("https://github.com/synthetic-owner/repo")
    )
    assert(norm("https://github.com/owner/repo/tree/main").isLeft, "github deep links are refused")
    assertNotEquals(norm("https://gitlab.com/Group/Repo"), norm("https://gitlab.com/group/repo"))
    Vector(
      "https://gitlab.com:8443/group/repo",
      "http://gitlab.com:443/group/repo",
      "https://gitlab.com/group/re%70o",
      "https://gitlab.com/group/../repo",
      "https://gitlab.com/group",
      "ftp://gitlab.com/group/repo",
      "https://localhost/group/repo"
    ).foreach(v => assert(norm(v).isLeft, v))
  }

  test(
    "repository URLs: two repositories named only by a query parameter never share a canonical ID"
  ) {
    // SYNTHETIC gitweb-style URLs: the repository is named by the `p` query parameter, so dropping
    // the query would merge two different repositories into one identifier. The documented rule
    // "never guesses ... fails to match rather than risking a merge" requires refusing or keeping
    // them distinct.
    val alpha =
      Identifiers.normalizeRepositoryUrl("https://git.example.org/gitweb/index.cgi?p=alpha.git")
    val beta =
      Identifiers.normalizeRepositoryUrl("https://git.example.org/gitweb/index.cgi?p=beta.git")
    assert(
      alpha.isLeft || beta.isLeft || alpha != beta,
      s"two distinct repositories normalized to one identifier: $alpha and $beta"
    )
  }

  private val secret = "S3cr3tT0kenSynthetic"

  private def leaks(e: ValidationError): Boolean =
    e.toString.contains(secret) || e.message.contains(secret)

  test(
    "repository URLs: rejected values never echo credentials in validation errors (RV-P5-04 m10)"
  ) {
    val raws = Vector(
      s"https://deploy:$secret@gitlab.example.org:8443/group/repo",
      s"https://deploy:$secret@gitlab.example.org/onlyone",
      s"git+ssh://git:$secret@gitlab.example.org/group/re%70o",
      s" https://deploy:$secret@gitlab.example.org/group/repo",
      s"\thttps://deploy:$secret@gitlab.example.org/group/repo",
      s"https://deploy:$secret/x@gitlab.example.org/group/repo"
    )
    raws.foreach { raw =>
      val viaOf = ExactId.of(S.RepositoryUrl, raw)
      assert(viaOf.isLeft, raw)
      viaOf.left.foreach(e => assert(!leaks(e), s"ExactId.of echoed the credential: $e"))
      val viaCheck = ExactId.check("identifiers[0]", ExactId(S.RepositoryUrl, raw))
      assert(viaCheck.nonEmpty, raw)
      viaCheck.foreach(e => assert(!leaks(e), s"ExactId.check echoed the credential: $e"))
      ExactIdentifiers
        .normalize(S.RepositoryUrl, raw)
        .left
        .foreach(why => assert(!why.contains(secret), s"entity resolution echoed it: $why"))
    }
  }
}
