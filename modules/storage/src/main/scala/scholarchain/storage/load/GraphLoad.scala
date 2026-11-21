package scholarchain.storage.load

import scholarchain.core.{Canonical, TargetStore}
import scholarchain.storage.model.{
  BoundGraphWrite,
  GraphProvenance,
  LoadState,
  StoreError,
  StoredEdge,
  StoredVertex
}
import scholarchain.storage.port.{Clock, GraphWriter, KeyValueStore}

/**
 * Result of one graph load call.
 *
 * @param skipped
 *   the RunLedger already recorded this load as `loaded`: nothing was written or attempted
 * @param written
 *   graph writes of this call that created an element
 * @param alreadyPresent
 *   graph writes of this call that found the equal element (a resume or a concurrent loader)
 */
final case class GraphLoadReport(
    runId: String,
    loadId: String,
    vertices: Int,
    edges: Int,
    written: Long,
    alreadyPresent: Long,
    finalState: LoadState,
    skipped: Boolean
)

/**
 * Loads the `neptune-bulk-load` store of a verified run into a [[GraphWriter]] (SE-P7-05 B4;
 * checklist N5, L2, L3, G-ID). It runs only inside [[LoadGate.proceed]], with a [[LoadPermit]] for
 * `neptune-bulk-load`; it reads nothing but the permit's items, re-parses them with the same row
 * contracts and graph-ID checks the verifier ran ([[NeptuneRows]]), and writes through the
 * allowlisted templates only ([[BoundGraphWrite]]). IDs are copied verbatim, never minted.
 *
 * Order and idempotency: every item is parsed first (a corrupt item stops before any write); then
 * the [[LoadLedger]] protocol (publication claim before any data write, decided by
 * `partManifestSha256`; RunLedger state machine; every vertex, then every edge, create-once; a
 * `Conflict` is never an overwrite and fails the load; a loaded load is skipped with zero writes).
 *
 * Every element keeps its provenance: run ID, load ID, source path, record schema version (L3).
 */
object GraphLoad {

  /** CR-P7-11 M1: the physical stores this loader writes (the graph only; plus the RunLedger). */
  val WrittenStores: Vector[TargetStore] = Vector(TargetStore.NeptuneBulkLoad)

  def load(
      permit: LoadPermit,
      graph: GraphWriter,
      ledger: KeyValueStore,
      clock: Clock,
      progress: LoadProgress = new LoadProgress
  ): Either[StoreError, GraphLoadReport] =
    if (permit == null || graph == null || ledger == null || clock == null || progress == null)
      Left(StoreError.Refused("graph load: permit, graph, ledger and clock are required"))
    else if (permit.target != TargetStore.NeptuneBulkLoad)
      Left(StoreError.Refused(s"graph load: the permit is for ${permit.target.tag}"))
    else
      for {
        g <- elements(permit)
        (vertices, edges) = g
        writes = vertices.map(Left(_)) ++ edges.map(Right(_))
        run <- LoadLedger.run(permit, ledger, clock, writes, WrittenStores, progress)(
          probe(graph)
        ) { e =>
          e.fold(BoundGraphWrite.vertex, BoundGraphWrite.edge).flatMap(graph.write)
        }
      } yield GraphLoadReport(
        permit.runId,
        permit.load.loadId,
        vertices.size,
        edges.size,
        run.written,
        run.alreadyPresent,
        run.finalState,
        run.skipped
      )

  /**
   * CR-P7-09 M1: absent or equal is fine; a different stored element under the ID (another run's,
   * or other properties or provenance) is a `Conflict` found before any write.
   */
  private def probe(
      graph: GraphWriter
  )(e: Either[StoredVertex, StoredEdge]): Either[StoreError, Unit] = {
    def same[A](id: String, what: String, mine: A, stored: Option[A]): Either[StoreError, Unit] =
      stored match {
        case Some(s) if s != mine =>
          Left(StoreError.Conflict(s"$what $id: holds a different element (pre-write probe)"))
        case _ => Right(())
      }
    e match {
      case Left(v) => graph.storedVertex(v.id).flatMap(same(v.id, "vertex", v, _))
      case Right(x) => graph.storedEdge(x.id).flatMap(same(x.id, "edge", x, _))
    }
  }

  /** The vertices and edges of the permit's items, with provenance, sorted by ID. */
  def elements(permit: LoadPermit): Either[StoreError, (Vector[StoredVertex], Vector[StoredEdge])] =
    NeptuneRows.parse(permit.load, permit.runId, permit.readItem) match {
      case Left(ps) =>
        Left(
          StoreError.Corrupt(
            s"graph load: ${ps.size} row problems, first ${ps.head.subject}: ${ps.head.reason}"
          )
        )
      case Right(g) =>
        val ids = NeptuneRows.graphIds(g)
        if (ids.nonEmpty)
          Left(
            StoreError.Corrupt(
              s"graph load: ${ids.size} graph-id problems, first ${ids.head.subject}: ${ids.head.reason}"
            )
          )
        else {
          val schemaOf = permit.items.map(i => i.path -> i.recordSchemaVersion).toMap
          def prov(path: String) =
            GraphProvenance(permit.runId, permit.load.loadId, path, schemaOf(path))
          def sorted[A](xs: Vector[A])(id: A => String) =
            xs.sortWith((a, b) => Canonical.compareUtf8(id(a), id(b)) < 0)
          Right(
            (
              sorted(g.vertices.map(v => StoredVertex(v.id, v.label, v.properties, prov(v.path))))(
                _.id
              ),
              sorted(
                g.edges.map(e =>
                  StoredEdge(e.id, e.label, e.fromId, e.toId, e.properties, prov(e.path))
                )
              )(_.id)
            )
          )
        }
    }
}
