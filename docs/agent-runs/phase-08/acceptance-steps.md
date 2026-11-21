# Phase 08 acceptance steps for the human (prepared by the orchestrator, 2026-11-21)

Nothing here has been run. Commits are human-only.

## 1. Review
- test-report.md: the final `sbt check` (5:02:18 PM, 169 suites, 2080 passed, 1 skipped, exit 0) and the marker contents.
- review-report.md: the verdicts (final reviews APPROVE) and the deferred non-blocking findings.
- agent-report.md: what was delivered and what is OUTSTANDING.
- Diff: `git status --short` and `git diff --stat`; then `git diff -- modules/storage tests` and the untracked files under modules/rag, prompts/schemas and tests/src/test/scala/scholarchain/tests/rag.

## 2. Write human-gate.md (human-owned)
Record in docs/agent-runs/phase-08/human-gate.md: what you reviewed; accepted or rejected; any manual changes; the marker reviews you made (21:13:00Z, 21:57:29Z, 22:20:45Z, and the final 23:02:18Z); remaining risks (OUTSTANDING list); that the retrieval smoke tests are non-production plumbing only; that there is no instructor approval for a smaller corpus.

## 3. Commit the implementation (Terminal)
```sh
cd /Users/taufeeqpatel/Desktop/ScholarChain
git status --short
git add modules/storage tests/src/test/scala/scholarchain/tests/storage tests/src/test/scala/scholarchain/tests/rag modules/rag/src prompts/schemas/semantic-context.v1.schema.json docs/agent-runs/phase-08
git status --short
git commit -m "Phase 8: local embedding work queue, model-scoped vectors, RAG semantic context; AWS ingestion outstanding"
git rev-parse HEAD
```
Check that the second `git status --short` shows nothing unstaged or untracked except files you chose to leave out (for example, add human-gate.md before committing if you want it in this commit).

## 4. Record the hash (Terminal)
```sh
git rev-parse HEAD > docs/agent-runs/phase-08/commit.txt
git add docs/agent-runs/phase-08/commit.txt docs/agent-runs/phase-08/human-gate.md
git commit -m "Record Phase 8 local closeout commit"
```
(This mirrors Phase 7: the implementation commit, then a separate hash-record commit.)

## Notes
- The marker records `git_head=ef2d7df`, `worktree=dirty`. It covers exactly the uncommitted tree that will be committed, provided nothing changes before the commit.
- The session audit hook will report the marker change as UNATTRIBUTED at the next orchestrator Bash call (documented recovery).
