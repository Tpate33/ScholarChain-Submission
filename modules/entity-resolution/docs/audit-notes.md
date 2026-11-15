# Entity resolution: auditor notes

Owner: `entity-resolution-engineer`. This note is for auditors of stored match evidence. It is not
loaded at runtime and is not part of any versioned policy, feature, prompt, or label-guide resource.

## AN-1: folded arXiv agreement vs. stored features (er-policy.v8, er-features.v2)

Source: RV-P5-10 MINOR-1. Human decision (decisions-13): document the discrepancy before labeling,
and defer the review-row schema change.

### When it occurs

It occurs for a **work** pair whose two endpoints both carry arXiv IDs that share a **base
submission ID** but do not share the same versioned value. For example, A = `2101.00001v1` and
B = `2101.00001v2`, or A = `2101.00001v1` and B = `2101.00001`.

Since er-policy.v8, the policy compares arXiv IDs by base submission ID, which is the ID without its
trailing `vN` (`ExactIdentifiers.comparable`). Such a pair therefore **agrees** on arXiv. The policy
records the reason code `arxiv-agreement-by-base-submission-id` on every outcome of such a pair, and
the outcome follows the normal arXiv rule:

- If both sides are pure preprints (`same-stage-preprint`), the pair is `ACCEPTED` on the exact-id
  path with confidence 0.99 and rule `exact-id.agree.arxiv`.
- Otherwise the agreement does not decide. The pair gets `exact-id.unresolved.arxiv-agreement-cross-stage`
  and reason `arxiv-agreement-cross-stage`, and it can end `UNRESOLVED`. It can also end `CONFLICT`
  when another identifier disagrees.

### Which stored fields look contradictory, and why

The candidate's features and `exactIdAgreements` are computed by **er-features.v2**
(`MatchFeatures`), which compares canonical identifier values by **exact equality**. Versions are
not folded there. er-features was deliberately not bumped for v8: Hadoop candidate computation did
not change, and the policy version records the change. As a result, for the pair above the stored
evidence still shows:

| Stored location | Field | Stored value | Looks like |
|---|---|---|---|
| review-queue row (`er-review-queue-row.v4`) | `exactIdAgreements` | `[]` (no `arxiv:` entry) | no arXiv agreement |
| review-queue row, `features` | `id.arxiv.both` / `id.arxiv.agree` / `id.arxiv.conflict` | `1` / `0` / `1` | an arXiv disagreement |
| `MatchDecision.features` (core) | same `id.arxiv.*` keys | `1` / `0` / `1` | an arXiv disagreement |

The policy's own view of the agreement, which holds the original versioned values of both sides, is
`ResolutionOutcome.policyAgreements`. It is held **in memory only**. Neither the review-queue row
nor `MatchDecision` persists it.

There is one exception. When a folded agreement is contradicted and becomes a contradicted-agreement
`CONFLICT`, the `conflicts` entry does carry the original values.

The text of `er-policy.v8.json` (arxiv rule `scope`) says "the original versioned identifiers are
kept in the decision record". Under the current row and decision schemas that holds only through the
endpoint records, as described below. It does not hold for a persisted field.

### What the deciding evidence is

For these pairs, **read the decision from `rulesFired` and `reasonCodes`, not from `id.arxiv.*`**.
In a review-queue row these fields are `system.rulesFired` and `system.reasonCodes`. In a
`MatchDecision` they are top-level fields.

- `exact-id.agree.arxiv` in `rulesFired` means the arXiv agreement decided (ACCEPTED).
- `arxiv-agreement-by-base-submission-id` in `reasonCodes` means the agreement holds only through
  the base submission ID. No versioned value is shared byte for byte.
- `policyVersion` is `er-policy.v8` (or later), and `featureVersion` is `er-features.v2`.

Treat `id.arxiv.agree = 0` together with `id.arxiv.conflict = 1` as the exact-equality measurement of
er-features.v2. It is not the policy's verdict.

The reason code can under-report the fold (RV-P5-10 NIT-1). If the two sides share one arXiv value
exactly and agree on a different one only through the fold, the reason code is absent. In that case
the exact match already gives `id.arxiv.agree = 1` and a non-empty `exactIdAgreements`, so the
stored fields are not contradictory.

### How to reproduce the normalized agreement from the endpoint records

The stored row does not contain the arXiv values. It contains the endpoint references. The original
values are in the endpoint `WorkRecord`s of the candidate's curated run, and both originals are
always there.

1. **Find the endpoints.**
   - From a review-queue row: `sourceIdA` and `sourceIdB` are work IDs, and `candidateRunId` is the
     candidate's run. `endpointA` and `endpointB` give `sourceDataset`, `sourceRecordId`, `snapshot`, and
     `recordRunId`.
   - From a `MatchDecision`: `sourceId` and `targetId` are the work IDs, and `candidateId` links to
     the candidate and its curated run.
2. **Read the arXiv values of each endpoint.** In the `WorkRecord` whose `workId` equals each
   endpoint ID, read the `arxivId` field and any `additionalIds` entry of scheme `arxiv`. These are
   the core canonical values that er-features.v2 used. `EndpointIdentifiers` rebuilds the same sets
   with `MatchFeatures.workExactIds`, and it refuses a candidate whose stored flags these sets do not
   reproduce. Confirm that exact comparison yields no shared value. That is why the stored row shows
   `exactIdAgreements = []` and `id.arxiv.conflict = 1`.
3. **Compute each base submission ID.** For every value, strip one trailing `v` followed by one or
   more digits, and only if the remainder is still a valid arXiv ID:
   - new style: `NNNN.NNNN` or `NNNN.NNNNN`
   - old style: `archive[-sub][.XX]/NNNNNNN`

   For example, `2101.00001v2` becomes `2101.00001`, and `hep-th/9901001v3` becomes
   `hep-th/9901001`. A value without a version suffix is already its own base ID.
4. **Compare the base IDs.** If any base ID appears on both sides, the policy's arXiv agreement holds.
   It should then match `arxiv-agreement-by-base-submission-id` and, at `same-stage-preprint`, rule
   `exact-id.agree.arxiv`. The policy's agreement values (the in-memory `policyAgreements`) are
   every original value of either side whose base ID is shared, with versions kept. For the example,
   that is `2101.00001v1` and `2101.00001v2`.
5. **If the base IDs are disjoint**, the policy saw an arXiv disagreement (T1). The outcome is then
   `CONFLICT` when the pair is plausible and `REJECTED` otherwise, and the fold reason code must be
   absent.

**What is not folded (RV-P5-10 NIT-2).** The policy strips only the version suffix. It does not
equate subject-class forms of old-style IDs. For example, `math.AG/0101001` and `math/0101001` have
different base IDs under this rule. The pair is therefore treated as an arXiv disagreement
(`CONFLICT` if plausible, `REJECTED` otherwise), even though arXiv may regard them as one submission.
This errs in the safe direction (it never merges), and it is an open finding. Do not fold these forms
when you reproduce a decision: reproduce the rule as implemented. Old-style and new-style IDs never
share a base ID.

### Deferred change

The schema change that would remove this discrepancy is **deferred by human decision**
(decisions-13). That change would persist `policyAgreements` in the review-queue row as row schema
`er-review-queue-row.v5`, and possibly add a corresponding `MatchDecision` field, which would be owned
by `domain-engineer`. Until it lands, the endpoint records are the provenance of the folded
agreement.

### Labelers are not affected

Gold labelers see only the blind view (`BlindView`). `BlindView.HiddenFields` hides `features`,
`exactIdAgreements`, `rulesFired`, `reasonCodes`, `conflicts`, and every other system-decision field.
Labelers therefore never see the contradictory `id.arxiv.*` flags, so this discrepancy cannot steer
a gold label. It affects audit of stored evidence only.
