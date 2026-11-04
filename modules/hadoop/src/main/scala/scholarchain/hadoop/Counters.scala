package scholarchain.hadoop

import org.apache.hadoop.mapreduce.TaskAttemptContext

/**
 * Hadoop counter names. Every counter lives in group [[Group]]. The ten required base counters
 * (role law 5) are always reported; detail counters add a `.<qualifier>` suffix (for example
 * `INPUT.dblp`, `REJECTED.missing-key`).
 */
object Counters {
  val Group = "scholarchain"

  // ---- required base counters ----
  val Input = "INPUT"
  val Accepted = "ACCEPTED"
  val Rejected = "REJECTED"
  val Duplicate = "DUPLICATE"
  val Conflict = "CONFLICT"
  val OversizeBlock = "OVERSIZE_BLOCK"
  val OversizeTeam = "OVERSIZE_TEAM"
  val EmittedPair = "EMITTED_PAIR"
  val MissingKey = "MISSING_KEY"
  val SchemaFailure = "SCHEMA_FAILURE"

  /** The required counters, in a fixed order. */
  val Required: Vector[String] = Vector(
    Input,
    Accepted,
    Rejected,
    Duplicate,
    Conflict,
    OversizeBlock,
    OversizeTeam,
    EmittedPair,
    MissingKey,
    SchemaFailure
  )

  // ---- additional counters ----
  /** Kept records that also produced a flag in the reject output. */
  val Flagged = "FLAGGED"

  /** Publication records outside the configured selection (record type, year, venue). */
  val Filtered = "FILTERED"

  /** DBLP `www`, `person`, `data` records: never works. */
  val SkippedNonPublication = "SKIPPED_NON_PUBLICATION"

  /** Identical copies of an embedded reference record (venue, topic, institution) merged. */
  val MergedReference = "MERGED_REFERENCE"

  /** Records written to a curated output, per output name. */
  val Emitted = "EMITTED"

  /** Works that produce no team (no authors), or whose source is not a team source. */
  val TeamNoAuthors = "TEAM_NO_AUTHORS"
  val TeamSourceExcluded = "TEAM_SOURCE_EXCLUDED"

  /** Exact-identifier index. */
  val ExactIdKey = "EXACT_ID_KEY"
  val ExactIdCrossSource = "EXACT_ID_CROSS_SOURCE"

  /** Blocking. */
  val BlockEmitted = "BLOCK_EMITTED"
  val BlockMembersRefined = "BLOCK_MEMBERS_REFINED"
  val BlockMembersDropped = "BLOCK_MEMBERS_DROPPED"

  /** Members re-read from the previous round's spill (rounds >= 1; never part of INPUT). */
  val RefineInput = "REFINE_INPUT"

  /** Records (works, profiles) read by a stage whose INPUT unit is something else. */
  val RecordsRead = "RECORDS_READ"

  /** Candidate features. */
  val CandidatePairs = "CANDIDATE_PAIRS"
  val CandidateMerged = "CANDIDATE_MERGED"
  val CandidateMultiBlock = "CANDIDATE_MULTI_BLOCK"

  def detail(base: String, qualifier: String): String = s"$base.$qualifier"

  def inc(ctx: TaskAttemptContext, name: String, n: Long = 1L): Unit =
    if (n != 0L) ctx.getCounter(Group, name).increment(n)

  /** Increments `base` and `base.qualifier`. */
  def inc2(ctx: TaskAttemptContext, base: String, qualifier: String, n: Long = 1L): Unit = {
    inc(ctx, base, n)
    inc(ctx, detail(base, qualifier), n)
  }
}
