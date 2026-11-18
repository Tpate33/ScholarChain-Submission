# Phase 06 IE-P6-02 repair 03: infra-engineer, CR-P6-30 m3, m4, n4 and CR-P6-29 n4 (2026-11-17)

- Routed under prompt-pause-handoff.md. Authoring only: no AWS call, deploy, upload or spend.
- STATUS: COMPLETE. Modified (untracked): scripts/deploy.sh, scripts/lib/common.sh, scripts/run-bounded-spark.sh, scripts/emr/submit-collaboration-job.sh.

## Changes

1. m3 deploy.sh: right after sourcing common.sh, exit 3 if `CLAUDECODE` is set, or unless `[[ -t 0 && -t 1 ]]` (mirrors cleanup.sh:46-47); header updated.
2. m4 common.sh `load_env_conf`: exit 3 unless `aws_readonly_profile` is exactly `scholarchain-readonly` and differs from the deploy and operator profiles. Committed env.conf passes.
3. n4 run-bounded-spark.sh: helpers `no_dot_segment`, `need_local_path_value`; before any hashing or listing, refuses `input_archive_home_relative` with a segment starting with `.`, and checks all five `*_local_path` keys (`~/` with a dot segment refused; otherwise `is_repo_path`); `local_path` repeats the refusal.
4. CR-P6-29 n4: `HC_MIN_STEP_TIMEOUT_MINUTES` 7; wrapper `PREPARATION_ALLOWANCE_SECONDS=60`, `MIN_STEP_TIMEOUT_SECONDS` = 240 + 120 + 60 = 420. Committed config unchanged at 30.

## Commands

`git ls-files --others --exclude-standard -- infra/terraform scripts`; `bash -n` on the four files: exit 0 each, no output; `shellcheck` exit 127 (`command not found`); `git diff --stat` (tracked files only); one compound `ls` refused by the guard. cleanup.sh unchanged and unchecked. No Terraform change.

## Risks (as reported)

Wrapper sha256 changed (`submit_script_sha256` still REPLACE-ME; take values from the current files; fresh `--dry-run` and `--stage`); shellcheck findings unknown; the deploy.sh TTY check also stops any future non-TTY deploy-engineer use (intended under D1).
