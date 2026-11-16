# Phase 5 held-out pipeline: pre-run checklist rehearsal and final-pass handoff (01)

Assembled by the orchestrator on 2026-11-15. This file records observations, not a human-gate record. The human ran each command in a regular Terminal and shared screenshots; the orchestrator read them. Both held-out tasks are human-only (D15(c), decisions-21). No agent ran, or will run, either task.

- **Approval (human, 2026-11-15):** "I approve preparing the Phase 5 held-out candidate population. I will run the held-out pipeline myself in regular Terminal after the pre-run checklist passes."
- **HEAD:** commit B `71ff55199c454c7c2393c973955cb6950f4d5392`.
- **Checklist source:** `docs/agents.md`, "Pre-run Terminal checklist" (FE-P5-07).
- **Rehearsal context:** this Claude Code session was open during the rehearsal, so item 1 cannot pass in the rehearsal. The final pass must be run with every Claude Code session on this repository closed.
- **C1:** still NOT RUN (see `post-restart-observations-01.md`).

## Rehearsal results (2026-11-15, 09:37-09:47 local time)

| Item | Result | Observed |
|---|---|---|
| 1 No other writer / sbt server | pass, except for this open session | `ps … \| grep` printed nothing; `ls: project/target/active.json: No such file or directory` |
| 2 Git config and gitlinks | pass | `config-exit=1`, `nonregular-exit=1`, no other lines |
| 3 Attribute and exclude files | pass | `XDG_CONFIG_HOME=unset`; `.git/info/attributes` and `~/.config/git/attributes` absent; no `.gitattributes` tracked; `.git/info/exclude` holds only git's default `#` comment lines |
| 4 Attributes in effect | pass | only the three marker lines |
| 5 Index flags | pass | `nonH-exit=1` |
| 6 Raw bytes equal HEAD | pass | `index equals HEAD`, `work tree bytes equal HEAD`, `.gitignore equals HEAD` |
| 7 `.DS_Store` in scope (listing only; no delete in the rehearsal) | expected findings | `project/.DS_Store`, `project/project/.DS_Store`, `project/project/target/.DS_Store`; `find: gold: No such file or directory` (gold/ does not exist yet), `find-exit=1` |
| 8 Status over scope | pass apart from the known `.DS_Store` files | allow-list filter printed only `!! project/.DS_Store`, `!! project/project/.DS_Store`, `disallowed-exit=0`; all other lines were on the allow list; no modified, staged or untracked path |
| 9 Labels file and run order | pass | labels file absent; `.gitignore:41:/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl`, `check-ignore-exit=0` |
| 10 Launcher / JVM / JDK files | pass | 11 of 12 paths absent. `/opt/homebrew/etc/sbtopts` (930 bytes, Oct 28) is Homebrew's stock template with every option commented out; `active-exit=1` |
| 11 Environment (names only) | pass | only `JAVA_HOME`, accepted if set deliberately |
| 12 Global sbt settings and plugins | pass | the checklist form printed only the `~/.sbt` directory itself (its name matches `*.sbt`); the `-type f` form printed nothing, `find-exit=0` |
| 13 Classpath files (optional) | pass | resource comparison printed only `compare-done` (no `NO-COUNTERPART` line). Not verified: that `find` matched at least one resource file |
| 14 Run | not run | final pass only |

## Final-pass sequence (human, regular Terminal, every Claude Code session closed)

Run these from the repository root, in this order, immediately before the run. Stop at the first unexpected result. Do not open repository folders in Finder between step 7 and step 14.

1. Quit every Claude Code session on this repository, and any IDE, Metals or BSP client. Then:
   `cd /Users/taufeeqpatel/Desktop/ScholarChain && ps -ax -o pid=,command= | grep -E '[x]sbt\.boot|[s]bt-launch|[s]btn|[m]etals|[b]loop|[s]bt\.ForkMain' ; ls -la project/target/active.json`
   Expected: no `ps` lines, and `No such file or directory`.
2-6. Same commands and expected results as in the rehearsal (items 2-6 of the checklist in `docs/agents.md`).
7. `find build.sbt project modules tests datasets gold -name .DS_Store -delete ; find build.sbt project modules tests datasets gold -name .DS_Store -print`
   Expected: only `find: gold: No such file or directory`, from each of the two finds. No `.DS_Store` paths.
8. `git status --porcelain=v1 --ignored=matching --untracked-files=all -- build.sbt project modules tests datasets gold | grep -vxE '!! (datasets/downloads/|modules/(core|entity-resolution|hadoop|rag|services|spark|storage)/target/|project/target/|project/project/target/|tests/target/)' ; echo "disallowed-exit=$?"`
   Expected: only `disallowed-exit=1`.
9-11. Same as the rehearsal. For item 9, the labels file must be absent.
12. `find ~/.sbt -maxdepth 3 -type f \( -name '*.sbt' -o -name '*.scala' \) -not -path '*/boot/*' -print ; echo "find-exit=$?"`
    Expected: only `find-exit=0`.
13. Optional, as in the rehearsal.
14. `time sbt -Dsbt.supershell=false localPipelineHeldout`
    Keep stdin, stdout and stderr on the Terminal: no `<`, `>`, `|`, `tee`, `script` or tmux logging. Expected first task lines: `localPipelineHeldout: plain batch sbt with stdout and stderr on a terminal (JDK ...)`, `localPipelineHeldout: git_head=... git_worktree=...`, `localPipelineHeldout: clean tree in build.sbt project modules tests datasets gold (decision D3(b)): ...`. Exit 0 means success; on a refusal, the error line names the reason.
    If the task refuses because of `-Dsbt.supershell=false`, read the printed reason, then retry without the flag (`docs/agents.md`).

Never run `sbt clean` or delete `modules/hadoop/target/` in that Terminal while the held-out attempt is not archived (D10). No hook protects the human's Terminal.

## After the run (human, then a fresh session)

1. Keep the full Terminal output of step 14. It contains no labels or metrics.
2. Archive the attempt outside the repository (D10(i)). Copy rather than move, because membership generation reads the curated run in place:
   `cp -Rp modules/hadoop/target/local-pipeline/er-candidates/heldout/attempt-1 /Users/taufeeqpatel/Desktop/ScholarChain-heldout-attempt-1-<YYYYMMDD>`
   Use the attempt directory that the run's `[er-split] split=heldout runId=... attempt=... status=complete output=...` line names, if it is not `attempt-1`.
3. `shasum -a 256 modules/hadoop/target/local-pipeline/er-candidates/heldout/attempt-1/summary.txt`
4. Open a fresh Claude Code session. Paste the step 14 output and the outputs of 2 and 3, and point it to this file. The orchestrator then verifies completion, records the run ID and the `summary.txt` sha256 for the human's `human-gate.md` entry, and reads the distinct researcher and work pair counts from the summary to decide whether the population reaches 240 (sample) and at least 200 (requirement).

## Next, after the run is verified (not started)

1. Membership and blind view (human, Terminal): `ReviewQueueMain membership <split-assignment.tsv> <PROVENANCE.tsv> <curated run dir> heldout gold/entity-resolution/heldout-v1/<manifest>.jsonl gold/entity-resolution/heldout-v1/<blind view>.jsonl <seed>` (capacity defaults to 240). Exact arguments and file names will be prepared from the code and the ratified amendment before use.
2. Freeze: write the `.sha256` freeze line, write `er-config.conf`, commit the four gold files before any label exists, and record the sha256 in `human-gate.md`.
3. Labeling in a spreadsheet outside the repository, from the frozen blind view only. Then convert to `er-gold-label.v3` JSON Lines at the L1-ignored path, and validate.
