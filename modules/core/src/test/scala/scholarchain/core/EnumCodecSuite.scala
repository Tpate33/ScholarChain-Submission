package scholarchain.core

import upickle.default.{read, write, ReadWriter}

/** Enum tags are frozen wire values: these lists change only with a schema version bump. */
class EnumCodecSuite extends munit.FunSuite {

  private def roundTrip[E: ReadWriter](values: Seq[E], tagOf: E => String, frozen: Seq[String])(
      using loc: munit.Location
  ): Unit = {
    assertEquals(values.map(tagOf), frozen)
    values.foreach { v =>
      assertEquals(write(v), "\"" + tagOf(v) + "\"")
      assertEquals(read[E]("\"" + tagOf(v) + "\""), v)
    }
    intercept[Exception](read[E]("\"no-such-tag\""))
    intercept[Exception](read[E]("0"))
  }

  test("SourceType tags") {
    roundTrip(
      SourceType.values.toSeq,
      _.tag,
      Seq("dblp", "openalex", "orcid", "crossref", "scholarchain-derived", "scholarchain-runtime")
    )
    assertEquals(
      SourceType.values.filter(_.isDataset).map(_.tag).toSeq,
      Seq("dblp", "openalex", "orcid", "crossref")
    )
  }

  test("MatchOutcome tags are the specification literals") {
    roundTrip(
      MatchOutcome.values.toSeq,
      _.tag,
      Seq("ACCEPTED", "REJECTED", "UNRESOLVED", "CONFLICT")
    )
  }

  test("EvidenceType tags") {
    roundTrip(
      EvidenceType.values.toSeq,
      _.tag,
      Seq("source-record", "coauthor-edge", "graph-path", "metric", "text-chunk", "match-decision")
    )
  }

  test("CollaborationMetric tags cover P_r, C_r, T_r, A_r, M_r, W_r separately") {
    roundTrip(
      CollaborationMetric.values.toSeq,
      _.tag,
      Seq(
        "publication-count",
        "distinct-coauthor-count",
        "total-coauthor-occurrences",
        "average-coauthors-per-paper",
        "maximum-coauthors-on-one-paper",
        "weighted-degree",
        "joint-publication-count",
        "shortest-path-length",
        "component-id",
        "component-size",
        "community-id",
        "bridge-score",
        "cross-community-degree",
        "participation-coefficient",
        "coreness"
      )
    )
  }

  test("QueryOperation tags") {
    roundTrip(
      QueryOperation.values.toSeq,
      _.tag,
      Seq(
        "resolve-researcher",
        "rank-researchers",
        "get-metrics",
        "direct-collaborators",
        "shortest-path",
        "component",
        "community",
        "bridge-researchers",
        "k-core",
        "team-size",
        "temporal-trend",
        "semantic-context",
        "verify-claims"
      )
    )
  }

  test("VerificationStatus tags") {
    roundTrip(
      VerificationStatus.values.toSeq,
      _.tag,
      Seq("supported", "weakened", "unsupported", "contradicted")
    )
  }

  test("graph node and edge classes are the specification literals") {
    roundTrip(
      NodeLabel.values.toSeq,
      _.tag,
      Seq(
        "Researcher",
        "AuthorMention",
        "Work",
        "Venue",
        "Institution",
        "Topic",
        "TextChunk",
        "DatasetSnapshot"
      )
    )
    roundTrip(
      EdgeLabel.values.toSeq,
      _.tag,
      Seq(
        "AUTHORED",
        "RESOLVES_TO",
        "COAUTHORED_WITH",
        "PUBLISHED_IN",
        "AFFILIATED_WITH",
        "HAS_TOPIC",
        "DERIVED_FROM",
        "SUPPORTED_BY"
      )
    )
    assertEquals(EdgeLabel.values.filter(_.undirected).toSeq, Seq(EdgeLabel.CoauthoredWith))
  }

  test("auxiliary enum tags") {
    roundTrip(
      MatchEntityKind.values.toSeq,
      _.tag,
      Seq("work", "researcher", "venue", "institution", "topic")
    )
    roundTrip(
      DocumentType.values.toSeq,
      _.tag,
      Seq(
        "researcher-profile",
        "work-abstract",
        "community-summary",
        "path-summary",
        "collaboration-summary"
      )
    )
    roundTrip(
      ExternalIdScheme.values.toSeq,
      _.tag,
      Seq("orcid", "dblp-pid", "openalex", "doi", "ror", "issn", "crossref-member")
    )
  }

  test("exact identifier schemes (Phase 4) and the entity kind each identifies") {
    roundTrip(
      ExactIdScheme.values.toSeq,
      _.tag,
      Seq(
        "doi",
        "arxiv",
        "dblp-key",
        "openalex-work",
        "mag",
        "scopus-eid",
        "orcid",
        "openalex-author",
        "dblp-pid",
        "s2-corpus",
        "s2-paper",
        "s2-author",
        "repository-url"
      )
    )
    assertEquals(
      ExactIdScheme.values.filter(_.entityKind == MatchEntityKind.Researcher).map(_.tag).toSeq,
      Seq("orcid", "openalex-author", "dblp-pid", "s2-author")
    )
    assert(
      ExactIdScheme.values.forall(s =>
        s.entityKind == MatchEntityKind.Work || s.entityKind == MatchEntityKind.Researcher
      )
    )
    roundTrip(AssertionSourceKind.values.toSeq, _.tag, Seq("client", "orcid"))
  }

  test("match decision v2 enums: paths, adjudication verdicts, identifier conflict kinds") {
    roundTrip(
      MatchDecisionPath.values.toSeq,
      _.tag,
      Seq("exact-id", "deterministic-bibliographic", "probabilistic", "llm-adjudicated")
    )
    roundTrip(AdjudicationVerdict.values.toSeq, _.tag, Seq("accept", "reject", "abstain"))
    assertEquals(
      AdjudicationVerdict.values.map(_.outcome).toSeq,
      Seq(MatchOutcome.Accepted, MatchOutcome.Rejected, MatchOutcome.Unresolved),
      "abstain leaves the pair UNRESOLVED; no verdict yields CONFLICT"
    )
    roundTrip(
      IdentifierConflictKind.values.toSeq,
      _.tag,
      Seq("identifier-disagreement", "agreement-contradicted")
    )
  }
}
