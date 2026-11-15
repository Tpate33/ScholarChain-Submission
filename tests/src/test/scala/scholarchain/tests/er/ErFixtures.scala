package scholarchain.tests.er

import scala.collection.immutable.SortedMap

import scholarchain.core.*
import scholarchain.core.config.{ConfigError, ConfigLoader, ScholarChainConfig}
import scholarchain.entityresolution.{BlockingKeys, MatchFeatures}
import scholarchain.entityresolution.policy.*

/**
 * SYNTHETIC FIXTURES for the TE-P5-01 adversarial entity-resolution suites. Every identifier, name,
 * and feature value here is invented for testing (ORCID iDs are built with the ISO 7064 check
 * character from all-zero base digits, DOIs use the unregistered `10.9999` prefix). Nothing here is
 * a real person, a real record, or a gold label.
 *
 * Candidates are built with hand-set feature values; their `id.*` features and exact-identifier
 * agreements are derived from the endpoint identifier sets exactly as er-features.v2 defines them,
 * so `Resolver.decide` accepts them as consistent.
 */
object ErFixtures {

  val policy: MatchPolicy =
    MatchPolicy.loadDefault().fold(e => throw new AssertionError(e.mkString("; ")), identity)

  val candidateRunId: String = "run:" + "a" * 64
  val decisionRunId: String = "run:" + "b" * 64
  val ctx: DecisionContext = DecisionContext(decisionRunId, "2026-11-06T12:00:00Z")

  val candidateProvenance: Provenance = Provenance(
    SourceType.Derived,
    "synthetic-te-p5-01",
    "synthetic-te-p5-01",
    candidateRunId,
    SchemaVersions.CandidateBlock,
    Some("hadoop-transform.v2"),
    None,
    None
  )

  def ok[A](e: Either[?, A]): A =
    e.fold(err => throw new AssertionError(s"synthetic fixture is invalid: $err"), identity)

  /** A checksum-valid SYNTHETIC ORCID iD with base digits 0000-0000-0000-NNN. */
  def orcid(n: Int): String = {
    val base = f"000000000000$n%03d"
    s"0000-0000-0000-${base.takeRight(3)}${Identifiers.orcidCheckChar(base)}"
  }

  def id(s: ExactIdScheme, v: String): ExactId = ExactId(s, v)

  // SYNTHETIC identifier values
  val doiA = "10.9999/synthetic.te.a"
  val doiB = "10.9999/synthetic.te.b"
  val preprintDoi = "10.48550/arxiv.2101.00001"
  val arxivA = "2101.00001"
  val arxivB = "2101.00002"
  val dblpKeyA = "journals/synthetic/TeA21"
  val dblpKeyB = "journals/synthetic/TeB21"
  val scopusA = "2-s2.0-90000000001"
  val scopusB = "2-s2.0-90000000002"
  val oaWorkA = "W9000000001"
  val oaWorkB = "W9000000002"
  val magA = "9000000001"
  val magB = "9000000002"
  val s2CorpusA = "900000001"
  val s2CorpusB = "900000002"
  val s2PaperA = "a" * 40
  val s2PaperB = "b" * 40
  val repoA = "https://gitlab.example.org/synthetic/repo-a"
  val repoB = "https://gitlab.example.org/synthetic/repo-b"
  val pidA = "00/9001"
  val pidB = "00/9002"
  val oaAuthorA = "A9000000001"
  val oaAuthorB = "A9000000002"
  val s2AuthorA = "900000011"
  val s2AuthorB = "900000012"

  private def zeros(names: Vector[String]): SortedMap[String, Double] =
    SortedMap.from(names.map(_ -> 0.0))

  private def raw(ids: Vector[ExactId], s: ExactIdScheme): Set[String] =
    ids.filter(_.scheme == s).map(_.value).toSet

  private def cmp(a: Vector[ExactId], b: Vector[ExactId], s: ExactIdScheme) = {
    val x = raw(a, s)
    val y = raw(b, s)
    val both = x.nonEmpty && y.nonEmpty
    val agree = both && x.exists(y.contains)
    (both, agree, both && !agree)
  }

  private def b01(x: Boolean): Double = if (x) 1.0 else 0.0

  private def flags(prefix: String, t: (Boolean, Boolean, Boolean)): Seq[(String, Double)] =
    Seq(
      s"id.$prefix.both" -> b01(t._1),
      s"id.$prefix.agree" -> b01(t._2),
      s"id.$prefix.conflict" -> b01(t._3)
    )

  /** The er-features.v2 `id.*` features implied by two endpoint identifier sets. */
  def idFeatures(kind: MatchEntityKind, a: Vector[ExactId], b: Vector[ExactId]) =
    kind match {
      case MatchEntityKind.Researcher =>
        MatchFeatures.ResearcherIdSchemes.flatMap(s => flags(s.tag, cmp(a, b, s)))
      case _ =>
        val others = MatchFeatures.OtherWorkIdSchemes.map(cmp(a, b, _))
        flags("doi", cmp(a, b, ExactIdScheme.Doi)) ++
          flags("arxiv", cmp(a, b, ExactIdScheme.Arxiv)) ++ Seq(
            "id.other.agree" -> others.count(_._2).toDouble,
            "id.other.conflict" -> others.count(_._3).toDouble
          )
    }

  /** A synthetic candidate with the endpoint identifiers it was built from. */
  final case class Built(candidate: MatchCandidate, ids: EndpointIds) {
    def decide(p: MatchPolicy = policy): ResolutionOutcome =
      Resolver
        .decide(p, candidate, ids)
        .fold(e => throw new AssertionError(e.mkString("; ")), x => x)
  }

  val mentionA: String = ok(MentionId.of("work:dblp:journals/synthetic/TeMentionA21", 0))
  val mentionB: String = ok(MentionId.of("work:dblp:journals/synthetic/TeMentionB21", 1))

  /** A researcher candidate (two DBLP mentions) with hand-set non-identifier features. */
  def researcher(
      kv: Seq[(String, Double)],
      idsA: Vector[ExactId] = Vector.empty,
      idsB: Vector[ExactId] = Vector.empty,
      blockingVersion: String = BlockingKeys.Version,
      featureVersion: String = MatchFeatures.Version,
      runId: String = candidateRunId
  ): Built = {
    val a = ExactId.canonical(idsA)
    val b = ExactId.canonical(idsB)
    val c = ok(
      MatchCandidate.build(
        MatchEntityKind.Researcher,
        mentionA,
        SourceType.Dblp,
        mentionB,
        SourceType.Dblp,
        Vector(BlockKey(0, "synthetic|s")),
        blockingVersion,
        featureVersion,
        zeros(MatchFeatures.ResearcherFeatureNames) ++ kv ++
          idFeatures(MatchEntityKind.Researcher, a, b),
        MatchFeatures.exactIdAgreements(a, b),
        candidateProvenance.copy(runId = runId)
      )
    )
    val byId = Map(mentionA -> a, mentionB -> b)
    Built(c, EndpointIds(byId(c.sourceIdA), byId(c.sourceIdB)))
  }

  val workA: String = "work:dblp:journals/synthetic/TeWorkA21"
  val workB: String = "work:doi:10.9999/synthetic.te.endpoint"

  /**
   * A work candidate (DBLP work vs Crossref work) with hand-set features. `dropFeatures` removes
   * non-identifier feature keys entirely (missing inputs).
   */
  def work(
      kv: Seq[(String, Double)],
      idsA: Vector[ExactId] = Vector.empty,
      idsB: Vector[ExactId] = Vector.empty,
      dropFeatures: Set[String] = Set.empty
  ): Built = {
    val a = ExactId.canonical(idsA)
    val b = ExactId.canonical(idsB)
    val base = (zeros(MatchFeatures.WorkFeatureNames) -- dropFeatures) ++ kv
    val c = ok(
      MatchCandidate.build(
        MatchEntityKind.Work,
        workA,
        SourceType.Dblp,
        workB,
        SourceType.Crossref,
        Vector(BlockKey(0, "synthetic_title")),
        BlockingKeys.Version,
        MatchFeatures.Version,
        base ++ idFeatures(MatchEntityKind.Work, a, b),
        MatchFeatures.exactIdAgreements(a, b),
        candidateProvenance
      )
    )
    val byId = Map(workA -> a, workB -> b)
    Built(c, EndpointIds(byId(c.sourceIdA), byId(c.sourceIdB)))
  }

  // ------------------------------------------------------------------ feature profiles (SYNTHETIC)

  /** Perfect name agreement and nothing else: the score lands inside the LLM band. */
  val nameOnly: Seq[(String, Double)] = Seq(
    "name.family_exact" -> 1.0,
    "name.family_key_agree" -> 1.0,
    "name.family_sim" -> 1.0,
    "name.given_both" -> 1.0,
    "name.first_initial_agree" -> 1.0,
    "name.initials_compatible" -> 1.0,
    "name.first_given_agree" -> 1.0,
    "name.given_sim" -> 1.0,
    "name.token_jaccard" -> 1.0,
    "name.best_variant_sim" -> 1.0
  )

  /** Name plus three shared coauthors: the score is above the accept threshold. */
  val strongResearcher: Seq[(String, Double)] =
    nameOnly ++ Seq("coauthor.both" -> 1.0, "coauthor.shared" -> 3.0, "coauthor.jaccard" -> 0.5)

  /** Same DOI, same position, same team size, same family key, compatible initials. */
  val samePaperSamePosition: Seq[(String, Double)] =
    nameOnly ++ Seq(
      "work.doi_agree" -> 1.0,
      "work.position_agree" -> 1.0,
      "work.team_size_agree" -> 1.0
    )

  /** Nothing agrees: the score is below the reject threshold, and the pair is not plausible. */
  val nothing: Seq[(String, Double)] = Seq.empty

  /** Two positions on one work (deterministic non-identity). */
  val sameWork: Seq[(String, Double)] = nameOnly :+ ("context.same_work" -> 1.0)

  /** Different DBLP homonym numbers (deterministic non-identity). */
  val homonymDiffers: Seq[(String, Double)] =
    nameOnly ++ Seq("name.homonym_suffix_both" -> 1.0, "name.homonym_suffix_agree" -> 0.0)

  val researcherProfiles: Vector[(String, Seq[(String, Double)])] = Vector(
    "name-only" -> nameOnly,
    "strong" -> strongResearcher,
    "same-paper-same-position" -> samePaperSamePosition,
    "nothing" -> nothing,
    "same-work" -> sameWork,
    "homonym-differs" -> homonymDiffers
  )

  /** Folded title, year and author sequence equal: the deterministic title-year-authors rule. */
  val titleYearAuthors: Seq[(String, Double)] = Seq(
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "title.token_jaccard" -> 1.0,
    "year.both" -> 1.0,
    "year.abs_diff" -> 0.0,
    "authors.both" -> 1.0,
    "authors.sequence_exact" -> 1.0,
    "authors.sequence_lcs_ratio" -> 1.0,
    "authors.family_jaccard" -> 1.0,
    "authors.first_family_agree" -> 1.0
  )

  /** High score without the deterministic rule (author sequences differ). */
  val strongWork: Seq[(String, Double)] = Seq(
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "title.token_jaccard" -> 1.0,
    "authors.both" -> 1.0,
    "authors.family_jaccard" -> 1.0,
    "authors.first_family_agree" -> 1.0,
    "authors.sequence_lcs_ratio" -> 0.8
  )

  /** A near-identical title only: the score lands inside the LLM band; the pair is plausible. */
  val bandWork: Seq[(String, Double)] = Seq("title.sim" -> 0.9, "title.token_jaccard" -> 0.9)

  /** Nothing agrees: REJECTED by score, not plausible. */
  val nothingWork: Seq[(String, Double)] = Seq.empty

  val workProfiles: Vector[(String, Seq[(String, Double)])] = Vector(
    "title-year-authors" -> titleYearAuthors,
    "strong" -> strongWork,
    "band" -> bandWork,
    "nothing" -> nothingWork
  )

  // ------------------------------------------------------------------ configuration (SYNTHETIC)

  /** The SYNTHETIC adjudication model ID of the test configuration (no model is ever called). */
  val syntheticErModelId: String = "fake-model.te-p5-04"

  /**
   * A complete SYNTHETIC `scholarchain.config.v2` document (no real account, bucket, endpoint, or
   * model). The defaults name the default policy's version and prompt and its file thresholds.
   */
  def syntheticErConfigText(
      policyVersion: String = policy.policyVersion,
      accept: Double = 0.85,
      reject: Double = 0.2,
      lower: Double = 0.4,
      upper: Double = 0.7,
      promptVersion: String = policy.adjudication.promptVersion
  ): String =
    s"""configSchemaVersion = "scholarchain.config.v2"
       |environment = synthetic
       |aws { region = "us-east-1", accountId = "000000000000" }
       |storage {
       |  rawBucket = "synthetic-raw-bucket", curatedBucket = "synthetic-curated-bucket"
       |  contextBucket = "synthetic-context-bucket", vectorBucket = "synthetic-vector-bucket"
       |  vectorIndex = synthetic-index, dynamoTablePrefix = synthetic
       |  neptuneEndpoint = "synthetic-neptune.invalid", openSearchEndpoint = null
       |}
       |models { llmModelId = "$syntheticErModelId", embeddingModelId = "fake-embed", embeddingDimension = 8 }
       |graph { windows = [{ startYear = 2000, endYear = 2020 }], policyVersion = synthetic-graph, identityVersion = v1 }
       |bridge { algorithm = synthetic, sampleSize = 10, randomSeed = 7, maxIterations = 5 }
       |community { algorithm = synthetic, maxIterations = 5 }
       |entityResolution {
       |  policyVersion = $policyVersion, acceptThreshold = $accept, rejectThreshold = $reject
       |  llmBandLower = $lower, llmBandUpper = $upper, promptVersion = $promptVersion
       |}
       |runtime { maxPathHops = 3, retrievalTopK = 5, defaultWindow = { startYear = 2000, endYear = 2020 } }
       |""".stripMargin

  /** A configuration source that resolves [[syntheticErConfigText]] (no environment, no files). */
  def syntheticErConfig(
      text: String = syntheticErConfigText()
  ): () => Either[Vector[ConfigError], ScholarChainConfig] =
    () => ConfigLoader.resolve(Vector("synthetic-te.conf" -> text), Map.empty)

  /** A configuration source with no configuration at all (the files variable is unset). */
  val missingErConfig: () => Either[Vector[ConfigError], ScholarChainConfig] =
    () => ConfigLoader.load(Map.empty, _ => Left("unused: no file list"))

  /** Every clause of the publication-stage corroboration holds (same venue, year, title). */
  val corroborating: Seq[(String, Double)] = Seq(
    "venue.id_both" -> 1.0,
    "venue.id_agree" -> 1.0,
    "venue.name_both" -> 1.0,
    "venue.name_jaccard" -> 1.0,
    "year.both" -> 1.0,
    "year.abs_diff" -> 0.0,
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "title.token_jaccard" -> 1.0
  )
}
