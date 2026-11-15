package scholarchain.entityresolution.review

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import upickle.default.{read, write, ReadWriter}
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{Canonical, CanonicalPair, MatchCandidate, StableId}

/** One endpoint of a gold member: enough to find its source record again. */
@allowUnknownKeys(false)
final case class MemberEndpoint(
    endpointId: String,
    endpointKind: String,
    sourceDataset: String,
    sourceRecordId: String,
    snapshot: String
) derives ReadWriter

object MemberEndpoint {
  def of(r: EndpointRef): MemberEndpoint =
    MemberEndpoint(r.endpointId, r.endpointKind, r.sourceDataset, r.sourceRecordId, r.snapshot)
}

/**
 * One line of a gold membership manifest (schema [[GoldMembership.SchemaVersion]]). A member is a
 * candidate pair fixed once for gold labeling. Its ID depends only on the entity kind and the two
 * endpoint record IDs, so the same member, and its human label, survive every later change of the
 * blocking, feature, or matching version and every new pipeline run.
 *
 * @param memberId
 *   [[GoldMembership.memberId]]
 * @param endpointA
 *   the first endpoint of the canonical pair (UTF-8 byte order)
 * @param unitIds
 *   the DS-P5-01 selection units of both endpoints' records, sorted and distinct
 * @param criterion
 *   the units' criterion; several distinct criteria are joined with `+` in sorted order
 * @param split
 *   `heldout` or `dev`; every unit of the member is in this split
 * @param generatingRunId
 *   the candidate run the membership was built from
 * @param blockingVersion
 *   the blocking version of that run
 * @param selectionRule
 *   [[GoldMembership.CensusRule]] or [[GoldMembership.SampleRule]]
 * @param seed
 *   the sample seed (present exactly when a capacity was given)
 * @param capacity
 *   the labeling capacity N, when given
 * @param populationSize
 *   the number of eligible pairs before sampling
 */
@allowUnknownKeys(false)
final case class GoldMember(
    schemaVersion: String,
    memberId: String,
    entityKind: String,
    endpointA: MemberEndpoint,
    endpointB: MemberEndpoint,
    unitIds: Vector[String],
    criterion: String,
    split: String,
    generatingRunId: String,
    blockingVersion: String,
    selectionRule: String,
    seed: Option[String],
    capacity: Option[Int],
    populationSize: Int
) derives ReadWriter

/**
 * How membership is chosen. Without a capacity: census (every eligible pair). With a capacity N
 * below the population: a seeded sample with proportional allocation over (entity kind, criterion)
 * groups.
 */
final case class MembershipDesign(split: String, capacity: Option[Int], seed: Option[String])

object MembershipDesign {
  def census(split: String): MembershipDesign = MembershipDesign(split, None, None)

  /**
   * The documented labeling design (human instruction 2026-11-05, decisions-04): capacity
   * [[GoldMembership.DefaultCapacity]] (240) with a required, recorded seed.
   */
  def labeling(split: String, seed: String): MembershipDesign =
    MembershipDesign(split, Some(GoldMembership.DefaultCapacity), Some(seed))
}

/**
 * A membership build: the eligible population and the chosen members.
 *
 * @param population
 *   every eligible pair (census), sorted by member ID
 * @param members
 *   the chosen members (the manifest), sorted by member ID
 */
final case class MembershipPlan(
    design: MembershipDesign,
    population: Vector[GoldMember],
    members: Vector[GoldMember]
) {

  /** Capacity minus population when the population is smaller (census, never padded), else 0. */
  def shortfall: Int = design.capacity.fold(0)(cap => math.max(0, cap - population.size))
}

/**
 * The only candidate fields that membership reads: the entity kind, the endpoint pair, the blocking
 * version, and the run. [[CandidatePair.of]] is the single place where a candidate is projected.
 */
final case class CandidatePair(
    entityKind: String,
    sourceIdA: String,
    sourceIdB: String,
    blockingVersion: String,
    runId: String
)

object CandidatePair {
  def of(c: MatchCandidate): CandidatePair =
    CandidatePair(c.entityKind.tag, c.sourceIdA, c.sourceIdB, c.blockingVersion, c.provenance.runId)
}

/**
 * Fixed gold membership (ER-P5-05; human instruction 2026-11-05, decisions-03). Membership is
 * chosen from the candidate pairs of one per-split pipeline run, their source records, and the
 * DS-P5-01 selection units only. It never sees a matching decision or any value derived from one,
 * so the held-out membership stays the same across matching versions. Per-version review-queue rows
 * are derived from a fixed manifest ([[RunQueue.resolveMembers]]).
 */
object GoldMembership {
  val SchemaVersion: String = "er-gold-membership.v1"
  val MemberTag: String = "er-gold-member.v1"
  val CensusRule: String = "er-gold-census.v1"
  val SampleRule: String = "er-gold-sample.v1"
  val EntityKinds: Set[String] = Set("researcher", "work")

  /**
   * Default labeling capacity N of held-out membership generation (human instruction 2026-11-05):
   * 240 pairs, by the seeded proportional sample over (entity kind, criterion). A smaller
   * population is taken whole (census); the shortfall is reported and nothing is padded.
   */
  val DefaultCapacity: Int = 240

  /**
   * `memberId = "gm:" + SHA-256(netstrings("er-gold-member.v1", entityKind, first, second))`, where
   * (first, second) is the canonical pair ([[CanonicalPair]]) of the two endpoint IDs. Endpoint IDs
   * are stable record IDs (work IDs, mention IDs = hash(work ID, position), source author IDs); the
   * candidate ID is deliberately not an input, because it hashes the blocking version, feature
   * version, and run ID.
   */
  def memberId(entityKind: String, x: String, y: String): Either[String, String] =
    if (!EntityKinds.contains(entityKind)) Left(s"entity kind '$entityKind' is not researcher/work")
    else
      CanonicalPair
        .of(x, y)
        .left
        .map(_.message)
        .map(p => "gm:" + Canonical.hashFields(MemberTag, entityKind, p.first, p.second))

  /** Sample order key of a member: SHA-256 of ([[SampleRule]], seed, memberId). */
  def sampleKey(seed: String, memberId: String): String =
    Canonical.hashFields(SampleRule, seed, memberId)

  private def checkDesign(d: MembershipDesign): Vector[String] =
    Vector(
      Option.when(!SelectionUnits.Splits.contains(d.split))(
        s"split '${d.split}' is not heldout or dev"
      ),
      Option.when(d.capacity.exists(_ < 1))("capacity must be at least 1"),
      Option.when(d.capacity.isDefined != d.seed.isDefined)(
        "a seed is required exactly when a capacity is given"
      ),
      Option.when(d.seed.exists(s => !StableId.isToken(s)))("seed must be a token [A-Za-z0-9._-]+")
    ).flatten

  /** The member of one pair, or why it is refused. Does not apply any selection. */
  private def member(
      c: CandidatePair,
      refs: Map[String, EndpointRef],
      units: SelectionUnits,
      split: String
  ): Either[Vector[String], GoldMember] =
    memberId(c.entityKind, c.sourceIdA, c.sourceIdB).left.map(Vector(_)).flatMap { id =>
      val ends = Vector(c.sourceIdA, c.sourceIdB).sortWith(Canonical.compareUtf8(_, _) < 0)
      val found = ends.map(e => e -> refs.get(e))
      val missing = found.collect { case (e, None) => s"$id: endpoint $e has no source record" }
      if (missing.nonEmpty) Left(missing)
      else {
        val rs = found.collect { case (_, Some(r)) => r }
        val unmapped = rs.collect {
          case r if units.unitIdsOf(r).isEmpty =>
            s"$id: endpoint ${r.endpointId} (record ${SelectionUnits.anchorOf(r)}) maps to no " +
              "selection unit"
        }
        val us = rs.flatMap(units.unitsOf).distinctBy(_.unitId).sortBy(_.unitId)
        val wrongSplit = us.collect {
          case u if u.split != split =>
            s"$id: unit ${u.unitId} is in the ${u.split} split; refused for the $split manifest"
        }
        if (unmapped.nonEmpty || wrongSplit.nonEmpty) Left(unmapped ++ wrongSplit)
        else
          Right(
            GoldMember(
              SchemaVersion,
              id,
              c.entityKind,
              MemberEndpoint.of(rs(0)),
              MemberEndpoint.of(rs(1)),
              us.map(_.unitId),
              us.map(_.criterion).distinct.sorted.mkString("+"),
              split,
              c.runId,
              c.blockingVersion,
              CensusRule,
              None,
              None,
              0
            )
          )
      }
    }

  /**
   * Proportional allocation of `n` over groups of the given sizes: floors of `n * size / total`,
   * then one more for the largest remainders (ties by group name). Requires `n < total`.
   */
  def allocate(sizes: Vector[(String, Int)], n: Int): Map[String, Int] = {
    val total = sizes.map(_._2).sum
    val exact = sizes.map { case (g, s) => (g, s, s.toDouble * n / total) }
    val floors = exact.map { case (g, s, x) => g -> math.min(s, math.floor(x).toInt) }.toMap
    val bonus = exact
      .filter { case (g, s, _) => floors(g) < s }
      .sortBy { case (g, _, x) => (-(x - math.floor(x)), g) }
      .take(n - floors.values.sum)
      .map(_._1)
      .toSet
    floors.map { case (g, f) => g -> (f + (if (bonus(g)) 1 else 0)) }
  }

  /**
   * Builds the membership of one per-split candidate run.
   *
   * Refused (every problem listed, nothing selected) when: the design is invalid; the pairs are
   * empty or come from more than one run or blocking version; an endpoint has no source record; an
   * endpoint's record maps to no selection unit; or any endpoint's unit is in another split (for a
   * held-out manifest: any dev endpoint).
   *
   * Census without a capacity, or when the capacity covers the population. Otherwise a seeded
   * sample: groups are (entity kind, criterion), sizes are allocated by [[allocate]], and each
   * group takes its members in [[sampleKey]] order. The result is sorted by member ID and does not
   * depend on the input order.
   */
  def build(
      pairs: Vector[CandidatePair],
      refs: Map[String, EndpointRef],
      units: SelectionUnits,
      design: MembershipDesign
  ): Either[Vector[String], Vector[GoldMember]] =
    plan(pairs, refs, units, design).map(_.members)

  /** [[build]] with the eligible population kept, for the generator's counts. */
  def plan(
      pairs: Vector[CandidatePair],
      refs: Map[String, EndpointRef],
      units: SelectionUnits,
      design: MembershipDesign
  ): Either[Vector[String], MembershipPlan] = {
    val runs = pairs.map(p => (p.runId, p.blockingVersion)).distinct
    val pre =
      checkDesign(design) ++
        Option.when(pairs.isEmpty)("no candidate pairs").toVector ++
        Option
          .when(runs.size > 1)(
            s"pairs come from ${runs.size} (run, blocking version) combinations; one run is required"
          )
          .toVector
    if (pre.nonEmpty) Left(pre)
    else {
      val built = pairs.map(member(_, refs, units, design.split))
      val errors = built.collect { case Left(e) => e }.flatten.distinct.sorted
      if (errors.nonEmpty) Left(errors)
      else {
        val population =
          built.collect { case Right(m) => m }.distinctBy(_.memberId).sortBy(_.memberId)
        val n = population.size
        val chosen = design.capacity match {
          case Some(cap) if cap < n =>
            val seed = design.seed.getOrElse("")
            val groups = population.groupBy(m => s"${m.entityKind}/${m.criterion}")
            val quota =
              allocate(groups.toVector.map { case (g, ms) => g -> ms.size }.sortBy(_._1), cap)
            groups.toVector
              .flatMap { case (g, ms) =>
                ms.sortBy(m => sampleKey(seed, m.memberId)).take(quota(g))
              }
              .sortBy(_.memberId)
              .map(_.copy(selectionRule = SampleRule))
          case _ => population
        }
        def record(ms: Vector[GoldMember]) =
          ms.map(_.copy(seed = design.seed, capacity = design.capacity, populationSize = n))
        Right(MembershipPlan(design, record(population), record(chosen)))
      }
    }
  }

  /** [[build]] over the candidates of a curated run (projected through [[CandidatePair.of]]). */
  def ofRun(
      run: CuratedRun,
      units: SelectionUnits,
      design: MembershipDesign
  ): Either[Vector[String], Vector[GoldMember]] =
    planOfRun(run, units, design).map(_.members)

  /** [[plan]] over the candidates of a curated run. */
  def planOfRun(
      run: CuratedRun,
      units: SelectionUnits,
      design: MembershipDesign
  ): Either[Vector[String], MembershipPlan] =
    plan(
      run.candidates.map(CandidatePair.of),
      EndpointRef.index(run.works, run.profiles),
      units,
      design
    )

  /** Member counts by entity kind (both kinds always present). */
  def kindCounts(ms: Vector[GoldMember]): SortedMap[String, Int] =
    SortedMap.from(EntityKinds.toVector.map(k => k -> ms.count(_.entityKind == k)))

  /** Member counts by selection stratum `<entity kind>/<criterion>`. */
  def strataCounts(ms: Vector[GoldMember]): SortedMap[String, Int] =
    SortedMap.from(ms.groupBy(m => s"${m.entityKind}/${m.criterion}").map { case (g, xs) =>
      g -> xs.size
    })

  /** SHA-256 (hex) of the manifest text exactly as [[writeNew]] writes it (UTF-8 bytes). */
  def manifestSha256(members: Vector[GoldMember]): String =
    Canonical.sha256HexUtf8(toJsonl(members))

  /** SHA-256 (hex) of a manifest file's raw bytes. */
  def fileSha256(path: Path): Either[String, String] =
    try {
      val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
      Right(digest.map(b => f"${b & 0xff}%02x").mkString)
    } catch { case NonFatal(e) => Left(s"cannot read $path: ${e.getMessage}") }

  /**
   * The generator's summary of a plan: design, population and sample sizes, shortfall, researcher
   * and work counts, per-stratum counts of the population and of the sample, and the manifest
   * SHA-256 that a human records to freeze the membership before labeling.
   */
  def summary(p: MembershipPlan): Vector[String] = {
    val d = p.design
    val sampled = p.members.size
    Vector(
      s"membership split=${d.split} seed=${d.seed.getOrElse("-")} " +
        s"capacity=${d.capacity.fold("-")(_.toString)} population=${p.population.size} " +
        s"sampled=$sampled rule=${p.members.headOption.fold("-")(_.selectionRule)} " +
        s"shortfall=${p.shortfall}" +
        (if (p.shortfall > 0) " (census: the population is below the capacity; nothing padded)"
         else ""),
      "population " + kindCounts(p.population).map { case (k, n) => s"$k=$n" }.mkString(" "),
      "sample " + kindCounts(p.members).map { case (k, n) => s"$k=$n" }.mkString(" ")
    ) ++ {
      val pop = strataCounts(p.population)
      val smp = strataCounts(p.members)
      pop.keys.toVector.map(g => s"stratum $g population=${pop(g)} sample=${smp.getOrElse(g, 0)}")
    } :+ s"manifest sha256=${manifestSha256(p.members)} members=$sampled"
  }

  /** JSON Lines, one member per line, in manifest order. */
  def toJsonl(members: Vector[GoldMember]): String =
    members.map(m => write(m)).mkString("", "\n", if (members.isEmpty) "" else "\n")

  /**
   * Parses a manifest. Refused when a line is malformed, has another schema version, or has a
   * member ID that does not match its kind and endpoints; when a member ID repeats; or when the
   * manifest-level fields (split, run, blocking version, selection rule, seed, capacity,
   * population) differ between lines.
   */
  def parse(lines: Vector[String]): Either[Vector[String], Vector[GoldMember]] = {
    val parsed = lines.zipWithIndex.filter(_._1.trim.nonEmpty).map { case (l, i) =>
      (try Right(read[GoldMember](l))
      catch { case NonFatal(e) => Left(s"manifest line ${i + 1}: malformed (${e.getMessage})") })
        .flatMap { m =>
          if (m.schemaVersion != SchemaVersion)
            Left(s"manifest line ${i + 1}: schemaVersion must be $SchemaVersion")
          else if (
            !memberId(m.entityKind, m.endpointA.endpointId, m.endpointB.endpointId)
              .contains(m.memberId)
          )
            Left(s"manifest line ${i + 1}: memberId does not match the entity kind and endpoints")
          else if (Canonical.compareUtf8(m.endpointA.endpointId, m.endpointB.endpointId) >= 0)
            Left(s"manifest line ${i + 1}: endpoints are not in canonical order")
          else Right(m)
        }
    }
    val errors = parsed.collect { case Left(e) => e }
    val ms = parsed.collect { case Right(m) => m }
    val dup = ms.groupBy(_.memberId).collect { case (id, xs) if xs.size > 1 => s"duplicate $id" }
    val header = ms
      .map(m =>
        (
          m.split,
          m.generatingRunId,
          m.blockingVersion,
          m.selectionRule,
          m.seed,
          m.capacity,
          m.populationSize
        )
      )
      .distinct
    val mixed =
      Option.when(header.size > 1)("manifest lines differ in their manifest-level fields").toVector
    val all = errors ++ dup.toVector.sorted ++ mixed ++
      Option.when(ms.isEmpty && errors.isEmpty)("manifest is empty").toVector
    if (all.nonEmpty) Left(all) else Right(ms)
  }

  /** [[parse]] over a manifest file. */
  def readFile(path: Path): Either[Vector[String], Vector[GoldMember]] =
    (try Right(Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector)
    catch { case NonFatal(e) => Left(Vector(s"cannot read $path: ${e.getMessage}")) })
      .flatMap(parse)

  /**
   * Reads a manifest file ONCE and returns the SHA-256 (hex) of exactly the bytes that are then
   * parsed (RV-P5-05 #5): the freeze check and the members can never come from two different reads.
   * The bytes must be valid UTF-8 (malformed input is refused, as by [[readFile]]); lines are split
   * on `\n`, `\r\n` or `\r`, as by [[readFile]].
   */
  def readFileWithSha256(path: Path): Either[Vector[String], (String, Vector[GoldMember])] =
    (try Right(Files.readAllBytes(path))
    catch { case NonFatal(e) => Left(Vector(s"cannot read $path: ${e.getMessage}")) })
      .flatMap { bytes =>
        val sha = MessageDigest
          .getInstance("SHA-256")
          .digest(bytes)
          .map(b => f"${b & 0xff}%02x")
          .mkString
        (try
          Right(
            StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString
          )
        catch {
          case NonFatal(e) => Left(Vector(s"cannot read $path: not UTF-8 (${e.getMessage})"))
        })
          .flatMap(text => parse(text.split("\r\n|\r|\n", -1).toVector))
          .map(ms => (sha, ms))
      }

  /**
   * Writes a new manifest; refuses to overwrite an existing file. The write is all-or-nothing
   * ([[CreateOnceFile]], RV-P5-04 N4): an I/O error never leaves a partial manifest.
   */
  def writeNew(path: Path, members: Vector[GoldMember]): Either[String, Unit] =
    if (Files.exists(path)) Left(s"$path exists; a membership manifest is never overwritten")
    else
      CreateOnceFile
        .write(path, toJsonl(members))
        .left
        .map(e =>
          if (Files.exists(path)) s"$path exists; a membership manifest is never overwritten" else e
        )
}
