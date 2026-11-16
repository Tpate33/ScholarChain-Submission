# Phase 5 decisions 18 (human choice on the F-05-1 probe issue; orchestrator transcription)

Recorded by the orchestrator, 2026-11-14. Not a human-gate record and not a ratification. The amendment stays in draft; any held-out run waits for separate approval.

## Human text (verbatim)

```text
I choose option (c). Revise the combined FE-P5-05 draft to replace probe 101 with two validator checks using a temporary fixture. Verify that the service-engineer's `sbt clean check` is allowed when the held-out output directory is absent and refused with `heldout-run-protected` when it exists.

Preserve the decisions already recorded, including human-only held-out evaluation under D15(c), direct-to-Terminal output, R6, and `ask` for the held-out pipeline. Give me the revised draft's file path and state the expected table-probe count and fixture-check count separately.

Keep the amendment in draft pending my ratification. Wait for separate approval before any held-out run.
```

## Effect

- F-05-1 resolved by option (c): `.claude/hooks/probes.tsv:101` is replaced by two fixture checks in `.claude/hooks/validate-agent-files.sh` using a temporary fixture root (directory absent: ALLOW; directory present: BLOCK `heldout-run-protected`). The validator becomes a ninth file in the combined amendment.
- All decisions in decisions-16 and decisions-17 are preserved unchanged.
- Routing: FE-P5-06 (factory-engineer), revised combined draft, draft only.
