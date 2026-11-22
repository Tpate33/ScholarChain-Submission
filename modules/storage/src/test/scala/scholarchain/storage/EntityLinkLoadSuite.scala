package scholarchain.storage

import scholarchain.core.{AuthorMention, ResearcherAssignment, TargetStore}
import scholarchain.storage.load.*
import scholarchain.storage.model.*
import scholarchain.storage.port.{KeyQuery, SortKeyCondition}
import OpeningFixture.*

/**
 * SE-P9-01: the EntityLinkCache is filled from the entity-resolution outputs of the verified run
 * (grouping-run researcher assignments, curated-run author mentions), through the production entry
 * point [[OpeningLoad.run]] over the real fixtures and in-memory stores, and by the pure
 * [[EntityLinkLoad.plan]] over the same upstream records read by the production readers.
 */
class EntityLinkLoadSuite extends munit.FunSuite {

  /** Test configuration values: an absolute expiry after the fixture clock (1.7e9 s). */
  private val Expires = 1_900_000_000L
  private def linkConfig(expires: Long = Expires, maxLinks: Int = 10000, maxIds: Int = 8) =
    EntityLinkLoadConfig.of(expires, maxLinks, maxIds).fold(e => fail(e.toString), identity)

  private def withLinks(c: EntityLinkLoadConfig = linkConfig()): OpeningLoadConfig =
    config.copy(entityLinks = Some(c))

  test("the shared fixture's entity-link configuration is this suite's default") {
    assertEquals(OpeningFixture.entityLinkConfig, linkConfig())
    assertEquals(OpeningFixture.configWithEntityLinks, withLinks())
  }

  private lazy val grouping =
    UpstreamRuns
      .grouping(
        Fixtures.source(UpstreamFixture.groupingDir, UpstreamFixture.groupingBounds),
        UpstreamFixture.upstreamBounds
      )
      .fold(e => fail(e.mkString("; ")), identity)

  private lazy val curated =
    UpstreamRuns
      .curated(
        Fixtures.source(UpstreamFixture.curatedDir, UpstreamFixture.curatedBounds),
        UpstreamFixture.upstreamBounds
      )
      .fold(e => fail(e.mkString("; ")), identity)

  private val LoadId = "load:" + "7" * 64

  private def planOf(
      assignments: Vector[ResearcherAssignment] = grouping.graphInput.researchers,
      mentions: Vector[AuthorMention] = curated.mentions,
      c: EntityLinkLoadConfig = linkConfig()
  ) =
    EntityLinkLoad.plan(
      Fixtures.RunId,
      LoadId,
      grouping.runId,
      curated.runId,
      assignments,
      mentions,
      c
    )

  private def surfaceRows(w: World, name: String): Vector[EntityLinkRecord] =
    TableKeys.EntityLinkCache
      .partition(EntityLinkSurfaceForm.sha256(name))
      .flatMap(p =>
        w.kvStore.query(TableRole.EntityLinkCache, KeyQuery(None, p, SortKeyCondition.All, 100))
      )
      .fold(e => fail(e.toString), identity)
      .map(i => ItemCodecs.EntityLinkCodec.decode(i).fold(e => fail(e.toString), identity))

  /** An identified mention of the fixture and its researcher. */
  private lazy val namedMentions: Vector[(AuthorMention, String)] = {
    val byId = curated.mentions.map(m => m.mentionId -> m).toMap
    grouping.graphInput.researchers.flatMap(a =>
      a.researcherId.flatMap(r => byId.get(a.memberId).map(_ -> r))
    )
  }

  // ------------------------------------------------------------------ normalization

  test("surface form v1: the rules of the runtime lookup, byte for byte") {
    val N = EntityLinkSurfaceForm.normalize
    assertEquals(EntityLinkSurfaceForm.Version, "scholarchain.rag.surface-form.v1")
    assertEquals(N("  John   SMITH "), "john smith")
    assertEquals(N("Smith, J."), "smith j")
    assertEquals(N("A.;B:C,D"), "a b c d")
    assertEquals(N("ﬁsher"), "fisher", "NFKC folds the ligature")
    assertEquals(N("Ｊｏｈｎ"), "john", "NFKC folds full-width letters")
    assertEquals(N("José García"), "josé garcía", "diacritics kept")
    assertEquals(N("José"), "josé", "NFKC composes")
    assertEquals(N("İstanbul"), "i̇stanbul", "ROOT-locale lower case")
    assertEquals(N("a\t\n b"), "a b")
    assertEquals(N("a b"), "a b", "NFKC maps NBSP to a space")
    assertEquals(N(null), "")
    assertEquals(N(" .,;: "), "")
    assertEquals(
      EntityLinkSurfaceForm.sha256("John Smith"),
      scholarchain.core.Canonical.sha256HexUtf8("john smith")
    )
  }

  // ------------------------------------------------------------------ plan

  test("the fixture upstream gives one ranked row per (surface form, researcher), with evidence") {
    val p = planOf().fold(e => fail(e.toString), identity)
    println(
      s"SE-P9-01 fixture entity links: rows=${p.links.size} surfaceForms=${p.surfaceForms} " +
        s"ambiguous=${p.ambiguousSurfaceForms} emptyNames=${p.skippedEmptyNames} " +
        s"identifiedMentions=${namedMentions.size}"
    )
    assert(p.links.nonEmpty)
    assertEquals(p.links.map(l => (l.surfaceSha256, l.researcherId)).distinct.size, p.links.size)
    assertEquals(p.links.map(_.surfaceSha256).distinct.size, p.surfaceForms)
    // Every identified mention is covered by the link of its own name and researcher.
    val keys = p.links.map(l => (l.surfaceSha256, l.researcherId)).toSet
    namedMentions.foreach { case (m, r) =>
      if (EntityLinkSurfaceForm.normalize(m.rawName).nonEmpty)
        assert(keys.contains((EntityLinkSurfaceForm.sha256(m.rawName), r)), m.mentionId)
    }
    p.links.groupBy(_.surfaceSha256).values.foreach { rows =>
      assertEquals(rows.map(_.candidateRank).sorted, (1 to rows.size).toVector)
    }
    p.links.foreach { l =>
      assertEquals(
        (l.sourceRunId, l.expiresAtEpochSeconds, l.matchedBy),
        (Fixtures.RunId, Expires, EntityLinkLoad.MatchedBy)
      )
      val e = l.evidence.getOrElse(fail("no evidence"))
      assertEquals(EntityLinkEvidence.problems(e), Vector.empty)
      assertEquals(
        (e.loadId, e.groupingRunId, e.curatedRunId),
        (LoadId, grouping.runId, curated.runId)
      )
      assert(e.sourceDatasets.nonEmpty && e.snapshots.nonEmpty)
      assert(e.mentionIds.size <= 8 && e.mentionIds.size <= e.mentionCount)
      assert(ItemCodecs.EntityLinkCodec.encode(l).isRight, l.toString)
    }
    // Deterministic.
    assertEquals(planOf(), Right(p))
  }

  test("an ambiguous name yields one row per researcher, never collapsed") {
    val distinctResearchers =
      namedMentions.groupBy(_._2).values.map(_.head).toVector.sortBy(_._1.mentionId).take(3)
    assertEquals(distinctResearchers.map(_._2).distinct.size, 3, "three different researchers")
    val name = "Ambiguous Q. Name"
    // Three spellings of one surface form, one per researcher.
    val spellings = Vector(name, "  ambiguous q  NAME ", "AMBIGUOUS Q, Name")
    val renamed = distinctResearchers.map(_._1.mentionId).zip(spellings).toMap
    val mentions =
      curated.mentions.map(m => renamed.get(m.mentionId).fold(m)(n => m.copy(rawName = n)))
    val p = planOf(mentions = mentions).fold(e => fail(e.toString), identity)
    val rows = p.links.filter(_.surfaceSha256 == EntityLinkSurfaceForm.sha256(name))
    assertEquals(rows.map(_.researcherId).toSet, distinctResearchers.map(_._2).toSet)
    assertEquals(rows.map(_.candidateRank).sorted, Vector(1, 2, 3))
    assert(p.ambiguousSurfaceForms >= 1)
  }

  test("plan refusals: an identified mention without its author mention; too many links") {
    val dropped = curated.mentions.filterNot(_.mentionId == namedMentions.head._1.mentionId)
    assert(planOf(mentions = dropped).left.exists(_.message.contains("no author mention")))
    assert(planOf(c = linkConfig(maxLinks = 1)).left.exists(_.message.contains("exceed maxLinks")))
    assert(EntityLinkLoadConfig.of(0L, 1, 1).isLeft)
    assert(EntityLinkLoadConfig.of(1L, 0, 1).isLeft)
    assert(EntityLinkLoadConfig.of(1L, 1, 0).isLeft)
  }

  // ------------------------------------------------------------------ the production load path

  private lazy val first: (World, OpeningLoadResult) = {
    val w = new World
    (w, OpeningLoad.run(sources(), withLinks(), w.targets()))
  }

  private def count(s: StoreLoadOutcome, name: String): Long =
    s.counts.find(_._1 == name).map(_._2).getOrElse(fail(s"no count $name in ${s.counts}"))

  test("the local load fills EntityLinkCache; the dynamodb store reports the rows") {
    val (w, res) = first
    val d = res.report.store(TargetStore.DynamoDb)
    println(s"SE-P9-01 dynamodb with entity links: ${d.status.tag} ${d.counts}")
    assert(res.report.complete, res.report.stores.map(s => s.target.tag -> s.error).toString)
    assertEquals(d.status, StoreLoadStatus.Loaded)
    val rows = count(d, "entity-link-rows")
    assert(rows > 0)
    assertEquals(count(d, "entity-link-written"), rows)
    assertEquals(count(d, "entity-link-present"), 0L)
    assertEquals((d.written, d.alreadyPresent), (1302L + rows, 0L))
    assertEquals(w.kvStore.itemCount(TableRole.EntityLinkCache), rows.toInt)
    assertEquals(w.kvStore.itemCount(TableRole.ResearcherMetricCache), 1302)
    // Equal to the pure plan of the same upstream.
    val p = planOf().fold(e => fail(e.toString), identity)
    assertEquals(
      (p.links.size.toLong, p.surfaceForms.toLong, p.ambiguousSurfaceForms.toLong),
      (
        rows,
        count(d, "entity-link-surface-forms"),
        count(d, "entity-link-ambiguous-surface-forms")
      )
    )
    // The runtime lookup: one query per surface form returns the name's researcher, this run.
    val (m, r) = namedMentions.head
    val found = surfaceRows(w, m.rawName)
    assert(found.exists(_.researcherId == r), found.toString)
    found.foreach(l => assertEquals(l.sourceRunId, Fixtures.RunId))
    // The runtime view reads the same rows.
    val rt = res.runtime.getOrElse(fail("no runtime view"))
    val q =
      TableKeys.EntityLinkCache.partition(EntityLinkSurfaceForm.sha256(m.rawName)).toOption.get
    assertEquals(
      rt.keyValues.query(TableRole.EntityLinkCache, KeyQuery(None, q, SortKeyCondition.All, 10)),
      w.kvStore.query(TableRole.EntityLinkCache, KeyQuery(None, q, SortKeyCondition.All, 10))
    )
    // RunLedger: 4 owners, 1 publication, 4 store loads + 1 entity-link load, all loaded.
    assertEquals(w.ledgerStore.itemCount(TableRole.RunLedger), 10)
    val loads = w.ledgerStore
      .query(
        TableRole.RunLedger,
        KeyQuery(None, "run#" + Fixtures.RunId, SortKeyCondition.BeginsWith("load#"), 20)
      )
      .fold(e => fail(e.toString), identity)
      .map(i => ItemCodecs.LoadEntryCodec.decode(i).fold(e => fail(e.toString), identity))
    assertEquals(loads.size, 5)
    assert(loads.forall(_.state == LoadState.Loaded), loads.toString)
    val dynamo = loads.filter(_.targetStore == TargetStore.DynamoDb)
    assertEquals(dynamo.size, 2, "the dynamodb manifest load and the entity-link load")
    val e = dynamo.find(_.written == rows).getOrElse(fail(s"no entity-link ledger row in $dynamo"))
    assertEquals((e.written, e.alreadyPresent, e.itemCount.toLong), (rows, 0L, rows))
    // The stored links name that ledger row's load ID.
    assertEquals(found.flatMap(_.evidence).map(_.loadId).distinct, Vector(e.loadId))
  }

  test("a rerun is idempotent: same ledger skipped with zero writes; fresh ledger all present") {
    val (w, _) = first
    val before = (w.kv.counts.calls, w.kvStore.itemCount(TableRole.EntityLinkCache))
    val again = OpeningLoad.run(sources(), withLinks(), w.targets()).report
    assertEquals(again.store(TargetStore.DynamoDb).status, StoreLoadStatus.Skipped)
    assertEquals(again.store(TargetStore.DynamoDb).written, 0L)
    assertEquals((w.kv.counts.calls, w.kvStore.itemCount(TableRole.EntityLinkCache)), before)
    val fresh = new fake.RecordingKeyValueStore(new fake.InMemoryKeyValueStore(1000))
    val r = OpeningLoad.run(sources(), withLinks(), w.targets(ledgerStore = fresh)).report
    val d = r.store(TargetStore.DynamoDb)
    val rows = count(d, "entity-link-rows")
    assertEquals((d.status, count(d, "entity-link-written")), (StoreLoadStatus.Loaded, 0L))
    assertEquals(count(d, "entity-link-present"), rows)
    assertEquals(w.kvStore.itemCount(TableRole.EntityLinkCache), before._2)
  }

  /** Plants one link item in the world's key-value store (bypassing the recorder). */
  private def plant(w: World, rec: EntityLinkRecord): Unit =
    ItemCodecs.EntityLinkCodec
      .encode(rec)
      .flatMap(w.kvStore.putIfAbsent(TableRole.EntityLinkCache, _))
      .fold(e => fail(e.toString), _ => ())

  private lazy val oneLink: EntityLinkRecord = {
    val (_, res) = first
    val (w, _) = first
    val (m, _) = namedMentions.head
    assert(res.report.complete)
    surfaceRows(w, m.rawName).head
  }

  test("one key and run cannot silently point at another record: different body is refused") {
    val stored = oneLink
    val w = new World
    val tampered = stored.copy(candidateRank = stored.candidateRank + 7)
    plant(w, tampered)
    val d = OpeningLoad.run(sources(), withLinks(), w.targets()).report.store(TargetStore.DynamoDb)
    assertEquals(d.status, StoreLoadStatus.Failed)
    assert(
      d.error.exists(e => e.isInstanceOf[StoreError.Conflict] && e.message.contains("pre-write")),
      d.error.toString
    )
    assertEquals(w.kvStore.itemCount(TableRole.EntityLinkCache), 1, "no link written")
    assertEquals(
      surfaceRows(w, namedMentions.head._1.rawName),
      Vector(tampered),
      "never overwritten"
    )
  }

  test("a surface form and run cannot silently point at another researcher: refused") {
    val stored = oneLink
    val w = new World
    val other =
      scholarchain.core.ResearcherId.scholarChain("v1", "dblp-pid", "999/9999").toOption.get
    assert(!namedMentions.exists(_._2 == other))
    val planted = stored.copy(researcherId = other, evidence = None)
    plant(w, planted)
    val d = OpeningLoad.run(sources(), withLinks(), w.targets()).report.store(TargetStore.DynamoDb)
    assertEquals(d.status, StoreLoadStatus.Failed)
    assert(
      d.error.exists(e =>
        e.isInstanceOf[StoreError.Conflict] && e.message.contains("another researcher set")
      ),
      d.error.toString
    )
    assertEquals(w.kvStore.itemCount(TableRole.EntityLinkCache), 1, "no link written")
  }

  test("an expiry not after the injected clock refuses the entity links, zero link writes") {
    val w = new World
    val now = w.clock.nowMillis() / 1000L
    val d = OpeningLoad
      .run(sources(), withLinks(linkConfig(expires = now)), w.targets())
      .report
      .store(TargetStore.DynamoDb)
    assertEquals(d.status, StoreLoadStatus.Failed)
    assert(d.error.exists(_.message.contains("already be expired")), d.error.toString)
    assertEquals(w.kvStore.itemCount(TableRole.EntityLinkCache), 0)
  }

  test("without an entity-link configuration no link is written (unchanged behaviour)") {
    val w = new World
    val r = OpeningLoad.run(sources(), config, w.targets()).report
    assert(r.complete)
    assertEquals(w.kvStore.itemCount(TableRole.EntityLinkCache), 0)
    assert(!r.store(TargetStore.DynamoDb).counts.exists(_._1.startsWith("entity-link")))
  }

  // ------------------------------------------------------------------ codec

  test("codec: a link without evidence keeps the pre-SE-P9-01 body; evidence is validated") {
    val bare = EntityLinkRecord(
      EntityLinkRecord.SchemaVersion,
      "a" * 64,
      namedMentions.head._2,
      "exact-name",
      1,
      Fixtures.RunId,
      Expires
    )
    val item = ItemCodecs.EntityLinkCodec.encode(bare).fold(e => fail(e.toString), identity)
    assertEquals(
      AttrValue.string(item, Attrs.Body),
      Right(
        s"""{"schemaVersion":"scholarchain.storage.entity-link.v1","surfaceSha256":"${"a" * 64}",""" +
          s""""researcherId":"${namedMentions.head._2}","matchedBy":"exact-name","candidateRank":1,""" +
          s""""sourceRunId":"${Fixtures.RunId}","expiresAtEpochSeconds":$Expires}"""
      )
    )
    assertEquals(ItemCodecs.EntityLinkCodec.decode(item), Right(bare))
    val withEvidence = oneLink
    val enc = ItemCodecs.EntityLinkCodec.encode(withEvidence).fold(e => fail(e.toString), identity)
    assertEquals(ItemCodecs.EntityLinkCodec.decode(enc), Right(withEvidence))
    val e = withEvidence.evidence.get
    val bad = Vector(
      e.copy(mentionIds = Vector.empty),
      e.copy(mentionIds = e.mentionIds.reverse :+ e.mentionIds.head),
      e.copy(mentionCount = e.mentionIds.size, mentionIdsSha256 = "b" * 64),
      e.copy(sourceDatasets = Vector.empty),
      e.copy(surfaceFormVersion = "scholarchain.rag.surface-form.v2"),
      e.copy(loadId = "load:x")
    )
    bad.foreach(b =>
      assert(
        ItemCodecs.EntityLinkCodec.encode(withEvidence.copy(evidence = Some(b))).isLeft,
        b.toString
      )
    )
  }
}
