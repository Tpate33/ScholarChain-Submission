# Phase 5 human decisions after DS-P5-07 and RV-P5-10 (transcribed by the orchestrator)

Received 2026-11-12. This is a verbatim transcription, not a human-gate record.

```text
Use the existing extraction command with its bounded Crossref/OpenAlex
requests. I'll run it in my Terminal. If the known Crossref lookup fails,
report that separately from DBLP extraction and verify the saved excerpts
before deciding whether anything needs a retry.

Keep the manual size and SHA-256 checks. In validation_commands, move
the parenthetical expectations into adjacent notes so both commands are
copy-pasteable. Show me the final manifest diff for the manifest-only commit.

Document the folded-arXiv evidence discrepancy before labeling. Explain
that auditors can reproduce the normalized agreement from the original
endpoint identifiers. Defer the review-row schema change.
```

## Routing

| Item | Work order |
|---|---|
| Extraction: `datasets/scripts/fetch-samples.sh --sample datasets/er-candidates`, run by the human; afterwards report the Crossref lookup separately from dblp extraction and verify saved excerpts before any retry decision | human run; then dataset-engineer verification (`--verify` with the release) |
| Manifest `validation_commands`: expectations moved to adjacent notes so the `shasum` and `wc -c` commands are copy-pasteable; manual checks kept | DS-P5-08 (dataset-engineer); orchestrator shows the final `git diff` of the manifest |
| RV-P5-10 MINOR-1: document the folded-arXiv evidence discrepancy and how auditors reproduce the normalized agreement from the original endpoint identifiers; review-row schema change deferred | ER-P5-13 (entity-resolution-engineer), documentation only |
