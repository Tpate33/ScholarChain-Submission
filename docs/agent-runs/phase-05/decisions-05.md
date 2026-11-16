# Phase 5 human policy decisions (transcribed by the orchestrator)

Received 2026-11-06 in response to `decision-brief-01.md` and the ER-P5-06 report. This is a
verbatim transcription, not a human-gate record.

```text
My policy decisions:

1. Select Option A for Phase 5. Define the target as the same publication
   entity at the same publication stage, allowing revisions within that
   entity. Do not describe it as byte-identical text. Preprint versus
   published article remains non-match. Record this definition before
   labeling. Defer the extra lifecycle question and aggregation to Phase 6.

2. Approve the narrow precedence approach:
   - Authoritative identifier disagreements prevent automatic acceptance,
     even when another identifier agrees.
   - Differing source-record keys alone do not prove an identity conflict.
   - Stronger identifier agreement may override a lower-tier blocking
     disagreement, with explicit reason codes and preserved provenance.
   - OpenAlex/S2 author-ID disagreements may block inferred acceptance
     without being authoritative conflicts; stronger researcher identifiers
     may override them.

3. Approve P-1's evidence-only treatment of differing algorithmic work-record
   IDs, and P-2's block-inferred-acceptance treatment of DBLP/Scopus keys.

4. Do not automatically accept a version-level match solely because an
   OpenAlex W ID agrees. The brief itself identifies cross-stage clustering.
   Require corroborating publication-stage evidence, or leave unresolved.
   Apply and document the same reasoning for other cluster IDs where relevant.
   Preserve all README-required exact-identifier rules.

5. Approve P-3, P-4 and P-6: version the policy as v3, retire v2 from active
   use while preserving history/tests, describe scores as uncalibrated,
   and remove the blanket source-record-disagreement penalty.
   Keep scheme-specific disagreement handling.

For evaluation, count unsure as human-reviewed but report it separately,
exclude it from binary precision/recall, and never present review count as
the number of definitive gold labels. Remove held-out --provisional metrics;
development evaluation may remain separate.

Integrate these choices, then run the planned independent testing/review.
Constitutional ratification and the full download remain pending.
Do not commit, push, deploy, or make paid calls.
```

## Decision status after this instruction

| Item | Decision |
|---|---|
| Labeling target | Option A: same publication entity at the same publication stage, revisions within it allowed; not "byte-identical"; preprint vs published = non-match. Lifecycle question and aggregation deferred to Phase 6 (P-7). |
| Precedence | narrow reading approved (decision-brief-01 §2.2), with item 4 below as an amendment |
| P-1 | approved: OpenAlex W, MAG, S2 corpus/paper disagreements are evidence-only |
| P-2 | approved: dblp-key and Scopus EID disagreements block inferred acceptance; never CONFLICT or REJECTED on their own |
| OpenAlex/S2 author IDs | approved: block inferred acceptance; not authoritative conflicts; T1 researcher IDs override |
| OpenAlex W (and other cluster IDs) agreement | NOT sufficient alone for ACCEPTED: requires corroborating publication-stage evidence, otherwise UNRESOLVED; README exact-identifier rules preserved |
| P-3 | approved: er-policy.v3; v2 retired from active use, history and tests preserved |
| P-4 | approved: "uncalibrated policy constant" wording |
| P-6 | approved: remove the blanket `id.other.conflict` penalty; keep scheme-specific handling |
| Unsure | counts as human-reviewed; reported separately; excluded from binary precision/recall; never presented as a definitive gold-label count |
| `--provisional` | removed for held-out evaluation; dev evaluation may stay separate |
| Constitutional amendment FE-P5-01, full download | still pending |

## Routing

ER-P5-07 (entity-resolution-engineer): integrate; then TE-P5-01 (test-engineer) and RV-P5-04
(code-reviewer, comprehensive).
