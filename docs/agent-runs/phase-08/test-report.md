# Phase 08 test report (orchestrator assembly, 2026-11-21)

Verbatim figures from test-engineer reports TE-P8-01 and TE-P8-02. The orchestrator did not run sbt.

## TE-P8-01 (superseded as marker evidence)
- Fixed tests/src/test/scala/scholarchain/tests/storage/PartIntegrityAdversarialSuite.scala and SummaryTextInjectionAdversarialSuite.scala for the SE-P8-01 key format and embedding work manifest.
- `sbt test` and `sbt check` overlapped for about 6 minutes in shared target/ directories. Both exited 0 (2021 passed). The marker it wrote (written_at_utc=2026-11-21T20:31:34.449305Z) was flagged UNATTRIBUTED by audit-bash-writes.sh at the next orchestrator Bash call. Per the human's instruction, it was replaced by the isolated run below.

## TE-P8-02: isolated `sbt check` (human instruction 2026-11-21)
- Pre-checks: `git status --porcelain` showed exactly the expected 18 modified and 3 untracked paths. `project/target/active.json` was absent (no sbt server). No ps access; the previous test-engineer task had ended with no live background children.
- Command: `sbt check`, started once, with no other sbt command running.
- Saved output (session scratch, not durable; 5,436,671 lines): /private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/3052db87-ce68-4ddd-b02e-e6a106a53520/tasks/b03n16if1.output
- Exit code: 0
- Final line: `[success] Total time: 379 s (0:06:19.0), completed Nov 21, 2026, 2:50:57 PM`

markTestRun lines (verbatim):
```
[info] markTestRun: core: production sources=28, test sources=32, passed=621
[info] markTestRun: entityResolution: production sources=40, test sources=41, passed=464
[info] markTestRun: hadoop: production sources=18, test sources=13, passed=85
[info] markTestRun: rag: production sources=0, test sources=0, passed=0
[info] markTestRun: root: production sources=0, test sources=0, passed=0
[info] markTestRun: services: production sources=0, test sources=0, passed=0
[info] markTestRun: sparkAnalytics: production sources=26, test sources=32, passed=263
[info] markTestRun: storage: production sources=48, test sources=48, passed=410
[info] markTestRun: tests: production sources=0, test sources=25, passed=178
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=ef2d7df93380a9a7e7e3d93b6f069de2c05edfcf, worktree=dirty, suites=163, passed=2021, skipped=0)
```

Marker `.claude/.last-test-run` after the run (verbatim):
```
scholarchain-test-marker-v1
written_by=sbt markTestRun
written_at_utc=2026-11-21T20:50:57.046988Z
git_head=ef2d7df93380a9a7e7e3d93b6f069de2c05edfcf
git_worktree=dirty
projects=core,entityResolution,hadoop,rag,root,services,sparkAnalytics,storage,tests
suites=163
tests_passed=2021
tests_failed=0
tests_errored=0
tests_skipped=0
production_sources=160
```

Limits: test-engineer read only the tail of the output and did not search it for non-fatal `[error]`/`[warn]` lines (no grep access). The marker is not bound to a commit (worktree=dirty). This run predates the CR-P8-01 repairs, so it is not final Phase 8 evidence.

## Intermediate runs (superseded; each marker reviewed by the human)
| Work order | Command | Result | Marker |
|---|---|---|---|
| SE-P8-02 | `sbt check` (backgrounded by the harness at 180 s) | exit 0, 164 suites, 2027 passed; completed 3:13:00 PM | written 21:13:00Z; human reviewed |
| SE-P8-03 | `sbt check` (backgrounded) | exit 1: tests 177/178, SummaryTextInjectionAdversarialSuite L226-230 used the unnamed reader; completed 3:48:20 PM | not written |
| TE-P8-03 | `sbt check` (backgrounded) | exit 0, 164 suites, 2030 passed; completed 3:57:29 PM | written 21:57:29Z; human reviewed |
| RE-P8-01 | `sbt check` (backgrounded) | exit 0, 168 suites, 2058 passed (rag 28); completed 4:20:45 PM | written 22:20:45Z; human reviewed |
| RE-P8-02 | `sbt rag/test` | 35 passed; completed 4:37:14 PM | n/a |
| TE-P8-04 | `sbt test` (backgrounded) | exit 0, 2080 passed, 1 skipped; completed 4:53:42 PM | n/a (sbt test writes no marker) |

## FINAL: TE-P8-05, the one final `sbt check` after all edits
- Pre-checks: `git status --porcelain` matched the expected set; `project/target/active.json` absent.
- Command: `sbt check`, once. Moved to the background by the harness after 180 s; no other sbt command ran.
- Saved output (session scratch, not durable; 6,644,059 lines): /private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/3052db87-ce68-4ddd-b02e-e6a106a53520/tasks/bmdcm0j3y.output
- Exit code: 0
- Final line: `[success] Total time: 393 s (0:06:33.0), completed Nov 21, 2026, 5:02:18 PM`

```
[info] markTestRun: core: production sources=28, test sources=32, passed=621
[info] markTestRun: entityResolution: production sources=40, test sources=41, passed=464
[info] markTestRun: hadoop: production sources=18, test sources=13, passed=85
[info] markTestRun: rag: production sources=5, test sources=4, passed=35
[info] markTestRun: root: production sources=0, test sources=0, passed=0
[info] markTestRun: services: production sources=0, test sources=0, passed=0
[info] markTestRun: sparkAnalytics: production sources=26, test sources=32, passed=263
[info] markTestRun: storage: production sources=48, test sources=49, passed=419
[info] markTestRun: tests: production sources=0, test sources=26, passed=193
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=ef2d7df93380a9a7e7e3d93b6f069de2c05edfcf, worktree=dirty, suites=169, passed=2080, skipped=1)
```
`tests` project: `Passed: Total 194, Failed 0, Errors 0, Passed 193, Skipped 1`. The skipped test is, by inference from TE-P8-04's output, SemanticRetrievalCorpusSmokeSuite "researcher ID -> community summaries": no community-summary record names a researcher ID (0 IDs), so it skips via `assume`.

Marker after the run (verbatim):
```
scholarchain-test-marker-v1
written_by=sbt markTestRun
written_at_utc=2026-11-21T23:02:18.658703Z
git_head=ef2d7df93380a9a7e7e3d93b6f069de2c05edfcf
git_worktree=dirty
projects=core,entityResolution,hadoop,rag,root,services,sparkAnalytics,storage,tests
suites=169
tests_passed=2080
tests_failed=0
tests_errored=0
tests_skipped=1
production_sources=165
```
Deviation reported by test-engineer: one extra Bash call (`tail -c 3000 <output>`) while the run was backgrounded; refused by guard-dangerous.sh, nothing executed.

## Retrieval smoke tests: NON-PRODUCTION PLUMBING ONLY
SemanticRetrievalCorpusSmokeSuite (15 tests: 14 passed, 1 skipped) loads the existing 249 local items (researcher-profile 176, community-summary 51, path-summary 9, work-summary 8, topic-summary 5) with the non-production stand-in embedder `synthetic-nonprod-hashed-token-bag.v1`. Its neighbour cases query a token that only the expected documents contain, so they verify load, store, named-model query, text pointer, template parse and excerpt withholding. They are NOT evidence of retrieval quality, embedding quality or production readiness. No corpus record was fabricated; no instructor approval for a smaller corpus is claimed.
