# Phase 5 dev pipeline run 02 (agent run at commit B; evidence transcription)

Assembled by the orchestrator on 2026-11-15 from the final reports of hadoop-engineer, work orders HE-P5-DEV-01 and HE-P5-DEV-02. This is not a human-gate record. Dev split only: no held-out run, and no `sbt goldSetEvalDev`. Post-restart check C1 is still recorded as NOT RUN (see `post-restart-observations-01.md`).

## Context

- **HEAD:** commit B `71ff55199c454c7c2393c973955cb6950f4d5392`, verified by `git rev-parse HEAD` before the run.
- **Work tree:** no tracked changes. There were 54 untracked `docs/agent-runs/phase-05/*` evidence files, identical before and after the run.
- **Protections:** every hook and permission rule stayed enabled.
- **Earlier attempt:** HE-P5-DEV-01 (2026-11-14) ran `sbt localPipelineDev` once and was refused before any job ran. Exit 1; sbt reported `[error] Total time: 2 s, completed Nov 14, 2026, 11:51:57 PM`. The reason was `.../dev/attempt-1_is_a_complete_run_of_this_split_(it_has__SUCCESS);_refusing_a_second_run`. That attempt came from the human's Nov 13 Terminal run (`dev-run-01.md`). The human then archived it outside the repository at `/Users/taufeeqpatel/Desktop/ScholarChain-dev-attempt-1-Nov13`. No agent read the archive.

## Run (HE-P5-DEV-02, 2026-11-15)

Command: `sbt localPipelineDev`, run once in the foreground by hadoop-engineer.

The following lines are verbatim, as reported by hadoop-engineer:

```text
localPipelineDev: git_head=71ff55199c454c7c2393c973955cb6950f4d5392 git_worktree=dirty
[er-split] split=dev runId=run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa attempt=attempt-1 status=complete output=/Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1
localPipelineDev: complete (ErSplitPipelineMain exit 0); attempt directory modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1 is complete (_SUCCESS present)
[success] Total time: 12 s, completed Nov 15, 2026, 9:05:53 AM
```

`git_worktree=dirty` reflects the 54 untracked evidence files. There were no tracked changes.

## Results

| Item | Value | Source |
|---|---|---|
| Status | complete; sbt exit 0, ErSplitPipelineMain exit 0, `_SUCCESS` present; `_IN_PROGRESS` and `_FAILED` absent | sbt log, attempt directory |
| Run ID | `run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa` (the same value is in `_SUCCESS`) | sbt log, `_SUCCESS` |
| Attempt directory | `modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/` (git-ignored) | sbt log |
| Curated run root | `.../dev/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa/` | `_run-manifest.json` |
| Distinct researcher pairs | 296 | `summary.txt` line 20; `candidates/_counters.json` `CANDIDATE_PAIRS.researcher` |
| Distinct work pairs | 78 | `summary.txt` line 20; `candidates/_counters.json` `CANDIDATE_PAIRS.work` |
| Distinct total | 374 (`candidateMerged=0`; read-back 296 and 78) | `summary.txt` line 20 |
| Oversized-block drops | none: `groups=0 groupsWithDrops=0 membersDropped=0`, `droppedByKind=none`, `simulationMatchesRun=true` for both kinds; `OVERSIZE_BLOCK`, `MISSING_KEY` and `OVERSIZE_TEAM` are all 0 | `summary.txt` lines 21 (researcher, cap=8, policy=drop) and 30 (work); `blocking/_counters.json`, `candidates/_counters.json` |
| Largest block | 4 for both kinds: `researcher:0:alemparte\|m`, `work:0:empirical_distribution` | `blocking/_counters.json` |
| Teams | `teams=41 coauthorPairs=182 oversizeTeams=0 maxTeamSize=5(work:dblp:conf/aaai/YuanBZLD23) maxPairExpansion=10(work:dblp:conf/aaai/YuanBZLD23)` | `summary.txt` line 39 |
| Normalize | `rejected=0 flagged=2 works=90 mentions=286 profiles=6 duplicate=2 conflict=2`. The conflicts are DBLP `conf/cinc/FotiadouKHV19` and `conf/ppopp/DiceKL16`. The duplicates are OpenAlex `W3170161232` and `W4389175248` | `summary.txt` |
| Elapsed | sbt `Total time: 12 s`; pipeline `elapsed total ms=10847` (normalize 3580, exact-ids 1159, teams 1149, blocking 1161, candidates 3498) | sbt log, `summary.txt` |

Line 20 of `summary.txt`, verbatim:

```text
[er-split] split=dev distinct.researcher=296 distinct.work=78 distinct.total=374 candidatePairs=374 candidatePairs.researcher=296 candidatePairs.work=78 candidateMerged=0 readBack.researcher=296 readBack.work=78 check=distinct.total==candidatePairs-candidateMerged,readBack==distinct:ok
```

The run's own checks: `check=...:ok`, `provenance check=every-record-snapshot==manifest-snapshot:ok`, and `simulationMatchesRun=true` for both kinds.

## Code version

- The run's `_run-manifest.json` records `"codeVersion":"a961035e8863a1c4b3337557bb17fbab05a1182a"` (commit A). That value is copied from the committed `modules/hadoop/local-runs/er-candidates.dev.run-manifest.json`.
- HEAD at the run was commit B `71ff551`.
- The orchestrator ran `git diff --stat a961035 71ff551`. Its output:
  ```text
   modules/hadoop/local-runs/er-candidates.dev.run-manifest.json     | 2 +-
   modules/hadoop/local-runs/er-candidates.heldout.run-manifest.json | 2 +-
   2 files changed, 2 insertions(+), 2 deletions(-)
  ```
  The only change between A and B is the one-line manifest pin. Pipeline code at B is identical to A.

## Comparison with dev-run-01 (human, 2026-11-13, 36ffcf9 plus uncommitted changes)

These counts are the same as in run 01: 296 researcher, 78 work and 374 total pairs, zero drops, and normalize with 0 rejected, 2 flagged, 2 duplicates and 2 conflicts. dev-run-01 recorded "Cap 4"; this run's summary records `cap=8, policy=drop`. Neither run dropped any members.

## Not done

- No `sbt check` was run in this work order.
- The stage subdirectories and `target/local-runs/dev/` (JVM scratch) were not listed.
- No `sbt goldSetEvalDev` and no held-out task ran.

## Human action

To run `sbt localPipelineDev` again, the human must first archive `dev/attempt-1`, because the pipeline refuses while a complete attempt exists.
