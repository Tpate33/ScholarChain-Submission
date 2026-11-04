package scholarchain.core

import scala.collection.immutable.SortedMap

import Fixtures.*

/** C1: CandidateBlock and MatchCandidate (README Phase 4 jobs 4 and 5). */
class CandidateContractSuite extends munit.FunSuite {

  private def invalidAt(errs: Vector[ValidationError], field: String): Boolean =
    errs.exists {
      case ValidationError.InvalidValue(f, _) => f == field
      case ValidationError.MissingField(f) => f == field
      case ValidationError.MalformedId(f, _, _, _) => f == field
      case _ => false
    }

  private def blockErrs(b: CandidateBlock) = CandidateBlock.contract.validate(b)
  private def candErrs(c: MatchCandidate) = MatchCandidate.contract.validate(c)

  // ------------------------------------------------------------------ CandidateBlock

  test("block builder sorts members and sources and derives size and ID") {
    assertEquals(
      block.memberIds,
      Vector(mention1.mentionId, mention0.mentionId, openAlexProfileId),
      "UTF-8 byte order"
    )
    assertEquals(block.sourceDatasets, Vector(SourceType.Dblp, SourceType.OpenAlex))
    assertEquals(block.size, 3)
    assertEquals(
      Right(block.blockId),
      CandidateBlockId.of(MatchEntityKind.Researcher, blockingVersion, 0, "example|a", runId)
    )
    val reordered = CandidateBlock.build(
      MatchEntityKind.Researcher,
      blockingVersion,
      0,
      "example|a",
      5,
      block.memberIds.reverse,
      Vector(SourceType.Dblp, SourceType.OpenAlex, SourceType.Dblp),
      block.provenance
    )
    assertEquals(reordered, Right(block))
  }

  test("a block is bounded: size must equal the members and not exceed maxBlockSize") {
    assert(invalidAt(blockErrs(block.copy(maxBlockSize = 2)), "size"))
    assert(invalidAt(blockErrs(block.copy(maxBlockSize = 1)), "maxBlockSize"))
    assert(invalidAt(blockErrs(block.copy(size = 2)), "size"))
    assert(invalidAt(blockErrs(block.copy(memberIds = Vector.empty, size = 0)), "memberIds"))
  }

  test("block members must be sorted, distinct, and of the block's entity kind") {
    assert(invalidAt(blockErrs(block.copy(memberIds = block.memberIds.reverse)), "memberIds"))
    val dup = Vector(mention1.mentionId, mention1.mentionId, openAlexProfileId)
    assert(invalidAt(blockErrs(block.copy(memberIds = dup)), "memberIds"))
    val workMember = Vector(mention1.mentionId, mention0.mentionId, workId).sortWith(
      Canonical.compareUtf8(_, _) < 0
    )
    assert(
      blockErrs(block.copy(memberIds = workMember))
        .exists(_.isInstanceOf[ValidationError.MalformedId])
    )
    val workBlock = CandidateBlock.build(
      MatchEntityKind.Work,
      blockingVersion,
      0,
      "synthetic study|2021",
      5,
      Vector("work:openalex:W1", workId),
      Vector(SourceType.Dblp, SourceType.OpenAlex),
      candidateProv("synthetic study|2021")
    )
    assert(workBlock.isRight, workBlock.toString)
  }

  test("block sources must be datasets and cover every source a member ID names") {
    assert(
      invalidAt(blockErrs(block.copy(sourceDatasets = Vector(SourceType.Dblp))), "sourceDatasets")
    )
    assert(
      invalidAt(
        blockErrs(block.copy(sourceDatasets = Vector(SourceType.Derived, SourceType.OpenAlex))),
        "sourceDatasets[0]"
      )
    )
    assert(
      invalidAt(
        blockErrs(block.copy(sourceDatasets = Vector(SourceType.OpenAlex, SourceType.Dblp))),
        "sourceDatasets"
      ),
      "sorted by tag"
    )
  }

  test("block entity kind, key, level, version, provenance and ID are checked") {
    assert(invalidAt(blockErrs(block.copy(entityKind = MatchEntityKind.Venue)), "entityKind"))
    assert(invalidAt(blockErrs(block.copy(blockKey = "a\tb")), "blockKey"))
    assert(invalidAt(blockErrs(block.copy(level = -1)), "level"))
    assert(invalidAt(blockErrs(block.copy(blockingVersion = "v 1")), "blockingVersion"))
    assert(invalidAt(blockErrs(block.copy(level = 1)), "blockId"), "ID binds the level")
    assert(
      invalidAt(blockErrs(block.copy(provenance = dblpProv)), "provenance.sourceDataset"),
      "blocks are derived records"
    )
    assert(
      blockErrs(block.copy(provenance = null))
        .exists(_.isInstanceOf[ValidationError.MissingProvenance])
    )
  }

  // ------------------------------------------------------------------ MatchCandidate

  test("candidate builder canonicalizes the pair; each endpoint keeps its own dataset") {
    val swapped = MatchCandidate.build(
      MatchEntityKind.Researcher,
      mention0.mentionId,
      SourceType.Dblp,
      openAlexProfileId,
      SourceType.OpenAlex,
      Vector(BlockKey(0, "example|a"), BlockKey(1, "example|ada"), BlockKey(0, "example|a")),
      blockingVersion,
      featureVersion,
      SortedMap("nameSimilarity" -> 0.92, "orcidEqual" -> 1.0),
      Vector(ExactId(ExactIdScheme.Orcid, orcidA), ExactId(ExactIdScheme.Orcid, orcidA)),
      matchCandidate.provenance
    )
    assertEquals(swapped, Right(matchCandidate))
    assertEquals(
      (matchCandidate.sourceIdA, matchCandidate.sourceDatasetA),
      (mention0.mentionId, SourceType.Dblp)
    )
    assertEquals(
      (matchCandidate.sourceIdB, matchCandidate.sourceDatasetB),
      (openAlexProfileId, SourceType.OpenAlex)
    )
    assertEquals(
      matchCandidate.blockKeys,
      Vector(BlockKey(0, "example|a"), BlockKey(1, "example|ada"))
    )
  }

  test("a match candidate carries no score, threshold, confidence, or decision") {
    val keys = ujson.read(Json.write(matchCandidate)).obj.keys.toVector
    assertEquals(
      keys,
      Vector(
        "schemaVersion",
        "candidateId",
        "entityKind",
        "sourceIdA",
        "sourceIdB",
        "sourceDatasetA",
        "sourceDatasetB",
        "blockKeys",
        "blockingVersion",
        "featureVersion",
        "features",
        "exactIdAgreements",
        "provenance"
      )
    )
    val withDecision = Json.write(matchCandidate).replaceFirst("\\{", "{\"decision\":\"ACCEPTED\",")
    assert(Json.decode[MatchCandidate](withDecision).isLeft, "unknown keys are rejected")
  }

  test("candidate pairs must be canonical, distinct, and of the candidate's entity kind") {
    val reversed = matchCandidate.copy(
      sourceIdA = matchCandidate.sourceIdB,
      sourceIdB = matchCandidate.sourceIdA,
      sourceDatasetA = matchCandidate.sourceDatasetB,
      sourceDatasetB = matchCandidate.sourceDatasetA
    )
    assert(candErrs(reversed).exists(_.isInstanceOf[ValidationError.NonCanonicalPair]))
    assert(
      candErrs(matchCandidate.copy(sourceIdB = matchCandidate.sourceIdA))
        .exists(_.isInstanceOf[ValidationError.SelfPair])
    )
    assert(candErrs(matchCandidate.copy(entityKind = MatchEntityKind.Work)).nonEmpty)
    assert(
      invalidAt(candErrs(matchCandidate.copy(entityKind = MatchEntityKind.Topic)), "entityKind")
    )
    assert(
      MatchCandidate
        .build(
          MatchEntityKind.Researcher,
          mention0.mentionId,
          SourceType.Dblp,
          mention0.mentionId,
          SourceType.Dblp,
          matchCandidate.blockKeys,
          blockingVersion,
          featureVersion,
          SortedMap.empty,
          Vector.empty,
          matchCandidate.provenance
        )
        .isLeft
    )
  }

  test("endpoint datasets must agree with the source an endpoint ID names") {
    assert(
      invalidAt(candErrs(matchCandidate.copy(sourceDatasetB = SourceType.Dblp)), "sourceDatasetB")
    )
    assert(
      invalidAt(
        candErrs(matchCandidate.copy(sourceDatasetA = SourceType.Derived)),
        "sourceDatasetA"
      )
    )
    // a mention ID names no source: any dataset is accepted for it
    assertEquals(candErrs(matchCandidate.copy(sourceDatasetA = SourceType.Crossref)), Vector.empty)
  }

  test("block keys are required, sorted by (level, key), and valid") {
    assert(invalidAt(candErrs(matchCandidate.copy(blockKeys = Vector.empty)), "blockKeys"))
    assert(
      invalidAt(
        candErrs(matchCandidate.copy(blockKeys = matchCandidate.blockKeys.reverse)),
        "blockKeys"
      )
    )
    assert(
      invalidAt(
        candErrs(matchCandidate.copy(blockKeys = Vector(BlockKey(-1, "x")))),
        "blockKeys[0].level"
      )
    )
    assert(
      invalidAt(
        candErrs(matchCandidate.copy(blockKeys = Vector(BlockKey(0, "")))),
        "blockKeys[0].key"
      )
    )
  }

  test("features are token-named and finite; versions are tokens; ID binds them") {
    assert(
      invalidAt(
        candErrs(matchCandidate.copy(features = SortedMap("x" -> Double.NaN))),
        "features.x"
      )
    )
    assert(
      invalidAt(
        candErrs(matchCandidate.copy(features = SortedMap("x" -> Double.PositiveInfinity))),
        "features.x"
      )
    )
    assert(
      invalidAt(candErrs(matchCandidate.copy(features = SortedMap("bad key" -> 1.0))), "features")
    )
    assert(invalidAt(candErrs(matchCandidate.copy(featureVersion = "")), "featureVersion"))
    assert(
      invalidAt(candErrs(matchCandidate.copy(featureVersion = "er-features-2.0.0")), "candidateId")
    )
    assert(
      invalidAt(candErrs(matchCandidate.copy(blockingVersion = "er-keys-2.0.0")), "candidateId")
    )
    assert(
      invalidAt(candErrs(matchCandidate.copy(provenance = dblpProv)), "provenance.sourceDataset")
    )
  }

  test("features are written in sorted key order") {
    val json = Json.write(matchCandidate)
    assert(json.contains("\"features\":{\"nameSimilarity\":0.92,\"orcidEqual\":1}"), json)
  }

  test("exact identifier agreements: valid, of the candidate's kind, sorted and distinct") {
    def withIds(ids: Vector[ExactId]) = candErrs(matchCandidate.copy(exactIdAgreements = ids))
    assert(
      invalidAt(withIds(Vector(ExactId(ExactIdScheme.Doi, "10.1234/x"))), "exactIdAgreements[0]"),
      "a DOI does not identify a researcher"
    )
    assert(
      withIds(Vector(ExactId(ExactIdScheme.Orcid, "0000-0000-0000-0002")))
        .exists(_.isInstanceOf[ValidationError.MalformedId])
    )
    val two =
      Vector(ExactId(ExactIdScheme.OpenAlexAuthor, "A1"), ExactId(ExactIdScheme.Orcid, orcidA))
    assertEquals(withIds(two), Vector.empty)
    assert(invalidAt(withIds(two.reverse), "exactIdAgreements"))
    assert(invalidAt(withIds(Vector(two(0), two(0))), "exactIdAgreements"))
  }

  test("work candidates use work IDs whose namespace matches their dataset") {
    val c = MatchCandidate.build(
      MatchEntityKind.Work,
      "work:openalex:W1",
      SourceType.OpenAlex,
      workId,
      SourceType.Dblp,
      Vector(BlockKey(0, "synthetic study|2021")),
      blockingVersion,
      featureVersion,
      SortedMap("titleSimilarity" -> 1.0, "yearDelta" -> 0.0),
      Vector(ExactId(ExactIdScheme.Doi, "10.1234/example.2021.001")),
      candidateProv(workId)
    )
    assert(c.isRight, c.toString)
    assertEquals(c.map(x => (x.sourceIdA, x.sourceDatasetA)), Right((workId, SourceType.Dblp)))
    assert(candErrs(ok(c).copy(sourceDatasetA = SourceType.Crossref)).nonEmpty)
    assert(candErrs(ok(c).copy(sourceIdB = mention0.mentionId)).nonEmpty, "mentions are not works")
  }
}
