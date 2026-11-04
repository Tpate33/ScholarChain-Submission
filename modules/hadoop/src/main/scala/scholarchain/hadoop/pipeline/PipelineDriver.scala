package scholarchain.hadoop.pipeline

import java.nio.charset.StandardCharsets

import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.util.{Tool, ToolRunner}

import scholarchain.core.{Json, RunManifest}

/** Parsed command line of [[PipelineDriver]]. */
final case class DriverArgs(
    manifest: Path,
    curatedRoot: Path,
    stages: Vector[Stage],
    inputs: PipelineInputs
)

object DriverArgs {
  val Usage: String =
    """usage: hadoop jar scholarchain-hadoop-jobs-<version>.jar scholarchain.hadoop.pipeline.PipelineDriver
      |  [generic Hadoop options, e.g. -D mapreduce.job.queuename=...]
      |  --manifest <uri>            run manifest JSON (scholarchain.run-manifest.v1, stage hadoop.pipeline)
      |  --curated-root <uri>        curated zone root; outputs go to <root>/hadoop/snapshot=<dblp>/run=<id>/
      |  --stages <list>             comma-separated subset of normalize,exact-ids,teams,blocking,candidates
      |  --dblp <uri>                DBLP XML file or directory (repeatable)
      |  --openalex-works <uri>      OpenAlex works JSONL (repeatable)
      |  --openalex-authors <uri>    OpenAlex authors JSONL (repeatable)
      |  --openalex-institutions <uri>  OpenAlex institutions JSONL (repeatable)
      |  --crossref-works <uri>      Crossref works JSONL (repeatable)
      |  --orcid <uri>               directory of ORCID summary XML files (repeatable)
      |""".stripMargin

  private val InputFlags = Set(
    "--dblp",
    "--openalex-works",
    "--openalex-authors",
    "--openalex-institutions",
    "--crossref-works",
    "--orcid"
  )

  def parse(args: Vector[String]): Either[String, DriverArgs] = {
    if (args.size % 2 != 0) Left("every option needs exactly one value")
    else {
      val pairs = args.grouped(2).map(p => p(0) -> p(1)).toVector
      val unknown = pairs
        .map(_._1)
        .filterNot(f => InputFlags(f) || Set("--manifest", "--curated-root", "--stages")(f))
      def single(flag: String): Either[String, String] =
        pairs.filter(_._1 == flag).map(_._2) match {
          case Vector(v) => Right(v)
          case Vector() => Left(s"$flag is required")
          case _ => Left(s"$flag is given more than once")
        }
      def many(flag: String): Vector[Path] = pairs.filter(_._1 == flag).map(p => new Path(p._2))
      if (unknown.nonEmpty) Left(s"unknown option(s): ${unknown.distinct.mkString(", ")}")
      else
        for {
          m <- single("--manifest")
          root <- single("--curated-root")
          s <- single("--stages").flatMap(Stage.parse)
        } yield DriverArgs(
          new Path(m),
          new Path(root),
          s,
          PipelineInputs(
            many("--dblp"),
            many("--openalex-works"),
            many("--openalex-authors"),
            many("--openalex-institutions"),
            many("--crossref-works"),
            many("--orcid")
          )
        )
    }
  }
}

/**
 * EMR / `hadoop jar` entry point. Exit codes: 0 success (every requested stage ran or was already
 * complete), 1 a stage failed or refused to overwrite, 2 invalid arguments or manifest.
 */
final class PipelineDriver extends Configured with Tool {
  override def run(args: Array[String]): Int =
    DriverArgs.parse(args.toVector) match {
      case Left(err) =>
        System.err.println(s"$err\n${DriverArgs.Usage}")
        2
      case Right(a) =>
        val conf = Option(getConf).getOrElse(new Configuration())
        val fs = a.manifest.getFileSystem(conf)
        val text = {
          val in = fs.open(a.manifest)
          try new String(in.readAllBytes(), StandardCharsets.UTF_8)
          finally in.close()
        }
        Json.decode[RunManifest](text) match {
          case Left(errors) =>
            System.err.println(
              s"invalid run manifest ${a.manifest}: ${errors.map(_.message).mkString("; ")}"
            )
            2
          case Right(manifest) =>
            Pipeline.run(conf, manifest, a.curatedRoot, a.inputs, a.stages) match {
              case Left(errors) =>
                errors.foreach(e => System.err.println(e))
                1
              case Right(reports) =>
                reports.foreach { r =>
                  println(s"stage ${r.stage}: ${r.status} -> ${r.outputDir}")
                  r.counters.foreach { case (k, v) => println(s"  $k=$v") }
                  r.stats.foreach { case (k, s) => println(s"  stat $k=${s.value} (${s.id})") }
                }
                0
            }
        }
    }
}

object PipelineDriver {
  def main(args: Array[String]): Unit =
    System.exit(ToolRunner.run(new Configuration(), new PipelineDriver, args))
}
