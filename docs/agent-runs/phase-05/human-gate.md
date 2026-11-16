# Phase 5 Unicode prerequisite human gate

Decider: Taufeeq Patel
Date: 2026-11-04
Scope: Unicode prerequisite only. Main Phase 5 is not accepted or completed.

## Decision
I accept the Unicode prerequisite implementation and its documented limitations.
Final closure remains pending a clean-tree test run on the committed prerequisite
and recording that commit and verification evidence.

## Evidence reviewed
- prereq-unicode-limitations.md
- prereq-unicode-test-report.md
- Recorded code-reviewer APPROVE, with NIT-1 and NIT-2 nonblocking.
- Working-tree file inventory, displayed diff portions, and new test inventory.
This was a focused acceptance check, not a fresh comprehensive code review.

## Personally observed verification
Command: sbt -no-colors check
Completed: 2026-11-04 21:59:29 America/Chicago
Exit status: 0
Passed: core 252, entityResolution 75, hadoop 67, tests 49; total 443.
Failed: 0. Errors: 0. Skipped: 0. Suites: 34.
HEAD: d16e8c9717f806f6f4018b56beb456382c5ed23a
Working tree: dirty.
Local full log: /tmp/scholarchain-unicode-check.zXN8Wt
The temporary log is not a durable archive or clean-commit evidence.

## Accepted limitations
- NIT-1: lone-surrogate fusion and the documented equivalence-claim limitation.
- NIT-2: Cf-only separation before a DBLP homonym number can lose recall.
- Cf-only word separators join words.
- Hadoop AuthorMention.normalizedName retains Cf; matching must use the
  intended entity-resolution keys and features.
- Blocking/features v1 outputs must not be mixed with v2 outputs.
- Cf classification depends on the running JDK's Unicode tables.
- Regression sensitivity against pre-fix code was hand-traced, not executed.
- Gold-set evaluation and the effect on match quality remain main Phase 5 work.
- Remaining evidence limitations are acknowledged as documented in
  prereq-unicode-limitations.md.

## Manual actions and boundaries
I ran verification in Terminal; generated build outputs and the test marker
were updated. This acceptance record was drafted with reviewer assistance.
No implementation was manually edited during this acceptance session.
Phase 4 AWS prerequisites and other deferred items remain outside this acceptance.

## Commit and clean-tree verification
Pending; to be recorded after the prerequisite commit.

## Final prerequisite closure — 2026-11-04
The pending verification conditions above are now satisfied.
I accept commit 127819b6a6e8eefd5c9ce270e924759f485a4523 as the completed
Unicode prerequisite, retaining the documented limitations.

I personally ran sbt -no-colors check on that commit with an empty
git status --porcelain captured immediately before execution.
Result: exit 0; 443 passed, 0 failed, 0 errors, 0 skipped; 34 suites.
Completed: 2026-11-04 22:04:09 America/Chicago.
Evidence: prereq-unicode-clean-test-report.md.
commit.txt records the Unicode prerequisite commit only.

This closes only the Unicode prerequisite. Main Phase 5 remains unimplemented
and unaccepted. The follow-up commit records evidence only.

## FE-P5-07 ratification and validation — 2026-11-14

I ratified FE-P5-07 and applied its patch from regular Terminal with Claude Code closed.
- Patch SHA-256: `978146a43ecb7a3cbc2342caa28160ded373d675c77e534c81297f510b504937`
- Applied allowlist SHA-256: `eb708cd4ea78c8f8da0a68579c1bb0b009840c762f5c0a58c052847f18f7eb73`
- All four hook syntax checks passed. Settings JSON parsed. The allowlist parsed with 94 identity rows and 3 groups, and the 119-row documentation mirror matched.
- Agent-file validator: PASS, 0 failures.
- Behavior probes: PASS, 0 failures, with 661 table probes and 38 fixture probes. Both held-out cleanup guard checks passed.
- The applied probe table matched the reference after normalizing three Unicode escapes. Temporary fixture cleanup required interactive confirmation.

---

# Phase 5 final human gate

Reviewer: Taufeeq Patel
Date: 2026-11-15
Specification: README.md, Phase 5

## 1. What I reviewed
- Agent reports: agent-report.md. Reviews: review-report.md (including CR-P5-07), review-report-cr-p5-05.md, review-report-cr-p5-06.md.
- Test evidence: test-report.md (TE-P5-01 to TE-P5-07; clean-tree check at f952eff).
- Held-out evidence: heldout-run-01.md, labels-import-01.md, heldout-eval-01.md, heldout-eval-02.md.
- Decisions: decisions-01 to decisions-22, open-findings-01.md.

## 2. Phase 5 gate items (README:1240-1247)
| Gate item | Evidence | Verdict |
|---|---|---|
| Exact-ID conflicts do not silently merge | ConflictPolicySuite, ExactIdPrecedenceAdversarialSuite, RecordKeyStageArxivBaseIdAdversarialSuite (T1 and step-3 CONFLICT); RV-P5-10 §1 "ACCEPTED never carries conflicts"; all passed in the clean-tree check at f952eff. Held-out quantitative evidence is withheld in this public derivative; consult the unchanged source record through the approved private evidence channel. | accept |
| LLM can abstain | AdjudicationSuite, AdjudicationAdversarialSuite (strict accept/reject/abstain schema, prompt v2); passed at f952eff. No live model (OA-1) | accept |
| Evaluation examples traceable to source records | er-gold-membership.jsonl (sha256 22779ca0…f8f4): per member sourceIdA/sourceIdB, generatingRunId run:7ae22ee9…159a, seed heldout-v1-20261115 (heldout-run-01.md); evaluator `membership run=` equals the pipeline run (section 4) | accept |
| Gold labels are human-reviewed | labels-import-01.md: my statements; 240/240 labels equal to the CSV; blind view only | accept |
| Precision and recall reported separately | The evaluation method reports precision and recall separately by entity kind and match path. Exact held-out results are withheld from this public derivative; see the unchanged source record for private review. | accept |
| Reviewer verifies no label leakage into matching logic | CR-P5-07 APPROVE at f952eff (review-report.md) | accept |

## 3. Held-out pipeline run (D10(i))
- runId run:7ae22ee957b7b55ef7d19d8eb2545913b55ee14388a4aaba7a2442f412d7159a; codeVersion a961035.
- summary.txt sha256 746ffdc025654de9020e20cfa3cab9c2ae59f51bea0f2f902e67f1b6232b44fe (original and archive copy).
- Archive: ~/Desktop/ScholarChain-heldout-attempt-1-20261115.

## 4. Held-out evaluation (D15(c))
- Run 1: `time sbt -Dsbt.supershell=false goldSetEvalHeldout`, run by me in a regular Terminal with Claude Code exited. Exit status `[success]`. Its output was lost before any metric was recorded (heldout-eval-01.md).
- Run 2, reported in sections 5 and 6: `time sbt -Dsbt.supershell=false goldSetEvalHeldout; echo "exit=$?"`, same Terminal procedure after the full pre-run checklist, unchanged inputs at HEAD f952eff. Result: `exit=0`. Disclosure of the rerun: heldout-eval-02.md.
- Inputs at HEAD f952eff:
  - membership 22779ca065cfb78f120e35e107c60f4fea15bb05ee998801c4e864dd2e21f8f4 (freeze record matches)
  - config c7862073c9d14032353139eb15e926e60e4b993ae176397891033cd12aa3f227
  - queue 4f296b19dcfa974a9b8894f5a2a9e17736d3c28978e47aa97efdeb8011be8102 (checked by me before run 2; the evaluator does not print it)
  - labels 0e267648b153047e0708781e9e335249b2d02bb3b94868acc6592b9bd32b692a
- Run 2 provenance lines, copied from my private export (no metrics):

```text
[info] goldSetEvalHeldout: plain batch sbt with stdout and stderr on a terminal (JDK 17.0.17 (Eclipse Adoptium))
[info] goldSetEvalHeldout: git_head=f952eff83888b6fb786062de49ae204e056be3e8 git_worktree=dirty
[info] goldSetEvalHeldout: clean tree in build.sbt project modules tests datasets gold (decision D3(b)): 539 tracked files without index flags; no modified, staged, deleted, or untracked path; 12 ignored path(s), all on the allow set (datasets/downloads/, gold/entity-resolution/heldout-v1/er-gold-labels.jsonl, modules/core/target/, modules/entity-resolution/target/, modules/hadoop/target/, modules/rag/target/, modules/services/target/, modules/spark/target/, modules/storage/target/, project/project/target/, project/target/, tests/target/); .gitignore committed and unmodified
[info] goldSetEvalHeldout: config gold/entity-resolution/heldout-v1/er-config.conf only (GoldSetEvalMain prints its sha256)
[info] goldSetEvalHeldout: forking scholarchain.entityresolution.evaluation.GoldSetEvalMain /Users/taufeeqpatel/Desktop/ScholarChain/gold/entity-resolution/heldout-v1/er-gold-membership.jsonl /Users/taufeeqpatel/Desktop/ScholarChain/gold/entity-resolution/heldout-v1/er-review-queue.jsonl /Users/taufeeqpatel/Desktop/ScholarChain/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl --manifest-sha256-file=/Users/taufeeqpatel/Desktop/ScholarChain/gold/entity-resolution/heldout-v1/er-gold-membership.jsonl.sha256 --expect-split=heldout in /Users/taufeeqpatel/Desktop/ScholarChain (classpath 11 entries; bound 60s; no stdin; child output straight to the terminal, not through the sbt log)
[info] goldSetEvalHeldout: final held-out gold metrics reported to the terminal (GoldSetEvalMain exit 0)
[success] Total time: 1 s, completed Nov 15, 2026, 6:30:05 PM
evaluation split=heldout: final held-out gold metrics only with the recorded freeze (--manifest-sha256-file) and at least the minimum of human-reviewed members
config file=/Users/taufeeqpatel/Desktop/ScholarChain/gold/entity-resolution/heldout-v1/er-config.conf sha256=c7862073c9d14032353139eb15e926e60e4b993ae176397891033cd12aa3f227
config overrides=none
manifest sha256=22779ca065cfb78f120e35e107c60f4fea15bb05ee998801c4e864dd2e21f8f4 freeze=matches file=/Users/taufeeqpatel/Desktop/ScholarChain/gold/entity-resolution/heldout-v1/er-gold-membership.jsonl.sha256
labels file=/Users/taufeeqpatel/Desktop/ScholarChain/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl sha256=0e267648b153047e0708781e9e335249b2d02bb3b94868acc6592b9bd32b692a
expect-split=heldout matches the manifest
membership run=run:7ae22ee957b7b55ef7d19d8eb2545913b55ee14388a4aaba7a2442f412d7159a blocking=er-blocking.v2
policy=er-policy.v8 blocking=er-blocking.v2 features=er-features.v2
settings policy=er-policy.v8 blocking=er-blocking.v2 features=er-features.v2 accept=0.85 reject=0.2 llmBand=[0.4,0.7] prompt=er-adjudication-prompt.v2 model=none (no adjudicator ran) config=applied
```

- `git_worktree=dirty` reflects my untracked `docs/agent-runs/phase-05/*` evidence files, which are outside the D3(b) scope. The scoped check above found no modified, staged, deleted or untracked path in `build.sbt project modules tests datasets gold`.
- Cross-checked against the recorded values by my extraction script: manifest sha256, labels sha256, config sha256, membership run (all equal; manifest freeze=matches).
- Not recovered: all of run 1's output and its numeric exit status. The evaluator prints no run ID of its own; `membership run=` names the held-out pipeline run.
- Decision: I accept this evaluation as final.

## 8–10. Held-out evaluation — publication redaction

This is a sanitized public derivative of the historical human-gate record. Label-derived held-out counts, ratios, class balances, subgroup breakdowns, threshold-grid results, and statements about error-example availability are withheld. The exact original evidence remains in the unchanged source record for approved private review. This redaction neither reruns the evaluation nor changes the original human decision.

The evaluation method distinguishes entity kinds and match paths and reports precision, recall, coverage, unresolved cases, conflicts, and generation failures separately. These are methodological descriptions, not public claims about the withheld results.

The policy freeze, configuration hashes, manifest provenance, human execution procedure, and rerun disclosure recorded in the preceding sections remain the provenance basis. Numerical development results and synthetic tests elsewhere are separate from this withheld held-out evaluation.

The bounded evaluation requires broader independent evaluation before generalization. Public metric claims must not be inferred from the omission of private results.

## 7. Disclosures and manual changes
- D3(c): confirmed. No policy, feature or threshold change was made after either held-out run.
- Label title-text warnings on CSV rows n=67, 110, 148, 190 and 219: not assessed. On these rows the spreadsheet title text differs from the blind view (labels-import-01.md); row alignment was confirmed by dataset, endpoint kind and source record ID. I have not independently verified that none of the differences affected the labels. Disclosed as a limitation.
- Held-out rerun: run 1's output was lost; run 2 used unchanged inputs (heldout-eval-02.md).
- Manual steps: I regenerated the freeze record (a bare digest, fixed before 01f1799). All gold/** files and both held-out evaluation runs and the held-out pipeline run were done by me in Terminal.  The orchestrator redacted member ID prefixes in labels-import-01.md. The orchestrator's scratchpad scripts are not committed. The public derivative withholds those quantitative evaluation results; the unchanged source record preserves the original evidence.
- Residual label copies in ~/Desktop/ScholarChain-labeling-heldout-v1/: kept, outside the repository and unfenced. The private evaluation export ~/Desktop/ScholarChain-Phase5-Heldout-Evaluation-2026-11-15.txt is also kept outside the repository.
- D5 = L1: the held-out labels stay gitignored; no build change.

## 8. Remaining risk
- BE-6 accepted for Phase 5 (decisions-21).
- CR-P5-07 minor findings (LeakageStructureSuite I/O bans; no Hadoop cross-module leakage test): deferred to a later work order (entity-resolution-engineer and test-engineer).
- CR-P5-07 unverified concerns: the a961035 byte identity of earlier reviews; the "7 of 8 sampled" note at er-policy.v8.json:164.
- Clean-tree sbt check at f952eff: exit 0; 921 passed, 1 skipped (SampleQueueProbeSuite, no local-pipeline output in a fresh clone).
- Evaluation limitation: this bounded held-out study requires broader independent evaluation before its findings can be generalized. Exact label-derived evidence is retained for private review.
- 47 unsure labels (20% of reviewed) are excluded from every metric, 38 of them in researcher/probabilistic.
- Empty evaluation paths: llm-adjudicated (both kinds), and work deterministic-bibliographic and probabilistic, have no pairs.
- Work coverage 0.7708 because 11 of 48 definitive work pairs ended as CONFLICT.

## 9. Acceptance
The original source records the human's Phase 5 decision and limitations. This public derivative withholds label-derived evaluation details and makes no new acceptance decision.
Accepted commit: f952eff83888b6fb786062de49ae204e056be3e8 (the tree that passed the clean-tree sbt check and was evaluated; recorded in commit.txt). The follow-up commit records evidence only.
This record was drafted with orchestrator assistance; the decisions are mine.
