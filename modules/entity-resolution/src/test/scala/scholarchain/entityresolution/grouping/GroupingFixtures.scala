package scholarchain.entityresolution.grouping

import scala.collection.immutable.SortedMap

import scholarchain.core.*
import scholarchain.core.config.GroupingRunSettings
import scholarchain.entityresolution.Samples
import scholarchain.entityresolution.Samples.A
import scholarchain.entityresolution.policy.MatchPolicy

/**
 * SYNTHETIC grouping fixtures (ER-P6-05). Every identifier, name, work and decision is invented
 * (ORCID iDs are generated with valid check characters from small integers); nothing is read from
 * any gold file, label, review queue or held-out output. Decisions are built directly with the core
 * builder, so a test controls exactly which pairs are ACCEPTED, REJECTED, CONFLICT or UNRESOLVED.
 */
object GroupingFixtures {

  def ok[T](e: Either[?, T]): T =
    e.fold(err => throw new AssertionError(s"fixture is invalid: $err"), identity)

  val matchPolicy: MatchPolicy = ok(MatchPolicy.loadDefault())
  val policy: GroupingPolicy = ok(GroupingPolicy.loadDefault())

  val snapshots: Vector[SnapshotRef] = Vector(
    SnapshotRef(SourceType.Dblp, "dblp-2026-10-03"),
    SnapshotRef(SourceType.OpenAlex, "openalex-2026-10-14"),
    SnapshotRef(SourceType.Crossref, "crossref-2026-10-01")
  )

  /** A SYNTHETIC, checksum-valid ORCID iD built from `n`. */
  def orcid(n: Int): String = {
    val base = f"$n%015d"
    val c = Identifiers.orcidCheckChar(base)
    s"${base.substring(0, 4)}-${base.substring(4, 8)}-${base.substring(8, 12)}-${base.substring(12)}$c"
  }

  val settings: GroupingRunSettings = GroupingRunSettings(
    "er-grouping.v1",
    "er-policy.v8",
    "v1",
    "/var/tmp/scholarchain-synthetic/er/decisions",
    "/var/tmp/scholarchain-synthetic/er/assignments"
  )
  val configDigest: String = "d" * 64
  val assignedAt: String = "2026-11-15T12:00:00Z"
  val decidedAt: String = "2026-11-15T00:00:00Z"

  val curatedManifest: RunManifest = RunManifest(
    SchemaVersions.RunManifest,
    "hadoop.pipeline",
    "a" * 40,
    "b" * 64,
    snapshots,
    SortedMap.empty[String, String],
    1
  )
  val curatedRunId: String = ok(RunId.of(curatedManifest))

  def decisionManifest(attempt: Int = 1, set: Map[String, String] = Map.empty): RunManifest =
    RunManifest(
      SchemaVersions.RunManifest,
      "er.decisions",
      "c" * 40,
      "e" * 64,
      snapshots,
      SortedMap.from(
        Map(
          "blockingVersion" -> "er-blocking.v2",
          "decidedAt" -> decidedAt,
          "featureVersion" -> "er-features.v2",
          "inputRunId" -> curatedRunId,
          "policyVersion" -> "er-policy.v8"
        ) ++ set
      ),
      attempt
    )

  /** SYNTHETIC provenance of a curated record of run `runId` (CR-P6-07). */
  def curatedProvenance(subject: String, runId: String = curatedRunId): Provenance =
    Provenance(
      SourceType.Derived,
      subject,
      "dblp-2026-10-03",
      runId,
      SchemaVersions.CandidateBlock,
      Some("hadoop-transform.v2"),
      None,
      None
    )

  /** The work and each of its mentions as records of curated run `runId` (CR-P6-07). */
  def ofRun(x: WorkRecord, runId: String = curatedRunId): WorkRecord =
    x.copy(
      provenance = x.provenance.copy(runId = runId),
      authors = x.authors.map(a => a.copy(provenance = a.provenance.copy(runId = runId)))
    )

  /** The profile as a record of curated run `runId` (CR-P6-07). */
  def profileOfRun(p: SourceAuthorProfile, runId: String = curatedRunId): SourceAuthorProfile =
    p.copy(provenance = p.provenance.copy(runId = runId))

  def groupingManifest(
      dm: RunManifest,
      at: String = assignedAt,
      s: GroupingRunSettings = settings,
      set: Map[String, String] = Map.empty,
      drop: Set[String] = Set.empty,
      stage: String = Grouping.Stage,
      inputs: Vector[SnapshotRef] = snapshots,
      digest: String = configDigest
  ): RunManifest = RunManifest(
    SchemaVersions.RunManifest,
    stage,
    "f" * 40,
    digest,
    inputs,
    SortedMap.from(
      Map(
        Grouping.Params.AssignedAt -> at,
        Grouping.Params.DecisionRunId -> ok(RunId.of(dm)),
        Grouping.Params.GroupingPolicyVersion -> s.groupingPolicyVersion,
        Grouping.Params.IdentityVersion -> s.identityVersion,
        Grouping.Params.InputRunId -> curatedRunId,
        Grouping.Params.MatchPolicyVersion -> s.matchPolicyVersion
      ) ++ set -- drop
    ),
    1
  )

  // ---------------------------------------------------------------- records

  private def title(id: String): String = s"Synthetic grouping fixture $id"

  def dblpWork(
      key: String,
      authors: Vector[A],
      doi: Option[String] = None,
      arxiv: Option[String] = None,
      year: Option[Int] = Some(2020)
  ): WorkRecord =
    ofRun(
      Samples
        .buildWork(
          SourceType.Dblp,
          s"work:dblp:$key",
          key,
          title(key),
          year,
          None,
          Some("Synthetic Venue"),
          doi,
          authors
        )
        .copy(arxivId = arxiv)
    )

  def crossrefWork(doi: String, authors: Vector[A], year: Option[Int] = Some(2020)): WorkRecord =
    ofRun(
      Samples.buildWork(
        SourceType.Crossref,
        s"work:doi:$doi",
        doi,
        title(doi),
        year,
        None,
        Some("Synthetic Venue"),
        Some(doi),
        authors
      )
    )

  def openAlexWork(
      w: String,
      authors: Vector[A],
      doi: Option[String] = None,
      arxiv: Option[String] = None
  ): WorkRecord =
    ofRun(
      Samples
        .buildWork(
          SourceType.OpenAlex,
          s"work:openalex:$w",
          w,
          title(w),
          Some(2020),
          None,
          Some("Synthetic Venue"),
          doi,
          authors
        )
        .copy(arxivId = arxiv)
    )

  def pid(p: String): Option[ExternalId] = Some(ExternalId(ExternalIdScheme.DblpPid, p))
  def openAlexAuthor(a: String): Option[ExternalId] = Some(ExternalId(ExternalIdScheme.OpenAlex, a))

  /** A SYNTHETIC OpenAlex author profile. */
  def openAlexProfile(
      a: String,
      name: String,
      orcidOf: Option[String] = None
  ): SourceAuthorProfile =
    SourceAuthorProfile(
      schemaVersion = SchemaVersions.SourceAuthorProfile,
      profileId = s"source-author:openalex:$a",
      source = SourceType.OpenAlex,
      sourceRecordId = a,
      displayName = Some(name),
      givenNames = None,
      familyName = None,
      nameVariants = Vector.empty,
      orcid = orcidOf,
      externalIds = Vector(ExternalId(ExternalIdScheme.OpenAlex, a)),
      institutionIds = Vector.empty,
      claimedWorkIds = Vector.empty,
      provenance = Samples.prov(SourceType.OpenAlex, a).copy(runId = curatedRunId)
    )

  /** The mention at `position` of `w`. */
  def m(w: WorkRecord, position: Int = 0): String = w.authors(position).mentionId

  // ---------------------------------------------------------------- decisions

  /** One SYNTHETIC decision: kind, endpoints, outcome, path, confidence. */
  final case class D(
      kind: MatchEntityKind,
      a: String,
      b: String,
      outcome: MatchOutcome,
      path: MatchDecisionPath,
      confidence: Double
  )

  def r(
      a: String,
      b: String,
      outcome: MatchOutcome = MatchOutcome.Accepted,
      path: MatchDecisionPath = MatchDecisionPath.Probabilistic,
      confidence: Double = 0.9
  ): D = D(MatchEntityKind.Researcher, a, b, outcome, path, confidence)

  def w(
      a: String,
      b: String,
      outcome: MatchOutcome = MatchOutcome.Accepted,
      path: MatchDecisionPath = MatchDecisionPath.Probabilistic,
      confidence: Double = 0.9
  ): D = D(MatchEntityKind.Work, a, b, outcome, path, confidence)

  def candidateId(d: D): String = {
    val (x, y) = if (Canonical.compareUtf8(d.a, d.b) < 0) (d.a, d.b) else (d.b, d.a)
    "mcand:" + Canonical.sha256HexUtf8(s"${d.kind.tag}|$x|$y")
  }

  def decision(d: D, decisionRunId: String): MatchDecision = {
    val cand = candidateId(d)
    val conflicts =
      if (d.outcome != MatchOutcome.Conflict) Vector.empty
      else if (d.kind == MatchEntityKind.Researcher)
        Vector(
          IdentifierConflict.contradictedAgreement(
            ExactIdScheme.Orcid,
            Vector(orcid(999)),
            "fixture-contradiction"
          )
        )
      else
        Vector(
          IdentifierConflict.contradictedAgreement(
            ExactIdScheme.Doi,
            Vector("10.9999/fixture"),
            "fixture-contradiction"
          )
        )
    val scored = d.path == MatchDecisionPath.Probabilistic
    ok(
      MatchDecision.build(
        candidateId = cand,
        entityKind = d.kind,
        sourceId = d.a,
        targetId = d.b,
        matchPath = d.path,
        decision = d.outcome,
        confidence = d.confidence,
        score = Option.when(scored)(d.confidence),
        blockingVersion = "er-blocking.v2",
        featureVersion = "er-features.v2",
        features = SortedMap.empty,
        rulesFired = Vector("fixture.rule"),
        reasonCodes = Vector("fixture-reason"),
        conflicts = conflicts,
        adjudication = None,
        decidedAt = decidedAt,
        provenance = Provenance(
          SourceType.Derived,
          cand,
          "dblp-2026-10-03",
          decisionRunId,
          SchemaVersions.MatchCandidate,
          Some("er-policy.v8"),
          None,
          None
        )
      )
    )
  }

  def inputs(
      works: Vector[WorkRecord],
      profiles: Vector[SourceAuthorProfile],
      ds: Vector[D],
      decisionAttempt: Int = 1,
      at: String = assignedAt,
      s: GroupingRunSettings = settings,
      g: GroupingPolicy = policy,
      mp: MatchPolicy = matchPolicy,
      decisionSet: Map[String, String] = Map.empty
  ): GroupingInputs = {
    val dm = decisionManifest(decisionAttempt, decisionSet)
    val decisions = ds.map(decision(_, ok(RunId.of(dm))))
    GroupingInputs(
      manifest = groupingManifest(dm, at, s),
      configDigest = configDigest,
      settings = s,
      policy = g,
      matchPolicy = mp,
      decisionManifest = dm,
      decisions = decisions,
      curatedManifest = curatedManifest,
      works = works,
      profiles = profiles,
      candidates =
        decisions.map(d => CuratedCandidate(d.candidateId, curatedProvenance(d.candidateId)))
    )
  }

  /**
   * A small SYNTHETIC run with every record type: two resolved researchers (one anchored on an
   * ORCID iD), a vetoed link (T1 orcid), a t1-anchored singleton, three teams, a grouped counted
   * work with a member-of-counted copy, and a pending work decision.
   */
  def sampleInputs(): GroupingInputs = {
    val o1 = orcid(1)
    val a = dblpWork("conf/x/N1", Vector(A("Wei Wang", orcid = Some(o1)), A("Kim Park")))
    val b = crossrefWork("10.1000/n2", Vector(A("Wei Wang")))
    val c = dblpWork("conf/x/N3", Vector(A("Wei Wang", orcid = Some(orcid(2)))))
    val d = dblpWork("journals/y/N4", Vector(A("Kim Park")), doi = Some("10.1000/n2"))
    inputs(
      Vector(a, b, c, d),
      Vector.empty,
      Vector(
        r(m(a), m(b), path = MatchDecisionPath.ExactId, confidence = 0.99),
        r(m(b), m(c), confidence = 0.9),
        r(m(a, 1), m(d), path = MatchDecisionPath.DeterministicBibliographic, confidence = 0.95),
        w(b.workId, d.workId, path = MatchDecisionPath.ExactId, confidence = 0.99),
        w(a.workId, d.workId, MatchOutcome.Unresolved, confidence = 0.5)
      )
    )
  }

  def group(in: GroupingInputs): GroupingBatch =
    Grouping.group(in).fold(e => throw new AssertionError(e.mkString("\n")), identity)

  // ---------------------------------------------------------------- reading a batch

  def researcher(b: GroupingBatch, member: String): ResearcherAssignment =
    b.researchers.find(_.memberId == member).getOrElse(throw new AssertionError(s"no $member"))

  def work(b: GroupingBatch, id: String): WorkAssignment =
    b.works.find(_.workId == id).getOrElse(throw new AssertionError(s"no $id"))

  /** True when both members carry one researcher ID. */
  def together(b: GroupingBatch, x: String, y: String): Boolean =
    researcher(b, x).researcherId.exists(id => researcher(b, y).researcherId.contains(id))

  def sameWork(b: GroupingBatch, x: String, y: String): Boolean =
    work(b, x).canonicalWorkId == work(b, y).canonicalWorkId

  /** Plain transitive closure of the ACCEPTED decisions: would it join `x` and `y`? */
  def closureJoins(ds: Vector[D], x: String, y: String): Boolean = {
    val links = ds.filter(_.outcome == MatchOutcome.Accepted).map(d => (d.a, d.b))
    var reached = Set(x)
    var grew = true
    while (grew) {
      val next = reached ++ links.collect {
        case (a, b) if reached(a) => b
        case (a, b) if reached(b) => a
      }
      grew = next.size > reached.size
      reached = next
    }
    reached(y)
  }

  def link(b: GroupingBatch, in: GroupingInputs, d: D): LinkResult = {
    val id = in.decisions.find(_.candidateId == candidateId(d)).get.decisionId
    b.links.find(_.decisionId == id).getOrElse(throw new AssertionError(s"no link $d"))
  }

  def decisionIdOf(in: GroupingInputs, d: D): String =
    in.decisions.find(_.candidateId == candidateId(d)).get.decisionId
}
