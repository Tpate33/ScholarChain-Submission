package scholarchain.entityresolution

import scala.collection.immutable.SortedMap

import scholarchain.core.{
  AuthorMention,
  ExactId,
  ExactIdScheme,
  ExternalId,
  ExternalIdScheme,
  SourceAuthorId,
  SourceAuthorProfile,
  SourceType,
  WorkRecord
}

/** One side of a researcher candidate pair: an author mention on its work, or a source profile. */
enum ResearcherEndpoint {
  case OfMention(work: WorkRecord, mention: AuthorMention)
  case OfProfile(profile: SourceAuthorProfile)

  /** Mention ID or source author profile ID (the MatchCandidate endpoint ID). */
  def id: String = this match {
    case OfMention(_, m) => m.mentionId
    case OfProfile(p) => p.profileId
  }

  /** Dataset of the endpoint (the MatchCandidate endpoint dataset). */
  def source: SourceType = this match {
    case OfMention(w, _) => w.source
    case OfProfile(p) => p.source
  }
}

/** Work context of a prepared author mention. */
final case class MentionContext(
    workId: String,
    position: Int,
    teamSize: Int,
    year: Option[Int],
    venueId: Option[String],
    venueTokens: Set[String],
    titleTokens: Set[String],
    doi: Option[String],
    coauthorKeys: Set[String]
)

/**
 * A researcher endpoint with every feature input precomputed once ([[MatchFeatures.prepare]]), so
 * that comparing all pairs of a block does not re-parse whole author teams for every pair.
 */
final case class PreparedResearcher(
    id: String,
    source: SourceType,
    name: PersonName,
    variants: Vector[PersonName],
    exactIds: Vector[ExactId],
    affiliationIds: Set[String],
    affiliationTokens: Set[String],
    context: Option[MentionContext],
    claimedWorkIds: Set[String]
)

/** A work with every feature input precomputed once ([[MatchFeatures.prepare]]). */
final case class PreparedWork(
    id: String,
    source: SourceType,
    titleText: String,
    titleTokens: Set[String],
    year: Option[Int],
    venueId: Option[String],
    venueTokens: Set[String],
    exactIds: Vector[ExactId],
    authorFamilies: Vector[String],
    orcids: Set[String]
)

/**
 * Versioned, deterministic match features (README Phase 4 job 5). A feature is a named raw
 * measurement, never a score, weight, threshold, or decision: those belong to the Phase 5 policy.
 * Every function is pure, symmetric in its two arguments, and returns finite values under token
 * keys, so the result can be stored as `MatchCandidate.features`.
 *
 * Conventions: a `*.both` feature is 1 when both sides carry the input and 0 otherwise; when an
 * input is missing on either side, the measurements that need it are 0 (so 0 alone never means
 * "disagree"; read it together with the `*.both` flag). Booleans are 0 or 1. Similarities and
 * Jaccard ratios are in [0, 1]. Counts and differences are non-negative integers stored as doubles.
 *
 * Researcher features ([[ResearcherFeatureNames]]):
 *   - `name.family_exact`: folded family keys equal (umlaut-folded `pöpper` vs `poepper` differ).
 *   - `name.family_key_agree`: blocking family keys equal (vowel digraphs collapsed: equal).
 *   - `name.family_sim`: Levenshtein similarity of blocking family keys.
 *   - `name.given_both`: both names have a given part.
 *   - `name.first_initial_agree`: first initials equal.
 *   - `name.initials_compatible`: one initials string is a prefix of the other (`jc` and `j`).
 *   - `name.first_given_agree`: both first given parts are written out (2 or more letters) and
 *     equal after vowel-digraph collapse.
 *   - `name.given_sim`: Levenshtein similarity of the given parts joined by spaces.
 *   - `name.token_jaccard`: Jaccard of the name token sets (given parts and family key).
 *   - `name.best_variant_sim`: maximum Levenshtein similarity of full name keys over all pairs of
 *     name variants (profile display name, structured name, and listed variants; a mention has its
 *     one written name).
 *   - `name.homonym_suffix_both`, `name.homonym_suffix_agree`: both names carry a DBLP homonym
 *     number, and the numbers are equal. Different numbers mean DBLP asserts different people.
 *   - `id.<scheme>.both`, `id.<scheme>.agree`, `id.<scheme>.conflict` for the researcher schemes
 *     `orcid`, `openalex-author`, `dblp-pid`: both sides assert the scheme; the asserted value sets
 *     intersect; they are disjoint (disagreeing authoritative identifiers).
 *   - `context.same_source`: both endpoints come from the same dataset.
 *   - `context.work_both`: both endpoints are mentions (have work context).
 *   - `context.same_work`: both are mentions on the same work ID (normally two different authors).
 *   - `context.claimed_work`: an ORCID profile on one side claims the other side's work.
 *   - `coauthor.both`, `coauthor.shared`, `coauthor.jaccard`: both mentions have coauthors; number
 *     of shared coauthor keys (`<family block key>|<first initial>`); Jaccard of those key sets.
 *   - `year.both`, `year.abs_diff`: both works have a year; absolute year difference.
 *   - `venue.id_both`, `venue.id_agree`: both works have a venue ID; they are equal.
 *   - `venue.name_both`, `venue.name_jaccard`: both works have venue name tokens; their Jaccard.
 *   - `affiliation.id_both`, `affiliation.id_shared`, `affiliation.id_jaccard`: institution IDs
 *     (mention affiliations or profile last known institutions).
 *   - `affiliation.text_both`, `affiliation.text_jaccard`: tokens of raw affiliation strings.
 *   - `work.title_jaccard`: Jaccard of the two works' title content tokens.
 *   - `work.position_agree`: equal author positions (both mentions).
 *   - `work.team_size_agree`: equal author counts (both mentions).
 *   - `work.doi_agree`: both works carry the same DOI.
 *
 * Work features ([[WorkFeatureNames]]):
 *   - `id.doi.*`, `id.arxiv.*`: `both`, `agree`, `conflict` as above, over the work's DOI (the
 *     `doi` field, a Crossref work ID, and further DOIs in `additionalIds`) and arXiv ID.
 *   - `id.other.agree`, `id.other.conflict`: number of other work schemes (`dblp-key`,
 *     `openalex-work`, `mag`, `scopus-eid`) whose value sets intersect, or are disjoint.
 *   - `title.exact`: folded titles equal (non-empty).
 *   - `title.sim`: Levenshtein similarity of the folded titles.
 *   - `title.token_jaccard`: Jaccard of title content tokens.
 *   - `year.*`, `venue.*`: as for researchers.
 *   - `authors.both`: both works list authors.
 *   - `authors.count_diff`: absolute difference of the author counts.
 *   - `authors.family_shared`, `authors.family_jaccard`: shared author family block keys and their
 *     Jaccard.
 *   - `authors.first_family_agree`: the first authors' family block keys are equal.
 *   - `authors.sequence_exact`: the family block key sequences are equal (compatible author
 *     sequence).
 *   - `authors.sequence_lcs_ratio`: longest common subsequence of the family sequences divided by
 *     the longer sequence length.
 *   - `authors.orcid_shared`: number of ORCID iDs asserted on both works.
 *   - `context.same_source`.
 *
 * Citation-neighbourhood evidence is not computed: no citation dataset is selected.
 */
object MatchFeatures {

  /** Version of the feature functions; recorded as `featureVersion` on candidates. */
  val Version: String = "er-features.v1"

  /** Researcher identifier schemes with per-scheme features, in feature order. */
  val ResearcherIdSchemes: Vector[ExactIdScheme] =
    Vector(ExactIdScheme.Orcid, ExactIdScheme.OpenAlexAuthor, ExactIdScheme.DblpPid)

  /** Work schemes counted by `id.other.*`. */
  val OtherWorkIdSchemes: Vector[ExactIdScheme] = Vector(
    ExactIdScheme.DblpKey,
    ExactIdScheme.OpenAlexWork,
    ExactIdScheme.Mag,
    ExactIdScheme.ScopusEid
  )

  private def b(x: Boolean): Double = if (x) 1.0 else 0.0

  // ------------------------------------------------------------------ identifiers

  private def exactOf(scheme: ExactIdScheme, value: String): Option[ExactId] =
    ExactId.of(scheme, value).toOption

  private def fromExternal(e: ExternalId): Option[ExactId] =
    if (e == null || e.scheme == null) None
    else
      e.scheme match {
        case ExternalIdScheme.Orcid => exactOf(ExactIdScheme.Orcid, e.value)
        case ExternalIdScheme.DblpPid => exactOf(ExactIdScheme.DblpPid, e.value)
        case ExternalIdScheme.OpenAlex => exactOf(ExactIdScheme.OpenAlexAuthor, e.value)
        case _ => None
      }

  /**
   * Researcher identifiers asserted for an endpoint, canonical order. A mention: its ORCID and its
   * source author ID (DBLP pid, OpenAlex A-ID). A profile: its own source ID, its ORCID, and its
   * external IDs. Values that are not canonical for their scheme are ignored.
   */
  def researcherExactIds(e: ResearcherEndpoint): Vector[ExactId] = {
    val ids = e match {
      case ResearcherEndpoint.OfMention(_, m) =>
        m.orcid.toVector.flatMap(exactOf(ExactIdScheme.Orcid, _)) ++
          m.sourceAuthorId.toVector.flatMap(fromExternal)
      case ResearcherEndpoint.OfProfile(p) =>
        val own = SourceAuthorId.parts(p.profileId).toVector.flatMap {
          case ("openalex", v) => exactOf(ExactIdScheme.OpenAlexAuthor, v)
          case ("orcid", v) => exactOf(ExactIdScheme.Orcid, v)
          case ("dblp-pid", v) => exactOf(ExactIdScheme.DblpPid, v)
          case _ => None
        }
        own ++ p.orcid.toVector.flatMap(exactOf(ExactIdScheme.Orcid, _)) ++
          p.externalIds.flatMap(fromExternal)
    }
    ExactId.canonical(ids)
  }

  /**
   * Work identifiers: the work's own source ID, DOI, arXiv ID, and `additionalIds`, canonical
   * order.
   */
  def workExactIds(w: WorkRecord): Vector[ExactId] =
    ExactId.canonical(WorkRecord.dedicatedIds(w).toVector ++ w.additionalIds)

  /** Identifiers asserted for both sides with equal canonical values (MatchCandidate field). */
  def exactIdAgreements(a: Vector[ExactId], b: Vector[ExactId]): Vector[ExactId] = {
    val bs = b.toSet
    ExactId.canonical(a.filter(bs.contains))
  }

  private def schemeValues(ids: Vector[ExactId], s: ExactIdScheme): Set[String] =
    ids.iterator.filter(_.scheme == s).map(_.value).toSet

  /** (both, agree, conflict) for one scheme. */
  private def schemeCompare(
      a: Vector[ExactId],
      bIds: Vector[ExactId],
      s: ExactIdScheme
  ): (Boolean, Boolean, Boolean) = {
    val x = schemeValues(a, s)
    val y = schemeValues(bIds, s)
    val both = x.nonEmpty && y.nonEmpty
    val agree = both && x.exists(y.contains)
    (both, agree, both && !agree)
  }

  // ------------------------------------------------------------------ preparation

  private def coauthorKey(n: PersonName): String =
    n.familyBlockKey + BlockingKeys.Separator + n.firstInitial.getOrElse(BlockingKeys.Missing)

  private def venueTokens(w: WorkRecord): Set[String] =
    w.venueName.toVector.flatMap(TitleText.textTokens).toSet

  /** Precomputes the feature inputs of a researcher endpoint. */
  def prepare(e: ResearcherEndpoint): PreparedResearcher = e match {
    case ResearcherEndpoint.OfMention(w, m) =>
      val name = PersonName.parse(m.rawName)
      val coauthors = w.authors.iterator
        .filter(_.mentionId != m.mentionId)
        .map(o => PersonName.parse(o.rawName))
        .filterNot(_.isEmpty)
        .map(coauthorKey)
        .toSet
      PreparedResearcher(
        id = m.mentionId,
        source = w.source,
        name = name,
        variants = if (name.isEmpty) Vector.empty else Vector(name),
        exactIds = researcherExactIds(e),
        affiliationIds = m.affiliationIds.toSet,
        affiliationTokens = m.rawAffiliations.iterator.flatMap(TitleText.textTokens).toSet,
        context = Some(
          MentionContext(
            workId = w.workId,
            position = m.position,
            teamSize = w.authors.size,
            year = w.year,
            venueId = w.venueId,
            venueTokens = venueTokens(w),
            titleTokens = TitleText.contentTokens(w.title).toSet,
            doi = w.doi,
            coauthorKeys = coauthors
          )
        ),
        claimedWorkIds = Set.empty
      )
    case ResearcherEndpoint.OfProfile(p) =>
      val name = BlockingKeys.profileName(p)
      val all = (Vector(
        PersonName.structured(p.givenNames, p.familyName),
        PersonName.parse(p.displayName.getOrElse(""))
      ) ++ p.nameVariants.map(PersonName.parse)).filterNot(_.isEmpty)
      PreparedResearcher(
        id = p.profileId,
        source = p.source,
        name = name,
        variants = all.distinct,
        exactIds = researcherExactIds(e),
        affiliationIds = p.institutionIds.toSet,
        affiliationTokens = Set.empty,
        context = None,
        claimedWorkIds = p.claimedWorkIds.toSet
      )
  }

  /** Precomputes the feature inputs of a work. */
  def prepare(w: WorkRecord): PreparedWork =
    PreparedWork(
      id = w.workId,
      source = w.source,
      titleText = TitleText.normalize(w.title),
      titleTokens = TitleText.contentTokens(w.title).toSet,
      year = w.year,
      venueId = w.venueId,
      venueTokens = venueTokens(w),
      exactIds = workExactIds(w),
      authorFamilies = w.authors.map(m => PersonName.parse(m.rawName).familyBlockKey),
      orcids = w.authors.iterator.flatMap(_.orcid).toSet
    )

  // ------------------------------------------------------------------ researcher features

  /** Researcher feature names of this version, in key order. */
  val ResearcherFeatureNames: Vector[String] = Vector(
    "affiliation.id_both",
    "affiliation.id_jaccard",
    "affiliation.id_shared",
    "affiliation.text_both",
    "affiliation.text_jaccard",
    "coauthor.both",
    "coauthor.jaccard",
    "coauthor.shared",
    "context.claimed_work",
    "context.same_source",
    "context.same_work",
    "context.work_both",
    "id.dblp-pid.agree",
    "id.dblp-pid.both",
    "id.dblp-pid.conflict",
    "id.openalex-author.agree",
    "id.openalex-author.both",
    "id.openalex-author.conflict",
    "id.orcid.agree",
    "id.orcid.both",
    "id.orcid.conflict",
    "name.best_variant_sim",
    "name.family_exact",
    "name.family_key_agree",
    "name.family_sim",
    "name.first_given_agree",
    "name.first_initial_agree",
    "name.given_both",
    "name.given_sim",
    "name.homonym_suffix_agree",
    "name.homonym_suffix_both",
    "name.initials_compatible",
    "name.token_jaccard",
    "venue.id_agree",
    "venue.id_both",
    "venue.name_both",
    "venue.name_jaccard",
    "work.doi_agree",
    "work.position_agree",
    "work.team_size_agree",
    "work.title_jaccard",
    "year.abs_diff",
    "year.both"
  )

  /** Features of a researcher candidate pair (symmetric). */
  def researcher(a: ResearcherEndpoint, b: ResearcherEndpoint): SortedMap[String, Double] =
    researcher(prepare(a), prepare(b))

  /** Features of two author mentions with their works (symmetric). */
  def researcher(
      aWork: WorkRecord,
      a: AuthorMention,
      bWork: WorkRecord,
      b: AuthorMention
  ): SortedMap[String, Double] =
    researcher(ResearcherEndpoint.OfMention(aWork, a), ResearcherEndpoint.OfMention(bWork, b))

  /** Features of two prepared researcher endpoints (symmetric). */
  def researcher(x: PreparedResearcher, y: PreparedResearcher): SortedMap[String, Double] = {
    val nx = x.name
    val ny = y.name
    val namesBoth = !nx.isEmpty && !ny.isEmpty
    val givenBoth = nx.givenParts.nonEmpty && ny.givenParts.nonEmpty
    val ix = nx.initials
    val iy = ny.initials
    val firstGivenAgree = (nx.firstGivenKey, ny.firstGivenKey) match {
      case (Some(p), Some(q)) =>
        TextFolding.codePointLength(p) >= 2 && TextFolding.codePointLength(q) >= 2 && p == q
      case _ => false
    }
    val bestVariant =
      if (x.variants.isEmpty || y.variants.isEmpty) 0.0
      else
        x.variants.iterator
          .flatMap(p =>
            y.variants.iterator.map(q => Similarity.levenshteinSimilarity(p.fullKey, q.fullKey))
          )
          .max
    val suffixBoth = nx.homonymSuffix.isDefined && ny.homonymSuffix.isDefined

    val ids = ResearcherIdSchemes.flatMap { s =>
      val (both, agree, conflict) = schemeCompare(x.exactIds, y.exactIds, s)
      Vector(
        s"id.${s.tag}.both" -> b(both),
        s"id.${s.tag}.agree" -> b(agree),
        s"id.${s.tag}.conflict" -> b(conflict)
      )
    }

    val cx = x.context
    val cy = y.context
    val workBoth = cx.isDefined && cy.isDefined
    def ctx[A](f: (MentionContext, MentionContext) => A, default: A): A =
      (cx, cy) match {
        case (Some(p), Some(q)) => f(p, q)
        case _ => default
      }
    val coBoth = ctx((p, q) => p.coauthorKeys.nonEmpty && q.coauthorKeys.nonEmpty, false)
    val yearBoth = ctx((p, q) => p.year.isDefined && q.year.isDefined, false)
    val venueIdBoth = ctx((p, q) => p.venueId.isDefined && q.venueId.isDefined, false)
    val venueNameBoth = ctx((p, q) => p.venueTokens.nonEmpty && q.venueTokens.nonEmpty, false)
    val claimed =
      cy.exists(c => x.claimedWorkIds.contains(c.workId)) ||
        cx.exists(c => y.claimedWorkIds.contains(c.workId))

    val affIdBoth = x.affiliationIds.nonEmpty && y.affiliationIds.nonEmpty
    val affTextBoth = x.affiliationTokens.nonEmpty && y.affiliationTokens.nonEmpty

    SortedMap.from(
      Vector(
        "name.family_exact" -> b(namesBoth && nx.familyKey == ny.familyKey),
        "name.family_key_agree" -> b(namesBoth && nx.familyBlockKey == ny.familyBlockKey),
        "name.family_sim" -> Similarity.levenshteinSimilarity(nx.familyBlockKey, ny.familyBlockKey),
        "name.given_both" -> b(givenBoth),
        "name.first_initial_agree" -> b(givenBoth && nx.firstInitial == ny.firstInitial),
        "name.initials_compatible" -> b(givenBoth && (ix.startsWith(iy) || iy.startsWith(ix))),
        "name.first_given_agree" -> b(firstGivenAgree),
        "name.given_sim" -> Similarity.levenshteinSimilarity(nx.givenText, ny.givenText),
        "name.token_jaccard" -> Similarity.jaccard(nx.tokens, ny.tokens),
        "name.best_variant_sim" -> bestVariant,
        "name.homonym_suffix_both" -> b(suffixBoth),
        "name.homonym_suffix_agree" -> b(suffixBoth && nx.homonymSuffix == ny.homonymSuffix),
        "context.same_source" -> b(x.source == y.source),
        "context.work_both" -> b(workBoth),
        "context.same_work" -> b(ctx((p, q) => p.workId == q.workId, false)),
        "context.claimed_work" -> b(claimed),
        "coauthor.both" -> b(coBoth),
        "coauthor.shared" ->
          ctx((p, q) => p.coauthorKeys.count(q.coauthorKeys.contains).toDouble, 0.0),
        "coauthor.jaccard" -> ctx(
          (p, q) => Similarity.jaccard(p.coauthorKeys, q.coauthorKeys),
          0.0
        ),
        "year.both" -> b(yearBoth),
        "year.abs_diff" -> (if (yearBoth)
                              ctx((p, q) => math.abs(p.year.get - q.year.get).toDouble, 0.0)
                            else 0.0),
        "venue.id_both" -> b(venueIdBoth),
        "venue.id_agree" -> b(venueIdBoth && ctx((p, q) => p.venueId == q.venueId, false)),
        "venue.name_both" -> b(venueNameBoth),
        "venue.name_jaccard" -> ctx(
          (p, q) => Similarity.jaccard(p.venueTokens, q.venueTokens),
          0.0
        ),
        "affiliation.id_both" -> b(affIdBoth),
        "affiliation.id_shared" -> x.affiliationIds.count(y.affiliationIds.contains).toDouble,
        "affiliation.id_jaccard" -> Similarity.jaccard(x.affiliationIds, y.affiliationIds),
        "affiliation.text_both" -> b(affTextBoth),
        "affiliation.text_jaccard" -> Similarity.jaccard(x.affiliationTokens, y.affiliationTokens),
        "work.title_jaccard" -> ctx(
          (p, q) => Similarity.jaccard(p.titleTokens, q.titleTokens),
          0.0
        ),
        "work.position_agree" -> b(ctx((p, q) => p.position == q.position, false)),
        "work.team_size_agree" -> b(ctx((p, q) => p.teamSize == q.teamSize, false)),
        "work.doi_agree" -> b(ctx((p, q) => p.doi.isDefined && p.doi == q.doi, false))
      ) ++ ids
    )
  }

  // ------------------------------------------------------------------ work features

  /** Work feature names of this version, in key order. */
  val WorkFeatureNames: Vector[String] = Vector(
    "authors.both",
    "authors.count_diff",
    "authors.family_jaccard",
    "authors.family_shared",
    "authors.first_family_agree",
    "authors.orcid_shared",
    "authors.sequence_exact",
    "authors.sequence_lcs_ratio",
    "context.same_source",
    "id.arxiv.agree",
    "id.arxiv.both",
    "id.arxiv.conflict",
    "id.doi.agree",
    "id.doi.both",
    "id.doi.conflict",
    "id.other.agree",
    "id.other.conflict",
    "title.exact",
    "title.sim",
    "title.token_jaccard",
    "venue.id_agree",
    "venue.id_both",
    "venue.name_both",
    "venue.name_jaccard",
    "year.abs_diff",
    "year.both"
  )

  /** Features of a work candidate pair (symmetric). */
  def work(a: WorkRecord, b: WorkRecord): SortedMap[String, Double] = work(prepare(a), prepare(b))

  /** Features of two prepared works (symmetric). */
  def work(x: PreparedWork, y: PreparedWork): SortedMap[String, Double] = {
    val (doiBoth, doiAgree, doiConflict) = schemeCompare(x.exactIds, y.exactIds, ExactIdScheme.Doi)
    val (arxBoth, arxAgree, arxConflict) =
      schemeCompare(x.exactIds, y.exactIds, ExactIdScheme.Arxiv)
    val others = OtherWorkIdSchemes.map(schemeCompare(x.exactIds, y.exactIds, _))
    val yearBoth = x.year.isDefined && y.year.isDefined
    val venueIdBoth = x.venueId.isDefined && y.venueId.isDefined
    val venueNameBoth = x.venueTokens.nonEmpty && y.venueTokens.nonEmpty
    val fx = x.authorFamilies
    val fy = y.authorFamilies
    val authorsBoth = fx.nonEmpty && fy.nonEmpty
    val sx = fx.filter(_.nonEmpty).toSet
    val sy = fy.filter(_.nonEmpty).toSet
    val longer = math.max(fx.size, fy.size)
    SortedMap(
      "id.doi.both" -> b(doiBoth),
      "id.doi.agree" -> b(doiAgree),
      "id.doi.conflict" -> b(doiConflict),
      "id.arxiv.both" -> b(arxBoth),
      "id.arxiv.agree" -> b(arxAgree),
      "id.arxiv.conflict" -> b(arxConflict),
      "id.other.agree" -> others.count(_._2).toDouble,
      "id.other.conflict" -> others.count(_._3).toDouble,
      "title.exact" -> b(x.titleText.nonEmpty && x.titleText == y.titleText),
      "title.sim" -> Similarity.levenshteinSimilarity(x.titleText, y.titleText),
      "title.token_jaccard" -> Similarity.jaccard(x.titleTokens, y.titleTokens),
      "year.both" -> b(yearBoth),
      "year.abs_diff" -> (if (yearBoth) math.abs(x.year.get - y.year.get).toDouble else 0.0),
      "venue.id_both" -> b(venueIdBoth),
      "venue.id_agree" -> b(venueIdBoth && x.venueId == y.venueId),
      "venue.name_both" -> b(venueNameBoth),
      "venue.name_jaccard" -> Similarity.jaccard(x.venueTokens, y.venueTokens),
      "authors.both" -> b(authorsBoth),
      "authors.count_diff" -> math.abs(fx.size - fy.size).toDouble,
      "authors.family_shared" -> sx.count(sy.contains).toDouble,
      "authors.family_jaccard" -> Similarity.jaccard(sx, sy),
      "authors.first_family_agree" ->
        b(authorsBoth && fx.head.nonEmpty && fx.head == fy.head),
      "authors.sequence_exact" -> b(authorsBoth && fx == fy && fx.forall(_.nonEmpty)),
      "authors.sequence_lcs_ratio" ->
        (if (longer == 0) 0.0 else Similarity.lcsLength(fx, fy).toDouble / longer.toDouble),
      "authors.orcid_shared" -> x.orcids.count(y.orcids.contains).toDouble,
      "context.same_source" -> b(x.source == y.source)
    )
  }
}
