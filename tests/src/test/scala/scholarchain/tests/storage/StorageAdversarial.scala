package scholarchain.tests.storage

import scholarchain.core.TargetStore
import scholarchain.storage.OpeningFixture
import scholarchain.storage.load.*
import scholarchain.storage.model.{Bytes, StoreError, TableRole}
import scholarchain.storage.port.RunSource

/**
 * TE-P7-01 (Phase 7 WP8, checklist T1): shared helpers of the cross-module adversarial storage
 * suites. Every suite drives the PUBLIC storage load path ([[OpeningLoad.run]]) and the runtime
 * view ([[scholarchain.storage.port.RuntimeStores]]) over the storage test fixtures (or temporary
 * copies of them, made by the storage fixture helpers; the fixture files are never modified), with
 * the in-memory stores of [[OpeningFixture.World]] whose recording wrappers count every write call.
 */
object StorageAdversarial extends munit.Assertions {

  val Stores: Vector[TargetStore] = TargetStore.values.toVector

  /** One line per store: status, counts, checks and the first rejections (no record content). */
  def show(r: OpeningLoadReport): String =
    r.stores
      .map { s =>
        s"${s.target.tag}: ${s.status.tag} written=${s.written} present=${s.alreadyPresent} " +
          s"conflicts=${s.conflicts} open=${s.open.mkString(",")}" +
          s.error.map(e => s" error=$e").getOrElse("") +
          (if (s.rejections.isEmpty) ""
           else s" rejections=${s.rejections.take(3).map(x => s"${x.check.tag}:${x.subject}")}")
      }
      .mkString("\n")

  /** The distinct rejection check tags of a report, in report order. */
  def tags(r: OpeningLoadReport): Vector[String] = r.rejections.map(_.check.tag).distinct

  /**
   * Every store refused, nothing written to any store (ledger included), no embedding, no runtime
   * view, and every rejection of the report has one of `allowed` checks with at least one of
   * `required` present.
   */
  def assertRefusedEverywhere(
      res: OpeningLoadResult,
      w: OpeningFixture.World,
      required: VerificationCheck,
      allowed: Set[VerificationCheck]
  )(using munit.Location): Unit = {
    val r = res.report
    assert(r.rejections.exists(_.check == required), s"expected ${required.tag}:\n${show(r)}")
    assert(
      r.rejections.forall(x => allowed.contains(x.check)),
      s"unexpected checks ${tags(r)} (allowed ${allowed.map(_.tag)}):\n${show(r)}"
    )
    Stores.foreach { t =>
      val s = r.store(t)
      assertEquals(s.status, StoreLoadStatus.Refused, s"${t.tag}\n${show(r)}")
      assertEquals((s.written, s.alreadyPresent), (0L, 0L), t.tag)
      assertEquals(s.rejections, r.rejections, s"${t.tag}: a rejection anywhere refuses it")
    }
    assertNoWrites(w, "refused load")
    assertEquals(res.runtime, None, "no runtime view of a refused load (CR-P7-10 R5)")
  }

  /** No write call on any store of `w` (ledger included), no element anywhere, no embedding. */
  def assertNoWrites(w: OpeningFixture.World, what: String)(using munit.Location): Unit = {
    assertEquals(w.writeCalls, 0, s"$what: no write call on any store, ledger included")
    assertEquals(
      (w.graph.vertexIds.size, w.graph.edgeIds.size, w.objectStore.size, w.vectorCount),
      (0, 0, 0, 0),
      what
    )
    assertEquals(w.kvStore.itemCount(TableRole.ResearcherMetricCache), 0, what)
    assertEquals(w.kvStore.itemCount(TableRole.ArtifactRegistry), 0, what)
    assertEquals(w.ledgerStore.itemCount(TableRole.RunLedger), 0, what)
    assertEquals(w.embedder.calls, 0, s"$what: the embedder is never called")
  }

  /**
   * Every write counter of `w` as one tuple: (graph written, key-value written, ledger written,
   * object written, vector written, vector batches).
   */
  def written(w: OpeningFixture.World): (Int, Int, Int, Int, Int, Int) =
    (
      w.graph.writer.counts.written,
      w.kv.counts.written,
      w.ledger.counts.written,
      w.objects.counts.written,
      w.vectors.counts.written,
      w.vectors.batches
    )

  /**
   * A run source that serves the underlying bytes, except that a read of `path` for which
   * `tamper(nth read of path)` holds returns `change(bytes)` instead: a file replaced on disk after
   * the verifier read it. A bounded read of changed bytes longer than its bound is refused, as a
   * real source would.
   */
  final class TamperingSource(
      u: RunSource,
      path: String,
      tamper: Int => Boolean,
      change: Array[Byte] => Array[Byte]
  ) extends RunSource {
    private var reads = 0
    var tampered: Int = 0
    def listFiles() = u.listFiles()
    private def serve(p: String, b: Either[StoreError, Bytes], bound: Option[Long]) =
      if (p != path) b
      else {
        reads += 1
        if (!tamper(reads)) b
        else
          b.flatMap { ok =>
            tampered += 1
            val changed = change(ok.toArray)
            if (bound.exists(changed.length.toLong > _))
              Left(StoreError.Refused(s"$p: larger than $bound bytes"))
            else Right(Bytes.of(changed))
          }
      }
    def read(p: String) = serve(p, u.read(p), None)
    def readAtMost(p: String, n: Long) = serve(p, u.readAtMost(p, n), Some(n))
  }

  /** `b` with one bit of its middle byte flipped (same length). */
  def flipMiddle(b: Array[Byte]): Array[Byte] = {
    val c = b.clone()
    c(c.length / 2) = (c(c.length / 2) ^ 0x01).toByte
    c
  }
}
