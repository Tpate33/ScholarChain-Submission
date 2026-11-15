package scholarchain.entityresolution.policy

import scala.collection.immutable.SortedMap

import scholarchain.core.MatchEntityKind

/**
 * Result of the plausibility test: whether the pair is a plausible identity match, and the clauses
 * that were evaluated (each `<clause>=<true|false>`, for the decision record).
 */
final case class PlausibilityResult(plausible: Boolean, clauses: Vector[String])

/**
 * "Plausible identity match" (human decision 1, Phase 5): whether the NON-IDENTIFIER evidence of a
 * pair is consistent with the two records being one entity. It reads only name, context, title, and
 * author features of the candidate's `featureVersion` (er-features.v2); it never reads an `id.*`
 * feature, an exact-identifier agreement, the score, or any label.
 *
 * Why it exists: a differing identifier alone does not show that two unrelated records are a
 * conflicted identity. Two different papers carry different DOIs; two different people carry
 * different ORCID iDs. Only when the other evidence says "these could be the same entity" does an
 * authoritative disagreement become a CONFLICT that needs a manual decision. When the other
 * evidence does not support identity, the disagreement is a second, independent signal that the
 * records differ, and the pair is REJECTED (rule [[Rules.disagreeImplausible]]).
 *
 * The test is deliberately generous (it errs toward "plausible"): a false "plausible" sends a pair
 * to a manual CONFLICT decision, while a false "implausible" rejects it automatically.
 *
 * '''Researcher''' (bounds in [[ResearcherPlausibility]]): plausible iff all of
 *   1. family: `name.family_key_agree = 1`, or `familySimMin <= name.family_sim < 1` (a near-equal
 *      family key such as a one-letter transliteration difference; the `< 1` excludes two empty
 *      keys, whose similarity is 1 while `name.family_key_agree` is 0);
 *   2. initials: `name.initials_compatible = 1`, or (`missingGivenNamesCompatible` and
 *      `name.given_both = 0`: one side has no given name, so nothing contradicts);
 *   3. not two positions on one work: `context.same_work = 0`;
 *   4. not different DBLP homonym numbers: not (`name.homonym_suffix_both = 1` and
 *      `name.homonym_suffix_agree = 0`).
 *
 * Rationale: clauses 1 and 2 are the name agreement under which the blocking keys group mentions;
 * clauses 3 and 4 are the deterministic non-identity evidence of er-policy.v1 (two authors of one
 * paper, or two people DBLP numbers apart), which no identifier disagreement can make plausible.
 *
 * '''Work''' (bounds in [[WorkPlausibility]]): plausible iff clause T or clause TA holds:
 *   - T (near-identical title): `title.exact = 1`, or `titleSimMin <= title.sim < 1` (the `< 1`
 *     excludes two titles that both fold to empty text);
 *   - TA (re-titled version by the same team): `authors.both = 1` and `authors.sequence_lcs_ratio
 *     >= authorSequenceLcsMin` and `title.token_jaccard >= titleJaccardWithAuthorsMin`.
 *
 * Rationale: a preprint and its version of record, or two source copies of one paper, keep a
 * near-identical title (T) or keep the author team and most title words (TA). An author team alone
 * is not enough: one team writes many distinct papers. Years are not used: a preprint and its
 * version of record can be years apart.
 */
object Plausibility {

  private def v(f: SortedMap[String, Double], k: String): Double = f.getOrElse(k, 0.0)
  private def flag(f: SortedMap[String, Double], k: String): Boolean = v(f, k) == 1.0

  def researcher(p: ResearcherPlausibility, f: SortedMap[String, Double]): PlausibilityResult = {
    val sim = v(f, "name.family_sim")
    val family =
      flag(f, "name.family_key_agree") || (sim >= p.familySimMin && sim < 1.0)
    val initials =
      flag(f, "name.initials_compatible") ||
        (p.missingGivenNamesCompatible && !flag(f, "name.given_both"))
    val notSameWork = !flag(f, "context.same_work")
    val notHomonym =
      !(flag(f, "name.homonym_suffix_both") && !flag(f, "name.homonym_suffix_agree"))
    PlausibilityResult(
      family && initials && notSameWork && notHomonym,
      Vector(
        s"family=$family",
        s"initials=$initials",
        s"not-same-work=$notSameWork",
        s"not-homonym-differs=$notHomonym"
      )
    )
  }

  def work(p: WorkPlausibility, f: SortedMap[String, Double]): PlausibilityResult = {
    val sim = v(f, "title.sim")
    val t = flag(f, "title.exact") || (sim >= p.titleSimMin && sim < 1.0)
    val ta = flag(f, "authors.both") &&
      v(f, "authors.sequence_lcs_ratio") >= p.authorSequenceLcsMin &&
      v(f, "title.token_jaccard") >= p.titleJaccardWithAuthorsMin
    PlausibilityResult(t || ta, Vector(s"title=$t", s"title-and-authors=$ta"))
  }

  def of(
      p: MatchPolicy,
      kind: MatchEntityKind,
      f: SortedMap[String, Double]
  ): PlausibilityResult =
    kind match {
      case MatchEntityKind.Researcher => researcher(p.researcherPlausibility, f)
      case _ => work(p.workPlausibility, f)
    }
}
