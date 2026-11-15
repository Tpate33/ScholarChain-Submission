package scholarchain.entityresolution.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import upickle.default.write

import scholarchain.core.config.ConfigLoader
import scholarchain.entityresolution.{ConfigEvidence, LoadedConfig}
import scholarchain.entityresolution.policy.PolicyFixtures.*
import scholarchain.entityresolution.review.{
  GoldMember,
  GoldMembership,
  ReviewQueue,
  ReviewQueueRow
}

/**
 * ER-P5-15 (decisions-15 items 1 and 2): the single freeze-record format verified by the evaluation
 * (`--manifest-sha256-file`), the runtime split check (`--expect-split`), and the configuration
 * evidence lines. SYNTHETIC TEST FIXTURES ONLY: every manifest, queue row and label is invented in
 * the test ([[SyntheticEval]]); none is gold data and no result here is a gold-set result.
 */
class FreezeSplitConfigSuite extends munit.FunSuite {
  import SyntheticEval.bigSet

  private val Name = "er-gold-membership.jsonl"
  private val hex = "0123456789abcdef" * 4

  private def bytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  // ------------------------------------------------------------------ the format

  test("freeze record: exactly the shasum -a 256 line is accepted") {
    assertEquals(FreezeRecord.parse(bytes(s"$hex  $Name\n"), Name), Right(hex))
    assertEquals(FreezeRecord.Suffix, ".sha256")
  }

  test("freeze record: every other shape is refused with its reason, never repaired") {
    val bom = Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte) ++ bytes(s"$hex  $Name\n")
    val notUtf8 = bytes(s"$hex  ") ++ Array(0xff.toByte) ++ bytes("\n")
    val cases: Vector[(String, Array[Byte], String)] = Vector(
      ("empty", Array.emptyByteArray, "is empty"),
      ("byte-order mark", bom, "byte-order mark"),
      ("CRLF", bytes(s"$hex  $Name\r\n"), "carriage return"),
      ("lone CR", bytes(s"$hex  $Name\r"), "carriage return"),
      ("no final LF", bytes(s"$hex  $Name"), "does not end with a line feed"),
      ("trailing blank line", bytes(s"$hex  $Name\n\n"), "more than one line"),
      ("extra line", bytes(s"$hex  $Name\n$hex  other.jsonl\n"), "more than one line"),
      ("upper-case hex", bytes(s"${hex.toUpperCase}  $Name\n"), "upper-case hex"),
      ("63 hex digits", bytes(s"${hex.drop(1)}  $Name\n"), "must be exactly one line"),
      ("one space", bytes(s"$hex $Name\n"), "must be exactly one line"),
      ("tab", bytes(s"$hex\t$Name\n"), "must be exactly one line"),
      ("hex only", bytes(s"$hex\n"), "must be exactly one line"),
      ("hex and separator only", bytes(s"$hex  \n"), "must be exactly one line"),
      ("binary mode", bytes(s"$hex *$Name\n"), "binary-mode marker"),
      ("escaped line", bytes(s"\\$hex  $Name\n"), "escaped shasum line"),
      ("leading space in name", bytes(s"$hex   $Name\n"), "whitespace or a control character"),
      ("trailing space", bytes(s"$hex  $Name \n"), "whitespace or a control character"),
      ("other file name", bytes(s"$hex  other.jsonl\n"), "names 'other.jsonl'"),
      ("relative path", bytes(s"$hex  ./$Name\n"), s"names './$Name'"),
      ("bare digest", bytes(s"$hex\n"), "must be exactly one line"),
      ("not UTF-8", notUtf8, "is not UTF-8")
    )
    cases.foreach { case (clue, b, why) =>
      FreezeRecord.parse(b, Name) match {
        case Right(v) => fail(s"$clue accepted as $v")
        case Left(e) => assert(e.contains(why), s"$clue: $e")
      }
    }
  }

  test("freeze record file: missing, symbolic link, other directory and oversize are refused") {
    val dir = workDir("er-p5-15-freeze-file")
    val m = dir.resolve(Name)
    Files.writeString(m, "x\n", StandardCharsets.UTF_8)
    val f = dir.resolve(Name + FreezeRecord.Suffix)
    assert(FreezeRecord.read(f, m).left.exists(_.contains("missing")), "missing")
    Files.writeString(f, s"$hex  $Name\n", StandardCharsets.UTF_8)
    assertEquals(FreezeRecord.read(f, m), Right(hex))
    val link = dir.resolve("link.sha256")
    Files.createSymbolicLink(link, f)
    assert(FreezeRecord.read(link, m).left.exists(_.contains("symbolic link")))
    val other = Files.createDirectories(dir.resolve("elsewhere")).resolve(Name + ".sha256")
    Files.writeString(other, s"$hex  $Name\n", StandardCharsets.UTF_8)
    assert(
      FreezeRecord.read(other, m).left.exists(_.contains("not in the membership file's directory"))
    )
    val big = dir.resolve("big.sha256")
    Files.writeString(big, s"$hex  ${"a" * FreezeRecord.MaxBytes}\n", StandardCharsets.UTF_8)
    assert(FreezeRecord.read(big, m).left.exists(_.contains("larger than")))
    assert(FreezeRecord.read(dir, m).left.exists(_.contains("not a regular file")))
  }

  test("freeze record file: it must be named exactly <membership file name>.sha256 (ER-P5-16)") {
    val dir = workDir("er-p5-16-freeze-name")
    val m = dir.resolve(Name)
    Files.writeString(m, "x\n", StandardCharsets.UTF_8)
    val line = s"$hex  $Name\n" // a correct line: right digest form, right membership name
    val wrongNames = Vector(
      "anything.txt",
      "er-gold-membership.sha256",
      s"$Name.SHA256",
      s"$Name.sha256.txt",
      s"other-$Name.sha256",
      s"$Name.sha256 "
    )
    wrongNames.foreach { n =>
      val f = Files.writeString(dir.resolve(n), line, StandardCharsets.UTF_8)
      val r = FreezeRecord.read(f, m)
      assert(
        r.left.exists(_.contains(s"must be named exactly '$Name.sha256'")),
        s"$n: $r"
      )
    }
    val ok =
      Files.writeString(dir.resolve(Name + FreezeRecord.Suffix), line, StandardCharsets.UTF_8)
    assertEquals(FreezeRecord.read(ok, m), Right(hex))
  }

  test("entry point: a correct record under another name beside the manifest is refused") {
    val f = heldout("er-p5-16-freeze-name-main")
    val good = freeze(f)
    val txt = Files.copy(good, f.dir.resolve("anything.txt"))
    val (code, text) =
      runMain(f.args :+ s"--manifest-sha256-file=$txt" :+ "--expect-split=heldout")
    assertEquals(code, 2, text.mkString("\n"))
    assert(text.exists(_.contains("must be named exactly")), text.mkString("\n"))
    assert(!text.exists(l => l.contains("human-reviewed=") || l.startsWith("report-json")))
    assertEquals(
      runMain(f.args :+ s"--manifest-sha256-file=$good" :+ "--expect-split=heldout")._1,
      0
    )
  }

  // ------------------------------------------------------------------ the entry point

  private final case class Files3(dir: Path, manifest: Path, args: Vector[String], sha: String) {
    def freeze: Path = manifest.resolveSibling(manifest.getFileName.toString + ".sha256")
  }

  /** SYNTHETIC manifest, queue and labels written under a fresh work directory. */
  private def evalFiles(
      name: String,
      q: Vector[ReviewQueueRow],
      m: Vector[GoldMember],
      l: Vector[GoldLabel]
  ): Files3 = {
    val dir = workDir(name)
    val mf = dir.resolve(Name)
    val qf = dir.resolve("er-review-queue.jsonl")
    val lf = dir.resolve("labels-synthetic.jsonl")
    Files.writeString(mf, GoldMembership.toJsonl(m), StandardCharsets.UTF_8)
    Files.writeString(qf, ReviewQueue.toJsonl(q), StandardCharsets.UTF_8)
    Files.writeString(lf, l.map(x => write(x)).mkString("\n"), StandardCharsets.UTF_8)
    val sha = GoldMembership.fileSha256(mf).fold(e => fail(e), identity)
    Files3(dir, mf, Vector(mf.toString, qf.toString, lf.toString), sha)
  }

  /** Writes the freeze record as the human does: `shasum -a 256 <name> > <name>.sha256`. */
  private def freeze(f: Files3, line: String = ""): Path = {
    val text = if (line.isEmpty) s"${f.sha}  ${f.manifest.getFileName}\n" else line
    Files.writeString(f.freeze, text, StandardCharsets.UTF_8)
  }

  private def runMain(
      args: Vector[String],
      source: () => LoadedConfig = () => ConfigEvidence.injected(configLoader())
  ): (Int, Vector[String]) = {
    val out = Vector.newBuilder[String]
    val code = GoldSetEvalMain.runWithConfig(args, out += _, source)
    (code, out.result())
  }

  private def heldout(name: String): Files3 = {
    val (q, m, l) = bigSet(195, 5, 3)
    evalFiles(name, q, m, l)
  }

  test("held-out final metrics with the recorded freeze file and the expected split") {
    val f = heldout("er-p5-15-heldout-ok")
    val rec = freeze(f)
    val (code, text) =
      runMain(f.args :+ s"--manifest-sha256-file=$rec" :+ "--expect-split=heldout")
    assertEquals(code, 0, text.mkString("\n"))
    assert(
      text.contains(
        s"manifest sha256=${f.sha} freeze=matches file=${rec.toAbsolutePath.normalize} " +
          "split=heldout"
      ),
      text.mkString("\n")
    )
    assert(text.contains("expect-split=heldout matches the manifest split=heldout"))
    assert(text.exists(_.startsWith("researcher/all:")), text.mkString("\n"))
    assert(text.exists(_.startsWith("report-json: ")))
    assert(text.head.contains("(--manifest-sha256-file)"), text.head)
  }

  test("a freeze record of other bytes, or a malformed one, refuses the evaluation") {
    val f = heldout("er-p5-15-heldout-mismatch")
    val withFile = f.args :+ s"--manifest-sha256-file=${f.freeze}" :+ "--expect-split=heldout"
    freeze(f, s"${"0" * 64}  ${f.manifest.getFileName}\n")
    val (c1, o1) = runMain(withFile)
    assertEquals(c1, 2, o1.mkString("\n"))
    assert(o1.exists(_.contains("differs from the recorded freeze")), o1.mkString("\n"))
    assert(!o1.exists(_.contains("human-reviewed=")), "coverage printed after a mismatch")
    assert(!o1.exists(_.startsWith("report-json")))
    freeze(f, s"${f.sha}  ${f.manifest.getFileName}\r\n")
    val (c2, o2) = runMain(withFile)
    assertEquals(c2, 2, o2.mkString("\n"))
    assert(o2.exists(_.contains("carriage return")), o2.mkString("\n"))
    Files.delete(f.freeze)
    val (c3, o3) = runMain(withFile)
    assertEquals(c3, 2, o3.mkString("\n"))
    assert(o3.exists(_.contains("missing or not a regular file")), o3.mkString("\n"))
  }

  test("argument rules: the file needs --expect-split; one freeze form; each flag once") {
    val f = heldout("er-p5-15-args")
    val rec = freeze(f)
    val cases = Vector(
      "file without split" -> (Vector(s"--manifest-sha256-file=$rec"), "requires --expect-split"),
      "removed hex flag, alone" -> (
        Vector(s"--manifest-sha256=${f.sha}", "--expect-split=heldout"),
        "unknown argument '--manifest-sha256="
      ),
      "removed hex flag, with the file" -> (
        Vector(
          s"--manifest-sha256-file=$rec",
          "--expect-split=heldout",
          s"--manifest-sha256=${f.sha}"
        ),
        "unknown argument '--manifest-sha256="
      ),
      "empty file path" -> (Vector("--manifest-sha256-file=", "--expect-split=heldout"), "needs"),
      "unknown split" -> (Vector("--expect-split=test"), "must be heldout or dev"),
      "split twice" -> (
        Vector("--expect-split=heldout", "--expect-split=heldout"),
        "given more than once"
      ),
      "file twice" -> (
        Vector(s"--manifest-sha256-file=$rec", s"--manifest-sha256-file=$rec"),
        "given more than once"
      )
    )
    cases.foreach { case (clue, (extra, why)) =>
      val (code, text) = runMain(f.args ++ extra)
      assertEquals(code, 2, s"$clue: ${text.mkString("\n")}")
      assert(text.exists(_.contains(why)), s"$clue: ${text.mkString("\n")}")
      assert(!text.exists(_.contains("human-reviewed=")), clue)
    }
  }

  test("--expect-split refuses a manifest of the other split before anything is evaluated") {
    val f = heldout("er-p5-15-split-heldout")
    val rec = freeze(f)
    val (c1, o1) = runMain(f.args :+ s"--manifest-sha256-file=$rec" :+ "--expect-split=dev")
    assertEquals(c1, 2, o1.mkString("\n"))
    assert(
      o1.exists(_.contains("the membership manifest is split=heldout, not --expect-split=dev")),
      o1.mkString("\n")
    )
    assert(!o1.exists(l => l.contains("human-reviewed=") || l.startsWith("report-json")))
    // Without the freeze file the split check applies too.
    assertEquals(runMain(f.args :+ "--expect-split=dev")._1, 2)
    // A dev manifest: refused as held-out, evaluated as DEV diagnostics with the dev freeze.
    val (q, hm, l) = bigSet(195, 5, 3)
    val d = evalFiles("er-p5-15-split-dev", q, hm.map(_.copy(split = "dev")), l)
    val drec = freeze(d)
    val (c2, o2) = runMain(d.args :+ s"--manifest-sha256-file=$drec" :+ "--expect-split=heldout")
    assertEquals(c2, 2, o2.mkString("\n"))
    assert(o2.exists(_.contains("split=dev, not --expect-split=heldout")), o2.mkString("\n"))
    val (c3, o3) = runMain(d.args :+ s"--manifest-sha256-file=$drec" :+ "--expect-split=dev")
    assertEquals(c3, 0, o3.mkString("\n"))
    assert(o3.head.startsWith("DEV evaluation split=dev"), o3.head)
    o3.filterNot(l => l.startsWith("report-json: ") || l.startsWith("DEV evaluation")).foreach {
      l => assert(l.startsWith("DEV ") && l.endsWith(" split=dev"), l)
    }
    assert(o3.exists(_.contains("freeze=matches file=")), o3.mkString("\n"))
  }

  test("held-out without the freeze file: coverage only, and the refusal names the file flag") {
    val f = heldout("er-p5-15-no-freeze")
    val (code, text) = runMain(f.args :+ "--expect-split=heldout")
    assertEquals(code, 2, text.mkString("\n"))
    assert(
      text.exists(_.startsWith("final held-out gold metrics refused: --manifest-sha256-file=")),
      text.mkString("\n")
    )
    assert(text.exists(_.contains("freeze=not-checked (no --manifest-sha256-file given)")))
    assert(!text.exists(l => l.contains("/all:") || l.startsWith("report-json")))
  }

  // ------------------------------------------------------------------ configuration evidence

  private def configFile(dir: Path, name: String, text: String): Path = {
    val p = dir.resolve(name)
    Files.writeString(p, text, StandardCharsets.UTF_8)
    p
  }

  test("configuration evidence: the file read, its sha256, and override names are printed") {
    val f = heldout("er-p5-15-config")
    val rec = freeze(f)
    val conf = configFile(f.dir, "er-config.conf", configText())
    val confSha = GoldMembership.fileSha256(conf).fold(e => fail(e), identity)
    val args = f.args :+ s"--manifest-sha256-file=$rec" :+ "--expect-split=heldout"
    val env = Map(ConfigLoader.FilesEnvVar -> conf.toString)
    val (code, text) = runMain(args, () => ConfigEvidence.load(env, ConfigLoader.readLocalFile))
    assertEquals(code, 0, text.mkString("\n"))
    assert(
      text.contains(
        s"config file=${conf.toAbsolutePath.normalize} sha256=$confSha split=heldout"
      ),
      text.mkString("\n")
    )
    assert(text.contains("config overrides=none split=heldout"), text.mkString("\n"))
    // An override of a key the evaluation does not check is applied by core and named here.
    val hops = "SCHOLARCHAIN_CFG_RUNTIME__MAX_PATH_HOPS"
    val (c2, o2) = runMain(
      args,
      () => ConfigEvidence.load(env + (hops -> "4"), ConfigLoader.readLocalFile)
    )
    assertEquals(c2, 0, o2.mkString("\n"))
    assert(o2.exists(_.startsWith(s"config overrides=$hops (")), o2.mkString("\n"))
    // No configuration: refused, and the evidence says that no file was read.
    val (c3, o3) = runMain(args, () => ConfigEvidence.load(Map.empty, ConfigLoader.readLocalFile))
    assertEquals(c3, 2, o3.mkString("\n"))
    assert(o3.exists(_.contains("SCHOLARCHAIN_CONFIG_FILES")), o3.mkString("\n"))
    assert(o3.exists(_.startsWith("config files=none")), o3.mkString("\n"))
    // An in-process configuration has no file evidence and says so.
    val (_, o4) = runMain(args)
    assert(o4.exists(_.startsWith("config source=injected")), o4.mkString("\n"))
  }

  test("configuration evidence records every listed file in order, hashing the bytes parsed") {
    val dir = workDir("er-p5-15-config-two")
    val a = configFile(dir, "a.conf", configText())
    val b = configFile(dir, "b.conf", "configSchemaVersion = \"scholarchain.config.v2\"\n")
    val loaded = ConfigEvidence.load(
      Map(ConfigLoader.FilesEnvVar -> s"$a,$b"),
      ConfigLoader.readLocalFile
    )
    assert(loaded.result.isRight, loaded.result.toString)
    assertEquals(
      loaded.files,
      Vector(a, b).map(p =>
        p.toAbsolutePath.normalize.toString -> GoldMembership.fileSha256(p).toOption.get
      )
    )
    assertEquals(loaded.overrides, Vector.empty)
    assert(!loaded.injected)
  }
}
