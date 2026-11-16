# Phase 5 held-out evaluation run 02 (human run; orchestrator observation record)

Assembled by the orchestrator on 2026-11-15 at the human's request ("Please document that disclosure"). This file records observations, not a human-gate record. The human ran `sbt goldSetEvalHeldout` in a regular Terminal (D15(c), decisions-21). No agent and not the orchestrator ran it, read its output, read the exported log, or read the held-out labels. No held-out metric, confusion matrix, individual label or false-positive/false-negative listing was given to this session or appears here. Nothing was evaluated, committed or pushed by the orchestrator.

## Why there is a second run (disclosure)

- Run 01 (`heldout-eval-01.md`) exited `[success]`. Its Terminal was closed before any metric was recorded, so its output is lost. No copy exists in the repository or under `target/`: the build sends the held-out evaluation's output only to the Terminal (`project/LocalRuns.scala` `outputRoute`, `docs/agents.md` "Held-out runs"), and `GoldSetEvalMain` writes no file ("Writes nothing", `GoldSetEvalMain.scala:78`).
- Without recorded metrics, the README Phase 5 evaluation requirements (`README.md:1231-1237`, at `d23d307`) and the gate item "Precision and recall are reported separately" (`:1246`) could not be met. The orchestrator recommended one rerun on unchanged inputs; the human chose it ("A", 2026-11-15).
- Why a rerun on unchanged inputs is not a new evaluation: `GoldSetEvaluation` is "a pure function of the review-queue rows and the validated human labels" (`GoldSetEvaluation.scala:291`). With the same committed membership, freeze record, config and queue, and the same labels file, it produces the same report.
- D3(c): no policy, feature, threshold or code change was made between run 01 and run 02 (HEAD unchanged at `f952eff`; human statement below). The human had seen run 01's terminal output before run 02.

## Human statements (2026-11-15, verbatim excerpts)

```text
The second Phase 5 held-out evaluation completed successfully with exit=0.

I completed the required pre-run checklist and verified that all four input checksums matched the original evaluation. Git HEAD remained f952eff.

I exported the full Terminal output to a private text file outside the repository:
~/Desktop/ScholarChain-Phase5-Heldout-Evaluation-2026-11-15.txt

The first run's output was lost; this second run used unchanged inputs. Please document that disclosure.
```

## Run 02 facts

| Item | Value | Source |
|---|---|---|
| Command | `time sbt -Dsbt.supershell=false goldSetEvalHeldout; echo "exit=$?"` | orchestrator instructions, Step 4 |
| Exit status | `exit=0` | human statement |
| HEAD | `f952eff` | human statement |
| Inputs | "all four input checksums matched the original evaluation" | human statement; the values on record are in `heldout-eval-01.md` (membership `22779ca0…f8f4`, config `c7862073…a227`, queue `4f296b19…8102`, labels `0e267648…b692a`) |
| Private output | `~/Desktop/ScholarChain-Phase5-Heldout-Evaluation-2026-11-15.txt`, outside the repository | human statement; not read by the orchestrator |
| GoldSetEvalMain exit | 0 implied: sbt exits 0 for this task only on the `Some(0)` branch (`project/LocalRuns.scala:931-934`); exit 3 (below the 200-review minimum) and exit 2 (refusal) fail the task | code reading |

## Not recovered or not provided (not invented)

- The run's `git_head=... git_worktree=...`, launch-check and D3(b) clean-tree lines.
- `GoldSetEvalMain`'s `config file=... sha256=...`, `manifest sha256=... freeze=...` and `labels file=... sha256=...` lines. These are in the private export; the human may copy them into `human-gate.md`.
- The freeze-record line and the git status check of Step 2, individually (covered by the human's summary statement).
- The pre-run checklist output (items 1-13); completion rests on the human statement.
- A sha256 of the private export file.
- The evaluator prints no run ID of its own. It prints `membership run=<generatingRunId>`, which should equal the pipeline run `run:7ae22ee9…159a` (`heldout-run-01.md`).

## Next (human)

The human copies aggregates only (Option A, after acceptance) from the private export into `human-gate.md` sections 5 and 6, then commits the evidence and records `commit.txt`. Individual pairs, member IDs and example listings stay out of the repository.
