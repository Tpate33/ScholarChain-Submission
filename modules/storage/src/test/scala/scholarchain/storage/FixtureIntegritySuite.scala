package scholarchain.storage

import java.nio.file.Path

import scholarchain.core.{Canonical, Json, PartManifests, StoreLoadManifest, TargetStore}
import scholarchain.storage.model.SourceBounds
import Fixtures.*

/**
 * The fixtures are exact copies of the archived Phase 6 outputs (checklist F1): every file matches
 * its SHA256SUMS.txt entry, there are 60 and 1 entries, and no other file is present.
 */
class FixtureIntegritySuite extends munit.FunSuite {

  private val Line = "^([0-9a-f]{64})  (\\S+)$".r

  private def sums(root: Path): Vector[(String, String)] =
    text(root.resolve("SHA256SUMS.txt")).split("\n").toVector.map {
      case Line(sha, path) => path -> sha
      case other => fail(s"malformed SHA256SUMS.txt line: $other")
    }

  private def checkTree(root: Path, expectedEntries: Int): Unit = {
    val entries = sums(root)
    assertEquals(entries.size, expectedEntries)
    assertEquals(entries.map(_._1).distinct.size, expectedEntries, "no path listed twice")
    entries.foreach { case (path, sha) =>
      assertEquals(Canonical.sha256Hex(bytes(root.resolve(path))), sha, path)
    }
    val bounds = SourceBounds.of(200, 16L * 1024 * 1024, 3).toOption.get
    val listed = source(root, bounds).listFiles()
    assertEquals(
      listed.map(_.toSet),
      Right(entries.map(_._1).toSet + "SHA256SUMS.txt"),
      "no file other than the listed ones and SHA256SUMS.txt, and no symbolic link"
    )
  }

  test("run-1 fixture: 60 entries, every file matches its SHA-256, nothing else") {
    checkTree(run1Root, 60)
  }

  test("run-2 fixture: 1 entry (_parts.json), matching its SHA-256, nothing else") {
    checkTree(run2Root, 1)
  }

  test("the two _parts.json are different publications of the same run ID (risk R1)") {
    val p1 = bytes(runDir(run1Root).resolve("_parts.json"))
    val p2 = bytes(runDir(run2Root).resolve("_parts.json"))
    assertEquals(Canonical.sha256Hex(p1), PartsSha256)
    assertEquals(Canonical.sha256Hex(p2), Run2PartsSha256)
    val ids = Vector(p1, p2).map(b =>
      PartManifests.decode(new String(b, "UTF-8")).map(PartManifests.asV2(_).runId)
    )
    assertEquals(ids, Vector(Right(RunId), Right(RunId)))
  }

  test("four load manifests with 24, 21, 29 and 4 items, all bound to the run-1 _parts.json") {
    val counts = TargetStore.values.toVector.map { t =>
      val m = Json
        .decode[StoreLoadManifest](
          text(loadDir(run1Root).resolve(s"${t.tag}.json")).stripSuffix("\n")
        )
        .fold(e => fail(s"${t.tag}: $e"), identity)
      assertEquals(m.partManifestSha256, PartsSha256, t.tag)
      assertEquals(m.sourceRunId, RunId, t.tag)
      t -> m.items.size
    }.toMap
    assertEquals(
      counts,
      Map(
        TargetStore.NeptuneBulkLoad -> 24,
        TargetStore.DynamoDb -> 21,
        TargetStore.S3 -> 29,
        TargetStore.S3Vectors -> 4
      )
    )
  }
}
