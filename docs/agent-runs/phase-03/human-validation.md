# Phase 3 human-run validations (2026-10-29)

Assembled by the orchestrator from results the human ran in their own terminal and reported in the session. This is not `human-gate.md`; Phase 3 is **not** accepted by this record.

All commands ran from the repository root on uncommitted files (no preparatory commit). The agent rule that scripts must be committed and identical to HEAD applies only to agent Bash calls. Before the human ran them, the orchestrator read every script and confirmed that the modes used are offline: no `curl`, no AWS access, and no writes. The `--sample` and `--full` modes were not run.

## Manual changes made by the human

| Step | Command (as run) | Observed result |
|---|---|---|
| Rename (review 1 n1) | `mv -n modules/core/src/test/scala/scholarchain/core/TmpDumpSuite.scala modules/core/src/test/scala/scholarchain/core/GraphModelCoverageSuite.scala` | `ls` shows `GraphModelCoverageSuite.scala` and no `TmpDumpSuite.scala`. A first attempt was split across two lines when pasted, failed with a `mv` usage error, and changed nothing. |
| Executable bits | `chmod +x` on `validate-manifest.sh`, `fetch-dblp.sh`, `fetch-openalex.sh`, `fetch-crossref.sh`, `fetch-orcid.sh`, `fetch-samples.sh` | Done (human report). |

The audit hook later reported these changes, plus the test marker, as an UNATTRIBUTED inter-call change at the orchestrator's next Bash call. That is the documented behaviour: the call was refused, the baseline was reset, and the paths listed matched the human's actions exactly.

## Validation results (human-observed)

| Gate item | Command | Result |
|---|---|---|
| Dataset manifest validation passes | `datasets/scripts/validate-manifest.sh --verify` | `manifest validation: 313 checks, 0 failures` (human report) |
| Sample scripts do not silently fetch large files | `datasets/scripts/fetch-samples.sh --dry-run` | All four datasets completed; 0 bytes fetched (human report) |
| Sample verification | `datasets/scripts/fetch-samples.sh --verify` | All datasets completed successfully (human report) |
| Definition of done after the rename | `sbt check` | `[info] Passed: Total 167, Failed 0, Errors 0, Passed 167` (core). Every other project: `Passed: Total 0`. `markTestRun: core: production sources=14, test sources=11, passed=167`. `wrote .../.claude/.last-test-run (git_head=e12777342c9b08775eb294a8452a0381e528ac8b, worktree=dirty, suites=10, passed=167, skipped=0)`. `[success] Total time: 1 s, completed Oct 29, 2026, 3:42:56 PM` (seen in the human's terminal screenshot). |

Not run: `validate-manifest.sh` with no flag (expected usage and exit 2). It is not a gate item.

## Final `sbt check` after repair-02 (human-run)

| Command | Result | Evidence form |
|---|---|---|
| `sbt check` (final tree, after the HOCON change) | 174 tests passed, 0 failures, 0 errors | Human report in session. The screenshot attached to that message shows `datasets/samples/ATTRIBUTION.md`, not the `sbt check` output, so the orchestrator did not see this result. It matches domain-engineer's agent-observed `sbt check` in repair-02 (core `Passed: Total 174, Failed 0, Errors 0, Passed 174`). |

Test-marker attribution: the final human-run `sbt check` changed `.claude/.last-test-run` outside an audited Bash call. The Stop hook refused orchestrator completion, and the next orchestrator Bash call reported one UNATTRIBUTED change, `.claude/.last-test-run` only, and reset the baseline. The human then checked the marker and confirmed it matches the manual run: `passed=174`, `suites=10`, 0 failed, 0 errored, 0 skipped, timestamp `2026-10-29T23:47:57Z`.

## External source verification status (as of 2026-10-29)

| Category | Status | Verified by |
|---|---|---|
| General reuse terms: DBLP (CC0 1.0, ODC-BY 1.0 secondary), OpenAlex (CC0 1.0), Crossref (metadata generally not subject to copyright; abstracts excluded), ORCID Public Data File (CC0 1.0, attribution requested) | **Independently checked** against the official terms pages | Human, using a separate tool (Codex), outside this session. Reported by the human; not observed by the orchestrator. |
| ORCID release record: published 2025-10-20, data as of 2025-10-01, CC0 1.0, XML format | **Independently checked** on the official ORCID release page | Human, directly |
| DBLP release: date 2026-10-03, DOI 10.4230/dblp.xml.2026-10-03, displayed size (1.03 GB), published MD5 `cbd329100ea1bbb2873fa2f6d414ea00` | **Independently checked** | Human, using Codex (reported by the human; not observed by the orchestrator) |
| OpenAlex release date 2026-10-14 | **Independently checked** | Human, using Codex (reported by the human) |
| Crossref: 2026-03-17 announcement, "nearly 180 million" records, "208 GB", linked dataset DOI 10.13003/nggf-vt1j | **Independently checked** | Human, using Codex (reported by the human) |
| Other release-specific claims: exact OpenAlex manifest totals (all entities, works, authors, institutions); ORCID summaries archive size (46,328,319,190 bytes), figshare file ID 58834837 and article ID 30375589; Crossref torrent infohash, torrent size and file count, and requester-pays bucket; whether a 2026 ORCID file exists; the DBLP DTD change date and person IDs | **Not independently verified.** Recorded by dataset-engineer from official pages through a summarizing fetch tool. | none |
| Individual sample values: DBLP keys and person IDs, OpenAlex W/A/I/S/T IDs, ORCID put-codes and DOI claims, author lists, titles, pages | **Unverified.** Transcribed by dataset-engineer from official APIs. The reviewer checked only internal consistency (cross-file agreement and ORCID check digits). | none |

Unverified claims must not be cited as fact in later phases until they are verified, or until the first real download confirms them (observed sizes and SHA-256 are still `pending-human-download` in the manifest).

## Status after these results

- Gate item 3 (manifest validation) is closed by the human-observed result above.
- The manifest and sample results above came before the HOCON configuration change chosen by the human on 2026-10-29. That change touched no `datasets/**` file, so they still apply. The final human-run `sbt check` after the change is recorded above (174 passed).
- Phase 3 is not accepted. Acceptance happens only in the human's `human-gate.md`.
