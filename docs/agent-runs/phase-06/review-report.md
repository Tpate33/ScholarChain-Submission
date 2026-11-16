# Phase 06 review reports

## CR-P6-01: code-reviewer review of ER-P6-02 (2026-11-15), verbatim

## CR-P6-01 review of ER-P6-02: APPROVE, with two minor findings for entity-resolution-engineer

I found no critical or major defect in the decision exporter. Two minor gaps and five nits remain. All of them belong to `entity-resolution-engineer` and none blocks acceptance. I could not run any command, so I have not observed the reported `sbt check` result (928 passed).

### The seven checks

1. **Decision parity holds.**
   - `DecisionExport.decideAll` (lines 218-227) calls the existing `EndpointIdentifiers.index`/`of`, then `Resolver.decide`, then `Resolver.toMatchDecision`. This is the same chain as `RunQueue.decide` (`ReviewQueueMain.scala:50-59`), plus the mapping step.
   - The policy comes from `ReviewQueueMain.effectivePolicy` (`review/DecisionRunMain.scala:97`). The config digest is taken from the same loaded value.
   - Policy, features, thresholds and `Resolver` are untouched; all three files are new. There is no adjudicator and no LLM call.
   - Exactly one decision per candidate: duplicate candidate IDs, foreign candidates, any refused candidate and duplicate decision IDs each refuse the whole run.
   - Output goes through the core `Json.encode`, which validates against the MatchDecision v2 contract first (`Contract.scala:147-150`).
   - There was no earlier writer of MatchDecision in any main source tree; I confirmed this with Grep.
2. **H9 holds.**
   - `decidedAt` comes only from the manifest. It must be an ISO-8601 instant ending in `Z` (lines 129-138, 181, 207).
   - No clock or randomness is read anywhere in the module's main sources: no `Instant.now`, `currentTimeMillis`, `nanoTime`, `Random` or `UUID`.
   - Output order is fixed: candidates are sorted by UTF-8 bytes, parameters are a `SortedMap`, and the run ID hashes inputs in sorted order (`StableIds.scala:570-601`).
   - The random staging name never reaches the published bytes; the directory is renamed to `run=<hex>`.
3. **Publication is all-or-nothing on a local file system.**
   - All files are written into a staging directory inside the output root, using create-new and force, with `_SUCCESS` last.
   - The target is checked before writing and again just before the single rename. A refused run is checked before anything is written, so not even the output root is created.
   - Staging is deleted in a `finally` block. `deleteTree` does not follow symlinks.
   - The readers (`publishedRuns`, `readPublished`) accept only a directory named exactly `run=<64 hex>` that holds `_SUCCESS`.
   - The S3 limitation is honestly scoped: the code says "local file system" and the report says S3 has no atomic rename. The race of a rename onto an empty `run=<hex>` directory is disclosed and loses no data.
4. **Provenance is validated.**
   - The manifest's stage, config digest, input snapshot set, exact parameter set, policy, blocking and feature versions, the `decidedAt` format and `inputRunId` are all checked against the curated run's ID. Every candidate's `provenance.runId` must equal `inputRunId` (lines 209-212).
   - `attempt` and `codeVersion` go into the run ID, and so into every decision ID. That is consistent with the claim "same manifest and inputs give the same bytes", and the report discloses that decision IDs change per run.
5. **The tests are meaningful.**
   - Parity is checked against the direct `Resolver` call and also against the separate `ReviewQueue.row` mapping (`SystemDecision`).
   - Reruns are compared byte for byte across two output roots.
   - An injected I/O failure on the work part file is tested, along with simulated crash leftovers, no-overwrite and 16 mismatch refusals.
   - Fixtures are synthetic. The probe reads the existing Hadoop test output under `modules/hadoop/target/test-work/local-pipeline/curated-a`, the same pattern as `SampleQueueProbeSuite`. No gold or held-out path is used.
   - One gap: no test shows that configured thresholds are actually applied (finding m2).
6. **Boundaries hold.**
   - The imports are core plus the module's own packages.
   - `decisions/DecisionExport.scala` has no `review`, `evaluation` or gold reference once comments are stripped, and `LeakageStructureSuite` was not changed.
   - No hardcoded bucket, endpoint or threshold; all paths come from arguments.
   - All new files are under `modules/entity-resolution/**`. The tests write only under `modules/entity-resolution/target/test-work`.
   - The leftover `decisions/DecisionRunMain.scala` holds a single blank line and is harmless.
7. **No security or data-loss issue**, apart from m1 below.

### Findings (all owned by entity-resolution-engineer)

- **m1 (minor): the reader treats a missing part file as zero decisions.**
  - Location: `modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionExport.scala:361-364`, used at 392-393.
  - Failure input: a `run=<hex>/` directory that has `_SUCCESS` and `_run-manifest.json` but no `decisions/work/part-00000.jsonl`. A partial copy or sync where `_SUCCESS` arrives before the parts (the expected S3 path) produces exactly this.
  - Impact: `readPublished` returns `Right` with no work decisions, so a consumer silently loses data. The reader also never checks that the manifest stage is `er.decisions`.
  - Fix: require both part files to be regular files and check `m.stage == Stage`.
- **m2 (minor): no test shows that configured thresholds are applied.**
  - Location: `modules/entity-resolution/src/test/scala/scholarchain/entityresolution/decisions/DecisionExportSuite.scala:166-170`.
  - Failure input: the test config (0.85/0.2, band 0.4-0.7, from `PolicyFixtures.scala:243-246`) is identical to the defaults in `er-policy.v8.json:22-23`. The only non-default-threshold case (suite lines 487-494) is refused by the digest check before any decision is made.
  - Impact: if `review/DecisionRunMain.scala:97` regressed to `MatchPolicy.loadDefault()`, every test would still pass, and decisions would ignore the external configuration.
  - Fix: add a case with non-default thresholds and a matching digest, and assert that a candidate's outcome flips.
- **n1 (nit):** `DecisionExport.scala:244-245` silently drops decisions of kinds other than researcher and work. This cannot happen today, because `MatchPolicy.kind` (`MatchPolicy.scala:326-330`) refuses other kinds. Asserting that the researcher and work counts add up to the total would make it fail closed.
- **n2 (nit):** `DecisionExport.scala:317-331` never forces the staging directory or the output root to disk after the rename. Atomicity holds, but durability after a power loss is neither guaranteed nor claimed.
- **n3 (nit):** `codeVersion` is asserted by the caller and never verified, yet it feeds every decision ID. This is disclosed. Two different builds given the same manifest would claim the same run ID; the no-overwrite rule only protects within one output root.
- **n4 (nit):** `DecisionExport.scala:353` uses `Files.isDirectory`, which follows symlinks, so a symlink named `run=<hex>` would be listed as published.
- **n5 (nit):** the empty leftover file `modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionRunMain.scala` should be deleted before commit. That is a human action, since the agent has no delete command.

### Unverified concerns

- **Reported test counts:** I have not observed the `sbt check` result (928 passed); I cannot run commands.
- **Hidden staging names:** I did not verify whether Hadoop or Spark glob expansion (for example `root/*/decisions/...`) skips names starting with `.staging-`. The hidden-file filter is known to apply when a directory is listed, not necessarily to globs.
- **Probe flakiness:** the committed-sample probe reads the manifest at `DecisionExportSuite.scala:451-454` without an `assume` guard, after `CuratedRunReader.read`. If the Hadoop `LocalPipelineSuite` rewrites `curated-a` at the same time, the read could throw. This would be a flaky test, not a product defect.
- **Exception type:** an atomic move onto a non-empty directory may throw a generic `FileSystemException` instead of `DirectoryNotEmptyException`. The outcome is still a refusal; only the message wording changes.
- **No split guard:** `DecisionRunMain` has no dev/held-out split guard, unlike `ReviewQueueMain diagnostics`. Its output holds no labels, and no agent can run it (there is no allowlist row).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-01 (Phase 6), read-only adversarial review of ER-P6-02
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: none run by the reviewer; the author's sbt check result (928 passed, 0 failed) is not observed by the reviewer
VALIDATION RESULTS: Read the work order (prompt-er-p6-02.md), the ER-P6-01 section 6 and ER-P6-02 report sections, the four files under review, ReviewQueueMain.scala, EndpointIdentifiers.scala, Resolver.scala (decide 420-451, toMatchDecision 990-1034), core MatchDecision.scala (build and contract 380-619), StableIds.scala (RunId, MatchDecisionId, MatchCandidateId), Contract.scala (Json.encode), CuratedRunReader.scala, LeakageStructureSuite.scala, PolicyFixtures (configText, workDir), MatchPolicy.kind, er-policy.v8.json thresholds. Grep confirmed: no clock, random or UUID use in entity-resolution main sources; no other MatchDecision writer in any main source. No gold/**, held-out, or target/ path was read or searched.
ASSUMPTIONS: The four files under review are exactly the untracked files named in the git status snapshot; no other file changed (git could not be run to confirm).
RISKS: m1 (a reader that accepts an incomplete run) matters most once the S3 path lands; m2 leaves configuration application untested in this exporter.
MANUAL ACTION REQUIRED: Human deletes the empty modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionRunMain.scala before commit and re-runs sbt check.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Orchestrator routes m1, m2 and n1-n4 to entity-resolution-engineer (optional, non-blocking), then human acceptance.
VERDICT: APPROVE
FINDINGS:
- m1 minor, DecisionExport.scala:361-364 and 392-393, entity-resolution-engineer: readPublished treats a missing part file as zero decisions and does not check the manifest stage.
- m2 minor, DecisionExportSuite.scala:166-170 (with PolicyFixtures.scala:243-246 and er-policy.v8.json:22-23), entity-resolution-engineer: no test proves configured thresholds are applied.
- n1 nit, DecisionExport.scala:244-245, entity-resolution-engineer: decisions of other kinds are silently filtered out (unreachable today).
- n2 nit, DecisionExport.scala:317-331, entity-resolution-engineer: no directory force after rename.
- n3 nit, DecisionExport.scala:144-183, entity-resolution-engineer: codeVersion unverified but feeds decision IDs (disclosed).
- n4 nit, DecisionExport.scala:353, entity-resolution-engineer: symlinks are followed when listing published runs.
- n5 nit, decisions/DecisionRunMain.scala, human action: empty leftover file to delete.
UNVERIFIED CONCERNS: sbt check counts not observed; Hadoop/Spark glob handling of .staging- names; possible probe flake at DecisionExportSuite.scala:451-454; exception type of an atomic move onto a non-empty directory; DecisionRunMain has no split guard (no labels in its output, no agent can run it).
```

---

## CR-P6-02: code-reviewer review of ER-P6-02 repair 01 regions (2026-11-15), verbatim

## CR-P6-02: APPROVE. m1, m2, n1 and n4 are closed; n2 and n3 are documented honestly

I found no critical or major defect in the repair regions. I have four nits, all owned by `entity-resolution-engineer`, and none blocks acceptance. I could not run any command, so I have not observed the author's `sbt check` result (934 passed, 0 failed, 77 suites).

### 1. m1 is closed
- **`layoutProblem`** (`DecisionExport.scala:390-409`) checks each item in turn and stops at the first problem:
  - the `run=<64 hex>` name;
  - that `dir` is a directory, without following links;
  - that `_SUCCESS` and `_run-manifest.json` are regular files;
  - that `decisions/`, `decisions/researcher/` and `decisions/work/` are directories;
  - that both part files are regular files.
  
  Every check uses `NOFOLLOW_LINKS` (lines 376-377).
- **`publishedManifest`** (416-440) decodes the manifest, then requires `stage == er.decisions`, then requires `runDirName(RunId.of(m)) == dir name`. Any exception becomes a `Left`.
- **Both readers use it.** `publishedRuns` filters on it (456), and `readPublished` goes through it before it decodes any line (474).
- **No missing file reads as zero rows any more.** `lines` (462-463) no longer has a fallback for missing files, and a race-deleted file throws and is caught (497-498).
- **A present zero-row part is accepted.** `""` splits to `[""]`, which is filtered out.
- **Not affected.** The publish path and the parity, rerun and refusal tests that CR-P6-01 approved are unchanged. `Kinds` is a `val` declared after `partition`, but it is only used inside defs, so there is no initialisation-order problem.

### 2. m2 is closed
- **The config differs only in the test.** The test config sets accept = 0.95 in test code only (`PolicyFixtures.configText(accept = 0.95)`). The `configText` defaults are still 0.85/0.2/0.4/0.7 (`PolicyFixtures.scala:243-246`), and `er-policy.v8.json:22-23` is still 0.85/0.2 with band 0.4-0.7. No main policy, feature or threshold changed. The threshold was tuned after reading a test report, but only as a test fixture value, which is acceptable.
- **The digest matches.** `configuredDigest` is checked to differ from the default digest and is put in the manifest (731, 761). The test also checks that `cp.thresholds.accept` is 0.95 and differs from the default.
- **The flipped-candidate guard is not tautological.** It compares `Resolver.decide` outcomes under the configured policy and under the default policy, both computed directly. It requires at least one flip and requires every flip to go from ACCEPTED to UNRESOLVED (743-760).
- **A fallback would be caught.** If `DecisionRunMain.scala:97` fell back to `MatchPolicy.loadDefault()`, three checks would fail:
  - `settingsLine(p)` would print `accept=0.85`, which breaks line 765;
  - the per-candidate equality at 772-775 would fail for the flipped candidate;
  - the `assertNotEquals` at 778 would fail.
  
  The digest is computed from `cfg`, not from `p` (line 108), so the digest check alone would not catch a fallback. The test still catches it.

### 3. n1 and n4 are fixed and fail closed
- **n1.** `partition` (263-283) refuses unless the researcher count plus the work count equals the total, and it names any decision of another kind.
- **n4.** Inside `run=<hex>` no symlink is followed. The only link-following left in these regions is `Files.isDirectory(root)` and `Files.list(root)` on the output root itself (451-453). The author discloses this.

### 4. n2 and n3 are documented accurately
The scaladoc at 104-112 makes no overclaim. It says atomicity holds, that durability after a power loss is not claimed, and that `codeVersion` is never verified.

### 5. Regression tests
Judged from the code, without reverting anything:
- **Would fail without their fix:** missing part, part as a directory, part as a symlink, undecodable manifest (through the `publishedRuns` assertion), wrong stage, the symlinked `run=<hex>`, and m2.
- **Would pass without the fix:** the missing `_SUCCESS` case and the zero-row case. The author says so.
- **Does not compile without `partition`:** the n1 test.
- **Fixtures:** all synthetic, written under `modules/entity-resolution/target/test-work`. No gold or held-out path is used.

### 6. Boundaries
- `DecisionExport.scala` imports only `java.*`, `scala.*`, `scholarchain.core` and `entityresolution.policy`.
- The changes are inside `modules/entity-resolution/**`.
- I could not confirm that `LeakageStructureSuite` is untouched without git. It is not among the changed files.

### Findings (all owned by entity-resolution-engineer)
- **nit 1: the run-ID/directory-name check has no test.**
  - Location: `DecisionExport.scala:435`.
  - Failure input: a complete run copied to `run=<other 64 hex>`.
  - Impact: if this check were removed, `readPublished` would return `Right`, because the decision check at 487 compares against the manifest's run ID, and no test would fail. The suite has no test that expects "does not hold run".
- **nit 2: a part file cut short at a line boundary reads as fewer rows.**
  - Location: `DecisionExport.scala:462-463` and 482.
  - Failure input: a partial copy that leaves a prefix of the file ending on whole lines.
  - Impact: neither the manifest nor `_SUCCESS` records a row count or checksum, so silent loss on copy or sync paths is still possible. Local `publish` itself is atomic.
- **nit 3: there is a gap between the layout check and the read.**
  - Location: `DecisionExport.scala:425` and 482.
  - Failure input: a part file swapped for a symlink after `layoutProblem` runs.
  - Impact: `Files.readString` follows links. This needs a concurrent writer in the output root, so it is low impact.
- **nit 4: the undecodable-manifest assertion is weak.**
  - Location: `DecisionExportSuite.scala:631`.
  - Failure input: none in the product. The expected substring `_run-manifest.json` would also match a layout refusal, so the case does not prove that the decode error is the reason. The `publishedRuns` assertion at 664 still has to hold.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-02 (Phase 6), read-only review of the ER-P6-02 repair 01 regions
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: none run by the reviewer; the author's sbt check (934 passed, 0 failed, 77 suites) was not observed by the reviewer
VALIDATION RESULTS: Read review-report.md (CR-P6-01), repair-01.md, the whole of DecisionExport.scala, DecisionExportSuite.scala 1-240 and 560-781, review/DecisionRunMain.scala 55-121, and PolicyFixtures.configText (225-274). Grep of entity-resolution main resources: er-policy.v8.json thresholds 0.85/0.2 with band 0.4/0.7. Grep in entity-resolution src: no other reader path still treats a missing file as zero rows, and no test expects "does not hold run". No gold/**, held-out or target/ path was read or searched.
ASSUMPTIONS: The repair changed only the two files listed (git cannot be run to confirm); LeakageStructureSuite is unchanged since CR-P6-01.
RISKS: nit 2 (no row count or checksum) matters once runs are copied or synced to S3; the output root is still opened through symlinks (disclosed).
MANUAL ACTION REQUIRED: Human accepts or rejects n2 and n3 as documented limitations; human reviews and commits the untracked files.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human acceptance; nits 1-4 may be routed to entity-resolution-engineer (optional, non-blocking).
VERDICT: APPROVE
FINDINGS:
- nit, DecisionExport.scala:435, entity-resolution-engineer: the run-ID/directory-name check has no regression test.
- nit, DecisionExport.scala:462-463 and 482, entity-resolution-engineer: a part file cut short at a line boundary reads as fewer rows (no row count or checksum in the manifest or _SUCCESS).
- nit, DecisionExport.scala:425 and 482, entity-resolution-engineer: gap between the layout check and the read; readString follows symlinks.
- nit, DecisionExportSuite.scala:631, entity-resolution-engineer: the undecodable-manifest expected substring is too weak to prove the decode reason.
UNVERIFIED CONCERNS: sbt check counts not observed; that LeakageStructureSuite is untouched could not be confirmed without git; that config validation accepts accept=0.95 above llmBandUpper=0.7 is inferred from the author's passing run, not observed; the CR-P6-01 unverified concerns (.staging- glob handling, probe flake, exception type of an atomic move, no split guard) are unchanged.
```

---

## CR-P6-03: code-reviewer review of DE-P6-01 (2026-11-15), verbatim

## CR-P6-03: review of DE-P6-01 (core contracts C1-C5)

**Verdict: APPROVE.** I found no verified critical or major defect. There are 5 minor findings and 6 nits, all owned by domain-engineer, plus one routing note for entity-resolution-engineer (ER-P6-04).

I could not observe the author's `sbt check` (1037 passed, 0 failed, 81 suites). I have no Bash, so that result is unverified by me.

### Checks passed (verified by reading the files)

**1. Compatibility**
- The git status snapshot shows none of `Identity.scala`, `MatchDecision.scala`, `StableIds.scala`, `Provenance.scala`, `Collaboration.scala` or `build.sbt` modified. So `AuthorshipRecord` v1, `MatchDecision` v2, `RunManifest`, the ID formulas, `ResearcherMetrics` and the dependencies are unchanged.
- `WireContractSuite.scala:269-273` and `SchemaVersionSuite.scala:11-21,61-64` only add names and counts.
- `AuthorshipRecords.decode` (`AuthorshipV2.scala:147-171`) dispatches v1 and v2 by `schemaVersion`.
- **uPickle default omission is confirmed.** No `serializeDefaults` annotation is on `EntityResolutionConfig`; the only one in core is `Bibliographic.scala:121`. The digest at `ScholarChainConfig.scala:317` is a plain `upickle.default.write`.
  - An explicit file `null` gives None (`optChecked`, `ConfigLoader.scala:508-517`).
  - An empty environment value gives `Raw.NullValue` (`ConfigLoader.scala:449`), so the key is omitted and the digest is unchanged. This is tested at `ConfigLoaderSuite.scala:769-784`.
  - An empty environment value overrides a file value. That fails closed: `GroupingRunSettings.from` returns `MissingKey`.
- Precedence is unchanged (`resolve`, lines 277-308). The required-key guard (line 484) applies to both pattern alternatives.
- **Optional keys within config.v2 are safe for loading.**
  - Residual: an older build reading a config that sets the keys refuses them as unknown keys rather than as an unsupported version. That still fails closed.
  - This departs from the package text ("the config loader refuses a missing groupingPolicyVersion"). Refusal moved to `GroupingRunSettings.from`, consistent with "preserve config behavior". It is disclosed in the author's ASSUMPTIONS.

**2. Clarifications**
- Grouping and match policy versions must differ at record level (`Assignments.scala:150-157`) and at config level (`ScholarChainConfig.scala:218-224`).
- Provenance policy equals the grouping version, and its run differs from the decision run (`Assignments.scala:166-189`).
- A singleton has an ID and no supporting decision (`Assignments.scala:429-440`). An unresolved member has no ID or anchor and keeps at least one blocking decision (`Assignments.scala:355-361, 441-458`).
- Exactly one representative per canonical work (`AssignmentValidation.scala:179`). Teams and authorships come from the representative only (`AssignmentValidation.scala:405-414, 486-513`).
- `graphEligibility`: record rules (`Assignments.scala:618-651`) plus group rules (`AssignmentValidation.scala:185-193`). Every label for a DBLP work in a non-DBLP group is refused, and I found no bypass.
- Nothing is added for preprint linking, and there is no Spark metric contract (grep: no matches).

**3. C5 integrity**
- The verifier (`PartManifest.scala:281-351`) does no I/O.
- It detects truncation at a line boundary (rows, bytes, sha256) and mid-line truncation (adds the missing final LF), same-length corruption (sha256), missing, extra and duplicate observations, missing markers, and the wrong run.
- Observed path tricks fail as missing plus extra.
- The manifest refuses `..`, `.`, empty segments, backslashes, absolute paths, case-only twins and file-versus-directory clashes.
- Empty file: 0 rows and `EmptySha256`. rows ≤ bytes. A non-empty file has at least one row. `_run-manifest.json` must be listed as exactly one `run-manifest.v1` row.
- The Accumulator refuses reuse after `result` (`require`). Out-of-range offset or length throws from `MessageDigest.update` before any counter changes.

**4. Matches decision package section 5**
- The five-list partition is checked per record (`disjoint`) and across the dataset ("exactly two records, same role").
- Connectivity uses union-find over supporting edges.
- `matchConfidence` equals the minimum supporting confidence (`AssignmentValidation.scala:437-455`).
- `checkTeams`: `teamSize` counts every representative mention, unresolved included (the T_r/A_r/M_r basis). `researcherIds` holds identified mentions only (the C_r, edge and W_r basis).

**5. `matchPolicyVersion`**
- `er-policy-1.0.0` is the synthetic `Fixtures.erPolicy` (`Fixtures.scala:30`).
- The contract only requires a token that differs from the grouping version. `checkAgainstDecisions` compares it with each decision's `provenance.policyVersion`, which ER sets to `o.policyVersion` (`Resolver.scala:1001`).
- So `er-policy.v8` is neither rejected nor mislabelled.

**6. Tests**
- The new suites cannot compile without the new types. The C4 tests use the new fields, and the old loader refused the keys as unknown.
- The frozen-digest test (`ConfigLoaderSuite.scala:749-759`) is the disclosed regression guard.
- Fixtures are synthetic.

**7. Boundaries**
- New files are under `modules/core` only, with no new dependency.
- No scratchpad or temporary-file test remains. The only `Files.writeString` calls are older tests at `ConfigLoaderSuite.scala:284, 309, 731-732`.

### Findings (all owned by domain-engineer)

**F1 (minor): over-merge backstop missing.** `AssignmentValidation.scala:83-93`
- Only Supporting and Vetoed roles are checked against group membership; REJECTED and CONFLICT fall to `case _ => Vector.empty`.
- Failure input: a REJECTED decision between members m1 and m2 listed in both `rejectedDecisionIds`, with m1 and m2 sharing one researcherId through other supporting links. `checkResearchers` and `checkAgainstDecisions` both return empty.
- Impact: a grouping that merged across a direct REJECTED or CONFLICT decision (N1 bypassed) passes core validation, and the over-merged researcher reaches Spark.
- Fix: two lines, or the human first decides it as part of the pending B3 N1 approval.

**F2 (minor): a misclassified unresolved member can get a provisional ID.** `Assignments.scala:429-440`
- A singleton with pending, vetoed or conflict decisions is accepted with any anchor namespace. D1 allows that only for the `t1-anchored` case (orcid or dblp-pid).
- Failure input: a singleton with `pendingDecisionIds=[d]`, `anchorNamespace="member"`, and an sc-form ID. It is valid.
- Impact: an unresolved member that ER misclassified gets the provisional ID that H3-b rejected, which inflates researcher counts. The author's assumption leaves this to ER policy; the human should decide.

**F3 (minor): a dropped assignment goes undetected.** `AssignmentValidation.scala:496-511`
- `teamSize` is compared with the number of assignment records present. Mention positions are not checked to be `0..n-1`, although `WorkRecord` guarantees that (`Bibliographic.scala:80`).
- Failure input: grouping drops the assignment of position 1 on a 3-author representative, and the team says `teamSize=2`. It passes.
- Impact: a silent undercount of T_r, A_r and M_r (data loss not detected).

**F4 (minor): grouping settings change the decision run ID.** `ScholarChainConfig.scala:107-109, 317`, with `DecisionRunMain.scala:108`
- The digest covers the whole config, so setting `groupingPolicyVersion`, `decisionRunRoot` or `assignmentRunRoot` changes the `configDigest` of every stage that shares that config.
- Failure input: add `assignmentRunRoot` to the dev config. The decision run ID changes, and existing decision manifests are refused (`DecisionExport.scala:176`).
- Impact: a grouping-only output location becomes an input of the decision run ID, so a re-export is not idempotent across locations. There is precedent (buckets are already in the config). The human should decide whether to use a separate config or accept this.

**F5 (minor): worst-case complexity.**
- `AssignmentValidation.scala:32-35`: `keys.count(_ == k)` runs once per duplicated key. It is O(n·d), quadratic on a fully duplicated dataset (the failure path). Fix: use `_._2.size`.
- `AssignmentValidation.scala:294`: `ids.contains` is O(k²) for a member with k decisions (bounded by the block cap).
- `PartManifest.scala:175-182`: the file-versus-directory check is O(F²). This one is disclosed.

### Nits
- N1. `AssignmentValidation.scala:6` claims the checks never throw. But `checkAuthorships:386`, `checkTeams:497` and `checkRun:238` dereference `status` or `workId` on researcher records they do not validate, so a null throws an NPE. A precondition is documented.
- N2. `matchConfidence` is the minimum over the member's own supporting decisions. It does not show that the member's link to the rest of the group may be transitive (example 1). This is a disclosed assumption for the human to review.
- N3. `AssignmentValidationSuite.scala:254-260` only asserts the fixture, so it is nearly tautological.
- N4. The verifier does not tie the observed `_parts.json` bytes to the manifest it is given. Also, `recordSchemaVersion` must be a current schema, so auxiliary files (nested `_SUCCESS`, `.crc`, `summary.txt`) cannot be listed. This is a risk for the deferred Hadoop and Spark part manifests.
- N5. `RunLocation.contains` (`ScholarChainConfig.scala:141`) is case-sensitive. On a case-insensitive file system, `/R` and `/r/a` overlap without being detected.
- N6. `checkTeams` compares `unresolvedMentionIds` as sets, so their order is not frozen.

### Routing note for entity-resolution-engineer (ER-P6-04; not a DE-P6-01 defect)
- `DecisionExport.scala:339-342, 355` writes `Json.encode(manifest) + "\n"`, which is one compact LF-terminated row. That is compatible with C5, so no format change is needed for the run manifest. Empty parts (`toJsonl` gives `""`) match 0 bytes, 0 rows and `EmptySha256`.
- ER-P6-04 must emit `_parts.json` before `_SUCCESS` (line 358). It lists `_run-manifest.json` as run-manifest.v1 and both part files as match-decision.v2.
- It should reorder the writes to the G2 order (parts, then manifest, then `_parts.json`, then `_SUCCESS`); lines 355-357 write the manifest first. This matters for S3 copies, not for the local atomic rename.
- `readPublished` and `layoutProblem` (lines 390-499) must call `PartManifestVerifier`. Today `lines()` (lines 462-463) drops empty lines, tolerates a missing final LF, and extra files go undetected.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-03 (Phase 6): read-only adversarial review of DE-P6-01, core contracts C1-C5
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only; no Bash)
TEST RESULTS: not observed; the author's sbt check (1037 passed, 0 failed, 81 suites) cannot be verified by me
VALIDATION RESULTS: compatibility, clarifications 1-7, C5 verifier semantics, validator rules, the matchPolicyVersion handling and the module boundary all verified by reading the source; the "fails without the change" claim holds by compile dependency, except the disclosed frozen-digest regression guard
ASSUMPTIONS: the git status snapshot at session start reflects the reviewed tree; B3 (N1) and D1 are not yet ratified in their final form, which is why F1 and F2 are graded minor
RISKS: F4 couples config changes to upstream run IDs; C5's current-schema rule blocks listing auxiliary files in future Hadoop/Spark manifests; ER-P6-04 must add the verifier to readPublished, because today a truncated decision part with a missing final LF is read silently
MANUAL ACTION REQUIRED: Human: decide F1 (enforce N1 in core) and F2 (t1-anchor rule in core) together with B3/D1; decide F4 (config scope of the grouping keys); review the author's disclosed assumptions (rejectedDecisionIds, graphEligibility, the authorship v2 additions, minimum confidence, optional keys within config.v2)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance of DE-P6-01; optional domain-engineer repair of F1-F5; ER-P6-04 (entity-resolution-engineer) per the routing note
VERDICT: APPROVE
FINDINGS: domain-engineer: F1 minor AssignmentValidation.scala:83-93; F2 minor Assignments.scala:429-440; F3 minor AssignmentValidation.scala:496-511; F4 minor ScholarChainConfig.scala:107-109,317 (with DecisionRunMain.scala:108); F5 minor AssignmentValidation.scala:32-35 and :294, PartManifest.scala:175-182; nits N1-N6 as listed. entity-resolution-engineer (ER-P6-04 note, not a defect): DecisionExport.scala:355-358 and 390-499
UNVERIFIED CONCERNS: whether the config's entityResolution.policyVersion equals the effective policy's version (er-policy.v8). GroupingRunSettings.matchPolicyVersion is taken from config (ScholarChainConfig.scala:298), while decisions carry the policy file's version, so ER-P6-05 should take or check it against the decision run. Whether any AuthorshipRecord v1 was ever written (the author's own UNVERIFIED item). Compilation and the test pass themselves (not observable by me).
```

---

## CR-P6-04: code-reviewer review of DE-P6-01 repair 01 regions (2026-11-15), verbatim

## CR-P6-04: review of the DE-P6-01 repair 01 regions. Verdict: APPROVE

I found no critical or major defect. There is one minor finding (F5 in `PartManifest`: the fix is not linear, and it adds a memory blow-up on over-long paths) and three nits, all owned by domain-engineer. The F4 follow-up at `DecisionRunMain.scala:108` is correctly routed to entity-resolution-engineer. I have no Bash, so I have not observed the author's `sbt check` (1055 passed, 0 failed, 81 suites).

### 1. F1/M2 is closed
- **The rule.** `decisionReferences` (`AssignmentValidation.scala:150-157`) now refuses a REJECTED or CONFLICT decision whose two endpoints are in the same group. The group is the `researcherId` for researchers and `Some(canonicalWorkId)` for works. Pending decisions are still unconstrained (157).
- **No bypass.**
  - A decision listed on only one endpoint is refused by the `hits.size != 2` check (129).
  - A decision listed on neither endpoint is refused by `checkAgainstDecisions`, which requires every decision of the run on both endpoints in the list of its outcome (360-372).
  - Unresolved records have `groupOf = None`, so a REJECTED decision between a resolved and an unresolved member stays valid. That is the base fixture `dR3`, asserted at suite line 546.
- **No false refusal.** Separate groups and separate canonical works stay valid (suite 543-546, 588-594). Refusing endpoints that are joined through an ACCEPTED chain is exactly what the human approved.
- **The tests would fail without the fix.** Before the repair, these roles fell to `case _ => Vector.empty`, so the tests that assert `errs.size == 1` would have failed.

### 2. F2 is closed
- **Record rule.** `Assignments.scala:459-467` and `requiresT1Anchor`/`blockingDecisions` (299-305).
- **Dataset rule.** `checkSingletonAnchors` (`AssignmentValidation.scala:709-771`) checks three things:
  - the member asserts the anchor value itself;
  - no other supplied member asserts it, of any status;
  - no other record uses it as its anchor.
- **No path gives a misclassified unresolved member a provisional ID:**
  - `unresolved` needs at least one pending, vetoed or conflict decision (480-485).
  - Dropping those decisions from the lists is refused by `checkAgainstDecisions`.
  - Relabelling the member `singleton` while keeping them triggers the t1 rule.
  - `resolved` needs connected supporting ACCEPTED links.
  - A singleton with only REJECTED decisions, or none, is legitimately unanchored.
- **The producer list (692-707) is honest and, for F2, complete:** completeness and faithfulness of the identifier input, canonical form, collisions outside the supplied set, and a correct singleton status. Draft O6 (unresolved members count as colliders) is disclosed. It fails closed and fits the human's word "non-colliding".
- **Stricter rule without a schema bump: acceptable.** `Assignments.scala` is untracked, so `researcher-assignment.v1` was never committed. The only frozen singleton example (`AssignmentWireSuite.scala:58-62`) is anchored on `dblp-pid` with a pending decision and stays valid. The tags `orcid` and `dblp-pid` match `ExactIdScheme` (`Enums.scala:297,299`).

### 3. F3 is closed
- **The input is independent.** `checkCoverage` (608-685) compares against `ExpectedAuthors.of(WorkRecord)`, not against the assignments. The shape check ties `mentionIds(i)` to `MentionId.of(workId, i)`.
- **Missing final author:** detected (suite 721-730). Before this repair, teams and authorships accepted that set, and the test asserts so.
- **Mid gap:** detected (732-748).
- **Zero authors:** an empty list passes, and any mention assigned to that work is refused as extra.
- **Non-representative works:** their lists are optional but checked when supplied. That matches the human's scope ("representative source work").
- **Extra and duplicate assignments:** an extra is refused. A duplicate `memberId` is left to `checkResearchers`, which the scaladoc says.

### 4. F4 is closed in core
- **`digestWithoutGrouping` equals `digest` when no grouping key is set.** `withoutGrouping` sets the three keys to `None`, and uPickle omits default `None` values (CR-P6-03 confirmed there is no `serializeDefaults` on `EntityResolutionConfig`). `digest` (350) is unchanged, and the frozen base input test (`ConfigLoaderSuite` 752-759) still holds.
- **The grouping digest is well defined.** Its input is a case class with no Option fields and a fixed field order, frozen at suite 862-870.
- **Leaving the run roots out is safe.** The roots are locations. Upstream identity reaches the grouping run through `baseConfigDigest` and through the decision run ID that the assignments carry (`decisionRunId`). The decision run already keeps its output root out of the digest: the root is a path argument. Buckets count in the base digest only because that digest covers every value. I accept the author's choice, provided ER-P6-05 records the decision run ID as an input of the grouping run manifest.
- **Ownership of the follow-up.** `DecisionRunMain.scala:108` still calls `digest(cfg)`. That file is under `modules/entity-resolution/**`, so it is correctly routed to entity-resolution-engineer, together with `DecisionExportSuite.scala:168,730`. No other main-source caller exists (Grep).

### 5. F5
- **`AssignmentValidation` is linear and behaviour-preserving:** `duplicates` (88-91) and `roleIndex` (334-341).
- **`PartManifest` is behaviour-preserving** (it names the same first deeper path, suite 190-220). It is not linear (finding m1 below).

### 6. O9, the nits and the tests
- **O9.** Scaladoc only (64-68, 432-436, 540-544), and it matches the human text.
- **N1.** Documented (53-62).
- **N3.** Replaced by a meaningful O9 test (suite 254-287). It pins existing behaviour.
- **N6.** Compared in author order (588-593). `PublicationTeam` imposes no byte order, so this cannot cause a false refusal.
- **Tests.** Fixtures are synthetic. The F2, F3 and F4 tests depend on the new API, so they do not compile without the fix.

### 7. Boundaries
- All changed files are under `modules/core`. The git snapshot shows no change to `MatchDecision`, `RunManifest`, `StableIds` or `build.sbt`.
- `Provenance.scala` is unmodified, which is the cause of nit n1.

### Findings (domain-engineer)
- **m1 (minor): the F5 `PartManifest` fix is not linear, and it inflates memory on over-long paths.** `PartManifest.scala:162-168`, with the cost claims at 45-47 and 186-188.
  - Cause: the fold builds `b.substring(0, i)` for every `/` in every path. The work and the stored prefix text are O(L × depth) per path, which is O(F·L²), not O(F·L).
  - The index is also built over paths that `pathProblem` refuses as too long, so nothing bounds it.
  - Failure input: a `_parts.json` with one path of about 100,000 `/` characters. The distinct prefixes total about 5·10⁹ characters, so the verifier runs out of memory instead of returning "longer than 1024 characters". The old check used O(L) memory on the same input.
  - Impact: a corrupt or hostile manifest crashes the reader instead of being refused. There is no data loss.
  - Fix: index only paths where `pathProblem` is empty, and either correct the stated cost or use a sorted prefix search.
- **n1 (nit): stale scaladoc.** `Provenance.scala:150` still says `RunManifest.configDigest` is `ScholarChainConfig.digest`. That contradicts `ScholarChainConfig.scala:344-349` and `:371-377`.
- **n2 (nit): a null `workId` in the expected input can throw.** `checkCoverage` guards null entries and null `mentionIds`, but not a null `ExpectedAuthors.workId`. With two or more entries, `.sorted`, `sortedKeys` and `duplicates` compare that null and throw instead of returning a typed error (`AssignmentValidation.scala:631, 653, 662, 684`).
- **n3 (nit): wording about which works get expected lists.** The repair-02 note says to pass `ExpectedAuthors.of` "for every representative work". A whole work dropped from both the assignments and the expected input goes undetected. Passing the lists of every curated work turns that into an "has no work assignment" refusal. Producer guidance for ER-P6-05.

### Unverified concerns
- I did not observe the author's `sbt check` (1055 passed, 0 failed, 81 suites); I cannot run commands.
- I could not compare the replaced lookup code with its old text without git, so the F5 equivalence in `AssignmentValidation` is judged from the current code and the F5 test.
- `MemberIdentifier.fromMention` ignores a `sourceAuthorId` with the ORCID scheme. If any parser emits an ORCID there with `orcid = None`, that member's ORCID would not count as a collision. I did not check the Hadoop parsers.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-04 (Phase 6), read-only review of the DE-P6-01 repair 01 regions
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: none run by the reviewer; the author's sbt check (1055 passed, 0 failed, 81 suites) not observed
VALIDATION RESULTS: Read prompt-de-p6-01-repair-01.md, repair-02.md, review-report.md (CR-P6-01 to CR-P6-03), the whole of AssignmentValidation.scala, Assignments.scala and ScholarChainConfig.scala, PartManifest.scala 30-200, Collaboration.scala 1-62, Bibliographic.scala 1-90, Provenance.scala 135-165, DecisionRunMain.scala 90-114, AssignmentValidationSuite 240-300 and 480-820, AssignmentContractSuite 1-150, AssignmentWireSuite 40-69 and 135-154, ConfigLoaderSuite 740-900, PartManifestSuite 180-230. Grep across modules for digest callers and for ExactIdScheme/ExternalIdScheme tags. No gold/**, held-out or target/ path read or searched.
ASSUMPTIONS: The session-start git status reflects the reviewed tree; ER-P6-05 will record the decision run ID as an input of the grouping run manifest (this is what makes it safe to leave the run roots out of the grouping digest).
RISKS: m1 (verifier out of memory on an over-long path); checkCoverage and checkSingletonAnchors protect nothing until ER-P6-05 calls them with complete inputs; until DecisionRunMain.scala:108 changes, grouping keys still change the decision run digest.
MANUAL ACTION REQUIRED: Human: confirm leaving the run roots out of the grouping digest, unresolved members counting as colliders (draft O6) and the stricter record rule without a schema bump. Orchestrator: route m1 and n1-n2 to domain-engineer (non-blocking); route the DecisionRunMain.scala:108 and DecisionExportSuite.scala:168,730 change, and n3 as guidance, to entity-resolution-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance; then the F4 follow-up by entity-resolution-engineer; then ER-P6-04 and ER-P6-05.
VERDICT: APPROVE
FINDINGS:
- m1 minor, PartManifest.scala:162-168 (claims at 45-47, 186-188), domain-engineer: the directory-prefix index is O(F·L²) and unbounded for paths over MaxPathLength; a ~100k-'/' path exhausts memory instead of being refused.
- n1 nit, Provenance.scala:150, domain-engineer: stale scaladoc (RunManifest.configDigest is said to be ScholarChainConfig.digest).
- n2 nit, AssignmentValidation.scala:631/653/662/684, domain-engineer: a null ExpectedAuthors.workId with two or more entries throws instead of returning a typed error.
- n3 nit, repair-02.md producer note, entity-resolution-engineer (ER-P6-05 guidance): pass the expected lists of every curated work, not only representatives, so that a dropped work is detected.
UNVERIFIED CONCERNS: sbt check counts not observed; old-versus-new F5 lookup equivalence judged without git; fromMention ignores an ORCID-scheme sourceAuthorId (Hadoop parser output not checked).
```

---

## CR-P6-05: code-reviewer review of DE-P6-01 repair 02 (2026-11-15), verbatim

## CR-P6-05: review of the DE-P6-01 repair 02 diff. Verdict: APPROVE

m1, n1 and n2 are all closed, and I found no critical or major defect. There are two nits, both owned by domain-engineer and neither blocking. I cannot run commands, so I have not observed the author's `sbt check` (success, 1058 passed, 0 failed, 81 suites).

### 1. m1 is closed (`PartManifest.scala` 45-50, 165-175, 193-203)
- **Only valid paths enter the index.** Line 168 is `validPaths = paths.filter(pathProblem(_).isEmpty)`, and both the fold (170-175) and the lookup (196-203) use only `validPaths`.
- **Over-long input costs nothing extra.** `pathProblem` checks `path.length > MaxPathLength` (line 84) before it splits anything, so an over-long path is refused in O(1). The other places that touch every path are linear: `fileChecks`, `strictlyAscending`, the lower-case `groupBy`, and the `filter` at 168. For the 100,000-'/' path, no unbounded work or memory is left.
- **Invalid paths still get their own message.** `fileChecks` (line 123) is unchanged and still runs over every entry.
- **Overlap detection among valid paths is preserved.** I checked the claim that every directory prefix of a valid path is itself valid against each rule:
  - Length: a prefix is shorter than the path.
  - Backslash and leading '/': a prefix has neither if the path has neither.
  - Segments: a prefix's segments are a subset of the path's segments, so none is empty, `.`, `..` or outside the alphabet. The cut is always at a '/', so no partial segment appears.
  - Case collisions and marker files: these are not part of `pathProblem`, so they are not used as filters. Case twins and marker paths stay in `validPaths` and are still refused separately (lines 124 and 184-191).
  - So every valid file that is a directory of another valid file is still found.
- **The bound is truthful.** A valid path has no leading '/' and no empty segment, so d <= (L-1)/2. Each prefix copy and hash costs at most L, so the work is O(L·d) per path. The total is O(F·L·d), at most F·L·L/2 characters. The scaladoc says plainly that the check is not linear.
- **"First listed deeper path" is unchanged** whenever every path is valid: it is the same fold in listed order. The existing F5 test at suite lines 190-220 pins this.

### 2. n1 is closed (`Provenance.scala` 149-153)
The scaladoc matches the code:
- `ScholarChainConfig.digestWithoutGrouping` (line 378) is `digest(withoutGrouping(c))`.
- `digest` (line 350) is documented at 347-348 as being replaced by `digestWithoutGrouping` for non-grouping stages, with `GroupingRunSettings.configDigest` for grouping.
- `GroupingRunSettings.configDigest` is at line 287.
- CR-P6-04 verified that the two digests are equal when no grouping key is set.

### 3. n2 is closed (`AssignmentValidation.scala` 624-626, 637-641)
- **No path reaches a comparison.** The null-`workId` branch runs after the null `expected`, entry and `mentionIds` guard (line 635) and before all of the following: `StableId.check`, the `toMap`, `.distinct.sorted` (662), `sortedKeys` (671) and `duplicates` (693).
- **The result is typed.** The branch returns one indexed `MissingField("expected[i].workId")` per null entry. This holds with one entry or with several.
- **Hidden errors:** the early return also suppresses the coverage errors for that same input. The input is already a refused producer bug, and the existing null-entry guard returns early in the same way, so this does not matter.
- **A malformed non-null `workId`** is caught by `StableId.check` (648). It is skipped by the shape step's per-mention check. The later sorts compare non-null strings, so nothing throws.

### 4. Tests
- **PartManifestSuite 222-252.** Without the fix, the old fold would build prefixes of the 100,000-'/' path of about 5e9 characters, so the test would run out of memory or time out. With the fix it returns at once. The assertions check the length refusal and that no directory error appears. The combined case checks that the valid `works` overlap is still reported.
- **PartManifestSuite 254-271.** This is a regression guard, and it would pass on the old code too. I checked the arithmetic: `"d/" * 508` is 1016 characters, plus 8 `x` gives 1024, and `fileAtDir` is the 1015-character directory.
- **AssignmentValidationSuite 785-809.** `nullFirst` and `twoNulls` would throw without the guard, because `.sorted` would compare the null (line 662). The single-null and malformed cases are coverage only.
- All fixtures are synthetic and in memory, with no I/O. The author reports 0.004 s for the m1 test; I did not observe that.

### 5. Boundaries
- All five changed files are under `modules/core`.
- There is no change to the wire format, IDs or schema versions. `PartManifest`, `PartFile` and `RunManifest` fields are unchanged, and the Provenance edit is scaladoc only.
- I found no new behaviour beyond the three items. The only effect on output is that a manifest already refused for an invalid path no longer also gets directory errors that name the invalid path.

### Findings (domain-engineer, both non-blocking)
- **nit a: `PartManifest.scala:193-194`.** The comment says the kept path is "the one `paths.find` named before". That is now true only when every path is valid.
  - Failure input: an unsorted manifest that lists `works` and then an over-long `works/...` path before `works/part-00000.jsonl`. The old code named the over-long path; the new code names the valid one.
  - Impact: none on whether a manifest is accepted, because that manifest is refused anyway. The comment could say "first listed valid deeper path".
- **nit b: `PartManifestSuite.scala:223-224`.** The comment gives "about 2.5e9 and 5e9" in the opposite order to the paths listed on lines 225-226 (`manySlashes` is about 5e9, `deep` about 2.5e9). Impact: wording only.

### Unverified concerns
- I did not observe the author's `sbt check` (1058 passed, 0 failed, 81 suites).
- I have no git access, so I judged the diff boundaries from the session-start git status and the cited regions. The untracked `modules/entity-resolution/**` files in that status come from earlier work orders, and I did not review them here.
- That the old code runs out of memory on the m1 test input comes from my reading of the code, not from a run.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-05 (Phase 6), read-only review of the DE-P6-01 repair 02 diff (CR-P6-04 m1, n1, n2)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read and Grep only)
TEST RESULTS: none run by the reviewer; the author's sbt check (success, 1058 passed, 0 failed, 81 suites) not observed
VALIDATION RESULTS: Read /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/repair-03.md, review-report.md section CR-P6-04 (318-415), the whole of /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/PartManifest.scala, Provenance.scala 125-174, AssignmentValidation.scala 595-699, PartManifestSuite.scala 1-275, AssignmentValidationSuite.scala 700-819; Grep of digest and configDigest definitions under modules/core/src/main/scala. No gold/**, held-out or target/ path read or searched.
ASSUMPTIONS: The session-start git status reflects the reviewed tree; the regions listed in repair-03.md are the whole of this diff.
RISKS: The overlap index is still O(F·L·d), at most about 512K characters per valid path; this is documented and bounded. checkCoverage still assumes non-null WorkAssignment.workId, as its precondition says.
MANUAL ACTION REQUIRED: Human: accept or reject this repair. Orchestrator: optionally route nits a and b to domain-engineer (non-blocking), then continue with ER-P6-04.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance of DE-P6-01 repair 02; then ER-P6-04 (entity-resolution-engineer)
VERDICT: APPROVE
FINDINGS:
- nit a, PartManifest.scala:193-194, domain-engineer: the comment says the kept deeper path matches the old paths.find; that holds only when every path is valid. Failure input: an unsorted manifest with an over-long deeper path listed first. Impact: none on acceptance, because that manifest is refused anyway.
- nit b, PartManifestSuite.scala:223-224, domain-engineer: the comment gives the prefix-size estimates (2.5e9, 5e9) in the opposite order to the paths on lines 225-226. Impact: wording only.
UNVERIFIED CONCERNS: sbt check counts not observed; diff boundaries judged without git from the session-start status; the old code running out of memory on the m1 input is inferred from reading the code, not observed.
```

---

## CR-P6-06: code-reviewer review of ER-P6-04 (2026-11-15), verbatim

CR-P6-06: review of ER-P6-04 (`_parts.json` integrity manifest, verified reads, F4 digest scope)

VERDICT: APPROVE. I found no verified critical or major issue. I found one minor test gap and four nits. All belong to entity-resolution-engineer, and none blocks.

I could not observe `sbt check`, because the reviewer role has no Bash. The author's result (success, 1064 passed, 0 failed, 81 suites) is unverified by me.

**What I checked against the files**

1. **Write order** (`DecisionExport.scala:416-447`).
   - Inside staging, the writes run in this order: the researcher part, the work part, `_run-manifest.json`, then `_parts.json`, then `_SUCCESS`.
   - `_parts.json` is built at 426-429 from `readNoFollow` over the staging files. Those are bytes read back from disk, not the in-memory text.
   - The target is checked twice: before staging at 415 and again after `_SUCCESS` at 436. Then comes the single `ATOMIC_MOVE` at 438.
   - Every failure path ends in `Left`, and the `finally` at 447 deletes the staging directory. Staging names start with `.staging-`, which readers never list.
   - So no failure step leaves anything consumable.

2. **Verified reads** (`verifiedRun`, 559-616).
   - Order: the layout check (which needs a regular `_SUCCESS`, `_run-manifest.json` and `_parts.json`), then `observe`, then a strict UTF-8 decode of `_parts.json`, then a check that the listed set is exactly the three expected files with their schemas, then `PartManifestVerifier.verify` against the run ID the directory name gives.
   - Only after that is the run manifest decoded from the kept bytes. Then come the stage, the run ID against the directory name, and `checkRunManifest`.
   - `observe` (521-547) walks without following links. It refuses symlinks, other non-regular entries and unexpected directories. It reads each file once, and the bytes it returns are the bytes it verified, so there is no second read.
   - Marker handling is correct: the core contract forbids listing a marker, the verifier requires both markers to be present, and it excludes them from the extra-file check.
   - The verifier checks row, byte and sha256 counts and the final LF.
   - `rows` (642-653) uses strict UTF-8 and refuses an empty row.
   - I traced every route I could think of: a cut at a line boundary, a cut mid-line, same-length corruption, a missing file, an extra file, an extra directory, a symlink, `_SUCCESS` arriving first, a stale run ID, a renamed directory (caught by the verifier's run-ID check), and a pre-ER-P6-04 run. Every one ends in `Left`.
   - The only `Right` with changed content needs a recomputed, self-consistent `_parts.json`. That is forgery, not truncation; `_parts.json` is an integrity record, not authentication.

3. **The disclosed gap** (`publishedRuns` lists a self-consistent copy that has an empty row). I judge it acceptable.
   - Truncation, a partial copy or `publish` cannot produce it: `toJsonl` (316-323) never emits an empty line, and any change to a part changes its sha256.
   - `readPublished` refuses it.
   - The documentation at 618-623 says rows are decoded only by `readPublished`, which every consumer must use.
   - No main-code caller of `publishedRuns` exists outside `DecisionExport`.

4. **F4.**
   - `DecisionRunMain.scala:113` passes `ScholarChainConfig.digestWithoutGrouping(cfg)`.
   - The run ID comes from the manifest alone, so the digest decides only acceptance, not the run ID.
   - No other main code in entity-resolution (or any module except core) calls `ScholarChainConfig.digest`.
   - The F4 test (suite 1059-1113) fails without the change: the grouped config would be refused on `configDigest`. It also asserts that a config without grouping keys gives `digest == digestWithoutGrouping`, and that the trees are byte-identical across the three configs.

5. **Tests.**
   - The 14 refusal cases are real (suite 888-1012).
   - `remanifest` (799-808) uses only core `ObservedFile` and `PartManifest.build`, so it does not depend on the exporter.
   - The fixtures are synthetic.
   - The earlier tests were changed only where behaviour changed by design: lines 170, 339-348, 402-403, 690-691 and 734.

6. **Scope.**
   - The `decisions` package imports only core and policy, not review, so `LeakageStructureSuite` is respected.
   - Neither file under `decisions/` uses a gold or held-out path.
   - The decision logic (`decideAll`, `Resolver` calls, `partition`, `toJsonl`) does not touch the new I/O code.
   - No agent can run `DecisionRunMain`: `runMain` matches no allowlist row (`probes.tsv` 565-566 show the `runMain` block).

7. **Resources.** Channels and walk streams are closed in `finally` blocks: `readNoFollow` 378-390, `observe` 525-528, `publishedRuns` 629-634.

FINDINGS (all owned by entity-resolution-engineer):
- **m1 (minor; a test passes when required behaviour is broken).** `DecisionExportSuite.scala:832-871`, the G2 write-order test.
  - The work order explicitly requires `_parts.json` to be computed from bytes read back from staging. The production code at `DecisionExport.scala:426-429` does this; I checked it.
  - Failure input: change `publish` to hash `text.getBytes(UTF_8)` in memory. The test still passes, because the default writer writes exactly the given text, so memory and disk agree.
  - Impact: a regression that drops the read-back, and with it the guarantee that the manifest describes what reached disk, would go undetected.
  - Fix route: inject a `FileWriter` whose bytes on disk differ from its argument (for example, it appends one extra row to a part). Then assert that `_parts.json` records the on-disk counts and sha256, and that `readPublished` accepts the run.
- **n1 (nit; test coverage).** Suite 396-405 injects an I/O failure only at the work-part write.
  - There is no injection at `_run-manifest.json`, `_parts.json` or `_SUCCESS`, or during the read-back. All of these share the catch and `finally` at `DecisionExport.scala:443-447`, so I confirmed by reading that they publish nothing; no test exercises them.
  - Impact: low.
- **n2 (nit; operations, fail closed but undocumented).** `DecisionExport.scala:536-544` together with the verifier's extra-file rule.
  - Failure input: opening a published run directory in macOS Finder, or copying it through SMB or FAT. This creates `.DS_Store` or `._*` files inside it.
  - Impact: the run is then refused by both readers ("extra file not listed" / "unexpected ..."), with no hint why. Refusing is the correct behaviour; document it next to the "may be deleted by hand" note at 119-126.
- **n3 (nit; robustness).** `DecisionExport.scala:567` and 372-392: `_parts.json` and `_run-manifest.json` are kept in memory with no size bound.
  - Failure input: a huge `_parts.json` in a run directory.
  - Impact: an `OutOfMemoryError`. `NonFatal` does not catch it, so `publishedRuns` crashes its caller instead of skipping that directory. The risk is low for a local file system.
- **n4 (nit; performance, disclosed).** `publishedRuns` (625-636) reads and hashes every file of every candidate run on every listing. This is accepted for now; reconsider it before larger decision runs or the S3 readers.

UNVERIFIED CONCERNS:
- **Rename onto an empty directory (from JDK behaviour, not in the repository).** On POSIX, `Files.move` with `ATOMIC_MOVE` calls `rename(2)` directly. I believe `rename(2)` silently replaces an existing empty directory, so an empty `run=<hex>` created between the check at 436 and the move at 438 could be replaced; no data would be lost.
- **Non-empty target error message.** For a non-empty target, ENOTEMPTY may surface as a generic `FileSystemException` ("nothing published under ...") rather than the "refusing to overwrite" message. Either way nothing is overwritten. Both points predate this change.
- **Byte-identical regeneration.** The claim that regenerating gives decision and run-manifest bytes identical to earlier exports cannot be checked against earlier code: these files were never committed (they are untracked), so no prior version exists to compare. The reproducibility test compares two runs of the current code only.
- **Remaining TOCTOU.** An intermediate directory swapped to a symlink between the walk and `readNoFollow` would redirect a read. The bytes read are still hashed and must match `_parts.json`, so this cannot yield `Right` on mismatching content, only from a different location.
- **`sbt check`.** Not observed by me.

COMPATIBILITY REPORT ASSESSMENT: The author's report is accurate where it can be checked.
- Exports without `_parts.json` are refused (`layoutProblem` 477-478; test 1049-1057).
- The run ID is unchanged for the same manifest.
- The no-overwrite rule is real: the check at 415 also applies to a legacy directory.
- The regeneration path is `DecisionRunMain <manifest> <curated run> <new output root>`, and only the human can run it, in a terminal.
- A manifest that recorded the full digest of a grouped config is refused (test 1108-1112).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-06 (Phase 6), read-only adversarial review of ER-P6-04 (DecisionExport _parts.json emission and verified reads, DecisionRunMain F4, DecisionExportSuite)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool); Read, Grep and Glob only
TEST RESULTS: not observed. Author reports sbt check success, 1064 passed, 0 failed, 81 suites; unverified by the reviewer
VALIDATION RESULTS: read /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionExport.scala (full), /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/DecisionRunMain.scala (full), /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/decisions/DecisionExportSuite.scala (full), /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/PartManifest.scala, ScholarChainConfig.scala 270-380, Contract.scala encode/decode, decision-package-01.md G1-G3, the work order item 2, and the ER-P6-04 report. Grep confirmed: no other ScholarChainConfig.digest call in main code; no gold or held-out reference in the decisions package; runMain is not allowlisted (probes.tsv 565-566). Nothing under gold/** or target/** was read or searched
ASSUMPTIONS: core PartManifest, ObservedFile, PartManifestVerifier and digestWithoutGrouping are correct as approved earlier; the ER-P6-04 changes are the current contents of the three untracked files
RISKS: S3 and Spark-side verification is still outstanding (G3: spark-engineer, infra-engineer); full hashing on every listing; fail-closed refusal of runs that Finder has touched
MANUAL ACTION REQUIRED: Human: decide whether m1 and the nits are repaired now or carried forward; review and commit the untracked files
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance of ER-P6-04; optional repair of m1 (read-back test) by entity-resolution-engineer
VERDICT: APPROVE
FINDINGS: m1 minor (suite 832-871, the read-back is not tested); n1 nit (suite 396-405, failure injected at one step only); n2 nit (DecisionExport.scala 536-544, .DS_Store refusal undocumented); n3 nit (DecisionExport.scala 567 and 372-392, unbounded in-memory marker files, OOM not caught); n4 nit (DecisionExport.scala 625-636, full hashing on every listing). Owner of all: entity-resolution-engineer
UNVERIFIED CONCERNS: ATOMIC_MOVE may replace an empty target directory created inside the check-to-rename window, and ENOTEMPTY may be reported without the refusal message (JDK behaviour, predates this change); byte-identity with earlier exports cannot be checked (no committed prior version); intermediate-directory TOCTOU redirects only the location, not the verified content; sbt check not observed
```

---

## CR-P6-07: code-reviewer review of ER-P6-05 (2026-11-15), verbatim

**Verdict: APPROVE** for CR-P6-07, my focused read-only review of ER-P6-05 (grouping layer er-grouping.v1). I found no critical or major defect. There is one minor finding and two nits, all owned by `entity-resolution-engineer`. I could not observe `sbt check`, and I could not diff the tracked LeakageStructureSuite.

## What I checked, item by item

**1. Veto rules: they match the approved definitions.**
- **N1** fires only on REJECTED or CONFLICT (`Grouping.scala:476-483`). UNRESOLVED never vetoes.
- **T1, BD, SW, HN and ST** all have the form "some cross pair has property P" (`Grouping.scala:509-607`). That makes them monotone, and a vetoed pair stays vetoed as groups grow.
- **Researcher BD override:** the pair itself must agree on a T1 scheme (orcid or dblp-pid). This equals Resolver `t1a` and step 6a (`Resolver.scala:561, 766`).
- **Work BD override** (`workT1Accepts`, `Grouping.scala:560-568`) is exactly the inverse of the pairwise blocks: `doiBlocked` (`Resolver.scala:550-554`), `arxivBlocked` (`:545-546`), `isPreprintDoi` (`:413`) and `bothPreprint` (`Corroboration.scala:144`).
- **HN** applies only between DBLP-sourced members that carry a homonym number, on the same base name (given parts plus family words). A number against no number never fires.
- **ST** fires only for a pure preprint against a pure published record.
- **O11:** every rule runs and every code is recorded.
- **Endpoint self-check** (`Grouping.scala:631-646, 745-748`) runs before any merge and refuses with `grouping.invariant.endpoint-veto`.
- **Scheme lists:** `GroupingPolicy.checkAgainst` (`GroupingPolicy.scala:222-245`) requires the T1 and BD lists to equal the policy's `isTier1` and `blocksInferredAcceptance` sets, the same predicates the Resolver uses.
- No rule is stricter or looser than approved.

**2. Determinism.**
- The merge order (B2, `:461-473`) is path rank, then confidence descending, then sourceId and targetId by bytes. Duplicate pairs are refused, so the order is total and uses data only.
- Members are sorted. Union-find roots never reach the output.
- Anchors and collisions (C1, C2) are computed over all final groups, unresolved members included (`:790-836`).
- Statuses (D1) are at `:850-864`, including t1-anchored.
- The work representative order (E1) is at `:911-916`. An unknown namespace is refused (`:403-410`).
- There is no clock or randomness in the grouping logic. The determinism test covers reversed input order, a changed decision run ID (it asserts that the decision IDs change) and a changed assignedAt (`GroupingSuite.scala:489-525`).

**3. Inputs and provenance.**
- The decision run is read only through `DecisionExport.readPublished` (`GroupingRunMain.scala:165-168`).
- The decisions' `inputRunId` must equal the curated run ID (`Grouping.scala:271-275`).
- The manifest must hold exactly six parameters, including decisionRunId and inputRunId, each checked against the verified inputs.
- `configDigest` must equal `GroupingRunSettings.configDigest`, which leaves out the run roots (`ScholarChainConfig.scala:287-296`).
- The match policy is checked three ways: the decision manifest, every decision's provenance, and the effective policy.
- On the additions beyond the draft: the local-root enforcement and the s3 refusal (`GroupingRunMain.scala:85-118`), the exact candidate-set check, the unknown-namespace refusal and the T1/BD scheme equality are all sound and fail closed. I support all four.

**4. Validators.**
- All nine core validators run before publication (`Grouping.scala:1061-1095`).
- `checkCoverage` receives `ExpectedAuthors.of` for every curated work.
- `checkSingletonAnchors` receives every ORCID iD and DBLP pid of every member, unresolved members included.
- The producer checks (`:1113-1203`) cover the F2 list: complete and canonical identifier input, a superset check against `MemberIdentifier.fromMention` and the profiles' own assertions, one assignment per member, no anchor collision outside a group, supporting/vetoed consistency, and no unresolved member with its own usable T1 anchor.
- `group` publishes nothing unless `verify` returns empty.
- The draft's M2 gap is now closed in core (`AssignmentValidation.scala:150-156`).

**5. Publishing and reading (`RunDirectory.scala`).**
- Write order (G2): parts, `_run-manifest.json`, then `_parts.json` built from `readNoFollow` of the staged bytes (`:175-178`), then `_SUCCESS`.
- Publication is an atomic move with an exists-refusal. Staging is deleted on any failure.
- Verified reads require an exact layout, never follow a symbolic link, use the core verifier, and bind the run ID to the directory name. Every record's `provenance.runId` is checked (`GroupingExport.scala:111`).
- The m1 regressions (`DecisionExportSuite.scala:1117-1157`, `GroupingExportSuite.scala:192-230`) inject a writer that appends a row. They assert that the listed counts and hashes equal the bytes on disk and differ from the in-memory text, so both would fail if the read-back were replaced.

**6. O9 and the output for Spark.**
- Authorships exist for every identified mention on every representative.
- Teams exist only for `Counted` works with mentions. They come from the representative's own mentions, hold identified researcherIds only, set `teamSize` to all mentions and list unresolved mentions in author order (`:1013-1042`).
- The author's "Grouping output available for Spark" section is accurate, including the warning that enrichment-only authorships must be filtered out and the note that the output is not durable.

**7. Probe diagnostics.** The numbers are internally consistent:
- Researchers: 103 members = 99 mentions + 4 profiles; 5 + 2 + 72 + 24 = 103 members in 36 groups; 103 − 36 = 67 merges; resolved 98 + singleton 5 = 103; anchors 10 + 17 + 9 = 36.
- Works: 13 − 6 = 7 merges; counted 4 + member-of-counted 5 + enrichment-only 4 = 13; links merged 74 = 67 + 7 and redundant 43 = 40 + 3.
- The diagnostics are labelled unevaluated and make no quality claim.
- Collision handling is correct: both groups fall back to the next anchor and record the collision code.
- **Note only, not a policy change:** an ORCID iD held by two groups that no decision links is a strong sign of a missed candidate or an undecided pair. A future diagnostic could list each collided value with the decisions, if any, that connect the colliding groups.

**8. Tests.** Each veto test asserts that plain transitive closure would join the pair, so it fails without the rule. The fixtures are synthetic. The LeakageStructureSuite additions at lines 169-218 are new tests appended under an ER-P6-05 header. The visible lines 1-168 contain only Phase 5 checks.

**9. Boundaries.** The git status at session start shows no change to er-policy.v8, Resolver, MatchFeatures or any other tracked policy file. No gold or held-out path is touched. The probe reads only the Hadoop dev test output. There is no Spark, AWS or LLM use.

**10. Performance (flagged, not a blocker).** T1 and BD cost O(|G1|·|G2|) pair comparisons per link. Nearly every DBLP mention asserts a dblp-pid, so total cost per entity is roughly O(n²). Everything is held in memory in one JVM, and full-DBLP scale is unmeasured (H7).

## Findings

All three are owned by `entity-resolution-engineer`.

1. **Minor: the curated data is not tied to the recorded inputRunId.**
   - **Where:** `Grouping.scala:450-456`, `GroupingRunMain.scala:169-184`.
   - **Problem:** grouping receives only `candidateIds`. Unlike `DecisionExport.scala:251-254`, it never checks that the curated candidates, works or profiles carry `provenance.runId == curatedRunId`.
   - **Failure input:** a curated directory whose `_run-manifest.json` names run R while its `normalize/works` come from another run with the same candidate pairs. One example is a re-run with edited author lists on works that no candidate touches.
   - **Impact:** the run passes and publishes teams, coverage and authorships built from other data while recording inputRunId R. This is a false provenance claim. It needs a manual mix-up to happen.
   - **Fix:** pass the candidates (or their run IDs) into `GroupingInputs` and require the curated run ID on candidates, works and profiles.
2. **Nit: decision feature and blocking versions are unchecked.**
   - **Where:** `Grouping.scala:271-290`.
   - **Problem:** the decision manifest's `featureVersion` and `blockingVersion` are not compared with the effective policy or `MatchFeatures.Version`, although the vetoes reuse the er-features.v2 functions.
   - **Impact:** the risk is low. The endpoint self-check and the policyVersion check partly cover it.
3. **Nit: the group-size histogram silently omits unresolved members.**
   - **Where:** `Grouping.scala:1255, 1313`.
   - **Problem:** the `researcher groupSizes` line counts identified groups only. On the probe it is harmless because unresolved = 0.

## Unverified concerns
- I could not observe the author's `sbt check` (success, 1097 passed, 0 failed, 83 suites).
- I could not run `git diff`. "LeakageStructureSuite is purely additive" therefore rests on reading the file. "DecisionExport.scala main is unchanged" and "DecisionExportSuite only adds lines 1114-1159" are unverifiable, because both files are untracked.
- I did not read the Hadoop IdentityLink data, so the assumption that the er-features.v2 identifier functions are equivalent to it is unverified. The producer superset check against core's `MemberIdentifier.fromMention` does hold.
- The probe uses `assume`, so it is skipped when the Hadoop test output is missing or still being written by a concurrent test. A green `sbt check` does not guarantee the probe ran.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-07 (Phase 6), focused read-only review of ER-P6-05 (grouping layer er-grouping.v1)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only; no Grep under gold/ or target/)
TEST RESULTS: not observed. The author reports sbt check success, 1097 passed, 0 failed, 83 suites; the reviewer cannot run it.
VALIDATION RESULTS: items 1-10 of the work order checked against the files (see "What I checked" above); probe numbers reconciled by arithmetic.
ASSUMPTIONS: the git status at session start is current for tracked files; core contracts (AssignmentValidation, PartManifestVerifier, GroupingRunSettings) are as approved in earlier reviews.
RISKS: unevaluated grouping changes entity membership; pair-level BD may split OpenAlex-split authors; O(n^2) veto cost and single-JVM memory at scale (H7); the probe can be silently skipped; there are two PublicationTeam producers (M6, Spark must read assignment-run teams only).
MANUAL ACTION REQUIRED: Human: match-policy review of er-grouping.v1; decide whether minor finding 1 is repaired now or carried forward. Orchestrator: route findings 1-3 to entity-resolution-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human match-policy review and phase acceptance; optional repair of finding 1 by entity-resolution-engineer, then sbt check.
VERDICT: APPROVE
FINDINGS:
- [minor] Grouping.scala:450-456 and GroupingRunMain.scala:169-184. Only candidate IDs reach grouping, so curated candidates, works and profiles are never checked for provenance.runId == curatedRunId (DecisionExport.scala:251-254 does this). Failure input: a curated dir whose manifest names run R but whose data comes from another run with the same candidate pairs. Impact: publishes with a false inputRunId. Owner: entity-resolution-engineer.
- [nit] Grouping.scala:271-290. Decision manifest featureVersion and blockingVersion are not checked against the effective policy or MatchFeatures.Version. Owner: entity-resolution-engineer.
- [nit] Grouping.scala:1255, 1313. The researcher groupSizes histogram excludes unresolved members without saying so. Owner: entity-resolution-engineer.
UNVERIFIED CONCERNS: sbt check not observed; LeakageStructureSuite judged additive by reading only (no diff); DecisionExport.scala and DecisionExportSuite are untracked, so "unchanged / additive only" cannot be verified; Hadoop IdentityLink equivalence not read; the probe can skip silently through assume().
```

Orchestrator note (2026-11-15): `git diff --stat` on LeakageStructureSuite.scala shows 51 insertions and 0 deletions, which confirms the change is purely additive. `skipped=0` in the test marker shows the committed-sample probe was not skipped.

---

## CR-P6-08: code-reviewer review of ER-P6-05 repair 01 (2026-11-15), verbatim

**Verdict: APPROVE** for CR-P6-08, my read-only review of the ER-P6-05 repair 01 diff (the regions listed in repair-04.md). Finding 1 and both nits from CR-P6-07 are closed. No critical or major finding remains. There is one minor finding (the residual the author disclosed) and one nit, both owned by `entity-resolution-engineer`. I could not observe `sbt check`, and I could not diff the untracked files.

## What I checked, item by item

**1. Finding 1 is closed.**
- **The check covers every curated record grouping uses.** `Grouping.provenanceProblems` (`Grouping.scala:372-384`) checks every candidate, every work, every mention (`w.authors`) and every profile. It delegates to `DecisionExport.foreignProvenance`.
- **It compares against the right run ID.** The comparison value is the curated manifest's `RunId.of`. `Grouping.scala:290-294` separately requires the decision manifest's `inputRunId` to equal it, so the two are the same value.
- **There is no bypass.** `GroupingInputs` (`:96-108`) holds only works, profiles and candidates of the curated data. `GroupingRunMain.scala:172-186` passes exactly `CuratedRunReader.read(curatedDir)`'s works, profiles and `candidates.map(CuratedCandidate.of)`. Nothing else is loaded.
- **Nulls.** A null provenance counts as foreign (`Option(p).forall`, `DecisionExport.scala:244`).
- **Nothing is published on a mismatch.** `manifestProblems` is the first step of `group` (`:797`), and a Left from it stops the run before `GroupingExport.publish` (`GroupingRunMain.scala:191`).

**2. The DecisionExport refactor.**
- `decideAll` (`:271-280`) calls `foreignProvenance` on the same sorted candidates with label `candidate <id>`. The message is `candidate <id>: provenance.runId is not the curated run <R>`, and the order and the combination with the duplicate reasons are as before. Decision bytes are unaffected because the decision path after the check is unchanged.
- The decisions package adds only a `Provenance` import and has no review reference.
- `candidateLabel` does not match LeakageStructureSuite's `(?i)\blabels?\b` pattern, because "Label" inside `candidateLabel` has no word boundary in front of it.

**3. Nit 2 is closed.**
- `decidedVersion` (`:352-364`) refuses unless the decision manifest's value equals both the effective policy's value and the code constant: `MatchFeatures.Version` = er-features.v2 and `BlockingKeys.Version` = er-blocking.v2.
- No false refusal on real data. er-policy.v8.json (and v1-v7) declare er-blocking.v2 and er-features.v2, and `DecisionExport.manifestProblems` already pins the decision manifest to the policy's values.

**4. Nit 3 is closed.**
- `:1390-1391` builds the histogram from `status.identified` groups. `AssignmentStatus` has only Resolved, Singleton (both identified) and Unresolved, so "identified groups only; unresolved members excluded: n" is exact.
- The `unevaluated` prefix is kept.

**5. Tests.**
- **Mixed-run test** (`GroupingSuite.scala:163-219`). It first shows that the base inputs group, then refuses mixed works, mentions only, profiles, candidates, and a null provenance. Without the check these inputs would group, so the test fails without the fix. It is not tautological, because it asserts the record prefix and the exact message together.
- **Version test** (`:221-248`). It asserts the decidedVersion message, which no other check produces.
- **Wording test** (`:250-261`). It pins the exact string.
- **Entry-point test** (`GroupingExportSuite.scala:479-501`). It shows that the decision export publishes, then that grouping exits 2 with the reason and the assignment root stays empty.
- **Fixtures** are synthetic.
- **Restamping weakened no earlier test.** Every fixture builder stamps the curated run ID, and the earlier refusal tests assert their own specific messages. One case changes: `curatedManifest.copy(attempt = 2)` (`:130-131`) now also triggers provenance reasons, but it still asserts "the decision run decided curated run", which only the inputRunId check produces.

**6. Boundaries.** All changes are under `modules/entity-resolution/**`. No change to the policy resources, `MatchFeatures`/`BlockingKeys` versions, Resolver, decision mapping or veto rules is visible. No gold or held-out path is touched or read.

**7. The disclosed residual.** I record it as a minor finding (below), deferrable. It does not block this repair.

## Findings (owner for both: entity-resolution-engineer)

1. **Minor: `decideAll` still ties only the candidates to the curated run.**
   - **Where:** `DecisionExport.scala:271-282`.
   - **Problem:** `decideAll` builds each decision's identifiers from `works` and `profiles` (`EndpointIdentifiers.index`, `:282`), but checks the provenance of candidates only.
   - **Failure input:** a curated directory whose `_run-manifest.json` and candidates belong to run R, while `normalize/works` comes from another run. `GroupingExportSuite.scala:489` shows that such a decision run is published.
   - **Impact:** a published decision run whose evidence came from other data but which records `inputRunId` R. This is the same false-provenance class as CR-P6-07 finding 1. Grouping now refuses it, so no assignment output is affected.
   - **Fix:** a one-line extension in a later work order: call `foreignProvenance` over the works, mentions and profiles in `decideAll` as well. Acceptable to carry forward if the human records it.
2. **Nit: no test for the code-constant half of `decidedVersion`.**
   - **Where:** `Grouping.scala:360`.
   - **Problem:** no test covers a decision value that equals the policy but differs from `MatchFeatures.Version` or `BlockingKeys.Version`, because the constants cannot be changed in a test.
   - **Impact:** low, since the logic is a plain `||`.

## Unverified concerns
- I could not observe the author's `sbt check` (success, 1101 passed, 0 failed, 0 skipped, 83 suites), the committed-sample probe run, or its diagnostics.
- `DecisionExport.scala` is untracked, so I cannot diff the old lines 251-254 to confirm the message is byte-identical. If the old code dereferenced a null provenance, it would have thrown; the new code refuses, which is strictly safer. The existing `DecisionExportSuite.scala:593` substring check still holds.
- A work whose `authors` is null would make `provenanceProblems` throw instead of refuse. JSON decoding probably prevents this; I did not verify it.
- That Hadoop stamps mentions with their work's run ID rests on the author's probe, not on code I read.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-08 (Phase 6), read-only review of the ER-P6-05 repair 01 diff (regions in docs/agent-runs/phase-06/repair-04.md)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only; no Grep under gold/ or target/; no held-out path read)
TEST RESULTS: not observed. The author reports sbt check success, 1101 passed, 0 failed, 0 skipped, 83 suites; the reviewer cannot run it.
VALIDATION RESULTS: items 1-7 of the work order checked against the files: DecisionExport.scala:228-282; Grouping.scala:96-118, 251-384, 490-533, 793-804, 1325-1394; GroupingRunMain.scala:146-200; GroupingFixtures.scala:49-336; GroupingSuite.scala:87-261, 532-547; GroupingExportSuite.scala:325-501; LeakageStructureSuite.scala:93-219; the version constants and policy resources.
ASSUMPTIONS: the git status at session start is current; CuratedRunReader is the only curated reader used by GroupingRunMain; core types are as approved earlier.
RISKS: a decision run can still be published from a mixed curated run (grouping refuses it); the probe can still skip silently through assume(); on full DBLP a mixed run yields one reason per record.
MANUAL ACTION REQUIRED: Human: accept the repair, and decide whether minor finding 1 (decideAll scope) is carried forward or repaired now. Orchestrator: route the findings to entity-resolution-engineer if a repair is wanted.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance of ER-P6-05 repair 01; optional follow-up work order for decideAll's provenance scope.
VERDICT: APPROVE
FINDINGS:
- [minor] DecisionExport.scala:271-282. decideAll checks candidate provenance only, but builds decision identifiers from works and profiles. Failure input: curated manifest and candidates of run R, works of another run; the decision run publishes (GroupingExportSuite.scala:489 shows it). Impact: a decision run with a false inputRunId; grouping now refuses it downstream. Owner: entity-resolution-engineer.
- [nit] Grouping.scala:360. The code-constant half of decidedVersion has no test. Owner: entity-resolution-engineer.
UNVERIFIED CONCERNS: sbt check and the probe not observed; DecisionExport.scala is untracked, so byte-identical message preservation is unverifiable by diff; a null WorkRecord.authors would throw instead of refuse (probably unreachable through JSON decoding); Hadoop mention run-ID stamping not read.
```

---

## CR-P6-09: code-reviewer review of BE-P6-01 and ER-P6-06 (2026-11-16), verbatim

## CR-P6-09: REQUEST_CHANGES, because the two dev run manifests claim a code version that doesn't contain the code

The held-out isolation, the path checks, the committed-input check, the overlay, the read-backs and the tests all held up. One major finding remains: the provenance of the two dev run manifests is false. It is cheap to fix. I could not observe either author's `sbt check` result (1101 passed for BE-P6-01; 1109 passed, 0 failed, 0 skipped, 59 guard cases for ER-P6-06), because I have no Bash.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-09 (Phase 6): read-only adversarial review of BE-P6-01 (sbt erDecisionGroupingDev, verifyErDevRunGuards, runForked change) and ER-P6-06 (er-dev.conf, two dev run manifests, DevRunInputsSuite, read-backs in DecisionRunMain and GroupingRunMain), as one change
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool; Read, Grep and Glob only)
TEST RESULTS: not observed. I cannot run sbt. The authors' sbt check results are their own claims and I did not verify them.
VALIDATION RESULTS:
- Read in full: project/LocalRuns.scala 1-250, 540-850, 918-940, 960-1561, 1960-1985; build.sbt; the three local-runs inputs; DevRunInputsSuite.scala; DecisionRunMain.scala; GroupingRunMain.scala.
- Also read: the committed Hadoop dev manifest; er-policy.v8.json; the guard's SBT, SBTTEST and held-out rule code (guard-dangerous.sh 89-95, 160-170, 285-295); the ConfigLoader file-list split (ConfigLoader.scala:265).
- I did not read gold/** or any held-out path, and did not Grep under gold/ or target/. Only one Grep touched phase-05 evidence: human-gate.md for the in-force thresholds (lines 132, 141). Its output also showed two lines of what looks like a metrics table (lines 181-182). I did not read that file further and repeat nothing from it.
- Verified, check 1 (held-out safety):
  - Path refusals: namesHeldout and devPathProblems (LocalRuns.scala:1013, 1023-1042) refuse every held-out spelling in any case, plus gold/, target/streams/, target/local-runs/heldout/, the held-out attempt root, absolute paths, backslashes, and empty, "." or ".." segments.
  - Curated-run discovery: devCuratedRun (1131-1160) lists only attemptsRoot(Dev). It needs exactly one complete attempt, then exactly one snapshot= and one run=<64 hex>, with no symbolic link on any component (inside() compares the real path; oneDir uses NOFOLLOW) and a regular _run-manifest.json.
  - Second check on the input: DecisionExport.decideAll compares the run ID of the curated _run-manifest.json with the pinned inputRunId (DecisionExport.scala:269-274) and refuses on a mismatch. A wrong curated run is therefore refused by the entry point too.
  - Outputs: everything is under the root target/local-runs/dev/. That is outside the D3(b) scope (CleanTreeScope, 107-108), outside the R6 fences and outside the D10 directory. The self-test also asserts this (1358-1365).
  - Guard rules: "erDecisionGroupingDev" contains neither goldsetevalheldout nor localpipelineheldout (guard 289-294), so neither rule can misfire on it or be bypassed by it.
  - runForked: the new parameter defaults to DefaultStrippedPrefixes = AWS_, SCHOLARCHAIN_ (89, 731). The Phase 5 calls at 830, 922 and 1969 pass no prefix list. This matches the unchanged class doc at lines 59-60. I could not see the code before the change.
- Verified, check 2 (bounds and hygiene):
  - Bounds: 45 s per stage (985-986), with destroy then destroyForcibly on timeout.
  - Environment: AWS_*, SCHOLARCHAIN_* and GIT_* are removed (1258); then only the isolation map and SCHOLARCHAIN_CONFIG_FILES are added (1257). SCHOLARCHAIN_CFG_* names are refused before any work (1210-1217).
  - No stdin: the child's input is closed (765).
  - Committed inputs: compared as raw bytes, HEAD:path against git hash-object --no-filters (562-576).
  - Create-once: the task refuses when an output root already holds a run= entry, and only reports .staging-* leftovers. Nothing is deleted, except that the overlay is rewritten on each run (N1).
  - Overlay: holds only the schema version and the two roots, and refuses symbolic links on every path prefix (1249). The path pattern (1016) is checked with matches(), so it covers the whole string. It excludes space, comma, quote, backslash, $, newline and non-ASCII characters. The paths sit inside quoted HOCON strings, so nothing can be injected. Comma is excluded, so the comma-split file list (ConfigLoader.scala:265) cannot be split. Both roots live under the checkout path, so this check also covers the checkout path that configFiles uses.
- Verified, check 3 (the inputs):
  - ER values in er-dev.conf: er-policy.v8, 0.85/0.2, band 0.4-0.7, er-adjudication-prompt.v2, er-grouping.v1, identityVersion v1. They equal er-policy.v8.json lines 22-28 and the in-force settings line in phase-05/human-gate.md:132.
  - Every other value in er-dev.conf is an obvious dummy: account 000000000000, "dummy-*" names, ".invalid" endpoint, "not-called" model.
  - inputRunId is pinned by RunId.of of the committed Hadoop dev manifest (DevRunInputsSuite:181-184).
  - Digests and run IDs are computed by digestWithoutGrouping, GroupingRunSettings.configDigest and RunId.of, and each manifest is read with its entry point's own decoder (102-103). The newline difference is handled: DecisionRunMain keeps the final LF (DecisionRunMain.scala:61), GroupingRunMain strips it (GroupingRunMain.scala:76).
  - Run roots stay out of both digests, tested with two different overlays (147-177).
- Verified, check 4 (the read-backs):
  - DecisionRunMain uses readPublished and requires the manifest and the decisions to equal the batch (107-124, 163).
  - GroupingRunMain uses readValidated and requires the run ID, manifest and all four record sets to equal the batch (156-177, 234).
  - Both exit 2 on a mismatch (169 and 240). Without a fault, the only change is one extra verified line.
- Verified, check 5 (the tests):
  - verifyErDevRunGuards has 59 cases: 44 on constants and 15 on a temporary directory. DevRunInputsSuite has 8 tests. Read-back tests 7 and 8 would fail if the read-back were removed. Fixtures are synthetic; the only real file copied is the committed Hadoop manifest (346).
- Verified, check 7 (boundaries):
  - ER-P6-06 touched no policy, feature, threshold, Resolver, outcome or grouping rule. Its files are all under modules/entity-resolution/**.
  - The git status at session start lists build.sbt and project/LocalRuns.scala as modified, with no .claude/** entry in the visible part. That list was cut off; see UNVERIFIED CONCERNS.
ASSUMPTIONS:
- The git status snapshot at session start is current. It shows DecisionRunMain.scala, GroupingRunMain.scala and the grouping/ and decisions/ packages as untracked, which means they are not in HEAD 426df590.
- The old body of runForked stripped AWS_ and SCHOLARCHAIN_. This rests on the unchanged doc at LocalRuns.scala:59-60; I could not see the diff.
RISKS:
- The run path has never been observed, and the 45 s bounds are unmeasured.
- `sbt clean` and `sbt clean check`, run by any agent, delete the dev runs and their Hadoop dev input (disclosed).
- A run that is published but fails its read-back stays under the normal run= name (M1, M2).
- The listing has a time-of-check to time-of-use gap (disclosed).
MANUAL ACTION REQUIRED:
- Human: commit the ER and core code first. Have entity-resolution-engineer pin codeVersion to that commit and regenerate the manifests, then commit them. Ratify the allowlist row only after that.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: entity-resolution-engineer repairs MAJ-1 (and M2 if accepted); build-engineer repairs M1 and M3 and optionally the code-scope check; then a focused re-review of the repair diff; then factory-engineer prepares the allowlist row and probes for human ratification.
VERDICT: REQUEST_CHANGES
FINDINGS:
- entity-resolution-engineer:
  - MAJ-1 (major): the codeVersion in both manifests names a commit that does not contain the producing code.
    - Where: modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json:1 and er-grouping.dev.run-manifest.json:1 both set codeVersion 426df590f3f043c96021b739dd7f39436dd30956. DevRunInputsSuite.scala:258-259 checks only that the two values are equal and are 40 hex characters.
    - Failure input: the human commits these files and the code as a new commit X, then runs sbt erDecisionGroupingDev. Both durable runs (decision run run:76ec32b6...e7b and the grouping run built on it) record code version 426df59. That commit does not contain DecisionRunMain.scala, GroupingRunMain.scala or the decisions/ and grouping/ packages; all are untracked in the session-start status.
    - Impact: false code provenance on runs meant as durable inputs for DE-P6-02 and Spark. The runs cannot be reproduced from the commit they name, which breaks the CLAUDE.md "Provenance everywhere" rule. This departs from the Phase 5 precedent: commit 71ff551 "pin run manifests to implementation commit" pinned the Hadoop manifests to a961035, the implementation commit.
    - Fix: pin codeVersion to the commit that contains the code, as a second commit like 71ff551, and regenerate both run IDs; the suite already enforces the chain. Optionally assert in the suite that the codeVersion is not the HEAD the work was based on.
  - M2 (minor): "refused" and "published but unverified" share exit code 2, and the unverified run keeps a normal name.
    - Where: DecisionRunMain.scala:163 and 169; GroupingRunMain.scala:234 and 240.
    - Failure input: the "a repeated row" writer fault (DevRunInputsSuite:485-488, 496). readPublished accepts the run, but it differs from the batch.
    - Impact: the run stays as run=<hex> with no marker. A later DecisionExport.readPublished (for example in DE-P6-02 or Spark) accepts it. The only signal is one log line, and the build cannot tell this case from a refusal.
    - Fix: a distinct exit code (for example 3) and/or an unverified marker or rename that deletes nothing.
- build-engineer:
  - M1 (minor): the task says nothing was published after a run was in fact published.
    - Where: project/LocalRuns.scala:1278-1282 ("DecisionRunMain refused (exit 2) ... nothing published") and 1301-1306 ("no grouping run was published").
    - Failure input: any read-back failure in either entry point. The run was published and left in place, and the entry point's own error line says so.
    - Impact: the envelope message contradicts the entry point and invites a false report that nothing was published. The create-once rule then refuses a re-run while the task's message gives no reason. This is a finding, not just wording, because the honest-reporting rule applies to task output that agents copy into reports.
    - Fix: after exit 2, list the output root and report any run=... entry as published but unverified, or key off M2's distinct exit code.
  - M3 (minor): the self-test never reaches the refusal branches of erDecisionGroupingDev itself.
    - Where: LocalRuns.scala:1334-1561. Not exercised: the SCHOLARCHAIN_CFG_* refusal (1210-1217), the create-once refusal (1229-1236), the output-root symlink refusal (outputDir 1166-1174), the overlay symlink check (1249) and the committed-input refusal (1221).
    - Failure input: delete lines 1230-1236 (create-once) or 1211-1217 (the CFG refusal); all 59 cases still pass.
    - Impact: regressions in the main guards would go undetected. Note that the "complete held-out attempt is never chosen" case cannot fail by construction, because attempts() takes the split as a parameter.
    - Fix: factor these checks into pure or temp-directory functions and add cases for them.
  - N1 (nit): the overlay is rewritten on every run without a create-once check (LocalRuns.scala:1250). That contradicts "nothing is ever deleted" in the doc. The content is the same for a given checkout and its sha256 is logged.
  - N2 (nit): JAVA_TOOL_OPTIONS, _JAVA_OPTIONS and JDK_JAVA_OPTIONS pass through to the forked JVMs (1258). This is the same as the Phase 5 dev tasks, and agents cannot set environment variables.
  - Suggestion (not a finding): the task checks only the three inputs against HEAD and compiles entityResolution/Compile/fullClasspath from the working tree, so code that differs from the codeVersion can produce a run. Consider refusing unless build.sbt, project, modules/core and modules/entity-resolution are clean against HEAD, and unless the manifests' codeVersion has no diff to HEAD in that scope.
- Allowlist row and probes (check 6; factory-engineer prepares, human ratifies):
  - The exact shape `sbt erDecisionGroupingDev` for entity-resolution-engineer only is narrow enough. Mode allow is acceptable: it gives no capability beyond what sbt check already runs (code from the same module), and its writes are bounded. Activate it only after MAJ-1 is fixed, so the first run is durable evidence.
  - Add these probes to the proposed ones: test-engineer and service-engineer get no-allowlist-entry; `sbt clean erDecisionGroupingDev` and `sbt erDecisionGroupingDev check` are refused.
  - The optional hardening is safe. No active row can carry a token containing "heldout": the SBT class allows only <project>/{compile,Test/compile,test,scalafmt,Test/scalafmt,scalafmtCheck}, SBTTEST allows only <project>/test, and every fixed row names no held-out task. Match the same spellings as namesHeldout (heldout, held-out, held_out), in any case, and run it after the two existing named rules so their rule names are still reported first.
UNVERIFIED CONCERNS:
- The git status at session start was cut off, so I cannot fully confirm that nothing under .claude/** or other project/** files changed. A human `git status` and `git diff --stat` is needed.
- I did not see the body of runForked before the change.
- CuratedRunReader follows symbolic links for part files (CuratedRunReader.scala:34-39, using Files.isRegularFile and Files.list without NOFOLLOW). This predates this change and is partly offset by the provenance run-ID checks.
- The 45 s bounds and the overall wall time (sbt start, compile, both stages) against the agent tool limit have not been observed.
- The decidedAt and assignedAt values are fixed literals, not the observed run time. This was by design in ER-P6-02 and is outside this change.
```

---

## CR-P6-10: code-reviewer review of ER-P6-07 and BE-P6-02 (2026-11-16), verbatim

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-10 (Phase 6). A read-only, focused review of the ER-P6-07 repair (entity-resolution-engineer: CR-P6-09 M2 and CR-P6-08 minor 1) and the BE-P6-02 repair (build-engineer: CR-P6-09 M1, M3, the code-scope check and N1), as reported in /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/repair-05.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only; no Bash)
TEST RESULTS: none run by the reviewer. The authors' reported sbt check counts (1117 passed, 84 suites; verifyErDevRunGuards 128 cases PASS) were not re-observed.
VALIDATION RESULTS: I read the code as it stands in these files:
- modules/entity-resolution/.../review/DecisionRunMain.scala
- modules/entity-resolution/.../review/GroupingRunMain.scala
- modules/entity-resolution/.../decisions/DecisionExport.scala:60-918
- modules/entity-resolution/.../grouping/RunDirectory.scala
- modules/entity-resolution/.../grouping/GroupingExport.scala
- modules/entity-resolution/.../grouping/Grouping.scala:366-381
- DecisionExportSuite.scala:1120-1375
- DevRunInputsSuite.scala:1-330 and 480-718
- project/LocalRuns.scala:165-360, 555-582, 975-2635
- build.sbt:380-439
- both dev run manifests
- project/build.properties (sbt 1.11.7)

Results by check:

1. ER-P6-07: verified.
   - Verification before the rename. publishRun (DecisionExport.scala:579-647) and RunDirectory.publish (RunDirectory.scala:154-228) verify the staging directory under its final name (readAs / check(dir, name)) before the only Files.move to run=<hex>.
   - A staged failure never creates run=<hex>. It sets staging=None, then calls withdraw, which writes the marker first and then renames to .unverified-<attempt>. withdraw catches every NonFatal and deletes nothing (DecisionExport.scala:520-536). The fallbacks keep the directory and report it through Preserved.rejectedByReaders.
   - Readers refuse withdrawn and staging runs.
     - layoutProblem refuses any name other than ^run=[0-9a-f]{64}$ and any directory holding _UNVERIFIED (DecisionExport.scala:685-689; RunDirectory.scala:251-258).
     - Every reader goes through that path with nameOf(dir): readPublished/publishedRuns (DecisionExport.scala:844-886), RunDirectory.verified/publishedRuns, and GroupingExport.readPublished/readValidated.
     - GroupingRunMain reads its decision input only through DecisionExport.readPublished (GroupingRunMain.scala:223-226).
   - Exit codes match the contract.
     - Exit 0 needs Published (staged check passed) and then afterPublication Right.
     - Every Left after publication is Withdrawn, so exit 3. No exit-2 path exists after the rename.
   - The afterRename hook is `_ => ()` in both main methods (DecisionRunMain.scala:143-153; GroupingRunMain.scala:61-71). No src/main caller of runWithConfig or run exists (grep).
   - decideAll now checks curatedRecords(works, profiles) in the foreign-provenance check before any decision or publication (DecisionExport.scala:355-364). Grouping.provenanceProblems uses the same list (Grouping.scala:373-381).
   - The tests are not tautological:
     - The staged-failure tests assert that run=<hex> is absent, that .unverified- is the only entry, and that the marker and every file are present.
     - The post-publication test checks that the run verified before the tamper.
     - The withdraw test injects a failing Mover.
     - The mixed-run tests check that only the foreign kind is reported.
   - No policy, threshold, feature, Resolver or O1-O11 file is in the changed set.
   - local-runs/* holds only the three inputs. The grouping manifest still pins 426df590...
2. BE-P6-02: verified.
   - classifyStage (LocalRuns.scala:1205-1266) cannot report "nothing published" when a new run= directory exists:
     - exit 2 accepts only new unmarked .staging-* directories;
     - any other new entry is StageUnestablished, with "published but unverified" and "a reader may accept" for an unmarked run=;
     - exit 0 accepts exactly one new unmarked run= directory.
   - The grouping stage runs only after PublishedVerified; every other state calls fail with "GroupingRunMain was not started" (1740-1755).
   - createOnceProblem names every entry and deletes nothing (1169-1178).
   - The code-scope check (1448-1595):
     - It fails closed on any git error, timeout or non-zero exit (ok() and the explicit merge-base handling).
     - It checks raw bytes against the ls-tree blobs, refuses symbolic links and gitlinks, and checks both manifests (same codeVersion, a full commit id, HEAD or an ancestor).
     - `diff --name-only -z --no-renames C HEAD -- scope` may list only the two manifests.
   - Allowing ignored .DS_Store files is acceptable. It applies only to files (a trailing-slash directory is refused), and sbt's default HiddenFileFilter keeps them out of resources; build.sbt has no excludeFilter override (grep).
   - Def.taskDyn (build.sbt:398-412): in sbt 1.11, `.value` calls inside the nested Def.task belong to the inner task. So fullClasspath is evaluated only after erDevBeforeCompile returns. This is judged from sbt semantics; it was not observed.
   - N1: writeOverlay never overwrites (1645-1669).
   - The fixture repository is a temporary directory, with a check that `rev-parse --show-toplevel` is the fixture.
3. The pinning sequence works as planned.
   - DevRunInputsSuite computes every expected value: canonical bytes via Json.write, and the run-ID chain via RunId.of. It requires only that both manifests share one 40-hex codeVersion (DevRunInputsSuite.scala:188-260), so it accepts HEAD=Y.
   - No other file in the code scope (build.sbt, project, modules/core, modules/entity-resolution, excluding target) mentions 426df59, the decision run ID 76ec32b67bb0... or the grouping configDigest 484845939b9f... The only match is the grouping manifest itself. modules/entity-resolution/local-runs holds only the three inputs.
   - So Y has to change only the two manifests. The decision manifest's codeVersion changes, and the grouping manifest's codeVersion and decisionRunId change. The task should then pass at HEAD=Y with a clean scope, and later commits outside the scope also pass (fixture case Z).
   - X must commit everything in the scope, and no ignored path other than the four target directories or .DS_Store may exist in the scope (for example project/metals.sbt or project/.bloop/).
4. Held-out safety is unchanged.
   - None of the changed ER main files mentions heldout or gold (grep).
   - The code scope excludes gold/, and the only file read outside it is the root .gitignore.
   - The stage and output paths stay under target/local-runs/dev/, as before.

ASSUMPTIONS:
- The default arguments of readTreeState keep the Phase 5 held-out messages byte-identical. I could not compare with HEAD without git, so this is not verified.
- sbt 1.x keeps nested Def.task dependencies inside a Def.taskDyn to the inner task (standard documented behaviour).
- git reads its default global ignore and attributes files even when GIT_CONFIG_GLOBAL is set. This is from git documentation, not observed.

RISKS:
- Neither erDecisionGroupingDev nor erDevBeforeCompile has ever run. The authors disclose this.
- The meta-build compiles before any check (disclosed).
- A change made between the second check and the fork is not seen (disclosed).

MANUAL ACTION REQUIRED: The human reviews this verdict. The commit X, then the manifest-only commit Y pinning X, then the human commit proceeds as planned. Both minor findings can be routed as later work orders.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human accepts the repairs, then commit X, then entity-resolution-engineer pins both manifests to X (MAJ-1), then commit Y, then the first real erDecisionGroupingDev run after the allowlist decision.
VERDICT: APPROVE
FINDINGS:
build-engineer:
- minor, /Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala:1416-1429 (with :1459-1460, :1504-1509, :1517-1522). The M3 claim "if any single check is removed, a case fails" does not hold for several refusals.
  - Failure input: change `case List(c) if FullCommitId...` to `case c :: _ if FullCommitId...`, so a manifest with two codeVersion fields (top level plus `"parameters":{"codeVersion":...}`) is accepted on the first match. verifyErDevRunGuards still passes, because no fixture case writes a manifest with zero or two codeVersion fields.
  - The same applies to the other refusals in the location: tracked == 0 (1459-1460), the ls-tree/index count mismatch (1504-1509) and the hash-object count mismatch (1517-1522).
  - The committed-input "not committed at HEAD" refusal (:578) is observed only in the read-only real-tree report, not in a fixture case.
  - Impact: the two-field protection the work order asks about could regress silently. Behaviour today is correct by reading.
  - Fix: add fixture cases for two fields, no field, and a codeVersion only inside a nested object, plus an untracked committed input.
- minor, /Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala:2142-2147. The fixture's git isolation is incomplete.
  - GIT_CONFIG_GLOBAL=/dev/null and GIT_CONFIG_NOSYSTEM=1 are set. HOME and XDG_CONFIG_HOME are inherited (gitProcess at :175-180 only strips GIT_*). By git's documented defaults, the fixture still reads ~/.config/git/ignore (core.excludesFile) and ~/.config/git/attributes (core.attributesFile).
  - Failure input: a user ~/.config/git/ignore containing `*.scala`. N.scala then reports as ignored, so the case "untracked, non-ignored source" gets the "ignored path(s)" refusal instead of "lists 1 path(s)".
  - Impact: verifyErDevRunGuards, and so sbt check, would fail on that machine. The fixture's verdicts depend on user files. The real repository is never touched.
  - Fix: set HOME and XDG_CONFIG_HOME to the temporary directory in the fixture env. The held-out fixture at :2739-2744 has the same pattern; it is pre-existing and out of this repair's scope.
entity-resolution-engineer: none.
UNVERIFIED CONCERNS:
- codeVersionOf's regex does not decode JSON escapes. A deliberately crafted committed manifest could carry its real key as `"codeVersion"` (if the core Json decoder accepts escaped keys, not verified) plus a decoy codeVersion elsewhere, and so mislead the check. DevRunInputsSuite's canonical-bytes assertion (Json.write) would fail such a file in sbt check, but the task itself runs no tests. Low risk: it needs an adversarial commit by the human.
- A fatal (non-NonFatal) error during the post-publication read-back would end the JVM with exit 1 and leave run=<hex> unmarked. So would any throw from summary or ConfigEvidence.lines after the rename, which looks unlikely but was not verified. M1 classifies this as "not established" with "a reader may accept it; move it aside by hand", so it is reported but not prevented.
- I did not confirm that the Phase 5 held-out refusal strings are byte-identical, because I could not read HEAD's LocalRuns.scala. The defaults ("the clean-tree check (decision D3(b))") look right, and the authors report verifyHeldoutGuards PASS.
- Cosmetic: "nothing was deleted" appears twice in stage failure messages (disclosed by build-engineer).
```

Orchestrator note: the human carried both CR-P6-10 minor findings forward (2026-11-16, prompt-er-p6-08-manifest-pin.md).

---

## CR-P6-11: code-reviewer re-check of CR-P6-09 MAJ-1 after ER-P6-08 (2026-11-16), verbatim

CR-P6-11 re-check: MAJ-1 is closed and I approve. Both manifests now name X, and nothing in the code scope still points at 426df59. I have no Bash, so I did not observe the author's `sbt check` (1117 passed, 0 failed). The run-ID equality rests on that run passing.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-11 (Phase 6). Read-only, narrow re-check of CR-P6-09 MAJ-1 after the ER-P6-08 pinning repair (docs/agent-runs/phase-06/repair-06.md).
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only; no Bash. No Grep under gold/ or target/; no held-out path and no phase-05 human-gate.md read)
TEST RESULTS: not observed. The author reports sbt check success: 1117 passed, 0 failed, 84 suites, markTestRun git_head=8c1bee04... worktree=dirty. I cannot run sbt.
VALIDATION RESULTS:
1. Both manifests are pinned to X and otherwise unchanged.
   - Files: modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json:1 and er-grouping.dev.run-manifest.json:1.
   - Both carry codeVersion 8c1bee042e7dca3119f5116df2150e0bee204598. That is 40 lower-case hex characters and equals X.
   - Values that match the old ones quoted in repair-06.md and review-report.md:
     - decision configDigest 61655dc1...e085
     - grouping configDigest 484845939b9f...6298 (the value CR-P6-10 also grep-checked)
     - inputRunId run:2881724b...8baa in both
     - decidedAt 2026-11-15T00:00:00Z (also asserted at DevRunInputsSuite.scala:205)
     - assignedAt 2026-11-15T12:00:00Z
     - the policy, feature, blocking, grouping and identity versions
     - three inputSnapshots, attempt 1
   - Grouping decisionRunId is now run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a.
   - I could not diff against HEAD without git. Unchanged fields are judged against the quoted old values plus the orchestrator's observed diff summary.
2. The decisionRunId chain is enforced by a test, so a passing sbt check verifies it.
   - RunId.of (modules/core/src/main/scala/scholarchain/core/StableIds.scala:577-600) hashes codeVersion along with the other manifest fields, so a codeVersion change must change the decision run ID.
   - DevRunInputsSuite.scala:229 computes RunId.of(decisionManifest).
   - :241 compares the whole grouping file byte for byte with Json.write(expected), and expected includes that derived decisionRunId.
   - :245 asserts equality directly. :258 asserts that both manifests carry the same codeVersion.
   - The author's evidence fits this:
     - With only the codeVersions edited, the suite failed at :241 and at the end-to-end check (:454), whose message named run:736cb170...b76a as the verified decision run ID.
     - The decision-manifest test (:188-219, canonical bytes at :200) passed.
     - After the decisionRunId edit, sbt check passed.
3. A commit Y holding exactly these two files passes the code-scope check at HEAD=Y. Check: project/LocalRuns.scala:1448-1595.
   - Clean tree, index-flag, ignored-path and raw-byte checks: satisfied once the two manifests are committed. docs/agent-runs/** is outside ErDevCodeScope (build.sbt, project, modules/core, modules/entity-resolution; :1388-1389), so committing or leaving the evidence files is irrelevant.
   - Common codeVersion C = X: both match (:1534-1544).
   - C is a commit (:1545-1548) and HEAD or an ancestor of it (:1551-1565). X is Y's parent.
   - The diff from X to Y over the scope lists only the two manifests (:1566-1588), and :1580 filters out exactly those (ErDevManifests, :1400).
   - At the old pin 426df59, the same check would now refuse, because the code added in X sits in its diff to HEAD. So the run-time guard also closes this failure class.
   - No other file in scope depends on the old or new values:
     - Grep for 426df59, 76ec32b6, 8c1bee0 and 736cb170 (excluding target/) matches only the two manifests under modules/, nothing under project/, and nothing in build.sbt.
     - local-runs/ holds only er-dev.conf and the two manifests.
4. Nothing left open on MAJ-1.
   - X (8c1bee0, "Phase 6: add reproducible entity-resolution dev pipeline") is HEAD.
   - The session-start status shows no untracked or modified code in scope other than the two manifests, so the producing code (DecisionRunMain, GroupingRunMain, the decisions/ and grouping/ packages) is in the commit the manifests name.
ASSUMPTIONS:
- The session-start git status is current: only the two manifests are modified in scope, and the untracked files are docs/agent-runs/phase-06/prompt-er-p6-08-manifest-pin.md and repair-06.md.
- HEAD is 8c1bee042e7dca3119f5116df2150e0bee204598, as stated in the work order and observed by the author with git rev-parse.
RISKS:
- Two values were never printed: the new decision run ID and the derived grouping run ID. They are verified only by the suite passing, not by an independent printout. The decision run ID was taken from a test failure message.
- Y must change nothing else in scope. Any extra file in scope would make the dev task refuse, so the risk fails closed.
- erDecisionGroupingDev has still never run.
MANUAL ACTION REQUIRED:
- Human: review the two-file diff and commit exactly the two manifests as Y. Adding the docs/agent-runs evidence files is optional. Record the hash.
- After Y, the allowlist row for sbt erDecisionGroupingDev can go to factory-engineer and human ratification, per CR-P6-09 check 6.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human commit Y; then factory-engineer prepares the erDecisionGroupingDev allowlist row and probes for ratification; then the first real dev run.
VERDICT: APPROVE
FINDINGS: none (CR-P6-09 MAJ-1 verified closed; the CR-P6-10 minors already carried forward for build-engineer are unaffected)
UNVERIFIED CONCERNS:
- The author's sbt check result and the step-4 failure messages were not observed by me.
- That the unchanged fields are byte-identical to HEAD rests on the orchestrator's observed git diff, not on a diff I could run.
```

---

## CR-P6-12: code-reviewer review of FE-P6-01 (inactive allowlist row) (2026-11-16), verbatim

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-12 (Phase 6). Read-only review of the FE-P6-01 constitutional change before human ratification: the inactive `sbt erDecisionGroupingDev` row for entity-resolution-engineer, its 35 probes, and the docs/agents.md mirror.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. Read, Grep and Glob only, on: docs/agent-runs/phase-06/fe-p6-01-report.md, prompt-fe-p6-01-allowlist.md, review-report.md (CR-P6-09 check 6); .claude/hooks/bash-allowlist.tsv; .claude/hooks/probes.tsv:670-729; .claude/hooks/guard-dangerous.sh:40-660; .claude/hooks/validate-agent-files.sh:200-334; .claude/settings.json; docs/agents.md; build.sbt:1-80 and 395-416; project/LocalRuns.scala:995-1074, 1380-1489 and 1600-1829.
TEST RESULTS: not run. I have no Bash. I did not observe the author's `--probes` run (696 table probes PASS) or the orchestrator's validator run (PASS, 120 rows, sha256 6d1a92c6694f).
VALIDATION RESULTS:
1. The row is scoped exactly (bash-allowlist.tsv:101).
   - Identity: entity-resolution-engineer only.
   - Status: `inactive:FE-P6-01-ratification-pending`. This matches the loader regex `active|inactive:[A-Za-z0-9+_-]+` (guard:252).
   - Mode allow, provenance `-`.
   - Spec: two literal tokens `sbt erDecisionGroupingDev`. `match()` requires `i == n`, so no extra token can match.
   - Inactive rows never reach `hits` (guard:508). No identity can run the command now.
   - The justification matches the code:
     - Root `Def.taskDyn` with `erDevBeforeCompile` runs before the classpath is compiled (build.sbt:398-412).
     - Refusals: `configOverrideProblem` (SCHOLARCHAIN_CFG_*); `devPathProblems` (held-out spellings, gold/, target/streams/ and the held-out roots); `erDevCodeScopeCheck` over `build.sbt project modules/core modules/entity-resolution`, including the rule that codeVersion is HEAD or an ancestor and only the two manifests changed since.
     - Create-once on both output roots; the overlay is never overwritten.
     - DecisionRunMain then GroupingRunMain, 45 s each (LocalRuns.scala:1002-1003).
     - Outputs under target/local-runs/dev/ (LocalRuns.scala:1016-1019).
2. Guard verdicts, read from bash_check (guard:478-545).
   - Inactive state, every probe is correct:
     - The 4 [FE-P6-01 T] rows: no active row matches. `@SBT-ALL` rows need exact token counts, and `<SBT:entityResolution>` accepts only `entityResolution/{compile,...}` (guard:165). The dormant check then finds row 101: both leading literals equal `toks[:2]`, so the rule is entry-inactive. `-Dscholarchain.x=y` passes the character gate (no leading `=`, no `==`).
     - `sbt clean ...`, `sbt check ...`, `entityResolution/...`, the upper-case name, `erDecisionGrouping`, `/usr/local/bin/sbt`, `runMain` and `erDecisionGroupingHeldout`: none shares both leading literal tokens, so all give no-allowlist-entry.
     - `;` is outside GATE_CH, so that probe gives shell-syntax-gate.
     - `heldout_human_only_bash` runs before allowlist matching (guard:505-507), so the held-out probes give the eval or pipeline rule. The eval rule is checked first, which matches the hadoop-engineer probe.
     - heldout-run-protected is evaluated only after a hit, so `sbt clean erDecisionGroupingDev` gives no-allowlist-entry whether or not the held-out directory exists.
     - Other identities: `rows_for` restricts the dormant check to the caller's own rows, so they get no-allowlist-entry. code-reviewer gets reviewer-read-only first. The ORCHESTRATOR's `sbt --version` row is active, so it is never dormant.
   - Active state after edits (a)-(e), every probe is correct:
     - The bare command hits row 101. Tripwire, label fence, gitignore and clean checks do not fire (`sbt` is not in DANGER, no secret pattern, no clean token). `decide` returns no decision, so the result is ALLOW, the same path as the existing goldSetEvalDev ALLOW.
     - The trailing-token forms no longer have a dormant row and give no-allowlist-entry.
     - `--validate-allowlist` tripwire on `['sbt','erDecisionGroupingDev']` is clean.
   - No other path admits the command:
     - `@SBT-ALL` is five exact shapes, `<SBTTEST>` is `<project>/test` only, and no other identity has a row naming it.
     - settings.json has no allow, ask or deny rule naming it, and no broad `Bash(sbt *)` allow.
     - The guard never emits allow.
3. Agreed probes are present (all from CR-P6-09 check 6):
   - test-engineer no-allowlist-entry (probes.tsv:720) and service-engineer (719).
   - `sbt clean erDecisionGroupingDev` (696).
   - `sbt erDecisionGroupingDev check` (692): BLOCK now, BLOCK with no-allowlist-entry after activation.
   - No required probe is missing. Optional extras are in the nits.
4. Trailing TAB (probes.tsv:683):
   - The validator reads rows with `IFS="$TAB" read -r exp hook label json want` (validate-agent-files.sh:319). TAB is an IFS whitespace character, so a trailing TAB is stripped and `want` is empty in both forms. `probe()` skips the substring check when `want` is empty (line 309). The verdict and check are unchanged.
   - The line count is consistent with the diff stat: 46 insertions = 35 probes + 10 comment lines + 1 re-added line 683.
   - Acceptable as is. Restoring it is optional (cosmetic byte identity only). It does not have to be restored before commit.
5. Mode recommendation: keep allow.
   - It matches the sibling dev tasks (localPipelineDev, goldSetEvalDev).
   - It adds no capability beyond `sbt check`, which already compiles and runs this module's code.
   - With no settings.json allow rule, default mode still prompts the human on every call, which suits the first observed runs.
   - Choose ask only if the human wants a forced prompt even after a later settings or "always allow" rule. In that case activation edit (d) must expect ASK, not ALLOW.
6. Nothing else changed, based on the session-start git status and the orchestrator's diff stat:
   - Only bash-allowlist.tsv, probes.tsv and docs/agents.md are modified, plus two untracked phase-06 docs.
   - No hook script, settings.json, .mcp.json, agent file or ownership row changed.
   - The held-out fences are intact: heldout_human_only_bash, the R6 regexes, the settings.json denies (155-158) and the clean rule are all as read.
ASSUMPTIONS:
- The orchestrator's observed git status and diff stat are accurate. I could not run git diff.
- The pre-change byte content of probes.tsv:683 differed only by the trailing TAB, per the author's report and the 1-deletion diff stat.
RISKS:
- The active-state verdicts are inferred from reading the code. They are first observed at the post-activation `--probes` run.
- Inherited, already-accepted residual: sbt writes to gitignored target/ paths, which audit-bash-writes.sh does not see.
- The 45+45 s stages plus compile and sbt start-up must fit inside the agent tool limit. Not observed.
MANUAL ACTION REQUIRED:
- Human: choose the mode (recommended: allow).
- Human: optionally restore the TAB on probes.tsv:683.
- Human: ratify and commit, then issue the activation work order, edits (a)-(e) with (d) adjusted if ask is chosen, and start a fresh session.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human ratification and commit of FE-P6-01, then a factory-engineer activation work order, then a fresh session and a `--probes` run that observes the active verdicts, before entity-resolution-engineer runs `sbt erDecisionGroupingDev`.
VERDICT: APPROVE
FINDINGS (all owned by factory-engineer; no critical or major findings):
- nit, docs/agent-runs/phase-06/fe-p6-01-report.md:30 and :142. The report says "7 comment lines". The actual count is 10: probes.tsv:684-689 plus the section headers at 690, 695, 705 and 710. Failure input: anyone reconciling the report with `git diff --stat` (46 insertions). Impact: only the evidence record is inaccurate; behaviour is unaffected.
- nit, .claude/hooks/bash-allowlist.tsv:101 (justification). "writes only target/local-runs/dev/" leaves out two other writes: sbt compile output in modules/core/target and modules/entity-resolution/target, and the task's streams log under target/streams/_global/erDecisionGroupingDev/, which receives the forked child output through ToLog (LocalRuns.scala:1794). Failure input: a reader relying on the justification as a complete write list. Impact: none on enforcement. The goldSetEvalDev row (line 100) has the same convention, and the output is dev-only.
- nit, .claude/hooks/probes.tsv:683. Trailing TAB removed from a Phase 5 probe. Verdict unchanged (check 4). Impact: cosmetic byte difference on a ratified line. Restoring it is optional.
- nit, missing optional probes. Two extra refusal probes would harden the record, each giving no-allowlist-entry in both states:
  - a leading option, `sbt -Dx=y erDecisionGroupingDev`
  - an upper-case program token, `SBT erDecisionGroupingDev`
  Both are refused today; there is simply no probe for them.
UNVERIFIED CONCERNS:
- I did not see the pre-change bytes of probes.tsv:683 or the full working-tree diff myself.
- Pre-existing and not specific to this row: rows with provenance `-` have no cwd-at-root requirement. An `sbt` call started from a subdirectory would make sbt treat that directory as a new build. Not examined further.
```

Orchestrator note: the harness flagged both FE-P6-01 and CR-P6-12 hand-backs as matching an instruction-shaped pattern (settings-json). Both only discuss whether a `settings.json` allow rule exists; no settings change is proposed or made in this work order.

---

## CR-P6-13: code-reviewer review of DE-P6-02 (Spark-facing core contracts C1-C7) (2026-11-16), verbatim

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-13 (Phase 6). Focused, read-only, adversarial review of DE-P6-02 (domain-engineer), the Spark-facing core contracts C1-C7 in modules/core/** (uncommitted on HEAD 6be1f1d).
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I used only Read, Grep and Glob. I read nothing under gold/, no held-out path, nothing under target/streams or target/local-runs/heldout, and not docs/agent-runs/phase-05/human-gate.md.
TEST RESULTS: none run by me (the reviewer cannot run commands). The author reports `sbt check` passed with 1226 tests, 0 failed, 88 suites. I did not observe that output.
VALIDATION RESULTS:
- Ownership (item 9): the git status snapshot shows changes only under modules/core/** plus the orchestrator's evidence under docs/agent-runs/phase-06/. build.sbt and project/** are unmodified, so there is no new dependency. Core has no Parquet import.
- I could not see the text before the change because I have no git. To judge v1 compatibility I relied on reading the code and on the existing tests.

VERDICT: REQUEST_CHANGES (one verified major finding)

FINDINGS:

domain-engineer

1. MAJOR. The analysis config digest is never checked against the settings it claims to describe.
   - Location: modules/core/src/main/scala/scholarchain/core/AnalysisValidation.scala:962-1049 (`checkRunChain`); modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala:142-152 (`AnalysisRunSettings` has no digest field).
   - The problem: `expected.configDigest` is copied from `analysisManifest.configDigest` (line 998). It is never compared with `AnalysisRunSettings.configDigest(config)` for the configuration whose `settings` the validator recomputes against. `settings` cannot carry that digest.
   - Failure input: an analysis run manifest (and lineages) that records `ScholarChainConfig.digestWithoutGrouping(c)`, which is what every non-grouping stage records, or any 64-hex value. The fixture itself uses `"9" * 64` (AnalysisFixtures.scala:72), and the happy path at AnalysisValidationSuite.scala:29 accepts it.
   - Impact: `RunId.of` hashes `configDigest` (StableIds.scala:614), and the analysis manifest's parameters hold only the three run-chain keys. So two analysis runs that differ only in `analysis.maxPathHops`, `maxPathsPerPair`, `maxEvidenceWorksPerHop`, `exportFormat` or `community.randomSeed` would get the same run ID. That means a run-directory collision (overwrite or silent reuse), and every record's lineage would name a config that does not identify its analysis settings. This breaks the provenance invariant without any error.
   - Fix: make `checkRunChain` require the expected analysis digest, either as a parameter or as a field filled by `AnalysisRunSettings.from`/`configDigest`, and refuse a mismatch. Add a test that fails without the check.

2. MINOR. The validator's inputs are not tied to the run chain.
   - Location: AnalysisValidation.scala:124-161 (`checkGraphInput`) and 426-452 (`checkIdentity`).
   - The problem: nothing checks that the teams, works and researchers carry `provenance.runId` equal to the grouping run ID, or that the `decisions` carry the lineage's `decisionRunId`. `checkGraphInput` also does not require a team for every counted work.
   - Failure input: decisions from another decision run, or a teams vector missing one counted work.
   - Impact: identity confidence is validated against the wrong run, or P_r is silently undercounted. The run chain looks complete when it is not.
   - Fix: check the provenance run IDs against the manifests, and require one team per counted work.

3. MINOR. Two load manifests that differ only in roles get the same load ID.
   - Location: StableIds.scala:903-931 (`StoreLoadId`); StoreLoad.scala:103.
   - The problem: `targetRole` is not an input of `loadId`.
   - Failure input: `StoreLoadManifest.build(S3, pm, sha, Map(p -> "researcher-vertices"))` and `build(S3, pm, sha, Map(p -> "edges"))` produce equal `loadId`s.
   - Impact: the storage loader's idempotency key collides, so a second load with a different role map can be skipped or misattributed.
   - Fix: hash the sorted (path, role) pairs, or document that `loadId` identifies the part set only and require loaders to key on roles too.

4. MINOR. Which shortest paths get stored is not deterministic.
   - Location: AnalysisValidation.scala:775-791 (`checkPaths`, per pair).
   - The problem: any subset of up to `maxPathsPerPair` shortest paths passes. Ordinals only order the subset that was stored.
   - Failure input: a pair with three shortest paths and `maxPathsPerPair` = 1. Storing either the smallest-ID or the largest-ID path validates.
   - Impact: re-running with the same inputs and config can store different evidence, so evidence is not reproducible.
   - Fix: define the rule (for example, the n smallest `pathId`s among all shortest paths) and check it. At dev scale the validator can enumerate the shortest paths.

5. NIT. Scale.
   - Location: AnalysisValidation.scala:266-272.
   - `models(...)` is rebuilt in each of the eight checks. Pair expansion costs O(sum of n_w^2) per window, so one consortium-size team (thousands of authors) multiplies quickly.
   - This is fine for the dev run (41 teams). Build the models once and pass them to the checks before sample scale.

6. NIT. There are two path-hop bounds with no cross-check: `runtime.maxPathHops`, which already existed, and the new `analysis.maxPathHops` (ScholarChainConfig.scala:93-98, ConfigLoader.scala:146). The runtime can ask for more hops than any stored path has. Document the relation or refuse runtime > analysis.

Checked and found sound:
- Item 1, graph scope: only counted works enter the graph; enrichment-only, member-of-counted and unknown works are refused (tested at AnalysisValidationSuite:37-56). T, A and M include unresolved coauthors in separate fields; C, W and edges use identified researchers only, and W = T − U is enforced both in the contract and in the recomputation. `GroupingEvaluation` has the single value `unevaluated`. Every record carries lineage and provenance. P-7 appears only in a comment saying it is deferred. Teams come from the grouping run (41 teams = 41 counted works in dev). PublicationTeam requires distinct researcherIds, so teamSize = i + u holds for grouping output (Grouping.scala:1101-1103).
- Item 2, identity confidence: a missing `kind`, a null confidence, an "estimated" kind and a value on an unknown are all refused. The missing-Option test (AnalysisContractSuite:126-151) is written so that either upickle behaviour leads to a refusal.
- Item 3, integrity: jsonl keeps the LF row-count and final-LF checks (gated by `lineDelimited`, which is true for every v1 file). parquet and csv are compared only by bytes and sha256; their rows come from the footer or the writer. 12 bytes ("PAR1" + 4-byte footer length + "PAR1") is a sound lower bound that never rejects a valid file. `fromV1` is equivalent to v1 (tested). The dispatcher cannot apply the wrong version's rules: each contract accepts only its own schemaVersion, both use allowUnknownKeys(false), and v2 requires `format`.
- Item 4, backward compatibility: v1 metrics, edge and part-manifest codecs and constants are unchanged. DecisionExport and GroupingExport still call `PartManifestVerifier.verify` (v1).
- Item 5, digests: `analysis` and `randomSeed` have defaults, so they are omitted from the JSON. The frozen base-digest test is ConfigLoaderSuite:752-759 and 1049-1063. `DevRunInputsSuite` compares digests computed through `digestWithoutGrouping` and `GroupingRunSettings.configDigest` with the committed dev manifests that hold 61655dc1… and 48484593…. If the reported `sbt check` pass holds, both digests are unaffected. Nothing hardcodes a window, threshold or bucket.
- Item 6, stable IDs: netstrings plus distinct tags and prefixes; pairs ordered by unsigned UTF-8 bytes; IDs anchored on the smallest member, so they do not depend on algorithm labels. Sharing the edge ID between v1 and v2 is intended and safe, because both use the same logical key.
- Item 7, recomputation: A as an IEEE double division, the participation coefficient (S counts the researcher's own community), the coreness peeling (k is monotone, so decrements below k are harmless), union-find components and the bounded BFS are all correct.
- Item 8, tests: the fixtures are synthetic. No new test reads target/, the home directory, the archive or gold/. The only temp files are pre-existing ConfigLoaderSuite tests (lines 282-314 and 727-742), which write to java.io.tmpdir and delete in `finally`. The new analysis tests write no files.

Open questions for SP-P6-01:
1. Consume the archived, verified bytes, as the human directed. Verify each part against `_parts.json` with `PartManifests.verify` and `checkGroupingRunParts` first. Do not re-pin.
2. The pair set must be decided explicitly. At dev scale, all connected pairs within `maxHops` per window are tractable; larger runs need a configured rule. It should go together with the deterministic path selection in finding 4.
3. Neptune CSV belongs to storage-engineer (the matrix gives it Neptune loaders and adapters). Spark exports the canonical v2 records.
4. Algorithms: GraphFrames provides components, label propagation and BFS/shortestPaths, but no betweenness and no k-core. Plan sampled Brandes and k-core peeling under bridge.* and community.*. Leave `community.randomSeed` unset if the chosen algorithm is deterministic. I did not verify version compatibility.
5. Do not migrate now. Moving windows, bridge.* and community.* out of the base digest would change the frozen digest and force an ER re-pin, which contradicts the human direction. Today these values only make the analysis digest depend on them, and no check ties the analysis base digest to the ER manifests. Record the inconsistency (community.randomSeed is analysis scope, community.algorithm is base scope) and schedule a versioned digest-scope migration before the next ER re-run.
6. Fractional edge weights are not needed for the specification's count metrics. Defer them (they would need edge v3 and metrics v3).
7. Keep reading the archived decision run. A confidence projection in the grouping output would change ER outputs and force a re-run.
8. Blocking for SP-P6-01: core is Scala 3, and sparkAnalytics depends on it. The Spark Scala version (2.13 via cross-version, or 2.12) must be decided by the human and build-engineer first.

ASSUMPTIONS:
- The orchestrator's git status is the complete change set.
- The author's reported `sbt check` pass is used only as context, never as evidence for a finding.
RISKS:
- I could not view the pre-change v1 verifier text, so "messages and order unchanged" rests on reading the code plus the existing v1 tests.
- The ER re-pin need (BE-P6-02 code-scope check) remains, as the author reported.
MANUAL ACTION REQUIRED: Route finding 1 (major) and findings 2-6 to domain-engineer, then run a focused re-review. The human decides open questions 2, 4, 5 and 8 before SP-P6-01.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: domain-engineer repair of finding 1 (and ideally 2-4), `sbt check`, then a focused re-review by code-reviewer.
UNVERIFIED CONCERNS:
- If upickle 4 reads a missing Option key as None, an unknown confidence with the `value` or `unknownReason` key absent may decode. Refusal still holds (kind is required and the reason rule applies), but this is weaker than the documented wire form with explicit nulls.
- JSON with duplicate `schemaVersion` keys in a part manifest: ujson and upickle should both take the last value consistently. Not observed.
- Whether GraphFrames and Spark versions support the planned algorithms on the chosen Scala version.
- The author's test counts were not observed by me.
```

---

## CR-P6-14: code-reviewer re-review of DE-P6-02 repair 01 (2026-11-16), verbatim

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-14 (Phase 6). Focused, read-only re-review of the DE-P6-02 repair 01 diff (domain-engineer) in modules/core/**, uncommitted on HEAD 6be1f1d, against CR-P6-13 findings 1-4.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I used only Read and Grep. I read nothing under gold/, no held-out path, nothing under target/, and not docs/agent-runs/phase-05/human-gate.md. No Grep under gold/ or target/.
TEST RESULTS: I ran no tests (the reviewer cannot run commands). The author reports `sbt check` passed with 1251 tests, 0 failed, 89 suites (core 557). I did not observe that output.
VALIDATION RESULTS:
- Finding 1 is closed.
  - `AnalysisRunSettings` has a private constructor plus a `configDigest` field. The only construction site is `from` (ScholarChainConfig.scala:261), which fills the field from `digestOf` (:195-212). `digestOf` hashes the same `DigestInput` as before: base `digestWithoutGrouping(c)`, `community.randomSeed` and the four `analysis.*` values.
  - `checkRunChain` refuses `analysisManifest.configDigest != settings.configDigest` (AnalysisValidation.scala:1241-1246). Every lineage is compared with an expected lineage whose `configDigest` is `settings.configDigest` (:1234, :1292).
  - Tests:
    - AnalysisValidationSuite:565-585: each of the five AnalysisScopeKeys changes the digest and `RunId.of`, the old manifest is refused, and `digestWithoutGrouping` does not move.
    - :587-606: the base digest, `"9"*64` and `"0"*64` are refused, even with records restamped to match the bad manifest.
    - The fixture now uses the real digest (AnalysisFixtures:156).
  - `digestWithoutGrouping` (:622-623) and `GroupingRunSettings.configDigest` (:507-518) have the same inputs as I reviewed in CR-P6-13. No consumer outside core uses the changed APIs (Grep over the other modules, tests/ and project/: no matches).
- Finding 2 is closed.
  - `checkGraphInput(input, groupingManifest)` (AnalysisValidation.scala:172-241) binds:
    - teams, works and researchers to `provenance.runId` = `RunId.of(groupingManifest)`, with a null provenance treated as foreign;
    - works' and researchers' `decisionRunId` to the manifest's `decisionRunId`, with a missing parameter refused;
    - duplicate teams (refused), and a counted work that has mentions but no team (refused).
  - `checkIdentity` (:509-539) binds researchers to the grouping run and decisions to the decision run before it derives anything. Tests are at AnalysisValidationSuite:88-194.
- Finding 3 is closed. `StoreLoadId.of` (StableIds.scala:911-944) uses tag `scholarchain.store-load.v2` and hashes the item count, then (path, role) for each item in UTF-8 path order. It refuses empty or null items, non-token roles and duplicate paths. Both call sites pass (path, role) (StoreLoad.scala:104-109, :236-241). The contract recomputes the ID with roles, so a relabelled role is refused. Tests: AnalysisWireSuite:208-252 and PartManifestV2Suite:303-326.
- Finding 4 is closed.
  - How `selectShortestPaths` works (AnalysisValidation.scala:971-1012):
    - A breadth-first search from the target, bounded by maxHops, stops after the level where it reaches the source. That gives the true distance to the target for every node at distance at most d(source).
    - A depth-first walk from the source then follows only neighbours at r-1, taken in ascending `compareUtf8` order, and stops after K paths.
  - Why it is correct:
    - Every node at distance r >= 1 has a neighbour at r-1, so the walk never reaches a dead end.
    - All the candidate sequences have the same length and the same first node, so a depth-first walk with sorted children emits them in lexicographic order, which is `EvidencePath.nodeOrder`.
    - The walk never lists all shortest paths. Its cost is about O(V+E) for the search plus K·maxHops·deg·log(deg) for the walk.
  - I checked the tie fixtures by hand:
    - Diamond n1-{n2,n3,n4}-n6: K=1 gives [n1,n2,n6], K=2 adds [n1,n3,n6], K=3 and K=5 give all three.
    - Two levels: the only 3-hop paths are n1-n2-n4-n6, n1-n2-n5-n6 and n1-n3-n4-n6. No 2-hop path exists, and the n4-n5 edge only adds longer walks. maxHops=2 gives an empty result.
    - The ORCID checksums of n1..n6 are valid (ISO 7064 11-2 gives 1, X, 8, 6, 4, 2), and the IDs sort as stated.
    - Main fixture: K=1 stores A-B-C only (AnalysisValidationSuite:424-431).
  - The exhaustive enumerator is a private test helper (EvidencePathSelectionSuite:99-122) and does not appear in main code. 61 pairs × 4 hop bounds × 7 values of K = 1708 cases, which matches the report.
  - `checkPaths` (:832-952) refuses:
    - paths for unlisted pairs, including the reversed orientation, because the pair contract requires canonical order;
    - benchmark researchers who are not identified;
    - stored sets or ordinals that differ from `selectShortestPaths`, including a listed, connected pair that has no stored paths.
  - `checkRunChain` binds `benchmarkPairsDigest` (required key) and `provenance.runId` = grouping run (:1221, :1247-1258).
- Item 5, no regressions:
  - The v1 schema constants are unchanged (Contract.scala:95-97 only adds `BenchmarkPairList`).
  - Findings 5 and 6 are untouched: `models(...)` is still rebuilt in each check, and there is still no runtime-vs-analysis hop cross-check.
  - No new config key: no `benchmark` key under config/.
  - The new and changed test files contain no target/, user.home, archive, gold/ or file I/O references, and the fixture config is in memory.

VERDICT: APPROVE

FINDINGS:

domain-engineer (all nits; none blocks)

1. NIT. Two `StoreLoadId.of` refusals have no direct test.
   - Location: StableIds.scala:932-935; AnalysisWireSuite.scala:249 tests only the duplicate-path refusal.
   - Failure input: delete the non-token-role branch (:934) or the empty-items branch (:932-933). Every test still passes.
     - The contract's own token check (StoreLoad.scala:229) still catches a bad role in a manifest.
     - `StoreLoadId.of(…, Vector(p -> "not a token"))` and `of(…, Vector.empty)` are never asserted.
   - Impact: the regression would go undetected.
   - Fix: add two `isLeft` assertions.

2. NIT. The provenance of the benchmark pair list is only partly bound.
   - Location: Analysis.scala:1307-1324 (contract) and AnalysisValidation.scala:1253.
   - Only `runId`, `sourceRecordId`, derived source and null model/prompt are checked. `snapshot` and `sourceSchemaVersion` are not.
   - Failure input: a list with `snapshot = "x"` and an arbitrary `sourceSchemaVersion` passes every check.
   - Impact: the list's provenance can be misleading, though its pairs and its digest binding stay correct.
   - Fix: require `snapshot` to be one of the grouping manifest's input snapshots and `sourceSchemaVersion` = `SchemaVersions.ResearcherAssignment`, or document that these fields are free text.

3. NIT. The missing-team rule trusts the researcher assignments to be complete.
   - Location: AnalysisValidation.scala:212-217.
   - Failure input: a grouping input that is missing both a counted work's team and every researcher assignment of that work passes `checkGraphInput`. P_r is then undercounted for that work's authors.
   - Why this is only a nit: catching this is the job of the accepted grouping gate (`AssignmentValidation.checkCoverage` against the curated expected author lists) and of part-manifest verification of the consumed bytes (`checkGroupingRunParts`).
   - Fix: state in the doc comment at :63-65 or :155-171 that `checkGroupingRunParts` must pass on the archived bytes before `checkGraphInput`. SP-P6-01 should call them in that order.

Recommendations on the two decisions the author flagged:
- Team rule: accept the narrowing. The rule is one team per counted work that has at least one mention, and no team for a counted work without authors.
  - It matches the accepted grouping output exactly. `Grouping.teamRecords` (Grouping.scala:1093-1094) emits teams only for counted works with `authors.nonEmpty`. `AssignmentValidation.checkTeams` (:600-603) uses the same rule. `PublicationTeam` requires `teamSize >= 1`.
  - Read literally, "exactly one canonical team per counted work" would refuse a valid grouping run that contains an authorless DBLP work.
  - A counted work with mentions but no team cannot slip through `checkGraphInput` while its assignments are present. The remaining route is nit 3, which upstream gates cover.
  - Record the precise wording in human-gate.md.
- One list per grouping run: accept. Binding `provenance.runId` to the grouping run is the conservative choice: it ties every pair ID to the run that issued it. Researcher IDs have no cross-run identity guarantee in the contracts, so a list carried silently into another run could name IDs that changed meaning. Re-issuing a list for a new grouping run is cheap and visibly changes `benchmarkPairsDigest` and the run ID.
  - Before SP-P6-01, someone still has to decide where the real list lives and who owns it. Today any such path would be UNASSIGNED, so it needs a matrix amendment by factory-engineer and human ratification.

ASSUMPTIONS:
- The git status snapshot is the complete change set. I had no git, so "unchanged" claims for digest inputs and v1 behaviour rest on reading the current code against my CR-P6-13 reading, plus the existing frozen-digest and DevRunInputsSuite tests the author reports as passing.
- The author's `sbt check` counts are context only, never evidence for a verdict.
RISKS:
- Spark must reproduce `selectShortestPaths` exactly: the same set and order, with ties broken by unsigned UTF-8 node order. A GraphFrames shortestPaths call does not give this by itself.
- Findings 5 and 6 and the upstream digest-scope inconsistency remain open, as recorded.
MANUAL ACTION REQUIRED: Human: decide the team-rule wording and the one-list-per-grouping-run binding (both recommended for acceptance above). Decide the location and owner of the real benchmark pair list (matrix amendment). Optionally route nits 1-3 to domain-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance of the repair and the two decisions, then the contract commit scope and SP-P6-01 planning.
UNVERIFIED CONCERNS:
- I assume Scala 3 makes the synthesized `copy` and `apply` of a case class with a private constructor private too, so a caller cannot build `AnalysisRunSettings` with a forged `configDigest` through `copy`. I found no `copy` use on it in the repository, but I did not observe compiler behaviour.
- `checkWindows.countedWorkCount` counts teams, not counted works, so an authorless counted work with a year is not counted. This is a pre-existing semantic outside the repair scope. Confirm it is the intended definition of countedWorkCount.
- I did not observe the author's test counts or the 1708-case count at runtime. I checked the arithmetic only.
```

---

## CR-P6-15: code-reviewer review of SP-P6-02 step 0 (SparkParquetCompatibilitySuite) (2026-11-16), verbatim

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-15 (Phase 6). Focused read-only review of /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/src/test/scala/scholarchain/spark/SparkParquetCompatibilitySuite.scala (SP-P6-02 step 0, spark-engineer, untracked on HEAD e59d753).
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I used only Read, Grep and Glob. I read the author report, the work order, the suite, core PartManifest.scala (ObservedFile, PartManifestVerifier.verify/verifyV2/compare, lines 240-439) and core PartManifestV2.scala (all of it). I grepped core for the schema labels and metrics. Glob listed modules/spark/src/**.
TEST RESULTS: not run, because the reviewer has no Bash. The author reports `sbt check` passing with sparkAnalytics at 3 passed, 0 failed, and 1254 passed overall. I did not observe that run.
VALIDATION RESULTS:
1. It is a genuine compatibility test. Verified:
   - The session is `master("local[1]")` (line 39). Driver host and bind address are 127.0.0.1, the UI is off, and there is 1 shuffle partition (lines 41-48).
   - Core records go through `Json.encode`/`upickle.write` and then `spark.createDataset(...)(using Encoders.STRING)` (line 161). No Scala 3 case class gets a Spark encoder. The `orderBy("value")` shuffle is followed by decoding and RunId re-derivation (lines 164-180).
   - Parquet is written from `createDataFrame(java.util.List[Row], StructType)` with an explicit schema, `coalesce(1)` and `ErrorIfExists`. It is read back with `.schema(parquetSchema)` (lines 186-212).
   - The row count comes straight from the footer: `ParquetFileReader.getRecordCount` (lines 94-99, 214-216), independent of Spark.
   - The SHA-256 is computed independently with the JDK (line 85). `PartManifestV2.build`, `verifyV2`, `checkRunManifest`, `PartManifests.decode` and `PartManifests.verify` are all exercised over bytes observed with `ObservedFile.of` (lines 249-270).
   - Counter-test 1 is real. `verifyV2` passes `f.format.lineDelimited` into `compare`, and `compare` emits "a non-empty file must end in LF" only when `lineDelimited && byteCount > 0 && !endsWithLf` (PartManifest.scala:352, 430-431). If `verifyV2` ignored the format, the assertion at line 288 would fail. If it applied the LF rule to Parquet, the empty-error assertion at line 267 would fail. `rowCount = max(observed, 1)` keeps `build` valid whether or not the bytes contain an LF.
   - Counter-test 2 is real. `dropRight(1)` changes both byteCount and sha256, and both messages match PartManifest.scala:415 and 427.
2. Hygiene. Verified:
   - Every write goes under `Files.createTempDirectory` (line 35): the warehouse, spark.local.dir, hadoop.tmp.dir, parquet-out and run directories.
   - `afterAll` stops the session in a try/finally and then deletes the tree (lines 54-56).
   - There are no reads of target/, ~/ScholarChain-archive, gold/ or any held-out path. Every Files.walk/list/readAllBytes runs under tmp.
   - The run directory holds only the 4 files written with java.nio, and the asserted path list confirms there are no stray .crc files.
   - The only output is two `println` lines on stdout, and nothing is written outside tmp. Nothing depends on randomness or time except the tmp name.
3. Test-data honesty: acceptable.
   - The `ResearcherMetricsV2` label is required because `fileChecks` refuses any label that is not a current schema (PartManifestV2.scala:162-169). It is disclosed in a comment (lines 229-230).
   - By design, `verifyV2` never inspects Parquet content (PartManifestV2.scala:15-18), so the label does not hide a defect this test could detect.
   - The synthetic RunManifest values (codeVersion "0"*40, the synthetic digest, snapshot dblp-synthetic-1) pass core validation and stay in tmp. They cannot reach a published run.
   - See finding N1 for the real gap the label sits next to.
4. Ownership: verified. Glob shows only `modules/spark/src/test/scala/scholarchain/spark/SparkParquetCompatibilitySuite.scala` added, besides the existing .gitkeep files. The session-start git status shows no modified tracked files: no build, core or main change. The two docs/agent-runs files are the orchestrator's.
5. Author risks. None blocks local step 1. All three are EMR-gate items:
   - **Hadoop version split.** sparkAnalytics must not import `hadoop` (CLAUDE.md module graph) and reads only local Parquet, so the 3.3.4 vs 3.4.1 split has no effect locally. It matters only for S3A and EMR-supplied (`Provided`) Hadoop.
   - **Scala binary for EMR.** Scala 3 can consume only the `_2.13` Spark artifacts. If EMR 7.x Spark is Scala 2.12, the jar will not run there. That is a hard blocker for the EMR gate and needs the human F2 decision before any cluster work. It does not affect local runs. The `startsWith("2.13.")` assertion (line 149) pins the current state on purpose and will flag any change.
   - **Loopback hostname warning.** It is cosmetic for local[1]. See finding N2.
VERDICT: APPROVE
FINDINGS:
spark-engineer:
- **N1 (nit).** /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/src/test/scala/scholarchain/spark/SparkParquetCompatibilitySuite.scala:229-239 and :220.
  - Failure input: a future Spark job publishes `metrics/part-*.parquet` labelled `scholarchain.researcher-metrics.v2` whose columns are not the ResearcherMetricsV2 fields, like this test's (researcherKey, year, jointPublicationCount). `verifyV2` accepts it, because core does not tie `recordSchemaVersion` to a Parquet column schema.
  - Impact: none in this test, and the label is disclosed. The realistic `metrics/` path plus the real label could be copied into step-1 producer code as if it were a verified layout.
  - Expected follow-up: step-1 producer tests, as part of the output-invariant gate, should assert that each Parquet part's StructType matches its labelled record schema. Optionally, use a neutral directory name here, such as `compat/part-00000.parquet`.
- **N2 (nit).** Same file, lines 23-24 (Scaladoc: "bound to the loopback address ... No network").
  - Failure input: on this machine Spark logged that the hostname resolves to loopback and advertised the executor host as 10.0.0.231 (author report, RISKS).
  - Impact: the doc claim is stronger than what was observed. Spark opens loopback listeners and does a local hostname lookup. There is no evidence of outbound traffic.
  - Expected fix: reword the comment to "loopback listeners only; no outbound network". Pinning SPARK_LOCAL_IP needs build-engineer, which is not required for step 1.
UNVERIFIED CONCERNS:
- **afterAll after a beforeAll failure.** If `getOrCreate` throws inside `beforeAll`, cleanup depends on MUnit calling `afterAll` after a failed `beforeAll`. I could not confirm that from the repository. It matters only for temp-dir leakage when the session fails to start.
- **Bind address.** The author says the RPC and block manager bound to 127.0.0.1 and nothing bound to 10.0.0.231. I did not observe this.
- **Reported results.** The `sbt check` counts and per-test timings come from the author's report, not from my own run.
ASSUMPTIONS: The file on disk is the one the author ran. The core sources at HEAD e59d753 are the ones it compiled against.
RISKS: Scala 2.13 vs EMR Scala 2.12 (F2) is the most serious open item for the EMR gate. Hadoop 3.3.4 vs 3.4.1 is a second, smaller one for S3A. Neither affects local step 1.
MANUAL ACTION REQUIRED: The human reviews and commits the test file. The human decides F2 (Spark, Scala and Hadoop runtime target) before any EMR work.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human acceptance of SP-P6-02 step 0. Then the step-1 work order. Its opt-in invocation gap (`sparkIT` needs build-engineer plus factory-engineer row activation), as recorded by the orchestrator in prompt-sp-p6-02-step-0.md, is outside this review.
```

---

## CR-P6-16: code-reviewer review of BE-P6-03 (opt-in sparkIT task) (2026-11-16), verbatim

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-16 (Phase 6). Focused, read-only, adversarial review of BE-P6-03 (build-engineer), the opt-in `sparkIT` task. Files reviewed: /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt, /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkItRuns.scala, /Users/taufeeqpatel/Desktop/ScholarChain/project/spark-it.properties, and /Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala (runForked at lines 716-803). Inputs read: be-p6-03-report.md and prompt-be-p6-03-sparkit.md.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I have no Bash tool. I used only Read, Grep and Glob. I read nothing under gold/, target/, the held-out paths or the archive.
TEST RESULTS: none observed by me. The author's `sbt check` result (exit 0, 1254 passed, verifySparkItGuards 91 cases) comes from a background run, and no reviewer or orchestrator observed its output (see F1).
VALIDATION RESULTS:
- Check 1, opt-in separation: verified, with one gap (F3).
  - `checkSteps` (build.sbt:523-534) keeps the previous order and adds `verifySparkItGuards` before `markTestRun`. I compared it with the order recorded in sp-p6-02-step-0-report.md:28.
  - `markTestRun` and `testOutcome` read only `Test / executeTests` (build.sbt:199-204, 426-430).
  - SparkIT has its own source directory, `src/it` (build.sbt:310). `SparkIT/definedTestNames` comes from SparkIT's own compile analysis.
  - The guard's SBT slot accepts only `<project>/{compile,Test/compile,test,scalafmt,Test/scalafmt,scalafmtCheck}` (guard-dangerous.sh:94, 164-165), and SBTTEST accepts only `<project>/test` (166-167). No active allowlist row can name a SparkIT-scoped key, so no agent shape reaches SparkIT. `sbt test` exiting 0 shows that the unscoped `test` resolves to Test.
- Check 2, portability: verified. guardSelfTest parses only the committed config (SparkItRuns.scala:1147-1153). It does one NOFOLLOW stat of the archive root. `Files.isDirectory` and `Files.exists` return false rather than throwing, and the result only goes into a log line (1154-1169), so it cannot fail `check`. All fixtures are in `Files.createTempDirectory` (851). Fixture preflights pass `inherited = Map.empty`.
- Check 3, archive path: verified as implemented.
  - The root is `user.home` plus the committed relative value (270-279). No user name is committed. The run IDs and the snapshot match dev-run-01.md.
  - Real path equals itself (293-298). On case-insensitive APFS this also refuses a differently cased value, because `toRealPath` returns the on-disk case and the layout listing compares exact case.
  - Every refusal listed in the report is present: inside or containing the repo (301-304), inside any target directory with canonicalised targets (305-309), exact layout, `_SUCCESS` regular, `_UNVERIFIED` absent with NOFOLLOW, `_parts.json`, held-out, gold and target/streams segments refused case-insensitively, `SCHOLARCHAIN_CFG_*` refused (167-175, 514).
  - The time-of-check to time-of-use window between the preflight and the fork needs a concurrent writer. I accept it as residual.
  - Gap: the contents of the run directories are not checked (F5).
- Check 4, forked JVM: verified.
  - Only fixed `-D` properties are passed (446-455).
  - Strip prefixes are AWS_, SCHOLARCHAIN_ and GIT_, and isolation variables are added after stripping (LocalRuns.scala:755-759).
  - Stdin is closed (773). The bound is 120 s, then destroy, a 10 s grace, then destroyForcibly (782-794). The working directory is the attempt directory (747).
  - The attempt directory is created with `createDirectory` (410). Run files are written with CREATE_NEW.
  - Nothing is deleted outside temp directories: `deleteTree` is used only on self-test fixtures.
- Check 5, pass semantics: verified as specified (461-481). Partial-run detection is limited (see Unverified concerns).
- Check 6: verified. Only the visibility of `runForked` changed (725-727). `stripPrefixes` already defaulted to `DefaultStrippedPrefixes` (line 89), and erDecisionGroupingDev already passes GIT_ (1783). The diff stat (4 lines) is consistent with this.
- Check 7: mostly verified. Coverage gaps are in F4.
- Check 8: F2.
- Check 9: F1.
ASSUMPTIONS:
- In sbt 1.11.7, `inConfig(SparkIT)(Defaults.testSettings)` defines `executeTests`, `bgRun`, `bgRunMain`, `fgRun`, `fgRunMain` and `console` in SparkIT. This is based on knowledge of sbt Defaults, not on a run.
- sbt-scalafmt 2.5.5 `scalafmtCheckAll` is `scalafmtCheck.?.all(anyConfigsInThisProject)`, defined only for Compile and Test. This is based on knowledge of the plugin source, not on a run.
- The previous `check` order is the one recorded in sp-p6-02-step-0-report.md:28. I could not run git diff.
RISKS:
- The archive is read-only by convention only, as the author disclosed.
- The 120 s bound has not been measured against real suites.
- Because of the time-of-check to time-of-use window, an archive change between the preflight and the fork is not seen.
MANUAL ACTION REQUIRED:
- The human decides whether integration attempt outputs need protection from `sbt clean` (F6). Any guard change would go to factory-engineer by work order. I propose no constitutional edit.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE:
1. Build-engineer, in a fresh context, re-runs `sbt check` in the foreground with a 600000 ms Bash timeout after addressing the minor findings (or after deciding to accept them), and reports the exact counts verbatim.
2. The orchestrator archives that output.
3. A focused code-reviewer re-check.
4. FE-P6-04 activation of `sbt sparkIT`.
VERDICT: REQUEST_CHANGES
FINDINGS:

Owner build-engineer:

F1 (major, evidence, not code). Location: /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/be-p6-03-report.md:19, 40, 119.
- What happened: the only `sbt check` for this diff ran as a background task. The audit records the Bash call when it returns, so `markTestRun` updated `.claude/.last-test-run` after the audited call ended. verify-tests-ran.sh (R1-b) then classified the marker as untrusted, and the next orchestrator call was refused as UNATTRIBUTED.
- Why the evidence is not enough: the orchestrator says it did not observe the output (report line 11). `verify-tests-ran.sh --check` only compares mtimes. It cannot show that this marker came from a passing run. So the definition of done (CLAUDE.md: `sbt check` passes, with observed counts) is not established to the project's own standard, even if the agent's account is probably true.
- Failure input: any background `sbt check`. A delayed write by test code would look identical.
- Impact: the commit would rest on test evidence that the hooks reject.
- Remedy: a fresh foreground `sbt check` with the Bash timeout parameter set to 600000 ms. The run took 212 s, well under the 600 s tool maximum. The report's claim that a run over 180 s "must run in the background" is wrong.
- No hook change is needed. Attributing background sbt runs would weaken R1-b, and that would be a factory-engineer constitutional matter. The orchestrator has no sbt row, so the re-run must be done by a subagent with @SBT-ALL, preferably build-engineer.

F2 (minor). Location: build.sbt:305-308; checkSteps entry `scalafmtCheckAll` at build.sbt:527.
- What is wrong: SparkIT sources (modules/spark/src/it/scala) are outside the format gate. `scalafmtCheckAll` covers only the configurations that define `scalafmtCheck` (Compile and Test). SparkIT extends Test, so at most `SparkIT/scalafmtCheck` delegates to Test's check, which re-checks the Test sources, not src/it.
- Failure input: a badly formatted file under src/it written outside the agent Write/Edit path, for example by the human, an IDE, or test-time code. `sbt check` still passes.
- Impact: the definition of done no longer covers formatting of all repository Scala. The risk is low today because no src/it source exists yet.
- Simplest fix:
  - add the plugin's per-configuration settings to SparkIT (`inConfig(SparkIT)(ScalafmtPlugin.scalafmtConfigSettings)`, using the 2.5.5 signature, which needs confirming in the plugin jar)
  - add `sparkAnalytics/SparkIT/scalafmtCheck` as an explicit step before `Test/compile`
  - add one verifySparkItGuards case asserting that the step is in checkSteps.
- Alternatively, the human accepts the gap explicitly until the first src/it suite lands.

F3 (minor). Location: build.sbt:308-328; claim at be-p6-03-report.md:63 ("This closes unbounded side routes").
- What is wrong: only `test`, `testOnly`, `testQuick`, `run` and `runMain` refuse. `Defaults.testSettings` also defines `sparkAnalytics/SparkIT/executeTests`, plus `bgRun`, `bgRunMain`, `fgRun`, `fgRunMain` and `console` in SparkIT.
- Failure input: `sbt sparkAnalytics/SparkIT/executeTests`, run by a human, an IDE or BSP, or an interactive shell. It compiles and runs the integration suites through sbt's test framework with no archive preflight, no 120 s bound, no attempt directory and no zero-test refusal.
- Mitigation: no active allowlist row can produce this shape (guard SBT slot, guard-dangerous.sh:94, 164-165), so agents cannot reach it. Hence minor.
- Fix: also override `executeTests`, `bgRun`, `bgRunMain`, `fgRun` and `fgRunMain` in SparkIT to refuse, and correct the claim. sparkIT forks its own JUnitCore and does not use `SparkIT/executeTests`.

F4 (minor). Location: SparkItRuns.scala:53 and 651-657 (the "tests every refusal" claim); report point 8 ("removing that check makes its case fail").
Refusals that no self-test case covers:
- the `MaxConfigBytes` size refusal (263-264)
- a symbolic-link `project/` directory (259)
- outputRoot "not a directory", where a regular file is placed at `modules/spark/target/spark-it` (394-399)
- verdict "exit 1 without a 'Tests run' summary" (473-478)
- the build.sbt wiring of every `refuseSbtRoute` override (311-327).

Failure input: delete any one of these checks, or the `SparkIT / test := ...` line. `verifySparkItGuards` still passes (91 cases), and `sbt test` still exits 0 because Test resolves first.
Impact: regressions go undetected, and the claim is overstated. Fix: add one case per item. For the route overrides, a meta-build-level check, for example via `Project.extract(state)` on whether the SparkIT test key's task throws, or at minimum a documented limitation.

F5 (minor). Location: SparkItRuns.scala:364-374. layoutProblems checks only the three marker names inside each run directory. It never lists or link-checks the rest of the run directory.
- Failure input: an archive run directory holding a part file that is a symbolic link, for example into modules/hadoop/target/local-pipeline/er-candidates/heldout/... or gold/**. `rsync -a` preserves symbolic links.
- Impact: the preflight passes, the integration tests follow the link and read held-out bytes, and any echo of them reaches the sbt task log under target/streams/**/sparkIT, which agents can read. The `_parts.json` checksum check in the tests would fail only after the read.
- Fix: a bounded NOFOLLOW walk of each run directory that refuses any symbolic link or non-regular, non-directory entry. It needs names and types only, no content.

F6 (minor). Location: SparkItRuns.scala:43 ("earlier attempts are never reused or deleted"); report line 80.
- What is wrong: `sbt clean check`, which is active for every @SBT-ALL identity, deletes modules/spark/target/ including every `spark-it/attempt-<n>/`. Unlike the held-out hadoop directory (guard rule heldout-run-protected), no guard protects these attempts.
- Impact: result.txt and run-config.properties, which may be cited as integration evidence, can be destroyed by a routine command. The claim is true only of the sparkIT task itself.
- Fix: reword the claim ("sparkIT never deletes; `sbt clean` does"). Require that integration evidence be copied into the phase report. A protection rule would be a human decision routed to factory-engineer.

Nit (build-engineer):
- SparkItRuns.scala:428-432: `used.max + 1` overflows at `attempt-2147483647` and produces `attempt--2147483648`.
- LocalRuns.namesHeldout (1032) does not match `held.out`, which SegmentChars permits. This is pre-existing and shared.

UNVERIFIED CONCERNS:
- Partial-run detection (check 5). JUnitCore's `OK (n tests)` uses the JUnit run count, which I believe includes assumption-failed tests. If a munit suite calls `assume(...)`, for example "skip if the archive is absent", or a `.flaky` test fails while `MUNIT_FLAKY_OK` is inherited (it is not stripped; only AWS_, SCHOLARCHAIN_ and GIT_ are), the skipped bodies count toward n and the run reads as PASS. A class whose `munitTests()` is empty contributes 0 while other classes keep n > 0. Classes that sbt's munit fingerprint does not detect (no-arg constructor required, abstract excluded) are silently not passed to JUnitCore.
  - I could not check the munit 1.1.1 and JUnit 4.13.2 behaviour from here.
  - Suggested hardening: a small JUnit RunListener main that reports run, ignored and assumption-failure counts per class, with PASS requiring zero assumption failures and a run count above zero for every class.
  - The inherited @RunWith(MUnitRunner) on munit.Suite is what makes JUnitCore run munit suites at all. I believe this but did not verify it in the jar.
- The forked JVM sets java.io.tmpdir under the attempt, but Hadoop's `hadoop.tmp.dir` defaults to `/tmp/hadoop-${user.name}`, not java.io.tmpdir. Spark or Hadoop local file-system code could write scratch data outside the attempt directory. Not verified.
- A test that prints `OK (5 tests)` and then calls `System.exit(0)` would yield a fake PASS, because the verdict takes the last captured summary line. Only adversarial test code could do this, and code review of src/it suites covers it.
- `testFull` may exist in sbt 1.11.7 as another SparkIT route (see F3). Not verified.
- The 91-case count and the PASS of verifyHeldoutGuards (23+17) and verifyErDevRunGuards (128) come from the unobserved background run (F1). I did not count the cases by hand.
```

---

## CR-P6-17: code-reviewer re-review of BE-P6-03 repair 01 (sparkIT) (2026-11-16), verbatim

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-17 (Phase 6). A focused, read-only re-review of BE-P6-03 repair 01 (build-engineer) against CR-P6-16. The work is uncommitted on HEAD e59d753. Files reviewed:
- /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt
- /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkItRuns.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkItJUnitRunner.java
- /Users/taufeeqpatel/Desktop/ScholarChain/project/spark-it.properties
- /Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala (runForked, lines 727-803)

Inputs read: repair-08.md, CR-P6-16 in review-report.md (lines 1429-1543), prompt-be-p6-03-repair-01.md, and the generated marker /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I have no Bash tool and used only Read, Grep and Glob. I read nothing under gold/, target/, the held-out paths, the archive, phase-05/human-gate.md or the saved sbt output under ~/.claude. I ran no Grep under gold/ or target/.
TEST RESULTS: none observed by me. The marker file agrees with the author's quoted final line: git_head e59d753…, worktree dirty, suites=90, tests_passed=1254, failed 0, errored 0, skipped 0, written_at 2026-11-16T21:44:00Z.
VALIDATION RESULTS:
1. F1 is closed. The only `sbt check` evidence now comes from one foreground, audited run, executed last, after every mutation was reverted:
   - The orchestrator's next Bash call was not refused (no UNATTRIBUTED marker change).
   - `verify-tests-ran --check` reported OK.
   - The marker's content matches the reported counts.
   - The agent observed the run and reported the counts verbatim, which is the CLAUDE.md standard. The orchestrator did not read the full output, but CR-P6-16 F1 did not require that.
2. F2 is closed.
   - build.sbt:316 applies `inConfig(SparkIT)(ScalafmtPlugin.scalafmtConfigSettings(SparkIT))`.
   - checkSteps (build.sbt:533-545) runs `sparkAnalytics/SparkIT/scalafmtCheck` (SparkItRuns.scala:139) before `Test/compile`.
   - `formatGateProblems` (SparkItRuns.scala:976-1007) checks four things in the loaded build: the setting is in SparkIT's own scope, it depends on SparkIT `unmanagedSources`, the SparkIT source directories contain modules/spark/src/it/scala, and the step order. A checkSteps counter-test is included.
   - scalafmtCheck reads sources only and does not compile. No src/it file exists yet (Glob shows only src/main/.gitkeep, src/test/.gitkeep and one Test suite). The archive is not touched.
3. F3 is closed for the 13 keys.
   - build.sbt:322-334 has one refusal per line. `refuseSbtRoute` and `refusedInput` (SparkItRuns.scala:903-913) throw at execution.
   - `routeWiringProblems` (937-968) takes the last setting in the exact SparkIT scope from `structure.settings`. It requires a build.sbt source position whose lines contain the refusal call. It also requires every RunCapableKey defined in SparkIT to be refused.
   - Removing a refusal line falls back to Defaults.scala and is caught (the author observed this; the logic matches). An override in a later line, a `~=` transform, or a setting from project/*.scala is also caught.
   - `testGrouping`, `definedTests` and `SparkIT/compile` only compute or compile. `tests` depends on `sparkAnalytics % Test` (test->compile), so it never sees SparkIT classes.
   - The check is not trivially satisfiable by accident. It is satisfiable on purpose (nit N1).
   - BSP is a remaining route I could not verify (Unverified concerns).
4. F4 is closed. Each new case fails if its check is removed:
   - Without the size check, the 70 KB comment-padded file parses, so the refusal case fails.
   - Without the `project/` link check, NOFOLLOW applies only to the last component, the file reads as regular, and the case fails.
   - Without the output-root-file check, `createDirectories` throws, the Try reports "EXCEPTION…", the substring is missing, and the case fails.
   - Without the exit check in "exit 1 without a result file", "ended with exit 1" is missing.
   - Without route or format wiring, the allowed case or the counter-tests fail.
   - Without MUNIT_ stripping, the predicate case fails.
   - Without the runner's MUNIT refusal, the real fork exits 2 with the nonce consumed instead of exit 4 with the nonce present.
5. F5 is closed. `runTreeProblems` (SparkItRuns.scala:442-489) uses `walkFileTree` with no FOLLOW_LINKS:
   - Links reach `visitFile` with link attributes and are refused by their own path. Their targets are never resolved or shown.
   - Entries that are neither regular files nor directories are refused as special files.
   - The depth bound is maxDepth+1, so entries at depth 5 are visited and flagged. The entry bound terminates the walk. `visitFileFailed` and `postVisitDirectory` errors are reported.
   - It runs only on run directories that layoutProblems already confirmed are real directories.
   - All fixtures live under `Files.createTempDirectory`. The held-out-named link target is a string under that temp directory, and the test asserts it is never created. `deleteTree` deletes links, never their targets.
6. F6 is closed. The wording at SparkItRuns.scala:46-49, 521-533 and 891 says that `sbt clean` removes attempt folders. No "never deleted" claim remains (Grep). The `sbt sparkIT` row is still `inactive:B10-alias-pending` (bash-allowlist.tsv:105). No protection rule was added, which matches the human's direction.
7. Pass rule: verified, with one unverified munit concern.
   - Nonce:
     - 32 SecureRandom bytes, written create-new in runner/ (613-623).
     - The runner refuses on MUNIT_* (exit 4) before touching the nonce.
     - It reads and deletes the nonce before `Class.forName` of JUnitCore or any test class (Runner.java:86-118). No JVM option loads classpath code earlier. The nonce is never logged; the fork log line shows only the nonce file path.
   - Result file and parse:
     - The runner writes the result file CREATE_NEW after the last class, so a file pre-planted by test code gives exit 3.
     - `readResult` requires the file to exist, be regular and NOFOLLOW, and be at most 1 MiB.
     - `parseResult` (667-704) requires the format line, the nonce on the first and last lines, a final LF, class or error lines only, and a matching classes= count.
   - Verdict (714-754):
     - exit 0 and nonce consumed;
     - missing, extra and repeated classes are each refused;
     - any error line is refused;
     - per class: run>0, failures 0, ignored 0, assumptionFailures 0.
   - These cases each produce NOT a pass:
     - System.exit (no file)
     - a printed summary (stdout is never parsed; a summary line inside the file is malformed)
     - a forged nonce
     - a truncated file or a missing complete= line
     - MUNIT_* (exit 4, checked by a real fork)
     - a missing JUnit (exit 2, checked by a real fork)
   - Runner:
     - It uses JUnit reflectively and has no nested class (`runnerClassBytes` checks `getDeclaredClasses` and `$1`, plus the class-file magic).
     - It is compiled and run on the same JDK, because `javaHome` is unset and runForked falls back to sbt's `java.home`. A mismatch would also fail the self-test's fork.
     - The runner directory goes first on the classpath. The nonce resource there is deleted before any test code loads.
   - Residual (disclosed by the author, not a defect): deliberately adversarial test code in the same JVM can defeat any in-process rule. Examples are a shutdown hook that rewrites the result file and then calls `Runtime.halt(0)`, or a heap dump. Code review of src/it remains the control.
8. No regressions found.
   - LocalRuns.scala: only the earlier `runForked` visibility change; this repair changes nothing there.
   - checkSteps keeps the earlier order plus the two SparkIT steps. sparkIT is not in check.
   - sparkAnalytics depends on core only. The Spark and Scala settings (build.sbt:299-309) are unchanged in kind.
   - The routine self-test does one stat of the archive root.
   - verifyHeldoutGuards 23+17, verifyErDevRunGuards 128 and erDecisionGroupingDev: reported unchanged. I did not count the cases by hand.
   - Both carried nits are recorded and still present (SparkItRuns.scala:549; LocalRuns.namesHeldout).
ASSUMPTIONS:
- sbt's `structure.settings` order is application order, so the last setting is the effective one for `:=`.
- In sbt 1.11.7, the SparkIT config id `SparkIT` resolves on the command line.
- JUnit 4.13.2 `Result.getAssumptionFailureCount` exists. If it does not, the runner records an error line and fails closed.
RISKS:
- The runner is unobserved against real munit or JUnit until the first `sbt sparkIT`.
- Time-of-check to time-of-use between the preflight and the fork. A hard link to other bytes inside a run directory is not detectable by names and types (it needs a deliberate act).
- Hadoop `hadoop.tmp.dir` may write outside the attempt directory (carried).
- The socket case is skipped, with only a log line, on systems that cannot create it.
MANUAL ACTION REQUIRED:
- Human: review and commit.
- After the first real run, copy attempt-<n>/junit-result.txt and result.txt into the Phase 6 evidence.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: FE-P6-04. factory-engineer prepares the standalone `sbt sparkIT` activation for human ratification and a session restart. Then spark-engineer adds the first src/it suite, and the first real run's evidence is archived.
VERDICT: APPROVE
FINDINGS:
Owner build-engineer (all nits, none blocking):
- N1 (nit). Location: /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkItRuns.scala:955-960. The wiring check is a text match on the setting's build.sbt line range. Failure input: `SparkIT / test := (Test / test).value // refuseSbtRoute("test")` passes the check while running tests. Impact: only a deliberate build.sbt edit can defeat it, and code review controls that. Optional fix: also match the setting's source line against the exact `SparkIT / <key> := SparkItRuns.refuse…("<key>")` shape.
- N2 (nit, carried). Location: SparkItRuns.scala:549. `used.max + 1` overflows at attempt-2147483647.
- N3 (nit, carried, pre-existing and shared). `LocalRuns.namesHeldout` does not match `held.out`, which SegmentChars permits.
UNVERIFIED CONCERNS:
- BSP route. I believe sbt 1.4+ includes `BuildServerProtocol.configSettings` in `Defaults.configSettings`, so `inConfig(SparkIT)(Defaults.testSettings)` would make SparkIT a BSP build target. I also believe BSP `buildTarget/run` (`runMainClassTask`) forks a main class from `fullClasspath` directly, without the `run` or `runMain` keys, so the refusals would not apply. Failure input: an IDE or Metals run request on a main class in src/it. Only a human can do this; agents cannot. Suggested hardening (build-engineer): `SparkIT / bspEnabled := false`, and add `bspEnabled` to the wiring check. Not verified against the sbt 1.11.7 source.
- munit `.only`. I believe that when one test is marked `.only`, munit silently filters out the other tests in that suite without reporting them as ignored. The class would then show run>0 with ignored=0 and pass with fewer tests. This would be an accidental skip that the human's "no tests skipped" direction is meant to exclude. I could not check whether munit 1.1.1 reports or refuses `.only`, for example under `CI=true`. A possible hardening: set `CI=true` in the child environment if munit then refuses `.only`, or review src/it for `.only`.
- The special-file case and the runner forks are as the author describes and depend on the OS. I did not observe them.
```

---

## CR-P6-18: code-reviewer review of FE-P6-04 (inactive sbt sparkIT row) (2026-11-16), summary of the verbatim report

The harness flagged the hand-back as matching an instruction-shaped pattern (settings-json); the report only states that no `settings.json` rule names sparkIT.

- STATUS: COMPLETE. VERDICT: APPROVE. Commands run: none (Read and Grep only; nothing under gold/, target/, the archive or phase-05/human-gate.md).
- Check 1 (row scope and justification): verified. bash-allowlist.tsv:105 is `spark-engineer`, `inactive:FE-P6-04-ratification-pending`, `allow`, `-`, `sbt sparkIT`; no other row names sparkIT; every justification claim matches build.sbt (98, 471, 533-545) and SparkItRuns.scala (89, 99, 106-107, 110, 223, 238, 327, 381, 442, 526, 714, 854, 1065) and spark-it.properties 15-18.
- Check 2 (probe verdicts, guard-dangerous.sh): verified for the inactive state (dormant check at 510-515 gives entry-inactive for the four [FE-P6-04 T] probes) and for the active state after edits (a)-(e) (the bare command matches with no decision = ALLOW; trailing-token forms give no-allowlist-entry). Same-in-both-states refusals, the semicolon gate (493), held-out rules first (505) and every other identity verified. settings.json has no rule naming sparkIT and no broad sbt allow; build.sbt:321-334 refuses every SparkIT run-capable route.
- Check 3: no coverage lost from turning the old B10 probe (probes.tsv:125) into a comment; the payload is re-probed at 742 with the stricter `entry-inactive` substring.
- Check 4: only bash-allowlist.tsv, probes.tsv and docs/agents.md changed among constitutional files; held-out rules and Phase 5/D21 probes intact.
- Check 5 / RISKS: provenance `-` and no HEAD check in SparkItRuns.scala, so activating before BE-P6-03 is committed would run uncommitted build code. Test code under src/it is opaque (disclosed).
- Recommended order: (1) commit BE-P6-03 with the inactive FE-P6-04 change (or BE-P6-03 first); (2) ratify FE-P6-04 and record the BE-P6-03 commit SHA in human-gate.md; (3) in a fresh session route activation edits (a)-(e) to factory-engineer, adding the BE-P6-03 commit hash to the justification (F2), then validator and --probes; (4) human commits the activation alone with a clean build.sbt and project/, then restarts; (5) only then spark-engineer runs `sbt sparkIT`.
- Findings (factory-engineer): F1 (nit) the justification "writes only modules/spark/target/spark-it/attempt-<n>/" understates the write set (SparkIT compile output, sbt task streams, the spark-it/ root); suggested "task outputs only under … (plus sbt compile output)". F2 (minor) the row cites CR-P6-16/17 with no commit hash, unlike the FE-P6-01 row; add the BE-P6-03 commit SHA at activation.
- Unverified: the HEAD text of old probes.tsv:125 (no git access); the 728/38 probe PASS (author- and orchestrator-reported).
