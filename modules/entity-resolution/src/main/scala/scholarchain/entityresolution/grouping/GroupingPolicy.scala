package scholarchain.entityresolution.grouping

import scala.util.control.NonFatal

import upickle.default.{read, ReadWriter}
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{
  ExactIdScheme,
  MatchDecisionPath,
  MatchEntityKind,
  MatchOutcome,
  StableId,
  WorkRecord
}
import scholarchain.entityresolution.policy.MatchPolicy

/** The merge order of links (decision package B2). */
@allowUnknownKeys(false)
final case class MergeOrder(pathRank: Vector[String], tieBreak: Vector[String], definition: String)
    derives ReadWriter

/**
 * One cannot-link rule of one entity kind (decision package B3, as refined by the approved draft
 * O1-O8). The semantics are implemented in [[Grouping]]; the resource names, documents and pins
 * them, and the loader refuses a resource whose rule set differs from the implemented one.
 *
 * @param id
 *   rule ID (`N1`, `T1`, `BD`, `SW`, `HN`, `ST`)
 * @param kind
 *   `researcher` or `work`
 * @param schemes
 *   exact identifier scheme tags the rule compares (T1 and BD only); the reason code is suffixed
 *   with `.<scheme>`
 * @param reasonCode
 *   reason code recorded on both endpoints of a vetoed link (`grouping.veto.*`)
 */
@allowUnknownKeys(false)
final case class VetoRule(
    id: String,
    kind: String,
    schemes: Vector[String],
    reasonCode: String,
    definition: String,
    mirrors: String,
    stricterThanPairwise: Boolean
) derives ReadWriter {

  /** The reason codes this rule can record: one per scheme, or the bare code. */
  def codes: Vector[String] =
    if (schemes.isEmpty) Vector(reasonCode) else schemes.map(s => s"$reasonCode.$s")
}

/**
 * The versioned grouping policy `er-grouping.v1` (resource [[GroupingPolicy.DefaultResource]]). It
 * holds no score, weight or threshold: only the link outcome, the merge order, the veto rules, the
 * anchor order, the collision scope, the status rules, the representative order and the graph
 * eligibility rule.
 */
@allowUnknownKeys(false)
final case class GroupingPolicy(
    schemaVersion: String,
    groupingPolicyVersion: String,
    supportedMatchPolicies: Vector[String],
    description: String,
    linkOutcome: String,
    mergeOrder: MergeOrder,
    vetoRules: Vector[VetoRule],
    endpointCheck: String,
    endpointCheckDefinition: String,
    anchorOrder: Vector[String],
    anchorDefinition: String,
    collisionScope: String,
    statusRules: Vector[String],
    representativeOrder: Vector[String],
    representativeRule: String,
    representativeDefinition: String,
    graphEligibility: String
) derives ReadWriter {

  /** The rules of one entity kind, in resource order. */
  def rules(kind: MatchEntityKind): Vector[VetoRule] = vetoRules.filter(_.kind == kind.tag)

  /** The merge rank of a match path (its index in `mergeOrder.pathRank`). */
  def pathRank(p: MatchDecisionPath): Int = mergeOrder.pathRank.indexOf(p.tag)
}

object GroupingPolicy {

  val FileSchemaVersion: String = "er-grouping-file.v1"

  /** Class-path resource of the grouping policy. */
  val DefaultResource: String = "/scholarchain/entityresolution/grouping/er-grouping.v1.json"

  /** The (rule ID, kind) pairs that [[Grouping]] implements. */
  val ImplementedRules: Set[(String, String)] = Set(
    "N1" -> "researcher",
    "N1" -> "work",
    "T1" -> "researcher",
    "T1" -> "work",
    "BD" -> "researcher",
    "BD" -> "work",
    "SW" -> "researcher",
    "HN" -> "researcher",
    "ST" -> "work"
  )

  /** Rules that compare identifier schemes (every other rule has no schemes). */
  val SchemeRules: Set[String] = Set("T1", "BD")

  /** The implemented merge tie-break after the path rank. */
  val TieBreak: Vector[String] =
    Vector("confidence-descending", "source-id-utf8-ascending", "target-id-utf8-ascending")

  /** The implemented anchor order (decision package C1). */
  val AnchorOrder: Vector[String] = Vector("orcid", "dblp-pid", "openalex-author", "member")

  /** The anchor namespace of the member-ID fallback. */
  val MemberAnchor: String = "member"

  val CollisionScope: String = "all-final-groups"
  val EndpointCheckCode: String = "grouping.invariant.endpoint-veto"
  val VetoPrefix: String = "grouping.veto."

  private def text(field: String, s: String): Vector[String] =
    if (s == null || s.trim.isEmpty) Vector(s"$field must be documented") else Vector.empty

  /** Every problem of a grouping policy on its own (empty when valid). */
  def validate(g: GroupingPolicy): Vector[String] = {
    val out = Vector.newBuilder[String]
    def need(ok: Boolean, msg: => String): Unit = if (!ok) out += msg
    need(g.schemaVersion == FileSchemaVersion, s"schemaVersion must be $FileSchemaVersion")
    need(StableId.isToken(g.groupingPolicyVersion), "groupingPolicyVersion must be a token")
    need(
      g.supportedMatchPolicies != null && g.supportedMatchPolicies.nonEmpty &&
        g.supportedMatchPolicies.forall(StableId.isToken),
      "supportedMatchPolicies must be a non-empty list of tokens"
    )
    need(
      g.supportedMatchPolicies == null ||
        !g.supportedMatchPolicies.contains(g.groupingPolicyVersion),
      "the grouping policy is versioned separately from every match policy"
    )
    need(g.linkOutcome == MatchOutcome.Accepted.tag, "linkOutcome must be ACCEPTED")
    val mo = g.mergeOrder
    need(mo != null, "mergeOrder is required")
    Option(mo).foreach { m =>
      val tags = MatchDecisionPath.values.toVector.map(_.tag)
      need(
        m.pathRank != null && m.pathRank.sorted == tags.sorted && m.pathRank.distinct == m.pathRank,
        s"mergeOrder.pathRank must order exactly the match paths ${tags.mkString(", ")}"
      )
      need(m.tieBreak == TieBreak, s"mergeOrder.tieBreak must be ${TieBreak.mkString(", ")}")
      text("mergeOrder.definition", m.definition).foreach(out += _)
    }
    val rules = Option(g.vetoRules).getOrElse(Vector.empty)
    val keys = rules.map(r => r.id -> r.kind)
    need(keys.distinct.size == keys.size, "vetoRules: a (rule, kind) pair appears twice")
    need(
      keys.toSet == ImplementedRules,
      s"vetoRules must be exactly ${ImplementedRules.toVector.sorted.mkString(", ")}; found " +
        keys.sorted.mkString(", ")
    )
    rules.foreach { r =>
      val f = s"vetoRules.${r.id}.${r.kind}"
      val kind = MatchEntityKind.fromTag(r.kind)
      need(
        StableId.isToken(r.reasonCode) && r.reasonCode.startsWith(VetoPrefix),
        s"$f.reasonCode must be a token starting with $VetoPrefix"
      )
      val schemes = Option(r.schemes).getOrElse(Vector.empty)
      if (SchemeRules.contains(r.id)) {
        need(schemes.nonEmpty, s"$f.schemes must be non-empty")
        schemes.foreach { s =>
          need(
            ExactIdScheme.fromTag(s).exists(x => kind.contains(x.entityKind)),
            s"$f.schemes: '$s' is not a ${r.kind} identifier scheme"
          )
        }
        need(schemes.distinct == schemes, s"$f.schemes has a duplicate")
      } else need(schemes.isEmpty, s"$f.schemes must be empty")
      text(s"$f.definition", r.definition).foreach(out += _)
      text(s"$f.mirrors", r.mirrors).foreach(out += _)
    }
    need(g.endpointCheck == EndpointCheckCode, s"endpointCheck must be $EndpointCheckCode")
    text("endpointCheckDefinition", g.endpointCheckDefinition).foreach(out += _)
    need(g.anchorOrder == AnchorOrder, s"anchorOrder must be ${AnchorOrder.mkString(", ")}")
    text("anchorDefinition", g.anchorDefinition).foreach(out += _)
    need(g.collisionScope == CollisionScope, s"collisionScope must be $CollisionScope")
    need(
      g.statusRules != null && g.statusRules.nonEmpty && g.statusRules.forall(s =>
        s != null && s.trim.nonEmpty
      ),
      "statusRules must be documented"
    )
    val namespaces = WorkRecord.NamespaceOf.values.toVector.sorted
    need(
      g.representativeOrder != null &&
        g.representativeOrder.sorted == namespaces &&
        g.representativeOrder.distinct == g.representativeOrder,
      s"representativeOrder must order exactly the work namespaces ${namespaces.mkString(", ")}"
    )
    need(
      g.representativeOrder != null &&
        g.representativeOrder.headOption
          .contains(WorkRecord.NamespaceOf(scholarchain.core.SourceType.Dblp)),
      "representativeOrder must start with the DBLP namespace (decision E2: a counted work is a DBLP representative)"
    )
    need(StableId.isToken(g.representativeRule), "representativeRule must be a token")
    text("description", g.description).foreach(out += _)
    text("representativeDefinition", g.representativeDefinition).foreach(out += _)
    text("graphEligibility", g.graphEligibility).foreach(out += _)
    out.result()
  }

  /**
   * Every problem of using `g` with the match policy `p` (empty when they fit): `p` must be a
   * supported match policy, and the T1 and BD schemes of each kind must be exactly the policy's
   * `conflict-if-plausible` (T1) and blocking (`block-inferred-acceptance[-if-plausible]`) schemes,
   * so that the group vetoes mirror the pairwise rules.
   */
  def checkAgainst(g: GroupingPolicy, p: MatchPolicy): Vector[String] = {
    val supported =
      if (g.supportedMatchPolicies.contains(p.policyVersion)) Vector.empty
      else
        Vector(
          s"match policy ${p.policyVersion} is not supported by ${g.groupingPolicyVersion} " +
            s"(supported: ${g.supportedMatchPolicies.mkString(", ")})"
        )
    val schemes = Vector(MatchEntityKind.Researcher, MatchEntityKind.Work).flatMap { k =>
      val kp = p.kind(k).toVector
      val tier1 = kp.flatMap(_.identifierRules.filter(_.isTier1).map(_.scheme)).toSet
      val blocking =
        kp.flatMap(_.identifierRules.filter(_.blocksInferredAcceptance).map(_.scheme)).toSet
      g.rules(k).collect {
        case r if r.id == "T1" && r.schemes.toSet != tier1 =>
          s"T1 ${k.tag} schemes ${r.schemes.mkString(",")} differ from the T1 schemes of " +
            s"${p.policyVersion} (${tier1.toVector.sorted.mkString(",")})"
        case r if r.id == "BD" && r.schemes.toSet != blocking =>
          s"BD ${k.tag} schemes ${r.schemes.mkString(",")} differ from the blocking schemes of " +
            s"${p.policyVersion} (${blocking.toVector.sorted.mkString(",")})"
      }
    }
    supported ++ schemes
  }

  /** Parses and validates a grouping policy document. */
  def parse(json: String): Either[Vector[String], GroupingPolicy] =
    (try Right(read[GroupingPolicy](json))
    catch {
      case NonFatal(e) => Left(Vector(s"grouping policy is not decodable: ${e.getMessage}"))
    }).flatMap { g =>
      val problems = validate(g)
      if (problems.isEmpty) Right(g) else Left(problems)
    }

  /** The resource text of [[DefaultResource]]. */
  def resourceText: Either[String, String] = MatchPolicy.resourceText(DefaultResource)

  /** The versioned grouping policy of this module. */
  def loadDefault(): Either[Vector[String], GroupingPolicy] =
    resourceText.left.map(Vector(_)).flatMap(parse)
}
