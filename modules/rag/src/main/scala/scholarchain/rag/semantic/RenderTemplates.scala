package scholarchain.rag.semantic

import scholarchain.core.{DocumentType, IdKind, StableId, TimeWindow}

/**
 * One parsed, validated context document ([[RenderTemplates.parse]]). Only the fields rag uses: the
 * provenance lines (input, run, source part, record schema) and the identity lines are validated
 * for shape and then dropped; they never reach an excerpt.
 *
 * @param facts
 *   (name, value) of every fact line, in document order (values are untrusted data)
 */
final case class ContextDocument(
    documentType: DocumentType,
    subjectId: String,
    window: Option[TimeWindow],
    facts: Vector[(String, String)]
)

/**
 * RE-P8-01 item 1, RE-P8-02 (CR-P8-04 findings 1 and 6): the template contract of the embedded
 * context documents.
 *
 * The rendering itself is done by storage's loader (`scholarchain.context-document.v2`); rag does
 * NOT import the loader (CLAUDE.md: rag depends on storage ports and their data types only). What
 * rag owns is the template contract [[RenderTemplates.Version]]:
 *
 *   - which document types are rendered and under which template name (researcher profile,
 *     community profile, collaboration path, representative-paper summary, topic summary);
 *     `work-abstract` has no template (free text, chunked by [[ChunkingPolicy]]);
 *   - the exact context-document version it binds ([[BoundContextVersion]]) and that version's
 *     layout, validated here by rag's own parser ([[parse]]), line by line:
 *     {{{
 *     scholarchain.context-document.v2
 *     notice: <NoticeLine text, exactly>
 *     documentType: <tag of the expected document type>
 *     subjectId: <stable ID>
 *     window: <yyyy>-<yyyy>            (present exactly for the windowed types)
 *     inputId: <suminput: stable ID>
 *     runId: <token>
 *     sourcePath: <token>
 *     recordSchemaVersion: <token>
 *     identity: ... and the five identity.* lines (all six, in order, or none)
 *     facts:
 *     - "<name>": "<value>"            (JSON string literals, exactly as upickle writes them)
 *       evidence: <kind:id, ...>       (at most one, directly after its fact line)
 *     }}}
 *     ending with one newline. Any other first line (a v1 or v3 header, a trailing space, a CR, a
 *     missing line, a line out of order, an extra line) refuses the document. A storage renderer
 *     change therefore fails closed: every document of a new layout or version is refused until rag
 *     publishes a new template version. Drift between this parser and storage's renderer is caught
 *     in test scope (RenderTemplatesSuite renders through storage's loader and parses the result).
 *
 * Every value in a rendered document is a JSON string literal, so dataset text cannot forge a line.
 * A rendered document is data, never instructions.
 */
object RenderTemplates {

  /** The rag template contract version. */
  val Version = "scholarchain.rag.render-templates.v1"

  /** The context-document version this template version binds (exactly). */
  val BoundContextVersion = "scholarchain.context-document.v2"

  /** The fixed second line of a bound document (storage's renderer writes exactly this). */
  val NoticeLine =
    "notice: deterministic rendering of verified Spark summary-input facts; not generated text"

  /** One template: a name and the document types rendered with it. */
  enum Template(val name: String, val documentTypes: Vector[DocumentType]) {
    case ResearcherProfile
        extends Template("researcher-profile", Vector(DocumentType.ResearcherProfile))
    case CommunityProfile
        extends Template("community-profile", Vector(DocumentType.CommunitySummary))
    case CollaborationPath
        extends Template(
          "collaboration-path",
          Vector(DocumentType.PathSummary, DocumentType.CollaborationSummary)
        )
    case RepresentativePaper
        extends Template("representative-paper", Vector(DocumentType.WorkSummary))
    case TopicSummary extends Template("topic-summary", Vector(DocumentType.TopicSummary))
  }

  /**
   * The template of a document type. `work-abstract` has none: abstracts are free text, chunked by
   * [[ChunkingPolicy]], not rendered.
   */
  def templateFor(d: DocumentType): Option[Template] =
    Template.values.find(_.documentTypes.contains(d))

  /**
   * The document types whose documents carry a `window:` line (semantic-summary inputs, which are
   * always windowed); the entity summaries (work, topic) never do.
   */
  val WindowedTypes: Set[DocumentType] = Set(
    DocumentType.ResearcherProfile,
    DocumentType.CommunitySummary,
    DocumentType.PathSummary,
    DocumentType.CollaborationSummary
  )

  /** The six identity line prefixes, in order. */
  val IdentityPrefixes: Vector[String] = Vector(
    "identity: ",
    "identity.status: ",
    "identity.anchorNamespace: ",
    "identity.memberCount: ",
    "identity.anchorCollisionCodes: ",
    "identity.lowestDecisionConfidence: "
  )

  private val Year4 = "^([0-9]{4})-([0-9]{4})$".r

  private def plain(v: String): Boolean =
    v.nonEmpty && !v.exists(c => c.isWhitespace || c.isControl)

  /** Why `text` is not a document of this template version for `documentType`, or None. */
  def problem(text: String, documentType: DocumentType): Option[String] =
    parse(text, documentType).left.toOption

  /** The validated document, or why `text` is not one of this template version. */
  def parse(text: String, documentType: DocumentType): Either[String, ContextDocument] =
    if (text == null) Left("document: missing")
    else if (documentType == null || templateFor(documentType).isEmpty)
      Left(s"document: no template for ${Option(documentType).map(_.tag).orNull}")
    else if (!text.endsWith("\n")) Left("document: does not end with one newline")
    else if (text.exists(c => c.isControl && c != '\n'))
      Left("document: holds a control character (CR, tab or other)")
    else {
      val lines = text.split("\n", -1).toVector.dropRight(1)
      var i = 0
      def at: Option[String] = lines.lift(i)
      def exact(expected: String, what: String): Either[String, Unit] =
        if (at.contains(expected)) { i += 1; Right(()) }
        else Left(s"document: line ${i + 1} is not $what")
      def field(prefix: String, ok: String => Boolean): Either[String, String] =
        at match {
          case Some(l) if l.startsWith(prefix) && ok(l.drop(prefix.length)) =>
            i += 1; Right(l.drop(prefix.length))
          case _ => Left(s"document: line ${i + 1} is not a valid ${prefix.trim} line")
        }
      for {
        _ <- exact(BoundContextVersion, s"the version header $BoundContextVersion")
        _ <- exact(NoticeLine, "the fixed notice line")
        _ <- exact(s"documentType: ${documentType.tag}", s"documentType: ${documentType.tag}")
        subject <- field(
          "subjectId: ",
          v => plain(v) && StableId.validateAny(v, "subjectId").isRight
        )
        window <-
          if (WindowedTypes.contains(documentType))
            field("window: ", v => windowOf(v).isDefined).map(windowOf)
          else if (at.exists(_.startsWith("window: ")))
            Left(s"document: a ${documentType.tag} document has no window line")
          else Right(None)
        _ <- field(
          "inputId: ",
          v => StableId.validate(IdKind.SummaryInput, v, "inputId").isRight
        )
        _ <- field("runId: ", plain)
        _ <- field("sourcePath: ", plain)
        _ <- field("recordSchemaVersion: ", plain)
        _ <-
          if (at.exists(_.startsWith(IdentityPrefixes.head)))
            IdentityPrefixes.foldLeft[Either[String, Unit]](Right(())) { (acc, p) =>
              acc.flatMap(_ => field(p, _.nonEmpty).map(_ => ()))
            }
          else Right(())
        _ <- exact("facts:", "facts:")
        facts <- body(lines.drop(i), i)
      } yield ContextDocument(documentType, subject, window, facts)
    }

  /** `yyyy-yyyy` with four-digit years that form a core [[TimeWindow]]. */
  def windowOf(v: String): Option[TimeWindow] =
    v match {
      case Year4(s, e) => TimeWindow.of(s.toInt, e.toInt).toOption
      case _ => None
    }

  private def body(rest: Vector[String], offset: Int): Either[String, Vector[(String, String)]] = {
    var out = Vector.empty[(String, String)]
    var lastWasFact = false
    var problem: Option[String] = None
    rest.zipWithIndex.foreach { case (l, j) =>
      if (problem.isEmpty) {
        if (l.startsWith("- ")) {
          factLine(l) match {
            case Some(f) => out :+= f; lastWasFact = true
            case None => problem = Some(s"document: line ${offset + j + 1} is not a fact line")
          }
        } else if (l.startsWith("  evidence: ") && lastWasFact && l.length > 12) {
          lastWasFact = false
        } else problem = Some(s"document: line ${offset + j + 1} is not a fact or evidence line")
      }
    }
    problem.toLeft(out)
  }

  /**
   * `- "<name>": "<value>"`, accepted only when it is exactly the line upickle writes for that
   * (name, value) pair, so no second spelling of one fact exists.
   */
  private[semantic] def factLine(l: String): Option[(String, String)] =
    try {
      val s = l.drop(2)
      val end = endOfJsonString(s)
      if (end < 0 || !s.startsWith(": ", end + 1)) None
      else {
        val name = ujson.read(s.substring(0, end + 1)).str
        val value = ujson.read(s.substring(end + 3)).str
        val canonical = s"- ${upickle.default.write(name)}: ${upickle.default.write(value)}"
        Option.when(name.nonEmpty && canonical == l)((name, value))
      }
    } catch { case _: Exception => None }

  /** Index of the closing quote of the JSON string that starts at 0, or -1. */
  private def endOfJsonString(s: String): Int =
    if (!s.startsWith("\"")) -1
    else {
      var i = 1
      var end = -1
      while (i < s.length && end < 0) {
        s.charAt(i) match {
          case '\\' => i += 2
          case '"' => end = i
          case _ => i += 1
        }
      }
      end
    }
}
