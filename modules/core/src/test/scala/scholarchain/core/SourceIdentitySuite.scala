package scholarchain.core

import Fixtures.*

/**
 * C4 identity links, C6 source author profiles, and item 7 (ORCID assertion source), plus the exact
 * identifier value object they share.
 */
class SourceIdentitySuite extends munit.FunSuite {

  private def invalidAt(errs: Vector[ValidationError], field: String): Boolean =
    errs.exists {
      case ValidationError.InvalidValue(f, _) => f == field
      case ValidationError.MissingField(f) => f == field
      case ValidationError.MalformedId(f, _, _, _) => f == field
      case _ => false
    }

  private def linkErrs(l: IdentityLink) = IdentityLink.contract.validate(l)
  private def profileErrs(p: SourceAuthorProfile) = SourceAuthorProfile.contract.validate(p)

  // ------------------------------------------------------------------ ExactId

  test("exact identifiers are normalized to their canonical form or refused") {
    assertEquals(
      ExactId.of(ExactIdScheme.Doi, "https://doi.org/10.1145/ABC"),
      Right(ExactId(ExactIdScheme.Doi, "10.1145/abc"))
    )
    assertEquals(
      ExactId.of(ExactIdScheme.Orcid, "https://orcid.org/0000-0002-1825-0097"),
      Right(ExactId(ExactIdScheme.Orcid, "0000-0002-1825-0097"))
    )
    assertEquals(
      ExactId.of(ExactIdScheme.OpenAlexAuthor, "https://openalex.org/A5113870699"),
      Right(ExactId(ExactIdScheme.OpenAlexAuthor, "A5113870699"))
    )
    assertEquals(
      ExactId.of(ExactIdScheme.ScopusEid, "2-s2.0-85103197452"),
      Right(ExactId(ExactIdScheme.ScopusEid, "2-s2.0-85103197452"))
    )
    assertEquals(ExactId.of(ExactIdScheme.Mag, "3137759927").map(_.value), Right("3137759927"))
    val bad = Vector(
      ExactIdScheme.Doi -> "doi-less",
      ExactIdScheme.Orcid -> "0000-0002-1825-0098",
      ExactIdScheme.OpenAlexWork -> "A1",
      ExactIdScheme.OpenAlexAuthor -> "W1",
      ExactIdScheme.Mag -> "12a",
      ExactIdScheme.ScopusEid -> "85103197452",
      ExactIdScheme.DblpKey -> "not a key",
      ExactIdScheme.DblpPid -> "no-slash",
      ExactIdScheme.Arxiv -> "arxiv"
    )
    bad.foreach { case (s, v) => assert(ExactId.of(s, v).isLeft, s"${s.tag} '$v'") }
  }

  // ------------------------------------------------------------------ AssertionSource

  test("ORCID assertion source: kinds, IDs, and origin are validated") {
    def errs(a: AssertionSource) = AssertionSource.check("a", a)
    assertEquals(errs(assertion), Vector.empty)
    val holder =
      AssertionSource(AssertionSourceKind.Orcid, orcidA, Some("Ada Example"), None, None, None)
    assertEquals(errs(holder), Vector.empty)
    assert(invalidAt(errs(holder.copy(sourceId = "0000-0000-0000-0002")), "a.sourceId"))
    assert(invalidAt(errs(assertion.copy(sourceId = "has space")), "a.sourceId"))
    assert(invalidAt(errs(assertion.copy(originId = None)), "a.originKind,originId"))
    assert(invalidAt(errs(holder.copy(originName = Some("x"))), "a.originName"))
    assert(invalidAt(errs(assertion.copy(sourceName = Some(" "))), "a.sourceName"))
    assert(invalidAt(errs(assertion.copy(originId = Some("bad"))), "a.originId"))
  }

  // ------------------------------------------------------------------ IdentityLink

  test("a mention link records the asserting source and the mention's work") {
    assertEquals(mentionLink.assertedBy, SourceType.Dblp)
    assertEquals(mentionLink.workId, Some(workId))
    assertEquals(linkErrs(mentionLink), Vector.empty)
    assert(invalidAt(linkErrs(mentionLink.copy(workId = None)), "workId"))
  }

  test("work links: entity is a work of the asserting source; no separate workId") {
    val l = ok(
      IdentityLink.build(
        ExactId(ExactIdScheme.Doi, "10.1234/example.2021.001"),
        MatchEntityKind.Work,
        workId,
        None,
        None,
        dblpProv
      )
    )
    assertEquals(l.assertedBy, SourceType.Dblp)
    assert(invalidAt(linkErrs(l.copy(workId = Some(workId))), "workId"))
    assert(linkErrs(l.copy(entityId = "work:openalex:W1")).exists {
      case ValidationError.InvalidValue("entityId", why) => why.contains("namespace")
      case _ => false
    })
    assert(
      IdentityLink
        .build(
          ExactId(ExactIdScheme.Doi, "10.1234/x"),
          MatchEntityKind.Work,
          mention0.mentionId,
          None,
          None,
          dblpProv
        )
        .isLeft,
      "a mention is not a work"
    )
  }

  test("the identifier scheme must identify the link's entity kind") {
    val doiOnMention = IdentityLink.build(
      ExactId(ExactIdScheme.Doi, "10.1234/example.2021.001"),
      MatchEntityKind.Researcher,
      mention0.mentionId,
      Some(workId),
      None,
      dblpProv
    )
    assert(doiOnMention.left.exists(e => invalidAt(e, "identifier.scheme")))
    assert(invalidAt(linkErrs(mentionLink.copy(entityKind = MatchEntityKind.Venue)), "entityKind"))
  }

  test("assertedBy must equal provenance and own the profile; assertion source only for ORCID") {
    assert(invalidAt(linkErrs(mentionLink.copy(assertedBy = SourceType.OpenAlex)), "assertedBy"))
    assert(
      invalidAt(linkErrs(mentionLink.copy(assertionSource = Some(assertion))), "assertionSource")
    )
    assertEquals(linkErrs(claimLink), Vector.empty)
    assertEquals(claimLink.assertionSource, Some(assertion))
    val foreignProfile = IdentityLink.build(
      ExactId(ExactIdScheme.Orcid, orcidA),
      MatchEntityKind.Researcher,
      openAlexProfileId,
      None,
      None,
      orcidProv(orcidA)
    )
    assert(foreignProfile.left.exists(e => invalidAt(e, "entityId")))
    val openAlexOwn = IdentityLink.build(
      ExactId(ExactIdScheme.Orcid, orcidA),
      MatchEntityKind.Researcher,
      openAlexProfileId,
      None,
      None,
      openAlexProv("A1")
    )
    assert(openAlexOwn.isRight, "a profile link without a work is allowed")
  }

  test("the link ID binds every input") {
    assert(invalidAt(linkErrs(mentionLink.copy(workId = Some(workId2))), "linkId"))
    assert(
      invalidAt(
        linkErrs(mentionLink.copy(identifier = ExactId(ExactIdScheme.Orcid, orcidB))),
        "linkId"
      )
    )
    assert(
      linkErrs(mentionLink.copy(provenance = null))
        .exists(_.isInstanceOf[ValidationError.MissingProvenance])
    )
  }

  // ------------------------------------------------------------------ SourceAuthorProfile

  test("profiles keep source IDs, names, ORCID, and last-known institutions") {
    assertEquals(profileErrs(openAlexProfile), Vector.empty)
    assertEquals(profileErrs(orcidProfile), Vector.empty)
    assertEquals(openAlexProfile.profileId, "source-author:openalex:A1")
    assertEquals(
      orcidProfile.claimedWorkIds,
      Vector("work:orcid-work:0000-0000-0000-0001/work/1001")
    )
  }

  test(
    "profile ID must be the source namespace plus sourceRecordId; source must match provenance"
  ) {
    assert(invalidAt(profileErrs(openAlexProfile.copy(sourceRecordId = "A2")), "profileId"))
    assert(invalidAt(profileErrs(openAlexProfile.copy(sourceRecordId = "W1")), "sourceRecordId"))
    assert(invalidAt(profileErrs(openAlexProfile.copy(source = SourceType.Orcid)), "source"))
    assert(
      invalidAt(
        profileErrs(
          openAlexProfile.copy(
            source = SourceType.Crossref,
            provenance = openAlexProv("A1").copy(
              sourceDataset = SourceType.Crossref,
              sourceSchemaVersion = "crossref-raw/1.0.0"
            )
          )
        ),
        "source"
      ),
      "Crossref has no author profiles"
    )
  }

  test("an ORCID profile carries the holder iD and claims only the holder's work summaries") {
    assert(invalidAt(profileErrs(orcidProfile.copy(orcid = None)), "orcid"))
    assert(invalidAt(profileErrs(orcidProfile.copy(orcid = Some(orcidB))), "orcid"))
    val foreign = ok(WorkId.orcidWork(orcidB, 7L))
    assert(
      invalidAt(
        profileErrs(orcidProfile.copy(claimedWorkIds = Vector(foreign))),
        "claimedWorkIds[0]"
      )
    )
    val two = Vector(ok(WorkId.orcidWork(orcidA, 2L)), ok(WorkId.orcidWork(orcidA, 10L)))
      .sortWith(Canonical.compareUtf8(_, _) < 0)
    assertEquals(profileErrs(orcidProfile.copy(claimedWorkIds = two)), Vector.empty)
    assert(
      invalidAt(profileErrs(orcidProfile.copy(claimedWorkIds = two.reverse)), "claimedWorkIds")
    )
    assert(
      invalidAt(
        profileErrs(openAlexProfile.copy(claimedWorkIds = Vector(orcidWorkId))),
        "claimedWorkIds"
      ),
      "only ORCID profiles claim works"
    )
  }

  test("profile names, external IDs and institutions are validated") {
    assert(invalidAt(profileErrs(openAlexProfile.copy(displayName = Some(""))), "displayName"))
    assert(
      invalidAt(
        profileErrs(openAlexProfile.copy(nameVariants = Vector("A. Example", "A. Example"))),
        "nameVariants"
      )
    )
    assert(
      invalidAt(
        profileErrs(openAlexProfile.copy(institutionIds = Vector("I1"))),
        "institutionIds[0]"
      )
    )
    assert(
      invalidAt(
        profileErrs(
          openAlexProfile.copy(externalIds = Vector(ExternalId(ExternalIdScheme.Orcid, "bad")))
        ),
        "externalIds[0]"
      )
    )
    assert(
      invalidAt(profileErrs(openAlexProfile.copy(orcid = Some("0000-0000-0000-0002"))), "orcid")
    )
    // a private ORCID record may hide every name
    assertEquals(
      profileErrs(
        orcidProfile.copy(givenNames = None, familyName = None, nameVariants = Vector.empty)
      ),
      Vector.empty
    )
  }
}
