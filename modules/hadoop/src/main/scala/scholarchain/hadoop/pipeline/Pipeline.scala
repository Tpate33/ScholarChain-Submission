package scholarchain.hadoop.pipeline

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.compress.{CompressionCodec, CompressionCodecFactory}
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, MultipleInputs}
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputCommitter,
  FileOutputFormat,
  LazyOutputFormat,
  MultipleOutputs,
  TextOutputFormat
}
import org.apache.hadoop.mapreduce.{InputFormat, Job, MRJobConfig, Mapper, Reducer}

import scholarchain.core.{Json, RunId, RunManifest, SourceType}
import scholarchain.entityresolution.EntityResolution
import scholarchain.hadoop.dblp.DblpInputFormat
import scholarchain.hadoop.io.{LocatedTextInputFormat, WholeFileInputFormat}
import scholarchain.hadoop.jobs.*
import scholarchain.hadoop.{
  BlockingEntity,
  BlockingSettings,
  CandidateSettings,
  Counters,
  DblpSettings,
  JobParams,
  NamedOutputs,
  ParamError,
  RunContext,
  StableHashPartitioner,
  StatLine,
  TeamSettings
}

/** Pipeline stages in dependency order. */
enum Stage(val name: String) {
  case Normalize extends Stage(Stages.Normalize)
  case ExactIds extends Stage(Stages.ExactIds)
  case Teams extends Stage(Stages.Teams)
  case Blocking extends Stage(Stages.Blocking)
  case Candidates extends Stage(Stages.Candidates)

  /** Stages that must be complete (in the same run) before this one runs. */
  def requires: Vector[Stage] = this match {
    case Normalize => Vector.empty
    case Candidates => Vector(Normalize, Blocking)
    case _ => Vector(Normalize)
  }
}

object Stage {
  def parse(names: String): Either[String, Vector[Stage]] = {
    val parts = names.split(',').toVector.map(_.trim).filter(_.nonEmpty)
    val unknown = parts.filterNot(n => Stage.values.exists(_.name == n))
    if (parts.isEmpty) Left("no stage given")
    else if (unknown.nonEmpty) Left(s"unknown stage(s): ${unknown.mkString(", ")}")
    else Right(Stage.values.toVector.filter(s => parts.contains(s.name)))
  }
}

/** Raw-zone inputs (files or directories) per source entity. */
final case class PipelineInputs(
    dblp: Vector[Path],
    openAlexWorks: Vector[Path],
    openAlexAuthors: Vector[Path],
    openAlexInstitutions: Vector[Path],
    crossrefWorks: Vector[Path],
    orcidRecords: Vector[Path]
) {
  def sources: Vector[(SourceType, Vector[Path])] = Vector(
    SourceType.Dblp -> dblp,
    SourceType.OpenAlex -> (openAlexWorks ++ openAlexAuthors ++ openAlexInstitutions),
    SourceType.Crossref -> crossrefWorks,
    SourceType.Orcid -> orcidRecords
  )
}

/**
 * Result of one stage: `ran`, or `skipped` (already complete for this run ID; counters read back
 * from `_counters.json`).
 */
final case class StageReport(
    stage: String,
    status: String,
    outputDir: String,
    counters: SortedMap[String, Long],
    stats: SortedMap[String, StatLine]
)

/**
 * Output layout of one run (idempotent by run ID and snapshot):
 * {{{
 * <curatedRoot>/hadoop/snapshot=<dblp snapshot>/run=<run ID hex>/
 *   _run-manifest.json
 *   normalize/{works,mentions,profiles,venues,institutions,topics,rejects}/part-[mr]-NNNNN.<ext>
 *   exact-ids/{links,rejects,stats}/...
 *   teams/{teams,pairs,rejects,stats}/...
 *   blocking/round-<r>/{blocks/<entity>,members/<entity>,spill,rejects,stats}/...
 *   candidates/{join/{joined,rejects},pairs/{pairs,rejects,stats},final/{researcher,work,rejects,stats}}/...
 *   <stage>/_counters.json, then <stage>/_SUCCESS (written last, by the driver)
 * }}}
 */
final case class RunLayout(root: Path) {
  def stage(s: Stage): Path = new Path(root, s.name)
  def manifest: Path = new Path(root, "_run-manifest.json")
  def normalized(output: String): Path = new Path(stage(Stage.Normalize), output)
  def works: Path = normalized(NamedOutputs.Works)
  def profiles: Path = normalized(NamedOutputs.Profiles)
  def blockingRound(r: Int): Path = new Path(stage(Stage.Blocking), s"round-$r")
  def candidatesJob(name: String): Path = new Path(stage(Stage.Candidates), name)
}

object RunLayout {
  def of(curatedRoot: Path, manifest: RunManifest, runId: String): Either[String, RunLayout] =
    manifest.inputSnapshots.find(_.source == SourceType.Dblp) match {
      case None => Left("the run manifest has no dblp input snapshot (DBLP is the required seed)")
      case Some(s) =>
        Right(
          RunLayout(
            new Path(
              new Path(new Path(curatedRoot, "hadoop"), s"snapshot=${s.snapshot}"),
              s"run=${runId.stripPrefix("run:")}"
            )
          )
        )
    }
}

/** Builds, runs, and reports the MapReduce job graph. */
object Pipeline {
  val ManifestStage = "hadoop.pipeline"
  val Success = "_SUCCESS"
  val CountersFile = "_counters.json"

  /** Candidate-stage job directories. */
  val JoinJob = "join"
  val PairsJob = "pairs"
  val FinalJob = "final"

  /** Validates the manifest and copies its parameters and run identity into a new Configuration. */
  def configure(
      base: Configuration,
      manifest: RunManifest,
      stages: Vector[Stage],
      inputs: PipelineInputs
  ): Either[Vector[String], (Configuration, String)] = {
    val runId = RunId.of(manifest).left.map(e => Vector(s"run manifest: ${e.message}"))
    runId.flatMap { id =>
      val conf = new Configuration(base)
      val params = manifest.parameters
      val unknown = params.keys.filterNot(JobParams.ManifestKeys.contains).toVector
      val required = (JobParams.Common ++ stages.flatMap(s => JobParams.ByStage(s.name))).distinct
      val missing = required.filterNot(params.contains)
      val stageErr =
        if (manifest.stage != ManifestStage)
          Vector(s"run manifest stage is '${manifest.stage}', expected '$ManifestStage'")
        else Vector.empty
      val inputErr = inputs.sources.collect {
        case (s, paths) if paths.nonEmpty && !manifest.inputSnapshots.exists(_.source == s) =>
          s"inputs for ${s.tag} were given but the run manifest has no ${s.tag} snapshot"
      } ++ (if (stages.contains(Stage.Normalize) && inputs.dblp.isEmpty)
              Vector("the normalize stage needs DBLP input")
            else Vector.empty)
      val errors =
        stageErr ++ unknown.map(k => s"unknown run manifest parameter '$k'") ++
          missing.map(k => s"run manifest parameter '$k' is required by the requested stages") ++
          inputErr
      if (errors.nonEmpty) Left(errors)
      else {
        params.foreach { case (k, v) => conf.set(k, v) }
        conf.set(JobParams.RunIdKey, id)
        manifest.inputSnapshots.foreach(s => conf.set(JobParams.snapshotKey(s.source), s.snapshot))
        val parsed: Vector[Either[ParamError, Any]] =
          Vector(
            JobParams.codecClass(conf, JobParams.CodecIntermediate),
            JobParams.codecClass(conf, JobParams.CodecOutput),
            RunContext.from(conf, SourceType.Dblp)
          ) ++ stages.flatMap {
            case Stage.Normalize =>
              Vector(DblpSettings.from(conf), JobParams.int(conf, JobParams.ReducersNormalize, 1))
            case Stage.ExactIds => Vector(JobParams.int(conf, JobParams.ReducersExactIds, 1))
            case Stage.Teams =>
              Vector(TeamSettings.from(conf), JobParams.int(conf, JobParams.ReducersTeams, 1))
            case Stage.Blocking =>
              Vector(
                BlockingSettings.from(conf).flatMap(checkKeySource),
                JobParams.int(conf, JobParams.ReducersBlocking, 1)
              )
            case Stage.Candidates =>
              Vector(
                CandidateSettings.from(conf).flatMap { c =>
                  if (c.featureVersion == EntityResolution.featuresVersion) Right(c)
                  else
                    Left(
                      ParamError(
                        JobParams.CandidatesFeatureVersion,
                        s"'${c.featureVersion}' differs from the feature functions' version " +
                          s"'${EntityResolution.featuresVersion}'"
                      )
                    )
                },
                JobParams.int(conf, JobParams.ReducersCandidates, 1)
              )
          }
        val paramErrors = parsed.collect { case Left(e) => e.message }
        if (paramErrors.nonEmpty) Left(paramErrors) else Right((conf, id))
      }
    }
  }

  /** The key source loads and its version equals the manifest's recorded version. */
  private def checkKeySource(b: BlockingSettings): Either[ParamError, BlockingSettings] =
    try {
      val src = BlockingKeySource.load(b.keySourceClass)
      if (src.version == b.keySourceVersion) Right(b)
      else
        Left(
          ParamError(
            JobParams.BlockingKeySourceVersion,
            s"'${b.keySourceVersion}' differs from the version '${src.version}' of ${b.keySourceClass}"
          )
        )
    } catch {
      case NonFatal(e) =>
        Left(ParamError(JobParams.BlockingKeySourceClass, s"cannot be loaded: $e"))
    }

  /** Runs `stages` for `manifest`. Never overwrites: complete stages are skipped. */
  def run(
      base: Configuration,
      manifest: RunManifest,
      curatedRoot: Path,
      inputs: PipelineInputs,
      stages: Vector[Stage]
  ): Either[Vector[String], Vector[StageReport]] =
    run(base, manifest, curatedRoot, inputs, stages, _ => ())

  /**
   * As [[run]]; `beforeCommit` runs after a stage's jobs succeeded and before its `_counters.json`
   * and `_SUCCESS` are written (tests use it to simulate a driver crash at that point).
   */
  private[hadoop] def run(
      base: Configuration,
      manifest: RunManifest,
      curatedRoot: Path,
      inputs: PipelineInputs,
      stages: Vector[Stage],
      beforeCommit: Stage => Unit
  ): Either[Vector[String], Vector[StageReport]] =
    for {
      configured <- configure(base, manifest, stages, inputs)
      (conf, runId) = configured
      layout <- RunLayout.of(curatedRoot, manifest, runId).left.map(Vector(_))
      fs = layout.root.getFileSystem(conf)
      _ <- writeManifest(fs, layout, manifest).left.map(Vector(_))
      reports <- stages.foldLeft[Either[Vector[String], Vector[StageReport]]](Right(Vector.empty)) {
        (acc, stage) =>
          acc.flatMap(done =>
            runStage(conf, fs, layout, inputs, stage, beforeCommit).map(done :+ _)
          )
      }
    } yield reports

  private def writeManifest(
      fs: FileSystem,
      layout: RunLayout,
      m: RunManifest
  ): Either[String, Unit] = {
    val json = Json.write(m)
    if (fs.exists(layout.manifest)) {
      val existing = readAll(fs, layout.manifest)
      if (existing == json) Right(())
      else
        Left(
          s"${layout.manifest} exists with a different manifest; refusing to reuse the run directory"
        )
    } else {
      val out = fs.create(layout.manifest, false)
      try out.write(json.getBytes(StandardCharsets.UTF_8))
      finally out.close()
      Right(())
    }
  }

  private def readAll(fs: FileSystem, p: Path): String = {
    val in = fs.open(p)
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()
  }

  private def runStage(
      conf: Configuration,
      fs: FileSystem,
      layout: RunLayout,
      inputs: PipelineInputs,
      stage: Stage,
      beforeCommit: Stage => Unit
  ): Either[Vector[String], StageReport] = {
    val dir = layout.stage(stage)
    val missingDeps =
      stage.requires.filterNot(d => fs.exists(new Path(layout.stage(d), Success)))
    if (fs.exists(new Path(dir, Success))) readReport(fs, dir, stage)
    else if (fs.exists(dir))
      Left(
        Vector(
          s"$dir exists without $Success (an earlier attempt failed or its driver stopped before " +
            "committing); refusing to overwrite. Rerun with a new run manifest attempt."
        )
      )
    else if (missingDeps.nonEmpty)
      Left(
        Vector(
          s"stage ${stage.name} needs complete ${missingDeps.map(_.name).mkString(", ")} " +
            "stage(s) of the same run"
        )
      )
    else {
      val result = stage match {
        case Stage.Normalize => runNormalize(conf, layout, inputs).map(Vector(_))
        case Stage.ExactIds => runExactIds(conf, fs, layout).map(Vector(_))
        case Stage.Teams => runTeams(conf, layout).map(Vector(_))
        case Stage.Blocking => runBlocking(conf, fs, layout)
        case Stage.Candidates => runCandidates(conf, fs, layout)
      }
      result.map { jobs =>
        // The ten required counters are always reported, as 0 when never incremented.
        val counters = Counters.Required.map(_ -> 0L).toMap ++ jobs
          .flatMap(j => counterMap(j))
          .groupMapReduce(_._1)(_._2)(_ + _)
        val jobDirs = jobs.map(j => FileOutputFormat.getOutputPath(j))
        val stats = jobDirs.flatMap(d => readStats(conf, fs, new Path(d, NamedOutputs.Stats)))
        val report = StageReport(
          stage.name,
          "ran",
          dir.toString,
          SortedMap.from(counters),
          SortedMap.from(stats.groupMapReduce(_.metric)(identity)(StatLine.better))
        )
        // Commit order (review H6): counters first, then _SUCCESS. The job committers write no
        // _SUCCESS of their own, so a driver that stops in between leaves a stage directory
        // without _SUCCESS, which is refused (never reported as complete without counters).
        beforeCommit(stage)
        writeReport(fs, dir, report)
        fs.create(new Path(dir, Success), false).close()
        report
      }
    }
  }

  // ---------------------------------------------------------------- job construction

  private[hadoop] def newJob(conf: Configuration, name: String, reducers: Int, out: Path): Job = {
    val job = Job.getInstance(conf, s"scholarchain-hadoop-$name")
    job.setJarByClass(classOf[StableHashPartitioner])
    val c = job.getConfiguration
    c.setBoolean(MRJobConfig.MAP_OUTPUT_COMPRESS, true)
    c.set(MRJobConfig.MAP_OUTPUT_COMPRESS_CODEC, c.get(JobParams.CodecIntermediate))
    c.setBoolean(MRJobConfig.MAP_SPECULATIVE, false)
    c.setBoolean(MRJobConfig.REDUCE_SPECULATIVE, false)
    // The stage's _SUCCESS is written by the driver after _counters.json (see runStage).
    c.setBoolean(FileOutputCommitter.SUCCESSFUL_JOB_OUTPUT_DIR_MARKER, false)
    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[Text])
    job.setOutputKeyClass(classOf[NullWritable])
    job.setOutputValueClass(classOf[Text])
    job.setPartitionerClass(classOf[StableHashPartitioner])
    job.setNumReduceTasks(reducers)
    FileOutputFormat.setOutputPath(job, out)
    FileOutputFormat.setCompressOutput(job, true)
    FileOutputFormat.setOutputCompressorClass(
      job,
      Class.forName(c.get(JobParams.CodecOutput)).asSubclass(classOf[CompressionCodec])
    )
    LazyOutputFormat.setOutputFormatClass(job, classOf[TextOutputFormat[NullWritable, Text]])
    job
  }

  private def named(job: Job, names: String*): Unit =
    names.foreach { n =>
      MultipleOutputs.addNamedOutput(
        job,
        n,
        classOf[TextOutputFormat[NullWritable, Text]],
        classOf[NullWritable],
        classOf[Text]
      )
    }

  private def reducers(conf: Configuration, key: String): Int =
    JobParams.orThrow(JobParams.int(conf, key, 1))

  private def await(job: Job): Either[Vector[String], Job] =
    try {
      if (job.waitForCompletion(true)) Right(job)
      else Left(Vector(s"job ${job.getJobName} failed: ${job.getStatus.getFailureInfo}"))
    } catch {
      case NonFatal(e) => Left(Vector(s"job ${job.getJobName} failed: $e"))
    }

  private def addInputs(
      job: Job,
      paths: Vector[Path],
      fmt: Class[? <: InputFormat[?, ?]],
      mapper: Class[? <: Mapper[?, ?, ?, ?]]
  ): Unit =
    paths.foreach(p => MultipleInputs.addInputPath(job, p, fmt, mapper))

  /** Directories that exist and hold at least one non-empty part file. */
  private def nonEmptyDirs(fs: FileSystem, dirs: Vector[Path]): Vector[Path] =
    dirs.filter { d =>
      fs.exists(d) && fs
        .listStatus(d)
        .exists(s => s.isFile && s.getLen > 0 && s.getPath.getName.startsWith("part-"))
    }

  private def runNormalize(
      conf: Configuration,
      layout: RunLayout,
      in: PipelineInputs
  ): Either[Vector[String], Job] = {
    val job = newJob(
      conf,
      Stages.Normalize,
      reducers(conf, JobParams.ReducersNormalize),
      layout.stage(Stage.Normalize)
    )
    addInputs(job, in.dblp, classOf[DblpInputFormat], classOf[DblpMapper])
    addInputs(job, in.openAlexWorks, classOf[LocatedTextInputFormat], classOf[OpenAlexWorksMapper])
    addInputs(
      job,
      in.openAlexAuthors,
      classOf[LocatedTextInputFormat],
      classOf[OpenAlexAuthorsMapper]
    )
    addInputs(
      job,
      in.openAlexInstitutions,
      classOf[LocatedTextInputFormat],
      classOf[OpenAlexInstitutionsMapper]
    )
    addInputs(job, in.crossrefWorks, classOf[LocatedTextInputFormat], classOf[CrossrefWorksMapper])
    addInputs(job, in.orcidRecords, classOf[WholeFileInputFormat], classOf[OrcidMapper])
    job.setCombinerClass(classOf[EnvelopeCombiner])
    job.setReducerClass(classOf[CanonicalRecordReducer])
    named(
      job,
      NamedOutputs.Works,
      NamedOutputs.Mentions,
      NamedOutputs.Profiles,
      NamedOutputs.Venues,
      NamedOutputs.Institutions,
      NamedOutputs.Topics,
      NamedOutputs.Rejects
    )
    await(job)
  }

  private def runExactIds(
      conf: Configuration,
      fs: FileSystem,
      layout: RunLayout
  ): Either[Vector[String], Job] = {
    val job = newJob(
      conf,
      Stages.ExactIds,
      reducers(conf, JobParams.ReducersExactIds),
      layout.stage(Stage.ExactIds)
    )
    addInputs(job, Vector(layout.works), classOf[LocatedTextInputFormat], classOf[ExactIdMapper])
    addInputs(
      job,
      nonEmptyDirs(fs, Vector(layout.profiles)),
      classOf[LocatedTextInputFormat],
      classOf[ExactIdProfileMapper]
    )
    job.setReducerClass(classOf[ExactIdReducer])
    named(job, NamedOutputs.Links, NamedOutputs.Rejects, NamedOutputs.Stats)
    await(job)
  }

  private def runTeams(conf: Configuration, layout: RunLayout): Either[Vector[String], Job] = {
    val job = newJob(
      conf,
      Stages.Teams,
      reducers(conf, JobParams.ReducersTeams),
      layout.stage(Stage.Teams)
    )
    job.setInputFormatClass(classOf[LocatedTextInputFormat])
    FileInputFormat.addInputPath(job, layout.works)
    job.setMapperClass(classOf[TeamMapper])
    job.setReducerClass(classOf[TeamReducer])
    named(job, NamedOutputs.Teams, NamedOutputs.Pairs, NamedOutputs.Rejects, NamedOutputs.Stats)
    await(job)
  }

  /**
   * One job per round for BOTH entities (researcher and work) under one run ID; round r + 1 reads
   * round r's spill. Stops when no member is refined or after the key source's last level.
   */
  private def runBlocking(
      conf: Configuration,
      fs: FileSystem,
      layout: RunLayout
  ): Either[Vector[String], Vector[Job]] = {
    val settings = JobParams.orThrow(BlockingSettings.from(conf))
    val levels = BlockingKeySource.load(settings.keySourceClass).levels
    def spillOf(r: Int) = new Path(layout.blockingRound(r), NamedOutputs.Spill)
    (0 until levels).foldLeft[Either[Vector[String], Vector[Job]]](Right(Vector.empty)) {
      (acc, r) =>
        acc.flatMap { jobs =>
          if (r > 0 && nonEmptyDirs(fs, Vector(spillOf(r - 1))).isEmpty) Right(jobs)
          else {
            val rc = new Configuration(conf)
            rc.setInt(JobParams.BlockingRound, r)
            val job = newJob(
              rc,
              s"${Stages.Blocking}-round-$r",
              reducers(rc, JobParams.ReducersBlocking),
              layout.blockingRound(r)
            )
            if (r == 0) {
              addInputs(
                job,
                Vector(layout.works),
                classOf[LocatedTextInputFormat],
                classOf[BlockingWorkSeedMapper]
              )
              addInputs(
                job,
                nonEmptyDirs(fs, Vector(layout.profiles)),
                classOf[LocatedTextInputFormat],
                classOf[BlockingProfileSeedMapper]
              )
            } else {
              job.setInputFormatClass(classOf[LocatedTextInputFormat])
              job.setMapperClass(classOf[BlockingSpillMapper])
              FileInputFormat.addInputPath(job, spillOf(r - 1))
            }
            job.setReducerClass(classOf[BlockReducer])
            named(
              job,
              NamedOutputs.Blocks,
              NamedOutputs.Members,
              NamedOutputs.Spill,
              NamedOutputs.Rejects,
              NamedOutputs.Stats
            )
            await(job).map(jobs :+ _)
          }
        }
    }
  }

  /** Every `members/<entity>` directory of every blocking round. */
  private def membershipDirs(fs: FileSystem, layout: RunLayout): Vector[Path] = {
    val dir = layout.stage(Stage.Blocking)
    val rounds = fs
      .listStatus(dir)
      .toVector
      .filter(s => s.isDirectory && s.getPath.getName.startsWith("round-"))
      .map(_.getPath)
      .sortBy(_.getName)
    nonEmptyDirs(
      fs,
      rounds.flatMap(r =>
        BlockingEntity.values.toVector.map(e => new Path(new Path(r, NamedOutputs.Members), e.tag))
      )
    )
  }

  private def lineJob(
      conf: Configuration,
      name: String,
      input: Path,
      out: Path,
      mapper: Class[? <: Mapper[Text, Text, Text, Text]],
      reducer: Class[? <: Reducer[Text, Text, NullWritable, Text]],
      outputs: Vector[String]
  ): Job = {
    val job = newJob(conf, name, reducers(conf, JobParams.ReducersCandidates), out)
    job.setInputFormatClass(classOf[LocatedTextInputFormat])
    FileInputFormat.addInputPath(job, input)
    job.setMapperClass(mapper)
    job.setReducerClass(reducer)
    named(job, outputs*)
    job
  }

  /** Job 5c alone: consolidates per-block candidates under `input` into `out`. */
  private[hadoop] def consolidateJob(conf: Configuration, input: Path, out: Path): Job =
    lineJob(
      conf,
      s"${Stages.Candidates}-$FinalJob",
      input,
      out,
      classOf[CandidateConsolidateMapper],
      classOf[CandidateConsolidateReducer],
      Vector(NamedOutputs.Candidates, NamedOutputs.Rejects, NamedOutputs.Stats)
    )

  /**
   * Job 5 as three MapReduce jobs: join (block memberships with their works and profiles, keyed by
   * record ID), pairs (per block: features of every member pair), and final (per candidate ID: one
   * MatchCandidate with the union of its block keys).
   */
  private def runCandidates(
      conf: Configuration,
      fs: FileSystem,
      layout: RunLayout
  ): Either[Vector[String], Vector[Job]] = {
    val joinOut = layout.candidatesJob(JoinJob)
    val pairsOut = layout.candidatesJob(PairsJob)
    val join = newJob(
      conf,
      s"${Stages.Candidates}-$JoinJob",
      reducers(conf, JobParams.ReducersCandidates),
      joinOut
    )
    addInputs(
      join,
      membershipDirs(fs, layout),
      classOf[LocatedTextInputFormat],
      classOf[MembershipJoinMapper]
    )
    addInputs(join, Vector(layout.works), classOf[LocatedTextInputFormat], classOf[WorkJoinMapper])
    addInputs(
      join,
      nonEmptyDirs(fs, Vector(layout.profiles)),
      classOf[LocatedTextInputFormat],
      classOf[ProfileJoinMapper]
    )
    join.setReducerClass(classOf[JoinReducer])
    named(join, NamedOutputs.Joined, NamedOutputs.Rejects)
    for {
      j1 <- await(join)
      joined = new Path(joinOut, NamedOutputs.Joined)
      done <-
        if (nonEmptyDirs(fs, Vector(joined)).isEmpty) Right(Vector[Job](j1))
        else
          for {
            j2 <- await(
              lineJob(
                conf,
                s"${Stages.Candidates}-$PairsJob",
                joined,
                pairsOut,
                classOf[BlockPairMapper],
                classOf[PairFeatureReducer],
                Vector(NamedOutputs.Pairs, NamedOutputs.Rejects, NamedOutputs.Stats)
              )
            )
            pairs = new Path(pairsOut, NamedOutputs.Pairs)
            j3 <-
              if (nonEmptyDirs(fs, Vector(pairs)).isEmpty) Right(Vector.empty[Job])
              else
                await(consolidateJob(conf, pairs, layout.candidatesJob(FinalJob))).map(Vector(_))
          } yield Vector(j1, j2) ++ j3
    } yield done
  }

  // ---------------------------------------------------------------- reports

  def counterMap(job: Job): Vector[(String, Long)] =
    job.getCounters.getGroup(Counters.Group).asScala.toVector.map(c => c.getName -> c.getValue)

  /** Reads every `stats` part file under `dir` (any codec). */
  def readStats(conf: Configuration, fs: FileSystem, dir: Path): Vector[StatLine] =
    readLines(conf, fs, dir).map(StatLine.fromJson)

  /** All lines of the part files of an output directory, in part-file name order. */
  def readLines(conf: Configuration, fs: FileSystem, dir: Path): Vector[String] =
    if (!fs.exists(dir)) Vector.empty
    else {
      val factory = new CompressionCodecFactory(conf)
      fs.listStatus(dir)
        .toVector
        .filter(s => s.isFile && s.getPath.getName.startsWith("part-"))
        .sortBy(_.getPath.getName)
        .flatMap { s =>
          val codec = factory.getCodec(s.getPath)
          val raw = fs.open(s.getPath)
          val in = if (codec == null) raw else codec.createInputStream(raw)
          val reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          try Iterator.continually(reader.readLine()).takeWhile(_ != null).toVector
          finally reader.close()
        }
    }

  private def writeReport(fs: FileSystem, dir: Path, r: StageReport): Unit = {
    val json = ujson.Obj(
      "stage" -> r.stage,
      "counters" -> ujson.Obj.from(r.counters.toSeq.map { case (k, v) =>
        k -> ujson.Num(v.toDouble)
      }),
      "stats" -> ujson.Obj.from(r.stats.toSeq.map { case (k, s) =>
        k -> ujson.Obj("value" -> ujson.Num(s.value.toDouble), "id" -> s.id)
      })
    )
    val out = fs.create(new Path(dir, CountersFile), false)
    try out.write(ujson.write(json, indent = 2).getBytes(StandardCharsets.UTF_8))
    finally out.close()
  }

  /**
   * Report of a complete stage. `_counters.json` is written before `_SUCCESS`, so a complete stage
   * always has it; if it is nevertheless missing (the directory was tampered with), the stage is
   * refused instead of being reported with empty counters.
   */
  private def readReport(
      fs: FileSystem,
      dir: Path,
      stage: Stage
  ): Either[Vector[String], StageReport] = {
    val p = new Path(dir, CountersFile)
    if (!fs.exists(p))
      Left(
        Vector(
          s"$dir has $Success but no $CountersFile; its counters are unknown. Rerun with a new " +
            "run manifest attempt."
        )
      )
    else {
      val v = ujson.read(readAll(fs, p))
      Right(
        StageReport(
          stage.name,
          "skipped",
          dir.toString,
          SortedMap.from(v("counters").obj.map { case (k, n) => k -> n.num.toLong }),
          SortedMap.from(v("stats").obj.map { case (k, s) =>
            k -> StatLine(k, s("value").num.toLong, s("id").str)
          })
        )
      )
    }
  }
}
