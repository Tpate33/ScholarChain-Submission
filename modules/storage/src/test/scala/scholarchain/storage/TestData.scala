package scholarchain.storage

import scala.collection.immutable.SortedMap

import scholarchain.core.{
  Canonical,
  EvidenceBundle,
  EvidenceId,
  EvidenceItem,
  EvidenceType,
  Provenance,
  ResearcherId,
  SchemaVersions,
  SourceType
}
import scholarchain.storage.model.*

/** Synthetic, valid IDs and records for storage tests (no fixture files are read). */
object TestData {
  def hex(seed: String): String = Canonical.sha256HexUtf8(seed)

  val runId: String = "run:" + hex("run-1")
  val otherRunId: String = "run:" + hex("run-2")
  val loadId: String = "load:" + hex("load-1")
  val otherLoadId: String = "load:" + hex("load-2")
  val chunkId: String = "chunk:" + hex("chunk-1")
  val communityId: String = "community:" + hex("community-1")
  val componentId: String = "component:" + hex("component-1")
  val inputId: String = "suminput:" + hex("input-1")
  val queryId: String = "query:q-0001"

  val researcherA: String = ResearcherId.scholarChain("v1", "dblp-pid", "00/0001").toOption.get
  val researcherB: String = ResearcherId.scholarChain("v1", "dblp-pid", "00/0002").toOption.get
  val orcidResearcher: String = "researcher:orcid:0000-0002-1825-0097"
  val (pairFirst, pairSecond) =
    if (Canonical.compareUtf8(researcherA, researcherB) < 0) (researcherA, researcherB)
    else (researcherB, researcherA)

  val workId = "work:dblp:journals/cacm/PopperMP21"
  val topicId = "topic:openalex:T10001"
  val institutionId = "institution:ror:05dxps055"

  val layoutSettings: S3LayoutSettings = S3LayoutSettings(
    Map(
      Zone.Raw -> ZoneLocation("test-raw-bucket", "raw"),
      Zone.Curated -> ZoneLocation("test-data-bucket", "curated"),
      Zone.Graph -> ZoneLocation("test-data-bucket", "graph"),
      Zone.SemanticContext -> ZoneLocation("test-data-bucket", "semantic-context"),
      Zone.Reports -> ZoneLocation("test-data-bucket", "reports"),
      Zone.Logs -> ZoneLocation("test-data-bucket", "logs")
    ),
    maxKeyBytes = 1024
  )
  lazy val layout: S3Layout = S3Layout.from(layoutSettings).toOption.get

  val limits: VectorLimits = VectorLimits(
    maxBatchSize = 100,
    maxValueBytes = 256,
    maxFilterableBytes = 2048,
    maxTotalBytes = 40960,
    maxKeys = 50,
    maxNonFilterableKeys = 10,
    maxListValues = 50,
    maxTopK = 30
  )

  def provenance(run: String = runId): Provenance =
    Provenance(
      SourceType.Derived,
      workId,
      "snap-1",
      run,
      "scholarchain.graph-edge.v2",
      Some("graph.v1"),
      None,
      None
    )

  def evidenceItem(uri: String): EvidenceItem = {
    val id = EvidenceId
      .of(EvidenceType.SourceRecord, uri, Some(workId), None, Vector.empty, runId)
      .toOption
      .get
    EvidenceItem(
      SchemaVersions.EvidenceItem,
      id,
      EvidenceType.SourceRecord,
      Some(workId),
      uri,
      None,
      None,
      1.0,
      None,
      provenance()
    )
  }

  def bundle(uris: String*): EvidenceBundle =
    EvidenceBundle.build(queryId, uris.toVector.map(evidenceItem), provenance()).toOption.get

  def publication(sha: String = hex("parts")): RunPublication =
    RunPublication(RunPublication.SchemaVersion, runId, sha, loadId)

  def artifact(key: String, sha: String = hex("bytes")): ArtifactRecord =
    ArtifactRecord(
      ArtifactRecord.SchemaVersion,
      runId,
      Some(loadId),
      "reports",
      "test-data-bucket",
      key,
      sha,
      42L,
      "application/json",
      Some("metrics/part-00000.parquet"),
      Some("scholarchain.researcher-metric.v1")
    )

  def rejected(recordKey: String = "row:metrics/part-00000.parquet#7"): RejectedRecord =
    RejectedRecord(
      RejectedRecord.SchemaVersion,
      runId,
      loadId,
      scholarchain.core.TargetStore.S3Vectors,
      "researcher-profiles",
      "summary-inputs/part-00000.parquet",
      recordKey,
      RejectReason.OversizeMetadata,
      "metadata value textS3Key exceeds the per-value cap",
      0,
      3L
    )

  def session(expires: Long = 2000000000L): QuerySessionRecord =
    QuerySessionRecord(
      QuerySessionRecord.SchemaVersion,
      queryId,
      2015,
      2020,
      "planned",
      Some(hex("plan")),
      None,
      1L,
      expires
    )

  def step(ordinal: Int): QueryStepRecord =
    QueryStepRecord(
      QueryStepRecord.SchemaVersion,
      queryId,
      ordinal,
      "get-metrics",
      "done",
      Vector(evidenceItem("s3://b/k").evidenceId),
      2000000000L
    )

  def link(researcher: String = researcherA): EntityLinkRecord =
    EntityLinkRecord(
      EntityLinkRecord.SchemaVersion,
      hex("ada lovelace"),
      researcher,
      "exact-name",
      1,
      runId,
      2000000000L
    )

  /**
   * A synthetic analysis-results row (SE-P7-08: the pre-SE-P7-07 ResearcherMetricRecord is removed;
   * AnalysisResultRecord is the only record of the table). The body is synthetic JSON, not a core
   * record: these tests exercise keys and indexes only.
   */
  def metric(
      subject: String = researcherA,
      role: String = "researcher-metrics",
      group: Option[String] = None,
      pair: Option[Vector[String]] = None,
      rank: Option[Int] = None
  ): AnalysisResultRecord = {
    val body = s"""{"synthetic":"$subject"}"""
    AnalysisResultRecord(
      AnalysisResultRecord.SchemaVersion,
      role,
      AnalysisResultRecord.SubjectScope,
      subject,
      "2015-2020",
      None,
      body,
      Canonical.sha256HexUtf8(body),
      SchemaVersions.ResearcherMetricsV2,
      group,
      pair,
      rank,
      runId,
      loadId,
      "metrics/part-00000.parquet"
    )
  }

  /** Valid metadata of a researcher-profile vector. */
  def profileMetadata(textSha: String = hex("profile text")): VectorMetadata =
    VectorMetadata(
      SortedMap(
        "documentType" -> MetaValue.Str("researcher-profile"),
        "subjectId" -> MetaValue.Str(researcherA),
        "runId" -> MetaValue.Str(runId),
        "indexVersion" -> MetaValue.Str("researcher-profile-v1"),
        "sourceDataset" -> MetaValue.Str("scholarchain-derived"),
        "inputSources" -> MetaValue.StrList(Vector("dblp", "openalex")),
        "researcherIds" -> MetaValue.StrList(Vector(researcherA)),
        "windowStart" -> MetaValue.Num(2015),
        "windowEnd" -> MetaValue.Num(2020),
        "communityId" -> MetaValue.Str(communityId),
        "topicIds" -> MetaValue.StrList(Vector(topicId)),
        "institutionIds" -> MetaValue.StrList(Vector(institutionId)),
        "identityStatus" -> MetaValue.Str("resolved"),
        "anchorCollision" -> MetaValue.Str("false"),
        "textS3Key" -> MetaValue.Str("semantic-context/loads/" + hex("load-1") + "/text/a.txt"),
        "textSha256" -> MetaValue.Str(textSha),
        "modelVersion" -> MetaValue.Str("fake-embedder-v1"),
        // SE-P7-11: a well-formed digest (these metadata tests do not tie it to data).
        "dataSha256" -> MetaValue.Str(hex("vector data")),
        "loadId" -> MetaValue.Str(loadId),
        "inputId" -> MetaValue.Str(inputId),
        "schemaVersion" -> MetaValue.Str("scholarchain.storage.vector-metadata.v2"),
        "sourcePath" -> MetaValue.Str("summary-inputs/part-00000.parquet")
      )
    )

  def withMeta(md: VectorMetadata, key: String, v: MetaValue): VectorMetadata =
    VectorMetadata(md.values.updated(key, v))

  def withoutMeta(md: VectorMetadata, key: String): VectorMetadata =
    VectorMetadata(md.values - key)
}
