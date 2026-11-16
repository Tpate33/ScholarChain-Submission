package scholarchain.entityresolution.grouping

import java.nio.file.Path

import scala.util.control.NonFatal

import scholarchain.core.{
  AssignmentValidation,
  AuthorshipRecordV2,
  Json,
  PublicationTeam,
  RecordContract,
  ResearcherAssignment,
  RunManifest,
  SchemaVersions,
  WorkAssignment
}
import scholarchain.entityresolution.decisions.{DecisionExport, PublishResult}

/** The records of one verified, published grouping (assignment) run. */
final case class AssignmentRun(
    manifest: RunManifest,
    runId: String,
    researchers: Vector[ResearcherAssignment],
    works: Vector[WorkAssignment],
    authorships: Vector[AuthorshipRecordV2],
    teams: Vector[PublicationTeam]
)

/**
 * Publication and verified reading of grouping runs (ER-P6-05), under the assignment run root:
 * {{{
 * <assignmentRunRoot>/run=<grouping run ID hex>/
 *   assignments/researcher/part-00000.jsonl   ResearcherAssignment (researcher-assignment.v1), one per member
 *   assignments/work/part-00000.jsonl         WorkAssignment (work-assignment.v1), one per work
 *   authorships/part-00000.jsonl              AuthorshipRecordV2 (authorship-record.v2)
 *   teams/part-00000.jsonl                    PublicationTeam (publication-team.v1), counted works only
 *   _run-manifest.json                        the grouping run manifest, one LF-terminated row
 *   _parts.json                               core PartManifest (part-manifest.v1, contract C5)
 *   _SUCCESS                                  empty; written last
 * }}}
 * Write order, staging, atomic rename, no overwrite and verification are those of [[RunDirectory]]
 * (the same rules as the decision export). Every part is written in the record order of the batch
 * (sorted by key), so the same inputs give the same bytes.
 *
 * Consumers (Spark among them) must read the teams of THIS run, never the Hadoop TeamJob teams
 * (which carry no researcher IDs), and compute graph metrics from counted works only
 * (`graphEligibility == counted`, or the teams, which exist only for counted works).
 */
object GroupingExport {

  val ResearcherPart: String = "assignments/researcher/part-00000.jsonl"
  val WorkPart: String = "assignments/work/part-00000.jsonl"
  val AuthorshipPart: String = "authorships/part-00000.jsonl"
  val TeamPart: String = "teams/part-00000.jsonl"

  /** The layout of a grouping run directory. */
  val Layout: RunLayout = RunLayout(
    Grouping.Stage,
    Vector(
      ResearcherPart -> SchemaVersions.ResearcherAssignment,
      WorkPart -> SchemaVersions.WorkAssignment,
      AuthorshipPart -> SchemaVersions.AuthorshipRecordV2,
      TeamPart -> SchemaVersions.PublicationTeam
    )
  )

  /** JSON Lines of records, each validated and encoded by the core codec. */
  def toJsonl[T](rs: Vector[T])(using c: RecordContract[T]): Either[Vector[String], String] = {
    val encoded = rs.map(Json.encode(_))
    val bad = encoded.zipWithIndex.collect { case (Left(es), i) =>
      s"${c.recordType}[$i]: ${es.map(_.message).mkString("; ")}"
    }
    if (bad.nonEmpty) Left(bad)
    else Right(encoded.collect { case Right(l) => l + "\n" }.mkString)
  }

  /**
   * Publishes a batch under `outputRoot` all-or-nothing ([[RunDirectory.publish]]). The staged
   * directory is read back under its final name with the reader of [[readValidated]] and must equal
   * the batch ([[compareWithBatch]]) before the rename (ER-P6-07); otherwise the result is
   * [[PublishResult.Unverified]] and the artifacts are preserved.
   */
  def publishRun(
      outputRoot: Path,
      b: GroupingBatch,
      writer: DecisionExport.FileWriter = DecisionExport.DefaultFileWriter
  ): PublishResult = {
    val texts = for {
      r <- toJsonl(b.researchers)
      w <- toJsonl(b.works)
      a <- toJsonl(b.authorships)
      t <- toJsonl(b.teams)
    } yield Map(ResearcherPart -> r, WorkPart -> w, AuthorshipPart -> a, TeamPart -> t)
    texts match {
      case Left(es) => PublishResult.Refused("nothing published: " + es.mkString("; "))
      case Right(ts) =>
        RunDirectory.publish(
          outputRoot,
          Layout,
          b.runId,
          b.manifest,
          ts,
          writer,
          (dir, name) => validatedAs(dir, name).flatMap(compareWithBatch(b, _))
        )
    }
  }

  /**
   * [[publishRun]] as an Either: Right with the published (staged-verified) directory; Left with
   * the reason otherwise (a refusal, or a staged verification failure naming the preserved
   * directory).
   */
  def publish(
      outputRoot: Path,
      b: GroupingBatch,
      writer: DecisionExport.FileWriter = DecisionExport.DefaultFileWriter
  ): Either[String, Path] =
    publishRun(outputRoot, b, writer) match {
      case PublishResult.Published(dir, _) => Right(dir)
      case PublishResult.Refused(why) => Left(why)
      case PublishResult.Unverified(_, why) => Left(why)
    }

  /**
   * Why the run read back differs from the batch `b` (run ID, manifest, or any record), or Right
   * (the staged and the post-publication comparison, ER-P6-06 and ER-P6-07).
   */
  def compareWithBatch(b: GroupingBatch, r: AssignmentRun): Either[String, Unit] =
    Either.cond(
      r.runId == b.runId && r.manifest == b.manifest && r.researchers == b.researchers &&
        r.works == b.works && r.authorships == b.authorships && r.teams == b.teams,
      (),
      s"the run read back (researcher=${r.researchers.size} work=${r.works.size} " +
        s"authorship=${r.authorships.size} team=${r.teams.size}) differs from the batch " +
        s"(researcher=${b.researchers.size} work=${b.works.size} " +
        s"authorship=${b.authorships.size} team=${b.teams.size})"
    )

  /** The verified published grouping runs below `root` (integrity only), sorted. */
  def publishedRuns(root: Path): Vector[Path] = RunDirectory.publishedRuns(root, Layout)

  private def decodePart[T](
      v: VerifiedRunDirectory,
      dir: Path,
      rel: String
  )(using c: RecordContract[T]): Either[String, Vector[T]] = {
    val file = s"$dir/$rel"
    RunDirectory.rows(v.parts(rel), file).flatMap { ls =>
      val decoded = ls.map(Json.decode[T](_))
      decoded.zipWithIndex.collectFirst { case (Left(es), i) => (i, es) } match {
        case Some((i, es)) => Left(s"$file: row ${i + 1}: ${es.map(_.message).mkString("; ")}")
        case None =>
          val got = decoded.collect { case Right(t) => t }
          got.zipWithIndex.find { case (t, _) => c.provenanceOf(t).runId != v.runId } match {
            case Some((_, i)) => Left(s"$file: row ${i + 1} is not a record of run ${v.runId}")
            case None => Right(got)
          }
      }
    }
  }

  /**
   * Reads a published grouping run with the verification of the decision export: refused unless the
   * directory passes [[RunDirectory.verified]] (layout, no symbolic link, the exact file set, row
   * counts, byte counts, SHA-256 and final LF of `_parts.json`, the run ID, a run manifest of stage
   * [[Grouping.Stage]] naming the directory); then every row decodes with the core codec (an empty
   * row is refused) and every record carries the run ID. Dataset rules are checked by
   * [[readValidated]]. A directory holding the `_UNVERIFIED` marker, or not named `run=<hex>`, is
   * refused (ER-P6-07).
   */
  def readPublished(dir: Path): Either[String, AssignmentRun] =
    publishedAs(dir, RunDirectory.nameOf(dir))

  /** [[readPublished]] of `dir` as if it were named `name` (the staged verification). */
  private def publishedAs(dir: Path, name: String): Either[String, AssignmentRun] =
    try
      RunDirectory.verified(dir, Layout, keepParts = true, name).flatMap { v =>
        for {
          rs <- decodePart[ResearcherAssignment](v, dir, ResearcherPart)
          ws <- decodePart[WorkAssignment](v, dir, WorkPart)
          as <- decodePart[AuthorshipRecordV2](v, dir, AuthorshipPart)
          ts <- decodePart[PublicationTeam](v, dir, TeamPart)
        } yield AssignmentRun(v.manifest, v.runId, rs, ws, as, ts)
      }
    catch {
      case NonFatal(e) => Left(s"cannot read $dir: ${e.getClass.getName}: ${e.getMessage}")
    }

  /**
   * [[readPublished]], then the core dataset validators that need no other input (researchers,
   * works, run, authorships, teams), and the manifest's decision run and grouping policy on every
   * assignment. The checks against decisions and curated data ran before publication.
   */
  def readValidated(dir: Path): Either[String, AssignmentRun] =
    validatedAs(dir, RunDirectory.nameOf(dir))

  /** [[readValidated]] of `dir` as if it were named `name` (the staged verification). */
  private def validatedAs(dir: Path, name: String): Either[String, AssignmentRun] =
    publishedAs(dir, name).flatMap { run =>
      val records = AssignmentValidation.checkResearchers(run.researchers) ++
        AssignmentValidation.checkWorks(run.works)
      val errors =
        if (records.nonEmpty) records.map(_.message)
        else
          (AssignmentValidation.checkRun(run.researchers, run.works) ++
            AssignmentValidation.checkAuthorships(run.authorships, run.researchers, run.works) ++
            AssignmentValidation.checkTeams(run.teams, run.researchers, run.works)).map(_.message)
      val params = run.manifest.parameters
      val decisionRun = params.get(Grouping.Params.DecisionRunId)
      val policy = params.get(Grouping.Params.GroupingPolicyVersion)
      val manifest =
        (run.researchers.map(r => (r.decisionRunId, r.groupingPolicyVersion)) ++
          run.works.map(w => (w.decisionRunId, w.groupingPolicyVersion))).distinct
          .filterNot { case (d, g) => decisionRun.contains(d) && policy.contains(g) }
          .map { case (d, g) =>
            s"assignments name decision run $d and grouping policy $g; the manifest names " +
              s"${decisionRun.getOrElse("(none)")} and ${policy.getOrElse("(none)")}"
          }
      if (errors.isEmpty && manifest.isEmpty) Right(run)
      else Left(s"$dir is not a valid grouping run: " + (errors ++ manifest).mkString("; "))
    }
}
