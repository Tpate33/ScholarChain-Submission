package scholarchain.entityresolution.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import upickle.default.write

import scholarchain.entityresolution.ConfigEvidence
import scholarchain.entityresolution.policy.PolicyFixtures.*
import scholarchain.entityresolution.review.{GoldMembership, ReviewQueue}

/**
 * ER-P5-17 (D5 L1): the evaluation names the labels file it read and the SHA-256 of its raw bytes.
 * SYNTHETIC TEST FIXTURES ONLY: every manifest, queue row and label file is invented in the test
 * ([[SyntheticEval]]) under a fresh temporary work directory; none is gold data, no real gold-label
 * file is read, and no result here is a gold-set result.
 */
class LabelsEvidenceSuite extends munit.FunSuite {
  import SyntheticEval.bigSet

  private val Name = "er-gold-membership.jsonl"
  private val LabelsLine = "^labels file=(\\S+) sha256=([0-9a-f]{64})( split=(heldout|dev))?$".r

  /** Independent SHA-256 (lowercase hex) of a file's raw bytes, not via any module helper. */
  private def sha256Of(p: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(p))
      .map(b => String.format("%02x", Byte.box(b)))
      .mkString

  private final case class Fixture(dir: Path, manifest: Path, queue: Path, labels: Path) {
    def args(labelArg: String = labels.toString): Vector[String] =
      Vector(manifest.toString, queue.toString, labelArg)
    def frozen: Vector[String] = Vector(
      s"--manifest-sha256-file=${manifest.resolveSibling(Name + FreezeRecord.Suffix)}",
      s"--expect-split=${split}"
    )
    def split: String =
      GoldMembership.readFile(manifest).fold(e => fail(e.mkString), _.head.split)
  }

  /** SYNTHETIC manifest, queue, labels and freeze record; `sep` joins the label lines. */
  private def fixture(name: String, split: String = "heldout", sep: String = "\n"): Fixture = {
    val (q, m, l) = bigSet(195, 5, 3)
    val dir = workDir(name)
    val mf = dir.resolve(Name)
    val qf = dir.resolve("er-review-queue.jsonl")
    val lf = dir.resolve("labels-synthetic.jsonl")
    Files.writeString(
      mf,
      GoldMembership.toJsonl(m.map(_.copy(split = split))),
      StandardCharsets.UTF_8
    )
    Files.writeString(qf, ReviewQueue.toJsonl(q), StandardCharsets.UTF_8)
    Files.writeString(lf, l.map(x => write(x)).mkString(sep), StandardCharsets.UTF_8)
    Files.writeString(
      mf.resolveSibling(Name + FreezeRecord.Suffix),
      s"${sha256Of(mf)}  $Name\n",
      StandardCharsets.UTF_8
    )
    Fixture(dir, mf, qf, lf)
  }

  private def runMain(args: Vector[String]): (Int, Vector[String]) = {
    val out = Vector.newBuilder[String]
    val code =
      GoldSetEvalMain.runWithConfig(args, out += _, () => ConfigEvidence.injected(configLoader()))
    (code, out.result())
  }

  private def labelLines(text: Vector[String]): Vector[String] =
    text.filter(l =>
      l.startsWith("labels ") || l.startsWith("DEV labels ") || l.contains("labels file=")
    )

  test("held-out run: exactly one labels line with the absolute path and the raw-byte sha256") {
    val f = fixture("er-p5-17-heldout")
    val (code, text) = runMain(f.args() ++ f.frozen)
    val all = text.mkString("\n")
    assertEquals(code, 0, all)
    val ls = labelLines(text)
    assertEquals(ls.size, 1, all)
    assertEquals(
      ls.head,
      s"labels file=${f.labels.toAbsolutePath.normalize} sha256=${sha256Of(f.labels)} split=heldout"
    )
    ls.head match {
      case LabelsLine(p, sha, _, sp) =>
        assert(Path.of(p).isAbsolute, p)
        assertEquals(sha, sha256Of(f.labels))
        assertEquals(sp, "heldout")
      case other => fail(s"labels line has the wrong format: $other")
    }
  }

  test("the labels line appears before the coverage and every metric line") {
    val f = fixture("er-p5-17-order")
    val (code, text) = runMain(f.args() ++ f.frozen)
    assertEquals(code, 0, text.mkString("\n"))
    val at = text.indexWhere(_.startsWith("labels file="))
    assert(at >= 0, text.mkString("\n"))
    val firstMetric = text.indexWhere(l =>
      l.startsWith("researcher/") || l.startsWith("work/") || l.startsWith("reviewed pairs=") ||
        l.startsWith("sensitivity") || l.startsWith("report-json")
    )
    val firstCoverage = text.indexWhere(_.startsWith("coverage "))
    assert(firstMetric > at, s"metric at $firstMetric, labels at $at")
    assert(firstCoverage > at, s"coverage at $firstCoverage, labels at $at")
  }

  test("a relative labels argument is printed as an absolute, normalized path") {
    val f = fixture("er-p5-17-relative")
    val cwd = Path.of("").toAbsolutePath
    val rel = cwd.relativize(f.labels.toAbsolutePath)
    assert(!rel.isAbsolute)
    val (code, text) = runMain(f.args(rel.toString) ++ f.frozen)
    assertEquals(code, 0, text.mkString("\n"))
    val ls = labelLines(text)
    assertEquals(ls.size, 1, text.mkString("\n"))
    ls.head match {
      case LabelsLine(p, _, _, _) =>
        assert(Path.of(p).isAbsolute, p)
        assertEquals(p, f.labels.toAbsolutePath.normalize.toString)
      case other => fail(s"labels line has the wrong format: $other")
    }
  }

  test("the hash is of the raw bytes: CRLF labels hash differently and parse identically") {
    val lf = fixture("er-p5-17-lf")
    val crlf = fixture("er-p5-17-crlf", sep = "\r\n")
    val (c1, o1) = runMain(lf.args() ++ lf.frozen)
    val (c2, o2) = runMain(crlf.args() ++ crlf.frozen)
    assertEquals(c1, 0, o1.mkString("\n"))
    assertEquals(c2, 0, o2.mkString("\n"))
    assertNotEquals(sha256Of(lf.labels), sha256Of(crlf.labels))
    assert(labelLines(o2).head.contains(s"sha256=${sha256Of(crlf.labels)} "), o2.mkString("\n"))
    def metrics(o: Vector[String]) = o.filter(_.startsWith("researcher/"))
    assertEquals(metrics(o1), metrics(o2))
  }

  test("dev run: the labels line carries the DEV marking and the split suffix") {
    val f = fixture("er-p5-17-dev", split = "dev")
    val (code, text) = runMain(f.args() ++ f.frozen)
    assertEquals(code, 0, text.mkString("\n"))
    assertEquals(
      labelLines(text),
      Vector(
        s"DEV labels file=${f.labels.toAbsolutePath.normalize} sha256=${sha256Of(f.labels)} split=dev"
      )
    )
  }

  test("a missing or unreadable labels file is refused and prints no labels line") {
    val f = fixture("er-p5-17-missing")
    val missing = f.dir.resolve("no-such-labels.jsonl")
    assert(!Files.exists(missing))
    val (c1, o1) = runMain(f.args(missing.toString) ++ f.frozen)
    assertEquals(c1, 2, o1.mkString("\n"))
    assert(o1.exists(_.contains(s"cannot read $missing")), o1.mkString("\n"))
    assertEquals(labelLines(o1), Vector.empty, o1.mkString("\n"))
    assert(!o1.exists(l => l.contains("human-reviewed=") || l.startsWith("report-json")))
    // A directory cannot be read as a file: refused the same way, no line.
    val (c2, o2) = runMain(f.args(f.dir.toString) ++ f.frozen)
    assertEquals(c2, 2, o2.mkString("\n"))
    assertEquals(labelLines(o2), Vector.empty, o2.mkString("\n"))
    // Not UTF-8: the bytes were read but are refused as before; no evidence for a refused read.
    val bad = Files.write(f.dir.resolve("not-utf8.jsonl"), Array(0xff.toByte, '\n'.toByte))
    val (c3, o3) = runMain(f.args(bad.toString) ++ f.frozen)
    assertEquals(c3, 2, o3.mkString("\n"))
    assert(o3.exists(_.contains(s"cannot read $bad")), o3.mkString("\n"))
    assertEquals(labelLines(o3), Vector.empty, o3.mkString("\n"))
  }

  test("labels not reached (manifest refused first) print no labels line") {
    val f = fixture("er-p5-17-not-reached")
    val (code, text) =
      runMain(
        f.args() :+ s"--manifest-sha256-file=${f.dir.resolve(Name + ".sha256")}" :+
          "--expect-split=dev"
      )
    assertEquals(code, 2, text.mkString("\n"))
    assertEquals(labelLines(text), Vector.empty, text.mkString("\n"))
  }

  test("labels read but refused at ingest: the refusal still names the file and hash once") {
    val f = fixture("er-p5-17-refused")
    Files.writeString(f.labels, "{not json}\n", StandardCharsets.UTF_8)
    val (code, text) = runMain(f.args() ++ f.frozen)
    assertEquals(code, 2, text.mkString("\n"))
    assert(text.head.startsWith("refused:"), text.head)
    assertEquals(
      labelLines(text),
      Vector(s"labels file=${f.labels.toAbsolutePath.normalize} sha256=${sha256Of(f.labels)}")
    )
  }

  test("readLabelsWithSha256: lines equal Files.readAllLines for the same bytes") {
    val dir = workDir("er-p5-17-lines")
    val texts = Vector(
      "",
      "a",
      "a\n",
      "a\nb",
      "a\nb\n",
      "a\r\nb\r\n",
      "a\rb\r",
      "a\n\n",
      "\n\n\n",
      "a\r\n\r\nb",
      "﻿a\n",
      "x é中\n y\n"
    )
    texts.zipWithIndex.foreach { case (t, i) =>
      val p = Files.writeString(dir.resolve(s"t$i.jsonl"), t, StandardCharsets.UTF_8)
      val want = Files.readAllLines(p, StandardCharsets.UTF_8).asScala.toVector
      GoldSetEvalMain.readLabelsWithSha256(p.toString) match {
        case Right((path, sha, lines)) =>
          assertEquals(lines, want, s"case $i")
          assertEquals(sha, sha256Of(p), s"case $i")
          assertEquals(path, p)
        case Left(e) => fail(s"case $i: $e")
      }
    }
    assert(GoldSetEvalMain.readLabelsWithSha256(dir.resolve("none").toString).isLeft)
  }
}
