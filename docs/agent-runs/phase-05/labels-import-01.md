# Phase 5 held-out labels import 01 (human run; orchestrator assistance)

Assembled by the orchestrator on 2026-11-15. This file records observations, not a human-gate record. The human transcribed the human-approved held-out labels into `er-gold-label.v3` JSON Lines and placed the file. Every command that read label bytes ran in the human's regular Terminal. No agent and not the orchestrator read the label CSV, the staged JSONL or the placed labels file. No label value, note text or label count was given to this session or appears here. Nothing was evaluated, committed or pushed. No matching algorithm, threshold, policy, frozen file or evaluation rule was changed.

## Inputs

| Item | Value | Source |
|---|---|---|
| Labeling source | `~/Desktop/ScholarChain-labeling-heldout-v1/ScholarChain_Taufeeq_Patel_Human_Approved_Import_Ready.csv` (outside the repository) | human |
| CSV sha256 | `8ccaaa5a9bca7fcf77bb130c1d201d48f7868f3b0b6c1b403e5dc54d95ddcad2` | human Terminal (Step 1; same value in Step 2) |
| CSV structure | 278996 bytes; 241 lines (header + 240); UTF-8 with BOM (`ef bb bf`); CRLF line endings (241); 44 columns, `n,memberId,entityKind,labelingTarget,question,maskingNotes,A_*…,B_*…,label,notes` | human Terminal (Steps 1-2) |
| Frozen membership | `gold/entity-resolution/heldout-v1/er-gold-membership.jsonl`, sha256 `22779ca065cfb78f120e35e107c60f4fea15bb05ee998801c4e864dd2e21f8f4`, 240 members (187 researcher, 53 work), commit `01f1799` | validator (Step 2) |
| Label guide and schema | `er-label-guide.v1` (`modules/entity-resolution/src/main/resources/scholarchain/entityresolution/review/er-label-guide.v1.txt`); `er-gold-label.v3` (`GoldLabels.scala`) | orchestrator Read |

## Human statements (2026-11-15, verbatim excerpts)

```text
I have personally reviewed and approved the 240 candidate-pair decisions in my labeling spreadsheet.
```

```text
I confirm that the spreadsheet contained only blind-view evidence, with no model scores, predictions, or matching decisions shown during my review.
```

```text
My chosen labeler token is Taufeeq_Patel.
```

## `unsure` as a final label (orchestrator reading of repository sources)

- Guide (`er-label-guide.v1.txt:8-10`): every pair is answered with exactly one of match, non-match, unsure; "Unsure is a valid answer; it is never replaced by another pair."
- Schema (`GoldLabels.scala:82`): accepted labels are exactly `match`, `non-match`, `unsure`.
- Evaluator (`GoldSetEvaluation.scala:299-304`): `unsure` counts toward the 200 reviewed pairs (human instruction 2026-11-05) and is excluded from precision, recall, F1 and coverage; the report shows reviewed and definitive counts separately.
- Course specification (`README.md` at `d23d307`, Phase 5 evaluation requirements): "At least 200 manually reviewed candidate pairs"; it does not mention `unsure`. Counting `unsure` as reviewed is this project's interpretation (human decision 2026-11-05), not specification wording. The human holds the label counts; the orchestrator does not.

## Tools (written by the orchestrator in its session scratchpad, outside the repository; reviewed by the human with `less` before each run)

| Step | Script | Purpose |
|---|---|---|
| 2 | `validate_heldout_labels.py` | read-only CSV validation against the frozen membership, freeze record and blind view |
| 3 | `convert_heldout_labels.py` | transcription to `er-gold-label.v3`; memberId, entityKind, sourceIdA and sourceIdB from the membership manifest; labels copied exactly; writes one new file outside the repository |
| 4 | `check_heldout_labels_jsonl.py` | independent read-only mirror of `GoldLabels.ingest` plus a label-by-label cross-check with the CSV |

Scratchpad directory: `/private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/79a33d79-c61e-41c3-b50f-9bd6b18e156c/scratchpad/` (session-scoped; may be cleared). The scripts are not committed. No script prints label values, note text or label counts in its pasteable section; the Step 2 validator printed label counts in a separate PRIVATE section that the human kept out of this session.

## Step 2: CSV validation (human Terminal)

Pre-checks: `git status --porcelain=v1 --ignored=matching -- gold` printed nothing; `git diff --quiet 01f1799 -- gold` printed `gold tracked files equal 01f1799`.

```text
csv_crlf_line_endings: 241
csv_rows: 240
unique_memberIds: 240
membership_members: 240
researcher_members: 187
work_members: 53
rows_with_notes: 240
WARN: CSV row n=67 memberId=[redacted]: A_workTitle text differs from the blind view (spreadsheet edit?)
WARN: CSV row n=110 memberId=[redacted]: A_workTitle text differs from the blind view (spreadsheet edit?)
WARN: CSV row n=148 memberId=[redacted]: B_workTitle text differs from the blind view (spreadsheet edit?)
WARN: CSV row n=190 memberId=[redacted]: A_workTitle text differs from the blind view (spreadsheet edit?)
WARN: CSV row n=219 memberId=[redacted]: A_workTitle text differs from the blind view (spreadsheet edit?)
warnings: 5  failures: 0
RESULT: PASS
```

Exit status 0 (human statement). Member ID prefixes in the five WARN lines were redacted by the orchestrator on 2026-11-15 (human instruction: no member IDs in the repository); the CSV row numbers are kept. The five warnings are non-blocking: on those rows the A/B dataset, endpoint kind and source record ID equal the frozen blind view, so row alignment is confirmed; the converter does not use CSV titles. Whether any of the five labels relied on altered title text is for the human to assess and disclose in `human-gate.md`. Not investigated by the orchestrator (it would require reading the CSV).

## Step 3: conversion (human Terminal)

```text
written: /Users/taufeeqpatel/Desktop/ScholarChain-labeling-heldout-v1/er-gold-labels.jsonl
lines: 240  bytes: 125750  final byte LF: True
by entityKind: {'researcher': 187, 'work': 53}
schemaVersion=er-gold-label.v3 labelGuideVersion=er-label-guide.v1 labeler=Taufeeq_Patel labeledAt=2026-11-15T19:33:00Z
notes: copied verbatim where non-blank
output sha256: 0e267648b153047e0708781e9e335249b2d02bb3b94868acc6592b9bd32b692a
RESULT: WRITTEN
exit=0
```

`labeledAt` `2026-11-15T19:33:00Z` was supplied by the human (the orchestrator suggested it from the CSV's 13:33 local modification time, assuming UTC-6). Notes were kept (`--omit-notes` not used). The step's `git status -- gold` output was not shown in the screenshot; Step 4 shows `gold/` unchanged.

## Step 4: independent check of the staged JSONL (human Terminal)

```text
label lines: 240  manifest members: 240  missing: 0
distinct labelers: ['Taufeeq_Patel']
labels equal to CSV: 240/240
failures: 0
RESULT: PASS
exit=0
```

`git status --porcelain=v1 --ignored=matching -- gold` printed nothing. Limitation: the checker does not recompute `GoldMembership.memberId`; it requires each memberId to be a member of the sha256-verified manifest with identical entity kind and endpoint IDs. `GoldLabels.ingest` itself runs again, all-or-nothing, inside `sbt goldSetEvalHeldout`.

## Step 5: placement (human Terminal, verbatim)

```text
ls: gold/entity-resolution/heldout-v1/er-gold-labels.jsonl: No such file or directory
-rw-------  1 taufeeqpatel  staff  125750 Nov 15 13:59 gold/entity-resolution/heldout-v1/er-gold-labels.jsonl
0e267648b153047e0708781e9e335249b2d02bb3b94868acc6592b9bd32b692a  gold/entity-resolution/heldout-v1/er-gold-labels.jsonl
.gitignore:41:/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl    gold/entity-resolution/heldout-v1/er-gold-labels.jsonl
!! gold/entity-resolution/heldout-v1/er-gold-labels.jsonl
gold/entity-resolution/heldout-v1/er-blind-label-view.jsonl
gold/entity-resolution/heldout-v1/er-config.conf
gold/entity-resolution/heldout-v1/er-gold-membership.jsonl
gold/entity-resolution/heldout-v1/er-gold-membership.jsonl.sha256
```

The labels file is a regular file, sha256 equal to the Step 3 output, ignored by `.gitignore` line 41 (D5 = L1), and untracked; the four frozen files are the only tracked `gold/` files.

## Residual copies and risks

- Label bytes also exist outside `gold/**`, unfenced: the CSV and the staged `er-gold-labels.jsonl` in `~/Desktop/ScholarChain-labeling-heldout-v1/`. Retention or deletion is the human's decision.
- `sbt localPipelineHeldout` now refuses while the labels file exists (expected; the held-out pipeline run `attempt-1` is complete, see `heldout-run-01.md`).
- The conversion scripts live only in a session scratchpad and are not preserved as repository artifacts; their behaviour is described above.

## Next (human, not started)

`sbt goldSetEvalHeldout`, run only by the human in a regular Terminal after the pre-run checklist in `docs/agents.md` (Held-out runs). Afterwards, record only its exit status, runId and sha256 values in `human-gate.md`; keep metrics and listings out of the repository and every work order.
