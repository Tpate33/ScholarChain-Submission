# Human decisions, 2026-11-20 ~20:50-20:55 CST: dependency remediation, disk, and records CR-P7-15 / DOC-P7-01

## Human statements (verbatim)
> For D3 and R1–R3, authorize bounded local remediation, subject to verifying the primary advisories and compatibility first:
> - Keep zstd-jni excluded from the affected scopes.
> - Remove unnecessary codec dependencies from the affected test scopes; use the verified patched snappy-java version where required.
> - Switch Spark's test codec to Snappy, preserving meaningful compression and pipeline coverage.
> - Verify the proposed Netty version addresses the applicable advisories, then pin compatible Netty modules consistently in the affected scopes.
> - Add checks for the resolved dependency versions and exclusions, and obtain code review.
>
> This authorizes remediation, not acceptance of unresolved vulnerabilities. If verification contradicts the proposed versions or requires broader changes, report the specific issue before expanding scope.
>
> Continue documentation and review now. Wait for my disk-space result before another build, then complete WP8 and the final local review. No deletions, cloud operations, spending, deployment, commits, or hook changes.

> Current disk check at 8:53 PM shows 13 GiB available and 93% used. The earlier 2.7 GiB report is stale. Proceed with the authorized bounded remediation and remaining local closeout, running builds sequentially. Do not delete anything.

## Applied
- DU-P7-04 (dependency-updater, read-only, no Bash) verifies Netty, snappy-java, unnecessary codecs, Spark test codec keys, and produces the exact change list before any build change.
- Builds resume sequentially.

## CR-P7-15 (code-reviewer): VERDICT APPROVE (re-check of SE-P7-12)
- m1: 46-byte literal decoded by hand (header + 3f800000, c0200000, 3e000000, 00000000); T6 checks length, header, preimage equality incl. -0f, pinned SHA-256 for literal and `VectorDigest.of`; `of` hashes exactly `preimage` (Vectors.scala:139-140); `preimage` is `private[storage]`; near-copy gone.
- m2: `VectorReader.readOnly` removed; `RuntimeStores.apply` always wraps with `verified` (RuntimeStores.scala:124); no other vector-reader facade in main.
- n1 fixed; n2 comment accurate (chunk ID derives from textSha256 via core `ChunkId.ofTextHash`; S3 checksums depend on the future adapter sending ChecksumSHA256).
- Nits: N1 the outside-package compile test catches only the name `readOnly`; N2 comment could state that the key commits to the expected hash.
- Risk (Phase 8/9): any raw VectorStore can be passed where a VectorReader is expected; only RuntimeStores guarantees verification — handoff item.

## DOC-P7-01 (documentation-engineer, no build): WP10 draft COMPLETE
- Created docs/storage/README.md (architecture, gate and nine deferred checks with per-store mapping, ledger/ownership, idempotency, provenance, schemas for all four stores, vector integrity design and limits, W6 cleanup proposal (design only), Phase 7 status table and counts, how to run locally, threats to validity) and docs/storage/phase-09-11-handoff.md (RuntimeStores from OpeningLoad.run or RuntimeStores.apply, read-only facades, query patterns, 9 limitations). README.md untouched.
- Unverified claims flagged by the author (own code reading): (1) run `_run-manifest.json` and `_parts.json` are not copied to S3 (ObjectLoad writes only load-manifest items); (2) `load-report.json` layout key exists but OpeningLoad returns its report in memory only; (3) **classpath question: rag/services/tests may be unable to call `OpeningLoad.run`** — Parquet is Optional scope, `OpeningLoadConfig` takes `scholarchain.storage.parquet.ParquetReadBounds`, and RagSourceImports forbids `scholarchain.storage.parquet` in rag sources. Agent-reported counts not cross-checked: 12 bulk-load requests, 3617-byte largest item. All "locally verified" entries rest on the 8:43:36 PM `sbt check`.
- Manual actions requested: human replay of `sbt storage/test` and `sbt check` for the "How to run locally" record; optional T6 `shasum` check; orchestrator updates requirements-checklist.md; route items (1)-(3) to storage-engineer/build-engineer.
- Routing: items (1)-(3) → SE-P7-13 (storage-engineer) now; docs reviewed in WP9.
