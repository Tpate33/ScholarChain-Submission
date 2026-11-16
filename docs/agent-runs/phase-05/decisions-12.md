# Phase 5 human report: dataset checkpoint and dblp download (transcribed by the orchestrator)

Received 2026-11-12. This is a verbatim transcription, not a human-gate record. The orchestrator did not observe these commands or their output.

```text
I committed the dataset checkpoint as 5e324bd, downloaded the pinned
DBLP release dblp-2026-10-03 on Nov 12, and ran
bash datasets/scripts/fetch-dblp.sh --verify in my regular Terminal.

Verification passed: 110 manifest checks, 0 failures, and the full
archive passed MD5, SHA-256 sidecar, and gzip checks.

Observed size: 1107081001 bytes.
SHA-256: 20e45961bec5610dc07b8e932ccd27a2387534cfa12c92a24289fe873aebe969

Route dataset-engineer to cross-check these values against the local
archive and sidecar and record the retrieval date, size, and SHA-256
in the manifest. Then show me the exact command to extract the selected
DBLP records and say whether it makes any network requests.
```

## Routing

| Item | Work order |
|---|---|
| Cross-check size and SHA-256 against the local archive and its sidecar; record retrieval date (2026-11-12), size and SHA-256 in the manifest | DS-P5-07 (dataset-engineer) |
| Exact extraction command for the selected dblp records, and whether it makes any network request | DS-P5-07 (report only; extraction not run) |
| RV-P5-10 MINOR-1 decision (persist folded arXiv agreements before gold labeling, or document) | still pending with the human |
