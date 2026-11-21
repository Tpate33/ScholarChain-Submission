# CR-P7-03 (code-reviewer): storage increment 2 (SE-P7-03) — orchestrator-condensed record

The full report is in the session transcript.

- VERDICT: APPROVE. No critical or major defect.
- STATUS: COMPLETE. Read-only: Read, Grep and Glob, no commands. The `sbt check` evidence is the human's observation, recorded in se-p7-03-report.md.
- Read against the core contracts: PartManifest, PartManifestV2, StoreLoad, StructureValidation, LoadRoles, NeptuneCsv, RunId, RunManifest. Fixture CSVs and SHA256SUMS were also read.

## What the reviewer confirmed
- **Gate:** `VerifiedRun` has a `private[load]` constructor. `LoadGate.proceed` writes only when the run is verified and there are no rejections.
- **Integrity:** every part's sha256 and byte count are checked. Rows are checked for jsonl and CSV; Parquet row counts are deferred and listed. The exact file set is enforced. Load items must equal their part entries. Path traversal is refused, and duplicate item paths are refused.
- **R1:** the publication key is correct. A tampered ledger row is refused as Corrupt, and an unreadable ledger fails closed.
- **LocalRunSource:** symlinks are refused and never followed; bounds have no defaults in main code.
- **CsvRecords:** RFC 4180 handling is correct. The Neptune headers match the fixture headers byte for byte.
- **Tests:** each refusal test traced fails without its behaviour. Fixtures are only read. Determinism is tested.
- **Boundary hygiene:** no SDK, Parquet or Hadoop imports in storage main; no hardcoded thresholds; typed errors.
- **Ownership:** this increment touched only modules/storage/**.

## Findings (all storage-engineer)
- **m1 (minor):** `accepted` and `LoadGate.proceed` ignore the deferred checks (row-contracts, graph-ids, evidence-trace, parquet-rows). A run with a dangling `~to`, a duplicate `~id` or unresolved evidence would pass the gate. Fix: refuse while any deferred check applying to the target store remains (or rename `accepted` to mark it structural only), and add a test.
- **m2 (minor):** `VerifiedRun` carries no part bytes and no binding to its source. A writer could upload bytes that were never verified, for example from another source or after a file is replaced. Fix: writers re-hash every part against the verified manifest before upload, or `VerifiedRun` carries the bytes or a re-checking handle. Correct the Scaladoc.
- **n1:** `_run-manifest.json` is read twice.
- **n2:** `read` does not enforce maxDepth or maxFiles, and can read unlisted paths (the port contract says more than the code does).
- **n3:** `private[load]` can be reached through a split package in another module (convention only).
- **n4:** the missing-`_SUCCESS` test does not prove nothing else was read.
- **n5:** no BOM, very-long-field or trailing-blank-line CSV tests.
- **n6:** the fixture SHA256SUMS.txt is not anchored outside the fixtures.
  - Orchestrator note: the load-manifest hashes are anchored externally by the archive's own `~/ScholarChain-archive/phase-06/local-evidence/SHA256SUMS.txt`. The human's fixture-copy script wrote the fixture SHA256SUMS.txt from those archive lines and verified the copy against them (human-fixture-copy-2026-11-19.md).

## Residual risks for the write increments (storage-engineer)
1. **R1 concurrency:** two loaders can both see "unrecorded". The writer must putIfAbsent the publication row before any data write. Because `firstLoadId` is part of the row body, two loads for different stores give a false Conflict; handle it by comparing `partManifestSha256`.
2. **Check-then-open race:** a swapped intermediate directory, FIFO or hard link is not caught by the path checks.
3. **Memory:** up to maxFiles × maxTotalBytes under concurrent growth.
4. **Test helpers:** public mutable `WriteCounts`.

## Unverified
- Whether a Scala 3 Mirror or `fromProduct` can reach the private constructor. Suggested check: a negative compile test.
- That port/Sources.scala changed only in Scaladoc.
- That sbt copies test resources byte-exactly.

## Routing (orchestrator)
m1 and m2 (with the Mirror negative compile test) become entry conditions for storage-engineer's next increment, before any writer exists. The nits are fixed where cheap in the same work order. Residual risk 1 is carried into the write-increment work order.
