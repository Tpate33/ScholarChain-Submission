package scholarchain.spark.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path => HPath}

import scholarchain.core.*
import scholarchain.core.config.*
import scholarchain.spark.input.{ArchiveRuns, SyntheticCuratedPin}

/**
 * A synthetic, hand-checkable run chain (curated, decision, grouping) in the layout of the archived
 * dev run: the curated run in the Hadoop producer layout of HD-P6-01 ([[writeHadoopRun]]: stage
 * `_SUCCESS` and `_counters.json`, crc sidecars, no run-level `_SUCCESS`, no part manifest), the
 * decision and grouping runs as published run directories with part-manifest v1. Every identifier
 * is synthetic; nothing is read from target/, the archive or any gold set.
 *
 * SP-P6-03 step 2B: every curated file is written through Hadoop's own `LocalFileSystem` (so every
 * crc sidecar is a real Hadoop checksum, written by the producer's library, never by the verifier
 * under test); the consumed normalize leaves hold real records ([[curatedFiles]]: one work record
 * per work assignment with its author mentions in position order, the spec's venues, institutions
 * and topics) and `normalize/_counters.json` declares their rows (`EMITTED.<leaf>`); the grouping
 * run's authorship part holds one `authorship-record.v2` per identified mention on a representative
 * work ([[authorshipsOf]]).
 *
 * Researchers (dblp-pid anchored sc IDs): A, B, C, D resolved across works, E a singleton; one
 * unresolved mention u1. Counted works and teams:
 *   - w1 (2018): A, B, C and u1; w2 (2019): A, B; w3 (2021): C, D; w4 (2022): E alone;
 *   - w5 (no year): A, D (in no window); x1: an enrichment-only DOI work without a team.
 *
 * Windows 2015-2019 and 2020-2024. Expected edges: 2015-2019 A-B (2018:1, 2019:1; w1, w2), A-C
 * (2018:1; w1), B-C (2018:1; w1); 2020-2024 C-D (2021:1; w3). No edge touches u1 or E; no edge
 * comes from w5.
 */
object RunFixtures {

  def ok[T](e: Either[Any, T]): T = e.fold(x => throw new AssertionError(s"fixture: $x"), identity)

  val snapshot = "dblp-2026-10-03"
  val snapshots: Vector[SnapshotRef] = Vector(SnapshotRef(SourceType.Dblp, snapshot))
  val code = "0123456789abcdef0123456789abcdef01234567"
  val groupingPolicy = "er-grouping.v1"
  val matchPolicy = "er-policy.v8"
  val assignedAt = "2026-11-15T12:00:00Z"

  val w1: String = ok(WorkId.dblp("journals/example/Fixture1"))
  val w2: String = ok(WorkId.dblp("journals/example/Fixture2"))
  val w3: String = ok(WorkId.dblp("journals/example/Fixture3"))
  val w4: String = ok(WorkId.dblp("journals/example/Fixture4"))
  val w5: String = ok(WorkId.dblp("journals/example/Fixture5"))
  val x1: String = ok(WorkId.doi("10.1234/fixture.x1"))

  private def rid(n: Int) = ok(ResearcherId.scholarChain("v1", "dblp-pid", s"00/000$n"))
  val rA: String = rid(1)
  val rB: String = rid(2)
  val rC: String = rid(3)
  val rD: String = rid(4)
  val rE: String = rid(5)
  val u1: String = ok(MentionId.of(w1, 3))

  val window1: TimeWindow = TimeWindow(2015, 2019)
  val window2: TimeWindow = TimeWindow(2020, 2024)

  def sorted(xs: String*): Vector[String] =
    xs.toVector.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  // ---------------------------------------------------------------- configuration

  val config: ScholarChainConfig = ScholarChainConfig(
    configSchemaVersion = "scholarchain.config.v2",
    environment = "test",
    aws = AwsConfig("us-east-1", "000000000000"),
    storage = StorageConfig(
      "dummy-raw",
      "dummy-curated",
      "dummy-context",
      "dummy-vectors",
      "dummy-index",
      "dummy",
      "dummy-neptune.invalid",
      None
    ),
    models = ModelConfig("not-called", "not-called", 8),
    graph = GraphConfig(Vector(window1, window2), "sc-graph.v1", "v1"),
    bridge = BridgeConfig("cross-community-degree", 1, 0L, 1),
    community = CommunityConfig("label-propagation", 20, None),
    entityResolution =
      EntityResolutionConfig(matchPolicy, 0.85, 0.2, 0.4, 0.7, "er-adjudication-prompt.v2"),
    runtime = RuntimeConfig(3, 5, window2),
    analysis = AnalysisConfig(Some(4), Some(3), Some(3), Some("parquet"))
  )

  val settings: AnalysisRunSettings = ok(AnalysisRunSettings.from(config))

  // ---------------------------------------------------------------- manifests

  /** Another DBLP snapshot, for the snapshot-set mutations. */
  val otherSnapshots: Vector[SnapshotRef] = Vector(SnapshotRef(SourceType.Dblp, "dblp-2026-09-01"))

  /** The Hadoop producer's manifest stage (modules/hadoop Pipeline.scala `ManifestStage`). */
  val hadoopStage = "hadoop.pipeline"

  def curatedManifestOf(snaps: Vector[SnapshotRef], stage: String = hadoopStage): RunManifest =
    RunManifest(
      SchemaVersions.RunManifest,
      stage,
      code,
      "c" * 64,
      snaps,
      SortedMap.empty,
      1
    )
  val curatedManifest: RunManifest = curatedManifestOf(snapshots)
  val curatedRunId: String = ok(RunId.of(curatedManifest))

  def decisionManifestOf(inputRunId: String, snaps: Vector[SnapshotRef]): RunManifest =
    RunManifest(
      SchemaVersions.RunManifest,
      "er.decisions",
      code,
      "d" * 64,
      snaps,
      SortedMap("inputRunId" -> inputRunId, "policyVersion" -> matchPolicy),
      1
    )
  val decisionManifest: RunManifest = decisionManifestOf(curatedRunId, snapshots)
  val decisionRunId: String = ok(RunId.of(decisionManifest))

  def groupingParamsOf(curated: String, decision: String): SortedMap[String, String] = SortedMap(
    "assignedAt" -> assignedAt,
    "decisionRunId" -> decision,
    "groupingPolicyVersion" -> groupingPolicy,
    "identityVersion" -> "v1",
    "inputRunId" -> curated,
    "matchPolicyVersion" -> matchPolicy
  )
  def groupingParams: SortedMap[String, String] = groupingParamsOf(curatedRunId, decisionRunId)

  def groupingManifestOf(
      params: SortedMap[String, String],
      snaps: Vector[SnapshotRef] = snapshots
  ): RunManifest =
    RunManifest(SchemaVersions.RunManifest, "er.grouping", code, "e" * 64, snaps, params, 1)

  // ---------------------------------------------------------------- records

  /** A pending decision ID that is not in the decision run (only listed, never looked up). */
  val fakePending: String = "match:" + Canonical.sha256HexUtf8("fixture pending decision")

  def gprov(subject: String, schema: String, groupingRunId: String): Provenance = Provenance(
    SourceType.Derived,
    subject,
    snapshot,
    groupingRunId,
    schema,
    Some(groupingPolicy),
    None,
    None
  )

  def team(
      w: String,
      year: Option[Int],
      ids: Vector[String],
      unresolved: Vector[String],
      g: String
  ) =
    PublicationTeam(
      SchemaVersions.PublicationTeam,
      w,
      year,
      ids,
      ids.size + unresolved.size,
      unresolved,
      gprov(w, SchemaVersions.WorkAssignment, g)
    )

  def teams(g: String): Vector[PublicationTeam] = Vector(
    team(w1, Some(2018), Vector(rA, rB, rC), Vector(u1), g),
    team(w2, Some(2019), Vector(rA, rB), Vector.empty, g),
    team(w3, Some(2021), Vector(rC, rD), Vector.empty, g),
    team(w4, Some(2022), Vector(rE), Vector.empty, g),
    team(w5, None, Vector(rA, rD), Vector.empty, g)
  )

  def work(w: String, eligibility: GraphEligibility, g: String, d: String = decisionRunId) =
    WorkAssignment(
      SchemaVersions.WorkAssignment,
      w,
      w,
      true,
      WorkGroupStatus.Single,
      "grouping.single",
      1,
      eligibility,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector("grouping.single"),
      groupingPolicy,
      matchPolicy,
      d,
      assignedAt,
      gprov(w, SchemaVersions.WorkRecord, g)
    )

  def works(g: String, d: String = decisionRunId): Vector[WorkAssignment] =
    Vector(w1, w2, w3, w4, w5).map(work(_, GraphEligibility.Counted, g, d)) :+
      work(x1, GraphEligibility.EnrichmentOnly, g, d)

  /** (work, position, researcher ID or None for unresolved, group size) of every mention. */
  private val mentions: Vector[(String, Int, Option[String], Int)] = Vector(
    (w1, 0, Some(rA), 3),
    (w1, 1, Some(rB), 2),
    (w1, 2, Some(rC), 2),
    (w1, 3, None, 1),
    (w2, 0, Some(rA), 3),
    (w2, 1, Some(rB), 2),
    (w3, 0, Some(rC), 2),
    (w3, 1, Some(rD), 2),
    (w4, 0, Some(rE), 1),
    (w5, 0, Some(rA), 3),
    (w5, 1, Some(rD), 2)
  )

  private val anchorOf: Map[String, String] =
    Map(rA -> "00/0001", rB -> "00/0002", rC -> "00/0003", rD -> "00/0004", rE -> "00/0005")

  def researchers(g: String, d: String = decisionRunId): Vector[ResearcherAssignment] =
    mentions.map { case (w, pos, r, size) =>
      val m = ok(MentionId.of(w, pos))
      val status = r match {
        case None => AssignmentStatus.Unresolved
        case Some(_) if size == 1 => AssignmentStatus.Singleton
        case Some(_) => AssignmentStatus.Resolved
      }
      ResearcherAssignment(
        SchemaVersions.ResearcherAssignment,
        m,
        Some(w),
        Some(pos),
        status,
        r,
        "v1",
        r.map(_ => "dblp-pid"),
        r.map(anchorOf),
        size,
        if (status == AssignmentStatus.Resolved) Vector(decision(d).decisionId) else Vector.empty,
        Vector.empty,
        if (status == AssignmentStatus.Unresolved) Vector(fakePending) else Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector(s"grouping.${status.tag}"),
        groupingPolicy,
        matchPolicy,
        d,
        assignedAt,
        gprov(m, SchemaVersions.AuthorMention, g)
      )
    }

  /**
   * The resolved members' supporting decision of the default chain: an ACCEPTED decision of the
   * decision run `runId` (confidence 0.97), so every resolved researcher's identity confidence can
   * be derived (`IdentityConfidence.derive`: measured 0.97).
   */
  def decision(runId: String): MatchDecision =
    acceptedDecision(
      runId,
      "fixture candidate",
      ok(MentionId.of(w1, 0)),
      ok(MentionId.of(w2, 0)),
      0.97
    )

  /** An ACCEPTED researcher decision of run `runId` between two mentions, with `confidence`. */
  def acceptedDecision(
      runId: String,
      candidateSeed: String,
      source: String,
      target: String,
      confidence: Double
  ): MatchDecision = ok(
    MatchDecision.build(
      candidateId = "mcand:" + Canonical.sha256HexUtf8(candidateSeed),
      entityKind = MatchEntityKind.Researcher,
      sourceId = source,
      targetId = target,
      matchPath = MatchDecisionPath.DeterministicBibliographic,
      decision = MatchOutcome.Accepted,
      confidence = confidence,
      score = None,
      blockingVersion = "er-blocking.v2",
      featureVersion = "er-features.v2",
      features = SortedMap.empty,
      rulesFired = Vector("rule.same-name"),
      reasonCodes = Vector("reason.same-name"),
      conflicts = Vector.empty,
      adjudication = None,
      decidedAt = "2026-11-15T00:00:00Z",
      provenance = Provenance(
        SourceType.Derived,
        "mcand:" + Canonical.sha256HexUtf8(candidateSeed),
        snapshot,
        runId,
        "scholarchain.match-candidate.v1",
        Some(matchPolicy),
        None,
        None
      )
    )
  )

  // ---------------------------------------------------------------- writing runs

  def lines[T](xs: Vector[T])(using c: RecordContract[T]): String =
    xs.map(x => Json.write(x) + "\n").mkString

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  /**
   * Writes one published run directory: data files, `_run-manifest.json`, `_parts.json` (v1, from
   * the observed bytes, with `partsRunId`) when `withParts`, then `_SUCCESS`.
   */
  def writeRun(
      dir: Path,
      manifest: RunManifest,
      files: Vector[(String, String, String)],
      withParts: Boolean,
      partsRunId: Option[String] = None
  ): Path = {
    Files.createDirectories(dir)
    files.foreach { case (rel, _, text) =>
      Files.createDirectories(dir.resolve(rel).getParent)
      Files.write(dir.resolve(rel), utf8(text))
    }
    val rm = ok(Json.encode(manifest)) + "\n"
    Files.write(dir.resolve(PartManifest.RunManifestFile), utf8(rm))
    if (withParts) {
      val listed = (files.map { case (rel, schema, text) => (rel, schema, text) } :+
        (PartManifest.RunManifestFile, SchemaVersions.RunManifest, rm)).map {
        case (rel, schema, text) =>
          val o = ObservedFile.of(rel, utf8(text))
          PartFile(rel, schema, o.rowCount, o.byteCount, o.sha256)
      }
      val runId = partsRunId.getOrElse(ok(RunId.of(manifest)))
      val pm = ok(PartManifest.build(runId, listed))
      Files.write(dir.resolve(PartManifest.FileName), utf8(Json.write(pm) + "\n"))
    }
    Files.write(dir.resolve(PartManifest.SuccessMarker), Array.emptyByteArray)
    dir
  }

  // ---------------------------------------------------------------- the Hadoop curated layout

  /** The five stage directories, restated by hand (HD-P6-01 rule 3; Pipeline.scala `Stage`). */
  val hadoopStages: Vector[String] =
    Vector("normalize", "exact-ids", "teams", "blocking", "candidates")

  /** The ten required counters, restated by hand from modules/hadoop Counters.scala:26-37. */
  val requiredCounters: Vector[String] = Vector(
    "INPUT",
    "ACCEPTED",
    "REJECTED",
    "DUPLICATE",
    "CONFLICT",
    "OVERSIZE_BLOCK",
    "OVERSIZE_TEAM",
    "EMITTED_PAIR",
    "MISSING_KEY",
    "SCHEMA_FAILURE"
  )

  /** Every candidate job output (join/joined, pairs/pairs and the final outputs). */
  val allCandidates: Vector[String] = Vector("joined", "pairs", "final")

  /** The checksum sidecar of `p` (Hadoop LocalFileSystem: `.<name>.crc` beside it). */
  def crcOf(p: Path): Path = p.resolveSibling(s".${p.getFileName}.crc")

  /** Hadoop's local (checksummed) file system: the producer's library writes the crc sidecars. */
  lazy val hadoopLocal: FileSystem = FileSystem.getLocal(new Configuration())

  /**
   * Writes `bytes` at `p` with Hadoop's `LocalFileSystem`, which writes the real crc sidecar
   * `.<name>.crc` beside it (the layout HD-P6-01 found in the producer's output).
   */
  def withCrc(p: Path, bytes: Array[Byte]): Unit = {
    Files.createDirectories(p.getParent)
    val out = hadoopLocal.create(new HPath(p.toUri), true)
    try out.write(bytes)
    finally out.close()
  }

  def gzip(text: String): Array[Byte] = {
    val bytes = new java.io.ByteArrayOutputStream
    val out = new java.util.zip.GZIPOutputStream(bytes)
    try out.write(utf8(text))
    finally out.close()
    bytes.toByteArray
  }

  /** One gzip part file with its crc in `dir`. */
  def hadoopPart(dir: Path, name: String = "part-r-00000.gz"): Unit =
    withCrc(dir.resolve(name), gzip("{\"synthetic\":true}\n"))

  /**
   * A `_counters.json` as the producer writes it (Pipeline.scala writeReport, indent 2): the named
   * counters at 0 plus `extra` (for normalize, the `EMITTED.<leaf>` row counts).
   */
  def countersJson(
      stage: String,
      counters: Vector[String] = requiredCounters,
      extra: Vector[(String, Long)] = Vector.empty
  ): String =
    ujson.write(
      ujson.Obj(
        "stage" -> stage,
        "counters" -> ujson.Obj.from(
          (counters.map(k => k -> ujson.Num(0)) ++
            extra.map { case (k, n) => k -> ujson.Num(n.toDouble) }).sortBy(_._1)
        ),
        "stats" -> ujson.Obj()
      ),
      indent = 2
    )

  // ---------------------------------------------------------------- curated records

  /** Extra source fields of one curated work record (venue and topic assertions). */
  final case class WorkDecor(
      venueId: Option[String] = None,
      topicIds: Vector[String] = Vector.empty
  )

  /**
   * The JSON-lines texts of the consumed normalize leaves (leaf -> (rows, text)); a leaf without
   * records is absent (the producer creates a leaf only when it writes a record).
   */
  final case class CuratedFiles(leaves: Map[String, (Long, String)]) {
    def rows(leaf: String): Long = leaves.get(leaf).map(_._1).getOrElse(0L)
  }

  private def sourceOf(workId: String): (SourceType, String, String) =
    if (workId.startsWith("work:dblp:"))
      (SourceType.Dblp, workId.stripPrefix("work:dblp:"), snapshot)
    else if (workId.startsWith("work:doi:"))
      (SourceType.Crossref, workId.stripPrefix("work:doi:"), "crossref-2026-11-05")
    else (SourceType.OpenAlex, workId.stripPrefix("work:openalex:"), "openalex-2026-11-05")

  def sourceProvenance(source: SourceType, recordId: String, snap: String, c: String): Provenance =
    Provenance(source, recordId, snap, c, s"${source.tag}-raw/1.0.0", None, None, None)

  /**
   * One curated work record per work assignment (`work-record.v3`, source by the ID namespace), its
   * authors the mention assignments of the work in position order (synthetic names, the
   * `affiliations` of each mention), its year the team year, and its `decor`. Curated run `c`.
   */
  def sourceWorks(
      works: Vector[WorkAssignment],
      researchers: Vector[ResearcherAssignment],
      years: Map[String, Int],
      decor: Map[String, WorkDecor],
      affiliations: Map[String, Vector[String]],
      c: String
  ): Vector[WorkRecord] =
    works.map { w =>
      val (source, recordId, snap) = sourceOf(w.workId)
      val prov = sourceProvenance(source, recordId, snap, c)
      val authors = researchers
        .filter(r => r.workId.contains(w.workId))
        .sortBy(_.position.getOrElse(0))
        .map { r =>
          val pos = r.position.getOrElse(0)
          AuthorMention(
            SchemaVersions.AuthorMention,
            r.memberId,
            w.workId,
            pos,
            s"Synthetic Author $pos",
            s"synthetic author $pos",
            None,
            None,
            affiliations.getOrElse(r.memberId, Vector.empty),
            Vector.empty,
            prov
          )
        }
      val d = decor.getOrElse(w.workId, WorkDecor())
      WorkRecord(
        schemaVersion = SchemaVersions.WorkRecord,
        workId = w.workId,
        source = source,
        sourceRecordId = recordId,
        title = s"Synthetic Work $recordId",
        normalizedTitle = s"synthetic work ${recordId.toLowerCase}",
        year = years.get(w.workId),
        venueId = d.venueId,
        doi = None,
        arxivId = None,
        authors = authors,
        abstractTextRef = None,
        provenance = prov,
        recordType = Some("article"),
        topicIds = d.topicIds
      )
    }

  /** The consumed normalize leaves of the given curated records (empty leaves left out). */
  def curatedFiles(
      works: Vector[WorkRecord],
      venues: Vector[Venue],
      institutions: Vector[Institution],
      topics: Vector[Topic]
  ): CuratedFiles = {
    def leaf[T](name: String, xs: Vector[T])(using c: RecordContract[T]) =
      Option.when(xs.nonEmpty)(name -> (xs.size.toLong, lines(xs)))
    CuratedFiles(
      Vector(
        leaf("works", works),
        leaf("mentions", works.flatMap(_.authors)),
        leaf("venues", venues),
        leaf("institutions", institutions),
        leaf("topics", topics)
      ).flatten.toMap
    )
  }

  /**
   * One `authorship-record.v2` per identified mention assignment on a representative work (the
   * grouping run's authorship rule), with its assignment's supporting decisions and, when resolved,
   * the lowest of their confidences in `decisions` (0.5 for a decision not listed).
   */
  def authorshipsOf(
      works: Vector[WorkAssignment],
      researchers: Vector[ResearcherAssignment],
      decisions: Vector[MatchDecision],
      years: Map[String, Int],
      g: String,
      d: String
  ): Vector[AuthorshipRecordV2] = {
    val reps = works.filter(_.isRepresentative).map(_.workId).toSet
    val confidence = decisions.map(x => x.decisionId -> x.confidence).toMap
    researchers
      .filter(r =>
        ResearcherAssignment.isMention(r) && r.status.identified && r.workId.exists(reps)
      )
      .map { r =>
        val resolved = r.status == AssignmentStatus.Resolved
        AuthorshipRecordV2(
          SchemaVersions.AuthorshipRecordV2,
          r.workId.get,
          r.memberId,
          r.researcherId.get,
          r.position.get,
          years.get(r.workId.get),
          r.status,
          r.supportingDecisionIds,
          Option.when(resolved)(
            r.supportingDecisionIds.map(x => confidence.getOrElse(x, 0.5)).minOption.getOrElse(0.5)
          ),
          groupingPolicy,
          matchPolicy,
          d,
          gprov(r.memberId, SchemaVersions.ResearcherAssignment, g)
        )
      }
  }

  /**
   * Writes a Hadoop curated run directory in the producer's layout (HD-P6-01 rules 2-11; the same
   * synthetic shape as build-engineer's preflight fixture in project/SparkItRuns.scala): the run
   * manifest as the producer writes it (`Json.write`, no LF) with its crc; per stage an empty
   * `_SUCCESS` and a `_counters.json`, each with its crc; normalize works, mentions and rejects
   * (part-m and part-r), exact-ids links, teams teams/pairs/stats; `rounds` blocking rounds with
   * blocks and members per entity (part files 5 levels below the run), spill except in the last
   * round (and in the last round too when `spillInLast`), and stats; candidates join (joined when
   * `cands` names it), pairs (pairs, stats), an empty pairs/ job directory (`pairs-empty`) and
   * final (researcher, work, stats) when named. No `_parts.json` and no run-level `_SUCCESS`. The
   * consumed normalize leaves hold `curated` (one gzip part each) and the normalize counters
   * declare their rows.
   */
  def writeHadoopRun(
      dir: Path,
      manifest: RunManifest,
      rounds: Int = 2,
      cands: Vector[String] = allCandidates,
      spillInLast: Boolean = false,
      curated: CuratedFiles = CuratedFiles(Map.empty)
  ): Path = {
    Files.createDirectories(dir)
    withCrc(dir.resolve(PartManifest.RunManifestFile), utf8(Json.write(manifest)))
    val emitted = curated.leaves.toVector.map { case (leaf, (n, _)) => s"EMITTED.$leaf" -> n }
    hadoopStages.foreach { s =>
      withCrc(dir.resolve(s).resolve(PartManifest.SuccessMarker), Array.emptyByteArray)
      val extra = if (s == "normalize") emitted else Vector.empty
      withCrc(
        dir.resolve(s).resolve("_counters.json"),
        utf8(countersJson(s, requiredCounters, extra))
      )
    }
    curated.leaves.foreach { case (leaf, (_, text)) =>
      withCrc(dir.resolve(s"normalize/$leaf/part-r-00000.gz"), gzip(text))
    }
    hadoopPart(dir.resolve("normalize/rejects"), "part-m-00000.gz")
    hadoopPart(dir.resolve("normalize/rejects"))
    hadoopPart(dir.resolve("exact-ids/links"))
    hadoopPart(dir.resolve("teams/teams"))
    hadoopPart(dir.resolve("teams/pairs"))
    hadoopPart(dir.resolve("teams/stats"))
    (0 until rounds).foreach { i =>
      val rd = dir.resolve(s"blocking/round-$i")
      Vector("blocks", "members").foreach(k =>
        Vector("researcher", "work").foreach(e => hadoopPart(rd.resolve(s"$k/$e")))
      )
      if (i + 1 < rounds || spillInLast) hadoopPart(rd.resolve("spill"))
      hadoopPart(rd.resolve("stats"))
    }
    val c = dir.resolve("candidates")
    Files.createDirectories(c.resolve("join"))
    if (cands.contains("joined")) hadoopPart(c.resolve("join/joined"))
    if (cands.contains("pairs")) {
      hadoopPart(c.resolve("pairs/pairs"))
      hadoopPart(c.resolve("pairs/stats"))
    }
    // The pairs job ran but emitted no pair: an empty pairs/ job directory (rule 11 accepts it).
    if (cands.contains("pairs-empty")) Files.createDirectories(c.resolve("pairs"))
    if (cands.contains("final"))
      Vector("researcher", "work", "stats").foreach(x => hadoopPart(c.resolve(s"final/$x")))
    dir
  }

  /**
   * Mutations of the valid chain, one per refusal test. Run IDs propagate: a mutation that changes
   * the curated or decision manifest changes its run ID, and every downstream reference (decision
   * `inputRunId`, grouping `inputRunId` / `decisionRunId`, assignment `decisionRunId`, decision
   * provenance) follows it unless the mutation itself overrides that reference. So each mutation
   * differs from the valid chain only by the property its test refuses.
   *
   * @param groupingEdit
   *   edit of the grouping manifest parameters (applied to the propagated defaults)
   * @param decisionInputRunId
   *   the decision manifest's `inputRunId` (default: the curated run ID)
   * @param curatedSnapshots
   *   input snapshots of the curated run manifest
   * @param decisionSnapshots
   *   input snapshots of the decision run manifest
   * @param groupingSnapshots
   *   input snapshots of the grouping run manifest
   * @param curatedDirHex
   *   the 64-hex of the curated directory name (default: its manifest's run ID)
   * @param pinnedCuratedRunId
   *   the pinned curated run ID handed to the reader (default: the curated directory's run ID, so
   *   only a test that sets it exercises the pin refusal)
   * @param curatedStage
   *   the stage of the curated run manifest (the producer's is `hadoop.pipeline`)
   * @param curatedParentDir
   *   the name of the curated run's parent directory (default: `snapshot=<the manifest's DBLP
   *   snapshot>`, as the producer names it)
   * @param curatedRounds
   *   blocking rounds of the curated run
   * @param curatedCandidates
   *   candidate job outputs of the curated run (see [[writeHadoopRun]])
   * @param decisionPartSchema
   *   the record schema the decision part is listed under
   * @param works
   *   work assignments of (grouping run, decision run) instead of the default works
   * @param researchers
   *   researcher assignments of (grouping run, decision run) instead of the default ones
   * @param decisions
   *   the decisions of the decision run (given its run ID) instead of the default single decision
   * @param curatedSpillInLast
   *   also write a spill part in the last blocking round (rule 5 cases)
   * @param venues
   *   curated venue records (`normalize/venues`; none by default)
   * @param institutions
   *   curated institution records
   * @param topics
   *   curated topic records
   * @param decor
   *   venue and topic assertions of curated work records, by work ID
   * @param affiliations
   *   affiliation IDs of curated author mentions, by mention ID
   * @param withoutCuratedWorks
   *   work IDs whose curated work record is left out (canonical-input refusals)
   */
  final case class Spec(
      groupingEdit: SortedMap[String, String] => SortedMap[String, String] = identity,
      decisionRunIdOfDecisions: Option[String] = None,
      teams: Option[String => Vector[PublicationTeam]] = None,
      extraTeamLines: Vector[String] = Vector.empty,
      withTeamsPart: Boolean = true,
      groupingDirName: Option[String] = None,
      groupingPartsRunId: Option[String] = None,
      decisionInputRunId: Option[String] = None,
      curatedSnapshots: Vector[SnapshotRef] = snapshots,
      decisionSnapshots: Vector[SnapshotRef] = snapshots,
      groupingSnapshots: Vector[SnapshotRef] = snapshots,
      curatedDirHex: Option[String] = None,
      pinnedCuratedRunId: Option[String] = None,
      curatedStage: String = hadoopStage,
      curatedParentDir: Option[String] = None,
      curatedRounds: Int = 2,
      curatedCandidates: Vector[String] = allCandidates,
      decisionPartSchema: String = SchemaVersions.MatchDecision,
      works: Option[(String, String) => Vector[WorkAssignment]] = None,
      researchers: Option[(String, String) => Vector[ResearcherAssignment]] = None,
      decisions: Option[String => Vector[MatchDecision]] = None,
      curatedSpillInLast: Boolean = false,
      venues: Vector[Venue] = Vector.empty,
      institutions: Vector[Institution] = Vector.empty,
      topics: Vector[Topic] = Vector.empty,
      decor: Map[String, WorkDecor] = Map.empty,
      affiliations: Map[String, Vector[String]] = Map.empty,
      withoutCuratedWorks: Set[String] = Set.empty
  )

  final case class Written(
      runs: ArchiveRuns,
      groupingManifest: RunManifest,
      groupingRunId: String,
      curatedRunId: String,
      decisionRunId: String
  )

  def hexOf(runId: String): String = runId.stripPrefix("run:")

  /** Writes the chain under `root/archive` in the dev archive's relative layout. */
  def write(root: Path, spec: Spec = Spec()): Written = {
    val archive = root.resolve("archive")
    val cm = curatedManifestOf(spec.curatedSnapshots, spec.curatedStage)
    val c = ok(RunId.of(cm))
    val dm = decisionManifestOf(spec.decisionInputRunId.getOrElse(c), spec.decisionSnapshots)
    val d = ok(RunId.of(dm))
    val gm = groupingManifestOf(spec.groupingEdit(groupingParamsOf(c, d)), spec.groupingSnapshots)
    val g = ok(RunId.of(gm))
    val curatedHex = spec.curatedDirHex.getOrElse(hexOf(c))
    val dblp = cm.inputSnapshots.find(_.source == SourceType.Dblp).map(_.snapshot)
    val parent = spec.curatedParentDir.getOrElse(s"snapshot=${dblp.getOrElse(snapshot)}")
    val decisionRun = spec.decisionRunIdOfDecisions.getOrElse(d)
    val decisions = spec.decisions.map(_(decisionRun)).getOrElse(Vector(decision(decisionRun)))
    val teamRecords = spec.teams.map(_(g)).getOrElse(teams(g))
    val researcherRecords = spec.researchers.map(_(g, d)).getOrElse(researchers(g, d))
    val workRecords = spec.works.map(_(g, d)).getOrElse(works(g, d))
    val years = teamRecords.flatMap(t => t.year.map(t.workId -> _)).toMap
    val curatedWorks = sourceWorks(
      workRecords.filterNot(w => spec.withoutCuratedWorks.contains(w.workId)),
      researcherRecords,
      years,
      spec.decor,
      spec.affiliations,
      c
    )
    val curated = writeHadoopRun(
      archive.resolve(s"curated/hadoop/$parent/run=$curatedHex"),
      cm,
      spec.curatedRounds,
      spec.curatedCandidates,
      spec.curatedSpillInLast,
      curatedFiles(curatedWorks, spec.venues, spec.institutions, spec.topics)
    )
    val decisionDir = writeRun(
      archive.resolve(s"er-decisions/run=${hexOf(d)}"),
      dm,
      Vector(("decisions/researcher/part-00000.jsonl", spec.decisionPartSchema, lines(decisions))),
      withParts = true
    )
    val teamText = lines(teamRecords) + spec.extraTeamLines.map(_ + "\n").mkString
    val groupingFiles = Vector(
      (
        "assignments/researcher/part-00000.jsonl",
        SchemaVersions.ResearcherAssignment,
        lines(researcherRecords)
      ),
      (
        "assignments/work/part-00000.jsonl",
        SchemaVersions.WorkAssignment,
        lines(workRecords)
      ),
      (
        "authorships/part-00000.jsonl",
        SchemaVersions.AuthorshipRecordV2,
        lines(authorshipsOf(workRecords, researcherRecords, decisions, years, g, d))
      )
    ) ++ (if (spec.withTeamsPart)
            Vector(("teams/part-00000.jsonl", SchemaVersions.PublicationTeam, teamText))
          else Vector.empty)
    val gDir = spec.groupingDirName.getOrElse(
      s"run=${spec.groupingPartsRunId.map(hexOf).getOrElse(hexOf(g))}"
    )
    val grouping = writeRun(
      archive.resolve(s"er-assignments/$gDir"),
      gm,
      groupingFiles,
      withParts = true,
      partsRunId = spec.groupingPartsRunId
    )
    Written(
      ok(
        SyntheticCuratedPin.archiveRuns(
          archive.toString,
          curated.toString,
          decisionDir.toString,
          grouping.toString,
          spec.pinnedCuratedRunId.getOrElse(s"run:$curatedHex")
        )
      ),
      gm,
      g,
      c,
      d
    )
  }

  /** The benchmark pair list of the fixture, bound to grouping run `g`. */
  def benchmarkPairs(g: String): BenchmarkPairList = BenchmarkPairList(
    SchemaVersions.BenchmarkPairList,
    "benchmark-pairs-fixture.v1",
    Vector(BenchmarkPair(rA, rD)).map(p =>
      if (Canonical.compareUtf8(p.researcherA, p.researcherB) < 0) p
      else BenchmarkPair(p.researcherB, p.researcherA)
    ),
    Provenance(
      SourceType.Derived,
      "benchmark-pairs-fixture.v1",
      snapshot,
      g,
      SchemaVersions.ResearcherAssignment,
      None,
      None,
      None
    )
  )
}
