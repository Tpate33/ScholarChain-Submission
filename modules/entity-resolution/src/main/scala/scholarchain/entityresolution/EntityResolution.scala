package scholarchain.entityresolution

import scala.collection.immutable.SortedMap

import scholarchain.core.{AuthorMention, ExactId, SourceAuthorProfile, WorkRecord}

/**
 * The Phase 4 entity-resolution functions under the names the Hadoop pipeline asked for (owner:
 * entity-resolution-engineer). Everything here is pure and deterministic; it measures and groups,
 * and never decides a match. Hadoop wraps these through its `BlockingKeySource` interface and calls
 * the feature functions in its candidate join (job 5).
 */
object EntityResolution {

  /** Version of the blocking-key formulas (a token; `CandidateBlock.blockingVersion`). */
  val blockingKeysVersion: String = BlockingKeys.Version

  /** Version of the feature functions (a token; `MatchCandidate.featureVersion`). */
  val featuresVersion: String = MatchFeatures.Version

  /** Maximum number of key levels returned by any key function. */
  val blockingLevels: Int = BlockingKeys.Levels

  /** Keys of one author mention with its work, level 0 first. Empty when no name is readable. */
  def researcherBlockingKeys(work: WorkRecord, mention: AuthorMention): Vector[String] =
    BlockingKeys.researcherKeys(work, mention)

  /** Keys of one source author profile, level 0 first. Empty when no name is readable. */
  def profileBlockingKeys(profile: SourceAuthorProfile): Vector[String] =
    BlockingKeys.profileKeys(profile)

  /** Keys of one work, level 0 first. Empty when no title word is readable. */
  def workBlockingKeys(work: WorkRecord): Vector[String] = BlockingKeys.workKeys(work)

  /** Researcher-pair features: mention or profile on each side (symmetric). */
  def matchFeatures(a: ResearcherEndpoint, b: ResearcherEndpoint): SortedMap[String, Double] =
    MatchFeatures.researcher(a, b)

  /** Researcher-pair features of two mentions with their works (symmetric). */
  def matchFeatures(
      a: AuthorMention,
      aWork: WorkRecord,
      b: AuthorMention,
      bWork: WorkRecord
  ): SortedMap[String, Double] =
    MatchFeatures.researcher(aWork, a, bWork, b)

  /** Work-pair features (symmetric). */
  def workMatchFeatures(a: WorkRecord, b: WorkRecord): SortedMap[String, Double] =
    MatchFeatures.work(a, b)

  /** Researcher identifiers asserted by both endpoints (`MatchCandidate.exactIdAgreements`). */
  def researcherExactIdAgreements(a: ResearcherEndpoint, b: ResearcherEndpoint): Vector[ExactId] =
    MatchFeatures.exactIdAgreements(
      MatchFeatures.researcherExactIds(a),
      MatchFeatures.researcherExactIds(b)
    )

  /** Work identifiers asserted by both works (`MatchCandidate.exactIdAgreements`). */
  def workExactIdAgreements(a: WorkRecord, b: WorkRecord): Vector[ExactId] =
    MatchFeatures.exactIdAgreements(MatchFeatures.workExactIds(a), MatchFeatures.workExactIds(b))

  /** Folded name text (`given parts family`), for display or debugging; keys do not need it. */
  def normalizeName(raw: String): String = PersonName.parse(raw).fullKey

  /** Folded title text, for display or debugging; keys do not need it. */
  def normalizeTitle(raw: String): String = TitleText.normalize(raw)
}
