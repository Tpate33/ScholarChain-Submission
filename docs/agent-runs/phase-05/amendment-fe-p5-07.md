# FE-P5-07 (consolidated): held-out runs human-only, gold set, label and output fences

Drafted by factory-engineer under work order FE-P5-08 (Phase 5, draft only), 2026-11-14, from the human instruction in `docs/agent-runs/phase-05/decisions-22.md`. Status: **DRAFT, awaiting human ratification.** Nothing is applied, committed or activated. No held-out task has run.

## 0. Supersession and how to apply

- **This text supersedes FE-P5-06 for application.** Apply it as **one patch against the current tree**: the live constitutional files at HEAD `36ffcf95e585c7def6aa06835a784820936e1b60`, whose `git diff --stat -- .claude CLAUDE.md docs/agents.md .mcp.json` was empty when this draft was made. Do **not** apply FE-P5-06 first, and do not apply FE-P5-07 as a delta on top of it.
- **FE-P5-06 stays the historical record.** It was ratified in decisions-19 and is archived as `docs/agent-runs/phase-05/amendment-fe-p5-06.md`. Its content is carried here unchanged in substance. Its hunk headers are not usable (erratum, section 12).
- **Content = FE-P5-06 + the FE-P5-07 delta draft (decisions-21), unchanged in substance, + the R6 coverage statement (decisions-22).** The only new text beyond the delta draft is the R6 coverage paragraph in docs/agents.md "Held-out runs", the rewritten held-out residuals paragraph, and the statements in this file.
- **Patch file:** `fe-p5-07.patch`, sha256 `<PATCH-SHA256>`. The human computes the value with `shasum -a 256 fe-p5-07.patch` before applying, and records it in `human-gate.md`. The complete post-patch files are in `post/` next to the patch.
- **Placeholders left literally in the patch, for the human to fill after applying:** `<NEW-SHA256>` in `docs/agents.md` (post line 132; patch line 555), which is the sha256 of the post-patch `.claude/hooks/bash-allowlist.tsv`, and `<RATIFICATION-DATE>` in `docs/agents.md` (post line 546, the G9 row; patch line 767).

## 1. Authority basis

- The course specification sections "Agent authority and ownership matrix", "Required hook and permission behavior" and Phase 5 ("Gold labels are human-reviewed"), README.md at `d23d307`.
- docs/agents.md: the matrix rows for `hadoop-engineer` ("Local or EMR sample run") and `entity-resolution-engineer` ("Gold-set evaluation"); the B10 placeholder rows `hadoop-engineer sbt localPipeline` and `entity-resolution-engineer sbt goldSetEval`; gap G9; the Human gates Phase 5 entry; factory-engineer ownership of `CLAUDE.md`, `docs/agents.md`, `.claude/agents/**`, `.claude/hooks/**`, `.claude/settings.json`.
- Human decisions: 2026-11-04, -05, -06 (blind labelling, freeze before labelling, recorded manifest SHA-256), decisions-15 (2026-11-13), decisions-16 to -18 (FE-P5-04 and FE-P5-05 decisions, D3 to D15, R6), **decisions-19 (ratification of FE-P5-06)**, decisions-20 (BE-1, BE-2, HD-1 repairs), **decisions-21** (both held-out tasks human-only; BE-6 accepted; D3(b) rewritten; no guard expansion; `datasets/downloads/` and `.DS_Store` handling kept; HD-1 stays in Hadoop; core validation deferred), **decisions-22** (keep in draft; confirm human-only runs and the removed runMain fallback; document R6 coverage; one valid patch; report counts separately).
- Amendment chain: FE-P5-03, FE-P5-04, FE-P5-05, FE-P5-06 (ratified), FE-P5-07 delta draft (decisions-21), this consolidated text.

## 2. Decisions carried (unchanged in substance)

| ID | Decision as carried |
|---|---|
| G9-a | `gold/**` -> `HUMAN` (ownership row, lib-ownership mirror and anchor, refusal message) |
| G9-b | `sbt localPipelineDev` (hadoop-engineer, allow) and `sbt goldSetEvalDev` (entity-resolution-engineer, allow) replace the B10 placeholders; **no row** for `sbt localPipelineHeldout` or `sbt goldSetEvalHeldout` |
| G9-c | R1-R3 and R5: Read fence on `gold/**/heldout*/er-gold-labels*`, Grep fence under `gold/` and for repository-wide gold/heldout/label globs, git diff/show fence (rule `heldout-label-read`); R4 not applied (D5 = L1) |
| D3 | (b) build-side clean-tree refusal, now described as implemented (section 6); (c) disclosure and a fresh held-out set after any post-result change |
| D4 | no dev labels fence |
| D5 | L1: held-out labels gitignored until acceptance |
| D8 | S1 (dev allows), S2 (label and gold Read/Edit denies plus R6 Read denies), S3 as revised: deny pairs for both held-out tasks, **no ask entry** |
| D10 | (i) the human archives the held-out attempt and records runId and `summary.txt` sha256; (ii) guard rule `heldout-run-protected` refuses any agent `sbt ... clean` while the held-out pipeline output exists, tested by two validator fixture checks (FE-P5-06) |
| D12 | dev rows allow + S1 |
| D13 | option D snapshot tokens (non-constitutional, done in DE-P5-04 / HD-P5-04) |
| D15(c) | `sbt goldSetEvalHeldout` human-only: guard rule `heldout-eval-human-only`, tripwire, settings deny, ER law 5 sentence and law 9 |
| R6 | Read/Grep fence on held-out **evaluation** output under `target/` (rule `heldout-eval-output-read`) plus two settings Read denies |
| decisions-21 | `sbt localPipelineHeldout` human-only: guard rule `heldout-pipeline-human-only`, tripwire, settings deny pair, hadoop-engineer law 10; the FE-P5-06 ask row is not created; BE-6 accepted for Phase 5; D3(b) text, remaining gaps and pre-run checklist added to docs/agents.md; HD-1 stays in Hadoop; core unique-source validation deferred; no guard expansion |
| decisions-22 | R6 coverage statement (section 7); runMain fallback removed (section 8); one consolidated patch |

## 3. Files and per-file justification (patch order)

| # | File | Justification | Patch lines |
|---|---|---|---|
| 1 | `.claude/hooks/lib-ownership.sh` | mirror of the docs ownership table: `gold/*` -> HUMAN after `*.sbt`; anchor `gold/x/y.scala` (G9-a) | 1-22 |
| 2 | `.claude/hooks/enforce-ownership.sh` | refusal message names the gold set (G9-a); no logic change | 23-34 |
| 3 | `.claude/hooks/bash-allowlist.tsv` | the two B10 placeholder rows become the two dev rows; no held-out row (G9-b, D15(c), decisions-21) | 35-51 |
| 4 | `.claude/hooks/guard-dangerous.sh` | G9-c fence, R6 fence, D10(ii), and one function `heldout_human_only_bash` holding both human-only rules, called before allowlist matching and from `tripwire` (so `--validate-allowlist` fails any active row naming either task) | 52-233 |
| 5 | `.claude/hooks/probes.tsv` | line 101 becomes a comment (moved to fixture checks, FE-P5-06); 222 appended lines (17 comments, 205 probes) covering every identity for every new rule and row | 234-471 |
| 6 | `.claude/hooks/validate-agent-files.sh` | two D10(ii) fixture checks in the mktemp fixture, `NOTE  <n> fixture probes run`, fixture-removal check (FE-P5-06) | 472-533 |
| 7 | `docs/agents.md` | ownership row, floor sentence, allowlist mirror rows and count, routing gates, Phase 5 human gate, the new "Held-out runs" subsection (D3(b), R6 coverage, gaps, BE-6, deferrals, checklist), guard layer row, Bash items 3 and 8-10, residuals paragraph, placeholder note, G9 row | 534-768 |
| 8 | `.claude/settings.json` | S1 allows (2), S2 Read denies (3) and Edit deny (1), deny pairs for both held-out tasks (4) | 769-807 |
| 9 | `.claude/agents/entity-resolution-engineer.md` | law 5 sentence, law 9, procedure 3-4, refusal text (D15(c)); frontmatter unchanged | 808-837 |
| 10 | `.claude/agents/hadoop-engineer.md` | law 10, procedure 3, refusal text (decisions-21); frontmatter unchanged | 838-end |

Not changed: `CLAUDE.md`, `.mcp.json`, every command file, every other hook and agent file. No mode change (`git apply` keeps the executable bits of guard-dangerous.sh and validate-agent-files.sh).

## 4. Privilege delta against the live files (HEAD 36ffcf9)

| Identity / surface | Change | Class |
|---|---|---|
| hadoop-engineer | + `sbt localPipelineDev` (allow; settings S1). Inactive `sbt localPipeline` row removed. Gains law 10: never runs the held-out pipeline or any `ErSplitPipelineMain heldout` run | widened within the ratified B10 intent (dev half only); held-out half withheld (narrowed) |
| entity-resolution-engineer | + `sbt goldSetEvalDev` (allow; S1). Inactive `sbt goldSetEval` row removed. Law 5 sentence, law 9 | widened within B10 (dev half only); held-out half withheld (narrowed) |
| All 18 agents and the orchestrator | lose: Read of held-out labels; Grep under `gold/` or repository-wide with a gold/heldout/label glob; git diff/show naming non-OK gold paths or printing held-out labels; any `sbt` token containing `goldsetevalheldout` or `localpipelineheldout` (any case); Read/Grep of held-out evaluation output under `target/` (and Grep of `target`, `target/streams/**`, `target/local-runs`); any `sbt` command with `clean` while the held-out pipeline output exists | narrowed |
| `gold/**` | UNASSIGNED -> HUMAN (no agent could write it before; now recorded) | ownership |
| settings.json | allow +2 (dev tasks; the guard still restricts them to their owners); ask 0; deny +8 (3 Read, 1 Edit, 4 Bash) | S1 widened within the guard; the rest narrowed |
| Orchestrator | no new rows | narrowed only |
| validator / probes | test harness only | unchanged authority |

Widening appears only where the ratified matrix already placed it (the B10 placeholder rows, now activated for dev only). No tool, MCP server or autonomy is added to any agent.

## 5. Confirmations required by decisions-22

**(a) Both held-out tasks are human-only.** For each of `sbt localPipelineHeldout` and `sbt goldSetEvalHeldout`:

| Control | localPipelineHeldout | goldSetEvalHeldout |
|---|---|---|
| No allowlist row | patch 35-51 adds only the two dev rows (post `bash-allowlist.tsv:96`, `:100`); no row names either task | same |
| Guard rule (before allowlist matching) | `heldout-pipeline-human-only`: patch 110-112 (post `guard-dangerous.sh:292-294`), called from `bash_check` at patch 202-204 (post `:505-507`), message `HP_DO` at patch 93-95 (post `:77-79`) | `heldout-eval-human-only`: patch 107-109 (post `:289-291`), same call site, message `HE_DO` at patch 90-92 (post `:74-76`) |
| Tripwire (`--validate-allowlist` fails any active row naming it) | `tripwire` calls the same function first: patch 118-120 (post `:300-302`); `validate()` applies `tripwire` to every active row (post `:603`) | same |
| Settings deny | patch 803-804 (post `settings.json:157-158`) | patch 801-802 (post `settings.json:155-156`) |
| Probes, every identity | owner hadoop-engineer patch 253 (post `probes.tsv:465`); the other 17 agents and the orchestrator patch 276-293 (post `:488-505`; code-reviewer refused first as read-only at post `:498`); forms: case, chained, trailing, `-batch`, absolute path, project-scoped, both tasks at patch 337, 340, 343, 346-347, 467-470 (post `:549`, `:552`, `:555`, `:558-559`, `:679-682`); sbt-only control patch 471 (post `:683`) | owner entity-resolution-engineer patch 255 (post `:467`); the other 17 agents and the orchestrator patch 314-331 (post `:526-543`; code-reviewer at post `:536`); forms post `:547-548`, `:551`, `:553-554`, `:556`, `:564`, `:667`, `:682`; unknown and empty identity post `:567-568`; sbt-only control post `:668` |
| Role rule | hadoop-engineer law 10, patch 845 (post `hadoop-engineer.md:26`) | entity-resolution-engineer law 9, patch 820 (post `entity-resolution-engineer.md:25`) |
| docs | Bash item 10, patch 746 (post `docs/agents.md:484`); floor sentence post `:128`; Held-out runs post `:333-454`; routing post `:287` | same |

**(b) The pre-activation runMain fallback is removed, and every held-out runMain form is human-only.**
- FE-P5-06 section 7 "Before activation" steps 1 and 8 (the `runMain ... ErSplitPipelineMain heldout` and `runMain ... GoldSetEvalMain ... --expect-split=heldout` forms) and its "After activation" split are **replaced** by section 8 below. Steps 1 and 8 use only the guarded tasks, which run the launch check, D3(b) and the task's environment isolation. The raw forms are not used for the held-out split, by anyone.
- No identity can run any `runMain` form: no allowlist row contains `runMain` (the `SBT:<project>` class admits only `compile`, `Test/compile`, `test`, `scalafmt`, `Test/scalafmt`, `scalafmtCheck`; post `guard-dangerous.sh:94`), so the default deny refuses them. The probes prove it: post `probes.tsv:565` (`entityResolution/runMain ... GoldSetEvalMain`, no-allowlist-entry) and `:566` (`hadoop/Test/runMain ... ErSplitPipelineMain heldout`, no-allowlist-entry).
- Role rules forbid them as well: hadoop-engineer law 10 ("any `ErSplitPipelineMain heldout` run") and entity-resolution-engineer law 9 ("any `GoldSetEvalMain` run on a held-out split").
- The remaining held-out runMain forms, the `ReviewQueueMain` membership and queue modes (steps 2 and 6), are not fallbacks: they have no sbt task, and they stay human-only in the human's Terminal.
- Residual: test code that `sbt test` runs could call these mains itself. That is outside the guard; code-reviewer review of test code is the control (docs "Limitations without an OS-level sandbox").

## 6. D3(b), remaining gaps, BE-6 and deferrals

These texts are part of the patch, in docs/agents.md "Held-out runs (Phase 5): build checks, accepted limitations, and Terminal checklist" (post lines 333-454). They are reproduced verbatim in Annex A so that this record stands alone:
- D3(b) "What the build checks" items 1-5 and the task-specific paragraph;
- the 12 remaining gaps;
- "BE-6: accepted limitation for Phase 5 (decisions-21)", with the permanent fix owned by `build-engineer` in a later phase;
- "Deferred (decisions-21)": HD-1 stays in `modules/hadoop`, and the core unique-source validation is deferred to `domain-engineer`;
- the 14-item pre-run Terminal checklist.

## 7. R6 coverage statement (decisions-22)

- **R6 protects held-out evaluation output only.** Its fences are:
  - the guard's Read and Grep refusals (rule `heldout-eval-output-read`) for `target/streams/**/goldsetevalheldout` and `target/local-runs/heldout` (any case);
  - its Grep refusal for `target`, `target/streams` and everything under it, and `target/local-runs`;
  - the settings Read denies `Read(./target/streams/**/goldSetEvalHeldout/**)` and `Read(./target/local-runs/heldout/**)`.
- **The held-out pipeline's output remains agent-readable:**
  - its candidate data and `summary.txt` under `modules/hadoop/target/local-pipeline/er-candidates/heldout/`;
  - its sbt envelope log, which is the `localPipelineHeldout` task directory under `target/streams/`. Read is not refused; Grep is refused there only because the `target/streams` Grep fence covers it.
- **No labels or metrics are in them.** The labels file is read only by `sbt goldSetEvalHeldout`. The pipeline's own JVM scratch under `target/local-runs/heldout/` happens to lie inside the R6 fence.
- **No new fence is added in this round** (decisions-21, decisions-22).
- Where the statement appears: docs "Held-out runs" (post `docs/agents.md:337`, patch 608), the held-out residuals paragraph (post `:490`, patch 752), and this section.
- Observed in the code: `HELDOUT_OUT_READ` and `HELDOUT_OUT_GREP` (post `guard-dangerous.sh:70-71`) and `gold_check` (post `:375-391`) match only paths starting with `target/`. No rule names `modules/hadoop/target/`.

## 8. Application procedure (HELD until the human ratifies this text)

1. **Ratify** this consolidated FE-P5-07, optionally after a code-reviewer pass on the archived copy. Decide the `<RATIFICATION-DATE>` value.
2. **Non-constitutional work orders: done** (decisions-19, -20): BE-P5-04, BE-P5-05, DE-P5-04, HD-P5-04, HD-P5-05, ER-P5-17; code review CR-P5-05 and CR-P5-06 (APPROVE; BE-6 accepted for Phase 5, decisions-21). The wording follow-ups in section 13 change no behaviour and may run before or after application.
3. **Close every Claude Code session.** In a Terminal at the repository root, follow `README.txt`:
   - archive this file;
   - `shasum -a 256` the patch;
   - confirm the controlled files are unchanged;
   - `git apply --check -v`, then `git apply -v`;
   - compare the result with `post/`;
   - fill `<NEW-SHA256>` and `<RATIFICATION-DATE>`;
   - check the executable bits.
4. **Terminal checks.** Run `bash -n` on the four changed hooks, `.claude/hooks/guard-dangerous.sh --validate-allowlist`, `python3 -I -m json.tool .claude/settings.json`, `.claude/hooks/validate-agent-files.sh`, then `--probes`. Expected results are in section 10.
5. **Commits, all by the human:** C (constitution only), then A (core, ER, tests, HD and build work, and the manifests with the stand-in codeVersion), then B. `git diff --stat A B` must show exactly the two run manifests.
6. **Fresh session.** hadoop-engineer sets `codeVersion` to A's 40-hex value in both manifests and runs `sbt check`. The human commits B.
7. **Post-restart observations:**
   - the orchestrator's Read of the held-out labels path is refused with `heldout-label-read`;
   - `sbt goldSetEvalHeldout` is refused with `heldout-eval-human-only`;
   - `sbt localPipelineHeldout` is refused with `heldout-pipeline-human-only`;
   - the S2 and deny rules match;
   - under L1, a repository-wide Grep skips an ignored dummy file (the human creates and deletes it in a Terminal);
   - after the first dev evaluation, list `target/streams/_global/goldSetEvalDev/`.
8. **Runs:** first the dev evidence run (hadoop-engineer `sbt localPipelineDev`, entity-resolution-engineer `sbt goldSetEvalDev`). Then the held-out sequence below, only with separate human approval, run by the human in their own Terminal after the pre-run checklist. `--probes` stays green after the held-out pipeline run (D10(ii) is tested only in the fixture).

**Held-out sequence (human Terminal only, plain batch sbt).**

Preconditions:
- this amendment is applied and validated, and the session restarted, so D10(ii), G9-c and R6 are live before any held-out output exists;
- commits A and B exist (`git diff --stat A B` lists only the two run manifests);
- the cap is 8 with `drop`, and the D13 tokens are ratified;
- `sbt check` passed at B;
- the L1 `.gitignore` labels line is committed;
- the cap-4 dev attempt is moved away: `mv modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1 modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1.cap4`;
- the pre-run Terminal checklist (docs/agents.md, Held-out runs) passes immediately before each held-out task.

Rehearsal: run steps 2-8 on dev first (`dev-v1`, `--expect-split=dev`). Run from `/Users/taufeeqpatel/Desktop/ScholarChain`. Set `G=gold/entity-resolution/heldout-v1`, and set `R` to the `output=` directory on the `[er-split] ... runId=... status=complete output=<dir>` line.

1. Checklist (the labels file must be absent), then `time sbt -Dsbt.supershell=false localPipelineHeldout`.
   - The held-out run manifest and both ledgers must be committed and unmodified.
   - Record every `[er-split]` line (`runId`, `distinct.total`, `oversize` as it comes; no cap re-tuning) and `echo $?`.
   - D10(i): run `shasum -a 256` on the attempt's `summary.txt`, archive the attempt outside the repository, and record both in `human-gate.md`.
   - From then on D10(ii) refuses any agent `sbt ... clean`, and you do not run `sbt clean` yourself before archiving.
   - The raw `sbt "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain heldout"` form is not used. It skips the launch check, D3(b) and the task's environment isolation.
2. `sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain membership datasets/er-candidates/split-assignment.tsv datasets/samples/er-candidates/PROVENANCE.tsv $R heldout $G/er-gold-membership.jsonl $G/er-blind-label-view.jsonl <SEED> 240"`. The printed `population` must equal `distinct.total`.
3. Write `$G/er-config.conf` (ER-P5-15 section 3 content, dummy values only).
4. `cd $G && shasum -a 256 er-gold-membership.jsonl > er-gold-membership.jsonl.sha256 && shasum -a 256 -c er-gold-membership.jsonl.sha256 && cd -`. The value must equal the printed `manifest sha256=`; record it in `human-gate.md`. Commit D: membership, blind view, `.sha256` and `er-config.conf`, before any label exists.
5. Label blindly from `$G/er-blind-label-view.jsonl` only, writing `$G/er-gold-labels.jsonl` (ignored under L1).
6. `export SCHOLARCHAIN_CONFIG_FILES="$PWD/$G/er-config.conf"`. `env | grep '^SCHOLARCHAIN_CFG_'` must print nothing. Then `sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain queue $G/er-gold-membership.jsonl $R $G/er-review-queue.jsonl --expect-split=heldout"`. Expect `not-generated=0`, `expect-split=heldout matches the manifest` and `config overrides=none`. Then `unset SCHOLARCHAIN_CONFIG_FILES`.
7. Commit E: the queue. Record `shasum -a 256 $G/er-gold-labels.jsonl` in `human-gate.md`.
8. Checklist again (the labels file must be a regular, ignored file), `unset SCHOLARCHAIN_CONFIG_FILES`, then `time sbt -Dsbt.supershell=false goldSetEvalHeldout`.
   - No `SCHOLARCHAIN_CFG_*` may be set; the task sets the config itself.
   - sbt exits 0 on success and non-zero otherwise. The error line names GoldSetEvalMain's code (2 refused, 3 below the minimum).
   - Record in `human-gate.md` only the exit status, the printed membership, config and labels sha256 values, and the pipeline runId.
   - Keep metrics and listings outside the repository and out of every work order until you accept the result. From then on D3(c) applies.
   - Afterwards, confirm that `target/streams/_global/goldSetEvalHeldout/` holds no evaluation output. If it does, archive and delete it in the Terminal before the next session.
   - The raw GoldSetEvalMain runMain form is not used, for the same reason as in step 1.

**Who may run what after application:**
- hadoop-engineer: `sbt localPipelineDev` (allow) only.
- entity-resolution-engineer: `sbt goldSetEvalDev` only.
- Human only, in a regular Terminal: `sbt localPipelineHeldout`, `sbt goldSetEvalHeldout`, every held-out runMain form (the `ReviewQueueMain` modes in steps 2 and 6), and every `gold/**` write.
- Every other identity, the orchestrator included: none of these.

## 9. Floor invariants

All are intact. Every change adds a refusal or narrows access, except S1 and the two dev rows activated within the ratified B10 intent. These are untouched:
- the dangerous-command guards and the default-deny gate;
- the test-stop hooks and the stale-marker refusal;
- the formatter and Scala auto-format;
- the secret-read denies;
- the terraform apply/destroy denies;
- the destructive-AWS and recursive-S3 denies;
- the EMR guards;
- the force-push denies;
- constitutional-write protection.

Each new refusal names its rule and says what to do instead: `heldout-label-read`, `heldout-eval-human-only`, `heldout-pipeline-human-only`, `heldout-eval-output-read`, `heldout-run-protected`.

## 10. Expected validation after application (computed from the `post/` files; not observed)

| Item | Expected | How derived (from post/) |
|---|---|---|
| Allowlist rows (docs mirror) | 119 | non-comment, non-directive lines of post `bash-allowlist.tsv` |
| `--validate-allowlist` | `PASS  bash-allowlist.tsv parses (94 identity rows, 3 groups)` | 119 - 25 group rows |
| Ownership rows | 46 (new `PASS  gold/** -> HUMAN`; anchor list matches) | post `docs/agents.md:53-98` table body |
| PYEOF lines | `PASS  guard decision core: one quoted PYEOF heredoc, 614 lines, parses as Python` | post guard lines 43-656 |
| guard-dangerous.sh total | 668 lines (`wc -l`) | post file |
| validate-agent-files.sh total | 428 lines (`wc -l`) | post file |
| probes.tsv | 683 lines, 22 comment lines, `NOTE  661 table probes run` | post file |
| Fixture probes | `NOTE  38 fixture probes run` and the two lines `PASS  probe D10(ii) service-engineer sbt clean check, held-out output absent -> ALLOW` and `... present -> BLOCK`; with scalafmt on the probe PATH: 37 and the existing `NOTE  scalafmt present on the probe PATH; C5 missing-tool probe skipped` | 36 live + 2 |
| Fixture section header | `== Fixture probes (C4 hash audit, C5 formatter, provenance, test marker, D10(ii) held-out run protection) ==` | post validator line 328 |
| B1 mirror | `PASS  docs/agents.md Bash allowlist block matches bash-allowlist.tsv (119 rows, sha256 <first 12 hex of NEW-SHA256>)`. Until `<NEW-SHA256>` is filled, this check FAILs as expected | |
| settings.json | allow 27 -> 29 (+2), ask 24 -> 24 (0), deny 180 -> 188 (+8); 341 lines | post file |
| docs/agents.md | 552 lines; mirror 119 rows; Held-out runs subsection 122 lines (333-454) | post file |
| Hooks | still 9 `.sh` files; `bash -n` PASS expected; frontmatter PASS for 18 agents | |
| `--probes` | `RESULT: PASS (0 failures)` | |

New probe output lines include:
- `PASS  probe [D21] hadoop-engineer sbt localPipelineHeldout (human-only, no row) -> BLOCK`;
- 17 lines `PASS  probe [D21] <identity> sbt localPipelineHeldout -> BLOCK`;
- `PASS  probe [G9-b] code-reviewer sbt localPipelineHeldout -> BLOCK`;
- the 5 `[D21]` lines of the last block.

The line `PASS  probe [entry] entry service-engineer: sbt clean check -> ALLOW` no longer appears.

Differences from the FE-P5-07 delta draft's predictions (119 / 94 / 46 / 614 / 429 / 661 / 38):
- All agree except "validator lines 429". That figure counted the empty line after the final newline; `wc -l` gives 428 for the same file.
- The same convention gives 668 for guard-dangerous.sh, against the draft's 669.
- The Held-out runs block is 122 lines, against the draft's 120: the R6 coverage paragraph and its blank line are new.

## 11. Remaining risks

1. Every post-application value is computed, not observed. No agent can run a patched copy, and no agent can run `git apply`.
2. Hook-gated settings rules with multiple wildcards are verified only after restart.
3. Agents can still Read the held-out pipeline output (section 7). This is by decision.
4. `HO_DO` says "Read or Grep dev output (... the goldSetEvalDev streams)", but the R6 Grep fence refuses Grep under `target/streams/**`, including the dev streams. This wording is inherited unchanged from the ratified FE-P5-06. It is a candidate for a later wording-only amendment.
5. The human's own Terminal is unguarded; for example, `sbt clean` there deletes held-out output.
6. BE-6 is closed only when the checklist runs. A change made between the checklist and the run is not detected, and dependency caches are not covered.
7. The name-substring rules refuse any agent sbt token containing either task name, including future key names. build-engineer keeps names clear of them.

## 12. FE-P5-06 hunk-header erratum

FE-P5-06 is authoritative for **content** only. Its diff headers are not machine-applicable:

| File | FE-P5-06 header | Problem |
|---|---|---|
| bash-allowlist.tsv | `@@ -95,6 +95,7 @@` | body has 7 old and 8 new lines |
| guard-dangerous.sh hunk 1 | `@@ -16,3 +16,12 @@` | body has 11 new lines |
| guard-dangerous.sh hunks 2-7 | `+60`, `+283`, `+318`, `+497`, `+512`, `+558` | new-side starts are 1-3 lines off (should be 59, 280, 315, 494, 509, 555) |
| guard-dangerous.sh hunk 6 | `@@ -405,8 +512,14 @@` | body has 9 old and 15 new lines |
| guard-dangerous.sh hunk 7 | `@@ -445,2 +558,3 @@` | body has 3 old and 4 new lines |
| probes.tsv hunk 1 | `@@ -101 +101 @@` | zero context (needs `--unidiff-zero`) |
| docs/agents.md | `@@ -127 +128 @@`, `@@ -131 +132 @@`, `@@ -195 +196,2 @@`, `@@ -199 +201 @@`, `@@ -272 +274 @@`, `@@ -286 +288 @@`, `@@ -326 +328 @@`, `@@ -337 +339 @@`, `@@ -354 +356 @@`, `@@ -416 +425 @@` | zero-context hunks |
| docs/agents.md | `@@ -358,2 +360,5 @@` | body has 1 old and 4 new lines |
| docs/agents.md | `@@ -362,2 +367,4 @@` | body has 3 old and 5 new lines, and elides line 364 as `...` |
| docs/agents.md | `@@ -385,2 +392,4 @@` | body has 3 old and 5 new lines |
| settings.json | `@@ -147,2 +154,4 @@` | body has 3 old and 5 new lines |
| entity-resolution-engineer.md | `@@ -35 +36 @@` | zero context |

Correct as written: lib-ownership.sh, enforce-ownership.sh, validate-agent-files.sh, the other settings hunks, and the first two ER hunks. `fe-p5-07.patch` was written fresh against the live bytes. It uses unified context and recounted headers, and it does not reuse any FE-P5-06 header.

## 13. Non-constitutional follow-ups (listed, not made)

1. build-engineer (wording only):
   - `build.sbt:44-49`: the `localPipelineHeldout` description should say "human Terminal only (decisions-21)";
   - `build.sbt:62-63`: no key name may contain `localPipelineHeldout`;
   - `project/LocalRuns.scala:20-21`: the wording should cover both held-out tasks;
   - optionally, `project/LocalRuns.scala:69-71`: the 90 s bound rationale;
   - later, the BE-6 permanent fix.
2. domain-engineer: the deferred core unique-source validation.
3. Orchestrator: archive this text; route nothing until the human ratifies it.

---

## Annex A: docs/agents.md "Held-out runs" subsection (verbatim from the patch; post lines 333-454)

### Held-out runs (Phase 5): build checks, accepted limitations, and Terminal checklist

Both held-out tasks, `sbt localPipelineHeldout` and `sbt goldSetEvalHeldout`, are human-only (D15(c), decisions-21; amendment FE-P5-07). No agent and not the orchestrator can start either: the guard refuses them by name (rules `heldout-pipeline-human-only` and `heldout-eval-human-only`), `settings.json` denies them, and no allowlist row names them. Both tasks also refuse inside any agent Bash call, because there is no terminal there. The human runs each as a plain batch sbt command from the repository root in a regular Terminal: never with `!` inside a Claude Code session, never through `sbt --client` or sbtn, an sbt server, BSP or an IDE, or the interactive sbt shell. No hook runs in that Terminal, so the checklist below is the human's own control. The held-out pipeline's output stays readable by agent Read (see R6 coverage below and the held-out residuals paragraph under Enforcement layers); the human inspects the run's Terminal output and records what `human-gate.md` needs.

**R6 coverage (decisions-22).** R6 protects held-out **evaluation** output only. Its fences are the guard's Read and Grep refusals (rule `heldout-eval-output-read`) for `target/streams/**/goldsetevalheldout` and `target/local-runs/heldout` (any case), its Grep refusal for `target`, `target/streams` and everything under it, and `target/local-runs`, and the `settings.json` Read denies `Read(./target/streams/**/goldSetEvalHeldout/**)` and `Read(./target/local-runs/heldout/**)`. The held-out **pipeline**'s candidate data and `summary.txt` under `modules/hadoop/target/local-pipeline/er-candidates/heldout/`, and its sbt envelope log (the `localPipelineHeldout` task directory under `target/streams/`), remain agent-readable: no rule refuses an agent Read of them, and Grep is refused only where the `target/streams` Grep fence applies. No labels or metrics are in them; the labels file is read only by `sbt goldSetEvalHeldout`, and the pipeline's own JVM scratch under `target/local-runs/heldout/` lies inside the R6 fence. No new fence is added in this round (decisions-21, decisions-22).

**What the build checks** (D3(b) as implemented in `project/LocalRuns.scala`, owner `build-engineer`; BE-P5-04, BE-P5-05; reviewed in CR-P5-06). Before it forks any JVM, each held-out task refuses unless all of the following hold, in this order:

1. **Launch (BE-2), checked first.** The command came from the batch command line (command channel none; the interactive shell's `console0` and every `network-N` channel of a thin client, BSP or IDE refuse); the sbt launcher arguments contain none of `--server`, `--detach-stdio`, `--client`, `-client`, `--jvm-client`, `-bsp`, `--bsp`, `shell`; `shell` is not queued; a console is present (JDK 22 and later: `Console.isTerminal()` is true, and a JDK 22 or later without that method refuses; earlier JDKs: `System.console()` is non-null and `jdk.console` is unset); and `/dev/fd/1` and `/dev/fd/2` are character devices other than `/dev/null` (an unreadable file type refuses).
2. **Index flags.** `git ls-files -v -z` over the scope `build.sbt project modules tests datasets gold` lists at least one tracked file and only `H` entries; `S` (skip-worktree), lower-case tags (assume-unchanged) and `M` (unmerged) refuse.
3. **Changes and ignored paths.** One call, `git status --porcelain=v1 -z --ignored=matching --untracked-files=all` over the same scope. Any modified, staged, deleted, renamed or untracked path refuses. Every ignored path must equal, as an exact string, an entry of the allow set: the `target/` directory of each sbt project whose base is in the scope (`modules/*/target/`, `tests/target/`), `project/target/`, `project/project/target/`, `datasets/downloads/` (kept by the human, decisions-21), and, for `goldSetEvalHeldout` only, `gold/entity-resolution/heldout-v1/er-gold-labels.jsonl`. Every other ignored path refuses, wherever its ignore rule comes from (any `.gitignore`, `.git/info/exclude`, `core.excludesFile`), including every `.DS_Store` (refusal kept by the human, decisions-21), `project/metals.sbt`, `project/project/metals.sbt` and `project/.bloop/`. Consequently `localPipelineHeldout` refuses while the held-out labels file exists.
4. **Root `.gitignore`.** A regular, symlink-free file, committed at HEAD and byte-identical to it (`git hash-object --no-filters`), for both held-out tasks.
5. **Git hygiene and failure.** Every inherited `GIT_*` variable is removed from the build's git processes (stdin closed, stderr discarded); the clean-tree calls run with `--no-optional-locks -c core.fsmonitor=false -c core.untrackedCache=false`; each git call is bounded at 30 s and killed on timeout. Any git start failure, non-zero exit, timeout or incomplete output refuses (fail closed).

Task-specific checks follow. The pipeline requires its committed, unmodified run manifest and sample ledgers and a bounded, symlink-free samples tree. The evaluation refuses any inherited `SCHOLARCHAIN_CFG_*` (checked right after the launch check), requires the four committed gold files byte-identical to HEAD, and requires the labels file to be a regular, symlink-free, git-ignored file. Both tasks send the forked child's output straight to the Terminal, never through an sbt log.

**Remaining gaps** (the build does not check these; "checklist" marks items the checklist below covers at run time). No guard expansion is made in Phase 5 (decisions-21).

1. BE-6: git configuration and attributes (`core.checkStat`, `core.trustctime`, clean and smudge filters, `core.attributesFile`, `.git/info/attributes`, the default global attributes file) can make `git status` report a clean tree while tracked bytes differ (accepted, see below; checklist).
2. Files placed by hand inside the allowed `target/` directories, for example a `core-site.xml` in `modules/hadoop/target/scala-*/test-classes/`, which is on `hadoop/Test/fullClasspath`; whether sbt's resource copy removes it is unverified (checklist, optional item).
3. Root launcher files `.jvmopts` and `.sbtopts` (and `.sbtrc` and the global `sbtopts` files), outside the scope (checklist).
4. `SBT_OPTS`, `JAVA_OPTS`, `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS` in sbt's environment (checklist).
5. JDK selector files (`.java-version`, `.tool-versions`, `.sdkmanrc`, mise files) (checklist).
6. `~/.sbt` global settings and plugins (checklist).
7. Dependency caches outside the repository (Coursier, Ivy, `~/.sbt/boot`); a modified cached jar would be on the classpath. Not covered.
8. Global git configuration (`~/.gitconfig`, `$XDG_CONFIG_HOME/git/config`), which the build's git calls read and the Bash audit does not see (checklist).
9. Pseudo-terminal wrappers (`script`, tmux logging, IDE terminals) pass the launch check, and their logs can capture held-out output (checklist: do not use them).
10. Submodule ignore settings: a committed gitlink with `submodule.<name>.ignore=all` would hide changes inside it, because no `--ignore-submodules=none` is passed (checklist).
11. Compile-before-check race: task dependencies compile before the clean-tree check, so an edit made and reverted in between is not seen; likewise a change after the check, during the run (checklist: no other writer open).
12. The allow path (console present, both descriptors character devices, channel none, nothing queued) has not been observed in a real Terminal; the first held-out run is its first observation, and the task refuses if a signal differs.

**BE-6: accepted limitation for Phase 5 (decisions-21).** CR-P5-06 finding BE-6 (minor) is accepted for Phase 5 without a build change. Rationale: both failure inputs need a deliberate change to git configuration or attributes by someone with write access to `.git/config`, `~/.gitconfig` or an attributes file. No agent Write/Edit and no allowlisted path argument can write `.git/**`; sbt-run code could, but `audit-bash-writes.sh` reports changes to `.git/config` and `.git/info/attributes` after the fact, while `~/.gitconfig` and `~/.config/git/attributes` are outside the audit. Both held-out tasks are now run only by the human, who performs the checklist below immediately before each run; its items 2-6 detect both CR-P5-06 failure inputs, including a raw-byte comparison of every tracked file in scope with HEAD and an index-to-HEAD comparison. And the human decided no guard expansion this round. Residual: a change made between the checklist and the run. Owner of the permanent fix: `build-engineer`, in a later phase, by work order: compare raw bytes (`git hash-object --no-filters --stdin-paths`) of every `git ls-tree -r -z HEAD` entry in scope with its HEAD blob (refusing symlink and gitlink modes explicitly), require `git diff-index --cached --quiet HEAD` over the scope, and keep the untracked and ignored listing (CR-P5-06 suggested fix; pinning `core.checkStat` and `core.trustctime` alone does not close the filter route).

**Deferred (decisions-21).** The HD-1 duplicate-source check stays in `modules/hadoop` (`ErSplitPipeline.checkManifest`, refused before any job; `hadoop-engineer`). The broader core unique-source validation (at most one snapshot per source in `inputSnapshots`; `domain-engineer`, with contract tests, after confirming that no stage needs two snapshots of one source) is deferred to a later work order. There is no Phase 5 change.

**Pre-run Terminal checklist.** The 14 items (shell commands and expected results) are in the patch verbatim at docs/agents.md post lines 368-453 (patch lines 639-724), and are not repeated here, to keep a single source. In summary:

1. no other writer or sbt server;
2. git configuration and gitlinks;
3. attribute and exclude files;
4. attributes in effect over the whole scope;
5. index flags;
6. raw bytes equal HEAD;
7. remove `.DS_Store` files in scope;
8. status over the scope, exactly as the build runs it;
9. the held-out labels file and run order;
10. launcher, JVM-option and JDK-selector files;
11. environment variable names only;
12. global sbt settings and plugins;
13. optional: unexpected classpath files;
14. run one task, in the planned order, with `time sbt -Dsbt.supershell=false <task>`, keeping stdin, stdout and stderr on the Terminal.
