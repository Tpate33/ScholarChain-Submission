package scholarchain.entityresolution.review

import upickle.default.{write, ReadWriter}
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{Canonical, ExternalId, SourceAuthorProfile, WorkRecord}

/**
 * The work an endpoint belongs to (a mention's work, or the work endpoint itself), as recorded by
 * its source. Untrusted source text, shown as data.
 *
 * @param authors
 *   every author name as written, in source order (the coauthor list), at most
 *   [[BlindView.MaxAuthors]]
 * @param identifiers
 *   the work's identifiers as recorded: `<scheme>:<value>`
 * @param eeLinks
 *   electronic-edition links as recorded (DBLP `ee`), at most [[BlindView.MaxLinks]]
 * @param urls
 *   other source URLs as recorded (DBLP `url`), at most [[BlindView.MaxLinks]]
 */
@allowUnknownKeys(false)
final case class BlindWork(
    workId: String,
    title: String,
    year: Option[Int],
    venue: Option[String],
    authorCount: Int,
    authors: Vector[String],
    identifiers: Vector[String],
    eeLinks: Vector[String],
    urls: Vector[String]
) derives ReadWriter

/**
 * One endpoint as its source record states it: no system output of any kind.
 *
 * @param endpointKind
 *   `mention` (an author position on a work), `profile` (a source author profile), or `work`
 * @param nameAsWritten
 *   a mention's name as written, or a profile's display name
 * @param structuredName
 *   a profile's `given / family` name, when the source records one
 * @param authorPosition
 *   a mention's 1-based author position
 * @param affiliations
 *   raw affiliation strings as written
 * @param institutionIds
 *   institution IDs asserted by the source
 * @param identifiers
 *   the endpoint's own identifiers as recorded: `<scheme>:<value>`
 * @param claimedWorkIds
 *   an ORCID profile's claimed work IDs
 */
@allowUnknownKeys(false)
final case class BlindEndpoint(
    endpointId: String,
    endpointKind: String,
    sourceDataset: String,
    sourceRecordId: String,
    snapshot: String,
    nameAsWritten: Option[String],
    structuredName: Option[String],
    nameVariants: Vector[String],
    authorPosition: Option[Int],
    affiliations: Vector[String],
    institutionIds: Vector[String],
    identifiers: Vector[String],
    claimedWorkIds: Vector[String],
    work: Option[BlindWork]
) derives ReadWriter

/**
 * One row of the blind labeling view (schema [[BlindView.RowSchemaVersion]]). It shows only source
 * evidence and the IDs a label line must repeat. It never carries the score, decision, confidence,
 * match path, rule IDs, feature values, reason codes, conflicts, plausibility, stratum, band, LLM
 * eligibility, model and prompt versions, or matching version of the internal queue row, nor any
 * selection-unit field of the membership manifest (unit IDs, criterion, split, selection rule).
 * `memberId` depends only on the entity kind and the endpoint IDs; labels are joined by it. Every
 * displayed string is masked by [[DblpMasking]] (no DBLP homonym number, no DBLP person ID).
 *
 * @param labelingTarget
 *   [[LabelGuide.target]] of the entity kind: `publication-stage` (work) or `person` (researcher)
 * @param labelGuideVersion
 *   [[LabelGuide.Version]]: the guide that defines the question and its edge cases
 * @param question
 *   the labeling question for the entity kind, verbatim from the label guide
 * @param note
 *   [[BlindView.UnsureNote]]
 * @param maskingNotes
 *   the constant notes of [[DblpMasking]] that apply to this pair (homonym number removed, person
 *   identifier removed), in that order; empty when nothing was masked
 * @param sourceIdA
 *   the label reference of endpoint A ([[BlindView.labelRef]]): its endpoint ID, or a
 *   `masked-endpoint:` hash when the endpoint ID names a DBLP person
 * @param labelOptions
 *   `match`, `non-match`, `unsure`
 */
@allowUnknownKeys(false)
final case class BlindRow(
    schemaVersion: String,
    memberId: String,
    entityKind: String,
    labelingTarget: String,
    labelGuideVersion: String,
    question: String,
    note: String,
    maskingNotes: Vector[String],
    sourceIdA: String,
    sourceIdB: String,
    endpointA: BlindEndpoint,
    endpointB: BlindEndpoint,
    labelOptions: Vector[String]
) derives ReadWriter

/**
 * What the blind view needs of a pair: its member ID, kind, and endpoints. Built from a membership
 * manifest line or an internal queue row; deliberately nothing else is carried.
 */
final case class BlindItem(
    memberId: String,
    entityKind: String,
    sourceIdA: String,
    sourceIdB: String
)

object BlindItem {
  def of(m: GoldMember): BlindItem =
    BlindItem(m.memberId, m.entityKind, m.endpointA.endpointId, m.endpointB.endpointId)

  def of(r: ReviewQueueRow): BlindItem =
    BlindItem(r.memberId, r.entityKind, r.sourceIdA, r.sourceIdB)
}

/**
 * The blind labeling view (human decision 4, Phase 5): pairs reduced to source evidence, in a
 * deterministic shuffle that is independent of the stratum, the decision, the selection unit and
 * the criterion (rows are ordered by SHA-256 of a view tag, the seed, and the member ID).
 *
 * The view of a held-out membership is built from the manifest alone, so it is the same under every
 * matching version. Selection-unit fields, and the DS-P5-01 `selection.tsv` facts behind them
 * (criterion names, `expect_*` values, unit IDs that encode a dblp person ID), are never copied
 * into it.
 *
 * View v3 (ER-P5-06, human instruction 2026-11-05): DBLP's identity assignment is masked in the
 * display ([[DblpMasking]]). Homonym numbers are removed from every person name (authors and
 * coauthors), and every DBLP person ID, person-page link, and pid value is replaced by one constant
 * marker; an endpoint ID that names a DBLP person (a `source-author:dblp-pid:` profile) is shown as
 * a `masked-endpoint:` label reference ([[labelRef]]). Titles, years, venues, DOIs and other work
 * identifiers, links, affiliations, institution IDs, ORCID iDs, and coauthor names (masked) stay
 * visible. The source records, the membership manifest (which keeps every source record ID), and
 * provenance are never changed. When the remaining evidence is insufficient, the labeler chooses
 * `unsure` ([[UnsureNote]]); DBLP's assignment is never the gold label.
 *
 * View v4 (ER-P5-07, human decision 1 of 2026-11-06, Option A): every row names its
 * `labelingTarget` and the label guide version ([[LabelGuide]]). The work question asks for the
 * same publication entity at the same publication stage, revisions within it allowed; a preprint
 * against the published article is a non-match (the guide's edge cases). The researcher question is
 * unchanged. There is one question per entity kind (no lifecycle question). The v3 masking is
 * unchanged.
 *
 * View v5 (ER-P5-08, human instruction of 2026-11-06, decisions-06; RV-P5-04 m9; TE-P5-01 D4 and
 * D5): OpenAlex author (A) and work (W) cluster IDs, and every link or identifier that names one,
 * are masked with one constant marker ([[OpenAlexMasking]], rules er-openalex-display-mask.v1); an
 * endpoint ID or source record ID that is an OpenAlex A or W ID is shown as a `masked-endpoint:`
 * reference or the marker. DBLP masking is er-dblp-display-mask.v2: homonym numbers after any
 * Unicode separator or format character, and DBLP person links inside percent-encoded URLs, are
 * masked too. DOIs, ORCID iDs, and institution IDs stay visible. Fields are unchanged from v4.
 */
object BlindView {
  val RowSchemaVersion: String = "er-blind-label-view.v5"
  val OrderTag: String = "er-blind-view-order.v2"
  val LabelRefTag: String = "er-blind-label-ref.v1"
  val MaskedRefPrefix: String = "masked-endpoint:"
  val DefaultSeed: String = "er-p5"
  val LabelOptions: Vector[String] = Vector("match", "non-match", "unsure")
  val UnsureNote: String = "If the evidence shown is insufficient, choose unsure."
  val MaxAuthors: Int = 50
  val MaxLinks: Int = 20

  /** The display masking rules this view applies, in order. */
  val MaskingVersions: Vector[String] = Vector(DblpMasking.Version, OpenAlexMasking.Version)

  /** Every JSON key a v5 view line may carry (rows, endpoints, works). */
  val VisibleFields: Vector[String] = Vector(
    "schemaVersion",
    "memberId",
    "entityKind",
    "labelingTarget",
    "labelGuideVersion",
    "question",
    "note",
    "maskingNotes",
    "sourceIdA",
    "sourceIdB",
    "endpointA",
    "endpointB",
    "labelOptions",
    "endpointId",
    "endpointKind",
    "sourceDataset",
    "sourceRecordId",
    "snapshot",
    "nameAsWritten",
    "structuredName",
    "nameVariants",
    "authorPosition",
    "affiliations",
    "institutionIds",
    "identifiers",
    "claimedWorkIds",
    "work",
    "workId",
    "title",
    "year",
    "venue",
    "authorCount",
    "authors",
    "eeLinks",
    "urls"
  )

  /** Content that is masked in every displayed string (never a JSON key of the view). */
  val MaskedContent: Vector[String] = Vector(
    "DBLP homonym number (four digits after a person name)",
    "DBLP person ID (dblp-pid identifiers and values)",
    "DBLP person-page and person-search links (pid, pers, homepages), also percent-encoded",
    "endpoint ID and source record ID of a DBLP person profile",
    "OpenAlex author (A) and work (W) cluster IDs and every link or identifier naming one",
    "endpoint ID and source record ID that are an OpenAlex A or W ID"
  )

  /**
   * Field names of the internal row and of the membership manifest that must never appear in the
   * blind view, as keys or as values derived from them.
   */
  val HiddenFields: Vector[String] = Vector(
    "rowId",
    "candidateId",
    "policyVersion",
    "criterion",
    "unitId",
    "unitIds",
    "split",
    "selectionRule",
    "seed",
    "capacity",
    "populationSize",
    "generatingRunId",
    "score",
    "decision",
    "outcome",
    "confidence",
    "path",
    "matchPath",
    "rulesFired",
    "features",
    "reasonCodes",
    "conflicts",
    "plausible",
    "stratum",
    "band",
    "llmBandEligible",
    "modelVersion",
    "promptVersion",
    "system",
    "exactIdAgreements",
    "thresholds",
    "scoreGate"
  )

  /** The question of an entity kind, verbatim from the label guide ([[LabelGuide.question]]). */
  def question(entityKind: String): String = LabelGuide.question(entityKind)

  /** The labeling target of an entity kind; a work target for anything that is not a researcher. */
  def labelingTarget(entityKind: String): String =
    LabelGuide.target(entityKind).getOrElse(LabelGuide.WorkTarget)

  private def external(e: ExternalId): String = s"${e.scheme.tag}:${e.value}"

  private def blindWork(w: WorkRecord): BlindWork =
    BlindWork(
      w.workId,
      w.title,
      w.year,
      w.venueName,
      w.authors.size,
      w.authors.take(MaxAuthors).map(_.rawName),
      (w.doi.map(d => s"doi:$d").toVector ++ w.arxivId.map(a => s"arxiv:$a").toVector ++
        w.additionalIds.map(e => s"${e.scheme.tag}:${e.value}")).distinct,
      w.eeLinks.take(MaxLinks),
      w.urls.take(MaxLinks)
    )

  private def fromWork(w: WorkRecord): Vector[BlindEndpoint] = {
    val bw = blindWork(w)
    val work = BlindEndpoint(
      w.workId,
      "work",
      w.source.tag,
      w.sourceRecordId,
      w.provenance.snapshot,
      None,
      None,
      Vector.empty,
      None,
      Vector.empty,
      Vector.empty,
      bw.identifiers,
      Vector.empty,
      Some(bw)
    )
    work +: w.authors.map { m =>
      BlindEndpoint(
        m.mentionId,
        "mention",
        w.source.tag,
        w.sourceRecordId,
        m.provenance.snapshot,
        Some(m.rawName),
        None,
        Vector.empty,
        Some(m.position + 1),
        m.rawAffiliations,
        m.affiliationIds,
        m.orcid.map(o => s"orcid:$o").toVector ++ m.sourceAuthorId.map(external).toVector,
        Vector.empty,
        Some(bw)
      )
    }
  }

  private def fromProfile(p: SourceAuthorProfile): BlindEndpoint =
    BlindEndpoint(
      p.profileId,
      "profile",
      p.source.tag,
      p.sourceRecordId,
      p.provenance.snapshot,
      p.displayName,
      (p.givenNames.toVector ++ p.familyName.toVector).headOption.map(_ =>
        s"${p.givenNames.getOrElse("")} / ${p.familyName.getOrElse("")}"
      ),
      p.nameVariants,
      None,
      Vector.empty,
      p.institutionIds,
      (Vector(s"${p.source.tag}:${p.sourceRecordId}") ++ p.orcid.map(o => s"orcid:$o").toVector ++
        p.externalIds.map(external)).distinct,
      p.claimedWorkIds,
      None
    )

  /**
   * Source evidence of every endpoint of the given records, by endpoint ID, UNMASKED. This index is
   * internal: only [[row]] (which masks) turns it into displayed text.
   */
  def index(
      works: Vector[WorkRecord],
      profiles: Vector[SourceAuthorProfile]
  ): Map[String, BlindEndpoint] =
    (works.flatMap(fromWork) ++ profiles.map(fromProfile)).map(e => e.endpointId -> e).toMap

  def orderKey(seed: String, memberId: String): String =
    Canonical.hashFields(OrderTag, seed, memberId)

  /**
   * The label reference shown for an endpoint: the endpoint ID itself, or, when the endpoint ID
   * names a DBLP person ([[DblpMasking.revealsPerson]], for example `source-author:dblp-pid:..`),
   * `masked-endpoint:` + SHA-256([[LabelRefTag]], memberId, endpointId). The reference is
   * recomputable from the membership manifest, so a label line may repeat it instead of the
   * endpoint ID. The member ID salts it, so one DBLP profile has a different reference in every
   * pair.
   */
  def labelRef(memberId: String, endpointId: String): String =
    if (!masksEndpointId(endpointId)) endpointId
    else MaskedRefPrefix + Canonical.hashFields(LabelRefTag, memberId, endpointId)

  /**
   * True when an endpoint ID is shown only as a label reference: it names a DBLP person, or (view
   * v5) it is or carries an OpenAlex A or W ID (`work:openalex:W..`, `source-author:openalex:A..`).
   */
  def masksEndpointId(endpointId: String): Boolean =
    DblpMasking.revealsPerson(endpointId) || OpenAlexMasking.reveals(endpointId)

  /** Applies `name` to every person-name field and `text` to every other displayed string. */
  private def mapStrings(
      e: BlindEndpoint,
      name: String => String,
      text: String => String
  ): BlindEndpoint =
    e.copy(
      sourceRecordId = text(e.sourceRecordId),
      nameAsWritten = e.nameAsWritten.map(name),
      structuredName = e.structuredName.map(name),
      nameVariants = e.nameVariants.map(name).distinct,
      affiliations = e.affiliations.map(text),
      institutionIds = e.institutionIds.map(text).distinct,
      identifiers = e.identifiers.map(text).distinct,
      claimedWorkIds = e.claimedWorkIds.map(text).distinct,
      work = e.work.map(w =>
        w.copy(
          workId = text(w.workId),
          title = text(w.title),
          venue = w.venue.map(text),
          authors = w.authors.map(name),
          identifiers = w.identifiers.map(text).distinct,
          eeLinks = w.eeLinks.map(text).distinct,
          urls = w.urls.map(text).distinct
        )
      )
    )

  private def strings(e: BlindEndpoint): Vector[String] = {
    val b = Vector.newBuilder[String]
    b += e.endpointId
    mapStrings(e, s => { b += s; s }, s => { b += s; s })
    b.result()
  }

  /** The masked display of one endpoint, and the masking notes that apply to it. */
  private def masked(
      memberId: String,
      e: BlindEndpoint,
      pids: Set[String],
      clusterIds: Set[String]
  ): (BlindEndpoint, Set[String]) = {
    val notes = scala.collection.mutable.Set.empty[String]
    def text(s: String): String = {
      val d = DblpMasking.text(s, pids)
      if (d != s) notes += DblpMasking.PersonIdNote
      val o = OpenAlexMasking.text(d, clusterIds)
      if (o != d) notes += OpenAlexMasking.Note
      o
    }
    def name(s: String): String = {
      val stripped = DblpMasking.stripHomonym(s)
      if (stripped != s) notes += DblpMasking.HomonymNote
      text(stripped)
    }
    val ref = labelRef(memberId, e.endpointId)
    val shown = mapStrings(e, name, text)
    val personRecord =
      DblpMasking.revealsPerson(e.endpointId) || pids.contains(e.sourceRecordId)
    val clusterRecord = !personRecord &&
      (OpenAlexMasking.reveals(e.endpointId) ||
        clusterIds.contains(e.sourceRecordId.toUpperCase(java.util.Locale.ROOT)))
    if (personRecord) notes += DblpMasking.PersonIdNote
    if (clusterRecord) notes += OpenAlexMasking.Note
    (
      shown.copy(
        endpointId = ref,
        sourceRecordId =
          if (personRecord) DblpMasking.Masked
          else if (clusterRecord) OpenAlexMasking.Masked
          else shown.sourceRecordId
      ),
      notes.toSet
    )
  }

  /**
   * The OpenAlex A and W values of a pair (rule 2 of [[OpenAlexMasking]]): those named in any
   * displayed string, plus the source record ID of an OpenAlex endpoint that is an A or W ID.
   */
  private def clusterIdsOf(endpoints: Vector[BlindEndpoint]): Set[String] =
    OpenAlexMasking.clusterValues(endpoints.flatMap(strings)) ++
      endpoints.collect {
        case e
            if e.sourceDataset == "openalex" &&
              OpenAlexMasking.ClusterId.matches(e.sourceRecordId) =>
          e.sourceRecordId.toUpperCase(java.util.Locale.ROOT)
      }

  /**
   * The blind row of a pair, or why its endpoints have no source evidence. Every displayed string
   * is masked ([[DblpMasking]]); the pid values found anywhere in the pair, and the record ID of a
   * DBLP profile, are masked in both endpoints.
   */
  def row(i: BlindItem, evidence: Map[String, BlindEndpoint]): Either[String, BlindRow] =
    (evidence.get(i.sourceIdA), evidence.get(i.sourceIdB)) match {
      case (Some(a), Some(b)) =>
        val profilePids = Vector(a, b).collect {
          case e if e.endpointKind == "profile" && DblpMasking.revealsPerson(e.endpointId) =>
            e.sourceRecordId
        }
        val pids = DblpMasking.pidValues(strings(a) ++ strings(b)) ++ profilePids
        val clusterIds = clusterIdsOf(Vector(a, b))
        val (ma, na) = masked(i.memberId, a, pids, clusterIds)
        val (mb, nb) = masked(i.memberId, b, pids, clusterIds)
        val notes = na ++ nb
        Right(
          BlindRow(
            RowSchemaVersion,
            i.memberId,
            i.entityKind,
            labelingTarget(i.entityKind),
            LabelGuide.Version,
            question(i.entityKind),
            UnsureNote,
            Vector(DblpMasking.HomonymNote, DblpMasking.PersonIdNote, OpenAlexMasking.Note)
              .filter(notes.contains),
            ma.endpointId,
            mb.endpointId,
            ma,
            mb,
            LabelOptions
          )
        )
      case _ => Left(s"${i.memberId}: no source evidence for an endpoint")
    }

  /**
   * The blind view of pairs: every pair reduced to source evidence, ordered by SHA-256 of
   * ([[OrderTag]], `seed`, member ID). The order depends only on the member IDs and the seed, so it
   * neither groups nor sorts rows by stratum, decision, score, unit, or criterion, and the input
   * order does not matter. A repeated member ID appears once.
   */
  def build(
      items: Vector[BlindItem],
      evidence: Map[String, BlindEndpoint],
      seed: String
  ): Either[Vector[String], Vector[BlindRow]] = {
    val built = items.distinctBy(_.memberId).map(row(_, evidence))
    val errors = built.collect { case Left(e) => e }
    if (errors.nonEmpty) Left(errors)
    else
      Right(built.collect { case Right(b) => b }.sortBy(b => orderKey(seed, b.memberId)))
  }

  /** JSON Lines, one row per line, in view order. */
  def toJsonl(rows: Vector[BlindRow]): String =
    rows.map(r => write(r)).mkString("", "\n", if (rows.isEmpty) "" else "\n")
}
