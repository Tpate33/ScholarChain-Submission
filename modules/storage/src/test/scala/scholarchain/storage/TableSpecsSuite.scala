package scholarchain.storage

import scholarchain.storage.model.*
import TestData.*

/** Suite 8 (SE-P7-01): table keys, GSIs, TTL policy and the access-pattern registry. */
class TableSpecsSuite extends munit.FunSuite {

  test("exactly the six tables of README L1381-1386, each with string pk and sk") {
    assertEquals(
      TableSpecs.all.map(_.role.tag).toSet,
      Set(
        "RunLedger",
        "EntityLinkCache",
        "QuerySession",
        "EvidenceBundle",
        "ArtifactRegistry",
        "ResearcherMetricCache"
      )
    )
    assertEquals(TableSpecs.all.map(_.role).distinct.size, 6)
    TableSpecs.all.foreach { t =>
      assertEquals((t.partitionKey, t.sortKey), ("pk", "sk"), t.role.tag)
      assert(t.justification.nonEmpty, t.role.tag)
    }
    TableRole.values.foreach(r => assertEquals(TableSpecs.of(r).role, r))
  }

  test("GSIs are exactly as designed") {
    def names(t: TableSpec) = t.indexes.map(_.name).toSet
    assertEquals(names(TableSpecs.RunLedger), Set("byLoad"))
    assertEquals(names(TableSpecs.ArtifactRegistry), Set("bySha256"))
    assertEquals(names(TableSpecs.EvidenceBundle), Set("byQuery", "byEvidence"))
    assertEquals(names(TableSpecs.QuerySession), Set.empty[String])
    assertEquals(names(TableSpecs.EntityLinkCache), Set("byResearcher"))
    assertEquals(names(TableSpecs.ResearcherMetricCache), Set("byGroup", "byPair", "rank"))
  }

  test("TTL applies only to QuerySession and EntityLinkCache, each with a justification") {
    val withTtl = TableSpecs.all.filter(_.ttlAttribute.isDefined).map(_.role).toSet
    assertEquals(withTtl, Set(TableRole.QuerySession, TableRole.EntityLinkCache))
    TableSpecs.all.foreach { t =>
      t.ttl match {
        case TtlPolicy.Ttl(a, why) =>
          assertEquals(a, "expiresAt")
          assert(
            why.contains("disposable") || why.contains("recomputable") || why.contains("losing")
          )
        case TtlPolicy.NoTtl(why) => assert(why.nonEmpty, t.role.tag)
      }
    }
  }

  test("only the three runtime tables are runtime-writable") {
    assertEquals(
      TableRole.values.filter(_.runtimeWritable).toSet,
      Set(TableRole.QuerySession, TableRole.EntityLinkCache, TableRole.EvidenceBundle)
    )
  }

  test("every access pattern is served by the table key or a declared GSI, never a scan") {
    val ids = TableSpecs.all.flatMap(_.accessPatterns.map(_.id))
    assertEquals(ids.distinct.size, ids.size, "pattern IDs are unique")
    TableSpecs.all.foreach { t =>
      assert(t.accessPatterns.nonEmpty, s"${t.role.tag} states its access patterns")
      t.accessPatterns.foreach { p =>
        assert(t.keyAttributes(p.index).isDefined, s"${p.id} uses a declared key")
        assert(p.partitionForm.nonEmpty, s"${p.id} names its partition value")
        assert(!p.description.toLowerCase.contains("scan"), p.id)
        if (p.sortUse != SortUse.WholePartition) assert(p.sortForm.nonEmpty, p.id)
      }
      t.indexes.foreach { g =>
        assert(
          t.accessPatterns.exists(_.index.contains(g.name)),
          s"GSI ${g.name} is justified by an access pattern"
        )
      }
    }
  }

  test("key attribute names are distinct within each table and never a data attribute") {
    val data =
      Set(Attrs.SchemaVersion, Attrs.Body, Attrs.RecordSha256, Attrs.Version, Attrs.ExpiresAt)
    TableSpecs.all.foreach { t =>
      val names = Vector(t.partitionKey, t.sortKey) ++ t.indexes.flatMap(g =>
        Vector(g.partitionKey, g.sortKey)
      )
      assertEquals(names.distinct.size, names.size, t.role.tag)
      assert(names.toSet.intersect(data).isEmpty, t.role.tag)
    }
  }

  test("key builders produce the stated forms") {
    val R = TableKeys.RunLedger
    assertEquals(R.publication(runId), Right(PrimaryKey(s"run#$runId", "publication")))
    assertEquals(R.load(runId, loadId), Right(PrimaryKey(s"run#$runId", s"load#$loadId")))
    // CR-P7-11 m1: rejected-record rows are keyed per write generation.
    assertEquals(
      R.reject(runId, loadId, 3L, "row:a/b.parquet#3"),
      Right(PrimaryKey(s"run#$runId", s"reject#$loadId#0000000003#row:a/b.parquet#3"))
    )
    assertEquals(R.rejectGenerationPrefix(loadId, 12L), Right(s"reject#$loadId#0000000012#"))
    assert(R.rejectGenerationPrefix(loadId, 0L).isLeft)
    assert(R.rejectGenerationPrefix(loadId, RejectedRecord.MaxGeneration + 1).isLeft)
    assertEquals(
      TableKeys.QuerySession.step(queryId, 7),
      Right(PrimaryKey(s"query#$queryId", "step#0007"))
    )
    assertEquals(
      TableKeys.ResearcherMetricCache
        .subjectResult(researcherA, "researcher-metrics", "2015-2020", None),
      Right(PrimaryKey(s"subject#$researcherA", "metric#researcher-metrics#2015-2020"))
    )
    assertEquals(
      TableKeys.ResearcherMetricCache.rankSort(3, researcherA),
      Right(s"0000000003#$researcherA")
    )
    assertEquals(
      TableKeys.ResearcherMetricCache.byPair(pairFirst, pairSecond),
      Right(s"pair#$pairFirst#$pairSecond")
    )
  }

  test("key builders refuse malformed or foreign-kind IDs and bad windows") {
    assert(TableKeys.RunLedger.publication("run:nothex").isLeft)
    assert(TableKeys.RunLedger.load(runId, runId).isLeft, "a run ID is not a load ID")
    assert(TableKeys.RunLedger.reject(runId, loadId, 1L, "has space").isLeft)
    assert(TableKeys.EvidenceBundle.bundle(queryId).isLeft)
    assert(
      TableKeys.EntityLinkCache.partition("Ada Lovelace").isLeft,
      "surface forms arrive hashed"
    )
    assert(
      TableKeys.ResearcherMetricCache.subjectResult(researcherA, "r", "2020-2015", None).isLeft
    )
    assert(TableKeys.ResearcherMetricCache.byPair(pairSecond, pairFirst).isLeft, "non-canonical")
    assert(TableKeys.ResearcherMetricCache.byGroup(workId, 2015, 2020).isLeft)
    assert(TableKeys.ResearcherMetricCache.byGroup(communityId, 2015, 2020).isRight)
    assert(TableKeys.QuerySession.step(queryId, 10000).isLeft)
  }

  test("SE-P7-11 item 5: per-generation rejection rows (RL-7) and set summaries (RL-8)") {
    val ps = TableSpecs.RunLedger.accessPatterns.map(p => p.id -> p).toMap
    val R = TableKeys.RunLedger
    assertEquals(ps("RL-7").sortForm, "reject#<loadId>#<generation:10 digits>#")
    assertEquals(ps("RL-8").sortForm, "rejectset#<loadId>#")
    assert(ps("RL-7").sortForm.startsWith(ps("RL-4").sortForm), "RL-7 narrows RL-4")
    // The key builders produce the stated forms; summaries never fall under RL-4's prefix.
    val rows = R.rejectGenerationPrefix(loadId, 5L).toOption.get
    assert(rows.startsWith(R.rejectPrefix(loadId).toOption.get))
    val summary = R.rejectionSet(runId, loadId, 5L).toOption.get
    assertEquals(summary, PrimaryKey(s"run#$runId", s"rejectset#$loadId#0000000005"))
    assert(summary.sk.startsWith(R.rejectionSetPrefix(loadId).toOption.get))
    assert(!summary.sk.startsWith(R.rejectPrefix(loadId).toOption.get))
    assert(!summary.sk.startsWith("reject#"))
    assert(R.rejectionSet(runId, loadId, 0L).isLeft)
    assert(R.rejectionSet(runId, loadId, RejectedRecord.MaxGeneration + 1).isLeft)
    // A summary row round-trips through its codec.
    val s = RejectionSet(
      RejectionSet.SchemaVersion,
      runId,
      loadId,
      scholarchain.core.TargetStore.S3Vectors,
      5L,
      0L,
      "reports/loads/x/rejects/part-00005.jsonl",
      hex("")
    )
    val item = ItemCodecs.RejectionSetCodec.encode(s).toOption.get
    assertEquals(ItemCodecs.RejectionSetCodec.decode(item), Right(s))
    assert(ItemCodecs.RejectionSetCodec.encode(s.copy(count = -1)).isLeft)
  }

  test("zero-padded sort keys order as their numbers (DynamoDB byte order)") {
    val ranks = Vector(10, 2, 1, 100).map(r =>
      TableKeys.ResearcherMetricCache.rankSort(r, researcherA).toOption.get
    )
    val sorted = ranks.sortWith((a, b) => scholarchain.core.Canonical.compareUtf8(a, b) < 0)
    assertEquals(sorted.map(_.takeWhile(_ != '#').toInt), Vector(1, 2, 10, 100))
  }
}
