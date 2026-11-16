# Phase 5 decisions 19 (human ratification instruction for FE-P5-06; orchestrator transcription)

Recorded by the orchestrator, 2026-11-14. This file transcribes the human's message; the human's own ratification record belongs in `human-gate.md` (human-owned). The constitutional files are NOT applied by any agent; the human applies them with all Claude Code sessions closed.

## Human text (verbatim)

```text
I ratify the archived FE-P5-06 amendment. Proceed with the non-constitutional work orders and code review in section 8, including the synthetic check that held-out evaluation output reaches my Terminal without appearing in persisted sbt logs.

After review, report the verdict, changed files, exact sbt check counts, and synthetic log-check result. Then pause so I can apply the nine constitutional changes myself with all Claude Code sessions closed.

Do not apply the constitutional changes, commit, push, or run anything on the held-out split. Held-out work requires my separate approval.
```

## Ratified text

`docs/agent-runs/phase-05/amendment-fe-p5-06.md` (FE-P5-06, supersedes FE-P5-05; builds on FE-P5-03 and FE-P5-04; decisions-16, -17, -18).

## Section 8 step 2 work orders (routed one at a time)

| Order | Owner | Scope |
|---|---|---|
| BE-P5-04 | build-engineer | BE-P5-02 sections 2 and 9; L1 `.gitignore` line; ignored-labels check; D3(b) dirty-tree refusal; held-out child output direct to the terminal; synthetic persisted-log check |
| DE-P5-04 | dataset-engineer | D13 option D snapshot tokens in the dataset manifest |
| HD-P5-04 | hadoop-engineer | copy D13 tokens into the run manifests / pipeline; report new run IDs |
| ER-P5-17 | entity-resolution-engineer | `labels file=... sha256=...` print line (L1) |
| CR-P5-0x | code-reviewer | review of all of the above and the uncommitted Phase 5 diff |

Not in scope: applying constitutional files, commits, pushes, any held-out run.
