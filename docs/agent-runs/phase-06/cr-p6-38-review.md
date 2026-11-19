# CR-P6-38: review of IE-P6-04 repair 01 (code-reviewer final report, verbatim)

```
CR-P6-38 review of IE-P6-04 repair 01 (uncommitted, against HEAD 148b4b91bc904a63680b94f91f275173f743c4b6)

Verdict: APPROVE. I found no critical, major, minor or nit findings that I could verify. All four checks pass. Two concerns I could not verify are listed at the end; neither blocks.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-38 (Phase 6). Read-only review of the infra-engineer IE-P6-04 repair 01 changes to:
- /Users/taufeeqpatel/Desktop/ScholarChain/scripts/run-bounded-spark.sh (the new code-scope check, lines 136-151)
- /Users/taufeeqpatel/Desktop/ScholarChain/infra/terraform/environments/dev/emr-run/terraform.tfvars
- /Users/taufeeqpatel/Desktop/ScholarChain/infra/terraform/environments/dev/runs/spark-dev-run-01.conf
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I have only Read, Grep and Glob.
TEST RESULTS: not applicable. I ran no tests.

VALIDATION RESULTS:

Check 1: the new code-scope check (run-bounded-spark.sh:140-151)
- Exit status under `set -euo pipefail` (line 77).
  - Line 143 uses `git diff --quiet ... || CS_RC=$?`. A non-zero exit sits on the left of `||`, so `set -e` does not fire, and `CS_RC` gets git's real exit code (0 equal, 1 differs, 128 or other on error).
  - Bash exempts the `((...))` tests in the `if`/`elif` at lines 144 and 148 from errexit.
  - There is no ERR trap. The only trap is `trap cleanup EXIT` at line 305, which is set after this block, so `die` here simply exits with its code.
- Listing under pipefail and SIGPIPE. On line 146, `git diff --name-only ... | head -n 50 >&2 || true`: `head` gets git's stdout and writes to stderr. If more than 50 names make git die of SIGPIPE, the pipeline returns 141 under pipefail, and `|| true` absorbs it. The `die` on line 147 then still runs with `EX_REFUSED` (3). This is correct.
- `$HEAD_SHA` is defined. `require_repo_root` (common.sh:77) sets `HEAD_SHA=$(git rev-parse --verify HEAD)` and runs at line 107, before this block. The refusal message on line 147 therefore cannot hit an unbound-variable error under `set -u`. `C_code_version` is set by `parse_kv` at line 114 and checked at lines 132-135.
- Word splitting. `CODE_SCOPE` is a readonly fixed string of four plain paths with no glob characters. A grep of `scripts/` shows no global IFS assignment; every `IFS=` is a per-command prefix on `read` (common.sh:100, run-bounded-spark.sh:284, 491). Unquoted expansion therefore splits into exactly four pathspecs, and pathname expansion cannot alter them.
- Placement.
  - It runs after `require_repo_root` and `require_clean_tree` (lines 107-108), so HEAD equals the work tree.
  - It also follows the ancestry check (line 135).
  - It comes before the local-artifact block (line 307), the dry-run branch (line 408), the token check (line 421), `--stage` (line 467) and `--execute` (line 500). It therefore applies in all three modes.
- Pathspec semantics. The pathspecs have no wildcard and no magic, and the script requires the repository root as cwd (common.sh:76). Git then matches each one as an exact path or a leading directory component. `project` matches `project` and `project/**`, not `projectX`. `modules/core` does not match `modules/core-x`. This holds for a tree-to-tree diff.
- Same scope as sparkSubmitLocal. `project/SparkSubmitLocal.scala:87` has `val CodeScope = Seq("build.sbt", "project", "modules/core", "modules/spark")`, used at line 1515 and asserted at line 1928. This matches line 140 exactly.
- What the comparison covers. It compares committed trees, so ignored `target/` output is excluded, as in sparkSubmitLocal's `GeneratedDirs`. The analysis config and benchmark pairs (`modules/spark/src/test/resources/...`) are inside the scope, which closes the CR-P6-37 m1 failure input.

Check 2: values
- Install and submit script sha256 (terraform.tfvars:12-13). I compared them character by character with human-shasum-2026-11-18.md lines 6-7. Both are identical:
  - `install_script_sha256` = `07d11283933c63afda1b03c6fe046bace19444ccc4a42e0df4dabf98fbbd8ff0`
  - `submit_script_sha256` = `ccf45f6cff9e202338dc377d559308abe028e5115d2bcee02e80d37c393db34f`
- `spark_dist_sha256` (spark-dev-run-01.conf:33) = `3581db56cb57cc94d83186d468be8233adebc27a94136540731b35839b09888f`. This is identical to line 8 of the human file.
- `partitions=2` (conf:56). The comment cites SP-P6-08 decision 1.
- Placeholders. Neither file contains REPLACE-ME. `input_tree_sha256` is still 64 zeros (conf:49).
- Unchanged values. The other values still equal the attempt-4 pins I checked in CR-P6-37: code_version 148b4b9…, jar `cd62042c…f597a`, spark sha512 `a462656d…3aa1`, scala `1ebb2b6f…30f1`, hadoop-aws sha256 `53f9ae03…3a4a` and sha1 `a65839fb…4f80`, sdk `873fe7cf…bae5`, the run IDs and the bounds.
- Comment-only edits. The other edits are comments: tfvars lines 3-6, and conf lines 21-25, 29-30 and 53-55.
- Limit. I cannot run git, so the "nothing else changed" check is a comparison of the current contents with the CR-P6-37 state, not a diff.

Check 3: other pins after the run-bounded-spark.sh change
- The tfvars pins cover only `scripts/emr/install-spark-dist.sh` and `scripts/emr/submit-collaboration-job.sh`. The repair report says neither was modified, and the git status snapshot shows neither as changed. The human's 148b4b9 hashes are therefore still valid, and the dry run and `--execute` re-hash both (lines 210-211, 536-537, 547-548).
- No file pins a hash of run-bounded-spark.sh. A repository-wide grep for a 64-hex value next to `run-bounded-spark` found nothing outside target/.
- References that do not pin a hash.
  - The references in deploy.sh, emr-run/main.tf, base/iam.tf, base/storage.tf, env.conf and submit-collaboration-job.sh are comments or constant cross-references. None is affected.
  - The probes.tsv rows and validate-agent-files.sh test shapes in a fixture repository with a stub script. They do not use this file's hash.
- Tokens and plan meta. The confirmation tokens bind `head=$HEAD_SHA` (line 327), so they change with the commit, and none has been issued yet. plan.sh's meta file records `head=`, so any plan made before this commit would be stale. None has been made; the bootstrap plan comes after the commit in the stated order.

Check 4: report honesty
- check-submit-args.sh not run. This is consistent with Script provenance rule 5 in docs/agents.md: every file under `scripts/**` must be committed and byte-identical to HEAD, and run-bounded-spark.sh is modified. The quoted guard message matches that rule. The report correctly says the new check has not been exercised.
- Other results. I cannot re-observe the bash -n, shellcheck, fmt and validate results. They are plausible: each `# shellcheck disable=SC2086` covers the one unquoted `$CODE_SCOPE` on the line below it; validate does not evaluate tfvars, so the `^[0-9a-f]{64}$` validation applies at plan time, and both values match it.
- Corrected nit text. n1 to n4 are corrected accurately: n2: parse_kv refuses REPLACE-ME at common.sh:110; n4: lines 314-322 and 408-416 show that a missing archive gives NOTE and RESULT DRY-RUN-OK with tokens bound to the zero tree.
- Negative-check recipe. It is correct: 692c173 is an ancestor, and the scope differs in three files, as the report's command 6 shows. I note that it creates and deletes a temporary branch in the human's Terminal.

ASSUMPTIONS:
- I assumed the git status snapshot at session start is accurate: only the three files are modified, and scripts/emr/* are unchanged.
- I assumed git's documented exit status for a tree-to-tree `git diff --quiet` (0 equal, 1 differs). I did not observe it.

RISKS:
- The new check stays unexercised until the first committed dry run, which must print `CHECK ok code scope ... unchanged between code_version 148b4b9... and HEAD`.
- From now on, any commit touching `build.sbt`, `project/**`, `modules/core/**` or `modules/spark/**` blocks the run until sparkSubmitLocal is re-run and the pins are updated. This is intended. It includes the optional SP-P6-08 byte-reproducibility hardening, if done before the run.
- `sbt clean` before `--stage` still deletes the pinned jars and the distribution (carried over from CR-P6-37).

MANUAL ACTION REQUIRED:
1. The human commits the three files and the untracked evidence files.
2. Run check-submit-args.sh and expect RESULT PASS.
3. Dry run 1: confirm the new CHECK ok code-scope line and get the computed input_tree_sha256.
4. Route that value to infra-engineer to write and commit.
5. Dry run 2.
6. The bootstrap plan in the human's Terminal.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human commit, check-submit-args.sh PASS, dry run 1 showing the code-scope CHECK ok line and the computed tree hash, infra-engineer writes input_tree_sha256, dry run 2 with RESULT DRY-RUN-OK, then the bootstrap plan.

VERDICT: APPROVE

FINDINGS: none

UNVERIFIED CONCERNS:
- Inherited git environment (owner `infra-engineer`; this predates the repair).
  - Problem: the new git calls, like the script's existing ones, inherit `GIT_*` variables. A `GIT_DIR` or `GIT_WORK_TREE` exported in the human's Terminal could point the comparison at another repository.
  - Mitigation: `require_repo_root` compares `--show-toplevel` with the cwd, but that check is itself affected by `GIT_WORK_TREE`.
  - Status: not a regression, and it applies only in the human's Terminal.
- Case-only path collisions (owner `infra-engineer`, shared with sparkSubmitLocal's identical scope; not established).
  - Problem: on macOS's case-insensitive filesystem, a committed path such as `Project/Foo.scala` would be checked out into `project/`. sbt would load it, but the case-sensitive pathspec `project` would not match it.
  - Status: this needs a deliberate, reviewed commit, and I did not verify how git behaves in that case.
```
