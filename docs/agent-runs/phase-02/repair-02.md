# Phase 2 repair-02: F1 ownership-map amendment (constitutional; pending ratification)

Assembled by the orchestrator.

## Routing
1. factory-engineer: proposal only, no files written. Recommended option (a): every `*.sbt` outside `/build.sbt` and `/project/**` -> `UNASSIGNED`.
2. code-reviewer: APPROVE. One minor finding: the proposed docs/agents.md paragraph overstated "every other `*.sbt`", because `.claude/{agents,hooks,commands}/**` stay with factory-engineer (constitutional rows match first). Nits: probes.tsv must end with a newline; there was no probe for `.claude/hooks/x.sbt`.
3. Human decision (2026-10-29): approve option (a) with fix (i). Reword the paragraph to exclude the constitutional rows, preserve the constitutional entries, add the `.claude/hooks/x.sbt` probe, and make sure probes.tsv ends with a newline.
4. factory-engineer: applied the change to `.claude/hooks/lib-ownership.sh`, `.claude/hooks/enforce-ownership.sh`, `docs/agents.md` and `.claude/hooks/probes.tsv`.

## Validation
Observed by the orchestrator in this session:
- `.claude/hooks/validate-agent-files.sh` -> `RESULT: PASS (0 failures)`. Includes `PASS  docs/agent-runs/**/*.sbt -> UNASSIGNED`, `PASS  **/*.sbt -> UNASSIGNED`, `PASS  lib-ownership.sh anchor list matches the ownership table`, and `PASS  docs/agents.md Bash allowlist block matches bash-allowlist.tsv (119 rows, sha256 eacc92bb7765)`. Also `NOTE  shellcheck not installed`.
- `git diff --stat`:
  - enforce-ownership.sh +9
  - lib-ownership.sh +7
  - probes.tsv +23/-1
  - docs/agents.md +4
  - .gitignore 2 (from build-engineer's repair-01)

Observed by factory-engineer only. The orchestrator has no `--probes` row.
- `validate-agent-files.sh --probes` -> 447 table probes run, all PASS, including the 22 `[F1]` probes. 36 fixture probes PASS. `RESULT: PASS (0 failures)`.
- `bash -n` on both changed hooks: exit 0.
- `test -x` on both changed hooks: exit 0.
- `json.tool` on settings.json and .mcp.json: exit 0.
- shellcheck: not installed (exit 127).

## Deviations from the reviewed proposal
- The applied agent had no copy of the reviewed text. It rebuilt the comments, the Basis cells, the block message and the probe set.
  - The ownership rules, their order and the anchors match the reviewed diff.
  - The probe set differs: 22 rows in place of the reviewed 20 plus 1.
- Reviewed cases with no probe in the applied set:
  - BLOCK: `modules/core/project/foo.sbt` (nested), `modules/hadoop/src/main/scala/x.sbt`, `datasets/x.sbt`, `infra/terraform/x.sbt`, `scripts/x.sbt`, `experiments/x.sbt`, `docs/incidents/x.sbt`, and build-engineer writing a root `other.sbt`.
  - ALLOW controls: `modules/core/notes.sbt.md` and the build-engineer `.gitkeep` skeleton.
- Probes added that were not in the reviewed set:
  - BLOCK: `build.sbt` in each remaining module, `modules/core/x.sbt`, and `tests/x/y.sbt` (Edit).
  - ALLOW controls: `project/x/y.sbt` and the orchestrator's `agent-report.md`.
  - The required ALLOW for factory-engineer `.claude/hooks/x.sbt`, and a BLOCK for build-engineer writing it (constitutional protection).
- probes.tsv line 429 (an existing row) lost its trailing TAB, which was an empty optional fifth column. Its verdict is unchanged.

## Completion: the 10 missing reviewed probes
- Human decision (2026-10-29), "Choose Option 2": factory-engineer adds only the 10 missing reviewed probe cases to probes.tsv, exactly as originally proposed. No other change.
- factory-engineer appended the 10 rows verbatim. The required substring "sbt build definition" is present in the unchanged block message.
- Results observed by factory-engineer only:
  - `validate-agent-files.sh --probes` -> `NOTE  457 table probes run`. All 32 `[F1]` probes PASS, including the 10 new ones. All fixture probes PASS. `RESULT: PASS (0 failures)`.
- Results observed by the orchestrator after the change:
  - `validate-agent-files.sh` -> `RESULT: PASS (0 failures)`. Allowlist block unchanged (119 rows, sha256 eacc92bb7765). `NOTE  shellcheck not installed`.
  - `git diff --stat` -> 5 files, +54/-2:
    - enforce-ownership.sh +9
    - lib-ownership.sh +7
    - probes.tsv +33/-1
    - .gitignore 2
    - docs/agents.md +4
  - `verify-tests-ran.sh --check` -> `OK (no uncommitted source change is newer than .claude/.last-test-run)`.
- The final applied probe set has not had a separate code-reviewer pass. The human directed that repair and review cycles stop once validation passed.

Status: applied to the working tree. Not effective until the human ratifies it and restarts the session.
