# IE-P6-04 repair 01: EMR run pins, partitions=2, code-scope binding (infra-engineer final report)

Findings routed: CR-P6-37 m1 (human chose "enforce in script") and n1-n4; human sha256 values (human-shasum-2026-11-18.md); SP-P6-08 decision 1 (partitions=2). Owner: infra-engineer.

The report below is verbatim except the COMPLETE DIFF field and the commit block, which are omitted. The diff (terraform.tfvars, spark-dev-run-01.conf, scripts/run-bounded-spark.sh) is recorded by the commit that contains this file. The orchestrator issues the combined commit block after review (CR-P6-38).

```
IE-P6-04 repair 01 is done but not yet run end to end. The values are filled and run-bounded-spark.sh now refuses, in every mode, when the code scope differs between code_version and HEAD. Syntax, shellcheck, Terraform fmt and validate passed. check-submit-args.sh was refused by the guard because run-bounded-spark.sh is uncommitted, so the new check has not been run yet. The human must commit first.

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: IE-P6-04 repair 01 (Phase 6). Finalize the EMR run config values (three human sha256 values, partitions=2 per SP-P6-08) and enforce the code-scope binding in scripts/run-bounded-spark.sh (CR-P6-37 m1, human chose "enforce in script"). Correct CR-P6-37 nits n1 to n4. HEAD 148b4b91bc904a63680b94f91f275173f743c4b6.

FILES CREATED: none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/infra/terraform/environments/dev/emr-run/terraform.tfvars: sets install_script_sha256 and submit_script_sha256 to the values in human-shasum-2026-11-18.md, and the header comment cites that file.
- /Users/taufeeqpatel/Desktop/ScholarChain/infra/terraform/environments/dev/runs/spark-dev-run-01.conf:
  - spark_dist_sha256=3581db56…888f
  - partitions=2, with a comment citing SP-P6-08 decision 1
  - the code_version comment now says both rules are enforced by the script in every mode
  - input_tree_sha256 stays the 64-zero placeholder
- /Users/taufeeqpatel/Desktop/ScholarChain/scripts/run-bounded-spark.sh: adds the code-scope check right after the ancestry check. It runs before any mode branch, so it applies to --dry-run, --stage and --execute. The mode stays 100755.
- Not modified: scripts/emr/install-spark-dist.sh, scripts/emr/submit-collaboration-job.sh, scripts/lib/common.sh.

COMMANDS RUN (exact observed results):
1. `bash -n scripts/run-bounded-spark.sh`: exit 0, no output.
2. `shellcheck scripts/run-bounded-spark.sh scripts/lib/common.sh`: exit 0, no output.
3. `terraform -chdir=infra/terraform fmt -check -recursive`: exit 0, no output.
4. `terraform -chdir=infra/terraform/environments/dev/emr-run validate -no-color`: "Success! The configuration is valid."
5. `scripts/emr/check-submit-args.sh`: not executed. The guard returned "BLOCKED [script-provenance]: scripts/run-bounded-spark.sh is modified relative to HEAD". The provenance rule covers every file in scripts/**. The human re-runs it after the commit (below).
6. `git diff --stat 692c173 148b4b9 -- build.sbt project modules/core modules/spark`: 3 files (TemporalJob.scala, TemporalDeterminismSuite.scala, project/SparkSubmitLocal.scala). This is the input for the negative test below.
7. `git rev-parse --verify 692c173`: 692c1734d07427b156aebe3b7391f2a4d2358077
8. `git status --short`: the three M lines plus five untracked evidence files (listed below).
9. `git diff --no-ext-diff --no-textconv -- <3 files>`: complete diff at the end of this report.

TEST RESULTS: none. No sbt and no dry run were in scope. The dry run needs a committed tree.

VALIDATION RESULTS:
- fmt: pass.
- validate (emr-run): pass. validate does not evaluate tfvars; the `^[0-9a-f]{64}$` variable validation applies at plan time, and both values now match it.
- plan counts: not run (plan.sh is excluded by the work order).
- script syntax: bash -n pass; shellcheck pass.
- check-submit-args.sh: not run (blocked by the provenance guard, see command 5).
- security scan: none run. No scanner is selected yet (B5 open).
- Secrets: the diff adds only hex digests and comments, with no secrets, ARNs or account IDs.
- No REPLACE-ME is left in either config file. The only placeholder left is the 64-zero input_tree_sha256, as instructed.

How the new check works (scripts/run-bounded-spark.sh, after line 135):
  readonly CODE_SCOPE="build.sbt project modules/core modules/spark"
  CS_RC=0
  git diff --quiet --no-ext-diff --no-textconv "$C_code_version" HEAD -- $CODE_SCOPE || CS_RC=$?
  exit 1 -> prints the changed file names (git diff --name-only, max 50) to stderr, then FAIL exit=3 "code scope (...) differs between code_version <sha> and HEAD <sha> (files above); the job jar no longer matches the run tree: rerun sbt sparkSubmitLocal at the new commit and update code_version, the jar and its pins in the run config"
  other non-zero -> FAIL exit=3 "git diff of the code scope ... failed (exit N)"
  0 -> CHECK ok code scope (build.sbt project modules/core modules/spark) unchanged between code_version <sha> and HEAD
- Git hygiene follows the script's existing plain git calls (require_tracked, merge-base). The script does not strip GIT_* or set --no-optional-locks anywhere, so I added neither.
- It compares two commit trees, so clean and smudge filters do not apply. --no-ext-diff and --no-textconv are added as defense.
- require_clean_tree already runs earlier, so HEAD equals the work tree.

How the human can verify it: no self-check covers run-bounded-spark.sh. check-submit-args.sh tests only the EMR wrapper, so I added no case. Two manual checks:
- Positive check: dry run 1 must print the `CHECK ok code scope … unchanged between code_version 148b4b91bc904a63680b94f91f275173f743c4b6 and HEAD` line.
- Optional negative check, in a Terminal, after the commit:
  1. `git switch -c tmp-m1-check`
  2. Set `code_version=692c1734d07427b156aebe3b7391f2a4d2358077` in the run config.
  3. `git commit -am "tmp m1 check"`
  4. `scripts/run-bounded-spark.sh --dry-run dev infra/terraform/environments/dev/runs/spark-dev-run-01.conf`
  5. Expected: on stderr, the three files from command 6, then `[run-bounded-spark] FAIL exit=3 code scope (...) differs ...`, exit status 3.
  6. Clean up with `git switch main && git branch -D tmp-m1-check`.

Corrected text for CR-P6-37 nits:
- n1: expected line is `[plan] CHECK ok profile scholarchain-admin (deploy-first-plan), Region us-east-1`. The ", Region us-east-1" suffix is part of the line (scripts/plan.sh:67).
- n2: While spark_dist_sha256 (or any key) holds REPLACE-ME, parse_kv (scripts/lib/common.sh:110) refuses the whole run config first, before check_local runs. check_local reports a digest mismatch only once a wrong hex value is filled in. (Moot now that the value is filled.)
- n3: stage and commit first, then `git status --porcelain`, which must print nothing.
- n4: If ~/ScholarChain-archive/phase-06/dev-run-01 is missing or is a symlink, the dry run prints `NOTE input archive … not present locally (needed for --stage only)` and still ends `RESULT DRY-RUN-OK`. Its CONFIRM line shows tree_sha256=000…000, and its two CONFIRMATION-TOKEN values are bound to the zero tree. Those tokens are unusable: --stage refuses and --execute refuses. Discard them, restore the archive and re-run the dry run.

First dry run: scripts/run-bounded-spark.sh --dry-run dev infra/terraform/environments/dev/runs/spark-dev-run-01.conf
What to look for, in order:
1. `CHECK ok head <new HEAD>` and `CHECK ok clean tree`.
2. The environment config line.
3. `CHECK ok code scope (build.sbt project modules/core modules/spark) unchanged between code_version 148b4b91bc904a63680b94f91f275173f743c4b6 and HEAD` (new).
4. `CHECK ok run config …`, then the BOUND lines: worst_case_cost_usd 0.7314 with max_cost_usd 1, cost_window_minutes 86.
5. Five `CHECK ok … digests match` lines: job jar, spark distribution (sha512 and the new sha256), scala-library, hadoop-aws, sdk bundle. Any FAIL "sha… differs from the config" means a stale or rebuilt file; stop.
6. `CHECK ok input tree …: N files, B bytes`, then `FAIL exit=3 computed input tree manifest sha256 <64 hex> differs from input_tree_sha256=000…`. That refusal is the expected result of dry run 1. Review N and B, then have infra-engineer write the <64 hex> value, commit it, and run dry run 2.
7. If you instead see the NOTE "input archive … not present locally" and RESULT DRY-RUN-OK, see corrected n4: the tokens are unusable.

ASSUMPTIONS:
- The tree-to-tree `git diff --quiet` exit status (0 equal, 1 differs) is reliable with --no-ext-diff and --no-textconv. That is git's documented behaviour; I have not seen it run in this script.
- The human's sha256 values were taken at 148b4b9, and the two scripts/emr files have not changed since. My diff does not touch them.

RISKS:
- The new check is unexercised until the first committed dry run.
- Any future commit touching modules/spark, modules/core, project or build.sbt, including test resources such as analysis-dev.conf, now blocks the run until sparkSubmitLocal is re-run and the pins are updated. This is intended.
- `sbt clean` before --stage still deletes the pinned jars and the distribution under target/ (CR-P6-37 risk, unchanged).

MANUAL ACTION REQUIRED:
- Commit, including the untracked evidence files.
- Run the post-commit checks, check-submit-args.sh and dry run 1.
- Optionally run the negative check.
- Review the computed input tree hash, then route its write to infra-engineer.
- The code-reviewer should review this script change before any --stage.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: code-reviewer review of this diff, then the human commit, check-submit-args.sh RESULT PASS, dry run 1 (computed input_tree_sha256), the infra-engineer commit of that value, dry run 2 (RESULT DRY-RUN-OK with the real tree), and the bootstrap plan in the human's Terminal.
```
