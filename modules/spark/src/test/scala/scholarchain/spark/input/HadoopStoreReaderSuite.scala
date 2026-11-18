package scholarchain.spark.input

import java.nio.file.{Files, Path}

import org.apache.hadoop.conf.Configuration

import scholarchain.core.*
import scholarchain.spark.store.{HadoopRunStore, NioRunStore, RunStore, S3SimFileSystem, StorePath}
import scholarchain.spark.testkit.{LocalSpark, RunFixtures}
import scholarchain.spark.testkit.RunFixtures.Written

/**
 * The verified reader through the Hadoop FileSystem store (SP-P6-04): the same synthetic chain as
 * [[VerifiedArchiveReaderSuite]], read through `file:` URIs (Hadoop's local file system, raw, with
 * java.nio NOFOLLOW stats) and through `s3sim` (a non-local Hadoop FileSystem with S3A semantics:
 * listings and stats from Hadoop `FileStatus`), must give exactly the input the java.nio reader
 * gives; the refusals that matter for an object store or a cluster file system (a link, a dangling
 * link that a Hadoop local listing would drop, a changed part, a missing `_SUCCESS`, an unlistable
 * location) refuse in the same check. [[ArchiveInput]]'s location rules are checked too.
 */
class HadoopStoreReaderSuite extends munit.FunSuite {

  private val limits =
    ReaderLimits(
      maxTreeEntries = 1000,
      maxTreeDepth = 6,
      maxDriverValidationRows = 10000,
      maxDriverValidationBytes = 1L << 20,
      maxMetadataBytes = 1L << 16
    )

  private val tmp = FunFixture[Path](
    _ => Files.createTempDirectory("scholarchain-hadoop-reader-"),
    p => LocalSpark.deleteTree(p)
  )

  private lazy val conf: Configuration = {
    val c = new Configuration()
    S3SimFileSystem.register(c)
    c
  }
  private lazy val hadoop: RunStore = new HadoopRunStore(conf)

  private def fileUri(p: Path): String = "file://" + p.toAbsolutePath.toString
  private def s3Uri(p: Path): String = S3SimFileSystem.locationOf(p).toString

  private def input(w: Written, uri: Path => String, store: RunStore = hadoop): ArchiveInput =
    SyntheticCuratedPin
      .archiveInput(
        store,
        uri(w.runs.archiveRoot),
        uri(w.runs.curatedRun),
        uri(w.runs.decisionRun),
        uri(w.runs.groupingRun),
        w.runs.pinnedCuratedRunId
      )
      .fold(e => fail(e.mkString("\n")), identity)

  private def read(w: Written, uri: Path => String = fileUri) =
    VerifiedArchiveReader.read(input(w, uri), limits)

  private def steps(w: Written, uri: Path => String = fileUri): Set[CheckStep] =
    read(w, uri).swap
      .getOrElse(fail("expected a refusal, the chain was accepted"))
      .map(_.step)
      .toSet

  /** The parts of a verified input as (relative path below the archive root) strings. */
  private def relative(v: VerifiedInput, root: StorePath): Vector[String] =
    (v.teamParts ++ v.workParts ++ v.curatedPartsOf("works"))
      .map(p => p.relativeTo(root).getOrElse(fail(s"$p is not below $root")))

  tmp.test("file: URIs and s3sim through Hadoop read exactly what the java.nio reader reads") {
    root =>
      val w = RunFixtures.write(root)
      val nio =
        VerifiedArchiveReader.read(w.runs, limits).fold(r => fail(r.mkString("\n")), identity)
      Vector[Path => String](fileUri, s3Uri).foreach { uri =>
        val in = input(w, uri)
        val v = VerifiedArchiveReader.read(in, limits).fold(r => fail(r.mkString("\n")), identity)
        assertEquals(
          (v.curated.runId, v.decision.runId, v.grouping.runId),
          (nio.curated.runId, nio.decision.runId, nio.grouping.runId)
        )
        assertEquals(v.graphInput, nio.graphInput)
        assertEquals(v.decisions, nio.decisions)
        assertEquals(v.authorships, nio.authorships)
        assertEquals(v.curatedSources, nio.curatedSources)
        assertEquals(
          HadoopCuratedRun.ConsumedLeaves.map(v.curatedRowsOf),
          HadoopCuratedRun.ConsumedLeaves.map(nio.curatedRowsOf)
        )
        assertEquals(v.grouping.partManifest, nio.grouping.partManifest)
        assertEquals(
          relative(v, in.archiveRoot),
          relative(nio, StorePath.local(w.runs.archiveRoot))
        )
      }
      // The URIs Spark is given: s3sim parts keep the scheme and the bucket.
      val s3 = VerifiedArchiveReader.read(input(w, s3Uri), limits).toOption.get
      assert(
        s3.teamParts.forall(_.toUri.toString.startsWith(s"s3sim://${S3SimFileSystem.Bucket}/"))
      )
  }

  tmp.test("a symbolic link in the grouping run is refused through Hadoop (NOFOLLOW)") { root =>
    val w = RunFixtures.write(root)
    Files.createSymbolicLink(
      w.runs.groupingRun.resolve("teams/link.jsonl"),
      w.runs.groupingRun.resolve("teams/part-00000.jsonl")
    )
    val rs = read(w).swap.getOrElse(fail("accepted"))
    assert(rs.exists(r => r.step == CheckStep.Tree && r.message.contains("is a symbolic link")), rs)
  }

  tmp.test("a dangling link (dropped by a Hadoop local listing) is still seen and refused") {
    root =>
      val w = RunFixtures.write(root)
      Files.createSymbolicLink(w.runs.decisionRun.resolve("dangling"), root.resolve("nowhere"))
      val rs = read(w).swap.getOrElse(fail("accepted"))
      assert(
        rs.exists(r =>
          r.step == CheckStep.Tree && r.run == "decision" && r.message.contains("dangling")
        ),
        rs
      )
  }

  tmp.test("a changed byte of a grouping part is refused by the part manifest (s3sim)") { root =>
    val w = RunFixtures.write(root)
    val part = w.runs.groupingRun.resolve("teams/part-00000.jsonl")
    val b = Files.readAllBytes(part)
    b(0) = (if (b(0) == '{'.toByte) '['.toByte else '{'.toByte)
    Files.write(part, b)
    assertEquals(steps(w, s3Uri), Set(CheckStep.PartManifest))
  }

  tmp.test("a decision run without _SUCCESS is refused (s3sim)") { root =>
    val w = RunFixtures.write(root)
    Files.delete(w.runs.decisionRun.resolve(PartManifest.SuccessMarker))
    assertEquals(steps(w, s3Uri), Set(CheckStep.SuccessMarker))
  }

  tmp.test("a missing run location is refused (s3sim: nothing under the prefix)") { root =>
    val w = RunFixtures.write(root)
    LocalSpark.deleteTree(w.runs.groupingRun)
    val rs = read(w, s3Uri).swap.getOrElse(fail("accepted"))
    assert(rs.exists(r => r.step == CheckStep.Tree && r.run == "grouping"), rs)
  }

  tmp.test("ArchiveInput: locations are parsed, inside the root, distinct, and in the store") {
    root =>
      val w = RunFixtures.write(root)
      val (a, c, d, g) = (
        w.runs.archiveRoot,
        w.runs.curatedRun,
        w.runs.decisionRun,
        w.runs.groupingRun
      )
      def problems(store: RunStore, xs: String*): Vector[String] =
        SyntheticCuratedPin
          .archiveInput(store, xs(0), xs(1), xs(2), xs(3), w.runs.pinnedCuratedRunId)
          .swap
          .getOrElse(Vector.empty)
      assertEquals(
        problems(hadoop, fileUri(a), fileUri(c), fileUri(d), fileUri(g)),
        Vector.empty
      )
      assert(problems(hadoop, fileUri(a), "relative/run", fileUri(d), fileUri(g)).nonEmpty)
      assert(
        problems(hadoop, fileUri(a), s3Uri(c), fileUri(d), fileUri(g))
          .exists(_.contains("is not inside")),
        "another scheme is not inside the root"
      )
      assert(
        problems(hadoop, fileUri(c), fileUri(a), fileUri(d), fileUri(g))
          .exists(_.contains("is not inside"))
      )
      assert(
        problems(hadoop, fileUri(a), fileUri(d), fileUri(d), fileUri(g))
          .exists(_.contains("different directories"))
      )
      assert(
        problems(NioRunStore, s3Uri(a), s3Uri(c), s3Uri(d), s3Uri(g))
          .exists(_.contains("local locations only"))
      )
      // The public constructor always pins the committed curated run: the synthetic run is refused.
      val pinned = ArchiveInput
        .of(hadoop, fileUri(a), fileUri(c), fileUri(d), fileUri(g))
        .fold(e => fail(e.mkString("\n")), identity)
      assertEquals(pinned.pinnedCuratedRunId, HadoopCuratedRun.PinnedRunId)
      assert(
        VerifiedArchiveReader
          .read(pinned, limits)
          .swap
          .toOption
          .get
          .exists(_.step == CheckStep.CuratedRun)
      )
  }
}
