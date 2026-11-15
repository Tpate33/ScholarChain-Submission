package scholarchain.entityresolution.policy

import java.nio.file.{Files, Path}

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.SortedMap

import scholarchain.core.*
import scholarchain.entityresolution.{
  BlockingKeys,
  EntityResolution,
  MatchFeatures,
  ResearcherEndpoint,
  Samples
}

/**
 * SYNTHETIC TEST FIXTURES. Candidates are built either from the sample-mirroring records of
 * [[Samples]] with the real v2 feature functions, or from hand-set feature values. None of this is
 * gold data, and no fixture here is a human label.
 */
object PolicyFixtures {
  val policy: MatchPolicy =
    MatchPolicy.loadDefault().fold(e => throw new AssertionError(e.mkString("; ")), identity)

  val candidateRunId: String = "run:" + "1" * 64
  val decisionRunId: String = "run:" + "2" * 64
  val ctx: DecisionContext = DecisionContext(decisionRunId, "2026-11-04T12:00:00Z")

  val candidateProvenance: Provenance = Provenance(
    SourceType.Derived,
    "fixture",
    "dblp-2026-10-03",
    candidateRunId,
    SchemaVersions.CandidateBlock,
    Some("hadoop-transform.v2"),
    None,
    None
  )

  def ok[A](e: Either[?, A]): A =
    e.fold(err => throw new AssertionError(s"fixture is invalid: $err"), identity)

  /**
   * Endpoint identifiers of every fixture candidate, keyed by the candidate value. A candidate the
   * fixtures did not build (for example a test's `copy`) has no entry and is decided with empty
   * identifier sets.
   */
  private val idsByCandidate = TrieMap.empty[MatchCandidate, EndpointIds]

  /** Records the identifiers of a candidate's endpoints, in the candidate's (canonical) order. */
  def register(c: MatchCandidate, byEndpoint: Map[String, Vector[ExactId]]): MatchCandidate = {
    idsByCandidate.update(
      c,
      EndpointIds(
        byEndpoint.getOrElse(c.sourceIdA, Vector.empty),
        byEndpoint.getOrElse(c.sourceIdB, Vector.empty)
      )
    )
    c
  }

  def idsOf(c: MatchCandidate): EndpointIds = idsByCandidate.getOrElse(c, EndpointIds.Empty)

  def researcherCandidate(a: ResearcherEndpoint, b: ResearcherEndpoint): MatchCandidate =
    register(
      ok(
        MatchCandidate.build(
          MatchEntityKind.Researcher,
          a.id,
          a.source,
          b.id,
          b.source,
          Vector(BlockKey(0, "fixture|x")),
          BlockingKeys.Version,
          MatchFeatures.Version,
          MatchFeatures.researcher(a, b),
          EntityResolution.researcherExactIdAgreements(a, b),
          candidateProvenance
        )
      ),
      Map(
        a.id -> MatchFeatures.researcherExactIds(a),
        b.id -> MatchFeatures.researcherExactIds(b)
      )
    )

  def workCandidate(a: WorkRecord, b: WorkRecord): MatchCandidate =
    register(
      ok(
        MatchCandidate.build(
          MatchEntityKind.Work,
          a.workId,
          a.source,
          b.workId,
          b.source,
          Vector(BlockKey(0, "fixture_title")),
          BlockingKeys.Version,
          MatchFeatures.Version,
          MatchFeatures.work(a, b),
          EntityResolution.workExactIdAgreements(a, b),
          candidateProvenance
        )
      ),
      Map(a.workId -> MatchFeatures.workExactIds(a), b.workId -> MatchFeatures.workExactIds(b))
    )

  private def zeros(names: Vector[String]): SortedMap[String, Double] =
    SortedMap.from(names.map(_ -> 0.0))

  private def values(ids: Vector[ExactId], s: ExactIdScheme): Set[String] =
    ids.filter(_.scheme == s).map(_.value).toSet

  /** (both, agree, conflict) as er-features.v2 computes them (raw canonical values). */
  private def compare(a: Vector[ExactId], b: Vector[ExactId], s: ExactIdScheme) = {
    val x = values(a, s)
    val y = values(b, s)
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

  /** The `id.*` features of er-features.v2 implied by two endpoint identifier sets. */
  def idFeatures(
      kind: MatchEntityKind,
      a: Vector[ExactId],
      b: Vector[ExactId]
  ): Seq[(String, Double)] =
    kind match {
      case MatchEntityKind.Researcher =>
        MatchFeatures.ResearcherIdSchemes.flatMap(s => flags(s.tag, compare(a, b, s)))
      case _ =>
        val others = MatchFeatures.OtherWorkIdSchemes.map(compare(a, b, _))
        flags("doi", compare(a, b, ExactIdScheme.Doi)) ++
          flags("arxiv", compare(a, b, ExactIdScheme.Arxiv)) ++ Seq(
            "id.other.agree" -> others.count(_._2).toDouble,
            "id.other.conflict" -> others.count(_._3).toDouble
          )
    }

  /** SYNTHETIC mention endpoints on two invented DBLP works. */
  val mentionA: String = ok(MentionId.of("work:dblp:journals/example/SynA", 0))
  val mentionB: String = ok(MentionId.of("work:dblp:journals/example/SynB", 0))

  /**
   * A researcher candidate with hand-set feature values (all other features 0). The `id.*` features
   * and the exact-identifier agreements are derived from the SYNTHETIC endpoint identifiers `idsA`
   * (of [[mentionA]]) and `idsB` (of [[mentionB]]), so they are consistent.
   */
  def syntheticResearcher(
      kv: Seq[(String, Double)],
      idsA: Vector[ExactId] = Vector.empty,
      idsB: Vector[ExactId] = Vector.empty,
      featureVersion: String = MatchFeatures.Version,
      sources: (SourceType, SourceType) = (SourceType.Dblp, SourceType.Dblp)
  ): MatchCandidate = {
    val a = ExactId.canonical(idsA)
    val b = ExactId.canonical(idsB)
    register(
      ok(
        MatchCandidate.build(
          MatchEntityKind.Researcher,
          mentionA,
          sources._1,
          mentionB,
          sources._2,
          Vector(BlockKey(0, "example|a")),
          BlockingKeys.Version,
          featureVersion,
          zeros(MatchFeatures.ResearcherFeatureNames) ++ kv ++
            idFeatures(MatchEntityKind.Researcher, a, b),
          MatchFeatures.exactIdAgreements(a, b),
          candidateProvenance
        )
      ),
      Map(mentionA -> a, mentionB -> b)
    )
  }

  val workA: String = "work:dblp:journals/example/SynA"
  val workB: String = "work:doi:10.9999/example.1"

  /** A work candidate with hand-set features; `id.*` derived from SYNTHETIC identifiers. */
  def syntheticWork(
      kv: Seq[(String, Double)],
      idsA: Vector[ExactId] = Vector.empty,
      idsB: Vector[ExactId] = Vector.empty
  ): MatchCandidate = {
    val a = ExactId.canonical(idsA)
    val b = ExactId.canonical(idsB)
    register(
      ok(
        MatchCandidate.build(
          MatchEntityKind.Work,
          workA,
          SourceType.Dblp,
          workB,
          SourceType.Crossref,
          Vector(BlockKey(0, "example_title")),
          BlockingKeys.Version,
          MatchFeatures.Version,
          zeros(MatchFeatures.WorkFeatureNames) ++ kv ++ idFeatures(MatchEntityKind.Work, a, b),
          MatchFeatures.exactIdAgreements(a, b),
          candidateProvenance
        )
      ),
      Map(workA -> a, workB -> b)
    )
  }

  /** Perfect name agreement and nothing else (a "Wei Wang" on two unrelated papers). */
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

  /** The SYNTHETIC model ID of the test configuration (no model is ever called). */
  val syntheticModelId: String = "fake-model.er-p5-11"

  /**
   * A complete SYNTHETIC `scholarchain.config.v2` document (no real account, bucket, endpoint or
   * model). Defaults equal the policy file's thresholds and versions.
   */
  def configText(
      policyVersion: String = policy.policyVersion,
      accept: Double = 0.85,
      reject: Double = 0.2,
      lower: Double = 0.4,
      upper: Double = 0.7,
      promptVersion: String = policy.adjudication.promptVersion,
      modelId: String = syntheticModelId
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
       |models { llmModelId = "$modelId", embeddingModelId = "fake-embed", embeddingDimension = 8 }
       |graph { windows = [{ startYear = 2000, endYear = 2020 }], policyVersion = synthetic-graph, identityVersion = v1 }
       |bridge { algorithm = synthetic, sampleSize = 10, randomSeed = 7, maxIterations = 5 }
       |community { algorithm = synthetic, maxIterations = 5 }
       |entityResolution {
       |  policyVersion = $policyVersion, acceptThreshold = $accept, rejectThreshold = $reject
       |  llmBandLower = $lower, llmBandUpper = $upper, promptVersion = $promptVersion
       |}
       |runtime { maxPathHops = 3, retrievalTopK = 5, defaultWindow = { startYear = 2000, endYear = 2020 } }
       |""".stripMargin

  /** A configuration source for the entry points that resolves [[configText]]. */
  def configLoader(text: String = configText()): () => Either[
    Vector[scholarchain.core.config.ConfigError],
    scholarchain.core.config.ScholarChainConfig
  ] =
    () => scholarchain.core.config.ConfigLoader.resolve(Vector("synthetic.conf" -> text), Map.empty)

  /** A configuration source that has no configuration (the files variable is unset). */
  val noConfig: () => Either[
    Vector[scholarchain.core.config.ConfigError],
    scholarchain.core.config.ScholarChainConfig
  ] =
    () => scholarchain.core.config.ConfigLoader.load(Map.empty, _ => Left("unused"))

  def decide(c: MatchCandidate, p: MatchPolicy = policy): ResolutionOutcome =
    Resolver.decide(p, c, idsOf(c)).fold(e => throw new AssertionError(e.mkString("; ")), identity)

  /** Repository root (build.sbt next to modules/entity-resolution). */
  lazy val repoRoot: Path = {
    var dir = Path.of(".").toAbsolutePath.normalize
    while (
      dir != null && !(Files.isRegularFile(dir.resolve("build.sbt")) &&
        Files.isDirectory(dir.resolve("modules/entity-resolution")))
    ) dir = dir.getParent
    require(dir != null, "repository root not found")
    dir
  }

  /** A fresh directory under modules/entity-resolution/target/test-work (git ignored). */
  def workDir(name: String): Path = {
    val d = repoRoot.resolve("modules/entity-resolution/target/test-work").resolve(name)
    if (Files.exists(d)) {
      val s = Files.walk(d)
      try s.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
      finally s.close()
    }
    Files.createDirectories(d)
  }

  val samples: Samples.type = Samples
}
