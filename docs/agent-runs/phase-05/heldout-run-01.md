# Phase 5 held-out pipeline run 01 (human run; orchestrator verification)

Assembled by the orchestrator on 2026-11-15. This file records observations, not a human-gate record. The human ran `sbt localPipelineHeldout` in a regular Terminal (D15(c), decisions-21). No agent ran it. Nothing was committed.

## Human statement (2026-11-15, verbatim excerpt)

```text
The Phase 5 held-out pipeline completed successfully after all 14 final pre-run checks.

Run: heldout/attempt-1
Status: complete
Candidates: 533 researcher pairs and 155 work pairs, 688 total.
Final oversized-block drops: 0.

I archived a copy at ~/Desktop/ScholarChain-heldout-attempt-1-20261115 and calculated the SHA-256 checksum of the original summary.txt.
```

## Verified by the orchestrator (Read only)

| Item | Value | Source |
|---|---|---|
| Status | `status=complete` | `summary.txt` line 49 |
| Run ID | `run:7ae22ee957b7b55ef7d19d8eb2545913b55ee14388a4aaba7a2442f412d7159a` | `summary.txt` line 49; `attempt-1/_SUCCESS` (`runId=` line, same value); curated directory name `run=7ae22ee9...159a` |
| Attempt directory | `modules/hadoop/target/local-pipeline/er-candidates/heldout/attempt-1/` (git-ignored) | `summary.txt` line 49 |
| Curated run root | `.../heldout/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=7ae22ee957b7b55ef7d19d8eb2545913b55ee14388a4aaba7a2442f412d7159a/`; `normalize/_SUCCESS` and `candidates/_SUCCESS` exist | Read |
| Distinct pairs | researcher 533, work 155, total 688; `candidateMerged=0`; read-back 533 / 155; `check=...:ok` | `summary.txt` line 28 |
| Oversized-block drops | none: researcher and work `cap=8 policy=drop groups=0 groupsWithDrops=0 membersDropped=0 droppedByKind=none simulationMatchesRun=true` | `summary.txt` lines 29, 38 |
| Teams | `teams=78 coauthorPairs=261 oversizeTeams=0 maxTeamSize=5` | `summary.txt` line 47 |
| Normalize | `rejected=0 flagged=5 works=171 mentions=485 profiles=14 duplicate=7 conflict=5`; every duplicate named | `summary.txt` lines 10, 27 |
| Provenance | dblp `dblp-2026-10-03`, openalex `openalex-api-2026-11-05`, crossref `crossref-api-2026-11-05`; `every-record-snapshot==manifest-snapshot:ok` | `summary.txt` lines 11-14 |
| Run manifest | `codeVersion` `a961035e8863a1c4b3337557bb17fbab05a1182a` (commit A; A to B differs only in the two run-manifest pins, see `dev-run-02.md`); `er-blocking.v2`, `er-features.v2`, `maxBlockSize` 8, `input.split` heldout, `input.digest` `bf26d518de202a48cbafc631f547bf69d3dc5bc2f86f3825f2fb7cd533b94731` | `_run-manifest.json` |
| Elapsed | `elapsed total ms=14484` | `summary.txt` line 48 |
| Archive | `/Users/taufeeqpatel/Desktop/ScholarChain-heldout-attempt-1-20261115/summary.txt` exists and its text reads identical, line for line, to the original (49 lines, same run ID) | Read of both files; visual comparison, not a byte comparison |

## Not verified

- **`summary.txt` SHA-256.** The human's value was not included in the message. The orchestrator has no allowlisted command that computes a hash (`shasum` has no ORCHESTRATOR row), so it cannot compute or independently confirm the value. The human records it in `human-gate.md`, from their own `shasum -a 256` output; a second `shasum -a 256` on the archive copy in the Terminal is the available independent cross-check.
- **Final-pass checklist and step 14 Terminal output.** Not provided to this session. Only the rehearsal (`heldout-prerun-rehearsal-01.md`) is on file. The `git_head`, launch-check and D3(b) clean-tree lines of the run are therefore not transcribed here.
- **Population vs capacity:** 688 >= 240, so `ReviewQueueMain membership` will draw a seeded proportional sample of 240 (not a census). Not yet run.

## Membership seed (human decision, 2026-11-15, verbatim)

```text
approve seed heldout-v1-20261115
```

Chosen before any membership was generated or any sample was seen. Capacity: 240 (default, decisions-04).

Orchestrator pre-check before generation (2026-11-15): `git rev-parse HEAD` = `71ff55199c454c7c2393c973955cb6950f4d5392`; `git status --porcelain` lists only untracked `docs/agent-runs/phase-05/*` files (no tracked change); `git ls-files -- gold` prints nothing.

## Step 2 pre-generation check (human Terminal, 2026-11-15)

Human statement, verbatim: "All four pre-generation checks passed. The repository is clean in the required scope, gold/ does not exist, the _SUCCESS run ID matches, and the curated output directory exists." The raw Terminal output was not pasted into this session.

## Step 3 generation and freeze file (human Terminal, 2026-11-15)

Human statement, verbatim: "The membership SHA-256 freeze file has been created and verified. Its contents match the generated manifest hash." The step 3 Terminal output (population, sample and per-stratum counts, printed manifest sha256) was not pasted into this session.

Orchestrator checks (Read only):
- The next orchestrator Bash call was refused once with the documented UNATTRIBUTED report naming exactly `gold/entity-resolution/heldout-v1/er-blind-label-view.jsonl`, `er-gold-membership.jsonl` and `er-gold-membership.jsonl.sha256` (owner HUMAN). The baseline was reset.
- `er-gold-membership.jsonl`: 240 lines plus a final LF. Lines 1 and 240 both carry `split=heldout`, `generatingRunId=run:7ae22ee9...159a`, `blockingVersion=er-blocking.v2`, `selectionRule=er-gold-sample.v1`, `seed=heldout-v1-20261115`, `capacity=240`, `populationSize=688`. Not hashed by the orchestrator (no hash command).
- **Freeze file format defect.** `er-gold-membership.jsonl.sha256` holds only `22779ca065cfb78f120e35e107c60f4fea15bb05ee998801c4e864dd2e21f8f4` + LF, without the two spaces and the file name. `FreezeRecord.parse` refuses this shape (`FreezeRecord.scala:94`; test case "bare digest", `FreezeSplitConfigSuite.scala:62`), so `goldSetEvalHeldout` would refuse it. Nothing is committed; the human regenerates the file with `shasum -a 256 er-gold-membership.jsonl > er-gold-membership.jsonl.sha256` in that directory. The digest value itself is unaffected.

## Steps 5-6 (human Terminal, 2026-11-15) and pre-commit check

Human statement (summary): steps 5 and 6 completed; `er-config.conf` created with the exact contents provided; non-ASCII check printed nothing, final byte is a newline, `shasum` succeeded. The config sha256 value was not pasted.

Orchestrator checks (Read and allowlisted git only; no matching or evaluation logic run):
- `er-config.conf`: identical to the provided text, 45 lines. Against `ConfigKeys.all` (core `ConfigLoader.scala:95-128`): all 32 keys present, no unknown key, `configSchemaVersion` `scholarchain.config.v2`, kinds valid (region pattern, 12-digit quoted account ID, bucket names, tokens, positive integers, windows, `identityVersion` `v1`, `openSearchEndpoint` null for an optional key). `entityResolution` equals `er-policy.v8.json`: `policyVersion` er-policy.v8, accept 0.85, reject 0.2, band [0.4, 0.7], prompt `er-adjudication-prompt.v2`; ordering 0.2 < 0.4 < 0.7 < 0.85 holds.
- `er-blind-label-view.jsonl`: 240 lines plus a final LF; schema `er-blind-label-view.v5`, label guide `er-label-guide.v1` (line 240 read).
- **Freeze file still defective.** `er-gold-membership.jsonl.sha256` still reads `22779ca0...f8f4` + LF with no file name. The UNATTRIBUTED report at the next Bash call named only `er-config.conf` as changed since the previous call, so the freeze file's content did not change in step 5. Not ready to commit.
- `git ls-files --others --exclude-standard -- gold`: the four files above, none ignored; no labels file.

## Freeze commit (human, 2026-11-15)

Human statement (summary): the four frozen held-out files were committed in `01f1799`; the manifest has 240 members, 187 researcher and 53 work; its SHA-256 was verified.

Orchestrator checks:
- The UNATTRIBUTED report at the next Bash call named `er-gold-membership.jsonl.sha256` as changed (the step 5 rewrite). Read now shows `22779ca065cfb78f120e35e107c60f4fea15bb05ee998801c4e864dd2e21f8f4  er-gold-membership.jsonl` + LF, the accepted `FreezeRecord` shape.
- `git show --stat 01f1799`: "Phase 5: freeze held-out membership and evaluation config", exactly `er-blind-label-view.jsonl` (240), `er-config.conf` (45), `er-gold-membership.jsonl` (240), `er-gold-membership.jsonl.sha256` (1); 4 files, 526 insertions. Parent `71ff551`.
- `git diff --stat HEAD -- gold`: no output (the working tree equals the commit).
- The researcher/work split 187/53 is the human's figure; the orchestrator did not count the manifest lines by kind. The step 3 per-stratum lines were not pasted.

## Next (human, not started)

Membership manifest and blind view for `gold/entity-resolution/heldout-v1/`, then freeze, config and commit before any label exists (`docs/agents.md`, Human gates, Phase 5).
