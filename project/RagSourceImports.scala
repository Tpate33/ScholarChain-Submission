import java.io.{File, IOException}
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  SimpleFileVisitor,
  StandardOpenOption
}
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

import scala.collection.mutable.ListBuffer

import sbt.internal.util.MessageOnlyException
import sbt.util.Logger

/**
 * Source-import check for the rag module (work order BE-P7-02, review CR-P7-01 F1). Owner:
 * build-engineer. Run by `check` as `verifyRagSourceImports`, before Test/compile and markTestRun.
 *
 * CLAUDE.md: "rag -> core, storage (ports/interfaces only, never concrete AWS clients)". The
 * classpath check (verifyDependencyScopes) keeps SDK, Parquet and Hadoop jars off rag, but the
 * storage adapter packages `scholarchain.storage.aws.*` and `scholarchain.storage.parquet` ship in
 * the same storage artifact rag depends on. This check scans every `*.scala` and `*.java` file
 * (extension compared case-insensitively) under `modules/rag/src/` (main, test and any other source
 * set) and fails on any reference to these packages: software.amazon.awssdk, com.amazonaws,
 * scholarchain.storage.aws, scholarchain.storage.parquet, org.apache.parquet, org.apache.hadoop.
 *
 * Method: a lexical scan, not a compiler. Each file (bytes decoded as ISO-8859-1, so every byte
 * maps to one character) is tokenized into identifiers (backticked identifiers unwrapped), `.`,
 * `{`, `}`, `*`, `=>` and single-character separators; whitespace and newlines are skipped, so a
 * path split across lines still matches. A lexer (`lex`, BE-P7-03 for CR-P7-02 F-B) models Scala 3
 * and Java literals and comments: nested Scala block comments, line comments, plain and
 * triple-quoted strings, Java text blocks, character literals (escapes, UTF-8 sequences), Scala 3
 * quotes `'{`/`'[`, backticked identifiers, and interpolated strings (`id"` or `id"""` after an
 * identifier that is not a hard keyword) with the escapes `$$` and `$"` and `${...}` splices lexed
 * as code, recursively (nesting at most MaxNesting). It yields two texts of the same length
 * (newlines kept): `code` (comments blanked, literal text kept) and `bare` (comments and literal
 * text blanked, splice code kept). Three token streams are scanned: the raw text and `code` with
 * the path, selector, wildcard and package rules (so a path split by a comment,
 * `scholarchain.storage./* */aws`, still matches), and `bare` with those four rules plus the rename
 * and backtick-escape rules. The tokenizer pairs any two backticks on a line, so in the raw and
 * `code` texts two backticks inside string literals can swallow a reference between them; `bare`
 * holds no literal text, so its backticks come only from code (CR-P7-04 F-C), and splice code
 * (`${...}`) stays in it. Rules, for a forbidden path s1...sn:
 *   - path: any dotted run of at least its last two segments (`storage.aws`, `apache.hadoop`,
 *     `amazon.awssdk`, `com.amazonaws`, and every longer suffix up to the full path). Covers plain,
 *     wildcard and renamed imports of the package or anything under it, fully qualified references
 *     in code and types, Scala 3 `export` clauses, `_root_.` prefixes, and relative references
 *     after importing a parent package (`import scholarchain.storage` then `storage.aws.X`);
 *   - selector: a dotted run of segments si...sj (j < n) followed by `.{` whose braces contain the
 *     next segment s(j+1), `_` or `*` (a `_` right after `as` or `=>` hides a name and does not
 *     count). Covers braced imports and exports (`scholarchain.storage.{aws, port}`) and braced
 *     renames of ancestors (`scholarchain.{storage => st}`, `org.{apache as a}`);
 *   - rename (`bare` stream; CR-P7-02 F-A): a dotted run si...sj (j < n) followed by the identifier
 *     `as`, or by `=>` inside selector braces (`.{ ... }`). Covers unbraced Scala 3 renames of an
 *     ancestor (`import scholarchain.storage as st`, `import org.apache as a`, `import scholarchain
 *     as sc`, `export scholarchain.storage as st`) and relative ones (`import storage as st` under
 *     a chained `package scholarchain`), after which `st.aws.X` names no forbidden run. It runs on
 *     `bare` so that prose in comments and strings ("storage as a port") does not fail;
 *   - wildcard: a dotted run si...sj (j < n) followed by `.*` or `._` (an ancestor wildcard such as
 *     `scholarchain.storage.*`, which would bring `aws` into scope);
 *   - package: a `package` clause whose path starts with si...s(n-1) (`package
 *     scholarchain.storage`, or the chained clause `package storage` under `package scholarchain`),
 *     which would put the forbidden package in scope by its last segment;
 *   - comment-at-dot (raw text, independent of the lexer; CR-P7-02 F-B): a `.` followed by spaces
 *     or tabs and a comment opener (slash-star or slash-slash); a block-comment closer (star-slash)
 *     followed by whitespace (newlines included) and `.`; and a line that ends with `.` (not
 *     starting with `*`, containing no comment opener) whose next non-blank line starts with a
 *     comment opener. A comment next to a dot is how a comment splits a path, so a lexer
 *     desynchronisation cannot hide such a split;
 *   - lexer (fail closed when unsure): an unterminated block comment, string or `${` splice, a
 *     single-line string not closed on its line, a `'` that is neither a character literal nor a
 *     Scala 3 quote, or interpolation nesting deeper than MaxNesting;
 *   - suspicious-char (raw bytes, both languages; U1): malformed UTF-8, and any code point of
 *     Unicode category Cc (except tab, LF, CR, FF), Cf, Zs (except the ASCII space), Zl or Zp,
 *     except a byte-order mark at offset 0. javac ignores identifier-ignorable characters (Cc, Cf)
 *     inside identifiers, so `had` + ZERO WIDTH SPACE + `oop` would still name Hadoop; neither
 *     compiler is assumed to treat non-ASCII spaces as whitespace, but such bytes fail anyway;
 *   - backtick-escape (`bare` stream; U2): a backslash inside a backticked identifier, in case a
 *     compiler translated a unicode escape there;
 *   - java-unicode-escape (`*.java` only): any backslash followed by the letter u, because javac
 *     translates unicode escapes before tokenizing, so an escaped letter inside `hadoop` still
 *     names Hadoop. Scala 3 translates escapes only inside literals.
 *
 * Conservative choices (documented, intended): comments and string literals are scanned like code
 * by the path, selector, wildcard and package rules, so a mention there fails too; the selector,
 * wildcard and rename rules also fail ancestor forms that could be harmless (`import
 * scholarchain.{core, storage}`, `import scholarchain.*`, `import org.apache.*`, `import
 * foo.storage as fs`, a string holding a glob with star-slash-dot or dot-slash-star); a block
 * comment followed by a dot (a leading-dot method chain after a block comment) fails; Cf characters
 * such as ZERO WIDTH JOINER in emoji fail; Scala XML literals with an unbalanced quote fail as a
 * lexer problem. Allowed: `scholarchain.storage.port.*`, `scholarchain.storage.model.*`,
 * `scholarchain.storage.{port, model}`, `scholarchain.core.*`, `org.apache.commons.*`, and a line
 * comment before a leading-dot method chain.
 *
 * Not detected (residual): names assembled at run time (string concatenation for reflection); a
 * lexer mismodel of some Scala 3 or Java form that neither raises a lexer problem nor leaves a
 * comment next to a dot, which could hide a rename or a path split by a line comment placed before
 * a leading dot (`storage // c` newline `.aws`); and whatever storage's own port packages expose (a
 * type alias or export of an adapter type inside scholarchain.storage.port is visible to rag
 * without naming a forbidden package; that is the storage owner's boundary). The lexer forms above
 * were modelled from the language rules, not checked against scalac or javac by this task.
 *
 * File system: the walk never follows symlinks; any symlink inside the tree, a symlinked `modules`,
 * `modules/rag` or `modules/rag/src`, a non-regular entry, an unreadable entry, a source file over
 * 4 MiB, or more than 100000 entries fails closed. Sources are opened with NOFOLLOW_LINKS. A second
 * line checks that every rag Compile/Test source directory sbt uses lies under modules/rag/src,
 * that every unmanaged source sbt compiles was scanned, and that rag has no managed (generated)
 * sources. An empty tree is a PASS that states it is vacuous.
 *
 * Output: one line per scan, relative paths sorted, no time stamp. The scan reads only repository
 * source; the self-test writes a fixture tree under the root target/ and deletes it.
 */
object RagSourceImports {
  val Task = "verifyRagSourceImports"
  val ScanRoot = "modules/rag/src"
  val MaxEntries = 100000
  val MaxFileBytes: Long = 4L * 1024 * 1024

  val ForbiddenPackages: Seq[String] = Seq(
    "software.amazon.awssdk",
    "com.amazonaws",
    "scholarchain.storage.aws",
    "scholarchain.storage.parquet",
    "org.apache.parquet",
    "org.apache.hadoop"
  )
  private val forbidden: Seq[Vector[String]] = ForbiddenPackages.map(_.split('.').toVector)

  /** A backslash followed by `u`, built from two literals so no escape appears in this source. */
  val UnicodeEscapeStart: String = "\\" + "u"

  final case class Line(pass: Boolean, id: String, detail: String) {
    def render: String = s"$Task: [${if (pass) "PASS" else "FAIL"}] $id: $detail"
  }

  final case class Tok(text: String, line: Int, ident: Boolean)

  /** One match: 1-based line, rule name, forbidden package. */
  final case class Hit(line: Int, rule: String, pkg: String)

  private def idStart(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$'
  private def idPart(c: Char): Boolean = idStart(c) || (c >= '0' && c <= '9')

  def tokens(src: String): Vector[Tok] = {
    val out = Vector.newBuilder[Tok]
    val n = src.length
    var i = 0
    var line = 1
    while (i < n) {
      val c = src.charAt(i)
      if (c == '\n') { line += 1; i += 1 }
      else if (c == ' ' || c == '\t' || c == '\r' || c == '\f') i += 1
      else if (c == '`') {
        val close = src.indexOf('`', i + 1)
        val nl = src.indexOf('\n', i + 1)
        if (close > i + 1 && (nl < 0 || close < nl)) {
          out += Tok(src.substring(i + 1, close), line, ident = true)
          i = close + 1
        } else { out += Tok("`", line, ident = false); i += 1 }
      } else if (idStart(c)) {
        var j = i + 1
        while (j < n && idPart(src.charAt(j))) j += 1
        out += Tok(src.substring(i, j), line, ident = true)
        i = j
      } else if (c == '=' && i + 1 < n && src.charAt(i + 1) == '>') {
        out += Tok("=>", line, ident = false); i += 2
      } else { out += Tok(c.toString, line, ident = false); i += 1 }
    }
    out.result()
  }

  /** 1-based line of a character offset (binary search over the newline offsets). */
  private def lineIndex(src: String): Int => Int = {
    val nl = src.indices.filter(src.charAt(_) == '\n').toArray
    (at: Int) => {
      val pos = java.util.Arrays.binarySearch(nl, at)
      (if (pos >= 0) pos else -pos - 1) + 1
    }
  }

  /** Scala 3 hard keywords: a string literal right after one of them is not interpolated. */
  private val ScalaKeywords: Set[String] = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "given",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "this",
    "throw",
    "trait",
    "true",
    "try",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield"
  )

  /** Deepest `${` splice nesting the lexer follows; deeper fails closed. */
  val MaxNesting = 32
  private val MaxProblems = 20

  /**
   * Lexer output: `code` has comments blanked and literal text kept; `bare` has comments and
   * literal text blanked and splice code kept. Both have the length of the source, newlines kept.
   * `problems` are (line, reason) where the lexer is unsure (fail closed).
   */
  final case class Lexed(code: String, bare: String, problems: Seq[(Int, String)])

  def lex(src: String, isJava: Boolean): Lexed = {
    val n = src.length
    val codeOut = new java.lang.StringBuilder(n)
    val bareOut = new java.lang.StringBuilder(n)
    val problems = ListBuffer.empty[(Int, String)]
    val lineOf = lineIndex(src)
    def problem(at: Int, what: String): Unit =
      if (problems.size < MaxProblems) problems += ((lineOf(at), what))
    def emit(from: Int, to: Int, inCode: Boolean, inBare: Boolean): Unit = {
      var k = from
      while (k < to) {
        val c = src.charAt(k)
        val blank = if (c == '\n') '\n' else ' '
        codeOut.append(if (inCode) c else blank)
        bareOut.append(if (inBare) c else blank)
        k += 1
      }
    }
    def asCode(from: Int, to: Int): Unit = emit(from, to, inCode = true, inBare = true)
    def asLiteral(from: Int, to: Int): Unit = emit(from, to, inCode = true, inBare = false)
    def asComment(from: Int, to: Int): Unit = emit(from, to, inCode = false, inBare = false)

    def interpolated(at: Int): Boolean =
      !isJava && at > 0 && idPart(src.charAt(at - 1)) && {
        var j = at - 1
        while (j >= 0 && idPart(src.charAt(j))) j -= 1
        val word = src.substring(j + 1, at)
        idStart(word.charAt(0)) && !ScalaKeywords.contains(word)
      }

    def utf8Len(at: Int): Int = {
      val b = src.charAt(at).toInt
      if (b >= 0xc0 && b <= 0xdf) 2
      else if (b >= 0xe0 && b <= 0xef) 3
      else if (b >= 0xf0 && b <= 0xf7) 4
      else 1
    }

    def isOctal(at: Int): Boolean = at < n && src.charAt(at) >= '0' && src.charAt(at) <= '7'

    def charLiteral(at: Int): Int = {
      val end =
        if (at + 1 >= n) -1
        else if (src.charAt(at + 1) == '\\') {
          var j = at + 2
          if (j < n && src.charAt(j) == 'u') {
            while (j < n && src.charAt(j) == 'u') j += 1
            j += 4
          } else if (isOctal(j)) {
            val stop = j + 3
            while (j < stop && isOctal(j)) j += 1
          } else j += 1
          if (j < n && src.charAt(j) == '\'') j + 1 else -1
        } else {
          val c1 = src.charAt(at + 1)
          if (c1 == '\'' || c1 == '\n' || c1 == '\r') -1
          else {
            val j = at + 1 + utf8Len(at + 1)
            if (j < n && src.charAt(j) == '\'') j + 1 else -1
          }
        }
      if (end > 0) { asLiteral(at, end); end }
      else if (!isJava && at + 1 < n && (src.charAt(at + 1) == '{' || src.charAt(at + 1) == '[')) {
        asCode(at, at + 1); at + 1
      } else { problem(at, "unrecognised character literal"); asCode(at, at + 1); at + 1 }
    }

    def blockComment(at: Int): Int = {
      var depth = 1
      var j = at + 2
      while (j < n && depth > 0) {
        if (src.startsWith("*/", j)) { depth -= 1; j += 2 }
        else if (!isJava && src.startsWith("/*", j)) { depth += 1; j += 2 }
        else j += 1
      }
      if (depth > 0) problem(at, "unterminated block comment")
      val e = math.min(n, j)
      asComment(at, e)
      e
    }

    def stringLiteral(at: Int, depth: Int): Int = {
      val interp = interpolated(at)
      val triple = src.startsWith("\"\"\"", at)
      var i = if (triple) at + 3 else at + 1
      asLiteral(at, i)
      var end = -1
      while (end < 0) {
        if (i >= n) { problem(at, "unterminated string literal"); end = n }
        else {
          val c = src.charAt(i)
          val next = if (i + 1 < n) src.charAt(i + 1) else 0.toChar
          if (triple && src.startsWith("\"\"\"", i)) {
            var e = i + 3
            while (e < n && src.charAt(e) == '"') e += 1
            asLiteral(i, e); end = e
          } else if (!triple && c == '"') { asLiteral(i, i + 1); end = i + 1 }
          else if (!triple && c == '\n') {
            problem(at, "string literal not closed on its line"); end = i
          } else if (c == '\\' && (!triple || isJava)) {
            val e = if (i + 1 < n && (triple || next != '\n')) i + 2 else i + 1
            asLiteral(i, e); i = e
          } else if (interp && c == '$' && (next == '$' || next == '"')) {
            asLiteral(i, i + 2); i += 2
          } else if (interp && c == '$' && next == '{') {
            asLiteral(i, i + 2)
            if (depth >= MaxNesting) {
              problem(i, "string-interpolation nesting too deep"); asCode(i + 2, n); i = n
            } else i = lexCode(i + 2, inSplice = true, depth + 1)
          } else { asLiteral(i, i + 1); i += 1 }
        }
      }
      end
    }

    def lexCode(start: Int, inSplice: Boolean, depth: Int): Int = {
      var i = start
      var braces = 0
      var end = -1
      while (end < 0) {
        if (i >= n) {
          if (inSplice) problem(start, "unterminated ${ splice")
          end = n
        } else {
          val c = src.charAt(i)
          if (src.startsWith("//", i)) {
            val e = src.indexOf('\n', i) match { case -1 => n; case x => x }
            asComment(i, e); i = e
          } else if (src.startsWith("/*", i)) i = blockComment(i)
          else if (c == '"') i = stringLiteral(i, depth)
          else if (c == '\'') i = charLiteral(i)
          else if (c == '`' && !isJava) {
            val close = src.indexOf('`', i + 1)
            val nl = src.indexOf('\n', i + 1)
            val e = if (close > i && (nl < 0 || close < nl)) close + 1 else i + 1
            asCode(i, e); i = e
          } else if (inSplice && c == '{') { braces += 1; asCode(i, i + 1); i += 1 }
          else if (inSplice && c == '}') {
            asCode(i, i + 1); i += 1
            if (braces == 0) end = i else braces -= 1
          } else { asCode(i, i + 1); i += 1 }
        }
      }
      end
    }

    lexCode(0, inSplice = false, depth = 0)
    Lexed(codeOut.toString, bareOut.toString, problems.toList)
  }

  /** Index of the last token of `segs` matched as a dotted run starting at `k`, if any. */
  private def chainAt(t: Vector[Tok], k: Int, segs: Seq[String]): Option[Int] = {
    var idx = k
    var s = 0
    var ok = segs.nonEmpty
    while (ok && s < segs.size) {
      if (s > 0) {
        if (idx < t.size && t(idx).text == "." && !t(idx).ident) idx += 1 else ok = false
      }
      if (ok) {
        if (idx < t.size && t(idx).ident && t(idx).text == segs(s)) idx += 1 else ok = false
      }
      s += 1
    }
    if (ok) Some(idx - 1) else None
  }

  /** Every rule on one token stream. */
  def tokenHits(t: Vector[Tok]): Seq[Hit] = {
    val out = ListBuffer.empty[Hit]
    for (k <- t.indices if t(k).ident; p <- forbidden) {
      val n = p.size
      val pkg = p.mkString(".")
      val line = t(k).line
      if ((0 to n - 2).exists(i => chainAt(t, k, p.drop(i)).isDefined))
        out += Hit(line, "path", pkg)
      for (i <- 0 until n - 1; j <- i until n - 1) chainAt(t, k, p.slice(i, j + 1)).foreach { e =>
        if (e + 2 < t.size && t(e + 1).text == "." && !t(e + 1).ident) {
          val next = t(e + 2)
          if (next.text == "*" || (next.ident && next.text == "_"))
            out += Hit(line, "wildcard", pkg)
          else if (next.text == "{" && !next.ident) {
            val close = t.indexWhere(x => x.text == "}" && !x.ident, e + 3) match {
              case -1 => t.size
              case x => x
            }
            val inner = t.slice(e + 3, close)
            // A `_` right after `as` or `=>` hides a name (`{model as _}`); it is no wildcard.
            def hiding(q: Int): Boolean =
              q > 0 && (inner(q - 1).text == "as" || inner(q - 1).text == "=>")
            if (
              inner.indices.exists { q =>
                val x = inner(q)
                (x.ident && x.text == p(j + 1)) ||
                ((x.ident && x.text == "_") || (!x.ident && x.text == "*")) && !hiding(q)
              }
            ) out += Hit(line, "selector", pkg)
          }
        }
      }
      if (
        t(k).text == "package" &&
        (0 to n - 2).exists(i => chainAt(t, k + 1, p.slice(i, n - 1)).isDefined)
      ) out += Hit(line, "package", pkg)
    }
    out.toList
  }

  /** True when token `k` sits inside import selector braces: `. {` then only selector tokens. */
  private def inSelectorBraces(t: Vector[Tok], k: Int): Boolean = {
    def selectorToken(x: Tok): Boolean =
      x.ident || x.text == "," || x.text == "=>" || x.text == "*"
    var idx = k - 1
    while (idx >= 0 && selectorToken(t(idx))) idx -= 1
    idx >= 1 && t(idx).text == "{" && !t(idx).ident && t(idx - 1).text == "." && !t(idx - 1).ident
  }

  /**
   * Rename rule (CR-P7-02 F-A), on the `bare` stream: a dotted run of a strict ancestor si...sj (j
   * < n) followed by `as`, or by `=>` inside selector braces.
   */
  def renameHits(t: Vector[Tok]): Seq[Hit] = {
    val out = ListBuffer.empty[Hit]
    for (k <- t.indices if t(k).ident; p <- forbidden) {
      val n = p.size
      val ends = for {
        i <- 0 until n - 1
        j <- i until n - 1
        e <- chainAt(t, k, p.slice(i, j + 1))
      } yield e
      val renamed = ends.exists { e =>
        e + 1 < t.size && {
          val nx = t(e + 1)
          (nx.ident && nx.text == "as") || (!nx.ident && nx.text == "=>" && inSelectorBraces(t, k))
        }
      }
      if (renamed) out += Hit(t(k).line, "rename", p.mkString("."))
    }
    out.toList
  }

  /** Backtick-escape rule (U2), on the `bare` stream. */
  def backtickEscapeHits(t: Vector[Tok]): Seq[Hit] =
    t.filter(x => x.ident && x.text.contains('\\'))
      .map(x => Hit(x.line, "backtick-escape", "backslash in a backticked identifier"))

  private val DotThenComment = """\.[ \t]*(?:/\*|//)""".r
  private val CommentThenDot = """\*/[ \t\r\n\f]*\.""".r
  val CommentAtDot = "comment next to a dot"

  /** Comment-at-dot rule (CR-P7-02 F-B), on the raw text; independent of the lexer. */
  def commentAtDotHits(src: String): Seq[Hit] = {
    val lineOf = lineIndex(src)
    val inline = (DotThenComment.findAllMatchIn(src) ++ CommentThenDot.findAllMatchIn(src))
      .map(m => Hit(lineOf(m.start), "comment-at-dot", CommentAtDot))
      .toList
    def opensComment(s: String): Boolean = s.startsWith("//") || s.startsWith("/*")
    val lines = src.split("\n", -1)
    val trailing = lines.indices.flatMap { idx =>
      val l = lines(idx)
      val tr = l.trim
      if (tr.endsWith(".") && !tr.startsWith("*") && !l.contains("//") && !l.contains("/*"))
        lines.indexWhere(_.trim.nonEmpty, idx + 1) match {
          case -1 => None
          case nx =>
            if (opensComment(lines(nx).trim)) Some(Hit(idx + 1, "comment-at-dot", CommentAtDot))
            else None
        }
      else None
    }
    inline ++ trailing
  }

  /**
   * Suspicious-char rule (U1), on the raw bytes: malformed UTF-8 and code points of category Cc
   * (except tab, LF, CR, FF), Cf, Zs (except the ASCII space), Zl, Zp; a BOM at offset 0 is
   * allowed.
   */
  def suspiciousCharHits(src: String): Seq[Hit] = {
    val out = ListBuffer.empty[Hit]
    val n = src.length
    var i = 0
    var line = 1
    def flag(what: String): Unit =
      if (out.size < MaxProblems) out += Hit(line, "suspicious-char", what)
    while (i < n) {
      val b = src.charAt(i).toInt
      if (b == '\n') { line += 1; i += 1 }
      else if (b < 0x80) {
        if ((b < 0x20 || b == 0x7f) && b != '\t' && b != '\r' && b != '\f') flag(f"U+$b%04X")
        i += 1
      } else {
        val len =
          if (b >= 0xc2 && b <= 0xdf) 2
          else if (b >= 0xe0 && b <= 0xef) 3
          else if (b >= 0xf0 && b <= 0xf4) 4
          else 0
        var cp = if (len == 2) b & 0x1f else if (len == 3) b & 0x0f else b & 0x07
        var ok = len > 0 && i + len <= n
        var q = 1
        while (ok && q < len) {
          val c = src.charAt(i + q).toInt
          if ((c & 0xc0) != 0x80) ok = false else cp = (cp << 6) | (c & 0x3f)
          q += 1
        }
        val min = if (len == 3) 0x800 else if (len == 4) 0x10000 else 0x80
        if (ok && (cp < min || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff))) ok = false
        if (!ok) { flag("malformed UTF-8"); i += 1 }
        else {
          val ty = Character.getType(cp)
          val bom = cp == 0xfeff && i == 0
          val bad = ty == Character.CONTROL || ty == Character.FORMAT ||
            ty == Character.SPACE_SEPARATOR || ty == Character.LINE_SEPARATOR ||
            ty == Character.PARAGRAPH_SEPARATOR
          if (bad && !bom) flag(f"U+$cp%04X")
          i += len
        }
      }
    }
    out.toList
  }

  /** Every hit in one source text, sorted and distinct. */
  def scanText(src: String, isJava: Boolean): Seq[Hit] = {
    val escapes =
      if (!isJava) Nil
      else
        src.split("\n", -1).toSeq.zipWithIndex.collect {
          case (l, idx) if l.contains(UnicodeEscapeStart) =>
            Hit(idx + 1, "java-unicode-escape", "unicode escape before tokenizing")
        }
    val lexed = lex(src, isJava)
    val bare = tokens(lexed.bare)
    // CR-P7-04 F-C: `tokens` pairs backticks wherever they sit, so two backticks inside literals in
    // the raw and `code` texts can swallow a path; `bare` has no literal text, so its backticks
    // come from lexer code state only, and splice code stays in it.
    val all =
      tokenHits(tokens(src)) ++ tokenHits(tokens(lexed.code)) ++ tokenHits(bare) ++
        renameHits(bare) ++
        backtickEscapeHits(bare) ++ commentAtDotHits(src) ++ suspiciousCharHits(src) ++
        lexed.problems.map { case (l, what) => Hit(l, "lexer", what) } ++ escapes
    all.distinct.sortBy(h => (h.line, h.rule, h.pkg))
  }

  // --- File system scan -------------------------------------------------------------------------

  final case class Scan(
      files: Seq[String],
      main: Int,
      test: Int,
      other: Int,
      ignored: Int,
      hits: Seq[String],
      refused: Seq[String],
      absent: Boolean
  )

  private def isSource(name: String): Boolean = {
    val l = name.toLowerCase(Locale.ROOT)
    l.endsWith(".scala") || l.endsWith(".java")
  }

  private def readBounded(p: Path, size: Long): Array[Byte] = {
    val ch: SeekableByteChannel =
      Files.newByteChannel(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    try {
      val buf = ByteBuffer.allocate(size.toInt + 1)
      while (buf.hasRemaining && ch.read(buf) >= 0) {}
      if (buf.position() > size) throw new IOException("file grew while reading")
      java.util.Arrays.copyOf(buf.array, buf.position())
    } finally ch.close()
  }

  /** Scans `<base>/<rootRel>`; every path in the result is relative to `base`. */
  def scan(base: Path, rootRel: String): Scan = {
    def rel(p: Path): String = base.relativize(p).toString.replace(File.separatorChar, '/')
    val refused = ListBuffer.empty[String]
    val segments = rootRel.split('/').toSeq
    val ancestors = segments.indices.map(i => base.resolve(segments.take(i + 1).mkString("/")))
    val linked = ancestors.filter(Files.isSymbolicLink)
    linked.foreach(p => refused += s"${rel(p)} (symlink)")
    val root = ancestors.last
    val absent = linked.isEmpty && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    if (linked.nonEmpty || absent)
      return Scan(Nil, 0, 0, 0, 0, Nil, refused.toList.sorted, absent)
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
      return Scan(Nil, 0, 0, 0, 0, Nil, Seq(s"$rootRel (not a directory)"), absent = false)
    val files = ListBuffer.empty[String]
    val hits = ListBuffer.empty[String]
    var main, test, other, ignored, entries = 0
    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path] {
        private def count(): Boolean = {
          entries += 1
          if (entries > MaxEntries) {
            refused += s"$rootRel (more than $MaxEntries entries)"
            false
          } else true
        }
        override def preVisitDirectory(d: Path, a: BasicFileAttributes): FileVisitResult =
          if (count()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
        override def visitFile(f: Path, a: BasicFileAttributes): FileVisitResult = {
          if (!count()) return FileVisitResult.TERMINATE
          val r = rel(f)
          if (a.isSymbolicLink) refused += s"$r (symlink)"
          else if (!a.isRegularFile) refused += s"$r (not a regular file)"
          else if (!isSource(f.getFileName.toString)) ignored += 1
          else if (a.size > MaxFileBytes) refused += s"$r (over $MaxFileBytes bytes)"
          else {
            val text = new String(readBounded(f, a.size), StandardCharsets.ISO_8859_1)
            files += r
            root.relativize(f).getName(0).toString match {
              case "main" => main += 1
              case "test" => test += 1
              case _ => other += 1
            }
            val isJava = f.getFileName.toString.toLowerCase(Locale.ROOT).endsWith(".java")
            scanText(text, isJava).foreach(h => hits += s"$r:${h.line} ${h.rule} ${h.pkg}")
          }
          FileVisitResult.CONTINUE
        }
        override def visitFileFailed(f: Path, e: IOException): FileVisitResult = {
          refused += s"${rel(f)} (unreadable: ${e.getClass.getSimpleName})"
          FileVisitResult.CONTINUE
        }
      }
    )
    Scan(
      files.toList.sorted,
      main,
      test,
      other,
      ignored,
      hits.toList.sorted,
      refused.toList.sorted,
      absent = false
    )
  }

  private def names(xs: Seq[String]): String = if (xs.isEmpty) "none" else xs.mkString(", ")

  def scanLine(s: Scan, rootRel: String): Line = {
    val pass = s.hits.isEmpty && s.refused.isEmpty
    val vacuous =
      if (pass && s.files.isEmpty)
        s" -- PASS is vacuous: no rag source exists to check" +
          (if (s.absent) s" ($rootRel is absent)" else "")
      else ""
    Line(
      pass,
      "rag-source-imports",
      s"${s.files.size} source files (*.scala, *.java) under $rootRel (main ${s.main}, test " +
        s"${s.test}, other ${s.other}; non-source files ignored ${s.ignored})$vacuous; refused " +
        s"${s.refused.size}: ${names(s.refused)}; matches ${s.hits.size}: ${names(s.hits)}"
    )
  }

  private def relTo(base: File, f: File): String =
    base.toPath.toAbsolutePath.normalize
      .relativize(f.toPath.toAbsolutePath.normalize)
      .toString
      .replace(File.separatorChar, '/')

  /** sbt's own view of rag's sources must lie inside the scanned tree. */
  def sbtSourcesLine(
      base: File,
      scanned: Seq[String],
      dirs: Seq[File],
      unmanaged: Seq[File],
      managed: Seq[File]
  ): Line = {
    val dirRels = dirs.map(relTo(base, _)).distinct.sorted
    val outside = dirRels.filterNot(d => d == ScanRoot || d.startsWith(ScanRoot + "/"))
    val srcRels = unmanaged.map(relTo(base, _)).distinct.sorted
    val unscanned = srcRels.filterNot(scanned.toSet)
    val managedRels = managed.map(relTo(base, _)).distinct.sorted
    Line(
      outside.isEmpty && unscanned.isEmpty && managedRels.isEmpty,
      "rag-sbt-source-roots",
      s"rag Compile/Test source directories ${dirRels.size}, outside $ScanRoot " +
        s"${outside.size}: ${names(outside)}; unmanaged sources ${srcRels.size}, not scanned " +
        s"${unscanned.size}: ${names(unscanned)}; managed sources ${managedRels.size}: " +
        names(managedRels)
    )
  }

  // --- Self-test --------------------------------------------------------------------------------

  /** The UTF-8 bytes of one code point, as ISO-8859-1 characters (how a file is decoded). */
  private def utf8Iso(cp: Int): String =
    new String(
      new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8),
      StandardCharsets.ISO_8859_1
    )

  private val TripleQuote = "\"\"\""
  private val QuoteInSplice = """s"${'"'}" + """
  private val Unterminated = "string literal not closed on its line"

  /** BE-P7-03 broken sources: CR-P7-02 F-A, F-B and U1-U3, and the lexer's fail-closed cases. */
  private val brokenSourcesP703: Seq[(String, String, Boolean, Hit)] = Seq(
    (
      "F-A unbraced rename of an ancestor",
      "package scholarchain.rag\nimport scholarchain.storage as st\n" +
        "object A { val x = st.aws.s3.S3ObjectStore }",
      false,
      Hit(2, "rename", "scholarchain.storage.aws")
    ),
    (
      "F-A unbraced rename of org.apache",
      "import org.apache as a\nobject A { def f(p: a.hadoop.fs.Path) = p }",
      false,
      Hit(1, "rename", "org.apache.hadoop")
    ),
    (
      "F-A unbraced rename of the root package",
      "import scholarchain as sc\nobject A { val x = sc.storage.parquet.ParquetRows }",
      false,
      Hit(1, "rename", "scholarchain.storage.parquet")
    ),
    (
      "F-A relative unbraced rename under a chained package",
      "package scholarchain\npackage rag\nimport storage as st\n" +
        "object A { val x = st.aws.s3.S3ObjectStore }",
      false,
      Hit(3, "rename", "scholarchain.storage.aws")
    ),
    (
      "F-A export rename of an ancestor",
      "object A { export scholarchain.storage as st }",
      false,
      Hit(1, "rename", "scholarchain.storage.aws")
    ),
    (
      "F-A selector rename (=>) under another qualifier",
      "import sc.{storage => st}",
      false,
      Hit(1, "rename", "scholarchain.storage.aws")
    ),
    (
      "F-A rename inside an interpolation splice",
      """object A { val q = s"${ { import scholarchain.storage as st; st.aws.X } }" }""",
      false,
      Hit(1, "rename", "scholarchain.storage.aws")
    ),
    (
      "F-B block comment in a path after a quote in a splice (lexer in sync)",
      "object A { val q = " + QuoteInSplice + "scholarchain.storage./**/aws.s3.S3ObjectStore }",
      false,
      Hit(1, "path", "scholarchain.storage.aws")
    ),
    (
      "F-B block comment in a path after a quote in a splice (raw rule)",
      "object A { val q = " + QuoteInSplice + "scholarchain.storage./**/aws.s3.S3ObjectStore }",
      false,
      Hit(1, "comment-at-dot", CommentAtDot)
    ),
    (
      "F-B line comment in a path after a quote in a splice (lexer in sync)",
      "object A { val q = " + QuoteInSplice + "scholarchain.storage.// c\naws.s3.S3ObjectStore }",
      false,
      Hit(1, "path", "scholarchain.storage.aws")
    ),
    (
      "F-B line comment in a path after a quote in a splice (raw rule)",
      "object A { val q = " + QuoteInSplice + "scholarchain.storage.// c\naws.s3.S3ObjectStore }",
      false,
      Hit(1, "comment-at-dot", CommentAtDot)
    ),
    (
      "F-B line comment before a leading dot after a quote in a splice",
      "object A { val q = " + QuoteInSplice + "scholarchain.storage // c\n  .aws.s3.X }",
      false,
      Hit(1, "path", "scholarchain.storage.aws")
    ),
    (
      "F-B trailing dot, then a comment line",
      "object A {\n  val x = scholarchain.storage.\n    // c\n    aws.s3.S3ObjectStore\n}",
      false,
      Hit(2, "comment-at-dot", CommentAtDot)
    ),
    (
      "F-B block comment, then a dot on the next line",
      "object A {\n  val x = scholarchain.storage /* c */\n    .aws.s3.S3ObjectStore\n}",
      false,
      Hit(2, "comment-at-dot", CommentAtDot)
    ),
    (
      "U3 the $\" quote escape",
      """object A { val q = s"$"//"; import scholarchain.storage as st }""",
      false,
      Hit(1, "rename", "scholarchain.storage.aws")
    ),
    (
      "hard keyword before a string is no interpolator",
      """object A { val q = if (true) "a" else"$"; import scholarchain.storage as st }""",
      false,
      Hit(1, "rename", "scholarchain.storage.aws")
    ),
    (
      "triple-quoted interpolation with a nested triple-quoted splice",
      "object A { val q = s" + TripleQuote + "a ${" + TripleQuote + "b" + TripleQuote + "} " +
        TripleQuote + "; import scholarchain.storage as st }",
      false,
      Hit(1, "rename", "scholarchain.storage.aws")
    ),
    (
      "U1 no-break space between a dot and a segment",
      "import scholarchain.storage." + utf8Iso(0xa0) + "aws.s3.S3ObjectStore",
      false,
      Hit(1, "suspicious-char", "U+00A0")
    ),
    (
      "zero width space inside a Java identifier",
      "import org.apache.had" + utf8Iso(0x200b) + "oop.fs.Path;",
      true,
      Hit(1, "suspicious-char", "U+200B")
    ),
    (
      "control character inside a Java identifier",
      "import org.apache.ha" + 1.toChar + "doop.fs.Path;",
      true,
      Hit(1, "suspicious-char", "U+0001")
    ),
    (
      "malformed UTF-8",
      "object A { val s = \"" + 0xff.toChar + "\" }",
      false,
      Hit(1, "suspicious-char", "malformed UTF-8")
    ),
    (
      "U2 unicode escape in a backticked identifier",
      "import scholarchain.storage.`" + UnicodeEscapeStart + "0061ws`.s3.S3ObjectStore",
      false,
      Hit(1, "backtick-escape", "backslash in a backticked identifier")
    ),
    (
      "lexer: string not closed on its line",
      "object A { val s = \"abc\n}",
      false,
      Hit(1, "lexer", Unterminated)
    ),
    (
      "lexer: unterminated block comment",
      "object A\n/* x",
      false,
      Hit(2, "lexer", "unterminated block comment")
    ),
    (
      "lexer: unrecognised character literal",
      "object A { val c = 'ab' }",
      false,
      Hit(1, "lexer", "unrecognised character literal")
    ),
    (
      "lexer: unterminated splice",
      """object A { val s = s"${ (1 + 2)""",
      false,
      Hit(1, "lexer", "unterminated ${ splice")
    ),
    (
      "lexer: interpolation nesting too deep",
      "object A { val s = " + ("s\"${" * (MaxNesting + 8)) + "1" + ("}\"" * (MaxNesting + 8)) +
        " }",
      false,
      Hit(1, "lexer", "string-interpolation nesting too deep")
    )
  )

  /** BE-P7-03 allowed sources: each must produce no hit (false-positive guards). */
  private val allowedSourcesP703: Seq[(String, String, Boolean)] = Seq(
    ("storage model wildcard", "import scholarchain.storage.model.*", false),
    ("braced port and model", "import scholarchain.storage.{port, model}", false),
    ("R3 hidden name", "import scholarchain.storage.{port, model as _}", false),
    ("renamed port package", "import scholarchain.storage.port as p", false),
    (
      "prose with ancestor words in comments and strings",
      "// Treats storage as a port, software as a service, amazon as a host.\n" +
        "/* org as a word, scholarchain as a name */\n" +
        "object A { val p = \"Use storage as evidence; com as a word.\" }",
      false
    ),
    (
      "doc comment sentences and line comments",
      "/**\n * First sentence.\n * Second sentence.\n */\n// Next.\n// More.\nobject A",
      false
    ),
    (
      "line comment before a leading-dot chain",
      "object A {\n  val x = List(1, 2)\n    // keep odd\n    .filter(_ % 2 == 1)\n}",
      false
    ),
    (
      "lambdas with ancestor-named parameters",
      "object A { def f(xs: List[Int]) = xs.map { storage => storage + 1 }.map(org => org) }",
      false
    ),
    (
      "interpolation forms",
      """object A { val n = 1; val s = s"n=$n, $$, ${n + 1} ${"x"} ${'"'} $"q"; """ +
        """val t = f"$n%2d"; val r = raw"a\\b"; val u = s""" + TripleQuote + """a "b" ${""" +
        TripleQuote + "c" + TripleQuote + "} $$ " + TripleQuote + " }",
      false
    ),
    (
      "character literals and Scala 3 quotes",
      """object A { val cs = Seq('a', '\n', '\'', '"', '\\', '{', '""" + utf8Iso(0xe9) +
        """'); def q(using Quotes) = '{ 1 }; def t(using Quotes) = '[Int] }""",
      false
    ),
    (
      "Java char and text block",
      "class A { char c = '\\''; String t = " + TripleQuote + "\n  a \\" + TripleQuote +
        " b\n  " + TripleQuote + "; }",
      true
    ),
    (
      "non-ASCII letters and a leading BOM",
      utf8Iso(0xfeff) + "object A { val n = \"M" + utf8Iso(0xfc) + "ller\" }",
      false
    )
  )

  /** Broken synthetic sources: label, text, java?, expected (line, rule, package). */
  private val brokenSources: Seq[(String, String, Boolean, Hit)] = brokenSourcesP703 ++ Seq(
    (
      "plain import (SDK v2)",
      "import software.amazon.awssdk.services.s3.S3Client",
      false,
      Hit(1, "path", "software.amazon.awssdk")
    ),
    (
      "plain import (SDK v1)",
      "import com.amazonaws.services.s3.AmazonS3",
      false,
      Hit(1, "path", "com.amazonaws")
    ),
    (
      "Scala 3 wildcard import",
      "import scholarchain.storage.aws.s3.*",
      false,
      Hit(1, "path", "scholarchain.storage.aws")
    ),
    (
      "Scala 2 wildcard import",
      "import org.apache.hadoop.conf._",
      false,
      Hit(1, "path", "org.apache.hadoop")
    ),
    (
      "ancestor wildcard import",
      "import scholarchain.storage.*",
      false,
      Hit(1, "wildcard", "scholarchain.storage.aws")
    ),
    (
      "renamed import (=>)",
      "import scholarchain.storage.parquet.{ParquetRows => R}",
      false,
      Hit(1, "path", "scholarchain.storage.parquet")
    ),
    (
      "renamed import (as)",
      "import org.apache.parquet.hadoop.ParquetReader as R",
      false,
      Hit(1, "path", "org.apache.parquet")
    ),
    (
      "renamed ancestor",
      "import scholarchain.{storage => st}\nobject A { val x = st.aws.s3.S3ObjectStore }",
      false,
      Hit(1, "selector", "scholarchain.storage.aws")
    ),
    (
      "braced import",
      "import scholarchain.storage.{aws, port}",
      false,
      Hit(1, "selector", "scholarchain.storage.aws")
    ),
    (
      "braced multi-line import",
      "import org.apache.{\n  parquet,\n  hadoop\n}",
      false,
      Hit(1, "selector", "org.apache.hadoop")
    ),
    (
      "fully qualified term in code",
      "object A {\n  val c = software.amazon.awssdk.services.s3.S3Client.create()\n}",
      false,
      Hit(2, "path", "software.amazon.awssdk")
    ),
    (
      "fully qualified type in code",
      "object A { def f(p: org.apache.hadoop.fs.Path): Unit = () }",
      false,
      Hit(1, "path", "org.apache.hadoop")
    ),
    (
      "Scala 3 export clause",
      "object A {\n  export scholarchain.storage.aws.s3.S3ObjectStore\n}",
      false,
      Hit(2, "path", "scholarchain.storage.aws")
    ),
    (
      "Scala 3 export of an ancestor wildcard",
      "object A { export scholarchain.storage.* }",
      false,
      Hit(1, "wildcard", "scholarchain.storage.parquet")
    ),
    (
      "_root_ import",
      "import _root_.scholarchain.storage.aws.dynamodb.Table",
      false,
      Hit(1, "path", "scholarchain.storage.aws")
    ),
    (
      "_root_ reference in code",
      "object A { val k = classOf[_root_.com.amazonaws.auth.AWSCredentials] }",
      false,
      Hit(1, "path", "com.amazonaws")
    ),
    (
      "backticked segment",
      "import scholarchain.storage.`aws`.s3.S3ObjectStore",
      false,
      Hit(1, "path", "scholarchain.storage.aws")
    ),
    (
      "relative reference after a parent import",
      "import scholarchain.storage\nobject A { val x = storage.aws.s3.S3ObjectStore }",
      false,
      Hit(2, "path", "scholarchain.storage.aws")
    ),
    (
      "chained package clause",
      "package scholarchain\npackage storage\nobject A",
      false,
      Hit(2, "package", "scholarchain.storage.aws")
    ),
    (
      "path split by a comment",
      "import scholarchain.storage./* x */aws.s3.S3ObjectStore",
      false,
      Hit(1, "path", "scholarchain.storage.aws")
    ),
    (
      "path split across lines",
      "object A {\n  val p = org\n    .apache\n    .hadoop\n    .fs.Path\n}",
      false,
      Hit(2, "path", "org.apache.hadoop")
    ),
    (
      "Java import",
      "package scholarchain.rag;\nimport software.amazon.awssdk.core.SdkBytes;",
      true,
      Hit(2, "path", "software.amazon.awssdk")
    ),
    (
      "Java static import",
      "import static org.apache.hadoop.fs.Path.SEPARATOR;",
      true,
      Hit(1, "path", "org.apache.hadoop")
    ),
    (
      "Java unicode escape",
      "import org.apache." + UnicodeEscapeStart + "0068adoop.fs.Path;",
      true,
      Hit(1, "java-unicode-escape", "unicode escape before tokenizing")
    ),
    (
      "string literal (conservative)",
      "object A { val n = \"org.apache.hadoop.fs.Path\" }",
      false,
      Hit(1, "path", "org.apache.hadoop")
    ),
    (
      "F-C fully qualified reference between two backticks in string literals",
      "object A { val a = \"`\"; val b = scholarchain.storage.aws.s3.S3ObjectStore; " +
        "val c = \"`\" }",
      false,
      Hit(1, "path", "scholarchain.storage.aws")
    ),
    (
      "F-C splice reference inside a Markdown code span",
      "object A { val p = s\"Call `${org.apache.hadoop.fs.Path.SEPARATOR}` now\" }",
      false,
      Hit(1, "path", "org.apache.hadoop")
    ),
    (
      "F-C Java reference between two backticks in string literals",
      "String a = \"`\"; org.apache.hadoop.fs.Path p = null; String b = \"`\";",
      true,
      Hit(1, "path", "org.apache.hadoop")
    ),
    (
      "comment mention (conservative)",
      "// backed by scholarchain.storage.aws\nobject A",
      false,
      Hit(1, "path", "scholarchain.storage.aws")
    )
  )

  /** Allowed synthetic sources: label, text, java?; each must produce no hit. */
  private val allowedSources: Seq[(String, String, Boolean)] = allowedSourcesP703 ++ Seq(
    ("port import", "import scholarchain.storage.port.ObjectStore", false),
    (
      "braced and renamed port import",
      "import scholarchain.storage.port.{ObjectStore, VectorIndex as VI}",
      false
    ),
    ("port wildcard", "import scholarchain.storage.port.*", false),
    ("core wildcard", "import scholarchain.core.model.*", false),
    ("other org.apache library", "import org.apache.commons.lang3.StringUtils", false),
    (
      "chained rag package with look-alike names",
      "package scholarchain\npackage rag\n\nimport scholarchain.storage.port.ObjectStore\n" +
        "object Q { val awsRegionLabel = \"aws\"; val hadoopish = 1; val s = \"//\" }",
      false
    ),
    (
      "Java port import",
      "package scholarchain.rag;\nimport scholarchain.storage.port.ObjectStore;",
      true
    ),
    ("empty source", "", false)
  )

  /**
   * Deletes the fixture tree without following symlinks (a link itself is removed, not its target).
   */
  private def deleteTree(root: Path): Unit =
    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path] {
        override def visitFile(f: Path, a: BasicFileAttributes): FileVisitResult = {
          Files.delete(f); FileVisitResult.CONTINUE
        }
        override def postVisitDirectory(d: Path, e: IOException): FileVisitResult = {
          Files.delete(d); FileVisitResult.CONTINUE
        }
      }
    )

  private def write(p: Path, text: String): Unit = {
    Files.createDirectories(p.getParent)
    Files.write(p, text.getBytes(StandardCharsets.UTF_8))
  }

  /** Real-file fixtures under `scratch`: emptiness, a forbidden file, symlinks, absent root. */
  private def walkerProblems(scratch: Path): (Seq[String], Int) = {
    val problems = ListBuffer.empty[String]
    val bad = "package scholarchain.rag\n\nimport scholarchain.storage.aws.s3.S3ObjectStore\n"
    val ok = "package scholarchain.rag\n\nimport scholarchain.storage.port.ObjectStore\n"
    def src(base: Path) = base.resolve(ScanRoot)

    val empty = scratch.resolve("empty")
    write(src(empty).resolve("main/scala/.gitkeep"), "")
    write(src(empty).resolve("test/scala/.gitkeep"), "")
    val e = scan(empty, ScanRoot)
    val el = scanLine(e, ScanRoot)
    if (!(el.pass && e.files.isEmpty && e.ignored == 2 && el.detail.contains("vacuous")))
      problems += s"empty tree not a vacuous PASS: ${el.render}"

    val forbiddenTree = scratch.resolve("forbidden")
    write(src(forbiddenTree).resolve("main/scala/Bad.scala"), bad)
    write(src(forbiddenTree).resolve("test/scala/Ok.scala"), ok)
    val f = scan(forbiddenTree, ScanRoot)
    val expected =
      Seq(s"$ScanRoot/main/scala/Bad.scala:3 path scholarchain.storage.aws")
    if (f.hits != expected || f.files.size != 2 || scanLine(f, ScanRoot).pass)
      problems += s"forbidden file not reported exactly: ${scanLine(f, ScanRoot).render}"

    val linkTree = scratch.resolve("links")
    write(linkTree.resolve("outside/Outside.scala"), bad)
    write(src(linkTree).resolve("main/scala/Ok.scala"), ok)
    Files.createSymbolicLink(
      src(linkTree).resolve("main/scala/Escape.scala"),
      linkTree.resolve("outside/Outside.scala")
    )
    Files.createDirectories(src(linkTree).resolve("test"))
    Files.createSymbolicLink(src(linkTree).resolve("test/linked"), linkTree.resolve("outside"))
    val l = scan(linkTree, ScanRoot)
    val expectedRefused = Seq(
      s"$ScanRoot/main/scala/Escape.scala (symlink)",
      s"$ScanRoot/test/linked (symlink)"
    )
    if (l.refused != expectedRefused || l.hits.nonEmpty || scanLine(l, ScanRoot).pass)
      problems += s"symlinks not refused, or followed: ${scanLine(l, ScanRoot).render}"

    val linkedRoot = scratch.resolve("linked-root")
    write(linkedRoot.resolve("elsewhere/main/scala/Bad.scala"), bad)
    Files.createDirectories(linkedRoot.resolve("modules/rag"))
    Files.createSymbolicLink(src(linkedRoot), linkedRoot.resolve("elsewhere"))
    val r = scan(linkedRoot, ScanRoot)
    if (r.refused != Seq(s"$ScanRoot (symlink)") || r.files.nonEmpty || scanLine(r, ScanRoot).pass)
      problems += s"symlinked source root not refused: ${scanLine(r, ScanRoot).render}"

    val absentTree = scratch.resolve("absent")
    Files.createDirectories(absentTree.resolve("modules/rag"))
    val a = scan(absentTree, ScanRoot)
    val al = scanLine(a, ScanRoot)
    if (!(a.absent && al.pass && al.detail.contains("is absent")))
      problems += s"absent source root not a vacuous PASS: ${al.render}"

    val outsideRoots = sbtSourcesLine(
      forbiddenTree.toFile,
      f.files,
      Seq(forbiddenTree.resolve("modules/rag/extra").toFile),
      Seq(forbiddenTree.resolve("modules/rag/extra/X.scala").toFile),
      Seq(forbiddenTree.resolve("modules/rag/target/gen/G.scala").toFile)
    )
    if (outsideRoots.pass) problems += s"sbt source roots outside the scan not reported"
    (problems.toList, 6)
  }

  /** Self-test failures (empty when all pass) and its counts (broken, allowed, fixtures). */
  def selfTest(scratchParent: Path): (Seq[String], Int, Int, Int) = {
    val problems = ListBuffer.empty[String]
    brokenSources.foreach { case (label, text, isJava, want) =>
      val got = scanText(text, isJava)
      if (!got.contains(want))
        problems += s"broken source '$label' not detected by rule ${want.rule} for ${want.pkg} " +
          s"at line ${want.line} (got ${got.mkString(", ")})"
    }
    allowedSources.foreach { case (label, text, isJava) =>
      val got = scanText(text, isJava)
      if (got.nonEmpty) problems += s"allowed source '$label' failed: ${got.mkString(", ")}"
    }
    // CR-P7-02 R2: a symlinked scratch parent (or its parent, the root target/) is refused before
    // anything is written, so the fixture tree cannot land outside target/.
    val linkedParents =
      Seq(Option(scratchParent.getParent), Some(scratchParent)).flatten
        .filter(p => Files.isSymbolicLink(p))
    val (walker, fixtures) =
      if (linkedParents.nonEmpty)
        (
          Seq(
            "self-test scratch parent is a symlink (refused, nothing written): " +
              linkedParents.mkString(", ")
          ),
          6
        )
      else {
        Files.createDirectories(scratchParent)
        val scratch = Files.createTempDirectory(scratchParent, "selftest-")
        try walkerProblems(scratch)
        catch {
          case e: Exception =>
            (Seq(s"file-system fixture failed: ${e.getClass.getSimpleName}: ${e.getMessage}"), 6)
        } finally deleteTree(scratch)
      }
    (problems.toList ++ walker, brokenSources.size, allowedSources.size, fixtures)
  }

  /** Self-test, the scan, and sbt's source roots; one line each; fails on any FAIL. */
  def run(
      base: File,
      scratchParent: File,
      dirs: Seq[File],
      unmanaged: Seq[File],
      managed: Seq[File],
      log: Logger
  ): Unit = {
    val (self, broken, allowed, fixtures) = selfTest(scratchParent.toPath)
    val selfLine = Line(
      self.isEmpty,
      "self-test",
      if (self.isEmpty)
        s"$broken broken synthetic sources each detected by the intended rule and package, " +
          s"$allowed allowed synthetic sources passed, $fixtures file-system fixtures behaved " +
          "(vacuous empty tree, forbidden file at path:line, symlinks refused and not followed, " +
          "symlinked root refused, absent root, sbt roots outside the scan)"
      else self.mkString("; ")
    )
    val scanned = scan(base.toPath.toAbsolutePath.normalize, ScanRoot)
    val lines = Seq(
      selfLine,
      scanLine(scanned, ScanRoot),
      sbtSourcesLine(base, scanned.files, dirs, unmanaged, managed)
    )
    lines.foreach(l => if (l.pass) log.info(l.render) else log.error(l.render))
    val failed = lines.filterNot(_.pass)
    if (failed.nonEmpty)
      throw new MessageOnlyException(
        s"$Task: ${failed.size} check(s) failed: ${failed.map(_.id).mkString(", ")}"
      )
    log.info(s"$Task: OK, ${lines.size} checks passed")
  }
}
