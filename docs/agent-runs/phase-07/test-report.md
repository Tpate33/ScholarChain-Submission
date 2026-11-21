# Phase 7 test report

## Definition of done (latest, human-reviewed)
`sbt check` (TE-P7-01, task b88zj58c3), marker written_at_utc=2026-11-21T09:07:45.171833Z, reviewed by the human:
- `[success] Total time: 10291 s (02:51:31.0), completed Nov 21, 2026, 3:07:45 AM`; `[exited with code 0]`.
- Per project: core 621, entityResolution 464, hadoop 85, sparkAnalytics 263, storage 395, tests 178; rag, root, services 0 (no tests). Failed 0, Errors 0, Skipped 0. markTestRun: suites=162, passed=2006.
- Phase 7 additions visible in the per-project counts: storage now 395 (new module tests, Phase 7); tests 145 → 178 (+33 adversarial, TE-P7-01); sparkAnalytics 259 → 263 (+4, SP-P7-01).

## Test-engineer (WP8, TE-P7-01): 33 adversarial cross-module tests — all PASS, no production defect
| Suite | Tests | Covers |
|---|---|---|
| PartIntegrityAdversarialSuite | 9 | truncated/flipped/deleted/extra parts, extra load manifest, missing `_SUCCESS`, `_parts.json` and Parquet part changed after verification (L1) |
| GraphIdConsistencyAdversarialSuite | 6 | duplicate `~id`, dangling endpoint, invalid stable ID, CSV/Parquet disagreements, injection in CSV only (G-ID) |
| LoadIdempotencyAdversarialSuite | 5 | full load, same-ledger rerun (0 writes), fresh-ledger rerun (all present; ledger 9 rows), second publication and foreign owners refused (L2) |
| GraphInjectionAdversarialSuite | 2 | openCypher payloads stay data; refused at parameter binding (N5) |
| VectorAdversarialSuite | 7 | different text hash Conflict; corrupted floats refused at read and probe; RealModelsOnly refuses synthetic/missing modelVersion (V2, integrity) |
| SummaryTextInjectionAdversarialSuite | 4 | prompt-injection-shaped text round-trips as data; metadata only from IDs; textS3Key not filterable (V3) |
- Per-suite "+" lines were not individually located in the ~5.4M-line output; totals rest on tests 178 = 145 + 33 with Failed 0.
- Deviation: `sbt tests/test` has no allowlist row for test-engineer; allowlisted `sbt Test/compile`, `sbt test` (378 s, all Failed 0) and `sbt check` used instead.

## Other runs
- `sbt sparkIT` (SP-P7-02): PASS, 14 tests in DevArchiveGraphFoundationSuite, exit 0, 2026-11-20 11:34:31 PM; result files copied verbatim in cr-p7-19-and-sp-p7-02.md. Local client mode only.
- `sbt packageJobs` (BE-P7-07): sparkJobAssemblyCheck and hadoopJobAssemblyCheck PASS (no forbidden prefixes).
- `sbt localPipelineDev` (HE-P7-01): **NOT run** — refused before any job because the earlier complete attempt-1 output already existed; per the human, not rerun and attempt-1 untouched. Not verified.
- `sbt sparkSubmitLocal`: **NOT run** — requires the human's commit of the code scope.
- `sbt storageIT`: not run (row inactive, B10); no live AWS.

## Known test gaps
- No test covers a LinkageError after counted data writes (CR-P7-18 nit). Classpath failure paths tested only with simulated failures.
- Second-run scenarios simulated (no second analysis-run fixture).
- Possible vacuous assertion in SummaryTextInjectionAdversarialSuite (CR-P7-20 n1).
