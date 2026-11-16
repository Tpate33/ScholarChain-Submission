# Phase 06 work order ER-P6-08: pin the dev run manifests (CR-P6-09 MAJ-1) (verbatim)

Received by the orchestrator on 2026-11-16, after CR-P6-10 (APPROVE) and the human's commit X. The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Commit X is complete. Carry forward both CR-P6-10 minor findings. Read HEAD and delegate manifest pinning to entity-resolution-engineer. Update only the two Phase 6 dev manifests, recompute the decision run ID first, and update the grouping manifest's dependency and run ID. Verify the chain and return the exact scope for commit Y. Keep the dev command inactive pending ratification. Hold DE-P6-02 changes to core until the pinned dev run is captured so those changes do not invalidate the pin. Preserve Phase 5 acceptance and evidence.
```

## Orchestrator check of HEAD (2026-11-16)

- `git rev-parse HEAD`: `8c1bee042e7dca3119f5116df2150e0bee204598` (commit X, "Phase 6: add reproducible entity-resolution dev pipeline"; parent 426df59).
- `git status --porcelain`: empty (clean tree).
- `git show --name-only` X: 55 files, the full list presented to the human for commit X: 10 modified tracked files, 25 new code and input files, and 20 evidence files under `docs/agent-runs/phase-06/`. `human-gate.md` is not in X.
- Carried forward (human decision): CR-P6-10 minor 1 (untested refusals in the code-scope check) and minor 2 (fixture git isolation reads HOME/XDG git ignore and attributes), owner build-engineer.
- DE-P6-02 is held until the pinned dev run is captured, because any change under `modules/core/**` after X would make the code-scope check refuse at HEAD=Y.
