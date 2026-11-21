# Human fixture copy, 2026-11-20 ~15:53 CST (human Terminal) — evidence record

Human statement (verbatim):
> The consolidated fixture copy completed successfully: ALL CHECKS PASSED, exit status: 0. The grouping run, curated run, and analysis-dev.conf are now under modules/storage/src/test/resources/fixtures/. Preserve the evidence distinction: grouping parts matched committed Phase 6 hashes; curated files matched the archive at copy time, without historical SHA-256 verification; settings matched Git HEAD. The source archive remained unchanged. Disk space is 13 GiB.

## What was copied (block given by the orchestrator, run by the human; agents paused)
| Destination (under modules/storage/src/test/resources/fixtures/) | Source | Evidence level |
|---|---|---|
| `phase06-grouping-dev/run=84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2/` (7 files) | `/Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01/target/local-runs/dev/er-assignments/run=84dd…d7d2` | Each of the 5 listed parts matched size and sha256 in the committed record `docs/agent-runs/phase-06/dev-run-01.md` lines 96-100 (git HEAD) and appears in the archive `_parts.json`; `_parts.json` and empty `_SUCCESS` copied byte-identical. |
| `phase06-curated-dev/snapshot=dblp-2026-10-03/run=2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa/` (whole run directory: `_run-manifest.json` and the blocking/, candidates/, exact-ids/, normalize/, teams/ stages with `_SUCCESS`, `_counters.json` and `.crc` sidecars; the audit recovery listing showed these paths plus the 7 grouping files and analysis-dev.conf, and nothing else) | `…/dev-run-01/modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=2881…8baa` | Byte-identical to the archive at copy time (`diff -rq` and per-file sha256 equality). **No historical SHA-256 verification exists for these files**; only Hadoop CRC32 sidecars and the earlier human `diff -rq` (2026-11-16). |
| `phase06-analysis-settings/analysis-dev.conf` | committed `modules/spark/src/test/resources/scholarchain/spark/dev-run-01/analysis-dev.conf` | `git hash-object --no-filters` equal to the HEAD blob before and after copy; sha256 at copy time `1f408973834c532fb2d5d3931aaf8d0446d7afbb06c2bcf55878f9257306b6a1` (from the human's screenshot). |

- Archive unchanged: per-file sha256 snapshots of both archive sources equal before and after the copy; the committed settings source unchanged.
- The sha256 values printed in step 6 of the block were computed at copy time from the verified copies; they are not archive evidence.
- No held-out, gold, label, `.DS_Store` or symlinked entry was present in the sources (step 0).
- This copy is new fixture data, not a test run. The 21:44:08Z test marker had already been reviewed.
- Audit recovery (orchestrator): the next `git status --porcelain` was refused UNATTRIBUTED listing only paths under the three new fixture directories (the human's copy); re-run succeeded with the eight expected entries.
- Correction to SE-P7-06a's estimate: the curated run directory holds more stages than the `normalize/` subset described; the whole directory was copied as recommended.
