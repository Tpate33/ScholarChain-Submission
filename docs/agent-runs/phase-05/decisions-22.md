# Phase 5 decisions 22 (human instruction on the FE-P5-07 draft; orchestrator transcription)

Recorded by the orchestrator, 2026-11-14. Not a human-gate record and not a ratification. FE-P5-07 stays in draft; nothing is applied; neither held-out task runs.

## Human text (verbatim)

```text
Keep FE-P5-07 in draft. Confirm that both held-out runs are human-only and that the pre-activation runMain fallback is removed. Document that R6 protects evaluation output and that pipeline candidates and summaries remain agent-readable.

Give me the exact Terminal command to archive FE-P5-07, and confirm whether it supersedes FE-P5-06 in full or must be applied as a delta. Given the malformed hunk headers in FE-P5-06, provide a single valid patch against the current tree and report whether git apply --check passes. Report the expected validator counts separately from any checks actually run.

Pause for my ratification. Do not apply the amendment, commit, push, or run either held-out task.
```

## Routing

| Order | Owner | Scope |
|---|---|---|
| FE-P5-08 | factory-engineer | consolidated FE-P5-07: one standalone amendment text superseding FE-P5-06 for application, one patch against the current tree (HEAD 36ffcf9 working-tree state of the constitutional files), the full post-amendment files, and the R6 coverage statement; written only to the session scratchpad, never to the repository |

`git apply --check` is not on any agent or orchestrator allowlist row, so no agent can run it; the human runs it in a Terminal.
