package scholarchain.core

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, nonEmpty, when}

/**
 * An exact identifier in the canonical form of its scheme ([[Identifiers.validateExact]]). Ordered
 * by scheme tag, then by the UTF-8 bytes of the value ([[ExactId.compare]]).
 */
@allowUnknownKeys(false)
final case class ExactId(scheme: ExactIdScheme, value: String) derives ReadWriter

object ExactId {

  /** Normalizes a raw value (resolver prefixes, DOI case) and builds the identifier. */
  def of(scheme: ExactIdScheme, raw: String): Either[ValidationError, ExactId] =
    if (scheme == null) Left(ValidationError.MissingField("scheme"))
    else
      Identifiers
        .normalizeExact(scheme, raw)
        .left
        .map(why =>
          ValidationError
            .MalformedId("value", scheme.tag, Identifiers.errorDisplay(scheme, raw), why)
        )
        .map(ExactId(scheme, _))

  def check(field: String, e: ExactId): Vector[ValidationError] =
    if (e == null || e.scheme == null) Vector(ValidationError.MissingField(field))
    else
      Identifiers.validateExact(e.scheme, e.value) match {
        case Right(_) => Vector.empty
        case Left(why) =>
          Vector(
            ValidationError
              .MalformedId(field, e.scheme.tag, Identifiers.errorDisplay(e.scheme, e.value), why)
          )
      }

  /** Scheme tag, then value, both by UTF-8 bytes. */
  def compare(a: ExactId, b: ExactId): Int = {
    val c = Canonical.compareUtf8(a.scheme.tag, b.scheme.tag)
    if (c != 0) c else Canonical.compareUtf8(a.value, b.value)
  }

  /** Sorted, duplicate-free copy (the canonical order of identifier sets). */
  def canonical(ids: Vector[ExactId]): Vector[ExactId] =
    ids.distinct.sortWith((a, b) => compare(a, b) < 0)

  /** Each identifier valid, of `kind`, and the vector strictly ascending (sorted, distinct). */
  def checkSet(
      field: String,
      ids: Vector[ExactId],
      kind: MatchEntityKind
  ): Vector[ValidationError] =
    if (ids == null) Vector(ValidationError.MissingField(field))
    else {
      val each = ids.zipWithIndex.flatMap { case (e, i) =>
        val f = s"$field[$i]"
        check(f, e) ++ (
          if (e == null || e.scheme == null || kind == null) Vector.empty
          else
            when(e.scheme.entityKind != kind)(
              ValidationError
                .InvalidValue(f, s"scheme '${e.scheme.tag}' does not identify a ${kind.tag}")
            )
        )
      }
      val valid = each.isEmpty
      each ++ when(valid && ids.sliding(2).exists(w => w.size == 2 && compare(w(0), w(1)) >= 0))(
        ValidationError.InvalidValue(field, "must be sorted by (scheme, value) without duplicates")
      )
    }
}

/**
 * Who asserted an ORCID item: the `common:source` of a work summary (`source-client-id` or
 * `source-orcid`, with `source-name`) and its optional `common:assertion-origin-*`. ORCID iDs are
 * bare and checksum-valid; client IDs are kept as printed by ORCID (printable ASCII). Kept as a
 * record field, not in [[Provenance]]: it is a fact about the source item, and Provenance is shared
 * by every record contract.
 */
@allowUnknownKeys(false)
final case class AssertionSource(
    sourceKind: AssertionSourceKind,
    sourceId: String,
    sourceName: Option[String],
    originKind: Option[AssertionSourceKind],
    originId: Option[String],
    originName: Option[String]
) derives ReadWriter

object AssertionSource {
  private[core] def checkId(
      field: String,
      kind: AssertionSourceKind,
      id: String
  ): Vector[ValidationError] =
    kind match {
      case AssertionSourceKind.Orcid =>
        when(!Identifiers.isValidOrcid(id))(
          ValidationError.MalformedId(
            field,
            "orcid",
            String.valueOf(id),
            "not a checksum-valid bare ORCID iD"
          )
        )
      case AssertionSourceKind.Client =>
        when(!Identifiers.isPrintableAscii(id))(
          ValidationError
            .MalformedId(field, "client", String.valueOf(id), "must be non-empty printable ASCII")
        )
    }

  def check(field: String, a: AssertionSource): Vector[ValidationError] =
    if (a == null) Vector(ValidationError.MissingField(field))
    else {
      val originKind = Option(a.originKind).getOrElse(None)
      val originId = Option(a.originId).getOrElse(None)
      val originName = Option(a.originName).getOrElse(None)
      all(
        when(a.sourceKind == null)(ValidationError.MissingField(at(field, "sourceKind"))),
        Option(a.sourceKind).toVector.flatMap(checkId(at(field, "sourceId"), _, a.sourceId)),
        Option(a.sourceName).getOrElse(None).toVector.flatMap(nonEmpty(at(field, "sourceName"), _)),
        when(originKind.isDefined != originId.isDefined)(
          ValidationError
            .InvalidValue(at(field, "originKind,originId"), "must be both present or both absent")
        ),
        (originKind, originId) match {
          case (Some(k), Some(id)) => checkId(at(field, "originId"), k, id)
          case _ => Vector.empty
        },
        when(originName.isDefined && originKind.isEmpty)(
          ValidationError.InvalidValue(at(field, "originName"), "requires originKind and originId")
        ),
        originName.toVector.flatMap(nonEmpty(at(field, "originName"), _))
      )
    }
}

/**
 * A work identifier that is local to the party that submitted an ORCID work summary: ORCID
 * external-id type `source-work-id` on the work summary itself. The value is unique, if at all,
 * only within its issuer (the `common:source` client or iD holder), so it always travels with its
 * issuer and is deliberately NOT an [[ExactId]]: it never enters `additionalIds`, exact-identifier
 * agreements, or [[IdentityLink]]s, and two equal values from different issuers (or from different
 * holders' copies of one client's work) are not evidence of anything by themselves. Ordered by
 * issuer kind tag, issuer ID, then value, all by UTF-8 bytes.
 *
 * @param issuerKind
 *   kind of the submitting source; equals the work's `assertionSource.sourceKind`
 * @param issuerId
 *   ID of the submitting source (client ID as printed by ORCID, or bare ORCID iD); equals the
 *   work's `assertionSource.sourceId`
 * @param value
 *   the source-work-id as given by ORCID (normalized value preferred), non-blank, without control
 *   characters or surrounding whitespace
 */
@allowUnknownKeys(false)
final case class SourceLocalWorkId(
    issuerKind: AssertionSourceKind,
    issuerId: String,
    value: String
) derives ReadWriter

object SourceLocalWorkId {

  /** Issuer kind tag, issuer ID, then value, each by UTF-8 bytes. */
  def compare(a: SourceLocalWorkId, b: SourceLocalWorkId): Int = {
    val k = Canonical.compareUtf8(a.issuerKind.tag, b.issuerKind.tag)
    if (k != 0) k
    else {
      val i = Canonical.compareUtf8(a.issuerId, b.issuerId)
      if (i != 0) i else Canonical.compareUtf8(a.value, b.value)
    }
  }

  /** Sorted, duplicate-free copy (the canonical order of a work's local IDs). */
  def canonical(ids: Vector[SourceLocalWorkId]): Vector[SourceLocalWorkId] =
    ids.distinct.sortWith((a, b) => compare(a, b) < 0)

  /** Builds a local ID whose issuer is the submitting source of `assertion`. */
  def issuedBy(assertion: AssertionSource, value: String): SourceLocalWorkId =
    SourceLocalWorkId(assertion.sourceKind, assertion.sourceId, value)

  def check(field: String, s: SourceLocalWorkId): Vector[ValidationError] =
    if (s == null) Vector(ValidationError.MissingField(field))
    else
      all(
        when(s.issuerKind == null)(ValidationError.MissingField(at(field, "issuerKind"))),
        Option(s.issuerKind).toVector.flatMap(
          AssertionSource.checkId(at(field, "issuerId"), _, s.issuerId)
        ),
        Validated.text(at(field, "value"), s.value),
        when(s.value != null && s.value.trim.nonEmpty && s.value != s.value.trim)(
          ValidationError.InvalidValue(at(field, "value"), "has surrounding whitespace")
        )
      )

  /**
   * The local IDs of one work: each valid, issued by the work's submitting source
   * (`assertion.sourceKind`, `assertion.sourceId`), and strictly ascending by [[compare]]. A
   * non-empty list requires an assertion source.
   */
  def checkSet(
      field: String,
      ids: Vector[SourceLocalWorkId],
      assertion: Option[AssertionSource]
  ): Vector[ValidationError] =
    if (ids == null) Vector(ValidationError.MissingField(field))
    else {
      val each = ids.zipWithIndex.flatMap { case (s, i) =>
        val f = s"$field[$i]"
        check(f, s) ++ (
          if (s == null) Vector.empty
          else
            assertion match {
              case Some(a) if a != null =>
                when(s.issuerKind != a.sourceKind || s.issuerId != a.sourceId)(
                  ValidationError.InvalidValue(
                    f,
                    "issuer differs from assertionSource (sourceKind, sourceId)"
                  )
                )
              case _ => Vector.empty
            }
        )
      }
      val noContext = when(ids.nonEmpty && assertion.isEmpty)(
        ValidationError.InvalidValue(field, "requires assertionSource (the issuing source)")
      )
      val valid = each.isEmpty
      each ++ noContext ++ when(
        valid && ids.sliding(2).exists(w => w.size == 2 && compare(w(0), w(1)) >= 0)
      )(
        ValidationError
          .InvalidValue(field, "must be sorted by (issuerKind, issuerId, value) without duplicates")
      )
    }
}

/**
 * One exact-identifier assertion: source `assertedBy` states that entity `entityId` carries
 * `identifier` (README "identity-link inputs"). It is evidence with provenance, never a match
 * decision: two links with the same identifier are join input for entity resolution.
 *
 *   - Work links: `entityId` is a work ID of the asserting source's namespace, and `workId` is
 *     absent (the entity is the work).
 *   - Researcher links: `entityId` is an author mention ID (then `workId` is required: the work the
 *     mention belongs to) or a source author profile ID of the asserting source (then `workId` is
 *     optional: a work the profile claims, for example an ORCID holder-to-work claim).
 *
 * @param linkId
 *   [[IdentityLinkId.of]](identifier, entityKind, entityId, workId, assertedBy, provenance.runId)
 * @param assertedBy
 *   dataset that asserts the identifier; equals `provenance.sourceDataset`
 * @param assertionSource
 *   the ORCID client or iD holder behind the assertion; allowed only when `assertedBy` is ORCID
 */
@allowUnknownKeys(false)
final case class IdentityLink(
    schemaVersion: String,
    linkId: String,
    identifier: ExactId,
    entityKind: MatchEntityKind,
    entityId: String,
    workId: Option[String],
    assertedBy: SourceType,
    assertionSource: Option[AssertionSource],
    provenance: Provenance
) derives ReadWriter

object IdentityLink {

  /** Entity kinds that identity links, candidate blocks and match candidates cover. */
  val EntityKinds: Set[MatchEntityKind] = Set(MatchEntityKind.Researcher, MatchEntityKind.Work)

  /** Builds a link asserted by `provenance.sourceDataset` and computes its ID. */
  def build(
      identifier: ExactId,
      entityKind: MatchEntityKind,
      entityId: String,
      workId: Option[String],
      assertionSource: Option[AssertionSource],
      provenance: Provenance
  ): Either[Vector[ValidationError], IdentityLink] =
    (for {
      p <- Option(provenance).toRight(
        ValidationError.MissingProvenance("IdentityLink", "provenance")
      )
      k <- Option(entityKind).toRight(ValidationError.MissingField("entityKind"))
      s <- Option(p.sourceDataset).toRight(ValidationError.MissingField("provenance.sourceDataset"))
      id <- IdentityLinkId.of(identifier, k, entityId, workId, s, p.runId)
    } yield IdentityLink(
      SchemaVersions.IdentityLink,
      id,
      identifier,
      k,
      entityId,
      workId,
      s,
      assertionSource,
      p
    )) match {
      case Left(e) => Left(Vector(e))
      case Right(link) =>
        val errors = contract.validate(link)
        if (errors.isEmpty) Right(link) else Left(errors)
    }

  given contract: RecordContract[IdentityLink] =
    new RecordContract[IdentityLink]("IdentityLink", SchemaVersions.IdentityLink, true) {
      def schemaVersionOf(t: IdentityLink): String = t.schemaVersion
      def provenanceOf(t: IdentityLink): Provenance = t.provenance
      protected def fields(t: IdentityLink, p: String): Vector[ValidationError] = {
        val workId = Option(t.workId).getOrElse(None)
        val assertion = Option(t.assertionSource).getOrElse(None)
        val entityKindOf = StableId.prefixKind(t.entityId)
        val local = all(
          ExactId.check(at(p, "identifier"), t.identifier),
          when(t.entityKind == null || !EntityKinds.contains(t.entityKind))(
            ValidationError.InvalidValue(at(p, "entityKind"), "must be 'researcher' or 'work'")
          ),
          when(
            t.identifier != null && t.identifier.scheme != null && t.entityKind != null &&
              t.identifier.scheme.entityKind != t.entityKind
          )(
            ValidationError.InvalidValue(
              at(p, "identifier.scheme"),
              s"does not identify a ${t.entityKind.tag}"
            )
          ),
          t.entityKind match {
            case MatchEntityKind.Work =>
              StableId.check(IdKind.Work, t.entityId, at(p, "entityId")) ++
                when(workId.isDefined)(
                  ValidationError.InvalidValue(at(p, "workId"), "must be null for a work link")
                )
            case MatchEntityKind.Researcher =>
              StableId.checkOneOf(
                Set(IdKind.Mention, IdKind.SourceAuthor),
                t.entityId,
                at(p, "entityId")
              ) ++ when(entityKindOf.contains(IdKind.Mention) && workId.isEmpty)(
                ValidationError.MissingField(at(p, "workId"))
              )
            case _ => Vector.empty
          },
          workId.toVector.flatMap(StableId.check(IdKind.Work, _, at(p, "workId"))),
          when(t.assertedBy == null || !t.assertedBy.isDataset)(
            ValidationError.InvalidValue(at(p, "assertedBy"), "must be a dataset source")
          ),
          when(
            t.assertedBy != null && t.provenance != null &&
              t.provenance.sourceDataset != t.assertedBy
          )(
            ValidationError
              .InvalidValue(at(p, "assertedBy"), "differs from provenance.sourceDataset")
          ),
          (t.entityKind, Option(t.assertedBy)) match {
            case (MatchEntityKind.Work, Some(s)) if s.isDataset =>
              WorkRecord.NamespaceOf.get(s).toVector.flatMap { ns =>
                when(t.entityId != null && !t.entityId.startsWith(s"work:$ns:"))(
                  ValidationError.InvalidValue(
                    at(p, "entityId"),
                    s"work namespace must be '$ns' for source '${s.tag}'"
                  )
                )
              }
            case (MatchEntityKind.Researcher, Some(s))
                if entityKindOf.contains(IdKind.SourceAuthor) =>
              SourceAuthorId.sourceOf(t.entityId).toVector.flatMap { owner =>
                when(owner != s)(
                  ValidationError.InvalidValue(
                    at(p, "entityId"),
                    s"profile of source '${owner.tag}' asserted by '${s.tag}'"
                  )
                )
              }
            case _ => Vector.empty
          },
          assertion.toVector.flatMap(AssertionSource.check(at(p, "assertionSource"), _)),
          when(assertion.isDefined && t.assertedBy != SourceType.Orcid)(
            ValidationError
              .InvalidValue(at(p, "assertionSource"), "only allowed for ORCID assertions")
          ),
          StableId.check(IdKind.IdentityLink, t.linkId, at(p, "linkId"))
        )
        val idCheck =
          if (local.nonEmpty || t.provenance == null) Vector.empty
          else
            IdentityLinkId.of(
              t.identifier,
              t.entityKind,
              t.entityId,
              workId,
              t.assertedBy,
              t.provenance.runId
            ) match {
              case Right(expected) =>
                when(expected != t.linkId)(
                  ValidationError.InvalidValue(at(p, "linkId"), s"expected $expected")
                )
              case Left(_) => Vector.empty
            }
        local ++ idCheck
      }
    }
}

/**
 * One source's author profile before identity resolution: an OpenAlex author (A-ID), an ORCID
 * record holder, or a DBLP person. Keeps the source-specific IDs, names and affiliations that a
 * work record cannot carry. A matching name is never evidence of identity.
 *
 * @param profileId
 *   [[SourceAuthorId.of]](namespace of `source`, `sourceRecordId`)
 * @param sourceRecordId
 *   the source's author ID in canonical form (A-ID, bare ORCID iD, or DBLP pid)
 * @param displayName
 *   source display name (absent when the source hides it, for example a private ORCID name)
 * @param givenNames
 *   ORCID `given-names`, when public
 * @param familyName
 *   ORCID `family-name`, when public
 * @param nameVariants
 *   OpenAlex `display_name_alternatives` or ORCID `other-names`, distinct, in source order
 * @param orcid
 *   ORCID iD asserted by the source; for an ORCID profile it is the holder's own iD (required)
 * @param externalIds
 *   other source-asserted author IDs (for example the OpenAlex A-ID), distinct
 * @param institutionIds
 *   last known institutions asserted by the source, distinct, in source order
 * @param claimedWorkIds
 *   ORCID holder-to-work claims: `work:orcid-work:<holder iD>/work/<put-code>` IDs of the holder's
 *   work summaries, sorted by UTF-8 bytes without duplicates. Empty for other sources
 */
@allowUnknownKeys(false)
final case class SourceAuthorProfile(
    schemaVersion: String,
    profileId: String,
    source: SourceType,
    sourceRecordId: String,
    displayName: Option[String],
    givenNames: Option[String],
    familyName: Option[String],
    nameVariants: Vector[String],
    orcid: Option[String],
    externalIds: Vector[ExternalId],
    institutionIds: Vector[String],
    claimedWorkIds: Vector[String],
    provenance: Provenance
) derives ReadWriter

object SourceAuthorProfile {

  /** Profile ID namespace of each source that has author profiles. */
  val NamespaceOf: Map[SourceType, String] =
    SourceAuthorId.Namespaces.map { case (ns, (src, _)) => src -> ns }

  given contract: RecordContract[SourceAuthorProfile] =
    new RecordContract[SourceAuthorProfile](
      "SourceAuthorProfile",
      SchemaVersions.SourceAuthorProfile,
      true
    ) {
      def schemaVersionOf(t: SourceAuthorProfile): String = t.schemaVersion
      def provenanceOf(t: SourceAuthorProfile): Provenance = t.provenance
      protected def fields(t: SourceAuthorProfile, p: String): Vector[ValidationError] = {
        val variants = Option(t.nameVariants).getOrElse(Vector.empty)
        val ext = Option(t.externalIds).getOrElse(Vector.empty)
        val insts = Option(t.institutionIds).getOrElse(Vector.empty)
        val claims = Option(t.claimedWorkIds).getOrElse(Vector.empty)
        val orcid = Option(t.orcid).getOrElse(None)
        val namespace = Option(t.source).flatMap(NamespaceOf.get)
        all(
          StableId.check(IdKind.SourceAuthor, t.profileId, at(p, "profileId")),
          when(t.source == null || namespace.isEmpty)(
            ValidationError.InvalidValue(at(p, "source"), "must be a source with author profiles")
          ),
          when(t.source != null && t.provenance != null && t.provenance.sourceDataset != t.source)(
            ValidationError.InvalidValue(at(p, "source"), "differs from provenance.sourceDataset")
          ),
          nonEmpty(at(p, "sourceRecordId"), t.sourceRecordId),
          namespace.toVector.flatMap { ns =>
            SourceAuthorId.of(ns, t.sourceRecordId) match {
              case Right(expected) =>
                when(expected != t.profileId)(
                  ValidationError.InvalidValue(at(p, "profileId"), s"expected $expected")
                )
              case Left(_) =>
                Vector(
                  ValidationError.InvalidValue(
                    at(p, "sourceRecordId"),
                    s"not a canonical '$ns' author ID"
                  )
                )
            }
          },
          Option(t.displayName).getOrElse(None).toVector.flatMap(nonEmpty(at(p, "displayName"), _)),
          Option(t.givenNames).getOrElse(None).toVector.flatMap(nonEmpty(at(p, "givenNames"), _)),
          Option(t.familyName).getOrElse(None).toVector.flatMap(nonEmpty(at(p, "familyName"), _)),
          when(t.nameVariants == null)(ValidationError.MissingField(at(p, "nameVariants"))),
          variants.zipWithIndex.flatMap { case (v, i) => nonEmpty(at(p, s"nameVariants[$i]"), v) },
          Validated.distinct(at(p, "nameVariants"), variants),
          orcid.toVector.flatMap { o =>
            when(!Identifiers.isValidOrcid(o))(
              ValidationError
                .MalformedId(at(p, "orcid"), "orcid", o, "not a checksum-valid ORCID iD")
            )
          },
          when(t.source == SourceType.Orcid && !orcid.contains(t.sourceRecordId))(
            ValidationError
              .InvalidValue(at(p, "orcid"), "an ORCID profile must carry the holder's iD")
          ),
          when(t.externalIds == null)(ValidationError.MissingField(at(p, "externalIds"))),
          ext.zipWithIndex.flatMap { case (e, i) =>
            ExternalId.check(at(p, s"externalIds[$i]"), e)
          },
          Validated.distinct(at(p, "externalIds"), ext.map(String.valueOf)),
          when(t.institutionIds == null)(ValidationError.MissingField(at(p, "institutionIds"))),
          insts.zipWithIndex.flatMap { case (id, i) =>
            StableId.check(IdKind.Institution, id, at(p, s"institutionIds[$i]"))
          },
          Validated.distinct(at(p, "institutionIds"), insts),
          when(t.claimedWorkIds == null)(ValidationError.MissingField(at(p, "claimedWorkIds"))),
          when(t.source != SourceType.Orcid && claims.nonEmpty)(
            ValidationError.InvalidValue(at(p, "claimedWorkIds"), "only ORCID profiles claim works")
          ),
          claims.zipWithIndex.flatMap { case (w, i) =>
            val f = at(p, s"claimedWorkIds[$i]")
            StableId.check(IdKind.Work, w, f) ++ when(
              t.source == SourceType.Orcid && w != null &&
                !w.startsWith(s"work:orcid-work:${t.sourceRecordId}/work/")
            )(ValidationError.InvalidValue(f, "not a work summary of the holder"))
          },
          Validated.strictlyAscending(at(p, "claimedWorkIds"), claims)
        )
      }
    }
}
