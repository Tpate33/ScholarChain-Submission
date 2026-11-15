package scholarchain.tests.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*

import upickle.default.write

import scholarchain.core.*
import scholarchain.entityresolution.policy.*
import scholarchain.entityresolution.review.*

import ErFixtures.*

/**
 * TE-P5-01 area 5: leakage into the blind labeling view, DBLP identity masking, and the structural
 * separation of matching code from labels (README Phase 5 gate: "no data leakage from evaluation
 * labels into matching logic"; decisions-04 masking instruction). Every name, pid, and link is
 * SYNTHETIC ("Quentin Synthwell" and "Ada Examplewright" are invented names).
 */
class BlindViewLeakageAdversarialSuite extends munit.FunSuite {

  private val title = "A Synthetic Study of Example Graphs"

  private def mentionEndpoint(
      endpointId: String,
      name: String,
      coauthors: Vector[String] = Vector("Ada Examplewright"),
      identifiers: Vector[String] = Vector.empty,
      eeLinks: Vector[String] = Vector("https://doi.org/10.9999/synthetic.x"),
      urls: Vector[String] = Vector("https://dblp.org/rec/journals/synthetic/X21"),
      affiliations: Vector[String] = Vector("Synthetic University")
  ): BlindEndpoint =
    BlindEndpoint(
      endpointId,
      "mention",
      "dblp",
      "journals/synthetic/X21",
      "synthetic-snapshot",
      Some(name),
      None,
      Vector.empty,
      Some(1),
      affiliations,
      Vector.empty,
      identifiers,
      Vector.empty,
      Some(
        BlindWork(
          "work:dblp:journals/synthetic/X21",
          title,
          Some(2021),
          Some("Synthetic Venue"),
          coauthors.size + 1,
          name +: coauthors,
          Vector("doi:10.9999/synthetic.x"),
          eeLinks,
          urls
        )
      )
    )

  private val mA = ok(MentionId.of("work:dblp:journals/synthetic/X21", 0))
  private val mB = ok(MentionId.of("work:dblp:journals/synthetic/Y22", 0))

  private def row(kind: String, a: BlindEndpoint, b: BlindEndpoint): BlindRow = {
    val member = ok(GoldMembership.memberId(kind, a.endpointId, b.endpointId))
    BlindView
      .row(
        BlindItem(member, kind, a.endpointId, b.endpointId),
        Map(a.endpointId -> a, b.endpointId -> b)
      )
      .fold(e => fail(e), identity)
  }

  private val idKeys = Set("memberId", "sourceIdA", "sourceIdB", "endpointId")

  /**
   * Every string leaf of the row JSON except the opaque ID fields (hashes could contain digits).
   */
  private def displayed(r: BlindRow): Vector[String] = {
    def walk(v: ujson.Value): Vector[String] = v match {
      case o: ujson.Obj =>
        o.value.toVector.flatMap { case (k, x) => if (idKeys(k)) Vector.empty else walk(x) }
      case a: ujson.Arr => a.value.toVector.flatMap(walk)
      case ujson.Str(s) => Vector(s)
      case _ => Vector.empty
    }
    walk(ujson.read(write(r)))
  }

  private def keys(v: ujson.Value): Set[String] = v match {
    case o: ujson.Obj => o.value.keySet.toSet ++ o.value.values.flatMap(keys)
    case a: ujson.Arr => a.value.flatMap(keys).toSet
    case _ => Set.empty
  }

  // ------------------------------------------------------------------ hidden system output

  test(
    "no system output reaches the blind view: rows built from an internal queue row and from a manifest member"
  ) {
    val built = researcher(nameOnly)
    val o = built.decide()
    def ref(id: String) = EndpointRef(
      id,
      "mention",
      "dblp",
      "journals/synthetic/X21",
      "synthetic-snapshot",
      "dblp-raw/1.0.0",
      candidateRunId,
      Some("work:dblp:journals/synthetic/X21"),
      Some(0),
      Vector("author as written: Quentin Synthwell")
    )
    val queueRow = ReviewQueue
      .row(policy, o, Map(o.sourceIdA -> ref(o.sourceIdA), o.sourceIdB -> ref(o.sourceIdB)))
      .fold(e => fail(e), identity)
    val evidence = Map(
      o.sourceIdA -> mentionEndpoint(o.sourceIdA, "Quentin Synthwell"),
      o.sourceIdB -> mentionEndpoint(o.sourceIdB, "Quentin Synthwell")
    )
    val fromQueue = BlindView.row(BlindItem.of(queueRow), evidence).fold(e => fail(e), identity)
    val member = GoldMember(
      GoldMembership.SchemaVersion,
      queueRow.memberId,
      "researcher",
      MemberEndpoint(
        o.sourceIdA,
        "mention",
        "dblp",
        "journals/synthetic/X21",
        "synthetic-snapshot"
      ),
      MemberEndpoint(
        o.sourceIdB,
        "mention",
        "dblp",
        "journals/synthetic/X21",
        "synthetic-snapshot"
      ),
      Vector("synthetic-unit-heldout-0077"),
      "synthetic-criterion-homonym",
      "heldout",
      candidateRunId,
      "er-blocking.v2",
      GoldMembership.SampleRule,
      Some("te-seed-77"),
      Some(240),
      977
    )
    val fromMember = BlindView.row(BlindItem.of(member), evidence).fold(e => fail(e), identity)
    assertEquals(fromQueue, fromMember, "the view does not depend on where the pair came from")
    val json = write(fromQueue)
    val forbiddenKeys = keys(ujson.read(json)).intersect(BlindView.HiddenFields.toSet)
    assertEquals(forbiddenKeys, Set.empty[String])
    assert(keys(ujson.read(json)).subsetOf(BlindView.VisibleFields.toSet), json)
    val s = queueRow.system
    val forbiddenValues = Vector(
      queueRow.rowId,
      queueRow.candidateId,
      queueRow.stratum,
      queueRow.policyVersion,
      queueRow.featureVersion,
      queueRow.blockingVersion,
      candidateRunId,
      s.outcome,
      s.path,
      s.score.toString,
      "llm-band",
      "synthetic-unit-heldout-0077",
      "synthetic-criterion-homonym",
      "heldout",
      "te-seed-77",
      "977",
      GoldMembership.SampleRule
    ) ++ s.rulesFired ++ s.reasonCodes ++ o.features.keys
    // Opaque hash IDs (memberId, endpoint IDs) are excluded: a hex hash may contain any digits.
    val text = displayed(fromQueue).mkString("\n")
    forbiddenValues.foreach(v => assert(!text.contains(v), s"blind view leaks '$v'"))
    Vector(queueRow.rowId, queueRow.candidateId, candidateRunId, queueRow.stratum).foreach(v =>
      assert(!json.contains(v), s"blind view JSON leaks '$v'")
    )
  }

  // ------------------------------------------------------------------ DBLP masking

  private val homonymPair = row(
    "researcher",
    mentionEndpoint(mA, "Quentin Synthwell 0001", coauthors = Vector("Ada Examplewright 0003")),
    mentionEndpoint(mB, "Synthwell 0002, Quentin", coauthors = Vector("Examplewright 0004, Ada"))
  )

  test("homonym numbers are removed from names and coauthor names; titles keep their digits") {
    val shown = displayed(homonymPair)
    Vector("0001", "0002", "0003", "0004").foreach(n =>
      assert(!shown.exists(_.contains(n)), s"homonym number $n is displayed: $shown")
    )
    assert(shown.contains("Quentin Synthwell"))
    assert(shown.contains("Synthwell, Quentin"))
    assert(shown.contains("Examplewright, Ada"))
    assert(shown.contains(title), "the title is publication evidence and stays visible")
    assertEquals(homonymPair.endpointA.work.flatMap(_.year), Some(2021))
    assert(homonymPair.maskingNotes.nonEmpty)
  }

  test(
    "dblp person IDs and person links are masked everywhere, including inside URLs and free text"
  ) {
    val a = mentionEndpoint(
      mA,
      "Quentin Synthwell",
      identifiers = Vector("dblp-pid:00/9001", "orcid:" + orcid(9)),
      eeLinks = Vector("https://doi.org/10.9999/synthetic.x", "https://dblp.org/pid/00/9001.html"),
      urls = Vector(
        "https://dblp.org/rec/journals/synthetic/X21",
        "https://dblp.uni-trier.de/pers/hd/s/Synthwell:Quentin",
        "db/journals/synthetic/synthetic21.html#X21",
        "https://dblp.org/search?q=x&pid=00/9001"
      ),
      affiliations = Vector(
        "Synthetic University; profile https://dblp.org/pid/00/9002 (see dblp_pid = 00/9002)"
      )
    )
    val profileId = "source-author:dblp-pid:00/9002"
    val profile = BlindEndpoint(
      profileId,
      "profile",
      "dblp",
      "00/9002",
      "synthetic-snapshot",
      Some("Quentin Synthwell 0002"),
      None,
      Vector("Q. Synthwell 0002"),
      None,
      Vector.empty,
      Vector.empty,
      Vector("dblp:00/9002", "dblp-key:homepages/00/9002"),
      Vector.empty,
      None
    )
    val r = row("researcher", a, profile)
    val shown = displayed(r)
    Vector("00/9001", "00/9002", "/pid/", "/pers/", "homepages/", "dblp-pid", "dblp_pid", "0002")
      .foreach(bad =>
        assert(
          !shown.exists(_.contains(bad)),
          s"'$bad' is displayed: ${shown.filter(_.contains(bad))}"
        )
      )
    val ids = Vector(r.sourceIdA, r.sourceIdB, r.endpointA.endpointId, r.endpointB.endpointId)
    assert(!ids.exists(_.contains("00/9002")), s"profile endpoint ID shown: $ids")
    assert(ids.exists(_.startsWith(BlindView.MaskedRefPrefix)), s"$ids")
    // Publication evidence stays visible.
    Vector(
      "https://doi.org/10.9999/synthetic.x",
      "https://dblp.org/rec/journals/synthetic/X21",
      "db/journals/synthetic/synthetic21.html#X21",
      "doi:10.9999/synthetic.x",
      "orcid:" + orcid(9)
    ).foreach(k => assert(shown.contains(k), s"publication evidence '$k' was removed"))
  }

  test("constant markers leak no equality: 0001 vs 0002 looks exactly like 0001 vs 0001") {
    def pair(n1: String, n2: String, p1: String, p2: String) = row(
      "researcher",
      mentionEndpoint(mA, s"Quentin Synthwell $n1", identifiers = Vector(s"dblp-pid:$p1")),
      mentionEndpoint(mB, s"Quentin Synthwell $n2", identifiers = Vector(s"dblp-pid:$p2"))
    )
    def display(r: BlindRow) =
      (r.maskingNotes, r.endpointA.copy(endpointId = ""), r.endpointB.copy(endpointId = ""))
    val different = pair("0001", "0002", "00/9001", "00/9002")
    val same = pair("0001", "0001", "00/9001", "00/9001")
    assertEquals(display(different), display(same))
    // And a name with no number at all next to a numbered one is shown identically to two numbered.
    val oneSided = row(
      "researcher",
      mentionEndpoint(mA, "Quentin Synthwell 0001"),
      mentionEndpoint(mB, "Quentin Synthwell")
    )
    assertEquals(oneSided.endpointA.nameAsWritten, oneSided.endpointB.nameAsWritten)
  }

  test("adversarial: a homonym number behind non-ASCII spacing is still masked") {
    // U+00A0 and U+202F are Unicode white space; U+200B ZERO WIDTH SPACE is a format character
    // (category Cf) that renders as nothing, so the display shows "Synthwell0001".
    val spaced = Vector(0x00a0, 0x202f, 0x2007, 0x200b)
      .map(_.toChar.toString)
      .map { sep =>
        sep -> row(
          "researcher",
          mentionEndpoint(mA, s"Quentin Synthwell${sep}0001"),
          mentionEndpoint(mB, "Quentin Synthwell")
        )
      }
    spaced.foreach { case (sep, r) =>
      val shown = displayed(r)
      assert(
        !shown.exists(_.contains("0001")),
        f"homonym number shown after U+${sep.codePointAt(0)}%04X: ${shown.filter(_.contains("0001"))}"
      )
    }
  }

  test("adversarial: a percent-encoded dblp person URL embedded in another link is masked") {
    val r = row(
      "researcher",
      mentionEndpoint(
        mA,
        "Quentin Synthwell",
        eeLinks = Vector(
          "https://redirect.example.org/out?to=https%3A%2F%2Fdblp.org%2Fpid%2F00%2F9001.html"
        )
      ),
      mentionEndpoint(mB, "Quentin Synthwell")
    )
    val shown = displayed(r)
    Vector("00%2F9001", "pid%2F00").foreach(bad =>
      assert(
        !shown.exists(_.contains(bad)),
        s"'$bad' is displayed: ${shown.filter(_.contains(bad))}"
      )
    )
  }

  // ------------------------------------------------------------------ structural separation

  /** Class files of one package of the entity-resolution module, by name. */
  private def classFiles(pkgPath: String): Vector[(String, Array[Byte])] = {
    val loc = classOf[MatchPolicy].getProtectionDomain.getCodeSource.getLocation
    val root = Paths.get(loc.toURI)
    if (Files.isDirectory(root)) {
      val dir = root.resolve(pkgPath)
      val s = Files.list(dir)
      try
        s.iterator.asScala.toVector
          .filter(p => p.toString.endsWith(".class"))
          .map(p => p.getFileName.toString -> Files.readAllBytes(p))
      finally s.close()
    } else {
      val zip = new ZipFile(root.toFile)
      try
        zip.entries.asScala.toVector
          .filter(e =>
            e.getName.startsWith(pkgPath + "/") && e.getName.endsWith(".class") &&
              !e.getName.stripPrefix(pkgPath + "/").contains("/")
          )
          .map(e => e.getName.stripPrefix(pkgPath + "/") -> zip.getInputStream(e).readAllBytes())
      finally zip.close()
    }
  }

  private def references(bytes: Array[Byte], needle: String): Boolean =
    new String(bytes, StandardCharsets.ISO_8859_1).contains(needle)

  private val base = "scholarchain/entityresolution"

  test("compiled matching code never references review, evaluation, or gold-label classes") {
    val matching = Vector(base, s"$base/policy", s"$base/adjudication", s"$base/identifiers")
      .flatMap(p => classFiles(p).map { case (n, b) => (s"$p/$n", b) })
    assert(matching.exists(_._1.endsWith("policy/Resolver$.class")), "scan found no Resolver")
    assert(matching.size > 20, s"too few classes scanned: ${matching.size}")
    val forbidden =
      Vector(s"$base/review/", s"$base/evaluation/", "GoldLabel", "GoldMember", "ReviewQueue")
    for { (name, bytes) <- matching; f <- forbidden }
      assert(!references(bytes, f), s"$name references $f")
  }

  test(
    "compiled membership code never references the policy, resolver, adjudication, or decisions"
  ) {
    val review = classFiles(s"$base/review")
    val membership = review.filter { case (n, _) =>
      Vector("GoldMembership", "GoldMember", "MembershipDesign", "MembershipPlan", "CandidatePair")
        .exists(n.startsWith) || n.startsWith("SelectionUnit") || n.startsWith("MemberEndpoint")
    }
    assert(membership.exists(_._1 == "GoldMembership$.class"), membership.map(_._1).toString)
    val forbidden = Vector(
      s"$base/policy/",
      s"$base/adjudication/",
      s"$base/evaluation/",
      "ReviewQueue",
      "SystemDecision",
      "ResolutionOutcome",
      "scholarchain/core/MatchDecision"
    )
    for { (name, bytes) <- membership; f <- forbidden }
      assert(!references(bytes, f), s"$name references $f")
    // The blind view itself names no evaluation or gold-label class either.
    review.filter(_._1.startsWith("BlindView")).foreach { case (name, bytes) =>
      Vector(s"$base/evaluation/", "GoldLabel", "SystemDecision", s"$base/policy/")
        .foreach(f => assert(!references(bytes, f), s"$name references $f"))
    }
  }
}
