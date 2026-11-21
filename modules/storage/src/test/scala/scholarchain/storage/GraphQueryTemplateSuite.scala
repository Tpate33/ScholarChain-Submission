package scholarchain.storage

import scholarchain.core.CanonicalGraph
import scholarchain.storage.model.*
import scholarchain.storage.model.GraphParamValue.{IntValue, Str}
import scholarchain.storage.port.GraphReader
import TestData.*

/** Query-template allowlist test (SE-P7-01 law 3; README L1377). */
class GraphQueryTemplateSuite extends munit.FunSuite {
  private val maxLimit = 50
  private val WriteWords =
    Vector(
      "CREATE",
      "MERGE",
      "DELETE",
      "DETACH",
      "SET",
      "REMOVE",
      "DROP",
      "LOAD",
      "CALL",
      "FOREACH"
    )
  private val Param = "\\$([A-Za-z][A-Za-z0-9]*)".r

  test("every template is read-only: no write or procedure keyword as a word") {
    GraphQuery.values.foreach { q =>
      val words = q.text.toUpperCase.split("[^A-Z_]+").toSet
      WriteWords.foreach(w => assert(!words.contains(w), s"$q contains $w"))
      assert(q.text.startsWith("MATCH "), q.toString)
      assert(q.text.contains(" RETURN "), q.toString)
    }
  }

  test("templates carry no literal and no interpolation: every value is a declared $parameter") {
    GraphQuery.values.foreach { q =>
      assert(!q.text.exists(c => c == '\'' || c == '"' || c == '`'), s"$q has a quote")
      assert(!q.text.contains("${"), q.toString)
      assert(!q.text.exists(_.isDigit), s"$q has a numeric literal")
      val used = Param.findAllMatchIn(q.text).map(_.group(1)).toSet
      assertEquals(used, q.params.map(_.name).toSet, q.toString)
      assertEquals(q.params.map(_.name).distinct.size, q.params.size, q.toString)
    }
  }

  test("templates use only labels and relationship types of the implemented graph model") {
    val labels = CanonicalGraph.NodeLabels.map(_.tag).toSet
    val edges = CanonicalGraph.EdgeLabels.map(_.tag).toSet
    val Label = ":([A-Za-z_]+)\\)".r
    val Rel = "\\[[a-z]*:([A-Z_]+)\\]".r
    GraphQuery.values.foreach { q =>
      Label
        .findAllMatchIn(q.text)
        .map(_.group(1))
        .foreach(l => assert(labels.contains(l), s"$q: $l"))
      Rel.findAllMatchIn(q.text).map(_.group(1)).foreach(r => assert(edges.contains(r), s"$q: $r"))
    }
  }

  test("binding requires exactly the declared, typed parameters") {
    val ok =
      BoundQuery.bind(GraphQuery.ResearcherById, Map("researcherId" -> Str(researcherA)), maxLimit)
    assertEquals(ok.map(_.parameters.keySet.toSet), Right(Set("researcherId")).withLeft[StoreError])
    assert(BoundQuery.bind(GraphQuery.ResearcherById, Map.empty, maxLimit).isLeft, "missing")
    assert(
      BoundQuery
        .bind(
          GraphQuery.ResearcherById,
          Map("researcherId" -> Str(researcherA), "extra" -> Str("x")),
          maxLimit
        )
        .isLeft,
      "extra"
    )
    assert(
      BoundQuery
        .bind(GraphQuery.ResearcherById, Map("researcherId" -> IntValue(1)), maxLimit)
        .isLeft,
      "type"
    )
    val collab = Map(
      "researcherId" -> Str(researcherA),
      "windowStartYear" -> IntValue(2015),
      "windowEndYear" -> IntValue(2020),
      "limit" -> IntValue(10)
    )
    assert(BoundQuery.bind(GraphQuery.DirectCollaborators, collab, maxLimit).isRight)
    assert(
      BoundQuery
        .bind(GraphQuery.DirectCollaborators, collab.updated("limit", IntValue(0)), maxLimit)
        .isLeft
    )
    assert(
      BoundQuery
        .bind(GraphQuery.DirectCollaborators, collab.updated("limit", IntValue(51)), maxLimit)
        .isLeft
    )
    assert(
      BoundQuery
        .bind(
          GraphQuery.DirectCollaborators,
          collab.updated("windowStartYear", IntValue(99999)),
          maxLimit
        )
        .isLeft
    )
    assert(
      BoundQuery.bind(GraphQuery.WorkVenue, Map("workId" -> Str(researcherA)), maxLimit).isLeft,
      "kind"
    )
  }

  test(
    "an injection string stays a parameter value and is refused as an ID; the text is unchanged"
  ) {
    val attacks = Vector(
      "researcher:orcid:0000-0002-1825-0097' OR 1=1 //",
      "x}) DETACH DELETE r //",
      "researcher:sc:v1:" + hex("a") + " SET r.x = 1",
      "$researcherId"
    )
    attacks.foreach { a =>
      val r = BoundQuery.bind(GraphQuery.ResearcherById, Map("researcherId" -> Str(a)), maxLimit)
      assert(r.isLeft, a)
    }
    val before = GraphQuery.values.map(_.text).toVector
    BoundQuery.bind(GraphQuery.ResearcherById, Map("researcherId" -> Str(researcherA)), maxLimit)
    assertEquals(GraphQuery.values.map(_.text).toVector, before)
  }

  test(
    "query text cannot be supplied: BoundQuery has no public constructor and the port takes only it"
  ) {
    assert(
      compileErrors(
        "BoundQuery(GraphQuery.ResearcherById, scala.collection.immutable.SortedMap.empty)"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "new BoundQuery(GraphQuery.ResearcherById, scala.collection.immutable.SortedMap.empty)"
      ).nonEmpty
    )
    assert(compileErrors("(??? : GraphReader).run(\"MATCH (n) RETURN n\")").nonEmpty)
    val runs = classOf[GraphReader].getMethods.filter(_.getName == "run")
    assertEquals(
      runs.map(_.getParameterTypes.toVector).toVector,
      Vector(Vector[Class[?]](classOf[BoundQuery]))
    )
  }
}
