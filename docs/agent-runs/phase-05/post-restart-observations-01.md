# Phase 5: FE-P5-07 step 7 post-restart observations (01)

Assembled by the orchestrator on 2026-11-14, from the live session that followed commit B. This file records observations only. It does not ratify or accept anything. The human's decisions belong in `human-gate.md`.

- **Amendment:** `docs/agent-runs/phase-05/amendment-fe-p5-07.md`, section 8 step 7.
- **Commits:** C `2a91c5c` (FE-P5-07), A `a961035e8863a1c4b3337557bb17fbab05a1182a`, B `71ff55199c454c7c2393c973955cb6950f4d5392` (HEAD while these observations were taken).
- **Work tree:** no tracked changes. The only untracked paths were the existing `docs/agent-runs/phase-05/*` evidence files, which were not modified.
- **Protections:** every hook and permission rule was left enabled. No setting was changed.
- **Not done:** neither held-out task ran. No dev run (`sbt localPipelineDev`, `sbt goldSetEvalDev`) ran. No real held-out labels or held-out results were accessed. Every probe used a dummy path or the non-executing `sbt help <key>` form.

## A. Validator and fixture results (human Terminal, before the restart; not live-session observations)

Source: the human's FE-P5-07 entry in `human-gate.md`. They are copied here for cross-reference and were not re-run by the orchestrator.

| Check | Recorded result |
|---|---|
| Patch sha256 | `978146a43ecb7a3cbc2342caa28160ded373d675c77e534c81297f510b504937` |
| Applied `bash-allowlist.tsv` sha256 | `eb708cd4ea78c8f8da0a68579c1bb0b009840c762f5c0a58c052847f18f7eb73` |
| `bash -n` on the four changed hooks | all passed |
| `settings.json` | parsed |
| `--validate-allowlist` | 94 identity rows, 3 groups; the 119-row docs mirror matched |
| `validate-agent-files.sh` | PASS, 0 failures |
| `validate-agent-files.sh --probes` | PASS, 0 failures; 661 table probes, 38 fixture probes; both D10(ii) held-out cleanup fixture checks passed |

These results exercise the hooks against probe tables and the temporary fixture repository. They are not observations of a live Claude Code session.

## B. Live Claude Code denials (this session, identity `ORCHESTRATOR`)

Each refusal below is the tool error returned in the session, quoted verbatim. The guard messages are shortened to the rule line and the `Nothing was executed.` line. The guard's full text also listed the identity's allowed shapes and a "Do instead" paragraph.

| # | Step 7 item | Tool call | Layer that refused | Exact result |
|---|---|---|---|---|
| B1 | label Read fence (S2) | Read `gold/entity-resolution/heldout-probe-dummy/er-gold-labels-dummy.jsonl` (dummy, does not exist) | `settings.json` deny `Read(./gold/**/heldout*/er-gold-labels*)` | `File is in a directory that is denied by your permission settings.` |
| B2 | label Read fence, case variant | Read `gold/entity-resolution/HELDOUT-probe-dummy/ER-GOLD-LABELS-dummy.jsonl` (dummy) | `settings.json` deny (matched despite the case change) | `File is in a directory that is denied by your permission settings.` |
| B3 | R6 Read deny | Read `target/local-runs/heldout/dummy-probe-nonexistent.txt` (dummy) | `settings.json` deny `Read(./target/local-runs/heldout/**)` | `File is in a directory that is denied by your permission settings.` |
| B4 | R6 Read deny | Read `target/streams/_global/goldSetEvalHeldout/dummy-probe-nonexistent.txt` (dummy) | `settings.json` deny `Read(./target/streams/**/goldSetEvalHeldout/**)` | `File is in a directory that is denied by your permission settings.` |
| B5 | R6 Read deny, case variants | Read `target/local-runs/HELDOUT/dummy-probe-nonexistent.txt` and `target/streams/_global/GOLDSETEVALHELDOUT/dummy-probe-nonexistent.txt` (dummies) | `settings.json` deny (matched despite the case change) | `File is in a directory that is denied by your permission settings.` (both) |
| B6 | `heldout-label-read` (G9-c git fence) | `git diff -- gold/entity-resolution/heldout-probe-dummy/er-gold-labels-dummy.jsonl` | `guard-dangerous.sh` | `BLOCKED [heldout-label-read]: git diff naming 'gold/entity-resolution/heldout-probe-dummy/er-gold-labels-dummy.jsonl' could print human gold-set labels (G9-c).` / `Nothing was executed.` |
| B7 | `heldout-eval-human-only` | `sbt help goldsetevalheldout` | `guard-dangerous.sh` | `BLOCKED [heldout-eval-human-only]: sbt goldSetEvalHeldout prints held-out metrics and false-positive and false-negative listings, so no agent and not the orchestrator runs it (decision D15(c)); no identity has an allowlist row for it.` / `Nothing was executed.` |
| B8 | `heldout-eval-human-only`, exact case | `sbt help goldSetEvalHeldout` | `guard-dangerous.sh` (fired before the `settings.json` Bash deny pair could apply) | same text as B7 |
| B9 | `heldout-pipeline-human-only` | `sbt help localpipelineheldout` | `guard-dangerous.sh` | `BLOCKED [heldout-pipeline-human-only]: sbt localPipelineHeldout runs only as plain batch sbt in the human's own Terminal (decisions-21), so no agent and not the orchestrator runs it; no identity has an allowlist row for it.` / `Nothing was executed.` |

Earlier in the same session, hadoop-engineer ran two foreground `sbt check` runs: one before commit B and one at B. Neither was refused, and no UNATTRIBUTED audit report appeared. The run at B passed: 922 passed, 0 failed, 0 errors, 0 skipped, 76 suites. `markTestRun` recorded `git_head=71ff55199c454c7c2393c973955cb6950f4d5392, worktree=dirty`. The orchestrator confirmed afterwards that no tracked file had changed.

### Layer coverage

- **Read (B1-B5).** The `settings.json` deny rules refused every Read before `guard-dangerous.sh` could decide. The hook's own Read refusals (`heldout-label-read` for Read, `heldout-eval-output-read`) were **not observed live**. Their evidence is the probe table (section A).
- **Bash (B6-B9).** The hook refused every call. The `settings.json` Bash deny pairs (`Bash(sbt goldSetEvalHeldout)`, `Bash(sbt *goldSetEvalHeldout*)`, `Bash(sbt localPipelineHeldout)`, `Bash(sbt *localPipelineHeldout*)`) were **not observed live**, because the hook refuses first. They are present in `.claude/settings.json` at lines 155-158.
- `heldout-run-protected` (D10(ii)) was not probed live, because no held-out pipeline output exists. Its evidence is the two fixture checks (section A).
- The `Edit(./gold/**)` deny was not probed live, to avoid any write attempt under `gold/**`.

## C. Pending checks

### C1. Repository-wide Grep skips an ignored dummy file: NOT RUN (see outcome below)

**Blocker:** this session has no Grep tool, and shell `grep` is not on the allowlist. The check needs a fresh session that offers Grep.

**Proposed dummy (outside the real held-out labels path and outside the held-out build scope `build.sbt project modules tests datasets gold`):**

- Path: `.idea/scholarchain-l1-grep-dummy-7f3a.txt`
- Expected ignore rule: `.gitignore` line 29, `/.idea/` (IDE metadata). This dummy tests the same Grep ignore behaviour as the L1 labels line (line 41), without touching the real labels path. Line 41 itself is not exercised.
- Content: one line made by joining `SCHOLARCHAIN_L1_DUMMY_` and `7f3a`. The joined token is deliberately not written anywhere in this file, so that this file cannot produce a false Grep match.

**Steps, all in the human's regular Terminal unless marked otherwise:**

1. Read-only rule check. This can run before the file exists, because it checks the pathname:
   ```sh
   git check-ignore -v -- .idea/scholarchain-l1-grep-dummy-7f3a.txt
   ```
   Expected: `.gitignore:29:/.idea/	.idea/scholarchain-l1-grep-dummy-7f3a.txt`. A tab separates the rule from the path. Exit status 0. If nothing is printed (exit status 1), the path is not ignored: stop.
2. Create the dummy:
   ```sh
   mkdir -p .idea && printf '%s%s\n' SCHOLARCHAIN_L1_DUMMY_ 7f3a > .idea/scholarchain-l1-grep-dummy-7f3a.txt
   ```
   Then repeat step 1, and run `git status --porcelain` and confirm the dummy is not listed. If `.idea/` did not exist before, note that, so that step 5 can remove the directory too.
3. In a fresh Claude Code session that offers Grep, the orchestrator runs one repository-wide Grep for the joined token, with no `path` and no `glob` argument. Expected: no match, and no `heldout-label-read` or `heldout-eval-output-read` refusal, because no gold, heldout, label or target path is named.
4. The orchestrator records the exact result in a follow-up evidence file.
5. Clean up in the Terminal:
   ```sh
   rm .idea/scholarchain-l1-grep-dummy-7f3a.txt
   ```
   If step 2 created `.idea/`, also run `rmdir .idea`.

`git check-ignore` is not an allowlisted agent Bash shape, so step 1 is the human's. No agent runs it.

#### C1 outcome (recorded 2026-11-14): NOT RUN

- **Status:** NOT RUN. Native Grep's handling of git-ignored files remains **unverified**.
- **Reason:** native Grep was unavailable in both the original session and the fresh Claude Code session. In the fresh session, the orchestrator observed that Grep was not among its loaded tools, and a `ToolSearch` query `select:Grep` returned `No matching deferred tools found.` No Grep call was made. No substitute was used: no shell `grep` (not allowlisted), no subagent, no Explore agent.
- **Step 1 (human-reported, Terminal):** the human reports that `git check-ignore` confirmed that the `/.idea/` rule covered the dummy path. The orchestrator did not observe this output.
- **Step 2 (human, Terminal):** the dummy file was created by the human. The orchestrator did not read or list it.
- **Step 5 (human-reported, Terminal):** the human reports removing the dummy file in Terminal after the second attempt. The orchestrator did not verify the removal.
- **Protections:** every hook and permission rule stayed enabled. No setting was changed.
- **Not done:** no dev run (`sbt localPipelineDev`, `sbt goldSetEvalDev`) and no held-out run.
- **Open item:** C1 needs a session that actually offers the native Grep tool. Until then, the expectation that repository-wide Grep skips the L1-ignored labels file (see the held-out residuals paragraph in `docs/agents.md`, "expected, verified after restart") remains unverified.

### C2. Listing `target/streams/_global/goldSetEvalDev/`: PENDING until after the first dev evaluation

This check applies only after entity-resolution-engineer's first `sbt goldSetEvalDev`, which in turn follows hadoop-engineer's `sbt localPipelineDev` (amendment section 8, step 8). Neither dev run has started, and the orchestrator paused before them as instructed.

## D. Next gate

The human reviews this file. Then, with separate approval each:
- C1: attempted and NOT RUN (native Grep unavailable); its rerun needs a session that offers Grep and a new dummy file;
- the dev evidence runs (`sbt localPipelineDev`, then `sbt goldSetEvalDev`), followed by C2.

Held-out runs stay human-only, in the human's Terminal, after the pre-run checklist.
