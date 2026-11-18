# Phase 06 human order: SP-P6-04 acceptance block and EMR proposal continuation (2026-11-17)

Verbatim:

> Use the existing SP-P6-04 sbt check and sparkIT run 08 evidence.
> Do not repeat those checks without a new change or specific failure.
> Return the acceptance-and-commit block, then continue preparing the
> minimum EMR runtime and infrastructure proposal. AWS execution and
> spending remain pending approval.

## Orchestrator handling

- No check repeated. `git status --short` shows the same SP-P6-04 tree as sparkit-run-08.md (17 modified files under modules/spark, untracked store/, job/, the three new suites and test resources) plus eight evidence files; `prompt-milestone-acceptance-and-emr-proposal.md` and `emr-docs-research-01.md` are already in facb73d.
- `.claude/hooks/verify-tests-ran.sh --check`: `OK (no uncommitted source change is newer than .claude/.last-test-run)`.
- No agent routed in this turn. Every remaining EMR preparation step either changes code that is waiting for this commit (CR-P6-26 nits, startup check, assembly settings) or depends on a decision in emr-proposal.md section 7 (runtime, lifecycle, environment, account, Region). Routing it before the commit would mix it into the reviewed SP-P6-04 diff. Routing it before the decisions would mean building against an unapproved runtime, which the human order of 2026-11-17 forbids.
- No AWS call made; aws-api and aws-docs MCP servers not connected (uvx not on PATH); terraform and github MCP servers not connected.
