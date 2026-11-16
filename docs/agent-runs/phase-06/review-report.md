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
