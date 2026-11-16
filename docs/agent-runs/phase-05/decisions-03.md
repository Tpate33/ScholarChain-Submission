# Phase 5 human instructions after ER-P5-04 / DS-P5-02 (transcribed by the orchestrator)

Received 2026-11-05. This is a verbatim transcription, not a human-gate record.

```text
Proceed with acquisition preparation only:

- Keep the existing pinned October release. Do not re-pin merely to improve
  coverage; record missing records honestly.
- Route dataset-engineer to remove the network fallback, make extraction
  offline-only, and record the source byte ranges needed to reproduce excerpts.
- Verify the extractor on a small local fixture before the full download.
- Have code-reviewer inspect the fetch/extraction changes and confirm the
  exact download command, output paths, checksum verification, and failure behavior.
- Present the reviewed command to me afterward. This does not authorize
  executing the full download yet.

Proceed with the proposed fixed membership manifest and policy-independent
pair IDs. Keep held-out membership unchanged across policy versions.

Policy decisions remain pending. Show the complete definitions of labeling
options A, B, and C together with your recommended precedence table.
Do not implement those policy choices until I select them.

No optional core-contract expansion unless existing fields cannot preserve
the required disagreement evidence. No commits, pushes, AWS operations,
or constitutional activation.
```

## Status after this instruction

| Item | Status |
|---|---|
| dblp release | stays pinned at dblp-2026-10-03; no re-pin |
| Full download | NOT authorized; review packet only |
| DS-P5-03 offline-only extraction, byte ranges, fixture test | authorized (dataset-engineer) |
| Code review of fetch and extraction changes | authorized (code-reviewer) |
| Fixed membership manifest and policy-independent pair IDs | authorized (entity-resolution-engineer, ER-P5-05) |
| Precedence (P-1, P-2, P-6, OpenAlex author), labeling target A/B/C, er-policy.v3 | pending human selection; not implemented |
| Optional core field for overridden disagreements | not authorized unless existing fields cannot preserve the evidence |

## Routing

DS-P5-03 -> ER-P5-05 -> RV-P5-02 (code-reviewer: DS-P5-01/03 fetch and extraction changes), run
sequentially because of the per-session audit lock.
