# TE-P7-01 (test-engineer, WP8) and human marker review — 2026-11-21

## Human statement (verbatim)
> Marker reviewed for November 21, 2026, 3:07:45 AM CST: 162 suites, 2006 passed, storage 395, tests module 178, skipped 0, success and exit code 0. Follow documented audit recovery without editing the marker or weakening hooks.
>
> Prioritize the minimum needed to move to Phase 8. Recover the completed WP8 report from saved evidence, perform the final review, and make only necessary documentation corrections. Fix only findings that block correctness or the next phase; defer optional improvements.
>
> Skip the additional localPipelineDev rerun and record that the earlier attempt was refused because output already existed. Do not describe that rerun as verified. Leave attempt-1 untouched.
>
> Prepare the concise Phase 7 handoff and exact remaining blockers, if any. Reuse valid completed checks rather than repeating builds without a concrete reason. No cloud operations, spending, deployment, commits, deletions, or hook changes.

## TE-P7-01 report (recovered after an API timeout; agent resumed read-only, no Bash)
- FILES CREATED (tests/src/test/scala/scholarchain/tests/storage/): StorageAdversarial.scala (helpers), PartIntegrityAdversarialSuite (9), GraphIdConsistencyAdversarialSuite (6), LoadIdempotencyAdversarialSuite (5), GraphInjectionAdversarialSuite (2), VectorAdversarialSuite (7), SummaryTextInjectionAdversarialSuite (4) — 33 tests. No production, fixture, build, hook or marker file touched; fixtures changed only in temporary copies.
- Commands: `sbt tests/test` refused (no allowlist row; test-engineer's per-project slot excludes `tests`; G7 row inactive); used allowlisted `sbt Test/compile` and `sbt test` (378 s, all projects Failed 0, tests 178) — disclosed deviation; `sbt check` once (task b88zj58c3).
- `sbt check` (observed in b88zj58c3.output): core 621, entityResolution 464, hadoop 85, sparkAnalytics 263, storage 395, tests 178 (+33); rag/root/services 0; all Failed 0 Errors 0; markTestRun suites=162, passed=2006, skipped=0; `[success] Total time: 10291 s (02:51:31.0), completed Nov 21, 2026, 3:07:45 AM`; `[exited with code 0]`. Per-suite "+" lines not individually located in the ~5.4M-line output; totals rest on 178 = 145 + 33 with Failed 0.
- Adversarial results (all PASS, no production defect): corrupt/missing/extra parts and `_SUCCESS` refused with the named check and zero writes; `_parts.json` or a Parquet part changed after verification → Corrupt at read, 0 written for that store, honest rerun recovers; duplicate `~id`, dangling endpoint, invalid stable ID, CSV/Parquet disagreements refused (neptune-graph-ids, neptune-row-contract, graph-id-parity); reruns: same-ledger skip with 0 writes, fresh-ledger all present (1258/1302/31/249), second publication and foreign owner rows refused with 0 writes; graph injection payloads stay data and are refused at parameter binding; vectors: different text hash Conflict, corrupted floats (1-ULP, swap, NaN, truncation) refused at runtime read and load probe, RealModelsOnly refuses synthetic and missing modelVersion; summary-text injection payloads round-trip as data, metadata only from IDs, textS3Key not filterable.
- Observations (not failing tests): U+2028/U+2029/U+0085 left raw inside JSON-quoted fact lines (a reader splitting on Unicode line terminators could see a forged break — rag-engineer/storage-engineer to judge in Phase 9); `EmbeddingPolicy.isSynthetic` is a case-sensitive label prefix check; tests JVM DEBUG logging (~5.4M lines; this check took 10291 s vs 378 s for `sbt test`) — logging deferred by the human.

## localPipelineDev
- HE-P7-01's `sbt localPipelineDev` was refused before any job ran because the earlier complete attempt-1 output already existed. Per the human: no rerun; attempt-1 left untouched. The hadoop H1 classpath change (snappy-java excluded) is verified only by the hadoop tests inside `sbt check` (85 passed), NOT by a localPipelineDev run.
