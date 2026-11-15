package scholarchain.entityresolution.policy

import scholarchain.core.config.{EntityResolutionConfig, ModelConfig}
import scholarchain.entityresolution.{BlockingKeys, MatchFeatures}

import PolicyFixtures.policy

class MatchPolicySuite extends munit.FunSuite {

  private val defaultJson: String =
    MatchPolicy.resourceText(MatchPolicy.DefaultResource).fold(e => fail(e), identity)

  test(
    "the default policy is versioned, uncalibrated, and bound to er-blocking.v2/er-features.v2"
  ) {
    assertEquals(policy.policyVersion, "er-policy.v8")
    assertEquals(policy.schemaVersion, MatchPolicy.FileSchemaVersion)
    assertEquals(policy.blockingVersion, BlockingKeys.Version)
    assertEquals(policy.featureVersion, MatchFeatures.Version)
    assertEquals(policy.blockingVersion, "er-blocking.v2")
    assertEquals(policy.featureVersion, "er-features.v2")
    assert(
      !policy.calibrated,
      "no gold labels exist, so the policy must declare itself uncalibrated"
    )
    assertEquals(MatchPolicy.validate(policy), Vector.empty)
  }

  test("separate accept and reject thresholds with the LLM band strictly inside the gap") {
    val t = policy.thresholds
    val b = policy.llmBand
    assert(t.reject < b.lower && b.lower <= b.upper && b.upper < t.accept)
  }

  test("the policy file holds no model ID, only the configuration key that supplies it") {
    assertEquals(policy.adjudication.modelIdConfigKey, "models.llmModelId")
    assert(!defaultJson.contains("anthropic") && !defaultJson.contains("bedrock"))
    assert(!defaultJson.contains("\"modelId\""))
  }

  private def problems(f: MatchPolicy => MatchPolicy): Vector[String] =
    MatchPolicy.validate(f(policy))

  test("validation refuses inverted thresholds and a band outside the unresolved interval") {
    assert(problems(_.copy(thresholds = Thresholds(0.3, 0.6))).exists(_.contains("reject must be")))
    assert(problems(_.copy(llmBand = LlmBand(0.1, 0.5))).exists(_.contains("strictly inside")))
    assert(problems(_.copy(llmBand = LlmBand(0.5, 0.9))).exists(_.contains("strictly inside")))
    assert(problems(_.copy(llmBand = LlmBand(0.6, 0.5))).exists(_.contains("must be below")))
    assert(problems(_.copy(llmBand = LlmBand(0.5, 0.5))).exists(_.contains("must be below")))
  }

  test("thresholds and band are unchanged from er-policy.v1 (no label informed them)") {
    assertEquals(policy.thresholds, Thresholds(0.85, 0.2))
    assertEquals(policy.llmBand, LlmBand(0.4, 0.7))
    assertEquals(policy.deterministicConfidence, 0.95)
    assertEquals(policy.conflictConfidence, 0.99)
  }

  test("the retired er-policy.v1 file is refused by name") {
    val v1 = MatchPolicy.resourceText(MatchPolicy.RetiredV1Resource).fold(e => fail(e), identity)
    assert(MatchPolicy.parse(v1).left.exists(_.exists(_.contains("retired"))))
    assert(!defaultJson.contains("er-policy-file.v1"))
  }

  test("the policy file documents the plausibility definitions and every identifier rule") {
    Vector(
      policy.researcherPlausibility.definition,
      policy.researcherPlausibility.rationale,
      policy.workPlausibility.definition,
      policy.workPlausibility.rationale
    ).foreach(d => assert(d.length > 40, d))
    assert(policy.researcherPlausibility.definition.contains("familySimMin"))
    assert(policy.workPlausibility.definition.contains("authorSequenceLcsMin"))
    (policy.researcher.identifierRules ++ policy.work.identifierRules).foreach { r =>
      assert(r.scope.nonEmpty && r.reliability.nonEmpty, r.scheme)
    }
    val blank = policy.researcher.identifierRules.map(_.copy(reliability = " "))
    assert(
      problems(_.copy(researcher = policy.researcher.copy(identifierRules = blank)))
        .exists(_.contains("must be documented"))
    )
  }

  test("validation refuses other key or feature versions (v1 and v2 outputs never mix)") {
    assert(problems(_.copy(featureVersion = "er-features.v1")).exists(_.contains("featureVersion")))
    assert(
      problems(_.copy(blockingVersion = "er-blocking.v1")).exists(_.contains("blockingVersion"))
    )
  }

  test("validation refuses unknown features, caps without weights, and foreign schemes") {
    val r = policy.researcher
    assert(
      problems(_.copy(researcher = r.copy(weights = r.weights + ("name.nonexistent" -> 1.0))))
        .exists(_.contains("unknown feature"))
    )
    assert(
      problems(_.copy(researcher = r.copy(caps = r.caps + ("name.family_sim" -> 2.0))))
        .exists(_.contains("has no weight"))
    )
    val doiRule = policy.work.rule(scholarchain.core.ExactIdScheme.Doi).get
    assert(
      problems(_.copy(researcher = r.copy(identifierRules = r.identifierRules :+ doiRule)))
        .exists(_.contains("not a researcher scheme"))
    )
    val badRule = r.identifierRules.head.copy(onDisagreement = "merge")
    assert(
      problems(_.copy(researcher = r.copy(identifierRules = badRule +: r.identifierRules.tail)))
        .exists(_.contains("onDisagreement must be one of"))
    )
    val evidenceWithConfidence =
      policy.work.identifierRules.map(x =>
        if (x.scheme == "repository-url") x.copy(agreementConfidence = 0.5) else x
      )
    assert(
      problems(_.copy(work = policy.work.copy(identifierRules = evidenceWithConfidence)))
        .exists(_.contains("agreementConfidence must be 0"))
    )
    assert(
      problems(_.copy(adjudication = policy.adjudication.copy(modelIdConfigKey = "x")))
        .exists(_.contains("modelIdConfigKey"))
    )
  }

  test("parse refuses unknown keys in the policy file") {
    val withExtra = defaultJson.replaceFirst("\\{", "{\"surprise\": 1,")
    assert(MatchPolicy.parse(withExtra).isLeft)
    assert(MatchPolicy.parse(defaultJson).isRight)
  }

  private val prompt = "er-adjudication-prompt.v2"

  private def cfg(
      accept: Double,
      reject: Double,
      lower: Double = 0.4,
      upper: Double = 0.7,
      policyVersion: String = "er-policy.v8",
      promptVersion: String = prompt
  ) = EntityResolutionConfig(policyVersion, accept, reject, lower, upper, promptVersion)

  test("core configuration overrides thresholds and the LLM band; versions must match") {
    val q =
      MatchPolicy.withConfig(policy, cfg(0.9, 0.1, 0.3, 0.6)).fold(e => fail(e.mkString), identity)
    assertEquals(q.thresholds, Thresholds(0.9, 0.1))
    assertEquals(q.llmBand, LlmBand(0.3, 0.6))
    assert(
      MatchPolicy
        .withConfig(policy, cfg(0.9, 0.1, policyVersion = "er-policy.v1"))
        .left
        .exists(_.exists(_.contains("differs from the policy")))
    )
  }

  test("a configured prompt version other than the policy's refuses the run") {
    val r =
      MatchPolicy.withConfig(policy, cfg(0.9, 0.1, promptVersion = "er-adjudication-prompt.v1"))
    assert(r.left.exists(_.exists(_.contains("promptVersion"))), r.toString)
    assert(r.left.exists(_.exists(_.contains("refused"))))
    // The configured v3 policy name is retired as well: a v3 configuration never runs.
    assert(
      MatchPolicy
        .withConfig(policy, cfg(0.9, 0.1, policyVersion = "er-policy.v3"))
        .left
        .exists(_.exists(_.contains("differs from the policy")))
    )
  }

  test("er-policy.v3 is retired history: its file and its name are refused, like v1 and v2") {
    val v3 = MatchPolicy.resourceText(MatchPolicy.RetiredV3Resource).fold(e => fail(e), identity)
    assert(v3.contains("\"er-policy.v3\""), "the v3 history file is kept")
    val refused = MatchPolicy.parse(v3)
    assert(refused.left.exists(_.exists(_.contains("retired"))), refused.toString)
    assert(
      MatchPolicy
        .validate(policy.copy(policyVersion = "er-policy.v3"))
        .exists(_.contains("retired"))
    )
    val renamed = defaultJson.replace("\"er-policy-file.v8\"", "\"er-policy-file.v3\"")
    assert(renamed != defaultJson, "replacement did not apply")
    assert(MatchPolicy.parse(renamed).left.exists(_.exists(_.contains("retired"))))
  }

  test("a policy that names the retired adjudication prompt v1 is refused") {
    assertEquals(policy.adjudication.promptVersion, "er-adjudication-prompt.v2")
    val old = policy.copy(adjudication =
      policy.adjudication.copy(promptVersion = "er-adjudication-prompt.v1")
    )
    assert(
      MatchPolicy.validate(old).exists(_.contains("retired")),
      MatchPolicy.validate(old).toString
    )
  }

  test("the publicationStage block documents every stage verdict; a gap is refused") {
    StageVerdict.values.foreach(v =>
      assert(policy.publicationStage.definition.contains(v.tag), v.tag)
    )
    assert(policy.publicationStage.rationale.contains("preprint"))
    val gap = policy.copy(publicationStage =
      policy.publicationStage.copy(definition =
        policy.publicationStage.definition.replace("same-stage-venue", "same-venue")
      )
    )
    assert(
      MatchPolicy.validate(gap).exists(_.contains("must name the verdict 'same-stage-venue'")),
      MatchPolicy.validate(gap).toString
    )
    assert(
      MatchPolicy
        .validate(policy.copy(publicationStage = PublicationStagePolicy(" ", "x")))
        .exists(_.contains("publicationStage definition and rationale required"))
    )
  }

  test("configuration is validated through EntityResolutionConfig.problems") {
    // The band must lie strictly inside (reject, accept): core rules, reported by key.
    val outside = MatchPolicy.withConfig(policy, cfg(0.6, 0.1, 0.4, 0.7))
    assert(
      outside.left.exists(_.exists(_.startsWith("entityResolution.llmBandUpper"))),
      outside.toString
    )
    val nan = MatchPolicy.withConfig(policy, cfg(Double.NaN, 0.1))
    assert(nan.left.exists(_.exists(_.startsWith("entityResolution.acceptThreshold"))))
    val badToken = MatchPolicy.withConfig(policy, cfg(0.9, 0.1, promptVersion = "has space"))
    assert(badToken.left.exists(_.exists(_.startsWith("entityResolution.promptVersion"))))
    assert(MatchPolicy.withConfig(policy, null).isLeft)
  }

  test("the adjudication model ID comes only from models.llmModelId") {
    assertEquals(
      MatchPolicy.modelId(ModelConfig("fake-model.test-1", "emb", 8)),
      Right("fake-model.test-1")
    )
    assert(MatchPolicy.modelId(ModelConfig("", "emb", 8)).isLeft)
    assert(MatchPolicy.modelId(ModelConfig(" padded", "emb", 8)).isLeft)
  }
}
