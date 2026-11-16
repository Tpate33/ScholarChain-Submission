# Phase 5 audit recovery 01: test-marker change between audited calls

## Incident

- ER-P5-02 (`entity-resolution-engineer`) started `sbt check` as a background Bash task
  (23:38 local, 2026-11-04). The agent was then terminated by an API usage limit and resumed.
- The background run's `markTestRun` wrote `.claude/.last-test-run` after its Bash call had
  returned, i.e. between audited calls.
- `verify-tests-ran.sh` (Stop) refused orchestrator completion: "The test marker
  .claude/.last-test-run changed after the last audited Bash call of this session, without an
  audited 'sbt check' ... Possible test-marker forgery".
- `audit-bash-writes.sh --pre` refused the agent's foreground re-run of `sbt check` as an
  UNATTRIBUTED inter-call change to `.claude/.last-test-run` (owner GENERATED).
- Incident log (temporary directory, outside the repository; preserved, not modified by the
  orchestrator):
  `/var/folders/zt/pbpscx0s72b8jch7_wr7gc9m0000gn/T/scholarchain-bash-audit-501/session-31ac882037fa1279f7fc3022ee76cd91.incidents.log`

## Human review (reported by the human, 2026-11-05)

The human inspected `.claude/.last-test-run` in a separate Terminal: `written_by=sbt markTestRun`,
timestamp `2026-11-05T05:40:17.427706Z`, HEAD `1bc5382c00200b76d03f6a88ffdbbac53220760e`, dirty tree,
49 suites, 604 passed, 0 failed / errors / skipped. The human stated this confirms the marker
contents only, not the test output.

## Status of the 604-test result

**Agent-reported and unaudited.** It came from a background run whose marker write was not
attributed by the audit hook. It is not accepted as test evidence.

## Recovery step (human-approved)

- Approval: the human approved one `git status --porcelain` through the existing hooks, followed by
  one foreground-only `sbt check` through the owning agent. No hook, permission, or marker change.
- Orchestrator call: `git status --porcelain` (ORCHESTRATOR `@GIT` row).
- Observed outcome: the call **ran normally**; the audit hook emitted **no UNATTRIBUTED report**
  and did not refuse it. Interpretation: the baseline had already been reset by the
  `audit-bash-writes.sh --pre` report that refused the agent's re-run (the documented reset
  behaviour). The orchestrator cannot inspect the baseline file to confirm this directly.
- Paths reported: only the known Phase 5 paths: 15 modified files under `modules/core/**`
  (DE-P5-01), untracked `modules/core/.../MatchDecision.scala` and `MatchDecisionV2Suite.scala`,
  untracked `modules/entity-resolution/**` paths (ER-P5-01/02), and untracked
  `docs/agent-runs/phase-05/{agent-report,decisions-01,prompt}.md`. No change outside these was
  observed. (The marker is gitignored and does not appear in `git status`.)
- No marker, hook, permission, or incident-log change was made by the orchestrator.

## Verification run

Routed to `entity-resolution-engineer` as a single foreground `sbt check`. The commands, HEAD,
tree status, sbt result and counts below are as reported by the agent (its report is quoted in
`agent-report.md`); the orchestrator did not observe the sbt output directly.

- Commands, in order, foreground, repository root, each once: `git rev-parse HEAD`;
  `git status --porcelain`; `sbt check`; `git status --porcelain`.
- HEAD: `1bc5382c00200b76d03f6a88ffdbbac53220760e`.
- Working tree before and after: identical. Known Phase 5 paths only:
  - modules/core/**: 15 modified files, plus untracked MatchDecision.scala and MatchDecisionV2Suite.scala;
  - modules/entity-resolution/**: untracked paths;
  - docs/agent-runs/phase-05/: four untracked evidence files, including this one.
- Hook output: none; no refusal and no UNATTRIBUTED report.
- Exit status: sbt reported `[success] Total time: 114 s (0:01:54.0), completed Nov 5, 2026,
  12:15:29 PM`. The agent reported "success" from that line; no numeric exit code was quoted.
- Earlier steps: `Build sources OK`, `Version ledger OK: 14 pinned entries`, scalafmt checks [success].
- Verbatim counts:
  ```
  [info] Passed: Total 292, Failed 0, Errors 0, Passed 292
  [info] Passed: Total 196, Failed 0, Errors 0, Passed 196
  [info] Passed: Total 67, Failed 0, Errors 0, Passed 67
  [info] Passed: Total 49, Failed 0, Errors 0, Passed 49
  [info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=1bc5382c00200b76d03f6a88ffdbbac53220760e, worktree=dirty, suites=49, passed=604, skipped=0)
  ```
  (rag, root, services, sparkAnalytics, storage: no tests.)
- Total: 604 passed, 0 failed, 0 errors, 0 skipped, 49 suites.
- Full output (27.3 MB, session tool-results directory, not durable):
  `/Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/tool-results/b54u42iid.txt`
- Orchestrator check afterwards: `.claude/hooks/verify-tests-ran.sh --check` gave `OK (no
  uncommitted source change is newer than .claude/.last-test-run)`.

Result: the audited foreground run reproduces 604 passed on a dirty tree at HEAD 1bc5382. This is
agent-run, dirty-tree evidence only. It is not clean-commit evidence, and it does not accept any
Phase 5 work. The earlier background 604-test result stays labeled agent-reported and unaudited.
