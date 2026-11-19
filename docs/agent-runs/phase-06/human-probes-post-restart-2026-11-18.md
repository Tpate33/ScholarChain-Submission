# Phase 06: post-restart probe validation, human Terminal run (2026-11-18)

Context: FE-P6-07 (fe-p6-07-report.md) ran the validator once inside the session; its tool output was truncated, so the NOTE and RESULT lines were not visible and the match could not be confirmed. Routed to the human.

Human message (verbatim, filtered output of the post-restart Terminal run at HEAD 103d05d):

```
FAIL  shellcheck .claude/hooks/audit-bash-writes.sh
FAIL  shellcheck .claude/hooks/enforce-ownership.sh
FAIL  shellcheck .claude/hooks/lib-baseline.sh
FAIL  shellcheck .claude/hooks/post-write.sh
FAIL  shellcheck .claude/hooks/verify-tests-ran.sh
NOTE  888 table probes run
NOTE  51 fixture probes run
RESULT: FAIL (5 failures)
```

Orchestrator comparison with human-probes-and-quota-2026-11-18.md and human-gate.md (FE-P6-06 application acceptance):

| Item | Earlier record | Post-restart run | Match |
|---|---|---|---|
| Table probes | 888 | 888 | yes |
| Fixture probes | 51 | 51 | yes |
| RESULT | FAIL (5 failures) | FAIL (5 failures) | yes |
| FAIL lines | shellcheck of audit-bash-writes.sh, enforce-ownership.sh, lib-baseline.sh, post-write.sh, verify-tests-ran.sh | same five | yes |

The in-session FE-P6-07 run showed the same five FAIL lines and no other visible failure. No additional failure. The five ShellCheck findings remain deferred (CR-P6-33 m1). Proceeding to `sbt sparkSubmitLocal`.
