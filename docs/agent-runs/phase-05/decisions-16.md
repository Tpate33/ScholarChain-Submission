# Phase 5 decisions 16 (human preferences for the FE-P5-03 amendment; orchestrator transcription)

Recorded by the orchestrator, 2026-11-14. Not a human-gate record and not a ratification. The amendment stays in draft; the held-out run stays pending human approval.

## Human text (verbatim)

```text
Record these as my preferred choices for the final amendment. Use D3(b)+(c) to freeze the policy, D5 L1 to keep held-out labels gitignored until acceptance, and D10(i)+(ii) to archive and protect the held-out run. Skip dev labels for now. Allow dev commands under D12 with S1, and use S2 and S3 under D8. Use D13(D) for the API snapshot tokens. Use D14 "no" and retain both split and hash checks in the evaluation tasks. For D15, use option (c), human-only held-out evaluation.

Show me the exact revised D15 text and any command or allowlist changes it requires. Keep the amendment in draft and the held-out run pending my approval.
```

## Preferred choices, as read against amendment-fe-p5-03.md section 6

| Decision | Preferred choice | Matches FE recommendation |
|---|---|---|
| D3 | (b) build-engineer refusal on a dirty tree in the held-out tasks + (c) disclosure and fresh held-out set rule in human-gate.md | yes |
| D4 | no dev label set for now (the dev evaluation task refuses without dev files) | n/a |
| D5 | L1: held-out labels gitignored until accepted; R4 not needed | yes |
| D8 | S1 + S2 + S3 | yes |
| D10 | (i) archive + (ii) guard rule `heldout-run-protected` | yes |
| D12 | dev rows allow, with S1 | yes |
| D13 | option D (dataset-engineer records the tokens; Hadoop copies them) | yes |
| D14 | no; the evaluation tasks keep passing both `--expect-split` and the freeze file | yes |
| D15 | (c) human-only held-out evaluation | **no** (FE recommended (a)) |

## Consequence noted by the orchestrator

D15(c) supersedes settled item D2 ("held-out evaluation is run by entity-resolution-engineer with ask") and the proposed `entity-resolution-engineer sbt goldSetEvalHeldout` (ask) row. The revised D15 text and any row, settings, probe and build changes are routed to factory-engineer (FE-P5-04, draft only).
