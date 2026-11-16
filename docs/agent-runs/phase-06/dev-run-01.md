# Phase 06 dev run 01: ER-P6-09, `sbt erDecisionGroupingDev` (entity-resolution-engineer, 2026-11-16)

- Work order (verbatim): docs/agent-runs/phase-06/prompt-er-p6-09-dev-run.md (includes the FE-P6-03 probe result)
- HEAD at run: 6be1f1db6eb22864ac20b051040412d8ddb4e0e1; code commit pinned in both manifests: 8c1bee042e7dca3119f5116df2150e0bee204598
- Outputs are DEV, unevaluated; no quality claim. They live under gitignored `target/` and are deleted by `sbt clean`.

## Agent report, verbatim

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-09 (Phase 6). Run the ratified dev-only `sbt erDecisionGroupingDev` exactly once, report the result, change no files.

SUMMARY
The single run succeeded with exit 0 (`[success] Total time: 3 s, completed Nov 16, 2026, 11:28:16 AM`). Both stages ended published-and-verified. The decision run ID matches the expected value. There was no refusal, no retry, and no other sbt command.

PREREQUISITES (step 1)
- `git rev-parse HEAD` printed 6be1f1db6eb22864ac20b051040412d8ddb4e0e1, as expected.
- `git status --porcelain`, before and after the run, printed only `?? docs/agent-runs/phase-06/prompt-er-p6-09-dev-run.md`.

TASK OUTPUT, VERBATIM (step 3). The pre-check block was printed twice, with the same content both times.
- `erDecisionGroupingDev: source and code-scope checks before compiling`
- `erDecisionGroupingDev: git_head=6be1f1db6eb22864ac20b051040412d8ddb4e0e1`
- `erDecisionGroupingDev: committed and unmodified (raw bytes equal HEAD): modules/entity-resolution/local-runs/er-dev.conf, modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json, modules/entity-resolution/local-runs/er-grouping.dev.run-manifest.json`
- `erDecisionGroupingDev: code scope build.sbt project modules/core modules/entity-resolution (BE-P6-02): 153 tracked files without index flags and with raw bytes equal to HEAD; no modified, staged, deleted, or untracked path; 4 ignored path(s), all sbt target directories or .DS_Store files; .gitignore committed and unmodified; codeVersion 8c1bee042e7dca3119f5116df2150e0bee204598 in both manifests is an ancestor of HEAD 6be1f1db6eb22864ac20b051040412d8ddb4e0e1, and 8c1bee042e7dca3119f5116df2150e0bee204598..HEAD changes modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json, modules/entity-resolution/local-runs/er-grouping.dev.run-manifest.json in this scope`
- Preflight: `erDecisionGroupingDev: dev curated run modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa (the one complete dev attempt)`
- `erDecisionGroupingDev: target/local-runs/dev/er-run-roots.conf written (run roots only; sha256=fcfeaf8c2a002852481d2d078da5b04e2c5af2e6e6ef7f390e0e1a7d44ee51b9); the entry points print the sha256 of both config files`

Decision stage:
- `decision run=run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a stage=er.decisions attempt=1 inputRunId=run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa decidedAt=2026-11-15T00:00:00Z`
- `decisions researcher=296 work=78`
- Counts by outcome and match path (dev, unevaluated):
  - researcher ACCEPTED: deterministic-bibliographic=97, exact-id=104, probabilistic=63
  - researcher CONFLICT: exact-id=6
  - researcher REJECTED: deterministic-bibliographic=3, exact-id=1
  - researcher UNRESOLVED: probabilistic=22
  - work ACCEPTED: exact-id=51
  - work CONFLICT: exact-id=27
- `verified decision run=run:736cb17...b76a dir=/Users/taufeeqpatel/Desktop/ScholarChain/target/local-runs/dev/er-decisions/run=736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a researcher=296 work=78 (read back with DecisionExport.readPublished; equal to the published batch)`
- `settings policy=er-policy.v8 blocking=er-blocking.v2 features=er-features.v2 accept=0.85 reject=0.2 llmBand=[0.4,0.7] prompt=er-adjudication-prompt.v2 model=none (no adjudicator ran; no LLM call) config=applied`
- `config file=.../modules/entity-resolution/local-runs/er-dev.conf sha256=80f85d4130637a86ffdfaf15650dc61d8a79273e7648fb7d9f395d95cd78b8aa`
- `config file=.../target/local-runs/dev/er-run-roots.conf sha256=fcfeaf8c2a002852481d2d078da5b04e2c5af2e6e6ef7f390e0e1a7d44ee51b9`
- `config overrides=none`
- State line: `DecisionRunMain: published-and-verified: target/local-runs/dev/er-decisions/run=736cb17...b76a (exit 0; the listing shows exactly this new run= directory, without _UNVERIFIED)`

Grouping stage:
- `grouping run=run:84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2 stage=er.grouping attempt=1 decisionRunId=run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a inputRunId=run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa assignedAt=2026-11-15T12:00:00Z`
- `records researcher=292 work=90 authorship=143 team=41`
- `links merged=220 redundant=91 vetoed=4`
- `verified grouping run=run:84dd896...d7d2 dir=/Users/taufeeqpatel/Desktop/ScholarChain/target/local-runs/dev/er-assignments/run=84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2 researcher=292 work=90 authorship=143 team=41 (read back with GroupingExport.readValidated; equal to the published batch)`
- `settings groupingPolicy=er-grouping.v1 matchPolicy=er-policy.v8 identityVersion=v1 configDigest=484845939b9f036bb5ff460a8b0a856cb1b731b5a2a080a1737168faa0026298 decisionRunRoot=.../target/local-runs/dev/er-decisions assignmentRunRoot=.../target/local-runs/dev/er-assignments model=none (grouping makes no model call) config=applied`
- The same two config sha256 lines as the decision stage, then `config overrides=none`.
- State line: `GroupingRunMain: published-and-verified: target/local-runs/dev/er-assignments/run=84dd896...d7d2 (exit 0; the listing shows exactly this new run= directory, without _UNVERIFIED)`
- `erDecisionGroupingDev: complete: decision run run:736cb17...b76a ..., read back by GroupingRunMain through DecisionExport.readPublished; grouping run run:84dd896...d7d2 ...; curated input modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=2881724b...baa; DEV only, unevaluated`
- `[success] Total time: 3 s, completed Nov 16, 2026, 11:28:16 AM`

GROUPING DIAGNOSTICS (dev, unevaluated, no quality claim; as printed)
- Researchers:
  - members=292, mentions=286, profiles=6, researcherIds=117
  - groupSizes 1:7, 2:71, 3:13, 4:26
  - status: resolved=285, singleton=3, singletonT1Anchored=4, unresolved=0 (rate 0.0000)
  - anchorNamespaces: member:14, openalex-author:16, orcid:87
  - anchorCollisions identifiedGroups: grouping.anchor-collision.openalex-author:2, grouping.anchor-collision.orcid:4; collidedValues: orcid:2, dblp-pid:0, openalex-author:1
  - links: total=264, merged=175, redundant=87, vetoed=2
  - veto rules: BD:2, N1:2, T1:2
  - veto reasons: blocking-disagreement.openalex-author:2, negative-decision:2, t1-disagreement.orcid:2
- Works:
  - members=90, canonicalWorks=45, groupSizes 1:4, 2:37, 3:4
  - graphEligibility: counted=41, member-of-counted=43, enrichment-only=6
  - links: total=51, merged=45, redundant=4, vetoed=2
  - veto rules: N1:2, T1:2
  - veto reasons: negative-decision:2, t1-disagreement.doi:2
- Teams: teams=41, teamMentions=135, unresolvedTeamMentions=0, works=90
- `ST=0 (expected 0 under er-policy.v8) SW-C=0 (O8 diagnostic, not a veto)`

READ-ONLY OUTPUT INSPECTION (step 4; Read only, no Grep)
- Directory listing limitation: I have no Glob tool, and ls is not on my Bash allowlist. Read refuses directories, so I could not list the run roots myself. The evidence that each root holds exactly one run= directory with no _UNVERIFIED is the task's own listing check, quoted in the two state lines above. Checks I did make:
  - _SUCCESS exists (empty) in both run directories.
  - `<run>/_UNVERIFIED` does not exist in either.
  - I did not check the roots for .unverified-* or .staging-* entries, because I cannot list them.
- Decision run: `/Users/taufeeqpatel/Desktop/ScholarChain/target/local-runs/dev/er-decisions/run=736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a`
  - _run-manifest.json: schemaVersion scholarchain.run-manifest.v1; stage er.decisions; codeVersion 8c1bee042e7dca3119f5116df2150e0bee204598; configDigest 61655dc122d9317924cb58b67fa7fbd176349e8ea13401c17af2f865f9bbe085 (matches 61655dc1...e085); inputSnapshots dblp-2026-10-03, openalex-api-2026-11-05, crossref-api-2026-11-05; parameters blockingVersion er-blocking.v2, featureVersion er-features.v2, policyVersion er-policy.v8, decidedAt 2026-11-15T00:00:00Z, inputRunId run:2881724b...baa; attempt 1.
  - _parts.json (scholarchain.part-manifest.v1, runId matches):

    | Part | Schema | Rows | Bytes | sha256 |
    |---|---|---|---|---|
    | _run-manifest.json | scholarchain.run-manifest.v1 | 1 | 641 | b50124ec9487c7136043cad9c6edf304f9234d59a7b05707b7e89801f2046f20 |
    | decisions/researcher/part-00000.jsonl | scholarchain.match-decision.v2 | 296 | 659552 | 835b06093c0909e21421b667125e8733136635a967affc271fc4b0f5f09cf264 |
    | decisions/work/part-00000.jsonl | scholarchain.match-decision.v2 | 78 | 136155 | de5d41646318ded61f7aceab0825fa5d8b2b5b30e1ebe2d50425e42b5c5b61b7 |

- Grouping run: `/Users/taufeeqpatel/Desktop/ScholarChain/target/local-runs/dev/er-assignments/run=84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2`
  - _run-manifest.json: stage er.grouping; codeVersion 8c1bee042e7dca3119f5116df2150e0bee204598; configDigest 484845939b9f036bb5ff460a8b0a856cb1b731b5a2a080a1737168faa0026298 (matches 48484593...6298); the same three snapshots; parameters decisionRunId run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a (equals the printed decision run ID and the expected value), groupingPolicyVersion er-grouping.v1, identityVersion v1, matchPolicyVersion er-policy.v8, inputRunId run:2881724b...baa, assignedAt 2026-11-15T12:00:00Z; attempt 1.
  - _parts.json (runId matches):

    | Part | Schema | Rows | Bytes | sha256 |
    |---|---|---|---|---|
    | _run-manifest.json | run-manifest.v1 | 1 | 728 | 5eec2572b8c9b23792412de754bd82c43fc799f000ef6bde813e7ec6e21dff4f |
    | assignments/researcher/part-00000.jsonl | researcher-assignment.v1 | 292 | 381248 | 4446b4838d8f16f795912edb4e7c014327a970098562c8aa5235f9627544a129 |
    | assignments/work/part-00000.jsonl | work-assignment.v1 | 90 | 106127 | fc4d96d0502c75dbfbc33fe6c9e59e51185d25581c22b32ab860307698767088 |
    | authorships/part-00000.jsonl | authorship-record.v2 | 143 | 148468 | 416ee5eb862b37eeb37042595edfac68edd4343c11788eb14529bd7bad33c116 |
    | teams/part-00000.jsonl | publication-team.v1 | 41 | 27771 | 251f4b82adba9454700acd2c3dc44de450eb42607a10ec6df9d1157fd51ca981 |

    Schemas are shown without their `scholarchain.` prefix.
  - Record counts: ResearcherAssignment 292, WorkAssignment 90, AuthorshipRecordV2 143, PublicationTeam 41.
- Overlay config: `/Users/taufeeqpatel/Desktop/ScholarChain/target/local-runs/dev/er-run-roots.conf`, sha256 fcfeaf8c2a002852481d2d078da5b04e2c5af2e6e6ef7f390e0e1a7d44ee51b9 (as printed). It holds config.v2 with the decision and assignment run roots only.

SPARK HANDOFF EVIDENCE (DEV, unevaluated)
- Code commit: 8c1bee042e7dca3119f5116df2150e0bee204598. Manifests pinned at 7b72681; run from HEAD 6be1f1db6eb22864ac20b051040412d8ddb4e0e1.
- Curated input:
  - run ID run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa
  - path `/Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa`
  - snapshots: dblp/dblp-2026-10-03, openalex/openalex-api-2026-11-05, crossref/crossref-api-2026-11-05
- Decision run:
  - run ID run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a
  - absolute path `/Users/taufeeqpatel/Desktop/ScholarChain/target/local-runs/dev/er-decisions/run=736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a`
  - repository path `target/local-runs/dev/er-decisions/run=736cb17...b76a`
  - parts: as in the decision table above (match-decision.v2: researcher 296 rows, work 78 rows)
- Grouping run:
  - run ID run:84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2
  - absolute path `/Users/taufeeqpatel/Desktop/ScholarChain/target/local-runs/dev/er-assignments/run=84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2`
  - repository path `target/local-runs/dev/er-assignments/run=84dd896...d7d2`
  - parts: as in the grouping table above (researcher-assignment.v1 292, work-assignment.v1 90, authorship-record.v2 143, publication-team.v1 41)
- Consumer rules:
  - Read only through the verified readers DecisionExport.readPublished and GroupingExport.readValidated.
  - Refuse any directory name that is not run=<64 lowercase hex>.
  - Refuse any run with _UNVERIFIED or without _SUCCESS, and any .staging-* or .unverified-* entry.
  - Check every part against _parts.json (rows, bytes, sha256), and check that the grouping manifest's decisionRunId equals the decision run used.
  - Graph construction uses only works with graphEligibility counted (41 dev works). Member-of-counted and enrichment-only works are not separate graph works.
  - Teams come from the grouping run's teams/ part, not from Hadoop TeamJob output.
  - These are DEV outputs with no quality claim.
- Durability: the outputs live under gitignored target/. `sbt clean` deletes them. They are not committed or audited, and a rerun must reproduce them from the pinned manifests.

FILES CREATED: none by me. The task itself writes under gitignored target/local-runs/dev/: er-run-roots.conf, the decision run directory and the grouping run directory.
FILES MODIFIED: none
COMMANDS RUN:
- `git rev-parse HEAD` (exit 0)
- `git status --porcelain` (exit 0)
- `sbt erDecisionGroupingDev` (once, timeout 600000 ms, exit 0, [success])
- `git status --porcelain` (exit 0, unchanged)
TEST RESULTS: none run; the work order forbids sbt check and any other sbt command.
VALIDATION RESULTS:
- Both stages are published-and-verified, each read back twice (by the entry point's verified reader and by the sbt task's listing check).
- The decision run ID equals the expected value and the grouping manifest's decisionRunId.
- Both configDigests and the inputRunId match the work order.
- _SUCCESS is present and _UNVERIFIED absent in both runs.
- No held-out path was read or run.
ASSUMPTIONS:
- The task's printed listing check is the evidence that each root has exactly one run= directory, because I have no directory-listing tool.
- The doubled pre-check block is the task's normal output (two entry points or two task dependencies), not two runs. Only one fork per stage and one attempt=1 per stage appear.
RISKS:
- I did not list the run roots myself, so .staging-* and .unverified-* absence rests on the task's own check.
- The outputs are ephemeral: any `sbt clean` removes them before Spark consumes them.
- All counts are dev, unevaluated diagnostics.
MANUAL ACTION REQUIRED: If an independent listing of target/local-runs/dev/er-decisions/ and er-assignments/ is wanted, the human or an identity with Glob can do it. Archive the outputs before any `sbt clean` if Spark work depends on them.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human reviews this report. Then the orchestrator routes the Spark handoff to spark-engineer (Phase 6 graph construction), consuming these two run IDs through the verified readers.

## Orchestrator notes (2026-11-16)

- The researcher and work link totals reconcile with the stage summary: merged 175 + 45 = 220, redundant 87 + 4 = 91, vetoed 2 + 2 = 4.
- "Total time: 3 s" is sbt's figure for the command as observed; it is short for two forked JVMs and is recorded as observed, not explained.
- The pre-check block printed twice is consistent with BE-P6-02's design (checks before compile in `erDevBeforeCompile`, repeated in the preflight after compile).
- These counts come from the localPipelineDev attempt and differ from the earlier committed-sample probe (103 researcher assignments); the two use different inputs. No comparison or quality claim is made.
- `sbt clean` and `sbt clean check` are on every agent's allowlist (`@SBT-ALL`) and would delete these outputs and their Hadoop dev input. The runs are reproducible from commit 6be1f1d (or any later commit that changes nothing in the code scope), but the bytes are not archived.
