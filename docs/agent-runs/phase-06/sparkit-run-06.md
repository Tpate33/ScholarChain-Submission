# Phase 06 sparkIT run 06: spark-engineer report (2026-11-17)

- Work order: prompt-sparkit-run-06.md (early archive-backed check of steps 2A-2B, under prompt-sp-p6-03-consolidated.md).
- Code: uncommitted working tree on HEAD `c2d9a8216b945f2dd57eb587099f0b938ed1d670`. `git diff --stat` at run time: 26 files changed, 2774 insertions(+), 323 deletions(-) (11 core, 14 spark, project/SparkItRuns.scala), plus 30 untracked (core and spark sources from DE-P6-03 and steps 2A-2B, phase evidence). `git status --porcelain` identical before and after. Run manifest codeVersion is the placeholder (`unpinned-sparkit-dev`); this tree state is the link to code.
- STATUS: PASS. One `sbt sparkIT`, `[success] Total time: 27 s, completed Nov 17, 2026, 2:59:23 PM`. No hook refusal. Attempts 1-3 unchanged (Read before and after).

## Verbatim result files (attempt-4)

`junit-result.txt`:

```
format=scholarchain.sparkit.junit-result.v2
nonce=e6b3532abdd96e6545ff05f4348c3e90e7454b330c666e943a9fa28ff0cc3a95
junit	JUnitCore	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
junit	Result	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
class	scholarchain.spark.DevArchiveGraphFoundationSuite	run=13	failures=0	ignored=0	assumptionFailures=0
classes=1
complete=e6b3532abdd96e6545ff05f4348c3e90e7454b330c666e943a9fa28ff0cc3a95
```

`result.txt`: `status=pass`, `tests=13`, `exit=0`, `expectedClasses=1`, expected and loaded JUnit junit-4.13.2.jar (implementationVersion 4.13.2), `class=scholarchain.spark.DevArchiveGraphFoundationSuite run=13 failures=0 ignored=0 assumptionFailures=0`.

## Printed summaries (verbatim, as reported)

```
SP-P6-02 sparkIT: analysisRunId=run:8089aefb064668d15edda16c5b9f9ba1ed015cbd18bb545071726458a55160b1 ... edges=259 metrics=176 configDigest=c2f9c94135074c8bf2b282d875b965cc0420ee02fe1e06bc313552af0743e6ce benchmarkPairsDigest=2e1ff1824ba18c90a8b154e9932c51c64565779f62eeb2a519df22ea20bc8be9
SP-P6-02 sparkIT window: WindowInputSummary(TimeWindow(1970,2026),41,112,0,163,182)
SP-P6-02 sparkIT window: WindowInputSummary(TimeWindow(2020,2024),24,64,0,96,107)
SP-P6-02 sparkIT metrics window: MetricsWindowSummary(TimeWindow(1970,2026),112,135,364,0,0,326,364,4)
SP-P6-02 sparkIT metrics window: MetricsWindowSummary(TimeWindow(2020,2024),64,78,214,0,0,192,214,4)
SP-P6-03 sparkIT structure window (computed only, checked by validator): window=1970-2026 researchers=112 edges=163 components=31 largestComponent=8 largestComponentFraction=0.07142857142857142 singletonComponents=1 communities=32 largestCommunity=8 singletonCommunities=1 crossCommunityEdges=6 maxCoreness=4 corenessHistogram=0:1,1:13,2:30,3:32,4:36 labelPropagation=3/true/0
SP-P6-03 sparkIT structure window (computed only, checked by validator): window=2020-2024 researchers=64 edges=96 components=18 largestComponent=8 largestComponentFraction=0.125 singletonComponents=1 communities=19 largestCommunity=5 singletonCommunities=1 crossCommunityEdges=6 maxCoreness=4 corenessHistogram=0:1,1:8,2:15,3:12,4:28 labelPropagation=2/true/0
SP-P6-03 sparkIT structure iterations (computed only): componentIterations=3 corenessRounds=6 checkpoints=5
SP-P6-03 sparkIT bridge (computed only): ((1970,2026,0,0),107) ((1970,2026,2,1),3) ((1970,2026,3,1),2) ((2020,2024,0,0),59) ((2020,2024,2,1),3) ((2020,2024,3,1),2)
SP-P6-03 sparkIT canonical (computed only, checked by validator): nodes=Vector((Researcher,117), (AuthorMention,143), (Work,45), (Venue,64), (Institution,60), (Topic,30)) edges=Vector((AUTHORED,143), (RESOLVES_TO,143), (COAUTHORED_WITH,259), (PUBLISHED_IN,70), (AFFILIATED_WITH,143), (HAS_TOPIC,41)) unmatched=UnmatchedReferences(0,0,0) csvParts=...:1 (12 roles, 1 part each)
```

(The bridge lines are printed one per tuple; joined here.) Window-structure records repeat the structure values (iterations 3 and 2, converged true). Identity lines as in run 05.

Reproducibility (two builds in the one forked JVM): `logicalEqual=true` and `sameSchema`/`sameHeader=true` for all 9 Parquet datasets (edges 259, metrics 176, components 176, communities 176, bridges 176, coreness 176, graph-node 459, graph-edge 799, window-structure 2) and all 12 Neptune CSV datasets; `sameBytes=false` for all except window-structure (diagnosis only).

## Expected versus observed

| Expected (prompt-sparkit-run-06.md) | Observed |
|---|---|
| 13 IT tests | match |
| edges 259, metrics 176 | match |
| nodes 117/45/143/64/60/30 | match |
| AUTHORED 143, RESOLVES_TO 143, COAUTHORED_WITH 259 | match |
| windows 112/163, 64/96 | match |
| logical reproducibility for every dataset | match (21 datasets) |
| attempt-4, attempts 1-3 untouched, no clean | match |
| 120 s bound | 27 s |

Not in the expectations, observed: PUBLISHED_IN 70, AFFILIATED_WITH 143, HAS_TOPIC 41; unmatched references 0/0/0.

## Integrity evidence

`_parts.json` (run `run:8089aefb…60b1`) lists 22 files; key entries: `_run-manifest.json` 1160 bytes sha256 `165b8c32…f0ff`; edges 259 rows 53965 bytes `e287cb00…1fdc`; metrics 176 rows 24436 bytes `9c64a207…fd79`; components 176 `6db1c79d…e610`; communities 176 `605ca9a9…5613`; bridges 176 `0b9c8f8a…8586`; coreness 176 `0adae612…3873`; graph-nodes 459 `081890c5…b796`; graph-edges 799 `70d33d57…8fb7`; window-structures 2 `3294bd7b…30c9`; 12 CSV parts (row counts from the writer: 117, 143, 45, 64, 60, 30, 143, 143, 259, 70, 143, 41). The full `_parts.json` and `_run-manifest.json` are in the spark-engineer report text and remain in attempt-4; the run manifest records the three pinned input runs, `community.converged.*=true`, `community.iterations.1970-2026=3`, `.2020-2024=2`, and the ten-entry `outputs` list. `_SUCCESS` present, `_UNVERIFIED` absent. sha256 values not recomputed by any agent (no hash command allowed); covered by the suite's verifyPublished.

## Observations for the review

- Archive-scale structure: the dev graph is fragmented (31 components in 1970-2026, largest 8 of 112 researchers); expected for a 41-work dev sample, not a defect.
- Edges Parquet is now 53965 bytes (run 05: 53966); different run ID, not comparable row for row.
