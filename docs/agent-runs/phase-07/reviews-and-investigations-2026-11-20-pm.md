# SE-P7-06a, FE-P7-01, CR-P7-07 — orchestrator-condensed records (2026-11-20 afternoon)

Full reports are in the session transcript.

## SE-P7-06a (storage-engineer, read-only): fixtures needed for summary-evidence
- Grouping run `run:84dd896f…d7d2` (whole directory, 7 files: `_run-manifest.json`, `_parts.json`, `_SUCCESS`, assignments/researcher, assignments/work, authorships, teams parts) and curated Hadoop run `run:2881724b…8baa` (whole directory recommended, ~0.37 MB estimate) under `/Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01/`. Analysis-run files already in fixtures; decision run not needed.
- No saved archive checksum file covers dev-run-01 (local-evidence/SHA256SUMS.txt 2079 lines, attempt-a3/SHA256SUMS.txt 33 lines; no dev-run-01/SHA256SUMS.txt). Grouping parts are covered by the run's own `_parts.json` and by committed docs/agent-runs/phase-06/dev-run-01.md lines 96-100 (orchestrator read and confirmed the five values). Curated files have only Hadoop CRC32 sidecars.
- Also needed: analysis settings, only in committed `modules/spark/src/test/resources/scholarchain/spark/dev-run-01/analysis-dev.conf`.
- Human decision (2026-11-20): copy both runs plus the committed settings file, with evidence limits: grouping parts checked against the committed Phase 6 hashes; curated files byte-identical to the archive at copy time, no historical sha256 claimed; settings byte-identical to git HEAD.

## FE-P7-01 (factory-engineer, read-only): reported guard pass of `cd … && sbt storage/compile`
- The guard as written refuses that string for every identity at the character gate (guard-dangerous.sh:514-517, GATE_CH line 88); no row starts with `cd`. High confidence.
- Explanations not determinable from the repository: (a) Claude Code rewrote/stripped the leading `cd` before hooks (unverified), (d) the agent's report was inaccurate; settings allow rules and in-code fail-open paths ruled out; harness handling of a killed/timed-out hook unverified.
- The hooks keep no command-text log; the session transcript is authoritative.
- Proposals P1 (guard decision log), P2 (probe row), P3 (check hook timeout docs): NOT applied. Human decision: investigation sufficient for now; unresolved explanation tracked; P1-P3 and further investigation deferred; no hook or constitutional change.

## CR-P7-07 (code-reviewer): review of SE-P7-05 (WP4) — VERDICT: APPROVE
- Verified: A1-A6 closed (summary-evidence mapping and validator use; permit surface, no bypass; bounded, link-safe reads; read-only counters; footer bounds, codec refusal; F4, F5). Gate integrity: only LoadVerification marks checks; hand-built VerifiedRuns only in package-`load` test suites. Mapping change s3 graph-ids → graph-id-parity acceptable (caveat: s3 ID uniqueness relies on whole-run rejection). B1/B2, B4 interleavings traced correct by hand, B5, B6, injection, ownership.
- Findings (storage-engineer):
  - m1: several NeptuneRows rule branches untested (non-canonical COAUTHORED_WITH order, self-loop, sourceEvidence rules, MentionId formula, wrong endpoint ID kind, missing runId); GraphLoadSuite:412-428 named for wrong endpoint label but tests only dangling.
  - m2: openCypher write templates (model/GraphWrites.scala:84, :96-97) have no RETURN, so on Neptune they cannot yield AlreadyPresent/Conflict/Refused; Conflict/Refused proven only against the fake. Requirement on the deferred AWS adapter.
  - m3: T1 residual understated: in path-checks mode an intermediate directory swap can also lead the open to a FIFO (LocalRunSource.scala:235-237). Availability only.
  - Nits n1 (SummaryEvidenceCheck untested), n2 (vacuous UTF-8 assertion NeptuneRowsSuite:307-311), n3 (NoCodecs factory use untested), n4 (non-canonical int32 accepted), n5 (re-read bound comment), n6 (bulk-load copy runs under neptune permit; plan should take a permit), n7 (one interleaving tested).
- Residual: multi-run graph — stable IDs repeat across runs, so a second run into the same graph store conflicts on shared vertices; one-graph-per-run vs cleanup not stated (Phase 9-11 handoff item). `fileKey` may be null on some file systems.
- Routing: m1, m3 and nits → storage-engineer's next work order; m2 → requirement on the deferred AWS adapter (tracked outstanding).
