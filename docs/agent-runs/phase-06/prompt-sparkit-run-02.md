# Phase 06 sparkIT run 02: work order (verbatim)

Received by the orchestrator on 2026-11-17 after the human committed 7244398. The text is the human's own words.

```text
The reviewed repair and retry approval are committed. Read HEAD and
confirm the build and Spark suite match the commit. The read-only dev
archive scan found no .DS_Store files.

Have spark-engineer run standalone sbt sparkIT once. Record the commit
SHA, observed edge counts, JUnit result, and Parquet integrity evidence.
If the run fails, report the exact failure and stop. Keep the next Spark
step and AWS work on hold.
```

## Orchestrator pre-run observations (2026-11-17)

- Audit interruption: the first Bash call of this step (`git rev-parse HEAD`) was refused UNATTRIBUTED for `docs/agent-runs/phase-06/human-gate.md` (owner HUMAN). This was the human's gate entry, committed in 7244398 (the tree is clean). The baseline was reset and the same command was re-run.
- `git rev-parse HEAD`: `7244398258894dbe400ae3811cd1c0ca9d9d6322` ("Phase 6: accept Spark graph foundation and curated archive repair").
- `git status --porcelain`: empty. `git ls-files --others --exclude-standard`: empty.
- `git show --stat 7244398`: 36 files, 6165 insertions, 28 deletions: the 2 modified files, 18 new modules/spark files, 15 evidence files and human-gate.md, matching the commit scope returned after CR-P6-21.
- `git diff --stat HEAD -- build.sbt project modules/spark`: empty (build and Spark suite match the commit).
- `git diff --stat e8c6ecc HEAD -- build.sbt project`: `project/SparkItRuns.scala | 694`, `1 file changed, 669 insertions(+), 25 deletions(-)` (BE-P6-04 only).
- human-gate.md entry "Curated archive repair and Spark integration retry (2026-11-17)" approves one standalone `sbt sparkIT` retry by spark-engineer with the reviewed build in 7244398, superseding the e8c6ecc build approval for this retry; allowlist scope unchanged; human's read-only archive scan found no `.DS_Store`.
