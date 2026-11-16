# FE-P5-08 final report (factory-engineer; verbatim)

Archived by the orchestrator, 2026-11-14. Work order: consolidated FE-P5-07 (one standalone amendment, one patch against the live tree, post/ files, README), draft only (decisions-22). The deliverables are in the session scratchpad, not in the repository: `/private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/scratchpad/fe-p5-07/`. The harness prefixed the report with a note that it matched an instruction-shaped pattern (settings-json); that note is omitted here.

---

FE-P5-08 is done: the consolidated FE-P5-07 (amendment text, one patch, the full post-patch files and a README) is ready for your review. Nothing in the repository was changed, nothing was applied or committed, and no held-out task ran. `git apply --check` was not run by any agent: no allowlist row permits it, or `shasum`, so the patch is unverified until the human runs it.

One process slip to disclose: I wrote a 3-line test file outside the approved directory, at `/private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/scratchpad/tabtest/t.txt`, to work out how to keep trailing TABs. It contains no repository data, and I cannot delete it. The human may remove it.

Directory: `/private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/scratchpad/fe-p5-07/`

**Files** (line counts; byte sizes could not be measured, no allowlisted command lists them):

| File | Lines (post / live) |
|---|---|
| `amendment-fe-p5-07.md` | consolidated text |
| `fe-p5-07.patch` | 861 lines, 10 files |
| `README.txt` | Terminal commands and fallback |
| `post/.claude/hooks/lib-ownership.sh` | 268 / 263 |
| `post/.claude/hooks/enforce-ownership.sh` | 100 / 100 |
| `post/.claude/hooks/bash-allowlist.tsv` | 169 / 169 |
| `post/.claude/hooks/guard-dangerous.sh` | 668 / 548 |
| `post/.claude/hooks/probes.tsv` | 683 / 461 |
| `post/.claude/hooks/validate-agent-files.sh` | 428 / 414 |
| `post/docs/agents.md` | 552 / 422 |
| `post/.claude/settings.json` | 341 / 332 |
| `post/.claude/agents/entity-resolution-engineer.md` | 57 / 56 |
| `post/.claude/agents/hadoop-engineer.md` | 58 / 57 |

Placeholders left literally:
- `<NEW-SHA256>`: post `docs/agents.md:132`, patch line 555.
- `<RATIFICATION-DATE>`: post `docs/agents.md:546`, patch line 767.
- `<PATCH-SHA256>`: amendment section 0.

**Supersession.** Amendment section 0 states that FE-P5-07 supersedes FE-P5-06 for application. It is applied as one patch against the live tree at HEAD 36ffcf9, never as FE-P5-06 plus a delta. FE-P5-06 (ratified in decisions-19) stays the historical record. The FE-P5-06 hunk-header erratum is section 12.

**R6 coverage statement** (decisions-22). Stated at post `docs/agents.md:337` (patch 608), in the residuals paragraph at post `:490` (patch 752), and in amendment section 7:
- R6 protects held-out evaluation output only: the guard's Read/Grep fences on `target/streams/**/goldsetevalheldout` and `target/local-runs/heldout`, plus the two settings Read denies.
- The held-out pipeline's candidate data and `summary.txt` under `modules/hadoop/target/local-pipeline/er-candidates/heldout/`, and its sbt envelope log, remain agent-readable.
- No labels or metrics are in them, and no new fence is added this round.

**Confirmation (a): both held-out tasks are human-only.** Evidence from my patch (P = patch line) and the post files:
- **No allowlist row.** P35-51 replaces the two inactive B10 rows with the dev rows only (post `bash-allowlist.tsv:96`, `:100`).
- **Guard rules, checked before allowlist matching:**
  - `heldout-eval-human-only` at P107-109 and `heldout-pipeline-human-only` at P110-112 (post `guard-dangerous.sh:289-294`).
  - Called from `bash_check` at P202-204 (post `:505-507`).
  - Refusal messages at P90-95 (post `:74-79`).
- **Tripwire.** P118-120 (post `:300-302`), and `validate()` applies it to every active row (post `:603`).
- **Settings denies.** goldSetEvalHeldout at P801-802, localPipelineHeldout at P803-804 (post `settings.json:155-158`).
- **Probes for every identity:**
  - localPipelineHeldout: owner at P253 (post `probes.tsv:465`), all other 17 agents plus the orchestrator at P276-293 (post `:488-505`), variant forms at post `:549`, `:552`, `:555`, `:558-559`, `:679-682`.
  - goldSetEvalHeldout: owner at P255 (post `:467`), others at P314-331 (post `:526-543`).
- **Role rules.** hadoop-engineer law 10 at P845; entity-resolution-engineer law 9 at P820.

**Confirmation (b): the runMain fallback is removed.**
- Amendment section 8 steps 1 and 8 use only the guarded tasks. The raw `ErSplitPipelineMain heldout` and `GoldSetEvalMain` runMain forms are explicitly not used, by anyone.
- No agent can run any runMain form: no row contains `runMain`, because the `SBT` class admits only compile, test and scalafmt tasks (post `guard-dangerous.sh:94`).
- Probes post `probes.tsv:565-566` expect `no-allowlist-entry` for both runMain forms. Laws 10 and 9 forbid them.
- The remaining held-out runMain forms (the `ReviewQueueMain` steps 2 and 6) are human-only.

**Expected counts after application** (computed from post/, not observed):

| Item | Expected |
|---|---|
| Allowlist rows | 119 |
| Identity rows / groups | 94 / 3 |
| Ownership rows | 46 |
| PYEOF lines | 614 |
| Validator lines (`wc -l`) | 428 |
| `NOTE  <n> table probes run` | 661 |
| `NOTE  <n> fixture probes run` | 38 (37 with scalafmt on the probe PATH, plus the existing scalafmt NOTE) |
| settings.json | allow +2, ask 0, deny +8 |
| docs/agents.md mirror | 119 rows; file 552 lines |

Differences from the FE-P5-07 delta draft's predictions (119 / 94 / 46 / 614 / 429 / 661 / 38):
- Validator: the draft's 429 counted the empty line after the final newline; the same file is 428 by `wc -l`. On the same convention the guard is 668, not the draft's 669.
- The "Held-out runs" block is 122 lines, not 120, because of the new R6 coverage paragraph.
- Everything else agrees.

**Checks actually run** (observed, live tree only):
- `.claude/hooks/validate-agent-files.sh --probes`: RESULT: PASS (0 failures); NOTE 457 table probes run; 36 fixture probe PASS lines; PYEOF 503 lines; 94 identity rows, 3 groups; mirror 119 rows, sha256 eacc92bb7765; `bash -n` PASS on 9 hooks; shellcheck not installed.
- JSON parse PASS for `.claude/settings.json` and `.mcp.json`; `test -x` PASS for guard-dangerous.sh and validate-agent-files.sh.
- `git diff --stat` and the untracked-file listing for the controlled files were both empty; HEAD is 36ffcf95e585c7def6aa06835a784820936e1b60.
- `bash -n` and shellcheck could not check the post/ hooks, because their path classes only accept repository `.claude/hooks/` paths.

**Self-checks:**
- I re-read every context and minus line of the patch against the live Read output, and recounted every `@@` header.
- I confirmed that patch sections and hunks land on the computed boundaries, for example the probes block covers P250-471, 222 lines (post 462-683), and the Held-out runs block covers P604-725, 122 lines.
- Post files: total line counts and the start and end lines of every changed region match the live file plus the hunks.
- Trailing TABs: the file tools strip trailing whitespace from what I type. I restored the TABs with a marker that I then deleted, and confirmed them by Read in both post/probes.tsv and the patch.
- Repeated-character runs: the 388/389 'a', 40 'a' and 64 'b' runs in probes.tsv lines 67-68 and 165-166 were built by exact doubling edits, because my typed runs failed a 400-byte gate test.
- No hook reported an UNATTRIBUTED change.

**Human commands** (full text in `README.txt`; all from the repository root with every session closed):
1. Archive: `cp "$S/amendment-fe-p5-07.md" "$R/docs/agent-runs/phase-05/amendment-fe-p5-07.md"`
2. Checksum: `shasum -a 256 "$S/fe-p5-07.patch"`
3. Pre-check: `git diff --stat -- .claude CLAUDE.md docs/agents.md .mcp.json` (expect empty).
4. Dry run: `git apply --check -v --whitespace=nowarn "$S/fe-p5-07.patch"` (expect 10 "Checking patch" lines and exit 0), then compare `git apply --numstat` with the expected table.
5. Apply: `git apply -v --whitespace=nowarn "$S/fe-p5-07.patch"`
6. Cross-check before filling placeholders: a `cmp`/`diff -u` loop over the 10 files against `post/`, then `test -x` on the two executable hooks.
7. Fill `<NEW-SHA256>` and the date with `sed -i ''`, then `grep` to confirm none remain.
8. Validators with the expected lines.

Fallback if `--check` fails:
- Before applying anything, symlink the repository as `regen/a` and `post/` as `regen/b`.
- Copy each live file's mode onto its post copy with `chmod "$(stat -f %Lp ...)"`.
- For each of the 10 files, run `git diff --no-index --no-prefix -- "a/$f" "b/$f"` and append the output to `fe-p5-07.regen.patch`.
- Confirm there are 10 diffs and no mode lines, check the numstat, then run `git apply --check` on the regenerated patch.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: FE-P5-08 (Phase 5; draft only; decisions-22): consolidated FE-P5-07 = FE-P5-06 + FE-P5-07 delta (unchanged in substance) + R6 coverage statement; one git-format patch against the live tree at 36ffcf9; full post/ files; README with archive, sha256, apply --check, cross-check, validator and fallback commands; written only to the scratch directory.
FILES CREATED: In <scratch>/fe-p5-07/: amendment-fe-p5-07.md, fe-p5-07.patch, README.txt, and post/ copies of .claude/hooks/{lib-ownership.sh, enforce-ownership.sh, bash-allowlist.tsv, guard-dangerous.sh, probes.tsv, validate-agent-files.sh}, .claude/settings.json, .claude/agents/{entity-resolution-engineer.md, hadoop-engineer.md}, docs/agents.md. Also <scratchpad>/tabtest/t.txt (test file outside the approved directory; disclosed above).
FILES MODIFIED: none in the repository.
COMMANDS RUN:
- 3 x git diff -- <a-run> as a 400-byte gate-length test (2 accepted, no output; 1 refused, "longer than 400 bytes").
- .claude/hooks/validate-agent-files.sh --probes: RESULT PASS.
- python3 -I -m json.tool .claude/settings.json: parsed.
- python3 -I -m json.tool .mcp.json: parsed.
- git diff --stat -- .claude CLAUDE.md docs/agents.md .mcp.json: empty.
- git ls-files --others --exclude-standard -- (same paths): empty.
- test -x on guard-dangerous.sh and validate-agent-files.sh: exit 0.
- git status --porcelain; git rev-parse HEAD.
- No sbt, git apply, shasum, commit, push, AWS or network command.
TEST RESULTS: not applicable (nothing applied; no sbt).
VALIDATION RESULTS: Live baseline observed: JSON PASS (both files); bash -n PASS (9 hooks); exec bits PASS; frontmatter PASS for 18 agents; --probes RESULT PASS (457 table, 36 fixture). Post-application values are predictions only.
ASSUMPTIONS:
- git apply accepts empty lines as blank context (GNU style) and context of uneven length.
- The live `@env` and `@env-nonprod` lines have no trailing TAB (Read shows none). This affects only the post/ cross-check, not the patch.
- No live file contains trailing spaces that Read does not show.
- The human runs the README commands in bash or zsh on macOS.
RISKS:
1. git apply --check is unobserved.
2. post/ is hand-transcribed; the cross-check in README step 6 detects any discrepancy.
3. HO_DO's "Grep dev output (... goldSetEvalDev streams)" wording contradicts the R6 Grep fence. It is inherited from ratified FE-P5-06, left unchanged, and proposed as a later wording fix.
4. Settings wildcard denies are verified only after restart.
5. Held-out pipeline output stays agent-readable, by decision.
6. /private/tmp may be cleared on reboot; README advises copying the directory first.
MANUAL ACTION REQUIRED: Human reviews and ratifies or rejects FE-P5-07; runs README steps 1-9 with all sessions closed; fills <NEW-SHA256>, <RATIFICATION-DATE> and <PATCH-SHA256>; commits; restarts; optionally deletes scratchpad/tabtest/t.txt. The orchestrator routes nothing until then.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human ratification of consolidated FE-P5-07 (optionally after a code-reviewer pass); human git apply --check, then apply, the post/ cross-check and the validators (expected 119/94/46/614/661/38, RESULT PASS); commits C, A, B; fresh session. Held-out runs only with separate approval, in the human's Terminal.
MATRIX BASIS: Ratified FE-P5-06 (decisions-19); decisions-21; decisions-22; the B10 placeholder rows; Human gates Phase 5; G9; factory-engineer ownership of .claude/** and docs/agents.md. No new widening beyond FE-P5-06's ratified dev rows.
PRIVILEGE DELTA: hadoop-engineer and entity-resolution-engineer widened by one dev task each, within B10; held-out halves withheld. All identities narrowed (G9-c, R6, D10(ii), both human-only rules). gold/** moves from UNASSIGNED to HUMAN. Settings: allow +2, deny +8. Orchestrator unchanged or narrowed. Full table in amendment section 4.
FLOOR INVARIANTS: All intact; changes only add refusals, apart from the two ratified dev rows and their S1 allows. Nothing refused.
AUDIT RESULTS: Collision: rule names, probe labels and settings entries are unique; no new agent, command or hook registration. Orphan: no new hook file; every new function and constant is used; validator and probes stay registered and referenced. One-writer: 46 ownership rows, with gold/** single-owned (HUMAN) and mirrored by anchor gold/x/y.scala.
COMPLETE DIFF: No controlled file changed (git diff --stat empty; no untracked controlled files). The complete proposed diff is <scratch>/fe-p5-07/fe-p5-07.patch (10 files, +529/-27 lines).
OUT-OF-SCOPE ITEMS: build-engineer wording follow-ups and the later BE-6 fix; domain-engineer core validation (deferred); archiving under docs/agent-runs (human or orchestrator); every held-out run, gold/** write, commit and push (human).
RATIFICATION REQUIRED: Yes. A human must review and ratify consolidated FE-P5-07, apply it from a Terminal with no session open, see the validators pass, commit, and restart. Nothing is effective before then.
```
