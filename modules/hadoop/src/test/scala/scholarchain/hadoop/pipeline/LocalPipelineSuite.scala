package scholarchain.hadoop.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import scala.collection.immutable.SortedMap

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat

import scholarchain.core.{
  AuthorMention,
  BlockKey,
  CandidateBlock,
  CoauthorPairCandidate,
  IdentityLink,
  Json,
  MatchCandidate,
  MatchEntityKind,
  MentionId,
  Provenance,
  PublicationTeam,
  RunManifest,
  SchemaVersions,
  SourceAuthorProfile,
  SourceType,
  WorkRecord
}
import scholarchain.entityresolution.{EntityResolution, MatchFeatures}
import scholarchain.hadoop.jobs.BlockMembership
import scholarchain.hadoop.{Counters, JobParams, NamedOutputs, RejectRecord, TestSupport}

/**
 * The local bounded pipeline: the full job graph (normalize -> exact-ids, teams, blocking ->
 * candidates) as real MapReduce jobs on the Hadoop local job runner, over every committed sample in
 * datasets/samples, with the REAL entity-resolution blocking keys and feature functions. DBLP input
 * is split into 512-byte splits, so records cross split boundaries in a real run. Outputs go to
 * modules/hadoop/target/test-work/ only. Counters, statistics, and one example line per record type
 * are printed for the phase report.
 */
class LocalPipelineSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  val inputs: PipelineInputs = {
    def p(rel: String) = Vector(new Path(TestSupport.sample(rel).toUri))
    PipelineInputs(
      dblp = p("dblp/dblp-sample.xml"),
      openAlexWorks = p("openalex/works-sample.jsonl"),
      openAlexAuthors = p("openalex/authors-sample.jsonl"),
      openAlexInstitutions = p("openalex/institutions-sample.jsonl"),
      crossrefWorks = p("crossref/works-sample.jsonl"),
      orcidRecords = p("orcid")
    )
  }

  val noInputs: PipelineInputs =
    PipelineInputs(
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )

  val params: Map[String, String] =
    TestSupport.baseParams ++ TestSupport.blockingParams ++ TestSupport.candidateParams
  val all: Vector[Stage] = Stage.values.toVector

  def conf(work: JPath): Configuration = {
    val c = TestSupport.localConf(work)
    c.setLong(FileInputFormat.SPLIT_MAXSIZE, 512L)
    c
  }

  def runPipeline(
      work: JPath,
      root: JPath,
      m: RunManifest,
      stages: Vector[Stage] = all,
      in: PipelineInputs = inputs
  ): Vector[StageReport] =
    Pipeline.run(conf(work), m, new Path(root.toUri), in, stages) match {
      case Left(errors) => fail(errors.mkString("\n"))
      case Right(r) => r
    }

  def lines(c: Configuration, dir: Path): Vector[String] =
    Pipeline.readLines(c, dir.getFileSystem(c), dir)

  def runRoot(root: JPath, m: RunManifest): Path = {
    val id = scholarchain.core.RunId.of(m).toOption.get
    RunLayout.of(new Path(root.toUri), m, id).toOption.get.root
  }

  /** All output lines of every curated and reject directory of a run, keyed by relative path. */
  def snapshotOf(c: Configuration, run: Path): SortedMap[String, Vector[String]] = {
    val fs = run.getFileSystem(c)
    def dirs(p: Path): Vector[Path] =
      fs.listStatus(p).toVector.filter(_.isDirectory).flatMap(s => s.getPath +: dirs(s.getPath))
    SortedMap.from(
      dirs(run)
        .map(d => d.toString.stripPrefix(run.toString) -> lines(c, d))
        .filter(_._2.nonEmpty)
    )
  }

  def report(rs: Vector[StageReport]): Unit =
    rs.foreach { r =>
      println(s"[local-pipeline] stage=${r.stage} status=${r.status} output=${r.outputDir}")
      r.counters.foreach { case (k, v) => println(s"[local-pipeline]   counter $k=$v") }
      r.stats.foreach { case (k, s) =>
        println(s"[local-pipeline]   stat $k=${s.value} id=${s.id}")
      }
    }

  def example(kind: String, line: String): Unit = println(s"[local-pipeline] example $kind: $line")

  def cnt(m: SortedMap[String, Long], k: String): Long = m.getOrElse(k, 0L)

  def decodeAll[T](ls: Vector[String])(using scholarchain.core.RecordContract[T]): Vector[T] =
    ls.map(l => Json.decode[T](l).fold(e => fail(s"$l: ${e.map(_.message)}"), identity))

  test("full job graph over the samples: counters, outputs, contracts, determinism, idempotency") {
    val work = TestSupport.workDir("local-pipeline")
    val rootA = work.resolve("curated-a")
    val rootB = work.resolve("curated-b")
    val m = TestSupport.manifest(params)
    val reports = runPipeline(work, rootA, m)
    report(reports)
    val c = conf(work)
    val run = runRoot(rootA, m)
    println(s"[local-pipeline] run root: $run")
    val byStage = reports.map(r => r.stage -> r).toMap
    assertEquals(reports.map(_.status), Vector.fill(5)("ran"))
    reports.foreach(r =>
      Counters.Required.foreach(k => assert(r.counters.contains(k), s"${r.stage} $k"))
    )
    val runId = scholarchain.core.RunId.of(m).toOption.get

    // The DBLP sample is read through several byte-range splits (several map tasks).
    val splitConf = new Configuration(c)
    splitConf.set(FileInputFormat.INPUT_DIR, inputs.dblp.head.toString)
    val dblpSplits = new scholarchain.hadoop.dblp.DblpInputFormat()
      .getSplits(
        new org.apache.hadoop.mapreduce.task.JobContextImpl(
          splitConf,
          new org.apache.hadoop.mapreduce.JobID("s", 1)
        )
      )
      .size
    println(s"[local-pipeline] DBLP sample input splits at 512 bytes: $dblpSplits")
    assert(dblpSplits > 1, s"expected several DBLP splits, got $dblpSplits")

    // ---- normalize ----
    val n = byStage("normalize").counters
    assertEquals(cnt(n, "INPUT"), 15L)
    assertEquals(cnt(n, "INPUT.dblp"), 4L, "a DBLP record split across mappers would change this")
    assertEquals(cnt(n, "ACCEPTED"), 15L, "OpenAlex authors are accepted as profiles")
    assertEquals(cnt(n, "REJECTED"), 0L)
    assertEquals(cnt(n, "EMITTED.works"), 13L)
    assertEquals(cnt(n, "EMITTED.mentions"), 99L)
    assertEquals(cnt(n, "EMITTED.profiles"), 4L)
    assertEquals(cnt(n, "EMITTED.venues"), 6L)
    assertEquals(cnt(n, "EMITTED.institutions"), 4L)
    assertEquals(cnt(n, "EMITTED.topics"), 3L)
    assertEquals(cnt(n, "DUPLICATE"), 0L)
    assertEquals(cnt(n, "CONFLICT"), 0L)
    assertEquals(cnt(n, "SCHEMA_FAILURE"), 0L)
    assertEquals(cnt(n, "MISSING_KEY"), 0L)

    val normalizeDir = new Path(run, "normalize")
    val workLines = lines(c, new Path(normalizeDir, NamedOutputs.Works))
    val works = decodeAll[WorkRecord](workLines)
    assertEquals(works.size, 13)
    assert(works.forall(_.schemaVersion == "scholarchain.work-record.v3"))
    val profileLines = lines(c, new Path(normalizeDir, NamedOutputs.Profiles))
    val profiles = decodeAll[SourceAuthorProfile](profileLines)
    assertEquals(profiles.map(_.source.tag).sorted, Vector("openalex", "orcid", "orcid", "orcid"))
    val mentions = decodeAll[AuthorMention](lines(c, new Path(normalizeDir, NamedOutputs.Mentions)))
    assertEquals(mentions.size, 99)
    val dblpCacm =
      workLines.find(_.contains("\"workId\":\"work:dblp:journals/cacm/PopperMP21\"")).get
    example("WorkRecord (DBLP, venueName)", dblpCacm)
    assert(works.exists(w => w.source == SourceType.Dblp && w.venueName.contains("Commun. ACM")))
    val orcidWork =
      workLines
        .find(l => l.contains("\"source\":\"orcid\"") && !l.contains("\"sourceLocalWorkIds\":[]"))
        .get
    example("WorkRecord (ORCID, sourceLocalWorkIds)", orcidWork)
    example("AuthorMention", lines(c, new Path(normalizeDir, NamedOutputs.Mentions)).head)
    example(
      "SourceAuthorProfile (OpenAlex)",
      profileLines.find(_.contains("\"source\":\"openalex\"")).get
    )
    example(
      "SourceAuthorProfile (ORCID)",
      profileLines.find(_.contains("source-author:orcid:0000-0001-6899-0651")).get
    )
    val rejects = lines(c, new Path(normalizeDir, NamedOutputs.Rejects)).map(RejectRecord.fromJson)
    assertEquals(rejects.size.toLong, cnt(n, "REJECTED") + cnt(n, "FLAGGED"))
    assert(rejects.forall(_.runId == runId))

    // ---- teams ----
    val t = byStage("teams").counters
    assertEquals(cnt(t, "INPUT.works"), 13L)
    assertEquals(cnt(t, "TEAM_SOURCE_EXCLUDED"), 9L)
    assertEquals(cnt(t, "EMITTED.teams"), 4L)
    assertEquals(cnt(t, "EMITTED_PAIR"), 325L + 3L + 0L + 36L)
    assertEquals(cnt(t, "OVERSIZE_TEAM"), 0L)
    assertEquals(byStage("teams").stats("maxTeamSize").value, 26L)
    assertEquals(byStage("teams").stats("maxPairExpansion").value, 325L)
    val teamsDir = new Path(run, "teams")
    val teamLines = lines(c, new Path(teamsDir, NamedOutputs.Teams))
    val teams = decodeAll[PublicationTeam](teamLines)
    assertEquals(teams.map(_.teamSize).sorted, Vector(1, 3, 9, 26))
    example("PublicationTeam", teamLines.find(_.contains("PopperMP21")).get)
    val pairLines = lines(c, new Path(teamsDir, NamedOutputs.Pairs))
    val pairs = decodeAll[CoauthorPairCandidate](pairLines)
    assertEquals(pairs.size, 364)
    assertEquals(pairs.groupBy(_.workId).values.map(_.size).toVector.sorted, Vector(3, 36, 325))
    val worksById = works.map(w => w.workId -> w).toMap
    assert(
      pairs.forall { p =>
        val w = worksById(p.workId)
        w.authors(p.positionA).mentionId == p.endpointA &&
        w.authors(p.positionB).mentionId == p.endpointB && p.year == w.year &&
        p.provenance == w.provenance
      },
      "every pair carries both positions, the work ID, year, and source provenance"
    )
    example("CoauthorPairCandidate", pairLines.find(_.contains("PopperMP21")).get)

    // ---- exact ids: identity links ----
    val x = byStage("exact-ids").counters
    val linkLines = lines(c, new Path(new Path(run, "exact-ids"), NamedOutputs.Links))
    val links = decodeAll[IdentityLink](linkLines)
    assertEquals(links.size.toLong, cnt(x, "EMITTED.links"))
    assertEquals(links.map(_.linkId).distinct.size, links.size)
    assertEquals(cnt(x, "EXACT_ID_KEY.doi"), 3L)
    assertEquals(cnt(x, "EXACT_ID_CROSS_SOURCE.doi"), 3L)
    assertEquals(cnt(x, "CONFLICT"), 0L)
    assert(
      links.exists(l => l.identifier.scheme.tag == "doi" && l.entityKind == MatchEntityKind.Work)
    )
    assert(links.exists(l => l.identifier.scheme.tag == "mag"))
    assert(
      links.exists(l => l.identifier.scheme.tag == "scopus-eid" && l.assertionSource.isDefined)
    )
    assert(
      links.exists(l =>
        l.entityId.startsWith("source-author:orcid:") && l.workId.isDefined &&
          l.assertionSource.isDefined
      ),
      "ORCID holder-to-work claims are identity links with their assertion source"
    )
    assert(links.exists(l => l.entityId.startsWith("source-author:openalex:")))
    assert(links.exists(l => l.entityId.startsWith("mention:") && l.workId.isDefined))
    example(
      "IdentityLink (mention ORCID)",
      linkLines.find(_.contains("\"entityId\":\"mention:")).get
    )
    example(
      "IdentityLink (ORCID claim)",
      linkLines
        .find(l => l.contains("source-author:orcid:") && l.contains("\"workId\":\"work:"))
        .get
    )

    // ---- blocking (real entity-resolution keys, both entities, one run) ----
    val b = byStage("blocking").counters
    val blockingDir = new Path(run, "blocking")
    val rounds = (0 until EntityResolution.blockingLevels).filter(r =>
      FileSystem.getLocal(c).exists(new Path(blockingDir, s"round-$r"))
    )
    def roundLines(sub: String): Vector[String] =
      rounds.toVector.flatMap(r => lines(c, new Path(new Path(blockingDir, s"round-$r"), sub)))
    val blockLines = Map(
      "researcher" -> roundLines("blocks/researcher"),
      "work" -> roundLines("blocks/work")
    )
    val blocks = blockLines.view.mapValues(decodeAll[CandidateBlock](_)).toMap
    assert(blocks("researcher").nonEmpty && blocks("work").nonEmpty, "both entities blocked")
    val allBlocks = blocks.values.flatten.toVector
    assert(allBlocks.forall(_.size <= 4), "no block exceeds scholarchain.blocking.maxBlockSize")
    assert(
      allBlocks.forall(_.blockingVersion == EntityResolution.blockingKeysVersion),
      "every block carries the production key source version"
    )
    assert(allBlocks.forall(_.provenance.runId == runId))
    assert(allBlocks.forall(_.provenance.sourceDataset == SourceType.Derived))
    assert(allBlocks.forall(_.sourceDatasets.nonEmpty))
    assertEquals(
      blocks("researcher").map(_.entityKind).distinct,
      Vector(MatchEntityKind.Researcher)
    )
    assertEquals(blocks("work").map(_.entityKind).distinct, Vector(MatchEntityKind.Work))
    assert(cnt(b, "OVERSIZE_BLOCK") >= 1L)
    assert(allBlocks.exists(_.level >= 1), "oversize groups were refined to a fallback level")
    Vector("researcher", "work").foreach { e =>
      assertEquals(
        blocks(e).map(_.size.toLong).sum + cnt(b, s"BLOCK_MEMBERS_DROPPED.$e"),
        cnt(b, s"ACCEPTED.$e"),
        s"every $e member ends in exactly one block or is counted as dropped"
      )
    }
    assertEquals(cnt(b, "INPUT.researcher"), 99L + 4L, "every mention and every profile")
    assertEquals(cnt(b, "INPUT.work"), 13L)
    assertEquals(
      cnt(b, "INPUT"),
      cnt(b, "ACCEPTED") + cnt(b, "MISSING_KEY") + cnt(b, "FLAGGED.invalid-blocking-key") +
        cnt(b, "REJECTED"),
      "one INPUT unit (blocking members)"
    )
    val memberships = (roundLines("members/researcher") ++ roundLines("members/work"))
      .map(upickle.default.read[BlockMembership](_))
    assertEquals(memberships.size.toLong, allBlocks.map(_.size.toLong).sum)
    example("CandidateBlock (researcher)", blockLines("researcher").head)
    example("CandidateBlock (work)", blockLines("work").head)

    // ---- candidates (job 5) ----
    val k = byStage("candidates").counters
    val finalDir = new Path(new Path(run, "candidates"), Pipeline.FinalJob)
    val candLines = Map(
      "researcher" -> lines(c, new Path(finalDir, "researcher")),
      "work" -> lines(c, new Path(finalDir, "work"))
    )
    val candidates = candLines.values.flatten.toVector.map(l =>
      Json.decode[MatchCandidate](l).fold(e => fail(e.map(_.message).mkString), identity)
    )
    assertEquals(candidates.map(_.candidateId).distinct.size, candidates.size, "one per pair")
    assertEquals(
      candidates.map(c => (c.sourceIdA, c.sourceIdB)).distinct.size,
      candidates.size,
      "one candidate per canonical pair per run"
    )
    val expectedPairs = allBlocks.map(bl => bl.size.toLong * (bl.size - 1) / 2).sum
    assertEquals(cnt(k, "CANDIDATE_PAIRS"), expectedPairs, "every pair of every block")
    assertEquals(candidates.size.toLong + cnt(k, "CANDIDATE_MERGED"), expectedPairs)
    assertEquals(cnt(k, "INPUT"), memberships.size.toLong)
    assertEquals(cnt(k, "ACCEPTED"), memberships.size.toLong, "every member joins its record")
    assertEquals(cnt(k, "MISSING_KEY"), 0L)
    val blockMembers = allBlocks
      .map(bl => (bl.level, bl.blockKey, bl.entityKind) -> bl.memberIds.toSet)
      .toMap
    candidates.foreach { cand =>
      assert(cand.blockKeys.nonEmpty)
      cand.blockKeys.foreach { bk =>
        val members = blockMembers((bk.level, bk.key, cand.entityKind))
        assert(members(cand.sourceIdA) && members(cand.sourceIdB), s"${cand.candidateId} $bk")
      }
      assertEquals(cand.blockingVersion, EntityResolution.blockingKeysVersion)
      assertEquals(cand.featureVersion, EntityResolution.featuresVersion)
      val names =
        if (cand.entityKind == MatchEntityKind.Researcher) MatchFeatures.ResearcherFeatureNames
        else MatchFeatures.WorkFeatureNames
      assertEquals(cand.features.keys.toVector, names)
    }
    assert(candLines("researcher").forall(l => !l.contains("score") && !l.contains("decision")))
    val multi = candidates.count(_.blockKeys.size > 1)
    println(
      s"[local-pipeline] candidates: researcher=${candLines("researcher").size} " +
        s"work=${candLines("work").size} multiBlockKey=$multi " +
        s"sameWork=${candidates.count(_.features.get("context.same_work").contains(1.0))}"
    )
    assertEquals(multi.toLong, cnt(k, "CANDIDATE_MULTI_BLOCK"))
    example(
      "MatchCandidate (researcher)",
      candLines("researcher")
        .find(_.contains("\"id.orcid.agree\":1"))
        .getOrElse(candLines("researcher").head)
    )
    example("MatchCandidate (work)", candLines("work").head)
    val blockRejects =
      roundLines("rejects").map(RejectRecord.fromJson).filter(_.reason == "oversize-block")
    example("reject (oversize-block, flagged)", RejectRecord.toJson(blockRejects.head))

    // ---- idempotency: same manifest again -> every stage skipped, outputs unchanged ----
    val before = snapshotOf(c, run)
    val again = runPipeline(work, rootA, m)
    assertEquals(again.map(_.status), Vector.fill(5)("skipped"))
    assertEquals(again.map(_.counters), reports.map(_.counters))
    assertEquals(snapshotOf(c, run), before)

    // ---- determinism: a second independent run produces identical outputs ----
    val runB = runRoot(rootB, m)
    runPipeline(work, rootB, m)
    val after = snapshotOf(c, runB)
    assertEquals(after.keySet, before.keySet)
    before.foreach { case (k, v) =>
      // spill is a between-round intermediate (order not part of any contract); curated outputs
      // and rejects must be byte-identical line by line.
      if (k.endsWith("/spill")) assertEquals(after(k).sorted, v.sorted, k)
      else assertEquals(after(k), v, k)
    }
  }

  test("oversize teams: counted and flagged, and ALL their pairs are still emitted") {
    val work = TestSupport.workDir("local-pipeline-oversize")
    val m = TestSupport.manifest(params ++ Map(JobParams.TeamMaxSize -> "20"))
    val reports =
      runPipeline(work, work.resolve("curated"), m, Vector(Stage.Normalize, Stage.Teams))
    report(reports)
    val t = reports.find(_.stage == "teams").get
    assertEquals(t.counters("OVERSIZE_TEAM"), 1L)
    assertEquals(t.counters("EMITTED_PAIR"), 364L, "the 26-author team still emits 325 pairs")
    assertEquals(t.counters("EMITTED.teams"), 4L)
    assertEquals(t.stats("maxPairExpansion").value, 325L)
    assertEquals(t.stats("maxTeamSize").value, 26L)
    val c = conf(work)
    val root = runRoot(work.resolve("curated"), m)
    val spanner = "work:dblp:journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13"
    val pairs = lines(c, new Path(root, "teams/pairs"))
      .map(Json.decode[CoauthorPairCandidate](_).toOption.get)
      .filter(_.workId == spanner)
    assertEquals(pairs.size, 325)
    assertEquals(pairs.map(p => Set(p.positionA, p.positionB)).distinct.size, 325)
    val rej = lines(c, new Path(root, "teams/rejects")).map(RejectRecord.fromJson)
    assertEquals(
      rej.map(r => (r.reason, r.disposition, r.sourceRecordId)),
      Vector(
        ("oversize-team", "flagged", Some("journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13"))
      )
    )
    assert(rej.head.detail.contains("pairs=325"))
    assert(rej.head.detail.contains("all pairs emitted"))
    example("reject (oversize-team, flagged)", RejectRecord.toJson(rej.head))
  }

  test("a removed pair-suppression parameter is refused (unknown run manifest parameter)") {
    val work = TestSupport.workDir("local-pipeline-no-skip")
    val m = TestSupport.manifest(params + ("scholarchain.team.oversizePolicy" -> "skip-pairs"))
    val res =
      Pipeline.run(
        conf(work),
        m,
        new Path(work.toUri),
        inputs,
        Vector(Stage.Normalize, Stage.Teams)
      )
    assert(
      res.left.exists(_.exists(_.contains("unknown run manifest parameter 'scholarchain.team"))),
      res.toString
    )
  }

  test("same DBLP key twice: DUPLICATE and CONFLICT counters and a conflict reject record") {
    val work = TestSupport.workDir("local-pipeline-dup")
    val xml = work.resolve("dup.xml")
    Files.write(
      xml,
      """<?xml version="1.0" encoding="UTF-8"?>
        |<dblp>
        |<article key="journals/x/Dup"><author>Ann One</author><author>Bob Two</author><title>First title.</title><year>2020</year><journal>J. X</journal></article>
        |<article key="journals/x/Dup"><author>Ann One</author><author>Bob Two</author><title>Second title.</title><year>2020</year><journal>J. X</journal></article>
        |<article key="journals/x/Same"><author>Cy Three</author><title>Same.</title><year>2021</year></article>
        |<article key="journals/x/Same"><author>Cy Three</author><title>Same.</title><year>2021</year></article>
        |</dblp>
        |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    val in = noInputs.copy(dblp = Vector(new Path(xml.toUri)))
    val m = TestSupport.manifest(TestSupport.baseParams)
    val reports = runPipeline(work, work.resolve("curated"), m, Vector(Stage.Normalize), in)
    report(reports)
    val n = reports.head.counters
    assertEquals(cnt(n, "INPUT.dblp"), 4L)
    assertEquals(cnt(n, "DUPLICATE"), 2L)
    assertEquals(cnt(n, "DUPLICATE.work"), 2L)
    assertEquals(cnt(n, "CONFLICT"), 1L, "identical copies are duplicates, not conflicts")
    assertEquals(cnt(n, "EMITTED.works"), 2L)
    assertEquals(cnt(n, "EMITTED.mentions"), 3L)
    val c = conf(work)
    val root = runRoot(work.resolve("curated"), m)
    val rej = lines(c, new Path(root, "normalize/rejects")).map(RejectRecord.fromJson)
    assertEquals(
      rej.map(r => (r.reason, r.disposition, r.sourceRecordId)),
      Vector(("conflict", "flagged", Some("work:dblp:journals/x/Dup")))
    )
    assert(rej.head.detail.head.contains("2 copies"))
    example("reject (conflict, flagged)", RejectRecord.toJson(rej.head))
    val kept =
      lines(c, new Path(root, "normalize/works")).map(Json.decode[WorkRecord](_).toOption.get)
    assertEquals(kept.size, 2)
  }

  test("a driver stop between the jobs and the commit leaves no _SUCCESS; reruns are refused") {
    val work = TestSupport.workDir("local-pipeline-crash")
    val root = work.resolve("curated")
    val m = TestSupport.manifest(TestSupport.baseParams)
    val c = conf(work)
    val crash = intercept[IllegalStateException] {
      Pipeline.run(
        c,
        m,
        new Path(root.toUri),
        inputs,
        Vector(Stage.Normalize),
        s => if (s == Stage.Normalize) throw new IllegalStateException("simulated driver crash")
      )
    }
    assertEquals(crash.getMessage, "simulated driver crash")
    val dir = new Path(runRoot(root, m), "normalize")
    val fs = FileSystem.getLocal(c)
    assert(fs.exists(dir), "the job ran")
    assert(!fs.exists(new Path(dir, Pipeline.Success)), "no job-committer _SUCCESS")
    assert(!fs.exists(new Path(dir, Pipeline.CountersFile)))
    val res = Pipeline.run(c, m, new Path(root.toUri), inputs, Vector(Stage.Normalize))
    assert(res.left.exists(_.exists(_.contains("refusing to overwrite"))), res.toString)

    // A complete stage always has its counters; one tampered to lose them is refused, never
    // reported as complete with empty counters.
    val m2 = TestSupport.manifest(TestSupport.baseParams, attempt = 2)
    runPipeline(work, root, m2, Vector(Stage.Normalize))
    val dir2 = new Path(runRoot(root, m2), "normalize")
    assert(fs.exists(new Path(dir2, Pipeline.CountersFile)))
    assert(fs.exists(new Path(dir2, Pipeline.Success)))
    fs.delete(new Path(dir2, Pipeline.CountersFile), false)
    val res2 = Pipeline.run(c, m2, new Path(root.toUri), inputs, Vector(Stage.Normalize))
    assert(res2.left.exists(_.exists(_.contains("counters are unknown"))), res2.toString)
  }

  test("candidate consolidation: one MatchCandidate per pair with the union of its block keys") {
    val work = TestSupport.workDir("local-pipeline-consolidate")
    val m = TestSupport.manifest(params)
    val (c, runId) = Pipeline.configure(conf(work), m, Vector(Stage.Candidates), noInputs) match {
      case Right(v) => v
      case Left(e) => fail(e.mkString("; "))
    }
    val w = "work:dblp:journals/x/A"
    val ms = (0 to 2).toVector.map(i => MentionId.of(w, i).toOption.get)
    val (m0, m1, m2) = (ms(0), ms(1), ms(2))
    val prov = Provenance(
      SourceType.Derived,
      "fixture",
      "dblp-2026-10-03",
      runId,
      SchemaVersions.CandidateBlock,
      Some("hadoop-transform.v2"),
      None,
      None
    )
    def cand(x: String, y: String, key: BlockKey, f: Double): String =
      Json.write(
        MatchCandidate
          .build(
            MatchEntityKind.Researcher,
            x,
            SourceType.Dblp,
            y,
            SourceType.Dblp,
            Vector(key),
            EntityResolution.blockingKeysVersion,
            EntityResolution.featuresVersion,
            SortedMap("name.family_exact" -> f),
            Vector.empty,
            prov
          )
          .fold(e => fail(e.map(_.message).mkString), identity)
      )
    val input = work.resolve("pairs")
    Files.createDirectories(input)
    Files.write(
      input.resolve("part-r-00000"),
      Vector(
        cand(m0, m1, BlockKey(1, "b|key"), 1.0),
        cand(m1, m0, BlockKey(0, "a"), 1.0), // same pair, other block, other order
        cand(m0, m2, BlockKey(0, "a"), 1.0),
        cand(m0, m2, BlockKey(2, "c"), 0.0) // same pair, different features: a conflict
      ).mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
    )
    val out = new Path(work.resolve("final").toUri)
    val job = Pipeline.consolidateJob(c, new Path(input.toUri), out)
    assert(job.waitForCompletion(true))
    val counters = Pipeline.counterMap(job).toMap
    val result =
      lines(c, new Path(out, "researcher")).map(Json.decode[MatchCandidate](_).toOption.get)
    assertEquals(result.size, 2)
    val byPair = result.map(r => Set(r.sourceIdA, r.sourceIdB) -> r).toMap
    assertEquals(byPair(Set(m0, m1)).blockKeys, Vector(BlockKey(0, "a"), BlockKey(1, "b|key")))
    assertEquals(byPair(Set(m0, m2)).blockKeys, Vector(BlockKey(0, "a"), BlockKey(2, "c")))
    assertEquals(byPair(Set(m0, m2)).features("name.family_exact"), 0.0, "smallest JSON kept")
    assertEquals(counters("CANDIDATE_MERGED"), 2L)
    assertEquals(counters("CANDIDATE_MULTI_BLOCK"), 2L)
    assertEquals(counters("CONFLICT"), 1L)
    val rej = lines(c, new Path(out, "rejects")).map(RejectRecord.fromJson)
    assertEquals(rej.map(r => (r.reason, r.disposition)), Vector(("conflict", "flagged")))
    example("MatchCandidate (consolidated, 2 block keys)", Json.write(byPair(Set(m0, m1))))
  }

  test(
    "blocking with a fixture key source and no fallback level: drop or fail (plumbing only)"
  ) {
    val work = TestSupport.workDir("local-pipeline-blocking-policy")
    val single =
      params ++ TestSupport.fixtureBlocking(classOf[scholarchain.hadoop.SingleLevelKeySource])
    val drop = TestSupport.manifest(single)
    val reports =
      runPipeline(work, work.resolve("drop"), drop, Vector(Stage.Normalize, Stage.Blocking))
    report(reports)
    val b = reports.find(_.stage == "blocking").get.counters
    assert(b("OVERSIZE_BLOCK") >= 1L)
    assert(b(Counters.BlockMembersDropped) >= 5L, "an oversize block has more than maxBlockSize=4")
    assertEquals(b.getOrElse(Counters.BlockMembersRefined, 0L), 0L)
    val c = conf(work)
    val root = runRoot(work.resolve("drop"), drop)
    val blocks = Vector("researcher", "work").flatMap(e =>
      lines(c, new Path(root, s"blocking/round-0/blocks/$e"))
        .map(Json.decode[CandidateBlock](_).toOption.get)
    )
    assert(blocks.forall(_.blockingVersion == "fixture-single-level.v1"))
    assertEquals(blocks.map(_.size.toLong).sum + b(Counters.BlockMembersDropped), b("ACCEPTED"))

    val failing = TestSupport.manifest(single + (JobParams.BlockingOversizePolicyKey -> "fail"))
    val res = Pipeline.run(
      c,
      failing,
      new Path(work.resolve("fail").toUri),
      inputs,
      Vector(Stage.Normalize, Stage.Blocking)
    )
    assert(res.left.exists(_.exists(_.contains("failed"))), res.toString)
  }

  test("guards: partial stage, unknown parameter, missing parameter, version mismatch, order") {
    val work = TestSupport.workDir("local-pipeline-guards")
    val root = work.resolve("curated")
    val m = TestSupport.manifest(TestSupport.baseParams)
    val partial = new Path(runRoot(root, m), "normalize")
    val c = conf(work)
    FileSystem.getLocal(c).mkdirs(partial)
    val res = Pipeline.run(c, m, new Path(root.toUri), inputs, Vector(Stage.Normalize))
    assert(res.left.exists(_.exists(_.contains("refusing to overwrite"))), res.toString)

    val bad = TestSupport.manifest(TestSupport.baseParams + ("scholarchain.unknown.knob" -> "1"))
    val res2 = Pipeline.run(c, bad, new Path(root.toUri), inputs, Vector(Stage.Normalize))
    assert(res2.left.exists(_.exists(_.contains("unknown run manifest parameter"))), res2.toString)

    val missing = TestSupport.manifest(TestSupport.baseParams - JobParams.TeamMaxSize)
    val res3 = Pipeline.run(c, missing, new Path(root.toUri), inputs, Vector(Stage.Teams))
    assert(res3.left.exists(_.exists(_.contains(JobParams.TeamMaxSize))), res3.toString)

    val wrongSnapshot = m.copy(inputSnapshots = m.inputSnapshots.filterNot(_.source.tag == "orcid"))
    val res4 = Pipeline.run(c, wrongSnapshot, new Path(root.toUri), inputs, Vector(Stage.Normalize))
    assert(res4.left.exists(_.exists(_.contains("no orcid snapshot"))), res4.toString)

    val wrongKeys =
      TestSupport.manifest(params + (JobParams.BlockingKeySourceVersion -> "er-blocking.v0"))
    val res5 = Pipeline.run(c, wrongKeys, new Path(root.toUri), inputs, Vector(Stage.Blocking))
    assert(res5.left.exists(_.exists(_.contains("differs from the version"))), res5.toString)

    val wrongFeatures =
      TestSupport.manifest(params + (JobParams.CandidatesFeatureVersion -> "er-features.v0"))
    val res6 =
      Pipeline.run(c, wrongFeatures, new Path(root.toUri), inputs, Vector(Stage.Candidates))
    assert(res6.left.exists(_.exists(_.contains("feature functions' version"))), res6.toString)

    val full = TestSupport.manifest(params)
    val res7 =
      Pipeline.run(c, full, new Path(work.resolve("order").toUri), inputs, Vector(Stage.Candidates))
    assert(
      res7.left.exists(_.exists(_.contains("needs complete normalize, blocking"))),
      res7.toString
    )
    assert(!Files.exists(work.resolve("curated/hadoop/snapshot=dblp-2026-10-03/run=x")))
  }
}
