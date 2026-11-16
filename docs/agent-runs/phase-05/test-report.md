# Phase 5 (main) test reports

## TE-P5-01: test-engineer, adversarial and cross-module tests (final report, verbatim)

Orchestrator notes (2026-11-06):
- The agent was interrupted once by an API usage limit and resumed.
- Its final `sbt check` was moved to the background by the harness after 180 s (task
  b2a8jso6a). The result was not observed by the agent or the orchestrator.
- The orchestrator made no Bash call between that hand-back and this archive, pending human
  review of the tree and the test marker.
- Counts below are from the agent's run 1 JUnit XML (agent-reported).
- Process finding: `sbt check` now runs close to or over the 180 s harness auto-background
  limit. This affects every agent's ability to run the definition of done in the foreground.

---

TE-P5-01 report (test-engineer). STATUS: FAILED. Two reasons:
1. Five new tests expose real production defects. I kept them failing.
2. The final `sbt check` did not finish in the foreground. The harness moved it to the background after 180 s. The work order says to stop in that case, so I stopped. Its result was not observed. No counts below come from that run.

## What the run looked like
- **Run 1 (completed, foreground).** Its console output was truncated, so the per-suite counts come from the JUnit XML reports under `/Users/taufeeqpatel/Desktop/ScholarChain/tests/target/test-reports/`.
- **Run 2 (moved to the background).** The tool's message, verbatim: "Command was moved to the background (ID: b2a8jso6a) after running for 180s. Output is being written to: /private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/tasks/b2a8jso6a.output. … It is running now".
- After that I made no further Bash calls and did not read the output file.
- Tests are expected to fail, so markTestRun should not write `.claude/.last-test-run`. If the human sees the marker change anyway, the audit hook may report it as UNATTRIBUTED on the next Bash call.

## Defects (failing tests, kept)

**D1. RV-P5-04 M1 reproduced. MEDIUM. Owner: entity-resolution-engineer.**
- Test: `MembershipEvaluationAdversarialSuite`, "a whitespace-padded label is either refused at ingest or counted as that label (RV-P5-04 M1)".
- Input: SYNTHETIC label `" match"` on an ACCEPTED row, plus one normal `"match"`.
- Expected: either ingest refuses it, or evaluation counts 2 definitive labels with TP + FP = 2.
- Observed: ingest accepts it. Then `definitiveGoldLabels` = 1, `coverage.definitiveLabels` = 1, TP + FP = 1. The label silently drops out of precision and recall.

**D2. RV-P5-04 m10 reproduced, new trigger. LOW-MEDIUM. Owner: domain-engineer.**
- Test: `MatchContractCrossModuleSuite`, "repository URLs: rejected values never echo credentials in validation errors (RV-P5-04 m10)".
- Input: `" https://deploy:S3cr3tT0kenSynthetic@gitlab.example.org/group/repo"` (leading space).
- Observed, verbatim: `MalformedId(value,repository-url, https://deploy:S3cr3tT0kenSynthetic@gitlab.example.org/group/repo,must be non-empty printable ASCII without whitespace)`.
- Cause: the `Identifiers.errorDisplay` user-info pattern is anchored at `^scheme://`, so a value with leading whitespace is shown unredacted. A password containing `/` should also escape the pattern by inspection, but the test stopped at the first leak, so that case was not executed.

**D3. False merge of query-named repositories. LOW. Owner: domain-engineer.**
- Test: `MatchContractCrossModuleSuite`, "repository URLs: two repositories named only by a query parameter never share a canonical ID".
- Input: `https://git.example.org/gitweb/index.cgi?p=alpha.git` and `…?p=beta.git`.
- Observed: both give `Right(https://git.example.org/gitweb/index.cgi)`, because the query is dropped. The documented principle is "refuse rather than risk a merge".
- Decision impact today is nil: repository-url is T3, evidence only.

**D4. Blind-view homonym leak after U+200B. LOW. Owner: entity-resolution-engineer.**
- Test: `BlindViewLeakageAdversarialSuite`, "adversarial: a homonym number behind non-ASCII spacing is still masked".
- Input: `"Quentin Synthwell"+U+200B+"0001"`.
- Observed: the name is displayed as `Quentin Synthwell​0001` (twice: name as written and author list).
- The masking rule's `(?U)\s+` does not match a zero-width space. U+00A0, U+202F and U+2007 are masked correctly.
- This is NIT-2 showing up in the display. The parser limitation itself still holds unchanged.

**D5. Percent-encoded dblp person URL not masked. LOW. Owner: entity-resolution-engineer.**
- Test: `BlindViewLeakageAdversarialSuite`, "adversarial: a percent-encoded dblp person URL embedded in another link is masked".
- Input: ee link `https://redirect.example.org/out?to=https%3A%2F%2Fdblp.org%2Fpid%2F00%2F9001.html`.
- Observed: displayed unmasked, so pid 00/9001 is readable.
- decisions-04 asks for masking "including identifiers embedded in URLs". Not seen in real data.

## Characterization tests (assert current behaviour; not failures)
- **OPEN POLICY GAP 1:**
  - the title-year-authors rule ACCEPTS a preprint (arXiv ID only, or no ID) against its published DOI record on the deterministic path;
  - a high score ACCEPTS it on the probabilistic path.
- **OPEN POLICY GAP 2:** a published record listing its preprint's arXiv ID is ACCEPTED against the preprint (exact-id, confidence 0.99, no stage check).
- **RV-P5-04 m7 reproduced:** two versions of one arXiv ID are neither a disagreement nor an agreement. Candidate agreements are empty, and the pair is decided by the score (REJECTED in the fixture).
- **ER-P5-07 Risk 4:** an openalex-author agreement alone still ACCEPTS at 0.90.
- **New finding, LOW, owner entity-resolution-engineer:** `PersonName.parse("Synthwell 0002, Quentin")` keeps the number in the family key (`synthwell0002`, no suffix). The parser documents only trailing numbers, and DBLP does not write this form.
  - My first version of this test asserted `synthwell`. That expectation went beyond the documented behaviour, so I changed it to a characterization (logged here, not hidden).
- **Accepted Unicode limitations still hold, no silent fix:**
  - a Cf character between words joins them (`Jun`+ZWSP+`Wang` → `junwang`);
  - NIT-2: `Frost`+ZWSP+`0001` → `frost0001`, no suffix;
  - NIT-1: lone surrogates around a Cf character → `fold` = "", `TitleText.words` = U+10000.

## What passed (run 1)
- **Exact-ID precedence:**
  - T1 disagreements are never ACCEPTED across 48 researcher and 90 work combinations, and always CONFLICT when another ID agrees;
  - ACCEPTED never carries conflicts;
  - override reason codes and rules match the documentation;
  - a T2 agreement contradicts a blocking disagreement (CONFLICT) rather than overriding it;
  - record keys only cap ACCEPTED (never CONFLICT, never REJECTED alone, score unchanged);
  - evidence-only schemes change nothing (metamorphic check);
  - the P-6 weight is 0.
- **Cluster corroboration:**
  - alone, a cluster agreement is UNRESOLVED and never sent to the LLM;
  - a corroborated agreement is ACCEPTED at 0.95;
  - preprint vs version of record is never ACCEPTED;
  - each clause passes exactly at its bound (0.8, 0, 0.9) and fails one ulp away;
  - missing inputs fail closed.
- **LLM band and adjudication:**
  - the band is inclusive at both ends and exclusive one ulp outside (set via config);
  - abstain gives UNRESOLVED on the LLM path;
  - 18 adversarial responses are refused: escaped duplicate key, homoglyph, BOM, wrong schema version, injected agreements, an identifier injected through endpoint text, an over-long response, and others;
  - exact-id evidence must match byte for byte;
  - cache key = (sha256 of the request, prompt version, model version, policy version), and poisoned entries are never served;
  - CONFLICT, blocked, forged and cluster pairs are never sent.
- **Contracts and config:**
  - every Resolver outcome kind round-trips through match-decision.v2;
  - 17 inconsistent JSON variants are refused;
  - v1 and v3 decision schemas are refused;
  - config band ordering is enforced by `EntityResolutionConfig.problems`, `withConfig` and the loader (env overrides), and config v1 is refused;
  - policy v1 and v2 are refused by file schema and by name;
  - the Semantic Scholar normalizers behave as specified;
  - gitlab subgroups stay distinct and only github folds case.
- **Blind view and leakage:**
  - no system output or membership field appears in the blind view;
  - pids, person links and homonym numbers are masked, publication evidence is kept, and markers leak no equality;
  - compiled matching code never references review or evaluation classes, and membership code never references policy, adjudication or decisions (bytecode scan).
- **Membership and evaluation:**
  - member IDs are stable across versions;
  - membership is independent of outcomes and input order, sampling is seeded and proportional, and the census is never padded;
  - the manifest is create-once;
  - a freeze mismatch and `--provisional` are refused;
  - label-file refusals work, and masked references cannot be replayed on another member;
  - labels join by member ID across policy versions;
  - not-generated, unsure and unlabeled members are reported separately;
  - 199 reviewed members withholds metrics (exit 3); 200 reviewed including 41 unsure reports them (exit 0) with reviewed and definitive counts shown distinctly.

```text
STATUS: FAILED
OWNER: test-engineer
WORK ORDER: TE-P5-01 (Phase 5 main): independent adversarial and cross-module tests of the Phase 5 entity-resolution work
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/ErFixtures.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/ExactIdPrecedenceAdversarialSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/ClusterCorroborationAdversarialSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/AdjudicationAdversarialSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/MatchContractCrossModuleSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/BlindViewLeakageAdversarialSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/MembershipEvaluationAdversarialSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/UnicodeNormalizationRegressionSuite.scala
FILES MODIFIED: none outside the files created above (no production, build, prompt, fixture-of-others, or marker file touched)
COMMANDS RUN:
- git ls-files (several); git ls-files --others (two, incl. tests/target/test-reports); git diff --stat; git diff (three paths); git show --stat 127819b: ok
- git status --short -- tests: REFUSED by guard (no-allowlist-entry), nothing ran
- sbt tests/test: REFUSED by guard (no-allowlist-entry), nothing ran
- sbt Test/compile: success
- sbt check (run 1, foreground): exit 1, test failures; console output truncated, results read from JUnit XML
- git status --porcelain (on resume): ok, no audit refusal; only known Phase 5 paths plus tests/src/test/scala/scholarchain/tests/er/
- sbt check (run 2, final): moved to the background by the harness after 180 s (ID b2a8jso6a); result NOT observed; stopped per the work order
TEST RESULTS (run 1, from XML; the suites in tests/** at that time):
- ExactIdPrecedenceAdversarialSuite: 10 passed, 0 failed, 0 skipped
- ClusterCorroborationAdversarialSuite: 9 passed, 0 failed, 0 skipped
- AdjudicationAdversarialSuite: 9 passed, 0 failed, 0 skipped
- MatchContractCrossModuleSuite: 8 passed, 2 failed (D3 query merge; D2 credential echo, m10), 0 skipped
- BlindViewLeakageAdversarialSuite: 6 passed, 2 failed (D4 ZWSP homonym; D5 percent-encoded pid), 0 skipped
- MembershipEvaluationAdversarialSuite: 12 passed, 1 failed (D1 whitespace label, M1), 0 skipped
- UnicodeNormalizationRegressionSuite: 10 passed, 1 failed (my own comma-form homonym expectation, since converted to a characterization; the suite now has 12 tests)
- Expected after the edits (not observed): 5 failing tests (D1 to D5); the ER suites total 70 tests.
- Other suites (core, entityResolution, hadoop, Phase 4 tests/**): not observed in either run; I read no counts for them.
VALIDATION RESULTS: no gold-set evaluation run; every input is SYNTHETIC; no metric here is a gold metric. Five defects listed above, with owners; reproductions of RV-P5-04 M1, m7 and m10 cited by ID.
ASSUMPTIONS:
- An uncorroborated cluster agreement counts as an agreement for contradiction steps 2 and 3, as ER-P5-07 states.
- Query-dropping in repository URLs (D3) and the U+200B and percent-encoded masking gaps (D4, D5) are defects against the documented "never merge" principle and the decisions-04 masking instruction; the human may accept them instead.
RISKS:
- The final run was not observed; the counts are from run 1.
- The test marker may have changed during the backgrounded run, between audited calls.
- D3, D4 and D5 involve inputs not seen in the current data.
- RV-P5-04 m1, m4, m5 and m6 were not independently tested.
MANUAL ACTION REQUIRED:
- Human: check whether background run b2a8jso6a has finished, and review `git status` / `git diff` plus the test marker before any further Bash call.
- Human: decide Option A gaps 1 and 2.
- Re-run `sbt check` in the foreground after the fixes are routed.
BLOCKED-ON OWNER: entity-resolution-engineer (D1, D4, D5); domain-engineer (D2, D3)
BLOCKED-ON EVIDENCE: the failing tests and observed outputs in the Defects section above.
NEXT GATE: owners fix D1 to D5 (or the human accepts D3 to D5 as documented limits); then a foreground `sbt check` by an agent whose run is not backgrounded; RV-P5-04 follow-up; human decision on the Option A gaps.
```

---

## TE-P5-02 (test-engineer) — hand-back 2026-11-11, verbatim

Orchestrator note (not part of the report): routed after ER-P5-08's foreground `sbt check` failed on exactly 7 tests/** tests asserting pre-decision behaviour. Orchestrator check after hand-back: `.claude/hooks/verify-tests-ran.sh --check` → `verify-tests-ran --check: OK (no uncommitted source change is newer than .claude/.last-test-run)`.

All 7 failing tests now expect the approved er-policy.v4 / er-adjudication-prompt.v2 behaviour. My single foreground `sbt check` exited successfully with 0 failures in every project. Each failure was caused only by the approved change; I found no production defect.

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: TE-P5-02 (Phase 5). Update the 7 `tests/**` ER characterization and version-pin tests to the human-approved Option A publication-stage target (er-policy.v4, prompt v2), and keep the D1–D5 regression tests unchanged.

FILES CREATED: none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/ClusterCorroborationAdversarialSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/MatchContractCrossModuleSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/AdjudicationAdversarialSuite.scala
- Not touched: ErFixtures.scala, MembershipEvaluationAdversarialSuite, BlindViewLeakageAdversarialSuite, the D2/D3 repository-URL tests, every other test, production code, the test marker, hooks, settings, and docs/agent-runs.

What I checked for each failure (production code read only: Resolver.scala, MatchPolicy.scala, Corroboration.scala, Adjudication.scala, AdjudicationProtocol.scala, er-policy.v4.json, prompt v1/v2 headers):
1. GAP 1: v4 Resolver steps 7–8 turn ACCEPTED into UNRESOLVED when the stage is cross-stage or uncertain (`stageBlocks`). Arxiv vs DOI gives CrossStage; no ID vs DOI with no venue gives Uncertain.
2. GAP 2: preprint (arXiv only) vs published (arXiv + DOI) is Preprint vs Mixed, so the verdict is Uncertain. `arxivBlocked` then applies, step 6c gives (UNRESOLVED, exact-id, 0.0), and the reasons are `arxiv-agreement-cross-stage` and `publication-stage-uncertain`.
3–6. `DefaultResource` is now er-policy.v4.json. v3 is in `RetiredPolicyVersions` and `RetiredFileSchemaVersions`, prompt v1 is in `RetiredPromptVersions`, and `withConfig` refuses a policy or prompt version mismatch.
7. `Adjudication.eligible` now requires `o.stage.exists(_.established)` for work pairs. The old fixture had no markers and no venue, so it was Uncertain.

Changes:
- ClusterCorroboration "OPEN POLICY GAP 1" was renamed to "publication stage (decisions-06, gap 1 closed): ...". It now asserts:
  - UNRESOLVED on the deterministic and probabilistic paths, with rule `<path>.accept-blocked-by-publication-stage` and reasons `publication-stage-cross-stage|uncertain` plus `acceptance-blocked-by-publication-stage`.
  - Not band-eligible, `Adjudication.eligible` false, and the match decision still validates.
  - Cases covered: arxiv vs DOI, preprint DOI vs no ID, arxiv vs DOI with the same venue, no ID vs DOI, a strong score, and band scores (cross-stage and uncertain, with `llm-band-excluded-by-publication-stage`).
  - Positive controls: same-venue is ACCEPTED, two preprints are ACCEPTED.
- "OPEN POLICY GAP 2" was renamed to "publication stage (decisions-06, gap 2 closed): ...". Across 5 feature profiles and both orientations it asserts (UNRESOLVED, exact-id, 0.0), with rule and reason `arxiv-agreement-cross-stage` and `publication-stage-uncertain`, and no `exact-id.agree.arxiv`. It is not LLM-eligible and has no conflicts. A T1 DOI disagreement next to the shared arXiv ID stays CONFLICT. Positive control: two pure preprints sharing the arXiv ID are ACCEPTED at 0.99.
- MatchContract outcome-kind test:
  - Pins er-policy.v4.
  - Every produced kind now has a required (outcome, path) expectation; before, only 3 of 14 were checked. A key-set equality check stops any entry going unchecked.
  - The "deterministic ACCEPTED" fixture now has venue-ID agreement. Under v4 the old fixture was actually UNRESOLVED, so the label was wrong.
  - Added "stage-blocked UNRESOLVED" and "arxiv cross-stage UNRESOLVED" kinds, each checked for its reason code. These entries are used only by this test.
- Config band test: uses v4 + prompt v2, with a positive control first. Policy versions v1, v2, v3 and the unknown v5 are refused, and so are prompt v1 and the unknown v3.
- Config loader test: the HOCON names v4 and prompt v2, which are checked after loading. A loaded file naming er-policy.v3 or prompt v1 is refused by `withConfig`.
- Policy versioning test: renamed to "v4 and prompt v2 are the default; v1, v2, v3 and prompt v1 are refused ...". It checks:
  - The default resource is v4.
  - The v1, v2 and v3 resources are refused.
  - The names v1, v2 and v3 inside a v4 file are refused.
  - File schemas v2 and v3 are refused, with checks that each text replacement actually applied.
  - A v4 file naming prompt v1 is refused.
  - The loaded template version is v2, the v1 template is refused, and a v2 body relabelled v1 is refused.
  - The existing extra-key, swapped-band and calibration-wording checks are kept.
- Adjudication byte-for-byte test: the `repoBand` fixture (used only by this test) now has `venue.id_both`/`venue.id_agree`. The verdict is same-stage-venue, and p is about 0.54 by hand calculation, still inside [0.4, 0.7]; the test asserts it is in band. All the byte-for-byte refusal checks and the positive control are kept. Added: without the venue the stage is Uncertain, the pair is not eligible, and 0 adjudicator calls are made.

COMMANDS RUN:
1. `git ls-files -- tests modules/entity-resolution` (ok)
2. `git ls-files --others --exclude-standard -- tests modules/entity-resolution` (ok)
3. A grep pipeline: refused by guard-dangerous.sh, rule shell-syntax-gate (a `"` character). This was a normal default-deny of my own malformed call, not an audit or UNATTRIBUTED refusal. Nothing ran; I switched to Read.
4. `git status --short` (ok)
5. `sbt check`: one foreground run from the repo root, `[success] Total time: 120 s`
6. `git ls-files --others -- tests/target/test-reports` (ok; used to find the JUnit XML)

TEST RESULTS (from the `sbt check` console, read from the saved full output at its end):
- core: Passed: Total 299, Failed 0, Errors 0, Passed 299
- entityResolution: Passed: Total 302, Failed 0, Errors 0, Passed 302
- hadoop: Passed: Total 67, Failed 0, Errors 0, Passed 67
- tests: Passed: Total 120, Failed 0, Errors 0, Passed 120
- rag, root, services, sparkAnalytics, storage: Total 0 each ("No tests to run")
- markTestRun line: `wrote .../.claude/.last-test-run (git_head=1bc5382c00200b76d03f6a88ffdbbac53220760e, worktree=dirty, suites=63, passed=788, skipped=0)`
- Ignored or skipped: 0. Failing tests: none.
- The console output was 27.3 MB, so the tool truncated it to a preview. The full text was saved to a file and I read the summary lines above from its end. As a cross-check, the JUnit XML reports (labelled as such) show:
  - ClusterCorroborationAdversarialSuite tests=9 failures=0 errors=0 skipped=0
  - MatchContractCrossModuleSuite tests=10 failures=0 errors=0 skipped=0
  - AdjudicationAdversarialSuite tests=9 failures=0 errors=0 skipped=0

VALIDATION RESULTS: The scalafmt check passed (3 test sources checked) and the tests project compiled. The build-sources guard and the version-ledger check passed. No audit hook refused any call.

ASSUMPTIONS:
- When a published record lists the preprint's arXiv ID, the verdict is "uncertain" (a mixed side), not "cross-stage". This follows the v4 policy definition, so GAP 2 asserts `publication-stage-uncertain` along with `arxiv-agreement-cross-stage`.
- Strengthening the outcome-kind and refusal checks inside the 7 tests counts as an "expectation update". I changed no other test.

RISKS:
- I did not run the strengthened tests against a reverted v3 build. Their adversarial value comes from reasoning: each asserts specific v4 reason codes, rules and outcomes that v3 did not produce.
- `sbt check` writes 27 MB of Hadoop DEBUG/INFO logging, which hides the console summary. I am noting this for build-engineer; it is not a defect.

MANUAL ACTION REQUIRED: The human reviews the diff of the 3 test files and commits.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer reviews the TE-P5-02 diff, then human acceptance of the Phase 5 test report.

---

## TE-P5-03 (test-engineer) — hand-back 2026-11-11, verbatim

Orchestrator note (not part of the report): routed per decisions-07.md after ER-P5-09; the work order reserved the single `sbt check` for the human (recovery-04.md). No test counts exist yet for this work order.

I updated the v4 pins, added the decisions-07 adversarial coverage, and confirmed everything compiles. No tests have been run, because `sbt check` is yours to run, so every pass or fail below is a prediction.

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: TE-P5-03 (Phase 5). Retarget the tests/** version pins to er-policy.v5 and add adversarial coverage for decisions-07 (Option 1, preprint-DOI agreement).

FILES CREATED:
none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/MatchContractCrossModuleSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/ClusterCorroborationAdversarialSuite.scala

COMMANDS RUN:
1. `wc -l <8 suite paths>`: refused by guard-dangerous.sh, rule `shell-syntax-gate` ("the command is longer than 400 bytes"; `wc` is also not an allowed shape). Nothing ran. This was the command guard, not an audit-hook report, so I carried on and read the files with Read instead.
2. `git ls-files --others --exclude-standard -- modules/entity-resolution tests`: succeeded.
3. `git ls-files -- modules/core/src/main/scala modules/entity-resolution/src/main/scala/scholarchain/entityresolution`: succeeded.
4. `git diff --stat`: succeeded. The tests/** files are untracked, so they do not appear in it.
5. `sbt Test/compile`: succeeded. Output: "compiling 2 Scala sources to .../tests/target/scala-3.3.6/test-classes ... done compiling", "[success] Total time: 4 s".
- No other sbt command was run: no `sbt check`, no `sbt test`. No AWS, paid, git-write or commit commands.
- The Grep tool is not available in this session, and `grep` is not on my Bash allowlist, so I read every suite in full.

TEST RESULTS:
- Not run. The work order reserves the single `sbt check` for the human, so I have no observed pass, fail or ignored counts for any suite.
- Observed: the tests project compiles (the 2 changed sources).
- Predicted, not observed: all tests in the two changed suites pass under er-policy.v5, and the other tests/er suites are unchanged and unaffected.
- The one prediction that could fail is the bypass-attack step in the new normalization test (see RISKS). If it fails, the message starts with "BYPASS:" and the owner is entity-resolution-engineer, or domain-engineer if the gap is in `MatchCandidate` validation.

Audit of every tests/er suite (production code read-only):
- **MatchContractCrossModuleSuite** is the only suite with v4 pins: the four tests ER-P5-09 predicted. Each fails only because the default moved to v5 under decisions-07. One of them (line 258 in the old file) expected v5 to be *refused*, which was correct under v4 and is now inverted.
- **No suite expects a cross-stage pair whose only shared DOI is the preprint DOI to be ACCEPTED.** Every shared DOI in the existing tests is a publication DOI (`doiA`, `10.9999/synthetic.te.shared`). `preprintDoi` appears only on one side, or next to an arXiv ID without a DOI agreement.
- **MembershipEvaluationAdversarialSuite**: its `er-policy.v4` strings are synthetic row labels, not pins of the active policy. Unchanged.
- **AdjudicationAdversarialSuite, ErFixtures, ExactIdPrecedenceAdversarialSuite, BlindViewLeakageAdversarialSuite, UnicodeNormalizationRegressionSuite**: no v4 pins and no affected expectations. Unchanged.
- The D1–D5 regression tests and every unrelated test are untouched.
- No production defect found.

Changes in MatchContractCrossModuleSuite (version pins):
- "every Resolver outcome kind…": `provenance.policyVersion` is now `er-policy.v5`. Added the produced kind "preprint-doi cross-stage UNRESOLVED" (expected UNRESOLVED on the exact-id path), with checks on the literal rule and reason codes.
- "config band ordering…": the active policy is v5. Refused versions are now v1, v2, v3, v4 and a future v6, plus prompt v1/v3.
- "config loader…": `er-policy.v4` added to the refused retired-config cases.
- "policy versioning…": renamed to "v5 … v1, v2, v3, v4 … refused". Pins `DefaultResource` = er-policy.v5.json and `FileSchemaVersion` = er-policy-file.v5. Refuses `RetiredV4Resource`, the names v1–v4 inside a v5 file, file schemas v1–v4, and prompt v1. Checks the v4 resource is a different document from the default.

New tests in ClusterCorroborationAdversarialSuite (mapped to decisions-07):
- **(a) "published record listing its own DOI and the preprint DOI…"** — 64-case sweep: 8 feature profiles, including title-year-authors, a strong score, the LLM band, corroborating features, and corroborating plus strong; 2 published-side forms; 2 preprint-side forms; both orientations. Each case asserts:
  - UNRESOLVED on the exact-id path, confidence 0
  - rule `exact-id.unresolved.preprint-doi-agreement-cross-stage` and reason `preprint-doi-agreement-cross-stage`, pinned literally
  - `publication-stage-<verdict>` with verdict uncertain
  - no `exact-id.agree.doi`
  - empty conflicts and not band-eligible
  - a fake adjudicator that always answers accept is called 0 times
  - the decision validates as match-decision.v2 and round-trips, with policy v5

  The test also covers:
  - two published versions that share only the preprint DOI
  - no score fallback even with thresholds configured to 0.3/0.05/0.1/0.2
  - no fallback through a corroborated cluster ID (the stage clause fails)
  - a forged in-band probabilistic copy is still refused by `Adjudication.eligible`
  - T1 conflicts unchanged: an arXiv disagreement next to the shared preprint DOI is a CONFLICT, with the DOI recorded as the contradicted agreement
- **(b) "two preprint records sharing the preprint DOI…"** — 48 cases: ACCEPTED on the exact-id path at 0.99, verdict same-stage-preprint, `exact-id.agree.doi`, none of the cross-stage codes.
- **(c1) "shared publication DOIs still decide, and the prefix list comes from the policy"**:
  - Pins `preprintDoiPrefixes` = ["10.48550/"].
  - Three publication-DOI controls are ACCEPTED in both orientations.
  - Metamorphic check: a policy copy with the synthetic prefix `10.9999/` blocks a pair that the default policy accepts. This shows the prefix is read from config, and that the trailing slash matters (`10.99999/` is not caught).
- **(c2) "case and resolver-URL forms of the preprint DOI cannot bypass the prefix check"**:
  - 11 raw forms (upper and mixed case, `doi.org`/`dx`/`www` URLs, `DOI:`, whitespace) normalize to the canonical preprint DOI through `ExactIdentifiers.normalize`, and through `ExactId.of` wherever it returns a value.
  - Pairs built from the normalized values are blocked.
  - Attack step: the raw non-canonical value is placed directly in both sides' identifier sets. It must be refused or must not be ACCEPTED, and failing that raises a "BYPASS" message instead of asserting the behaviour.
  - A canonical control proves the attack construction is not empty.
  - Look-alike prefixes (`10.485500/`, `10.4855/`) do not normalize into the prefix.

VALIDATION RESULTS: `sbt Test/compile` succeeded (observed). No bypass found by reading the code: every DOI on the production path reaches `Resolver` in canonical lower-case bare form, since `Identifiers.normalizeDoi` lower-cases and strips resolver prefixes. A shared non-canonical value should be refused by `MatchCandidate` validation of `exactIdAgreements` (`ExactId.checkSet`). I inferred that from the code; no test run has confirmed it.

ASSUMPTIONS:
- `MatchCandidate.build` / `Resolver.decide` refuse non-canonical agreement values (see RISKS).
- Synthetic `10.99999/synthetic.te.vor` is not a real registered DOI.
- An agreement backed by a non-DOI identifier (for example a shared dblp-key) next to a blocked preprint DOI is outside decisions-07, so I made no assertion about it.

RISKS:
- `Resolver` checks the preprint prefix with a plain `startsWith` on the raw value, and `EndpointIdentifiers.inconsistencies` does not require endpoint values to be canonical.
  - If the candidate contract ever stopped validating `exactIdAgreements`, a URL-form preprint DOI shared by both sides would skip the block and be ACCEPTED. The c2 attack step would then fail with "BYPASS".
  - Defence in depth (normalizing or validating inside `isPreprintDoi` / `PublicationStage.evidence`) is the call of entity-resolution-engineer. I report it as a hardening point, not an observed defect.
- All counts are predictions until the human runs `sbt check`.

MANUAL ACTION REQUIRED: The human runs one foreground `sbt check` in a Terminal and records the exact per-suite pass, fail and ignored counts. Any failure in the two changed suites goes back to test-engineer, except a "BYPASS:" failure, which goes to entity-resolution-engineer.

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none. The one refusal was guard-dangerous.sh `shell-syntax-gate` on a `wc -l` command that was not allowed, quoted in COMMANDS RUN; there were no audit-hook refusals.

NEXT GATE: The human runs `sbt check`, then focused code-reviewer re-review (RV-P5-06) of ER-P5-09 and TE-P5-03.

---

## TE-P5-04 (test-engineer) — hand-back 2026-11-12, verbatim

Orchestrator note (not part of the report): routed per decisions-09.md after ER-P5-11; the work order banned all Bash calls because DS-P5-05 ran scripts in parallel. Interrupted once by an API usage limit and resumed by SendMessage. No test counts exist for this work order.

TE-P5-04 is done in code, but nothing has been compiled or run. The work order banned all Bash calls, so I could only write the tests, not check them. All nine stale tests are updated to er-policy.v7 without weakening any behavioural assertion. The two entry-point tests now use a synthetic config and v7 rows that record their thresholds. A new suite covers the arXiv record-key block and the m1 score gate. Reading the code, I found nothing that looks like a production defect: each of the nine failures comes only from the approved v7 / config-wiring / row-schema change.

STATUS: COMPLETE (written only; not compiled or run)
OWNER: test-engineer
WORK ORDER: TE-P5-04 (Phase 5). Update the tests/** assertions made stale by ER-P5-11 (er-policy.v7, row schema er-review-queue-row.v4, config loading in GoldSetEvalMain). Add adversarial coverage for decisions-09 item 1 (a shared arXiv ID that does not decide also holds back dblp-key / scopus-eid) and for the RV-P5-04 m1 score gate.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/ArxivRecordKeyScoreGateAdversarialSuite.scala (4 tests)

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/MatchContractCrossModuleSuite.scala
  - "every Resolver outcome kind…": pins er-policy.v7.
  - "config band ordering…": `activePolicy` is v7. The positive control is unchanged. The refused list is now v1–v6 (retired) plus v8 (unknown), so v6 is no longer treated as unknown.
  - "config loader: a valid v2 file…": the retired-config loop gains v5 and v6.
  - "policy versioning…": v7 is the default resource and file schema er-policy-file.v7. v1–v6 are refused by resource, by name and by file schema, and every retired resource differs from the default. Prompt v1 is still refused. The unknown-key, band-swap and wording checks now run against the v7 file.
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/ClusterCorroborationAdversarialSuite.scala
  - `assertPreprintDoiBlocked` (two pins) and the "decisions-07 c" test now use `ActivePolicyVersion = "er-policy.v7"`. No behavioural assertion changed.
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/MembershipEvaluationAdversarialSuite.scala
  - `row` gains an optional `thresholds` argument (default None).
  - New helpers: `activeRow` (v7 rows with DecisionThresholds(0.85, 0.2, 0.4, 0.7)), `runWith(loader)`, and `runMain`, which now calls `GoldSetEvalMain.run(args, out, loader)` with the synthetic config.
  - New premise test: the synthetic config names v7 and the policy-file thresholds; the no-config source fails.
  - Test 8 keeps its intent: wrong freeze gives exit 2 with "differs from the recorded freeze", an upper-case sha gives exit 2, `--provisional` and `--provisional=true` give exit 2, and the matching freeze with 10 reviewed gives exit 3 with metrics withheld and the same coverage and definitive-count checks.
    - Additions:
      - The settings line is checked.
      - No config gives exit 2 with "configuration refused".
      - A config naming v5 or v6 gives exit 2.
      - Three kinds of queue rows give exit 2 with no coverage printed: stale v3 rows, v7 rows with no thresholds, and v7 rows decided under other thresholds.
  - Test 9 keeps its intent: 199 reviewed gives exit 3, and 200 reviewed with the freeze gives exit 0 with the same 200/159/41 and TP=159 FP=0 checks.
    - Additions: the settings line is checked, and the same run without config gives exit 2 with no report.
  - The other tests, including D1 (the whitespace-padded label), are unchanged.
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/ErFixtures.scala
  - Adds `syntheticErModelId`, `syntheticErConfigText(...)` (a synthetic scholarchain.config.v2 document), `syntheticErConfig(text)` (via `ConfigLoader.resolve`) and `missingErConfig` (`ConfigLoader.load(Map.empty, …)`).
- Not touched: the D1–D5 regression tests, BlindViewLeakageAdversarialSuite, UnicodeNormalizationRegressionSuite, ExactIdPrecedenceAdversarialSuite, AdjudicationAdversarialSuite, production code, hooks, settings, docs/agent-runs, the test marker.

COMMANDS RUN: none. The work order banned Bash; I used only Read, Edit and Write.

TEST RESULTS: none observed. No sbt run happened, so there are no pass, fail or ignored counts for any suite.
- Each new or updated test maps to:
  - decisions-09 item 1: new tests "decisions-09 item 1: a shared dblp-key or scopus-eid never overrides…" and "…controls…", plus a CHARACTERIZATION test (see RISKS).
  - RV-P5-04 m1: new test "m1: a stage-blocked or identifier-blocked high-score pair is never counted as acceptable at any threshold…".
  - RV-P5-04 m6 and the ER-P5-11 config wiring: entry-point tests 8 and 9 and the premise test.
  - Version settlement (decisions-09 item 2): MatchContract tests 1–4 and the ClusterCorroboration pins.
- What the new suite checks:
  - **Block:** A = {key, arXiv X} against B = {key, arXiv X, publication DOI}, for both keys, both orientations and 7 profiles (28 cases), plus both keys shared at once. Each case must be:
    - UNRESOLVED on exact-id at 0.0, stage uncertain, under v7;
    - carrying the literal rule `exact-id.unresolved.record-key-agreement-blocked-by-arxiv-stage.<scheme>` and the reasons `record-key-agreement-blocked-by-arxiv-stage`, `arxiv-agreement-cross-stage` and `publication-stage-uncertain`;
    - free of any key or arXiv acceptance rule, any preprint-DOI rule and any inferred-path rule;
    - free of conflicts, not band-eligible and not `Adjudication.eligible`;
    - never sent to the fake always-accept adjudicator (0 calls);
    - `scoreGate = (false, false, [])`, with a valid match-decision.v2 that round-trips.
    - It also holds under very low configured thresholds, and a forged in-band copy is still refused.
  - **Controls:**
    - A shared publication DOI is ACCEPTED at 0.99 (mixed/mixed, published/mixed, mixed/published).
    - Two pure preprints are ACCEPTED on the arXiv ID with stage same-stage-preprint.
    - A DOI or arXiv disagreement is CONFLICT, with the key recorded as a contradicted agreement and no block codes.
  - **m1:** a stage-blocked, an identifier-blocked, an unblocked control and an exact-id-blocked candidate are turned into real queue rows with `ReviewQueue.row`, which must round-trip through `GoldLabels.parseQueue`.
    - Their gates are pinned.
    - At each point of the default grid plus (0.3, 0.1), (0.5, 0.2) and (0.99, 0.95), the sensitivity counts must equal an independent `Resolver.decide` under the config with those thresholds.
    - At every point there must be FP = 0, TP coming only from the control, the expected blockedAccept count, and the exact-id row absent from the score table.
    - At (0.3, 0.1) it must report blockedAccept = 2, one row per capping rule.

VALIDATION RESULTS: none run.
- What I confirmed by reading the code:
  - `MatchPolicy` is at v7, with v1–v6 retired and `RetiredV5Resource` / `RetiredV6Resource` present.
  - `GoldSetEvalMain` parses arguments before loading config, then checks the freeze, then checks settings in `evaluate`. That order is why the `--provisional` and freeze tests needed a valid config.
  - `ReviewQueue.RowSchemaVersion` is v4, and the new `SystemDecision` fields have defaults, so the existing positional constructor calls still compile.
  - `Resolver` behaves exactly as ER-P5-11 describes. I found nothing that looks like a production defect.

ASSUMPTIONS:
- The tests project can see the main classes of entityResolution and core; I did not rely on ER test fixtures.
- strongWork plus the venue features still scores at or above 0.85. The tests assert this premise and fail with a clear message if it does not hold.
- Record-key agreement confidence is at most 0.99, so the shared-publication-DOI control expects 0.99.
- `EntityResolutionConfig.problems` accepts any strictly ordered band inside (reject, accept).

RISKS:
- Nothing was compiled. Type, import or formatting errors are possible: tuple patterns in for-comprehensions, splats, `.map(DecisionThresholds.of)`, and the 10-argument positional `EndpointRef`. The formatter hook did run after each write.
- An unrelated tests/** file outside tests/er that pins a policy version could still fail; I could not list the directory.
- The CHARACTERIZATION test pins an open gap that no decision covers yet: {key, arXiv} against {key, publication DOI}, with no shared preprint identifier, is still ACCEPTED through the record key (ER-P5-11 risk). It is reported, not endorsed.

MANUAL ACTION REQUIRED: the human runs one foreground `sbt check` and reports the per-suite counts. If anything fails to compile, route it back to test-engineer with the compiler output.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human's foreground `sbt check`, expecting 0 failures (including the D1–D5 regressions), with markTestRun refreshing the marker. Then a code-reviewer re-review of decisions-09 item 1, m1 and m6, plus a human decision on the characterized gap.

---

## TE-P5-05 (test-engineer) — hand-back 2026-11-12, verbatim

Orchestrator note (not part of the report): routed per decisions-11.md after ER-P5-12 (er-policy.v8); interrupted once by an API usage limit and resumed by SendMessage; the work order allowed at most two `sbt Test/compile` runs and no test task. No test counts exist for this work order.

The test updates and the new decisions-11 coverage are written, and `sbt Test/compile` passes (run 1 of 2: "compiling 9 Scala sources … done compiling", "[success] Total time: 7 s"). No tests were run, as the work order required, so whether anything passes is still unknown until the human runs `sbt check`. I found no production defect: every stale failure I traced comes from the approved v8 change.

ER-P5-12 missed one stale test, so there were 13, not 12:
- **UnicodeNormalizationRegressionSuite** (ER did not read it): the RV-P5-04 m7 CHARACTERIZATION expected a v1/v2 arXiv pair to be REJECTED by score. Under v8 it is ACCEPTED on exact-id (two pure preprints). This is the intended decisions-11 item 3 change, so I turned it into a regression.
- **MembershipEvaluationAdversarialSuite**, inside the predicted "freeze mismatch" test, also had a literal message pin "differs from the configured er-policy.v7"; it now uses the active version.

BlindViewLeakageAdversarialSuite has no version pin and no record-key or arXiv-revision pair; I expect it to pass unchanged.

```text
STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: TE-P5-05 (Phase 5): update the stale tests under tests/** after ER-P5-12 (er-policy.v8), turn the record-key CHARACTERIZATION into a regression, and add adversarial cross-module coverage for decisions-11 items 2 and 3.
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/RecordKeyStageArxivBaseIdAdversarialSuite.scala (10 tests)
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/MatchContractCrossModuleSuite.scala: activePolicy is er-policy.v8; the decision provenance pin uses it. Retired v1-v7 and an unknown v9 are refused by withConfig. The config loader refuses v3-v7, v9 and prompt v1. The versioning test covers DefaultResource v8, er-policy-file.v8 and RetiredV7Resource; names v1-v7 and file schemas v1-v7 and v9 are refused inside a v8 file; a v9 policy is refused against a v8 config and the other way round. The prompt v1 refusal is kept.
- .../ClusterCorroborationAdversarialSuite.scala: ActivePolicyVersion is er-policy.v8 (pin only).
- .../MembershipEvaluationAdversarialSuite.scala: activePolicy is er-policy.v8; v7 added to the retired-config loop; the message pin uses activePolicy.
- .../ArxivRecordKeyScoreGateAdversarialSuite.scala: pin v8. assertArxivKeyBlocked now also requires the v8 rule and reason; the v7 codes are still required. The CHARACTERIZATION is now a decisions-11 item 2 regression: {key, arXiv} vs {key, publication DOI}, each key and both keys, both orientations. It expects UNRESOLVED exact-id 0.0, the v8 rule and reason, publication-stage-cross-stage, no v7 trigger code, no conflicts, 0 LLM calls, and a valid match-decision.v2.
- .../UnicodeNormalizationRegressionSuite.scala: the m7 CHARACTERIZATION is now a regression. The candidate is unchanged (exactIdAgreements empty, id.arxiv.conflict 1.0). The outcome is ACCEPTED exact-id 0.99, same-stage-preprint, exact-id.agree.arxiv, arxiv-agreement-by-base-submission-id, policyAgreements = both versioned values, no conflicts.
COMMANDS RUN:
- git ls-files -- tests modules/entity-resolution: ok
- git ls-files --others --exclude-standard -- tests modules/entity-resolution: ok
- git status --short: ok
- git ls-files --others -- docs/agent-runs/phase-05/agent-report.md: ok
- git ls-files --others -- modules/core/src/main/scala/scholarchain/core: ok
- git status --short -- tests: refused by guard-dangerous.sh [no-allowlist-entry] (shape not allowlisted); nothing executed. This was not an audit refusal.
- sbt Test/compile (run 1 of 2, foreground): success, 7 s
- No sbt check and no test task were run.
TEST RESULTS: Not run, per the work order, so there are no pass, fail or ignored counts. Compilation succeeded. Expected after the change, from reading the code only: all 13 stale tests pass, and the 10 new tests pass.
VALIDATION RESULTS: The new suite maps to the work order as follows.
- (a) RV-P5-08 inputs (a)-(d), each key alone and both together, both orientations, 4 strong profiles (96 cases). It also covers low-threshold no-fallback, the forged band copy refused, and {key} vs {key} uncertain blocked.
- (a) Controls: same venue accepts; a venue never overrides a one-sided preprint marker; two pure preprints with no shared preprint identifier accept; a shared publication DOI accepts on cross-stage and uncertain pairs.
- (a) Conflicts: T1 doi and arXiv disagreements stay CONFLICT with the key as a contradicted agreement; the step-3 key agree/disagree CONFLICT is unchanged.
- Every blocked case expects UNRESOLVED exact-id 0.0, the v8 rule and reason plus publication-stage-<verdict>, no v6/v7 codes, scoreGate(false, false, []), 0 adjudicator calls, and a valid, round-tripping match-decision.v2.
- (b) Revisions agree, covering v1/v2, v1/bare, vNN, and old-style with versions: ACCEPTED for two pure preprints with the originals in policyAgreements. On a mixed side they are UNRESOLVED, arXiv cross-stage, never sent.
- (b) Distinct submissions (new/new, old/new, old/old): T1 CONFLICT with the original values; REJECTED when implausible.
- (b) Sweep over 66×66 side sets against an independent base-ID oracle: agreement and disagreement are never both true; the agreement values and the fold flag match the oracle. A Resolver sweep of 11×11 single values checks the same.
- (b) A DOI-contradicted folded agreement keeps both versions in the contradicted-agreement values.
- (b) The RV-P5-08 MINOR-1 input is now UNRESOLVED with the stage, arXiv-key and fold codes.
ASSUMPTIONS:
- "10.1145/y" in RV-P5-08 (d) is a publication DOI; I used the invented value 10.1145/synthetic.te.y.
- MINOR-1 is as ER-P5-12 states: A={key, v1}, B={key, v2, publication DOI}.
- The record-key agreementConfidence is 0.99 (from the ER-P5-07 table); it is asserted only where a DOI decides.
RISKS:
- Nothing was executed, so a wrong expectation of mine could show up as a failure in the human's sbt check. If so, route the failure details to me before treating it as a production defect.
- The 66×66 sweep adds a little runtime.
- The probabilistic fallback is not shown for MINOR-1 (it is blocked at 6c by design).
MANUAL ACTION REQUIRED: The human runs one foreground `sbt check` and routes any tests/** failures to test-engineer with the output.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human-run `sbt check` with exact per-suite counts, then the focused code-reviewer review of decisions-11 items 2 and 3, including this suite and the two regressions that replaced characterizations.
```

---

## TE-P5-06 (test-engineer), decisions-15: migrate tests/** to the freeze file and split check — verbatim

```text
STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: TE-P5-06 (Phase 5). Move tests/** from `--manifest-sha256=<hex>` to `--manifest-sha256-file` plus `--expect-split`, add adversarial freeze and split cases, make tests/** independent of the legacy hex flag, and run `sbt check`.
```

Summary: all migrated and new tests pass and `sbt check` is green. One test is deliberately ignored: it describes the behaviour ER's follow-up should produce. No production defect was found.

FILES CREATED: none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/MembershipEvaluationAdversarialSuite.scala
  - Helpers:
    - The manifest is now named `er-gold-membership.jsonl` (the gold layout name).
    - `EvalFiles` gained `dir`, `freeze` (`<manifest>.sha256` beside the manifest) and `args`.
    - `sha` is now computed with the JDK `MessageDigest` over the file's raw bytes, so the expected digest no longer comes from the production hashing code.
    - New helpers: `shasumLine(hex, name)` writes `"<hex>  <name>\n"`, the exact `shasum -a 256` output line. `writeRecord`, `freezeArgs` and `frozen(f, split)` write the record and build the arguments. `assertRefused` checks exit 2, a named reason, no `report-json` and no `precision=`. `minimumSet` builds 200 SYNTHETIC ACCEPTED/match members of a given split.
    - `plan(n, design)` now builds its population in `design.split`. All earlier callers use held-out, so they behave exactly as before.
  - Migrated (intent kept):
    - "entry point: freeze mismatch and --provisional are refused; below 200 reviewed withholds metrics":
      - A well-formed record beside the manifest with the wrong digest: exit 2, "differs from the recorded freeze", no coverage.
      - The right digest in upper case: exit 2, no "freeze=matches".
      - The correct record with `--expect-split=heldout`: exit 3, "freeze=matches".
      - The missing-config, retired-policy and mismatched-rows refusals now use the file freeze.
    - "entry point: 199 reviewed withholds the metrics; 200 reviewed ...":
      - 200 reviewed with the file freeze and `--expect-split=heldout`: exit 0, and the line `manifest sha256=<sha> freeze=matches` is checked.
      - The missing-config run with the file freeze: exit 2.
      - New check: 200 reviewed with no freeze argument at all gives exit 2, "final held-out" refusal, no report and no metrics.
  - New tests:
    - "TE-P5-06: --expect-split refuses the other split for held-out and dev manifests".
      - Positive controls: held-out/heldout and dev/dev, both exit 0.
      - Held-out with `--expect-split=dev`, and dev with `--expect-split=heldout`, are refused even though the freeze matches.
      - A dev manifest that meets the minimum, with no freeze and `--expect-split=heldout`, is refused without printing coverage.
    - "TE-P5-06: a freeze record written for a different manifest is refused".
      - B's valid record in B's directory: refused (not in the membership file's directory).
      - B's record copied beside A: refused (differs).
      - A record beside A naming a sibling manifest, with the sibling's digest and with A's own digest: refused (names 'er-gold-membership-dev.jsonl').
      - Positive control on A: exit 0.
    - "TE-P5-06: a correct freeze record outside the manifest's directory is refused".
      - A record with the right digest and name is refused when it is in an unrelated directory, in a subdirectory, or reached through a symlinked directory inside the manifest's directory.
      - Positive control: the same bytes beside the manifest, exit 0.
    - "TE-P5-06: a manifest changed after its freeze is refused, even when it parses the same".
      - Positive control first, exit 0.
      - Refused with "differs" for: CRLF line ends, a trailing blank line, one member removed, members reordered.
      - Restoring the frozen bytes gives exit 0 again.
    - "TE-P5-06 TARGET: held-out final metrics with only the legacy hex flag do not exit 0" is marked `.ignore`.
      - It runs `--manifest-sha256=<hex>` alone and together with `--expect-split=heldout`, and asserts the exit code is not 0, with no report and no metrics.
      - I ignored it rather than using munit `.fail`. Today the hex flag still counts as a freeze and exits 0, so the test would fail now. A `.fail` marker would then break `sbt check` as soon as ER removes the flag.
      - Enablement: delete `.ignore` in the same change that removes the hex flag.
  - Task 3: the only remaining use of `--manifest-sha256=` in this file is the ignored TARGET test. That test passes once the flag is removed, because the flag then becomes an "unknown argument" (exit 2). ER can remove the flag without breaking this suite.

COMMANDS RUN:
- `git ls-files -- modules/entity-resolution tests docs/agent-runs/phase-05`: ok
- `git ls-files --others --exclude-standard -- modules/entity-resolution tests`: ok
- `git diff --no-ext-diff --stat`: ok
- `git grep -n manifest-sha256 -- tests`: refused by guard-dangerous.sh (no-allowlist-entry); nothing ran
- `sbt tests/test`: refused by guard-dangerous.sh (no-allowlist-entry; the `<SBTTEST>` slot does not accept the `tests` project); nothing ran
- `sbt check`: success, "Total time: 131 s (0:02:11.0), completed Nov 13, 2026, 5:52:41 PM"

TEST RESULTS (from the `sbt check` output and the JUnit XML report):

| Suite | Passed | Failed | Errors | Ignored |
|---|---|---|---|---|
| core | 299 | 0 | 0 | 0 |
| entityResolution | 382 | 0 | 0 | 0 |
| hadoop | 80 | 0 | 0 | 0 |
| rag, root, services, sparkAnalytics, storage | 0 each ("No tests to run") | 0 | 0 | 0 |
| tests | 143 | 0 | 0 | 1 |

- markTestRun line: "wrote .claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=75, passed=904, skipped=1)".
- MembershipEvaluationAdversarialSuite (/Users/taufeeqpatel/Desktop/ScholarChain/tests/target/test-reports/TEST-scholarchain.tests.er.MembershipEvaluationAdversarialSuite.xml, timestamp 2026-11-13T17:51:47): tests=19, failures=0, errors=0, skipped=1 (the TARGET test). All four new TE-P5-06 tests and both migrated entry-point tests passed.
- Failing tests: none.

VALIDATION RESULTS:
- scalafmt check of the tests sources passed inside `sbt check`.
- Mapping of tests to requirements:
  - decisions-15 item 2 (human-recorded SHA-256, one hash-file format, verified by the evaluation): the migrated tests and the different-manifest, other-directory and tampered tests.
  - decisions-15 item 1 (expected split enforced at runtime): the split test.
  - RV-P5-04 m4 (no final held-out metrics without the freeze): the no-freeze-argument check, plus the TARGET test once it is enabled.
  - ER-P5-15 work order items 1-3: covered as described under FILES MODIFIED.

ASSUMPTIONS:
- `GoldSetEvalMain` and `--manifest-sha256` are used in tests/** only in MembershipEvaluationAdversarialSuite.scala, as ER-P5-15's BLOCKED-ON evidence states. I could not search the rest of the tree: I have no Grep tool here and `git grep` is not on my allowlist. ErFixtures.scala was read and has no such use.
- The synthetic config, labels and queue rows are test-only. No gold labels, held-out data, network or AWS were used. All files are written under a JVM temp directory.

RISKS:
- Observation for entity-resolution-engineer, not a test: `FreezeRecord.read` does not check the freeze file's own name. A correct line stored as `anything.txt` in the manifest's directory is accepted, although the docs describe the file as `<membership file>.sha256`. decisions-15 fixes the line format, not the file name, so I did not add a failing test. ER or the human may want to decide whether to enforce the name.
- The TARGET test is ignored. If it is not enabled in ER's follow-up, the "legacy hex alone does not exit 0" behaviour goes unguarded. It shows up as "Ignored 1" in the tests summary.
- The symlinked-directory case creates a symlink inside the temp directory, so it needs a filesystem that supports symlinks (it does on macOS).

MANUAL ACTION REQUIRED: none.

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE:
- entity-resolution-engineer can now remove `--manifest-sha256=<hex>` from GoldSetEvalMain, its doc comment and its own tests, and in the same change delete `.ignore` from "TE-P5-06 TARGET: held-out final metrics with only the legacy hex flag do not exit 0" in MembershipEvaluationAdversarialSuite.scala. Editing that file is test-engineer's to do, so the orchestrator should route that one-line change to me in the same phase.
- After that, `sbt check` should show the tests suite with Ignored 0.
- Then code-reviewer reviews the tests/** diff.

---

## TE-P5-07 (test-engineer): TARGET test enabled, freeze file-name case — verbatim

```text
STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: TE-P5-07 (follow-up to TE-P5-06). Turn the ignored TARGET test back on now that ER-P5-16 removed `--manifest-sha256=<hex>`, confirm the freeze file naming, optionally add a test for the new file-name rule, and run `sbt check`.
```

The TARGET test is enabled and passes, and `sbt check` is green with 908 passed, 0 failed, 0 ignored.

FILES CREATED: none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/er/MembershipEvaluationAdversarialSuite.scala
  - Item 1: removed `.ignore` from "TE-P5-06 TARGET: held-out final metrics with only the legacy hex flag do not exit 0". It runs a held-out set that meets the 200-reviewed minimum with only `--manifest-sha256=<hex>`, and again with `--expect-split=heldout` added. In both runs it asserts the exit code is not 0 and that no `report-json` and no `precision=` are printed. I rewrote its doc comment and the section comment, which had described it as ignored.
  - Item 2 (confirmed): every freeze record this suite writes is named exactly `er-gold-membership.jsonl.sha256` beside the manifest. The other-directory, subdirectory, symlinked-directory and B's-record cases also use that exact name, so they are still refused for the directory reason, which ER checks before the name. No case relies on a different file name being accepted.
  - Item 2 (new test): "TE-P5-07: a correct record beside the manifest under another file name is refused". This runs through the evaluation entry point. A correct line (right digest, right content name) is stored beside the manifest as each of `er-gold-membership.jsonl.SHA256`, `er-gold-membership.jsonl.sha256.bak`, `er-gold-membership.sha256` and `freeze.txt`. Each run must exit 2 with "must be named exactly 'er-gold-membership.jsonl.sha256'", and print no report and no metrics. A positive control under the exact name exits 0.

COMMANDS RUN:
- `sbt check`: success, "Total time: 129 s (0:02:09.0), completed Nov 13, 2026, 6:03:30 PM"

TEST RESULTS (from the `sbt check` output):

| Suite | Passed | Failed | Errors | Ignored |
|---|---|---|---|---|
| core | 299 | 0 | 0 | 0 |
| entityResolution | 384 | 0 | 0 | 0 |
| hadoop | 80 | 0 | 0 | 0 |
| tests | 145 | 0 | 0 | 0 |
| rag, root, services, sparkAnalytics, storage | 0 each ("No tests to run") | 0 | 0 | 0 |

- markTestRun: "wrote .claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=75, passed=908, skipped=0)". The target of Ignored 0 is met.
- MembershipEvaluationAdversarialSuite (/Users/taufeeqpatel/Desktop/ScholarChain/tests/target/test-reports/TEST-scholarchain.tests.er.MembershipEvaluationAdversarialSuite.xml, timestamp 2026-11-13T18:02:37): tests=20, failures=0, errors=0, skipped=0.
- Failing tests: none.

VALIDATION RESULTS: the scalafmt check of the tests sources passed inside `sbt check`. Requirement mapping:
- The TARGET test covers decisions-15 item 2 and RV-P5-04 m4 (no final held-out metrics without the recorded freeze file).
- The TE-P5-07 test covers ER-P5-16's exact-name rule through the entry point.

ASSUMPTIONS:
- On macOS's default case-insensitive filesystem, `.SHA256` and `.sha256` can be the same file. The test deletes each wrong-name file before the next case, and the rule compares the path string it is given, so the case-sensitive check is still exercised.
- All data is SYNTHETIC and lives in JVM temp directories. No held-out data, labels, network or AWS were used.

RISKS: none new. The file-name risk I raised in TE-P5-06 is closed by ER-P5-16 and now covered by the new test.

MANUAL ACTION REQUIRED: none

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: code-reviewer reviews the tests/** diff together with ER-P5-16's change.

---

## Clean-tree verification (human-run, 2026-11-15)

Orchestrator note: the human ran this in a regular Terminal. The orchestrator did not run it and observed it only through the human's screenshots; every value below is transcribed from them. This is the clean-tested-tree record required by `docs/agent-runs/README.md` (`test-report.md` row). No held-out data was used: the clone contains no gitignored files, so the held-out labels file is absent from it.

Working copy: a fresh local clone, `git clone --no-hardlinks /Users/taufeeqpatel/Desktop/ScholarChain ~/Desktop/ScholarChain-check-f952eff`. The human's repository was not modified.

Captured immediately before the run, in the clone:

```text
$ git rev-parse HEAD
f952eff83888b6fb786062de49ae204e056be3e8
$ git status --porcelain
(no output)
```

Command, exit status and timing:

```text
$ time sbt -no-colors check > ~/Desktop/sbt-check-f952eff.log 2>&1; echo "exit=$?"
sbt -no-colors check > ~/Desktop/sbt-check-f952eff.log 2>&1  242.79s user 33.11s system 135% cpu 3:24.34 total
exit=0
```

Final sbt line: `[success] Total time: 150 s (0:02:30.0), completed Nov 15, 2026, 4:31:31 PM`.

Per-project results. The sbt summary lines do not name their project; they are mapped in sbt's aggregate order (core, entityResolution, hadoop, rag, root, services, sparkAnalytics, storage, tests), which matches earlier reports in this file and the skipped suite's location below.

| Project | Total | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| core | 299 | 299 | 0 | 0 | 0 |
| entityResolution | 393 | 392 | 0 | 0 | 1 |
| hadoop | 85 | 85 | 0 | 0 | 0 |
| rag, root, services, sparkAnalytics, storage | 0 each ("No tests to run") | 0 | 0 | 0 | 0 |
| tests | 145 | 145 | 0 | 0 | 0 |

markTestRun line, written inside the clone, not in the human's repository:

```text
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain-check-f952eff/.claude/.last-test-run (git_head=f952eff83888b6fb786062de49ae204e056be3e8, worktree=clean, suites=76, passed=921, skipped=1)
```

Check: 299 + 392 + 85 + 145 = 921 passed. Failing tests: none.

Skipped test, identified from the JUnit XML in the clone (`modules/entity-resolution/target/test-reports/TEST-scholarchain.entityresolution.review.SampleQueueProbeSuite.xml`):

```text
<testcase classname="scholarchain.entityresolution.review.SampleQueueProbeSuite" name="sample review-queue strata (probe; skipped without Hadoop local-pipeline outputs)" time="0.003">
  <skipped/>
</testcase>
```

Reason, confirmed by the orchestrator from the source (Read): `SampleQueueProbeSuite.scala:24-29` calls munit `assume` and skips unless exactly one completed run exists under `modules/hadoop/target/test-work/local-pipeline/curated-a`, which Hadoop's `LocalPipelineSuite` writes. Its doc comment (lines 15-16) states that it is skipped "on a clean tree, or while the Hadoop suite is still running". It is a dev-diagnostic probe over the committed samples (never held-out membership) and asserts invariants only. Earlier runs in this file reported 0 skipped because they ran on working trees where those Hadoop outputs already existed. Residual: on a clean tree this probe's invariants are not exercised, and whether it runs depends on whether the Hadoop suite finishes first.
