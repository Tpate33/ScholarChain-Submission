package scholarchain.storage.load

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import scholarchain.core.*
import scholarchain.core.config.AnalysisRunSettings
import scholarchain.storage.model.{Bytes, GraphValue, StoreError}
import scholarchain.storage.parquet.ParquetReadBounds

/**
 * Upstream records of an analysis run (SE-P7-06 item 6, SE-P7-07; CR-P7-08 m2): the grouping run
 * and the curated input run, each read and verified from its own
 * [[scholarchain.storage.port.RunSource]] by [[UpstreamRuns]]. A plain class whose constructor is
 * `private[load]`: the only way to one is [[UpstreamRuns.upstream]] from a verified [[GroupingRun]]
 * and [[CuratedRun]], so no caller can supply records that make a check pass. The verifier binds it
 * to the analysis run before use ([[SummaryEvidenceCheck.bound]]).
 */
final class SummaryUpstream private[load] (
    val grouping: GroupingRun,
    val curated: CuratedRun,
    val benchmarkPairs: Option[VerifiedBenchmarkPairs]
) {
  def groupingManifest: RunManifest = grouping.manifest
  def graphInput: GraphInput = grouping.graphInput
  def sourceWorks: Vector[WorkRecord] = curated.works

  /** The canonical-table input of [[CanonicalGraphValidation.checkTables]]. */
  def canonicalInput: CanonicalGraphInput =
    CanonicalGraphInput(
      grouping.graphInput.works,
      grouping.graphInput.researchers,
      grouping.authorships,
      curated.works,
      curated.mentions,
      curated.venues,
      curated.institutions,
      curated.topics
    )

  override def toString: String =
    s"SummaryUpstream(grouping ${grouping.runId}, curated ${curated.runId}, " +
      s"benchmark pairs ${benchmarkPairs.map(_.list.listVersion).getOrElse("absent")})"
}

/**
 * Configuration of the record-level checks (SE-P7-06). No default: the loader supplies it.
 *
 * @param settings
 *   the analysis settings the run was produced with; refused unless its `configDigest` equals the
 *   run manifest's (so the configuration is the run's own, by SHA-256)
 * @param parquet
 *   the Parquet read bounds
 * @param upstream
 *   the grouping and curated records for summary-evidence; None leaves it NOT performed
 */
final case class VerificationContext(
    settings: AnalysisRunSettings,
    parquet: ParquetReadBounds,
    upstream: Option[SummaryUpstream]
)

/** What the record-level checks decided (SE-P7-06). */
final case class RecordCheckOutcome(
    passed: Map[TargetStore, Set[DeferredCheck]],
    rejections: Vector[Rejection],
    parts: Vector[DecodedPart]
)

/**
 * The record-level deferred checks over the Parquet parts and the Neptune CSV of one structurally
 * valid run (SE-P7-06 items 2-6). Run by [[LoadVerifier]] (step 12) only when steps 1-11 found
 * nothing and a [[VerificationContext]] is given. Every check is really performed on the run's
 * bytes; a check is marked passed for a store only when it ran over every part the check needs and
 * found nothing. Any problem rejects the run.
 *
 *   - `parquet-rows` (dynamodb, s3, s3-vectors): every Parquet item of the store decodes
 *     ([[ParquetRecords.decodePart]]); its footer row count and its decoded row count equal the
 *     declared rows.
 *   - `row-contracts` (Parquet stores): every decoded record of the store passes its core record
 *     contract and has the item's record schema; its provenance names this run and the configured
 *     graph policy; its window is configured; every lineage equals the run chain the run manifest
 *     states (grouping, decision and input run IDs, code version, config digest) and one lineage
 *     per run; and the core cross-record validators that need no upstream record:
 *     [[StructureValidation.checkWindowStructures]], [[AnalysisValidation.checkCommunities]],
 *     [[CanonicalGraphValidation.checkStructure]], unique summary input IDs.
 *   - `graph-id-parity` (neptune, s3): the Neptune CSV vertices and edges equal the `graph-nodes` /
 *     `graph-edges` records one for one (ID, label, endpoints, evidence, properties, run); every
 *     record carries the configured graph policy, whose edge-ID and coauthor-ID formulas the record
 *     contracts check.
 *   - `evidence-trace` (neptune, dynamodb, s3): [[EvidenceTrace.check]].
 *   - `summary-evidence` (s3, s3-vectors): [[SummaryEvidenceCheck]] over the bound
 *     [[SummaryUpstream]]; not performed without one.
 *   - SE-P7-07, all with a bound [[SummaryUpstream]] only: the binding itself (`upstream-binding`:
 *     run IDs, non-empty input, the grouping half of every lineage); `analysis-recompute` (neptune,
 *     dynamodb, s3); `canonical-tables` (neptune, s3); `paths-run-chain` (dynamodb, s3), only when
 *     the benchmark pair list is present, else NOT performed.
 */
object ParquetChecks {

  private def corrupt(es: Vector[ValidationError]): Vector[String] = es.map(_.message)

  /** The parts of `t`'s load that are Parquet, in item order. */
  private def parquetItems(m: StoreLoadManifest): Vector[LoadItem] =
    m.items.filter(_.format == PartFormat.Parquet)

  /** The analysis outputs of a set of decoded parts. */
  def outputsOf(parts: Vector[DecodedPart]): AnalysisOutputs = outputsOfRecords(
    parts.flatMap(_.records)
  )

  /** The analysis outputs of a set of decoded records. */
  def outputsOfRecords(all: Vector[Product]): AnalysisOutputs = {
    def of[A](pf: PartialFunction[Product, A]): Vector[A] = all.collect(pf)
    AnalysisOutputs(
      metrics = of { case x: ResearcherMetricsV2 => x },
      edges = of { case x: CoauthorEdgeV2 => x },
      components = of { case x: ComponentMembership => x },
      communities = of { case x: CommunityMembership => x },
      bridges = of { case x: BridgeMeasure => x },
      coreness = of { case x: Coreness => x },
      paths = of { case x: EvidencePath => x },
      windows = of { case x: AnalysisWindow => x },
      summaryInputs = of { case x: SemanticSummaryInput => x },
      nodes = of { case x: GraphNodeV2 => x },
      graphEdges = of { case x: GraphEdgeV2 => x },
      windowStructures = of { case x: WindowStructure => x },
      pathLengthEstimates = of { case x: PathLengthEstimate => x },
      windowComparisons = of { case x: WindowComparison => x },
      temporalChanges = of { case x: ResearcherTemporalChange => x },
      entitySummaryInputs = of { case x: EntitySummaryInput => x }
    )
  }

  /** The core contract errors of one decoded record, and its schema version and window. */
  private def contractOf(
      p: Product,
      at: String
  ): (Vector[ValidationError], String, Option[TimeWindow]) =
    p match {
      case x: CoauthorEdgeV2 =>
        (CoauthorEdgeV2.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: ResearcherMetricsV2 =>
        (ResearcherMetricsV2.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: ComponentMembership =>
        (ComponentMembership.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: CommunityMembership =>
        (CommunityMembership.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: BridgeMeasure =>
        (BridgeMeasure.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: Coreness => (Coreness.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: EvidencePath =>
        (EvidencePath.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: AnalysisWindow =>
        (AnalysisWindow.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: SemanticSummaryInput =>
        (SemanticSummaryInput.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: GraphNodeV2 => (GraphNodeV2.contract.validateAt(x, at), x.schemaVersion, None)
      case x: GraphEdgeV2 => (GraphEdgeV2.contract.validateAt(x, at), x.schemaVersion, None)
      case x: WindowStructure =>
        (WindowStructure.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: PathLengthEstimate =>
        (PathLengthEstimate.contract.validateAt(x, at), x.schemaVersion, Some(x.window))
      case x: WindowComparison =>
        (WindowComparison.contract.validateAt(x, at), x.schemaVersion, None)
      case x: ResearcherTemporalChange =>
        (ResearcherTemporalChange.contract.validateAt(x, at), x.schemaVersion, None)
      case x: EntitySummaryInput =>
        (EntitySummaryInput.contract.validateAt(x, at), x.schemaVersion, None)
      case other =>
        (
          Vector(
            ValidationError.InvalidValue(at, s"${other.productPrefix} is not an analysis record")
          ),
          "",
          None
        )
    }

  /** The lineage and provenance of one decoded record (every analysis record has both). */
  private def chainOf(p: Product): Option[(AnalysisLineage, Provenance)] =
    outputsOfRecords(Vector(p)).lineageAndProvenance.headOption.map(x => (x._2, x._3))

  /** The run chain the run manifest states (the grouping-manifest fields are not in the run). */
  private def chainProblems(
      at: String,
      l: AnalysisLineage,
      rm: RunManifest,
      settings: AnalysisRunSettings
  ): Vector[String] = {
    val K = AnalysisValidation.AnalysisManifestKeys
    val params = Option(rm.parameters).getOrElse(SortedMap.empty[String, String])
    def eq(name: String, got: String, exp: Option[String]) =
      if (exp.contains(got)) Vector.empty
      else Vector(s"$at.lineage.$name: $got is not the run manifest's ${exp.getOrElse("(absent)")}")
    if (l == null) Vector(s"$at.lineage: missing")
    else
      eq("groupingRunId", l.groupingRunId, params.get(K.GroupingRunId)) ++
        eq("decisionRunId", l.decisionRunId, params.get(K.DecisionRunId)) ++
        eq("inputRunId", l.inputRunId, params.get(K.InputRunId)) ++
        eq("codeVersion", l.codeVersion, Some(rm.codeVersion)) ++
        eq("configDigest", l.configDigest, Some(rm.configDigest)) ++
        eq("identityVersion", l.identityVersion, Some(settings.identityVersion))
  }

  def run(
      runId: String,
      runManifest: RunManifest,
      loads: Vector[StoreLoadManifest],
      neptune: Option[NeptuneGraph],
      read: LoadItem => Either[StoreError, Bytes],
      ctx: VerificationContext
  ): RecordCheckOutcome = {
    val rejections = mutable.ArrayBuffer.empty[Rejection]
    def reject(c: VerificationCheck, subject: String, why: String): Unit =
      rejections += Rejection(c, subject, StoreError.Corrupt(why))
    val settings = ctx.settings

    if (settings.configDigest != runManifest.configDigest) {
      rejections += Rejection(
        VerificationCheck.RunSettings,
        "settings",
        StoreError.Refused(
          s"the analysis settings have config digest ${settings.configDigest}; the run manifest " +
            s"records ${runManifest.configDigest}: they are not the run's configuration"
        )
      )
      return RecordCheckOutcome(Map.empty, rejections.toVector, Vector.empty)
    }

    // Decode every Parquet item once (the s3 load lists every Parquet part of the run).
    val items: Vector[LoadItem] =
      loads
        .flatMap(parquetItems)
        .groupBy(_.path)
        .values
        .map(_.head)
        .toVector
        .sortWith((a, b) => Canonical.compareUtf8(a.path, b.path) < 0)
    val decoded: Map[String, DecodedPart] = items.flatMap { it =>
      read(it).flatMap(b =>
        ParquetRecords.decodePart(it.path, it.recordSchemaVersion, b, ctx.parquet)
      ) match {
        case Left(e) =>
          rejections += Rejection(VerificationCheck.ParquetRowCount, it.path, e); None
        case Right(d) =>
          if (d.footer.rowCount != it.rowCount)
            reject(
              VerificationCheck.ParquetRowCount,
              it.path,
              s"footer row count ${d.footer.rowCount}, declared ${it.rowCount}"
            )
          else if (d.decodedRows != it.rowCount)
            reject(
              VerificationCheck.ParquetRowCount,
              it.path,
              s"decoded ${d.decodedRows} rows, declared ${it.rowCount}"
            )
          Some(it.path -> d)
      }
    }.toMap
    if (rejections.nonEmpty) return RecordCheckOutcome(Map.empty, rejections.toVector, Vector.empty)

    val passed = mutable.Map.empty[TargetStore, Set[DeferredCheck]]
    def pass(t: TargetStore, c: DeferredCheck): Unit = passed(t) =
      passed.getOrElse(t, Set.empty) + c
    def partsOf(t: TargetStore): Vector[DecodedPart] =
      loads.find(_.targetStore == t).toVector.flatMap(parquetItems).map(i => decoded(i.path))
    val parquetStores = loads.filter(m => parquetItems(m).nonEmpty).map(_.targetStore)

    // parquet-rows: every Parquet item of the store decoded with its declared rows (above).
    parquetStores.foreach(pass(_, DeferredCheck.ParquetRows))

    // row-contracts, per Parquet store.
    val lineages = mutable.Set.empty[AnalysisLineage]
    parquetStores.foreach { t =>
      val before = rejections.size
      val parts = partsOf(t)
      parts.foreach { d =>
        d.records.zipWithIndex.foreach { case (r, i) =>
          val at = s"${d.path}#${i + 1}"
          val (errors, version, window) = contractOf(r, at)
          corrupt(errors).foreach(reject(VerificationCheck.ParquetRowContract, at, _))
          if (version != d.recordSchemaVersion)
            reject(
              VerificationCheck.ParquetRowContract,
              at,
              s"schemaVersion $version, item ${d.recordSchemaVersion}"
            )
          window.foreach { w =>
            if (!settings.windows.contains(w))
              reject(
                VerificationCheck.ParquetRowContract,
                at,
                s"window ${w.startYear}-${w.endYear} is not configured"
              )
          }
          chainOf(r) match {
            case None =>
              reject(VerificationCheck.ParquetRowContract, at, "no lineage and provenance")
            case Some((l, pr)) =>
              lineages += l
              if (pr == null || pr.runId != runId)
                reject(
                  VerificationCheck.ParquetRowContract,
                  at,
                  s"provenance.runId is not the verified run $runId"
                )
              if (Option(pr).flatMap(_.policyVersion) != Some(settings.graphPolicyVersion))
                reject(
                  VerificationCheck.ParquetRowContract,
                  at,
                  s"provenance.policyVersion is not the configured ${settings.graphPolicyVersion}"
                )
              chainProblems(at, l, runManifest, settings).foreach(
                reject(VerificationCheck.ParquetRowContract, at, _)
              )
          }
        }
      }
      val out = outputsOf(parts)
      val cross =
        (if (out.windowStructures.nonEmpty)
           StructureValidation.checkWindowStructures(
             out.windowStructures,
             out,
             settings,
             runManifest
           )
         else Vector.empty) ++
          (if (out.communities.nonEmpty)
             AnalysisValidation.checkCommunities(out.communities, out.components, settings)
           else Vector.empty) ++
          (if (out.nodes.nonEmpty || out.graphEdges.nonEmpty)
             CanonicalGraphValidation.checkStructure(out.nodes, out.graphEdges)
           else Vector.empty) ++
          duplicateIds("summaryInputs.inputId", out.summaryInputs.map(_.inputId)) ++
          duplicateIds("entitySummaryInputs.inputId", out.entitySummaryInputs.map(_.inputId))
      corrupt(cross).foreach(reject(VerificationCheck.ParquetRowContract, t.tag, _))
      if (rejections.size == before) pass(t, DeferredCheck.RowContracts)
    }
    if (lineages.size > 1)
      reject(
        VerificationCheck.ParquetRowContract,
        "run",
        s"${lineages.size} different lineages: one run chain per run"
      )

    val all = outputsOf(decoded.values.toVector)

    // graph-id-parity (neptune, s3).
    val hasGraphParts = all.nodes.nonEmpty || all.graphEdges.nonEmpty
    neptune.filter(_ => hasGraphParts && rejections.isEmpty).foreach { g =>
      val problems = GraphParity.check(g, all.nodes, all.graphEdges, settings)
      problems.foreach { case (s, why) => reject(VerificationCheck.GraphIdParity, s, why) }
      if (problems.isEmpty)
        Vector(TargetStore.NeptuneBulkLoad, TargetStore.S3).foreach { t =>
          if (loads.exists(_.targetStore == t)) pass(t, DeferredCheck.GraphIdParity)
        }
    }

    // evidence-trace (neptune from its CSV; dynamodb and s3 from the graph-edges records).
    if (rejections.isEmpty && all.edges.nonEmpty) {
      neptune.foreach { g =>
        val ps = EvidenceTrace.check(EvidenceTrace.fromCsv(g), all.edges, Vector.empty)
        ps.foreach { case (s, why) => reject(VerificationCheck.EvidenceTrace, s, why) }
        if (ps.isEmpty) pass(TargetStore.NeptuneBulkLoad, DeferredCheck.EvidenceTrace)
      }
      if (hasGraphParts) {
        val ps = EvidenceTrace.check(
          EvidenceTrace.fromRecords(all.nodes, all.graphEdges),
          all.edges,
          all.paths
        )
        ps.foreach { case (s, why) => reject(VerificationCheck.EvidenceTrace, s, why) }
        if (ps.isEmpty)
          Vector(TargetStore.DynamoDb, TargetStore.S3).foreach { t =>
            if (loads.exists(_.targetStore == t)) pass(t, DeferredCheck.EvidenceTrace)
          }
      }
    }

    // SE-P7-07: every check below needs the bound upstream; none runs on a rejected run.
    if (rejections.isEmpty) ctx.upstream.foreach { up =>
      def passFor(c: DeferredCheck, stores: TargetStore*): Unit =
        stores.foreach(t => if (loads.exists(_.targetStore == t)) pass(t, c))
      def checked(c: VerificationCheck, subject: String, ps: Vector[String]): Boolean = {
        ps.foreach(reject(c, subject, _))
        ps.isEmpty
      }
      val gi = up.graphInput
      val binding = SummaryEvidenceCheck.bound(up, runManifest) ++ groupingLineage(
        lineages.toVector,
        up.groupingManifest,
        runManifest
      )
      if (checked(VerificationCheck.UpstreamBinding, "upstream", binding)) {
        // summary-evidence (s3, s3-vectors).
        val se = SummaryEvidenceCheck
          .check(
            SummaryEvidenceInputs(
              all.summaryInputs,
              all,
              gi,
              all.entitySummaryInputs,
              all.nodes,
              all.graphEdges,
              gi.works,
              up.sourceWorks,
              settings
            )
          )
          .map(_.message)
        if (checked(VerificationCheck.SummaryEvidence, "summary-inputs", se))
          passFor(DeferredCheck.SummaryEvidence, TargetStore.S3, TargetStore.S3Vectors)

        // analysis-recompute (neptune, dynamodb, s3): the core validators, unchanged.
        val ar = corrupt(
          AnalysisValidation.checkEdges(all.edges, gi, settings) ++
            AnalysisValidation.checkMetrics(all.metrics, gi, settings) ++
            AnalysisValidation.checkComponents(all.components, gi, settings) ++
            AnalysisValidation.checkBridges(all.bridges, all.communities, gi, settings) ++
            AnalysisValidation.checkCoreness(all.coreness, gi, settings) ++
            AnalysisValidation.checkWindows(all.windows, all, gi, settings) ++
            StructureValidation.checkTemporal(
              all.windowComparisons,
              all.temporalChanges,
              all,
              gi,
              settings
            ) ++
            StructureValidation.checkPathLengthEstimates(all.pathLengthEstimates, gi, settings)
        )
        if (checked(VerificationCheck.AnalysisRecompute, "analysis", ar))
          passFor(
            DeferredCheck.AnalysisRecompute,
            TargetStore.NeptuneBulkLoad,
            TargetStore.DynamoDb,
            TargetStore.S3
          )

        // canonical-tables (neptune, s3).
        val ct = corrupt(
          CanonicalGraphValidation.checkTables(
            all.nodes,
            all.graphEdges,
            up.canonicalInput,
            all.edges,
            settings
          )
        )
        if (checked(VerificationCheck.CanonicalTables, "graph-tables", ct))
          passFor(DeferredCheck.CanonicalTables, TargetStore.NeptuneBulkLoad, TargetStore.S3)

        // paths-run-chain (dynamodb, s3): only with the benchmark pair list; otherwise NOT
        // performed (left open, never skipped as passed). SE-P7-08: the list read by
        // BenchmarkPairsReader is first bound to this run (benchmark-pairs): its core digest equals
        // the run manifest's benchmarkPairsDigest, it names the grouping run, and every pair is two
        // identified researchers of that run. A failure rejects the run; checkPaths is not run.
        up.benchmarkPairs.foreach { bp =>
          val pairs = bp.list
          val binding = benchmarkBinding(bp, up, runManifest)
          if (checked(VerificationCheck.BenchmarkPairs, pairs.listVersion, binding)) {
            val pr = corrupt(
              AnalysisValidation.checkPaths(all.paths, all.metrics, gi, settings, pairs) ++
                AnalysisValidation.checkRunChain(
                  runManifest,
                  up.groupingManifest,
                  all,
                  settings,
                  pairs
                )
            )
            if (checked(VerificationCheck.PathsRunChain, "paths", pr))
              passFor(DeferredCheck.PathsRunChain, TargetStore.DynamoDb, TargetStore.S3)
          }
        }
      }
    }

    if (rejections.nonEmpty) RecordCheckOutcome(Map.empty, rejections.toVector, Vector.empty)
    else
      RecordCheckOutcome(
        passed.toMap,
        Vector.empty,
        decoded.values.toVector.sortWith((a, b) => Canonical.compareUtf8(a.path, b.path) < 0)
      )
  }

  /**
   * SE-P7-08: the benchmark pair list belongs to this run. Its [[BenchmarkPairList.digest]] (core)
   * equals the analysis run manifest's `benchmarkPairsDigest`; its `provenance.runId` is the bound
   * grouping run; every pair is two identified researchers of that grouping run (the analysis job's
   * own binding rule). Each problem is stated with both values; none is repaired.
   */
  private[load] def benchmarkBinding(
      bp: VerifiedBenchmarkPairs,
      up: SummaryUpstream,
      analysis: RunManifest
  ): Vector[String] = {
    val K = AnalysisValidation.AnalysisManifestKeys
    val recorded =
      Option(analysis.parameters).flatMap(_.get(K.BenchmarkPairsDigest))
    val l = bp.list
    val digest =
      if (recorded.contains(bp.digest)) Vector.empty
      else
        Vector(
          s"the benchmark pair list ${l.listVersion} has digest ${bp.digest} " +
            s"(BenchmarkPairList.digest); the analysis run manifest records " +
            s"${K.BenchmarkPairsDigest} ${recorded.getOrElse("(absent)")}"
        )
    val run = Option(l.provenance).map(_.runId) match {
      case Some(r) if r == up.grouping.runId => Vector.empty
      case other =>
        Vector(
          s"provenance.runId is ${other.getOrElse("(absent)")}, the grouping run is ${up.grouping.runId}"
        )
    }
    val identified = up.graphInput.researchers
      .filter(r => r != null && r.status != null && r.status.identified)
      .flatMap(_.researcherId)
      .toSet
    val ids = l.pairs.zipWithIndex.flatMap { case (p, i) =>
      Vector(p.researcherA, p.researcherB)
        .filterNot(identified.contains)
        .map(r => s"pairs[$i]: $r is not an identified researcher of the grouping run")
    }
    digest ++ run ++ ids
  }

  /**
   * The grouping-manifest half of every record lineage (SE-P7-07): grouping policy, match policy
   * and identity version equal the grouping manifest's parameters, and the analysis run's input
   * snapshots are the grouping run's. (The analysis-manifest half is a row contract; the whole run
   * chain, with the benchmark digest, is checkRunChain in paths-run-chain.)
   */
  private def groupingLineage(
      lineages: Vector[AnalysisLineage],
      grouping: RunManifest,
      analysis: RunManifest
  ): Vector[String] = {
    val G = AnalysisValidation.GroupingManifestKeys
    val gp = Option(grouping.parameters).getOrElse(SortedMap.empty[String, String])
    val snapshots =
      if (analysis.inputSnapshots.toSet == grouping.inputSnapshots.toSet) Vector.empty
      else Vector("analysis run input snapshots differ from the grouping run's")
    snapshots ++ lineages.flatMap { l =>
      Vector(
        ("groupingPolicyVersion", l.groupingPolicyVersion, gp.get(G.GroupingPolicyVersion)),
        ("matchPolicyVersion", l.matchPolicyVersion, gp.get(G.MatchPolicyVersion)),
        ("identityVersion", l.identityVersion, gp.get(G.IdentityVersion))
      ).collect {
        case (n, got, exp) if !exp.contains(got) =>
          s"lineage.$n $got is not the grouping manifest's ${exp.getOrElse("(absent)")}"
      }
    }.distinct
  }

  private def duplicateIds(field: String, ids: Vector[String]): Vector[ValidationError] =
    ids.groupBy(identity).toVector.filter(_._2.size > 1).sortBy(_._1).map { case (k, xs) =>
      ValidationError.InvalidValue(field, s"$k appears ${xs.size} times")
    }
}

/**
 * Deferred check `graph-id-parity` (SE-P7-06 item 4): the Neptune CSV rows are exactly the Spark
 * `graph-nodes` / `graph-edges` records. Per ID: the same label, endpoints and evidence pointer,
 * the same profile properties (the CSV `Int` columns compared by their decimal text, which the
 * profiles fix to canonical form) and the same run; no ID on one side only. Every record carries
 * the configured graph policy; the record contracts (row-contracts) check the edge-ID and
 * coauthor-ID formulas with that policy.
 */
object GraphParity {

  /** A CSV value as its text; a null (never produced by the CSV parser) never equals a property. */
  private[load] def text(v: GraphValue): String = v match {
    case GraphValue.IntValue(n) => n.toString
    case GraphValue.Str(s) => s
    case GraphValue.Null => "\u0000null"
  }

  def check(
      g: NeptuneGraph,
      nodes: Vector[GraphNodeV2],
      edges: Vector[GraphEdgeV2],
      settings: AnalysisRunSettings
  ): Vector[(String, String)] = {
    val out = Vector.newBuilder[(String, String)]
    def bad(s: String, why: String): Unit = out += s -> why
    val policy = settings.graphPolicyVersion
    nodes.foreach { n =>
      if (Option(n.provenance).flatMap(_.policyVersion) != Some(policy))
        bad(s"graph-nodes[${n.nodeId}]", s"policyVersion is not the configured $policy")
    }
    edges.foreach { e =>
      if (Option(e.provenance).flatMap(_.policyVersion) != Some(policy))
        bad(s"graph-edges[${e.edgeId}]", s"policyVersion is not the configured $policy")
    }
    val csvV = g.vertices.groupBy(_.id)
    val parV = nodes.groupBy(_.nodeId)
    (csvV.keySet ++ parV.keySet).toVector
      .sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
      .foreach { id =>
        (csvV.get(id), parV.get(id)) match {
          case (Some(Vector(v)), Some(Vector(n))) =>
            val props = (v.properties - "runId").map { case (k, x) => k -> text(x) }
            if (v.label != n.label)
              bad(v.path + "#" + v.record, s"~label ${v.label.tag}, record ${n.label.tag}")
            if (props != n.properties)
              bad(v.path + "#" + v.record, s"properties differ from the graph-nodes record $id")
            if (v.properties.get("runId").map(text) != Option(n.provenance).map(_.runId))
              bad(v.path + "#" + v.record, s"runId differs from the graph-nodes record $id")
          case (Some(vs), None) =>
            bad(vs.head.path + "#" + vs.head.record, s"vertex $id has no graph-nodes record")
          case (None, Some(_)) => bad(s"graph-nodes[$id]", "no Neptune vertex row")
          case _ => bad(id, "more than one row or record")
        }
      }
    val csvE = g.edges.groupBy(_.id)
    val parE = edges.groupBy(_.edgeId)
    (csvE.keySet ++ parE.keySet).toVector
      .sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
      .foreach { id =>
        (csvE.get(id), parE.get(id)) match {
          case (Some(Vector(r)), Some(Vector(e))) =>
            val at = r.path + "#" + r.record
            val props = (r.properties - "runId" - "sourceEvidence").map { case (k, x) =>
              k -> text(x)
            }
            if (r.label != e.edgeType) bad(at, s"~label ${r.label.tag}, record ${e.edgeType.tag}")
            if (r.fromId != e.fromId || r.toId != e.toId)
              bad(at, s"endpoints differ from the graph-edges record $id")
            if (r.properties.get("sourceEvidence").map(text) != Some(e.sourceEvidence))
              bad(at, s"sourceEvidence differs from the graph-edges record $id")
            if (props != e.properties) bad(at, s"properties differ from the graph-edges record $id")
            if (r.properties.get("runId").map(text) != Option(e.provenance).map(_.runId))
              bad(at, s"runId differs from the graph-edges record $id")
          case (Some(rs), None) =>
            bad(rs.head.path + "#" + rs.head.record, s"edge $id has no graph-edges record")
          case (None, Some(_)) => bad(s"graph-edges[$id]", "no Neptune edge row")
          case _ => bad(id, "more than one row or record")
        }
      }
    out.result()
  }
}

/**
 * Deferred check `evidence-trace` (SE-P7-06 item 5; gate G-EV, README L1428): every COAUTHORED_WITH
 * edge traces to the supporting-work evidence it summarizes.
 *
 *   - its `sourceEvidence` is the ID of a `coauthor-edge.v2` record of the run (a broken pointer is
 *     refused) with the same canonical pair, the same window, the same joint publication count and
 *     the same first and last collaboration years;
 *   - every supporting work of that record is a Work vertex of the run, and both endpoints have an
 *     AUTHORED edge to it;
 *   - every coauthor-edge record is cited by exactly one COAUTHORED_WITH edge;
 *   - every evidence-path hop cites a coauthor-edge record of the path's window joining the hop's
 *     two researchers, and shows only supporting works of it.
 *
 * Everything it needs is in the analysis run directory: the Neptune CSV (or the `graph-nodes` /
 * `graph-edges` records), and the `edges` and `evidence-paths` Parquet parts.
 */
object EvidenceTrace {

  /** One COAUTHORED_WITH edge as either side states it. */
  final case class Coauthored(
      subject: String,
      fromId: String,
      toId: String,
      sourceEvidence: Option[String],
      properties: Map[String, String]
  )

  /** The graph facts the trace needs: COAUTHORED_WITH edges, AUTHORED pairs, Work vertices. */
  final case class GraphFacts(
      coauthored: Vector[Coauthored],
      authored: Set[(String, String)],
      works: Set[String]
  )

  private def text(v: GraphValue): String = GraphParity.text(v)

  def fromCsv(g: NeptuneGraph): GraphFacts =
    GraphFacts(
      g.edges.filter(_.label == EdgeLabel.CoauthoredWith).map { e =>
        Coauthored(
          s"${e.path}#${e.record}",
          e.fromId,
          e.toId,
          e.properties.get("sourceEvidence").map(text),
          e.properties.map { case (k, v) => k -> text(v) }
        )
      },
      g.edges.filter(_.label == EdgeLabel.Authored).map(e => (e.fromId, e.toId)).toSet,
      g.vertices.filter(_.label == NodeLabel.Work).map(_.id).toSet
    )

  def fromRecords(nodes: Vector[GraphNodeV2], edges: Vector[GraphEdgeV2]): GraphFacts =
    GraphFacts(
      edges.filter(_.edgeType == EdgeLabel.CoauthoredWith).map { e =>
        Coauthored(
          s"graph-edges[${e.edgeId}]",
          e.fromId,
          e.toId,
          Some(e.sourceEvidence),
          e.properties
        )
      },
      edges.filter(_.edgeType == EdgeLabel.Authored).map(e => (e.fromId, e.toId)).toSet,
      nodes.filter(_.label == NodeLabel.Work).map(_.nodeId).toSet
    )

  def check(
      facts: GraphFacts,
      coauthorEdges: Vector[CoauthorEdgeV2],
      paths: Vector[EvidencePath]
  ): Vector[(String, String)] = {
    val out = Vector.newBuilder[(String, String)]
    def bad(s: String, why: String): Unit = out += s -> why
    val byId = coauthorEdges.groupBy(_.edgeId)
    byId.foreach { case (id, xs) =>
      if (xs.size > 1) bad(s"edges[$id]", s"${xs.size} coauthor-edge records")
    }
    val cited = mutable.Map.empty[String, Int]
    facts.coauthored.foreach { c =>
      c.sourceEvidence match {
        case None => bad(c.subject, "no sourceEvidence pointer")
        case Some(ev) =>
          cited(ev) = cited.getOrElse(ev, 0) + 1
          byId.get(ev).map(_.head) match {
            case None =>
              bad(
                c.subject,
                s"broken evidence pointer: $ev resolves to no coauthor-edge record of the run"
              )
            case Some(e) =>
              def prop(k: String, exp: String): Unit =
                if (!c.properties.get(k).contains(exp))
                  bad(
                    c.subject,
                    s"$k ${c.properties.getOrElse(k, "(absent)")}, coauthor edge $ev has $exp"
                  )
              if (c.fromId != e.researcherA || c.toId != e.researcherB)
                bad(
                  c.subject,
                  s"pair ${c.fromId} ${c.toId}, coauthor edge $ev joins ${e.researcherA} ${e.researcherB}"
                )
              prop("windowStartYear", e.window.startYear.toString)
              prop("windowEndYear", e.window.endYear.toString)
              prop("jointPublicationCount", e.jointPublicationCount.toString)
              prop("firstCollaborationYear", e.firstCollaborationYear.toString)
              prop("lastCollaborationYear", e.lastCollaborationYear.toString)
              if (e.supportingWorkIds.isEmpty)
                bad(c.subject, s"coauthor edge $ev has no supporting work")
              e.supportingWorkIds.foreach { w =>
                if (!facts.works.contains(w))
                  bad(c.subject, s"supporting work $w is not a Work vertex of the run")
                Vector(c.fromId, c.toId).foreach { r =>
                  if (!facts.authored.contains((r, w)))
                    bad(c.subject, s"supporting work $w has no AUTHORED edge from $r")
                }
              }
          }
      }
    }
    coauthorEdges.foreach { e =>
      cited.getOrElse(e.edgeId, 0) match {
        case 1 => ()
        case 0 => bad(s"edges[${e.edgeId}]", "no COAUTHORED_WITH edge cites this coauthor edge")
        case n => bad(s"edges[${e.edgeId}]", s"cited by $n COAUTHORED_WITH edges")
      }
    }
    paths.foreach { p =>
      p.hops.zipWithIndex.foreach { case (h, i) =>
        val at = s"evidence-paths[${p.pathId}].hops[$i]"
        byId.get(h.edgeId).map(_.head) match {
          case None =>
            bad(at, s"broken evidence pointer: ${h.edgeId} resolves to no coauthor-edge record")
          case Some(e) =>
            val ends = Set(p.nodeIds.lift(i), p.nodeIds.lift(i + 1)).flatten
            if (e.window != p.window) bad(at, s"coauthor edge ${h.edgeId} is of another window")
            if (ends != Set(e.researcherA, e.researcherB))
              bad(at, s"coauthor edge ${h.edgeId} does not join the hop's researchers")
            h.evidenceWorkIds.filterNot(e.supportingWorkIds.contains).foreach { w =>
              bad(at, s"$w is not a supporting work of ${h.edgeId}")
            }
        }
      }
    }
    out.result()
  }
}
