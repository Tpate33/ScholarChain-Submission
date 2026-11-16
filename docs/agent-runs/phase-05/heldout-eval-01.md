# Phase 5 held-out evaluation run 01 (human run; orchestrator observation record)

Assembled by the orchestrator on 2026-11-15 with the human's approval. This file records observations, not a human-gate record. The human ran `sbt goldSetEvalHeldout` in a regular Terminal (D15(c), decisions-21). No agent and not the orchestrator ran it, read its output, or read the held-out labels. No held-out metric, confusion matrix, individual label or false-positive/false-negative listing was given to this session or appears here. Nothing was evaluated, committed or pushed by the orchestrator.

## Human statements (2026-11-15, verbatim excerpts)

```text
The Phase 5 held-out evaluation completed successfully.

I personally ran `time sbt -Dsbt.supershell=false goldSetEvalHeldout` after the required final checklist.

The output ended with:
`goldSetEvalHeldout: final held-out gold metrics reported to the terminal`
`[success] Total time: 1 s`

The queue was committed in `f952eff`.
```

```text
I confirm that I personally ran the held-out evaluation in regular Terminal after completing the checklist.

Before the evaluation, I exited the Claude Code session using /exit. The Terminal tab may have remained visible, but Claude Code was no longer running.

The evaluation finished successfully, reporting final held-out gold metrics.

The checksums and Git status you verified are correct.
```

```text
Keep held-out metrics, individual labels, and confusion matrices out of the repository.
```

## Exit status

`[success]` (human statement above). sbt prints `[success]` only when the task exits 0; the numeric exit status was not captured.

## Post-run state (human Terminal screenshot, 2026-11-15; values read by the orchestrator)

| Item | Value | Cross-check |
|---|---|---|
| `git rev-parse HEAD` | `f952eff83888b6fb786062de49ae204e056be3e8` | the queue commit named by the human |
| `git status --porcelain=v1 --ignored=matching -- gold` | `!! gold/entity-resolution/heldout-v1/er-gold-labels.jsonl` only | labels ignored and untracked (D5 = L1); tracked `gold/` files unchanged |
| freeze record `er-gold-membership.jsonl.sha256` | `22779ca065cfb78f120e35e107c60f4fea15bb05ee998801c4e864dd2e21f8f4  er-gold-membership.jsonl` | equals the membership sha256 below and `heldout-run-01.md` |
| `er-gold-membership.jsonl` sha256 | `22779ca065cfb78f120e35e107c60f4fea15bb05ee998801c4e864dd2e21f8f4` | frozen in `01f1799` |
| `er-config.conf` sha256 | `c7862073c9d14032353139eb15e926e60e4b993ae176397891033cd12aa3f227` | first recorded here |
| `er-review-queue.jsonl` sha256 | `4f296b19dcfa974a9b8894f5a2a9e17736d3c28978e47aa97efdeb8011be8102` | committed in `f952eff`; first recorded here |
| `er-gold-labels.jsonl` sha256 | `0e267648b153047e0708781e9e335249b2d02bb3b94868acc6592b9bd32b692a` | equals `labels-import-01.md` steps 3 and 5 (labels unchanged since import) |
| held-out pipeline `summary.txt` sha256 (original, `modules/hadoop/target/local-pipeline/er-candidates/heldout/attempt-1/`) | `746ffdc025654de9020e20cfa3cab9c2ae59f51bea0f2f902e67f1b6232b44fe` | pipeline run `run:7ae22ee957b7b55ef7d19d8eb2545913b55ee14388a4aaba7a2442f412d7159a` (`heldout-run-01.md`) |
| archive copy `~/Desktop/ScholarChain-heldout-attempt-1-20261115/summary.txt` sha256 | `746ffdc025654de9020e20cfa3cab9c2ae59f51bea0f2f902e67f1b6232b44fe` | byte-identical to the original (D10(i) archive confirmed) |

These hashes were taken after the evaluation. They show the inputs at HEAD `f952eff` after the run; they do not by themselves prove the state at run time.

## Not recovered (not provided to this session; not invented)

- The evaluation's `git_head=... git_worktree=...` line.
- The evaluation's launch-check line and D3(b) `clean tree in build.sbt project modules tests datasets gold` line.
- The evaluation's `manifest sha256=... freeze=matches` line.
- Any evaluation run ID printed by the task. Whether the task prints one was not checked here.
- The numeric exit status (see Exit status).
- The final pre-run checklist output (items 1-13). Only the rehearsal (`heldout-prerun-rehearsal-01.md`) is on file; completion rests on the human statement.

## Process note

The human states that the Claude Code session was exited with `/exit` before the evaluation, and that only the Terminal tab stayed visible (checklist item 1). Hooks and permission rules do not apply in the human's Terminal, so this rests on the human's statement.

## Next (human)

`human-gate.md` and `commit.txt` (human-only). Per `docs/agents.md`, before acceptance `human-gate.md` holds only the exit status, runId and sha256 values for the evaluation; metrics and listings stay out of the repository (human instruction above).
