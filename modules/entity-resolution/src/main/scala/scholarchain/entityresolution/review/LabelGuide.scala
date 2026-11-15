package scholarchain.entityresolution.review

import java.io.InputStream
import java.nio.charset.StandardCharsets

import scala.util.control.NonFatal

/**
 * The versioned labeling guide (human decision 1, 2026-11-06: Option A). The guide text is the
 * class-path resource [[LabelGuide.Resource]]; this object holds its version, the labeling targets,
 * and the exact question texts the blind view shows, and a test checks that the guide states each
 * question verbatim.
 *
 * Work target `publication-stage`: the same publication entity at the same publication stage,
 * revisions within that entity allowed. A preprint against the published article is a non-match.
 * The definition never asks for identical wording. Researcher target `person`: unchanged ("the same
 * person"). There is one question per entity kind; the lifecycle question is deferred to Phase 6.
 */
object LabelGuide {
  val Version: String = "er-label-guide.v1"
  val Resource: String = "/scholarchain/entityresolution/review/er-label-guide.v1.txt"

  /** Labeling target of work pairs (Option A). */
  val WorkTarget: String = "publication-stage"

  /** Labeling target of researcher pairs. */
  val ResearcherTarget: String = "person"

  val ResearcherQuestion: String =
    "Do endpoint A and endpoint B refer to the same person? Answer match, non-match, or unsure."

  val WorkQuestion: String =
    "Do endpoint A and endpoint B describe the same publication entity at the same publication " +
      "stage (revisions within that entity count as the same)? Answer match, non-match, or unsure."

  /** The labeling target of an entity kind (`researcher` or `work`), if any. */
  def target(entityKind: String): Option[String] = entityKind match {
    case "researcher" => Some(ResearcherTarget)
    case "work" => Some(WorkTarget)
    case _ => None
  }

  /** The question of an entity kind; a work question for anything that is not a researcher. */
  def question(entityKind: String): String =
    if (entityKind == "researcher") ResearcherQuestion else WorkQuestion

  /** The guide text (UTF-8). */
  def text(): Either[String, String] = {
    val in: InputStream = getClass.getResourceAsStream(Resource)
    if (in == null) Left(s"resource $Resource not found")
    else
      try Right(new String(in.readAllBytes(), StandardCharsets.UTF_8))
      catch { case NonFatal(e) => Left(s"resource $Resource unreadable: ${e.getMessage}") }
      finally in.close()
  }
}
