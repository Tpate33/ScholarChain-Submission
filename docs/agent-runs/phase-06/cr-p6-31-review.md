# Phase 06 CR-P6-31: code-reviewer focused review of IE-P6-02 repair 03 and BE-P6-09 repair 01 (2026-11-17)

## Verdict: APPROVE

- CR-P6-30 m3 closed: deploy.sh:30-31 refuses CLAUDECODE and non-TTY stdin/stdout, after sourcing common.sh (:26), before argument parsing; same text as cleanup.sh:46-47; human Terminal not blocked; safe under `set -euo pipefail`.
- CR-P6-30 m4 closed: common.sh:139-141 requires `scholarchain-readonly`, distinct from deploy and operator profiles; env.conf:18-20 passes.
- CR-P6-30 n4 closed: run-bounded-spark.sh `no_dot_segment` (:149) on `input_archive_home_relative` (:158-159) and the five `~/` local paths (:150-163) before the first hash or listing (:193, :287-292); repeated at :180.
- CR-P6-29 n4 closed: run-bounded-spark.sh:50 = 7 minutes; submit-collaboration-job.sh:52-54 = 420 s; no stale 360 s or 6-minute text.
- CR-P6-27 m2 closed: build.sbt:594-613; SparkSubmitLocal.scala:1384, `headMoved` (:1356-1362), before any download or fork.
- CR-P6-27 m3 closed: SparkSubmitLocal.scala:1543-1567 with StrippedNames (:143-150); LocalRuns.scala:761 default `Set.empty`; `forkedEnvironment` (:729-735) equivalent; self-test :1811.
- CR-P6-27 n3 closed: SparkSubmitLocal.scala:689-723; `dest` never deleted; `drop()` (:661) deletes only the own nonce-named partial when `dest` matches; otherwise both kept and refused; match returns `downloadedNow=true` and :1419-1422 stops with "NOT a pass".
- No new defect.

## Findings

- n1 (nit, infra-engineer) scripts/deploy.sh:4 header still says "Operator: deploy-engineer (allowlist row, mode ask) or the human"; non-TTY deploy-engineer calls are now refused. Documentation only.

## Unverified concerns

`no_dot_segment` refuses `~/.ivy2/...` jar paths (repository or `~/Library/Caches/Coursier` pass); the 60 s preparation allowance at the 7-minute minimum is unobserved (committed run uses 30); `Files.createLink` exceptions other than FileAlreadyExists (e.g. hard links unsupported) propagate.

Risks: shellcheck unavailable; wrapper sha256 changed (recompute `submit_script_sha256`; fresh `--dry-run` and `--stage`); `sbt sparkSubmitLocal` never run.
