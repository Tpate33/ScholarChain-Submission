package scholarchain.core

/** Every graph node and edge class from the specification has an ID kind and endpoint rule. */
class GraphModelCoverageSuite extends munit.FunSuite {

  test("every node class maps to a stable ID kind") {
    assertEquals(GraphNode.IdKindOf.keySet, NodeLabel.values.toSet)
  }

  test("every edge class has endpoint rules") {
    assertEquals(GraphEdge.Endpoints.keySet, EdgeLabel.values.toSet)
  }

  test("COAUTHORED_WITH connects researchers only and requires the specification properties") {
    assertEquals(
      GraphEdge.Endpoints(EdgeLabel.CoauthoredWith),
      (Set(IdKind.Researcher), Set(IdKind.Researcher))
    )
    assertEquals(
      GraphEdge.CoauthoredWithProperties.toSet,
      Set("jointPublicationCount", "firstCollaborationYear", "lastCollaborationYear")
    )
    val missing =
      Fixtures.graphEdge.copy(properties = Fixtures.graphEdge.properties - "jointPublicationCount")
    assert(
      GraphEdge.contract
        .validate(missing)
        .contains(ValidationError.MissingField("properties.jointPublicationCount"))
    )
  }
}
