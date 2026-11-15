package scholarchain.entityresolution.review

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.immutable.SortedMap

import scholarchain.core.{Canonical, MentionId, SourceType}
import scholarchain.entityresolution.Samples
import scholarchain.entityresolution.Samples.{em, ep}
import scholarchain.entityresolution.policy.*
import scholarchain.entityresolution.policy.PolicyFixtures.*

/**
 * Fixed gold membership (ER-P5-05) over sample-mirroring records with SYNTHETIC selection units,
 * and SYNTHETIC pair populations for the seeded sample. No labels anywhere; no real held-out
 * manifest is generated here.
 */
class GoldMembershipSuite extends munit.FunSuite {

  private val works = Vector(
    Samples.spannerDblp,
    Samples.spannerCrossref,
    Samples.cacmDblp,
    Samples.cacmCrossref,
    Samples.varajaoDblp,
    Samples.bigtableDblp
  )
  private val profiles = Vector(Samples.popperOpenAlex)
  private val refs = EndpointRef.index(works, profiles)

  private val candidates = Vector(
    researcherCandidate(em(Samples.cacmDblp, 0), ep(Samples.popperOpenAlex)),
    researcherCandidate(em(Samples.spannerDblp, 4), em(Samples.spannerCrossref, 4)),
    researcherCandidate(em(Samples.spannerDblp, 1), em(Samples.bigtableDblp, 1)),
    workCandidate(Samples.spannerDblp, Samples.spannerCrossref),
    workCandidate(Samples.cacmDblp, Samples.varajaoDblp)
  )
  private val devCandidate =
    researcherCandidate(em(Samples.cacmDblp, 0), em(Samples.cacmCrossref, 0))

  /** SYNTHETIC units: two held-out units and one dev unit. */
  private val units = SyntheticUnits.of(
    Vector(
      (
        "syn-spanner",
        "syn-crit-a",
        "heldout",
        Vector(
          Samples.spannerDblp.workId,
          Samples.spannerCrossref.workId,
          Samples.bigtableDblp.workId
        )
      ),
      (
        "syn-cacm",
        "syn-crit-b",
        "heldout",
        Vector(
          Samples.cacmDblp.workId,
          Samples.varajaoDblp.workId,
          Samples.popperOpenAlex.profileId
        )
      ),
      ("syn-dev", "syn-crit-a", "dev", Vector(Samples.cacmCrossref.workId))
    )
  )

  private def run(cs: Vector[scholarchain.core.MatchCandidate]): CuratedRun =
    CuratedRun(repoRoot, works, profiles, cs)

  private def census(cs: Vector[scholarchain.core.MatchCandidate]): Vector[GoldMember] =
    GoldMembership
      .ofRun(run(cs), units, MembershipDesign.census("heldout"))
      .fold(e => fail(e.mkString("; ")), identity)

  private val manifest = census(candidates)

  test("memberId: kind and canonical endpoint pair only; candidateId would change with versions") {
    val c = candidates(1)
    val expected =
      "gm:" + Canonical.hashFields("er-gold-member.v1", "researcher", c.sourceIdA, c.sourceIdB)
    assertEquals(GoldMembership.memberId("researcher", c.sourceIdA, c.sourceIdB), Right(expected))
    assertEquals(GoldMembership.memberId("researcher", c.sourceIdB, c.sourceIdA), Right(expected))
    assertNotEquals(GoldMembership.memberId("work", c.sourceIdA, c.sourceIdB), Right(expected))
    assert(GoldMembership.memberId("venue", c.sourceIdA, c.sourceIdB).isLeft)
    assert(GoldMembership.memberId("researcher", c.sourceIdA, c.sourceIdA).isLeft)
    // The same pair under another feature version and another run: new candidate ID, same member.
    val v1 = syntheticResearcher(nameOnly)
    val v9 = syntheticResearcher(nameOnly, featureVersion = "er-features.v9")
    val otherRun = v1.copy(provenance = v1.provenance.copy(runId = "run:" + "3" * 64))
    assertNotEquals(v1.candidateId, v9.candidateId)
    Vector(v1, v9, otherRun).foreach { x =>
      assertEquals(
        GoldMembership.memberId(x.entityKind.tag, x.sourceIdA, x.sourceIdB),
        GoldMembership.memberId(v1.entityKind.tag, v1.sourceIdA, v1.sourceIdB)
      )
    }
  }

  test("census: every held-out pair, with endpoints, units, criterion, split, run and blocking") {
    assertEquals(manifest.size, 5)
    assertEquals(manifest.map(_.memberId), manifest.map(_.memberId).sorted)
    manifest.foreach { m =>
      assertEquals(m.schemaVersion, "er-gold-membership.v1")
      assertEquals(m.split, "heldout")
      assertEquals(m.selectionRule, "er-gold-census.v1")
      assertEquals((m.seed, m.capacity, m.populationSize), (None, None, 5))
      assertEquals(m.generatingRunId, candidateRunId)
      assertEquals(m.blockingVersion, "er-blocking.v2")
      Vector(m.endpointA, m.endpointB).foreach { e =>
        assert(e.sourceRecordId.nonEmpty && e.snapshot.nonEmpty && e.sourceDataset.nonEmpty)
      }
      assert(Canonical.compareUtf8(m.endpointA.endpointId, m.endpointB.endpointId) < 0)
    }
    val popper = manifest
      .find(_.endpointB.endpointKind == "profile")
      .orElse(
        manifest.find(_.endpointA.endpointKind == "profile")
      )
      .get
    assertEquals(popper.unitIds, Vector("syn-cacm"))
    assertEquals(popper.criterion, "syn-crit-b")
    assertEquals(manifest.filter(_.unitIds == Vector("syn-spanner")).size, 3)
  }

  test("membership is identical under permuted candidate features (outcome-blind)") {
    val permuted = candidates.map { c =>
      val vs = c.features.values.toVector.reverse
      c.copy(features = SortedMap.from(c.features.keys.toVector.zip(vs)))
    }
    assertEquals(GoldMembership.toJsonl(census(permuted)), GoldMembership.toJsonl(manifest))
    assertEquals(
      GoldMembership.toJsonl(census(candidates.reverse)),
      GoldMembership.toJsonl(manifest)
    )
  }

  test("two policies: the outcomes and row IDs differ, the members do not") {
    val strict = policy.copy(
      policyVersion = "er-policy.synthetic-strict",
      thresholds = Thresholds(accept = 0.99, reject = 0.01)
    )
    val o1 = candidates.map(decide(_))
    val o2 = candidates.map(decide(_, strict))
    assertNotEquals(o1.map(_.outcome), o2.map(_.outcome), "the policies must disagree somewhere")
    val q1 = RunQueue.resolveMembers(policy, manifest, run(candidates))
    val q2 = RunQueue.resolveMembers(strict, manifest, run(candidates))
    assertEquals(q1.rows.map(_.memberId), manifest.map(_.memberId))
    assertEquals(q2.rows.map(_.memberId), manifest.map(_.memberId))
    assertNotEquals(q1.rows.map(_.system.outcome), q2.rows.map(_.system.outcome))
    q1.rows.zip(q2.rows).foreach { case (a, b) =>
      assertNotEquals(a.rowId, b.rowId)
      assertEquals(a.rowId, ReviewQueue.rowId(a.memberId, "er-policy.v8"))
      assertEquals(b.rowId, ReviewQueue.rowId(b.memberId, "er-policy.synthetic-strict"))
    }
    // The outcome-stratified (dev) selection does move with the policy.
    val d = SamplingDesign(2, 1, "s")
    def strata(p: MatchPolicy, os: Vector[ResolutionOutcome]) =
      ReviewQueue.buildDevDiagnostics(p, os, refs, d, SelectionUnits.Empty).toOption.get.strata
    assertNotEquals(strata(policy, o1), strata(strict, o2))
  }

  test("a member that the run does not generate is reported as not-generated, never dropped") {
    val q = RunQueue.resolveMembers(policy, manifest, run(candidates.dropRight(1)))
    val missing = GoldMembership
      .memberId("work", candidates.last.sourceIdA, candidates.last.sourceIdB)
      .toOption
      .get
    assertEquals(q.notGenerated.map(_.memberId), Vector(missing))
    assertEquals(q.rows.size + q.notGenerated.size, manifest.size)
    assertEquals(q.refused, Vector.empty)
    assert(RunQueue.summary(q, manifest.size).exists(_ == s"not-generated $missing"))
    // A candidate outside the manifest is ignored and counted.
    val extra = RunQueue.resolveMembers(policy, manifest.drop(1), run(candidates))
    assertEquals(extra.outsideManifest, 1)
  }

  test("dev endpoints are refused for the held-out manifest; unmapped endpoints are refused") {
    val r = GoldMembership.ofRun(
      run(candidates :+ devCandidate),
      units,
      MembershipDesign.census("heldout")
    )
    assert(r.isLeft)
    assert(r.left.toOption.get.exists(_.contains("unit syn-dev is in the dev split")), r.toString)
    val unmapped = GoldMembership.build(
      Vector(CandidatePair.of(syntheticResearcher(nameOnly))),
      refs,
      units,
      MembershipDesign.census("heldout")
    )
    assert(unmapped.left.toOption.get.exists(_.contains("has no source record")), unmapped.toString)
    val outside =
      SyntheticUnits.of(Vector(("syn-x", "c", "heldout", Vector(Samples.cacmDblp.workId))))
    val notInSelection =
      GoldMembership.ofRun(run(candidates.take(2)), outside, MembershipDesign.census("heldout"))
    assert(notInSelection.left.toOption.get.exists(_.contains("maps to no selection unit")))
    val twoRuns = candidates.updated(
      0,
      candidates(0).copy(provenance = candidates(0).provenance.copy(runId = "run:" + "4" * 64))
    )
    assert(GoldMembership.ofRun(run(twoRuns), units, MembershipDesign.census("heldout")).isLeft)
    assert(GoldMembership.ofRun(run(candidates), units, MembershipDesign.census("test")).isLeft)
  }

  /** SYNTHETIC population: `n` work pairs of invented DBLP records in one held-out unit each. */
  private def population(spec: Vector[(String, String, Int)]) = {
    val rows = spec.flatMap { case (kind, crit, n) =>
      (0 until n).map { i =>
        val wa = s"work:dblp:journals/syn/$crit-$kind-a$i"
        val wb = s"work:dblp:journals/syn/$crit-$kind-b$i"
        val (a, b) =
          if (kind == "work") (wa, wb)
          else (ok(MentionId.of(wa, 0)), ok(MentionId.of(wb, 0)))
        (kind, crit, wa, wb, a, b)
      }
    }
    def ref(id: String, w: String, kind: String) =
      id -> EndpointRef(
        id,
        if (kind == "work") "work" else "mention",
        SourceType.Dblp.tag,
        w.stripPrefix("work:dblp:"),
        "dblp-2026-10-03",
        "dblp-raw/1.0.0",
        candidateRunId,
        Some(w),
        if (kind == "work") None else Some(0),
        Vector("SYNTHETIC")
      )
    val rs = rows.flatMap { case (kind, _, wa, wb, a, b) =>
      Vector(ref(a, wa, kind), ref(b, wb, kind))
    }.toMap
    val us = SyntheticUnits.of(
      rows.groupBy(_._2).toVector.map { case (crit, xs) =>
        (s"syn-unit-$crit", crit, "heldout", xs.flatMap(x => Vector(x._3, x._4)).distinct)
      }
    )
    val pairs = rows.map { case (kind, _, _, _, a, b) =>
      val Vector(x, y) = Vector(a, b).sortWith(Canonical.compareUtf8(_, _) < 0)
      CandidatePair(kind, x, y, "er-blocking.v2", candidateRunId)
    }
    (pairs, rs, us)
  }

  test("seeded sample: deterministic, proportional over (kind, criterion), outcome-blind") {
    val (pairs, rs, us) =
      population(Vector(("researcher", "ca", 30), ("researcher", "cb", 10), ("work", "ca", 20)))
    val design = MembershipDesign("heldout", Some(12), Some("s1"))
    val a = GoldMembership.build(pairs, rs, us, design).fold(e => fail(e.mkString("; ")), identity)
    val b = GoldMembership.build(pairs.reverse, rs, us, design).toOption.get
    assertEquals(GoldMembership.toJsonl(a), GoldMembership.toJsonl(b))
    assertEquals(a.size, 12)
    val by = a.groupBy(m => s"${m.entityKind}/${m.criterion}").map { case (k, v) => k -> v.size }
    assertEquals(by, Map("researcher/ca" -> 6, "researcher/cb" -> 2, "work/ca" -> 4))
    a.foreach { m =>
      assertEquals(
        (m.selectionRule, m.seed, m.capacity, m.populationSize),
        ("er-gold-sample.v1", Some("s1"), Some(12), 60)
      )
    }
    // Within each group the first members by SHA-256(er-gold-sample.v1, seed, memberId) are taken.
    val all = GoldMembership.build(pairs, rs, us, MembershipDesign.census("heldout")).toOption.get
    val expected = all
      .groupBy(m => s"${m.entityKind}/${m.criterion}")
      .values
      .flatMap(ms =>
        ms.sortBy(m => GoldMembership.sampleKey("s1", m.memberId))
          .take(by(s"${ms.head.entityKind}/${ms.head.criterion}"))
      )
      .map(_.memberId)
      .toSet
    assertEquals(a.map(_.memberId).toSet, expected)
    val other = GoldMembership
      .build(pairs, rs, us, MembershipDesign("heldout", Some(12), Some("s2")))
      .toOption
      .get
    assertNotEquals(other.map(_.memberId).toSet, a.map(_.memberId).toSet)
    // A capacity at or above the population is a census (capacity and seed still recorded).
    val big = GoldMembership
      .build(pairs, rs, us, MembershipDesign("heldout", Some(60), Some("s1")))
      .toOption
      .get
    assertEquals(big.size, 60)
    assert(big.forall(_.selectionRule == "er-gold-census.v1"))
    assert(GoldMembership.build(pairs, rs, us, MembershipDesign("heldout", Some(12), None)).isLeft)
    assert(
      GoldMembership.build(pairs, rs, us, MembershipDesign("heldout", Some(0), Some("s"))).isLeft
    )
  }

  test("capacity 240 by default: seeded proportional sample, counts by kind and stratum") {
    assertEquals(GoldMembership.DefaultCapacity, 240)
    val design = MembershipDesign.labeling("heldout", "er-p5-heldout-1")
    assertEquals((design.capacity, design.seed), (Some(240), Some("er-p5-heldout-1")))
    val (pairs, rs, us) =
      population(Vector(("researcher", "ca", 150), ("researcher", "cb", 50), ("work", "ca", 100)))
    val p = GoldMembership.plan(pairs, rs, us, design).fold(e => fail(e.mkString("; ")), identity)
    assertEquals((p.population.size, p.members.size, p.shortfall), (300, 240, 0))
    assertEquals(
      GoldMembership.strataCounts(p.members).toMap,
      Map("researcher/ca" -> 120, "researcher/cb" -> 40, "work/ca" -> 80)
    )
    assert(p.members.forall(m => m.seed.contains("er-p5-heldout-1") && m.capacity.contains(240)))
    val s = GoldMembership.summary(p)
    assert(s.head.contains("seed=er-p5-heldout-1 capacity=240 population=300 sampled=240"), s.head)
    assert(s.head.contains("rule=er-gold-sample.v1 shortfall=0"), s.head)
    assert(s.contains("population researcher=200 work=100"), s.mkString("\n"))
    assert(s.contains("sample researcher=160 work=80"), s.mkString("\n"))
    assert(s.contains("stratum researcher/cb population=50 sample=40"), s.mkString("\n"))
    assert(s.last.startsWith(s"manifest sha256=${GoldMembership.manifestSha256(p.members)}"))
    // The same seed reproduces the sample; the build never depends on anything but the design.
    assertEquals(
      GoldMembership.plan(pairs.reverse, rs, us, design).map(_.members),
      Right(p.members)
    )
    // A seed is required.
    assert(GoldMembership.plan(pairs, rs, us, design.copy(seed = None)).isLeft)
  }

  test("a population below 240 is a census: shortfall reported, nothing padded") {
    val (pairs, rs, us) = population(Vector(("researcher", "ca", 30), ("work", "cb", 20)))
    val p = GoldMembership
      .plan(pairs, rs, us, MembershipDesign.labeling("heldout", "s"))
      .fold(e => fail(e.mkString("; ")), identity)
    assertEquals((p.population.size, p.members.size, p.shortfall), (50, 50, 190))
    assert(p.members.forall(_.selectionRule == GoldMembership.CensusRule))
    assertEquals(p.members.map(_.memberId).distinct.size, 50, "no member is repeated as padding")
    val s = GoldMembership.summary(p)
    assert(s.head.contains("shortfall=190 (census"), s.head)
    assert(s.contains("stratum work/cb population=20 sample=20"), s.mkString("\n"))
  }

  test("freeze: the summary sha256 equals the sha256 of the written manifest bytes") {
    val (pairs, rs, us) = population(Vector(("researcher", "ca", 5)))
    val p =
      GoldMembership.plan(pairs, rs, us, MembershipDesign.labeling("heldout", "s")).toOption.get
    val f = workDir("gold-membership-freeze").resolve("er-gold-membership.synthetic.jsonl")
    assertEquals(GoldMembership.writeNew(f, p.members), Right(()))
    assertEquals(GoldMembership.fileSha256(f), Right(GoldMembership.manifestSha256(p.members)))
    assert(GoldMembership.writeNew(f, p.members).isLeft, "create-once: never overwritten")
  }

  test("membership CLI: the seed is required; usage lists the default capacity") {
    val out = Vector.newBuilder[String]
    val args = Vector("membership", "sa.tsv", "pv.tsv", "dir", "heldout", "m.jsonl", "b.jsonl")
    assertEquals(ReviewQueueMain.run(args, out += _), 2)
    assert(out.result().exists(l => l.contains("<seed>") && l.contains("default 240")))
  }

  test("largest-remainder allocation sums to the capacity") {
    val q = GoldMembership.allocate(Vector("a" -> 5, "b" -> 3, "c" -> 1), 4)
    assertEquals(q.values.sum, 4)
    assertEquals(q, Map("a" -> 2, "b" -> 1, "c" -> 1))
  }

  test("the manifest round-trips, refuses tampering, and is never overwritten") {
    val text = GoldMembership.toJsonl(manifest)
    assertEquals(GoldMembership.parse(text.split('\n').toVector), Right(manifest))
    val tampered = manifest.updated(0, manifest(0).copy(memberId = "gm:" + "0" * 64))
    assert(GoldMembership.parse(tampered.map(m => upickle.default.write(m))).isLeft)
    val mixed = manifest.updated(0, manifest(0).copy(generatingRunId = "run:" + "5" * 64))
    assert(GoldMembership.parse(mixed.map(m => upickle.default.write(m))).isLeft)
    val dup = manifest :+ manifest(0)
    assert(GoldMembership.parse(dup.map(m => upickle.default.write(m))).isLeft)
    val dir = workDir("gold-membership")
    val p = dir.resolve("er-gold-membership.synthetic.jsonl")
    assertEquals(GoldMembership.writeNew(p, manifest), Right(()))
    assertEquals(GoldMembership.readFile(p), Right(manifest))
    assert(GoldMembership.writeNew(p, Vector.empty).isLeft)
    assertEquals(Files.readString(p, StandardCharsets.UTF_8), text)
  }

  test("selection units: cross-checked against split-assignment; unknown kinds refused") {
    val (sa, pv) =
      SyntheticUnits.texts(Vector(("u1", "c1", "heldout", Vector(Samples.cacmDblp.workId))))
    assert(SelectionUnits.fromText(sa, pv).isRight)
    assert(SelectionUnits.fromText(sa, pv.replace("\theldout\tc1\t", "\tdev\tc1\t")).isLeft)
    assert(SelectionUnits.fromText(sa, pv.replace("\tdblp\trec\t", "\tdblp\tperson\t")).isLeft)
    assert(SelectionUnits.fromText(sa.replace("\theldout\t", "\ttest\t"), pv).isLeft)
    assert(SelectionUnits.fromText(sa, pv.replace("\tu1\t", "\tu2\t")).isLeft)
  }

  test(
    "the committed DS-P5-01 files parse as data: 70 units, 46 heldout and 24 dev, every record"
  ) {
    val u = SelectionUnits
      .load(
        repoRoot.resolve("datasets/er-candidates/split-assignment.tsv"),
        repoRoot.resolve("datasets/samples/er-candidates/PROVENANCE.tsv")
      )
      .fold(e => fail(e.mkString("; ")), identity)
    assertEquals(u.units.size, 70)
    assertEquals(u.units.values.count(_.split == "heldout"), 46)
    assertEquals(u.units.values.count(_.split == "dev"), 24)
    assert(u.byAnchor.size > 100, u.byAnchor.size.toString)
    assert(u.byAnchor.values.forall(_.nonEmpty))
  }
}
