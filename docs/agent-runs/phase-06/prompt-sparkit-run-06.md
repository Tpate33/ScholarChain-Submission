# Phase 06 sparkIT run 06 (orchestrator work order under the consolidated authorization)

- Authority: prompt-sp-p6-03-consolidated.md ("Continue through necessary local diagnosis, repairs, tests and permitted sparkIT reruns"), prompt-sp-p6-03-audit-review.md.
- Purpose: an early archive-backed check of steps 2A and 2B (curated reader with CRC sidecars and name rules, canonical tables, structure algorithms, window structures, the 120 s bound with two builds) before steps 2C-2D build on them. Not the final integration run.
- Code: uncommitted working tree on HEAD c2d9a82; the run report records `git status --porcelain` and `git diff --stat` at run time (the run manifest's codeVersion is the unpinned placeholder).
- Expected: 13 IT tests; edges 259; metrics 176; nodes Researcher 117, Work 45, AuthorMention 143, Venue 64, Institution 60, Topic 30; edges AUTHORED 143, RESOLVES_TO 143, COAUTHORED_WITH 259; window researchers/edges 112/163 and 64/96; logical reproducibility for every dataset. New attempt directory (expected attempt-4); attempts 1-3 untouched; no `sbt clean`.
- On failure: exact failure recorded; diagnosis and repair go to the owner (spark-engineer for modules/spark, build-engineer for project/), then a rerun.
