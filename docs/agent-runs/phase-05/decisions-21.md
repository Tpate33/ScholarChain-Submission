# Phase 5 decisions 21 (human decisions after CR-P5-06; orchestrator transcription)

Recorded by the orchestrator, 2026-11-14. Not a human-gate record and not a ratification. The FE-P5-06 files remain unapplied; the revision (FE-P5-07) stays in draft pending the human's approval; any held-out run still needs separate approval.

## Human text (verbatim)

```text
Record BE-6 as an accepted limitation for Phase 5. Document the Git configuration and attributes checks I should do in my Terminal before the held-out run. Update D3(b) to describe what the guard actually checks and list the remaining gaps. Do not expand the guard in this round.

Revise FE-P5-06 so both the held-out pipeline and the held-out evaluation are human-only and run as plain batch sbt commands in my regular Terminal. Remove the proposed active `ask` row for `sbt localPipelineHeldout` and reconcile the corresponding settings, documentation, probes, and expected counts. Preserve the dev commands.

Keep the datasets/downloads exception and the current .DS_Store refusal. Leave the duplicate-source check in Hadoop and defer the broader core validation change.

Show me the exact amendment delta and the updated validation counts, then pause for my approval. Do not apply the amendment, commit, push, or run anything on the held-out split.
```

## Effect

| Item | Decision |
|---|---|
| BE-6 (CR-P5-06) | accepted limitation for Phase 5; no guard change this round; human pre-run Git config/attributes checks documented |
| D3(b) text | rewritten to describe the actual guard (index flags, status incl. ignored with exact allow set, committed root `.gitignore` for both held-out tasks, `GIT_*` stripped, fsmonitor/untracked cache off, 30 s bound, fail closed) and to list remaining gaps |
| `sbt localPipelineHeldout` | human-only, plain batch sbt in the human's regular Terminal; proposed active `ask` row removed; settings, docs, probes, expected counts reconciled |
| `sbt goldSetEvalHeldout` | human-only (unchanged from D15(c)); plain batch sbt in the regular Terminal |
| Dev commands | preserved |
| `datasets/downloads/` allow entry | kept |
| `.DS_Store` refusal | kept |
| HD-1 duplicate-source check | kept in Hadoop |
| Core unique-source validation | deferred |

## Routing

| Order | Owner | Scope |
|---|---|---|
| FE-P5-07 | factory-engineer | draft delta against FE-P5-06 plus updated expected counts; draft only, no file written |
