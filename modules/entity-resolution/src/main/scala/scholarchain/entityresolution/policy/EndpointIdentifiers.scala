package scholarchain.entityresolution.policy

import scholarchain.core.{
  ExactId,
  ExactIdScheme,
  MatchCandidate,
  MatchEntityKind,
  SourceAuthorProfile,
  WorkRecord
}
import scholarchain.entityresolution.{MatchFeatures, ResearcherEndpoint}

/**
 * The exact identifiers each endpoint of a candidate asserts, rebuilt from the endpoint records of
 * the same curated run with the er-features.v2 functions that produced the candidate
 * ([[MatchFeatures.researcherExactIds]], [[MatchFeatures.workExactIds]]). The candidate carries
 * only the agreeing values and per-scheme flags; disagreeing values (needed for
 * `MatchDecision.conflicts`) come from here.
 */
final case class EndpointIds(a: Vector[ExactId], b: Vector[ExactId])

object EndpointIds {
  val Empty: EndpointIds = EndpointIds(Vector.empty, Vector.empty)
}

object EndpointIdentifiers {

  /**
   * Identifiers of every endpoint of the given records by endpoint ID: each work, each author
   * mention on it, and each source author profile.
   */
  def index(
      works: Vector[WorkRecord],
      profiles: Vector[SourceAuthorProfile]
  ): Map[String, Vector[ExactId]] =
    (works.flatMap { w =>
      (w.workId -> MatchFeatures.workExactIds(w)) +:
        w.authors.map(m =>
          m.mentionId -> MatchFeatures.researcherExactIds(ResearcherEndpoint.OfMention(w, m))
        )
    } ++ profiles.map(p =>
      p.profileId -> MatchFeatures.researcherExactIds(ResearcherEndpoint.OfProfile(p))
    )).toMap

  /** The identifier sets of a candidate's endpoints, or why they cannot be found. */
  def of(
      c: MatchCandidate,
      index: Map[String, Vector[ExactId]]
  ): Either[String, EndpointIds] =
    (index.get(c.sourceIdA), index.get(c.sourceIdB)) match {
      case (Some(a), Some(b)) => Right(EndpointIds(a, b))
      case (a, b) =>
        Left(
          "no endpoint record for " +
            Vector(
              a.fold(Some(c.sourceIdA))(_ => None),
              b.fold(Some(c.sourceIdB))(_ => None)
            ).flatten
              .mkString(", ")
        )
    }

  private def flag(c: MatchCandidate, k: String): Option[Boolean] =
    c.features.get(k).map(_ == 1.0)

  private def raw(scheme: ExactIdScheme, ids: Vector[ExactId]): Set[String] =
    ids.iterator.filter(_.scheme == scheme).map(_.value).toSet

  /**
   * Why the endpoint identifiers do not reproduce the candidate (empty when they do): the
   * candidate's `exactIdAgreements` must equal the agreements of the two sets, every identifier
   * must be of the candidate's entity kind, and every `id.<scheme>.both/agree/conflict` flag and
   * the `id.other.*` counts must equal the values the sets imply. A mismatch means the endpoint
   * records are not the ones the candidate was built from, and the candidate is refused rather than
   * decided on inconsistent evidence.
   */
  def inconsistencies(c: MatchCandidate, ids: EndpointIds): Vector[String] = {
    val out = Vector.newBuilder[String]
    val kind = c.entityKind
    (ids.a ++ ids.b).filter(_.scheme.entityKind != kind).foreach { e =>
      out += s"endpoint identifier ${e.scheme.tag} does not identify a ${kind.tag}"
    }
    val agreements = MatchFeatures.exactIdAgreements(ids.a, ids.b)
    if (agreements != c.exactIdAgreements)
      out += "candidate exactIdAgreements differ from the endpoint identifiers"
    val flagged: Vector[ExactIdScheme] = kind match {
      case MatchEntityKind.Researcher => MatchFeatures.ResearcherIdSchemes
      case _ => Vector(ExactIdScheme.Doi, ExactIdScheme.Arxiv)
    }
    flagged.foreach { s =>
      val x = raw(s, ids.a)
      val y = raw(s, ids.b)
      val both = x.nonEmpty && y.nonEmpty
      val agree = both && x.exists(y.contains)
      Vector("both" -> both, "agree" -> agree, "conflict" -> (both && !agree)).foreach {
        case (suffix, expected) =>
          val k = s"id.${s.tag}.$suffix"
          flag(c, k) match {
            case Some(actual) if actual == expected => ()
            case Some(_) => out += s"candidate feature $k differs from the endpoint identifiers"
            case None => out += s"candidate feature $k is missing"
          }
      }
    }
    if (kind == MatchEntityKind.Work) {
      val others = MatchFeatures.OtherWorkIdSchemes.map { s =>
        val x = raw(s, ids.a)
        val y = raw(s, ids.b)
        val both = x.nonEmpty && y.nonEmpty
        (both && x.exists(y.contains), both && !x.exists(y.contains))
      }
      Vector(
        "id.other.agree" -> others.count(_._1).toDouble,
        "id.other.conflict" -> others.count(_._2).toDouble
      ).foreach { case (k, expected) =>
        if (!c.features.get(k).contains(expected))
          out += s"candidate feature $k differs from the endpoint identifiers"
      }
    }
    out.result()
  }
}
