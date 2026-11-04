# Phase 4 human gate

Decider: Taufeeq Patel
Date: 2026-11-03

## Decision
I accept Phase 4: the Hadoop MapReduce pipeline (modules/hadoop), the Phase 4
blocking-key and deterministic match-feature functions (modules/entity-resolution),
the Phase 4 core contracts (modules/core), the adversarial and cross-module tests
(tests/**), and the bounded EMR smoke-run script (scripts/run-bounded-pipeline.sh),
together with repair-01 and repair-02.

The final code-reviewer verdict is APPROVE (re-check after repair 02,
review-report.md). README:1198 "Code reviewer returns APPROVE" is met.

## Checks I ran personally (my terminal, repository root)
- sbt check
  Result: success, completed Nov 3, 2026, 11:17:28 PM Central.
  core 252, entityResolution 56, hadoop 67, tests 32: 407 passed, 0 failed,
  0 errors, 0 skipped, 32 suites.
  markTestRun wrote .claude/.last-test-run (git_head=8c679ad, worktree=dirty,
  suites=32, passed=407, skipped=0).
- bash -n scripts/run-bounded-pipeline.sh
  Result: exit code 0.
- chmod +x scripts/run-bounded-pipeline.sh, then confirmed -rwxr-xr-x.
- I reviewed git status. The changes are only under modules/core,
  modules/entity-resolution, modules/hadoop, tests/src/test,
  scripts/run-bounded-pipeline.sh and docs/agent-runs/phase-04. No build outputs
  are included.
- Not run by me: shellcheck (not installed), jar tf / MANIFEST.MF inspection of
  the job jars, the smoke script in any mode, any AWS command.

## Reviewer-reported behavior (code-reviewer, read-only; not run by me)
- Review 1 (initial Hadoop): REQUEST_CHANGES. Findings H1-H6; dependencies C1-C9.
- Focused review 2 (core contracts): APPROVE, no blockers. Integration tasks
  a-i tracked.
- Final review (work order 7): REQUEST_CHANGES with one documentation blocker,
  B1. job-contracts.md claimed without evidence that the real DBLP dump has no
  comments, CDATA or processing instructions.
- Re-check after repair 02: APPROVE. B1 and the bridge wording are closed.
- Reported by reading code:
  - every n(n-1)/2 coauthor pair is emitted, with no suppression anywhere;
    positions match mention IDs; oversize teams are counted and flagged;
  - provenance is on every curated record type;
  - ORCID source-work-id always carries its issuing source and is never
    treated as a global identifier;
  - H1-H6, C1-C6, C9 and tasks a-j are closed;
  - the entity-resolution functions are pure and locale-independent, with no
    scores, thresholds or decisions;
  - the adversarial tests drive the real pipeline across all six README
    categories;
  - the smoke script fails closed, makes no AWS calls in dry-run, and never
    deletes, terminates or scales;
  - no ownership violations.
- The reviewer ran no commands.

## Agent-reported results (not run by me)
- Local bounded pipeline over all committed samples, with real
  entity-resolution keys (hadoop-engineer):
  - largest team 26, largest pair expansion 325, total pairs 364;
  - largest blocking group 5 (researcher) and 6 (work);
  - 119 MatchCandidates.
- Adversarial runs (test-engineer):
  - teams of 0 to 1000 authors emitted exactly n(n-1)/2 pairs: 509,504 in
    total, 499,500 for the 1000-author work;
  - test-engineer found defects D1-D3 in the DBLP reader; hadoop-engineer
    fixed them in repair-01.
- build-engineer: sbt packageJobs built both job jars with no merge conflicts.
  The jar contents were inferred from sbt records; no jar was listed.
- These counts are consistent with my sbt check; the counter values themselves
  are agent-reported.

## Decisions made in this phase
- Pair suppression is removed. Every required n(n-1)/2 pair is always emitted;
  oversize teams are counted and flagged only.
- ORCID source-work-id is preserved with its submitting-source context
  (SourceLocalWorkId on WorkRecord v3). It is never an exact identifier.
- WorkRecord is now v3 and CoauthorPairCandidate v2; earlier versions are
  rejected. No earlier version was ever committed.
- Entity resolution in Phase 4 is limited to versioned blocking keys
  (er-blocking.v1) and deterministic features (er-features.v1). No Phase 5
  scoring, thresholds, decisions or LLM review.
- The temporary six-argument pair builder was removed after Hadoop migrated.
- The DBLP venue subset is NOT decided. It remains a run-manifest parameter;
  the smoke script refuses functional runs until I decide it.

## Manual changes (disclosed)
- chmod +x scripts/run-bounded-pipeline.sh (executable bit only; content
  unchanged).
- I ran sbt check and bash -n in my own terminal. The audit hook reported my
  sbt check marker update as UNATTRIBUTED. The orchestrator acknowledged it
  through the documented procedure, and the report listed only
  .claude/.last-test-run. My chmod may be reported the same way.
- Work order 4 (test-engineer) was interrupted twice by account usage limits
  and resumed from saved state each time. No content was changed by hand.

## Accepted deferrals
- Prerequisite before Phase 5 (owner entity-resolution-engineer, with
  test-engineer):
  - Unicode format characters inside names (ZWJ, ZWNJ, zero-width space, word
    joiner, soft hyphen, BOM, bidi marks) are folded to spaces and split family
    keys (TextFolding.scala:70-81), causing blocking recall loss;
  - UnicodeSuite:161-165 does not assert this;
  - this is the first Phase 5 work order.
- Optional items deferred:
  - stale v2 test name and v2Fields (domain-engineer);
  - smoke-script trap window and lock-before-trap (infra-engineer);
  - malformed non-publication records counted as skipped (hadoop-engineer);
  - no block-key length cap (entity-resolution-engineer);
  - job-contracts.md nits at 182-184 and 200-201 (hadoop-engineer);
  - DblpInputFormat.scala:159-161 Scaladoc DTD assumption without the doc's
    qualifier (hadoop-engineer).
- C8: the sbt localPipeline alias stays inactive. The local pipeline runs
  inside sbt check.

## Required before any AWS execution (open)
1. Full-snapshot DBLP markup scan of the exact input bytes. Owner undecided.
   Any non-zero count needs my recorded decision. No full-snapshot scan exists
   yet.
2. DOCTYPE gap: refuse-if-present does not refuse DOCTYPE-only input
   (run-bounded-pipeline.sh:753). Owner infra-engineer.
3. EMR step MainClass vs jar manifest Main-Class. The class name may be passed
   twice and the driver would exit.
4. Operator IAM must allow the head-object absence check (ListBucket/GetObject
   on the pipeline-runs prefix).
5. Verify AWS CLI/API flags and field names against current docs, including
   put-object --if-none-match and the CLI version.
6. Run shellcheck on scripts/run-bounded-pipeline.sh and record the result.
7. Ratify the hard caps and the 10 USD cost cap. The estimate covers the step
   window only.
8. Typesafe Config 1.4.3 is bundled unshaded; verify EMR classpath and
   classloader order.
9. The Scala 2.13 and 3 libraries in the Spark jar versus EMR's Scala 2.12
   Spark (F2, before Phase 6).
10. Giant-team performance at EMR scale is not measured.
11. Open decisions: G6 (script operator), the environment list, the owner and
    path of run configs and run manifests, raw-zone upload, and acceptance of
    the script's submission=<manifest sha256>/ write location.

## Remaining risk
- The XML reader has a documented, untested residual case in which a split read
  and a whole-file read can disagree. Markup longer than maxRecordBytes is
  treated as unclosed. The pinned snapshot's body markup is unverified.
- The DBLP DTD entity set (Latin-1 only), ORCID small-file and gzip handling,
  and EMR runtime compatibility are unverified.

## Commit record
The accepted commit hash will be recorded in commit.txt in a follow-up commit.
Phase 5 has not started.
