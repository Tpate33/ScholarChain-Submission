# Phase 8 human acceptance — local scope

Date: 2026-11-21
Decision: Accept the local development scope only.

Reviewed the reported final code-review approvals, test results,
marker verification, changed-file list, and outstanding requirements.
This does not claim a personal line-by-line source review.

Final verification:
- Completed 2026-11-21 at 5:02:18 PM CST.
- Marker: 2026-11-21T23:02:18.658703Z.
- 169 suites; 2080 passed; 0 failed; 0 errored; 1 skipped; exit 0.
- The researcher-to-community neighbour check was skipped because
  the local community summaries contain no researcher IDs.
- Earlier marker reviews are documented in test-report.md.
- No manual implementation changes made during this acceptance.

Retrieval smoke tests cover 249 existing local items using a
non-production stand-in embedder. They verify plumbing only,
not embedding quality, retrieval quality, or production readiness.

Outstanding:
- Real embedding model and access.
- Required 10000-vector corpus and bounded AWS ingestion.
- No instructor approval for a smaller corpus.
- Live AWS adapters, infrastructure, IAM, and operational evidence.
- Phase 7 live AWS requirements.
- Deferred findings recorded in review-report.md.

This acceptance does not waive those requirements or authorize
paid calls, AWS operations, deletions, or hook changes.
