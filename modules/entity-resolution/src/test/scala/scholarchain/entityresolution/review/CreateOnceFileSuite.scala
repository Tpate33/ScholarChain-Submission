package scholarchain.entityresolution.review

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import scholarchain.entityresolution.policy.PolicyFixtures.workDir

/**
 * RV-P5-04 N4: create-once writes are all-or-nothing. An I/O error part-way through never leaves a
 * partial target (the old direct `CREATE_NEW` write did), a target that appears while the text is
 * written is never overwritten, and no temporary file is left behind. SYNTHETIC content only.
 */
class CreateOnceFileSuite extends munit.FunSuite {

  private def listing(dir: Path): Vector[String] = {
    val s = Files.list(dir)
    try s.iterator.asScala.map(_.getFileName.toString).toVector.sorted
    finally s.close()
  }

  private val text = "{\"synthetic\":1}\n" * 1000

  test("a complete write creates the file once; a second write is refused and changes nothing") {
    val dir = workDir("create-once-ok")
    val f = dir.resolve("out.jsonl")
    assertEquals(CreateOnceFile.write(f, text), Right(()))
    assertEquals(Files.readString(f, StandardCharsets.UTF_8), text)
    assertEquals(listing(dir), Vector("out.jsonl"), "no temporary file is left")
    val again = CreateOnceFile.write(f, "other")
    assert(again.left.exists(_.contains("exists")), again.toString)
    assertEquals(Files.readString(f, StandardCharsets.UTF_8), text)
  }

  test("an I/O error part-way through leaves no target and no temporary file") {
    val dir = workDir("create-once-failure")
    val f = dir.resolve("out.jsonl")
    val failing: CreateOnceFile.Writer = (ch, buf) => {
      val half = buf.duplicate()
      half.limit(buf.limit() / 2)
      while (half.hasRemaining) ch.write(half)
      throw new IOException("synthetic disk failure")
    }
    val r = CreateOnceFile.write(f, text, failing)
    assert(r.left.exists(_.contains("synthetic disk failure")), r.toString)
    assert(!Files.exists(f), "a partial target was left behind")
    assertEquals(listing(dir), Vector.empty)
    // The create-once rule does not block a clean retry after a failed write.
    assertEquals(CreateOnceFile.write(f, text), Right(()))
    assertEquals(Files.readString(f, StandardCharsets.UTF_8), text)
  }

  test("a target that appears while the text is written is never overwritten") {
    val dir = workDir("create-once-race")
    val f = dir.resolve("out.jsonl")
    val racing: CreateOnceFile.Writer = (ch, buf) => {
      Files.writeString(f, "first writer", StandardCharsets.UTF_8)
      CreateOnceFile.DefaultWriter(ch, buf)
    }
    val r = CreateOnceFile.write(f, text, racing)
    assert(r.left.exists(_.contains("exists")), r.toString)
    assertEquals(Files.readString(f, StandardCharsets.UTF_8), "first writer")
    assertEquals(listing(dir), Vector("out.jsonl"), "no temporary file is left")
  }

  test("the membership manifest writer is create-once through the same all-or-nothing write") {
    val dir = workDir("create-once-manifest")
    val f = dir.resolve("manifest-synthetic.jsonl")
    val members = scholarchain.entityresolution.evaluation.SyntheticEval.manifest
    assertEquals(GoldMembership.writeNew(f, members), Right(()))
    assertEquals(GoldMembership.readFile(f), Right(members))
    assertEquals(GoldMembership.fileSha256(f), Right(GoldMembership.manifestSha256(members)))
    assertEquals(listing(dir), Vector("manifest-synthetic.jsonl"))
    val before = Files.readAllBytes(f)
    assert(GoldMembership.writeNew(f, members.take(1)).isLeft)
    assert(java.util.Arrays.equals(Files.readAllBytes(f), before))
  }
}
