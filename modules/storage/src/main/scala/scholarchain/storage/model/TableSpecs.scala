package scholarchain.storage.model

/**
 * The DynamoDB tables (README L1381-1389; SE-P7-01). Logical names only: physical table names come
 * from configuration in the SDK adapter. `runtimeWritable` marks the three tables the runtime
 * workflow may write (QuerySession, EntityLinkCache, EvidenceBundle) through
 * [[scholarchain.storage.port.RuntimeStores]]; the six README tables other than those are written
 * by loaders only.
 *
 * SE-INT-01: `serviceState` marks the three service-state tables (QueryOwnership, QueryJob,
 * IdempotencyKey). They are written only through [[scholarchain.storage.state.ServiceStateStores]],
 * never through RuntimeStores, so the rag workflow cannot reach them; they are not
 * `runtimeWritable`.
 */
enum TableRole(val tag: String, val runtimeWritable: Boolean, val serviceState: Boolean = false) {
  case RunLedger extends TableRole("RunLedger", false)
  case ArtifactRegistry extends TableRole("ArtifactRegistry", false)
  case EvidenceBundle extends TableRole("EvidenceBundle", true)
  case QuerySession extends TableRole("QuerySession", true)
  case EntityLinkCache extends TableRole("EntityLinkCache", true)
  case ResearcherMetricCache extends TableRole("ResearcherMetricCache", false)
  case QueryOwnership extends TableRole("QueryOwnership", false, true)
  case QueryJob extends TableRole("QueryJob", false, true)
  case IdempotencyKey extends TableRole("IdempotencyKey", false, true)
}

/**
 * A global secondary index; both key attributes are type S. Sparse: items without them are absent.
 */
final case class GlobalIndex(name: String, partitionKey: String, sortKey: String)

/** Whether and why a table expires items. */
enum TtlPolicy {

  /** No TTL; `justification` says why the data is not disposable. */
  case NoTtl(justification: String)

  /**
   * TTL on `attribute` (epoch seconds, type N); `justification` says why the data is disposable.
   */
  case Ttl(attribute: String, justification: String)
}

/** How an access pattern constrains the sort key. */
enum SortUse {
  case Exact
  case BeginsWith
  case Range
  case WholePartition
}

/**
 * One stated access pattern, served by a GetItem or Query on the table key or one GSI, never by a
 * Scan.
 *
 * @param index
 *   None: the table key; Some(name): that GSI
 * @param partitionForm
 *   the partition key value form, for example `run#<runId>`
 * @param sortForm
 *   the sort key value form (or prefix) the pattern uses
 */
final case class AccessPattern(
    id: String,
    description: String,
    index: Option[String],
    partitionForm: String,
    sortUse: SortUse,
    sortForm: String
)

/**
 * One table: string partition key `pk` and string sort key `sk`, its GSIs, TTL policy and the
 * access patterns that justify the keys (README L1387, gate L1429).
 */
final case class TableSpec(
    role: TableRole,
    partitionKey: String,
    sortKey: String,
    indexes: Vector[GlobalIndex],
    ttl: TtlPolicy,
    accessPatterns: Vector[AccessPattern],
    justification: String
) {

  /** (partition, sort) key attribute names of the table key (None) or a GSI. */
  def keyAttributes(index: Option[String]): Option[(String, String)] =
    index match {
      case None => Some((partitionKey, sortKey))
      case Some(n) => indexes.find(_.name == n).map(g => (g.partitionKey, g.sortKey))
    }

  /** Every key attribute name (table key and GSIs). */
  def allKeyAttributes: Set[String] =
    Set(partitionKey, sortKey) ++ indexes.flatMap(g => Vector(g.partitionKey, g.sortKey))

  def ttlAttribute: Option[String] = ttl match {
    case TtlPolicy.Ttl(a, _) => Some(a)
    case TtlPolicy.NoTtl(_) => None
  }
}

/** Standard item attribute names shared by every table (SE-P7-01 item codecs). */
object Attrs {
  val Pk = "pk"
  val Sk = "sk"
  val SchemaVersion = "schemaVersion"
  val Body = "body"
  val RecordSha256 = "recordSha256"
  val Version = "version"
  val ExpiresAt = "expiresAt"
}

object TableSpecs {
  import SortUse.*

  private def ap(
      id: String,
      description: String,
      index: Option[String],
      pk: String,
      use: SortUse,
      sk: String
  ) = AccessPattern(id, description, index, pk, use, sk)

  val RunLedger: TableSpec = TableSpec(
    TableRole.RunLedger,
    Attrs.Pk,
    Attrs.Sk,
    Vector(GlobalIndex("byLoad", "byLoadPk", "byLoadSk")),
    TtlPolicy.NoTtl(
      "the audit trail of every run publication, load and rejected record, and the idempotency " +
        "record of a load (a rerun of a loaded load writes nothing); never disposable"
    ),
    Vector(
      ap(
        "RL-1",
        "the publication of a run (one _parts.json sha256 per run ID)",
        None,
        "run#<runId>",
        Exact,
        "publication"
      ),
      ap("RL-2", "every load of a run", None, "run#<runId>", BeginsWith, "load#"),
      ap("RL-3", "the state of one load of a run", None, "run#<runId>", Exact, "load#<loadId>"),
      ap(
        "RL-4",
        "the rejected records of one load, every write generation (history and current)",
        None,
        "run#<runId>",
        BeginsWith,
        "reject#<loadId>#"
      ),
      ap(
        "RL-7",
        "the rejected records of one write generation of a load (CR-P7-11 m1; SE-P7-11): the " +
          "generation named by the current RL-8 summary gives the current set (generations are " +
          "zero-padded to ten digits, so sort-key order is generation order)",
        None,
        "run#<runId>",
        BeginsWith,
        "reject#<loadId>#<generation:10 digits>#"
      ),
      ap(
        "RL-8",
        "the rejection-set summaries of a load, one per finished write generation (CR-P7-12 F2): " +
          "the last row (highest generation) is the current rejection set; generations without " +
          "a summary are incomplete and never current",
        None,
        "run#<runId>",
        BeginsWith,
        "rejectset#<loadId>#"
      ),
      ap(
        "RL-5",
        "the run and ledger rows of a load ID",
        Some("byLoad"),
        "load#<loadId>",
        WholePartition,
        ""
      ),
      ap(
        "RL-6",
        "the one run that owns a target store instance (CR-P7-10 m1; one run per store)",
        None,
        "store#<targetStore>",
        Exact,
        "owner"
      )
    ),
    "required (README L1381): load state machine, run publication and rejected-record ledger"
  )

  val ArtifactRegistry: TableSpec = TableSpec(
    TableRole.ArtifactRegistry,
    Attrs.Pk,
    Attrs.Sk,
    Vector(GlobalIndex("bySha256", "bySha256Pk", "bySha256Sk")),
    TtlPolicy.NoTtl(
      "every stored object's content hash and source reference (README L1370); evidence pointers " +
        "must keep resolving, so never disposable"
    ),
    Vector(
      ap("AR-1", "one stored object of a run", None, "run#<runId>", Exact, "object#<zone>#<key>"),
      ap("AR-2", "every stored object of a run", None, "run#<runId>", BeginsWith, "object#"),
      ap(
        "AR-3",
        "the stored objects of a run in one zone",
        None,
        "run#<runId>",
        BeginsWith,
        "object#<zone>#"
      ),
      ap(
        "AR-4",
        "every stored object with a content hash",
        Some("bySha256"),
        "sha256#<hex>",
        WholePartition,
        ""
      )
    ),
    "required (README L1385): one row per object written, with sha256 and source run"
  )

  val EvidenceBundle: TableSpec = TableSpec(
    TableRole.EvidenceBundle,
    Attrs.Pk,
    Attrs.Sk,
    Vector(
      GlobalIndex("byQuery", "byQueryPk", "byQuerySk"),
      GlobalIndex("byEvidence", "byEvidencePk", "byEvidenceSk")
    ),
    TtlPolicy.NoTtl(
      "evidence IDs cited by answers must keep resolving (provenance invariant); never disposable"
    ),
    Vector(
      ap("EB-1", "one evidence bundle", None, "bundle#<bundleId>", Exact, "bundle"),
      ap("EB-2", "the evidence items of a bundle", None, "bundle#<bundleId>", BeginsWith, "item#"),
      ap("EB-3", "the bundles of a query", Some("byQuery"), "query#<queryId>", WholePartition, ""),
      ap(
        "EB-4",
        "resolve an evidence ID to its bundles",
        Some("byEvidence"),
        "ev#<evidenceId>",
        WholePartition,
        ""
      )
    ),
    "required (README L1384): evidence bundles behind every answer"
  )

  val QuerySession: TableSpec = TableSpec(
    TableRole.QuerySession,
    Attrs.Pk,
    Attrs.Sk,
    Vector.empty,
    TtlPolicy.Ttl(
      Attrs.ExpiresAt,
      "per-query working state; the durable answer evidence is in EvidenceBundle, so losing a " +
        "session only forces the query to run again"
    ),
    Vector(
      ap("QS-1", "one query session", None, "query#<queryId>", Exact, "session"),
      ap(
        "QS-2",
        "the plan steps of a session in order",
        None,
        "query#<queryId>",
        BeginsWith,
        "step#"
      ),
      ap(
        "QS-3",
        "the audit record of a query (SE-P9-02; README stage 10): plan hash, resolved researcher " +
          "IDs, evidence IDs, versions, token counts, latency, verification and answer hash; " +
          "written once, under the session TTL",
        None,
        "query#<queryId>",
        Exact,
        "audit"
      )
    ),
    "required (README L1383): runtime query sessions"
  )

  val EntityLinkCache: TableSpec = TableSpec(
    TableRole.EntityLinkCache,
    Attrs.Pk,
    Attrs.Sk,
    Vector(GlobalIndex("byResearcher", "byResearcherPk", "byResearcherSk")),
    TtlPolicy.Ttl(
      Attrs.ExpiresAt,
      "a cache of surface-form lookups recomputable from Neptune and the published metrics; stale " +
        "entries must age out"
    ),
    Vector(
      ap(
        "EL-1",
        "the cached researcher candidates of a surface form",
        None,
        "surface#<sha256>",
        WholePartition,
        ""
      ),
      ap("EL-2", "one cached link", None, "surface#<sha256>", Exact, "researcher#<researcherId>"),
      ap(
        "EL-3",
        "the surface forms cached for a researcher",
        Some("byResearcher"),
        "researcher#<researcherId>",
        WholePartition,
        ""
      )
    ),
    "required (README L1382): runtime entity-link cache"
  )

  val ResearcherMetricCache: TableSpec = TableSpec(
    TableRole.ResearcherMetricCache,
    Attrs.Pk,
    Attrs.Sk,
    Vector(
      GlobalIndex("byGroup", "byGroupPk", "byGroupSk"),
      GlobalIndex("byPair", "byPairPk", "byPairSk"),
      GlobalIndex("rank", "rankPk", "rankSk")
    ),
    TtlPolicy.NoTtl(
      "deterministic Spark outputs of a published run, cited as metric evidence; never expired and " +
        "never replaced. Keys carry no run ID: by human decision (2026-11-20, option b) one " +
        "analysis run owns a store instance, enforced by the RunLedger store-owner row (RL-6) " +
        "before any data write; a different row under a key is also refused by the pre-write " +
        "probe. Multi-run coexistence and active-run switching are deferred"
    ),
    Vector(
      ap(
        "MC-1",
        "every metric row of a subject",
        None,
        "subject#<subjectId>",
        BeginsWith,
        "metric#"
      ),
      ap(
        "MC-2",
        "one metric role of a subject in one window",
        None,
        "subject#<subjectId>",
        Exact,
        "metric#<role>#<SSSS-EEEE>"
      ),
      ap(
        "MC-3",
        "the members of a component or community",
        Some("byGroup"),
        "group#<groupId>#<SSSS-EEEE>",
        WholePartition,
        ""
      ),
      ap(
        "MC-4",
        "direct and indirect connectivity of two researchers in a window: their coauthor-edge row " +
          "and evidence-path rows",
        Some("byPair"),
        "pair#<first>#<second>",
        BeginsWith,
        "window#<SSSS-EEEE>#"
      ),
      ap(
        "MC-5",
        "top-k subjects of a ranked role in a window, ascending rank (collaboration breadth: " +
          "researcher-metrics; bridges: bridge-measures; coreness; largest components: " +
          "path-length-estimates)",
        Some("rank"),
        "rank#<role>#<SSSS-EEEE>",
        Range,
        "<rank:10 digits>#"
      ),
      ap(
        "MC-6",
        "the window-level results of a window: analysis window, window structure and the " +
          "comparisons from it to later windows",
        None,
        "window#<SSSS-EEEE>",
        BeginsWith,
        "result#"
      ),
      ap(
        "MC-7",
        "the temporal change of a researcher between two windows",
        None,
        "subject#<researcherId>",
        Exact,
        "metric#temporal-changes#<SSSS-EEEE>#<SSSS-EEEE>"
      ),
      ap(
        "MC-8",
        "resolve an evidence ID (coauthor edge, evidence path, component estimate) to its record",
        None,
        "subject#<id>",
        BeginsWith,
        "metric#<role>#"
      )
    ),
    "optional (README L1386), used as the one analysis-results table of a run (SE-P7-07): " +
      "bounded runtime lookup by researcher and window without a graph traversal (N4), pair " +
      "connectivity, group membership, ranked top-k, window-level and temporal reads, and evidence " +
      "resolution; one table with typed sort keys instead of one table per role"
  )

  /**
   * SE-INT-01: who submitted a query (services QueryOwnership port). Written once per query by the
   * QueryJobs process when the workflow call is admitted; read by the EvidenceExplain process.
   */
  val QueryOwnership: TableSpec = TableSpec(
    TableRole.QueryOwnership,
    Attrs.Pk,
    Attrs.Sk,
    Vector.empty,
    TtlPolicy.Ttl(
      Attrs.ExpiresAt,
      "per-query access-control state of the same lifetime as the query session (configured " +
        "TTL at least the QuerySession TTL); losing an owner row only makes the query's " +
        "explanation and evidence occurrences unavailable (fail closed: 404), so it is disposable"
    ),
    Vector(
      ap("QO-1", "the owner of one query (GetItem)", None, "query#<queryId>", Exact, "owner"),
      ap(
        "QO-2",
        "the owners of a bounded list of queries in one BatchGetItem (ownedBy: evidence " +
          "occurrences of the caller's own queries)",
        None,
        "query#<queryId>",
        Exact,
        "owner"
      )
    ),
    "SE-INT-01 (phase-10 outstanding, N1 and owner eviction): a durable, shared QueryOwnership " +
      "for separate QueryJobs and EvidenceExplain processes; keyed by query ID because every read " +
      "is by query ID (one item or a batch of items), never by client"
  )

  /** SE-INT-01: one asynchronous query job (services JobStore), shared by QueryJobs replicas. */
  val QueryJob: TableSpec = TableSpec(
    TableRole.QueryJob,
    Attrs.Pk,
    Attrs.Sk,
    Vector.empty,
    TtlPolicy.Ttl(
      Attrs.ExpiresAt,
      "job state and its opaque result are a delivery buffer of an answer whose durable evidence " +
        "is in EvidenceBundle and QuerySession; after the configured TTL a client re-submits, so " +
        "the rows are disposable"
    ),
    Vector(
      ap(
        "QJ-1",
        "one job by its ID (GetItem; the owning client is checked on the item, never by a query)",
        None,
        "job#<jobId>",
        Exact,
        "job"
      )
    ),
    "SE-INT-01 (phase-10 outstanding): durable jobs, required before more than one QueryJobs " +
      "replica; keyed by job ID because jobs are only ever read and updated by ID (per-state " +
      "counts are per-replica metrics, not a table read)"
  )

  /**
   * SE-INT-01: one Idempotency-Key of one client (services IdempotencyStore), keyed by the SHA-256
   * of the length-prefixed (client ID, key) pair; the raw key is never stored.
   */
  val IdempotencyKey: TableSpec = TableSpec(
    TableRole.IdempotencyKey,
    Attrs.Pk,
    Attrs.Sk,
    Vector.empty,
    TtlPolicy.Ttl(
      Attrs.ExpiresAt,
      "replay records are disposable by definition (a completed record lives for the configured " +
        "idempotency TTL); an in-flight record carries a crash bound (in-flight TTL) so a crashed " +
        "replica cannot pin a key forever"
    ),
    Vector(
      ap(
        "IK-1",
        "the record of one (client, Idempotency-Key) pair by its digest (GetItem, conditional " +
          "put and conditional delete)",
        None,
        "idem#<sha256>",
        Exact,
        "request"
      )
    ),
    "SE-INT-01 (phase-10 outstanding): durable idempotency shared by QueryJobs replicas; keyed " +
      "by the digest because the only access is by (client, key)"
  )

  /** The six README tables, in README order, then the three SE-INT-01 service-state tables. */
  val all: Vector[TableSpec] =
    Vector(
      RunLedger,
      EntityLinkCache,
      QuerySession,
      EvidenceBundle,
      ArtifactRegistry,
      ResearcherMetricCache,
      QueryOwnership,
      QueryJob,
      IdempotencyKey
    )

  def of(role: TableRole): TableSpec = all.find(_.role == role).get
}
