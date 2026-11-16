# Phase 06 work order FE-P6-02: active form of the `sbt erDecisionGroupingDev` allowlist entry (verbatim)

Received by the orchestrator on 2026-11-16, after CR-P6-12 (APPROVE). The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
I choose allow mode for the exact standalone `sbt erDecisionGroupingDev`
command, scoped to entity-resolution-engineer only.

Have factory-engineer prepare the active form of the reviewed entry.
Update the matching docs row, checksum, and state-dependent probe
expectations. Preserve all held-out restrictions and the rest of the
permission scope. Accept the cosmetic tab difference and carry forward
the non-blocking review nits.

Present the final diff, privilege change, validation results, and exact
commit scope for my ratification. If the repository requires a separate
commit for the inactive form, cite the rule. Do not use the new permission
before I ratify the active diff and restart the session.
```

## Orchestrator notes (2026-11-16)

- Carried forward (human decision): the probes.tsv:683 trailing-TAB difference (accepted), and the CR-P6-12 nits (report comment-line count 7 vs 10; justification omits compile output and the streams log; two optional probes).
- The guard reads `bash-allowlist.tsv` on every Bash call, so once the active row is written it matches in this session before ratification and restart. Control until then is procedural: the orchestrator dispatches no entity-resolution-engineer work and runs nothing that uses the row. No entity-resolution-engineer agent is running.
