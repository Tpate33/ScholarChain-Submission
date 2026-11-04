package scholarchain.core

import scala.collection.immutable.SortedMap

import Fixtures.*

class StableIdSuite extends munit.FunSuite {

  test("work IDs are namespace plus authoritative source ID") {
    assertEquals(
      WorkId.dblp("journals/cacm/PopperMP21"),
      Right("work:dblp:journals/cacm/PopperMP21")
    )
    assertEquals(WorkId.doi("https://doi.org/10.1145/2491245"), Right("work:doi:10.1145/2491245"))
    assertEquals(
      WorkId.openAlex("https://openalex.org/W3137759927"),
      Right("work:openalex:W3137759927")
    )
    assertEquals(
      WorkId.orcidWork("0000-0002-1825-0097", 107628996L),
      Right("work:orcid-work:0000-0002-1825-0097/work/107628996")
    )
    assert(WorkId.dblp("not a key").isLeft)
    assert(WorkId.openAlex("A5113870699").isLeft)
  }

  test("researcher ID uses a validated ORCID iD") {
    assertEquals(
      ResearcherId.orcid("https://orcid.org/0000-0002-1825-0097"),
      Right("researcher:orcid:0000-0002-1825-0097")
    )
    assert(ResearcherId.orcid("0000-0002-1825-0098").isLeft, "bad check digit is not validated")
  }

  test("ScholarChain identity ID is versioned and golden") {
    val id = ResearcherId.scholarChain("v1", "dblp-pid", "00/0001")
    assertEquals(
      id,
      Right("researcher:sc:v1:5813d4e998bfb50c42df21805dd740a646ea877ab33895baca2cdd24a681fb46")
    )
    assertNotEquals(ResearcherId.scholarChain("v2", "dblp-pid", "00/0001"), id)
    assert(ResearcherId.scholarChain("1", "dblp-pid", "00/0001").isLeft)
    assert(ResearcherId.scholarChain("v1", "dblp pid", "00/0001").isLeft)
  }

  test("coauthor edge ID is SHA-256 of the documented netstring encoding") {
    val encoding =
      "29:scholarchain.coauthor-edge.v1," +
        "36:researcher:orcid:0000-0000-0000-0001," +
        "36:researcher:orcid:0000-0000-0000-001X," +
        "4:2015,4:2024," +
        "18:graph-policy-1.0.0,"
    assertEquals(coauthorEdge.edgeId, "coauthor:" + Canonical.sha256HexUtf8(encoding))
    assertEquals(
      coauthorEdge.edgeId,
      "coauthor:cd7c4e8bd9050455fb19cf46ce86ecd73659cbe3c0a9bce3e4206e1596f23071"
    )
  }

  test("mention ID is SHA-256 of the documented netstring encoding") {
    val encoding =
      "23:scholarchain.mention.v1," +
        "38:work:dblp:journals/example/Synthetic21," +
        "1:0,"
    assertEquals(mention0.mentionId, "mention:" + Canonical.sha256HexUtf8(encoding))
    assertEquals(
      mention0.mentionId,
      "mention:4d99b473c7f1d066094cb66ae71ded0bb0305eea0b75006f46ff2fd12c4c0bc4"
    )
  }

  private val RunLit = "run:8dc188c7670e600b1e1f8bf679a11809bd4ebadbd4a9ed27d27e318a59a88614"

  test("candidate block ID is SHA-256 of the documented netstring encoding") {
    val encoding =
      "31:scholarchain.candidate-block.v1," +
        "10:researcher," +
        "13:er-keys-1.0.0," +
        "1:0," +
        "9:example|a," +
        s"68:$RunLit,"
    assertEquals(block.blockId, "block:" + Canonical.sha256HexUtf8(encoding))
    assertEquals(
      block.blockId,
      "block:5e4eb70030f3c1fa0e7f973c21ea1400787f7b38edc34f965f7bbe010f3713d6"
    )
  }

  test("match candidate ID is SHA-256 of the documented netstring encoding (canonical pair)") {
    val encoding =
      "31:scholarchain.match-candidate.v1," +
        "10:researcher," +
        "72:mention:4d99b473c7f1d066094cb66ae71ded0bb0305eea0b75006f46ff2fd12c4c0bc4," +
        "25:source-author:openalex:A1," +
        "13:er-keys-1.0.0," +
        "17:er-features-1.0.0," +
        s"68:$RunLit,"
    assertEquals(matchCandidate.candidateId, "mcand:" + Canonical.sha256HexUtf8(encoding))
    assertEquals(
      matchCandidate.candidateId,
      "mcand:400ad93720eceb8fc152cf580854ebf0788262cd4b4d446ec53a51dbed53d300"
    )
  }

  test("identity link ID is SHA-256 of the documented netstring encoding") {
    val encoding =
      "29:scholarchain.identity-link.v1," +
        "5:orcid," +
        "19:0000-0000-0000-0001," +
        "10:researcher," +
        "72:mention:4d99b473c7f1d066094cb66ae71ded0bb0305eea0b75006f46ff2fd12c4c0bc4," +
        "38:work:dblp:journals/example/Synthetic21," +
        "4:dblp," +
        s"68:$RunLit,"
    assertEquals(mentionLink.linkId, "idlink:" + Canonical.sha256HexUtf8(encoding))
    assertEquals(
      mentionLink.linkId,
      "idlink:91a4645916260ea98c3855b3c3be3f6e2153e7d2f6f4151afe3f57b1f76c5dbe"
    )
    assertEquals(
      claimLink.linkId,
      "idlink:24c2c226c444f9e083343853ae922c343bf29b7e678d8e0133489d032288f04e"
    )
    val noWork =
      "29:scholarchain.identity-link.v1,3:doi,24:10.1234/example.2021.001,4:work," +
        "38:work:dblp:journals/example/Synthetic21,0:,4:dblp," + s"68:$RunLit,"
    assertEquals(
      IdentityLinkId.of(
        ExactId(ExactIdScheme.Doi, "10.1234/example.2021.001"),
        MatchEntityKind.Work,
        workId,
        None,
        SourceType.Dblp,
        runId
      ),
      Right("idlink:" + Canonical.sha256HexUtf8(noWork)),
      "an absent workId is encoded as the empty field"
    )
  }

  test("new hash IDs change with every documented input") {
    val pair = ok(CanonicalPair.of(mention0.mentionId, openAlexProfileId))
    def mc(k: MatchEntityKind = MatchEntityKind.Researcher, b: String = blockingVersion)(
        f: String = featureVersion,
        r: String = runId
    ) = ok(MatchCandidateId.of(k, pair, b, f, r))
    val base = mc()()
    assertNotEquals(mc(k = MatchEntityKind.Work)(), base)
    assertNotEquals(mc(b = "er-keys-2.0.0")(), base)
    assertNotEquals(mc()(f = "er-features-2.0.0"), base)
    assertNotEquals(mc()(r = ok(RunId.of(manifest.copy(attempt = 2)))), base)
    assert(
      MatchCandidateId
        .of(MatchEntityKind.Researcher, pair, "bad version", featureVersion, runId)
        .isLeft
    )

    def bl(level: Int = 0, key: String = "example|a") =
      CandidateBlockId.of(MatchEntityKind.Researcher, blockingVersion, level, key, runId)
    assertNotEquals(bl(level = 1), bl())
    assertNotEquals(bl(key = "example|b"), bl())
    assert(bl(level = -1).isLeft)
    assert(bl(key = "tab\there").isLeft, "control characters are not valid key text")
    assert(bl(key = " ").isLeft)
  }

  test("source author profile IDs are namespace plus the source's canonical author ID") {
    assertEquals(
      SourceAuthorId.openAlex("https://openalex.org/A5113870699"),
      Right("source-author:openalex:A5113870699")
    )
    assertEquals(
      SourceAuthorId.orcid("https://orcid.org/0000-0002-1825-0097"),
      Right("source-author:orcid:0000-0002-1825-0097")
    )
    assertEquals(
      SourceAuthorId.of("dblp-pid", "69/6042-1"),
      Right("source-author:dblp-pid:69/6042-1")
    )
    assert(SourceAuthorId.openAlex("W1").isLeft, "a work ID is not an author ID")
    assert(SourceAuthorId.orcid("0000-0002-1825-0098").isLeft, "checksum is verified")
    assert(SourceAuthorId.of("crossref", "x").isLeft, "unknown namespace")
    assertEquals(
      SourceAuthorId.sourceOf("source-author:orcid:0000-0002-1825-0097"),
      Some(SourceType.Orcid)
    )
    assertEquals(SourceAuthorId.sourceOf("source-author:dblp-pid:69/6042-1"), Some(SourceType.Dblp))
    assertEquals(SourceAuthorId.sourceOf("researcher:orcid:0000-0002-1825-0097"), None)
  }

  test("graph edge ID is SHA-256 of the documented netstring encoding") {
    val encoding =
      "26:scholarchain.graph-edge.v1," +
        "15:COAUTHORED_WITH," +
        "36:researcher:orcid:0000-0000-0000-0001," +
        "36:researcher:orcid:0000-0000-0000-001X," +
        "18:graph-policy-1.0.0," +
        "73:coauthor:cd7c4e8bd9050455fb19cf46ce86ecd73659cbe3c0a9bce3e4206e1596f23071,"
    assertEquals(graphEdge.edgeId, "gedge:" + Canonical.sha256HexUtf8(encoding))
    assertEquals(
      graphEdge.edgeId,
      "gedge:d1b7e4740a1812ec2080af060fa4486fffa9ccf3c2a49417dbcd3fadf0dd11c0"
    )
  }

  test("coauthor edge ID changes with window and policy version") {
    val pair = ok(CanonicalPair.researchers(researcherA, researcherB))
    val base = ok(CoauthorEdgeId.of(pair, window, graphPolicy))
    assertNotEquals(ok(CoauthorEdgeId.of(pair, TimeWindow(2015, 2023), graphPolicy)), base)
    assertNotEquals(ok(CoauthorEdgeId.of(pair, window, "graph-policy-2.0.0")), base)
    assertEquals(ok(CoauthorEdgeId.of(pair, window, graphPolicy)), base)
    assert(CoauthorEdgeId.of(pair, TimeWindow(2025, 2024), graphPolicy).isLeft)
  }

  test("golden hash IDs are stable across runs and JVMs") {
    assertEquals(runId, "run:8dc188c7670e600b1e1f8bf679a11809bd4ebadbd4a9ed27d27e318a59a88614")
    assertEquals(
      mention0.mentionId,
      "mention:4d99b473c7f1d066094cb66ae71ded0bb0305eea0b75006f46ff2fd12c4c0bc4"
    )
    assertEquals(
      graphEdge.edgeId,
      "gedge:d1b7e4740a1812ec2080af060fa4486fffa9ccf3c2a49417dbcd3fadf0dd11c0"
    )
    assertEquals(
      ChunkId.of(researcherA, "profile-v1", DocumentType.ResearcherProfile, Some(0), "hello"),
      Right("chunk:27cc80b0f1b446873bd96b0f2a0d7e7b9927642337e1837e36333987c344b625")
    )
    assertEquals(
      evidenceWork.evidenceId,
      "ev:a23c2deeb69843d6d7c61d20b74106c611b2ca9811701c05eb9edd7ac9dcf425"
    )
    assertEquals(
      matchDecision.decisionId,
      "match:9d9d899ab153f9836f40ae28d20a71913ca8bc1f2a0313b6a15d124467f65670"
    )
    assertEquals(
      bundle.bundleId,
      "bundle:29c5fb7e497fc51e84c0e09a623484d12e2a54d6dd89850c18a6f7bcde870af9"
    )
    assertEquals(
      claim.claimId,
      "claim:89b2d877ad2813d8bb282c38da17e97201ff36b55ac4a71ac95146e8a7fa99f1"
    )
    assertEquals(
      answer.responseId,
      "answer:14cd1802bed530ed707b5b162772446cf925a11557853705770a9a1bfe9423f3"
    )
  }

  test("chunk ID depends on every documented input") {
    def id(ord: Option[Int], text: String, version: String = "profile-v1") =
      ok(ChunkId.of(researcherA, version, DocumentType.ResearcherProfile, ord, text))
    val base = id(Some(0), "hello")
    assertNotEquals(id(None, "hello"), base)
    assertNotEquals(id(Some(1), "hello"), base)
    assertNotEquals(id(Some(0), "hello!"), base)
    assertNotEquals(id(Some(0), "hello", "profile-v2"), base)
    assertNotEquals(
      ok(ChunkId.of(researcherA, "profile-v1", DocumentType.WorkAbstract, Some(0), "hello")),
      base
    )
    assert(
      ChunkId.of(researcherA, "profile-v1", DocumentType.ResearcherProfile, Some(-1), "x").isLeft
    )
    assert(ChunkId.of("not-an-id", "profile-v1", DocumentType.ResearcherProfile, None, "x").isLeft)
  }

  test("graph edge ID: undirected types ignore endpoint order, directed types do not") {
    val ab = GraphEdgeId.of(EdgeLabel.CoauthoredWith, researcherA, researcherB, graphPolicy, "e")
    val ba = GraphEdgeId.of(EdgeLabel.CoauthoredWith, researcherB, researcherA, graphPolicy, "e")
    assertEquals(ab, ba)
    val d1 = GraphEdgeId.of(EdgeLabel.ResolvesTo, mention0.mentionId, researcherA, graphPolicy, "e")
    val d2 = GraphEdgeId.of(EdgeLabel.ResolvesTo, researcherA, mention0.mentionId, graphPolicy, "e")
    assertNotEquals(d1, d2)
    assertNotEquals(
      GraphEdgeId.of(EdgeLabel.CoauthoredWith, researcherA, researcherB, graphPolicy, "f"),
      ab
    )
    assert(
      GraphEdgeId.of(EdgeLabel.CoauthoredWith, researcherA, researcherA, graphPolicy, "e").isLeft
    )
  }

  test("run ID is timestamp-independent and derived only from the manifest") {
    assertEquals(ok(RunId.of(manifest)), runId)
    val twoInputs = manifest.copy(inputSnapshots =
      Vector(
        SnapshotRef(SourceType.OpenAlex, "openalex-2026-10-01"),
        SnapshotRef(SourceType.Dblp, "dblp-2026-10-03")
      )
    )
    val reordered = twoInputs.copy(inputSnapshots = twoInputs.inputSnapshots.reverse)
    assertEquals(RunId.of(twoInputs), RunId.of(reordered), "input order must not matter")
    assertNotEquals(ok(RunId.of(manifest.copy(attempt = 2))), runId, "re-run gets a new ID")
    assertNotEquals(
      ok(RunId.of(manifest.copy(parameters = SortedMap("maxRecords" -> "1001")))),
      runId
    )
    assert(RunId.of(manifest.copy(attempt = 0)).isLeft)
    assert(RunId.of(manifest.copy(codeVersion = "abc")).isLeft)
    assert(RunId.of(manifest.copy(schemaVersion = "scholarchain.run-manifest.v9")).isLeft)
  }

  test("bundle ID does not depend on item order") {
    val ids = Vector(evidenceWork.evidenceId, evidenceEdge.evidenceId)
    assertEquals(BundleId.of(queryId, ids), BundleId.of(queryId, ids.reverse))
    assertNotEquals(BundleId.of(queryId, ids), BundleId.of(queryId, ids.take(1)))
  }

  test("malformed stable IDs are rejected by kind") {
    val hex = "0" * 64
    val bad: Vector[(IdKind, String)] = Vector(
      IdKind.Work -> "work:dblp:",
      IdKind.Work -> "work:isbn:123",
      IdKind.Work -> "work:doi:10.1145/ABC",
      IdKind.Work -> "work:dblp:journals/x y",
      IdKind.Work -> "dblp:journals/x/y",
      IdKind.Researcher -> "researcher:orcid:0000-0002-1825-0098",
      IdKind.Researcher -> s"researcher:sc:1:$hex",
      IdKind.Researcher -> s"researcher:sc:v1:${hex.drop(1)}",
      IdKind.Researcher -> "researcher:dblp:11/3019",
      IdKind.CoauthorEdge -> "coauthor:XYZ",
      IdKind.CoauthorEdge -> s"coauthor:${"A" * 64}",
      IdKind.Run -> "run:",
      IdKind.Run -> s"run: $hex",
      IdKind.Mention -> s"coauthor:$hex",
      IdKind.Venue -> "venue:issn:0000-0018",
      IdKind.Institution -> "institution:ror:00e5k082",
      IdKind.Topic -> "topic:openalex:W1",
      IdKind.Snapshot -> "snapshot:scholarchain-derived:x",
      IdKind.Query -> "query:",
      IdKind.Work -> "",
      IdKind.Work -> null
    )
    bad.foreach { case (kind, id) =>
      StableId.validate(kind, id, "f") match {
        case Left(_: ValidationError.MalformedId) => ()
        case other => fail(s"expected MalformedId for ${kind.prefix} '$id', got $other")
      }
    }
  }

  test("valid IDs of every kind are accepted and classified") {
    val good: Vector[(IdKind, String)] = Vector(
      IdKind.Work -> workId,
      IdKind.Researcher -> researcherA,
      IdKind.Mention -> mention0.mentionId,
      IdKind.Venue -> "venue:issn:0000-0019",
      IdKind.Institution -> "institution:ror:00e5k0821",
      IdKind.Topic -> "topic:openalex:T10772",
      IdKind.Snapshot -> ok(SnapshotId.of(SourceType.Dblp, "dblp-2026-10-03")),
      IdKind.Query -> queryId,
      IdKind.Run -> runId,
      IdKind.CoauthorEdge -> coauthorEdge.edgeId,
      IdKind.GraphEdge -> graphEdge.edgeId,
      IdKind.Evidence -> evidenceWork.evidenceId,
      IdKind.MatchDecision -> matchDecision.decisionId,
      IdKind.Bundle -> bundle.bundleId,
      IdKind.Claim -> claim.claimId,
      IdKind.Answer -> answer.responseId,
      IdKind.SourceAuthor -> openAlexProfileId,
      IdKind.MatchCandidate -> matchCandidate.candidateId,
      IdKind.CandidateBlock -> block.blockId,
      IdKind.IdentityLink -> mentionLink.linkId
    )
    good.foreach { case (kind, id) =>
      assertEquals(StableId.validate(kind, id), Right(id), s"${kind.prefix} $id")
      assertEquals(StableId.validateAny(id), Right(kind), id)
    }
    assert(StableId.validateAny("unknown:abc").isLeft)
  }
}
