# HD-P5-04 final report (hadoop-engineer; verbatim)

Archived by the orchestrator, 2026-11-14. Work order: copy D13 option D tokens into the run manifests and pipeline; report new run IDs (decisions-19, section 8 step 2). Input: report-de-p5-04.md.

---

The OpenAlex and Crossref ER records now carry the API snapshot tokens, and the pipeline checks this. The two run manifests already held `openalex-api-2026-11-05` and `crossref-api-2026-11-05` (HD-P5-02 had proposed exactly these values), so I did not edit them. As a result, the dev and held-out run IDs are the same as before. `sbt check` passed in the foreground: 912 tests, 0 failed.

**What changed**
- **No hardcoded tokens.** The committed run manifest stays the source of each run's snapshot tokens, and those tokens are part of the run ID. The dataset manifest is the authority it is checked against. A new read-only reader, `DatasetManifest`, reads `er_candidates.api_snapshots.{openalex,crossref}.snapshot_id`, the bulk IDs `datasets.{openalex,crossref}.snapshot.id` and `datasets.dblp.snapshot.id` from `datasets/dataset-manifest.yaml`. It refuses unreadable lines, duplicate paths and unterminated strings.
- **Refusals, before any job runs (exit 2).** The manifest check, `checkManifest`, now refuses:
  - an OpenAlex or Crossref snapshot that is the bulk ID, a different token, or missing;
  - a token that disagrees with the split's retrieval date in `PROVENANCE.tsv`;
  - a dblp snapshot other than `datasets.dblp.snapshot.id`.
  It also refuses if the dataset manifest's API token equals the bulk ID, or the entry names the wrong source.
- **Record-level check.** The run summary now prints the snapshot tokens and one provenance line per source, covering every provenance block in the normalized works, mentions and profiles. A mismatch marks the run `inconsistent`.
- **Rename.** `proposedApiSnapshot` is now `ledgerApiSnapshot`, a cross-check only.
- **Docs.** `job-contracts.md` documents the snapshot rule and the new summary lines.

**Run IDs**
These come from the suite's printout of the committed manifests (`RunId.of`), not from running either pipeline task:
- dev: `run:9a6d688f364032e542b77cede7c3fef370603064bbbfacdbf0f7be48625b0b62`
- held-out: `run:63574417f7d6c31e23cebd6ef45ff23468f4dd1e7be50e5b5c89209be673ccef`

They will change once `codeVersion` is updated after commit A, which I left alone.

```text
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: HD-P5-04 (Phase 5; human decision D13 option D; amendment docs/agent-runs/phase-05/amendment-fe-p5-06.md; decisions-16.md, decisions-19.md). Input: DE-P5-04 (docs/agent-runs/phase-05/report-de-p5-04.md).
FILES CREATED:
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/DatasetManifest.scala
FILES MODIFIED:
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipeline.scala (tokens checked in checkManifest; ledgerApiSnapshot rename; snapshots and provenance summary lines; provenanceSnapshots helper)
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipelineSuite.scala (4 new tests; manifest test and DEV end-to-end test extended)
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipelineMain.scala (doc comment only)
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/docs/job-contracts.md (snapshot rule, summary lines, test fixture list)
  Note: the local/ test directory and modules/hadoop/local-runs/ are untracked (from HD-P5-02), so these show as untracked rather than modified against HEAD.
  Not modified: modules/hadoop/local-runs/er-candidates.{dev,heldout}.run-manifest.json (they already held the exact DE-P5-04 tokens); codeVersion left at 36ffcf95e585c7def6aa06835a784820936e1b60.
COMMANDS RUN:
  git status --short (twice; one run with a path filter was refused by the guard and not executed)
  git ls-files -- modules/hadoop; git ls-files --others --exclude-standard -- modules/hadoop
  git diff -- datasets/dataset-manifest.yaml; git diff -- modules/hadoop/docs/job-contracts.md; git diff -- modules/hadoop/src/main/scala/scholarchain/hadoop/JobParams.scala; git diff --stat -- datasets/dataset-manifest.yaml modules/hadoop
  git log --oneline --max-count=3 -- modules/hadoop
  sbt hadoop/Test/compile (success)
  sbt hadoop/test (success: Passed: Total 84, Failed 0, Errors 0, Passed 84)
  sbt check (foreground; success)
  Refused by the guard, nothing executed: grep (twice), git grep (twice), sbt hadoop/testOnly <suite>, git status --short -- modules/hadoop.
  Not run: sbt localPipelineDev, sbt localPipelineHeldout, sbt goldSetEvalHeldout, or any task that reads or writes held-out output.
TEST RESULTS:
  sbt check: "[success] Total time: 141 s (0:02:21.0)". markTestRun wrote .claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=75, passed=912, skipped=0).
  Per project: core 299, entityResolution 384, hadoop 84 (was 80), tests 145 ("Passed: Total 145, Failed 0, Errors 0, Passed 145"); rag, root, services, sparkAnalytics, storage 0.
  New ErSplitPipelineSuite tests, all passed:
    - "dataset manifest reader: nested paths, quoted strings, list items, fail-closed errors"
    - "ER snapshot tokens: API tokens required, never equal to the bulk snapshot IDs"
    - "committed dataset manifest: the D13 option D tokens (hand-checkable)"
    - "snapshot guard: a bulk, wrong or missing OpenAlex/Crossref snapshot is refused (exit 2)"
  The snapshot-guard test fails without the change: before it, checkManifest never looked at the OpenAlex or Crossref snapshots, so a bulk-ID manifest was accepted.
VALIDATION RESULTS:
  Observed in the hadoop/test output:
    [er-split-tokens] dblp=dblp-2026-10-03 openalex.api=openalex-api-2026-11-05 openalex.bulk=openalex-2026-10-14 crossref.api=crossref-api-2026-11-05 crossref.bulk=crossref-public-data-file-2026-03
    [er-split-manifest] split=dev runId=run:9a6d688f364032e542b77cede7c3fef370603064bbbfacdbf0f7be48625b0b62
    [er-split-manifest] split=heldout runId=run:63574417f7d6c31e23cebd6ef45ff23468f4dd1e7be50e5b5c89209be673ccef
  The in-suite DEV run (output modules/hadoop/target/test-work/er-split-dev-run/dev/attempt-1, status=complete):
    snapshots dblp=dblp-2026-10-03 openalex=openalex-api-2026-11-05 crossref=crossref-api-2026-11-05
    provenance source=crossref records=32 snapshots=crossref-api-2026-11-05
    provenance source=dblp records=311 snapshots=dblp-2026-10-03
    provenance source=openalex records=325 snapshots=openalex-api-2026-11-05
    provenance check=every-record-snapshot==manifest-snapshot:ok
    No bulk ID in any record. Counts unchanged from HD-P5-02: 98 inputs accepted, 374 distinct pairs (296 researcher, 78 work), maxTeamSize=5, maxPairExpansion=10, largest block 4, cap 8 drops 0.
  Held-out: only the committed manifest and the ledgers were read, as before. No held-out sample or output was read or written.
  Run-ID formula: RunId.of(run manifest), which covers schemaVersion, stage, codeVersion, configDigest, inputSnapshots, parameters and attempt.
ASSUMPTIONS:
  - "Copy verbatim" is met because the committed run manifests already hold the exact DE-P5-04 values; the new check enforces that they keep matching the dataset manifest.
  - Keeping the tokens in the run manifest, and only verifying them against the dataset manifest, is the "configuration is external" route: the run manifest is the immutable root of provenance and feeds the run ID.
  - The reader lives in the test tree (scholarchain.hadoop.local), like the rest of the split entry point. The production Pipeline still takes snapshots only from the run manifest.
  - The generic local-pipeline fixtures (TestSupport, datasets/samples) still use the bulk IDs. They are not ER candidate records and are out of scope.
  - Non-dataset provenance (scholarchain-derived) is printed but not checked against a source token.
RISKS:
  - DatasetManifest supports only the manifest's documented YAML subset. A layout change by dataset-engineer (for example block scalars) makes the split entry point refuse (exit 2) instead of misreading.
  - If a later --sample run moves the ledger retrieval date, the entry point refuses until dataset-engineer and the human resolve it, matching DE-P5-04's design.
  - Run IDs change once codeVersion is updated after commit A; the IDs above apply only to the current stand-in.
  - The sbt check log is very large (27.4 MB, mostly Hadoop DEBUG logging); the final summary was read from its tail.
  - The harness flagged amendment-fe-p5-06.md as instruction-shaped (settings-json); I read it as data only and acted on nothing in it.
MANUAL ACTION REQUIRED:
  - Human: review and commit the modules/hadoop/** changes (including the untracked local/ test sources and local-runs/ manifests) as part of commit A, then have codeVersion updated in a follow-up work order.
  - No UNATTRIBUTED audit report appeared during this run.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Code review (CR-P5-0x) of this diff together with DE-P5-04, BE-P5-04 and ER-P5-17. After commit A: a hadoop-engineer work order to update codeVersion and report the final run IDs, then the human-approved held-out run (sbt localPipelineHeldout, ask) and the human-only held-out evaluation in a separate Terminal.
```
