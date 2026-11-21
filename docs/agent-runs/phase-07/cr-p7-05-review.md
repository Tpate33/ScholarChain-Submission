# CR-P7-05 (code-reviewer): review of SE-P7-04 — orchestrator-condensed record

The full report is in the session transcript.

- VERDICT: APPROVE. No critical or major finding. STATUS: COMPLETE. Read-only (Read, Grep, Glob); test evidence is the human-observed `sbt check` (exit 0, 129 suites, 1711 passed, storage 137, 2026-11-20 14:07:44 CST).

## Verified
- m1: `LoadGate.proceed` is the only writer call and always goes through `refusal` (LoadVerification.scala:729-735); `acceptedFor` = `refusal(...).isEmpty` (:292); `DeferredCheck.appliesTo` is an exhaustive match with no default (:124-129) and `-Werror` (build.sbt:255) forces mapping of any new store; `passedChecks` only via the `private[load]` constructor, production passes `Set.empty` (:363, :609); fixture refused per store with zero writes (LoadGateSuite:68-105).
- m2: `readVerified` (:205-220) refuses unlisted paths and hashes the same immutable `Bytes` it returns (Bytes.scala:11-16); the bound source is not readable or replaceable; equality ignoring the source cannot be abused.
- Constructors: plain final classes, `private[load]`; access-error assertions with in-package positive controls (LoadPackageControlSuite:40-59).
- Nits n1, n2, n4, n5 as claimed.
- Codec: ParquetFooters reads footer metadata only, `NonFatal` → `Corrupt` (ParquetFooters.scala:48-70); {SNAPPY} asserted per file and union over 29 parts (ParquetFooterSuite:31-44); Spark citations confirmed, no compression setting in Spark main.
- Boundary hygiene: no SDK imports; Parquet only in parquet/ParquetFooters.scala, Optional (build.sbt:421-424); no foreign `scholarchain.storage.load` package.

## Findings (storage-engineer)
- **F1 (minor):** no deferred check for summary evidence. s3-vectors maps to {ParquetRows, RowContracts} only (:128); core `AnalysisValidation.checkSummaryInputs` (core AnalysisValidation.scala:1102) and `StructureValidation.checkEntitySummaries` (StructureValidation.scala:543) are not represented; Spark main never calls them. Failure: entity-summary-inputs citing a `gedge:` absent from graph-edges, or summary-inputs citing a missing path ID, would pass to vectors once ParquetRows and RowContracts are performed. Impact: provenance/grounding. Entry condition before any deferred check is marked performed.
- **F2 (minor):** `LoadPermit.run` is public (:239), so `permit.run.readVerified(...)` reads another store's items or non-item files; `VerificationReport.verified` also public. Byte integrity holds; the "reads only that store's items" claim is not enforced.
- **F3 (nit):** no test for a valid trailing `PAR1` with zero or out-of-range footer length.
- **F4 (nit):** a hand-built report with no `verified` and no rejections is tagged `RunManifestFile` (:691-699).
- **F5 (nit):** `Mirror`/`copy` assertions match broad words (LoadGateSuite:236-249).

## Unverified
- Whether parquet-mr 1.13.1 `ParquetFileReader.open(InputFile)` reads beyond the footer, and whether `ParquetReadOptions.builder().build()` creates a Hadoop `Configuration` (loading `core-site.xml` from the classpath).
- A crafted Thrift footer could raise `OutOfMemoryError` or `StackOverflowError`, which escape `NonFatal`.
- Spark's default Parquet codec (footers confirm SNAPPY for the fixtures only).
- Fixture immutability rests on FixtureIntegritySuite; n6 still open.
- Other core cross-record checks for DynamoDB analysis records (checkWindowStructures, checkTemporal, checkPathLengthEstimates, metrics vs edges) are not in the deferred-check list; scope question.

## Risk triage T1-T6 (corrections)
- T1 confirmed, plus: FIFO/device reachable only through the race (`resolveNoFollow` refuses non-regular files, LocalRunSource.scala:134); `NOFOLLOW_LINKS` covers only the last component (:148), so a swapped intermediate directory is followed; reads are bounded by `maxTotalBytes` (:143), and a manifest-bytes+1 bound needs a `RunSource.read` port change (Sources.scala:28).
- T2 confirmed; correction: `Publications.check` already compares only `partManifestSha256` (:313); the false-Conflict risk is in the writer's putIfAbsent comparison.
- T3 confirmed; peak memory per file about twice its size.
- T4 confirmed: Recording.scala:23-26, :61, :85, :116; rag reaches storage at Compile (build.sbt:430).
- T5, T6 confirmed.

## Routing (orchestrator)
F1 and F2 become entry conditions of storage-engineer's WP4 work order, with T1 (including the reviewer's three details) and T4; F3-F5 fixed where cheap; the Thrift fatal-error concern addressed by bounding the declared footer length against the file size and a configured maximum before parsing. The other core cross-record checks for DynamoDB records are assigned to WP5's row-contracts check (call the existing core validators; no core change). WP4 starts after BE-P7-04's build completes (builds sequential).
