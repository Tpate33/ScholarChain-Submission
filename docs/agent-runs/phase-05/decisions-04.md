# Phase 5 human instructions after RV-P5-02 / ER-P5-05 (transcribed by the orchestrator)

Received 2026-11-05. This is a verbatim transcription, not a human-gate record.

```text
Proceed with the required download-script repairs already identified by
the reviewer, within the existing acquisition scope. Verify the fixes and
present the reviewed download command afterward; do not download yet.

For blind labeling:
- Mask DBLP homonym suffixes and person IDs in the labeling display,
  including identifiers embedded in URLs.
- Preserve original source records and provenance separately.
- Keep relevant publication, affiliation, and coauthor evidence available.
- If the remaining evidence is insufficient, allow an uncertain label.
  Do not force a decision or use DBLP's identity assignment as the gold label.

Set labeling capacity to 240 held-out pairs using the documented seeded,
policy-independent sampling method. Report researcher/work counts and
selection strata. Freeze membership before labeling. Do not replace
uncertain cases based on system predictions; report exclusions and coverage.
At least 200 pairs must actually receive human review.

Before more policy implementation, provide one consolidated decision brief:
the full definitions of A/B/C, your recommended labeling target, and the
exact agreement/disagreement precedence for each identifier scheme.
Those choices remain pending until I respond.

Prepare the narrowly scoped label-path and evaluation-command amendment
for human ratification. Do not activate it before approval and the required
session restart.

Hold the next comprehensive independent test/review cycle until the policy
and blind-view changes are integrated. Keep the focused download-script
review separate because it is needed before acquisition.

Preserve prerequisite acceptance and recovery evidence. No commits,
pushes, AWS operations, or paid model calls.
```

## Status after this instruction

| Item | Status |
|---|---|
| DS-P5-04: reviewer findings 1, 2, 4, 7 (download time bound, stuck resume, non-atomic excerpt/ledger write, fixture gaps) | authorized; other RV-P5-02 minors and nits stay recorded as open |
| RV-P5-03: focused re-review of download scripts | authorized after DS-P5-04 |
| Full download | NOT authorized |
| ER-P5-06: blind-view masking of DBLP homonym suffixes and person IDs (including in URLs); original records and provenance preserved; "unsure" label kept | authorized |
| Labeling capacity | N = 240 held-out pairs, seeded policy-independent sample; membership frozen before labeling; uncertain cases never replaced; >= 200 pairs must receive human review |
| Consolidated decision brief | orchestrator: `decision-brief-01.md` |
| A/B/C, precedence, P-1/P-2/P-6, OpenAlex author, er-policy.v3 | pending human response |
| FE-P5-01 label-path / evaluation-command amendment | draft only: returned as a diff in the agent report; live constitutional files are NOT edited, because the hooks read them on every call |
| Comprehensive test-engineer / code-reviewer cycle | held until the policy and blind-view changes are integrated |

Note on the 240 capacity: the seeded sample requires a population of at least 240 held-out
candidate pairs. DS-P5-01 estimated ~130 without dblp and ~600 with dblp (estimates, not
observed). If the population is smaller, the census is all there is; this will be reported, not
padded.
