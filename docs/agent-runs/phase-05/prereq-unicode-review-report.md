# Phase 5 prerequisite (Unicode format characters): review report

> **PREREQUISITE WORK ONLY. THIS IS NOT COMPLETION OF PHASE 5.**

Review RV-P5-00 by `code-reviewer` (read-only: Read, Grep, Glob; no Bash). Work order verbatim in
`prereq-unicode-work-orders.md`.

## Summary

| Item | Value |
|---|---|
| Verdict | **APPROVE** |
| Blockers (critical/major) | none |
| Non-blocking findings | NIT-1, NIT-2 (owner `entity-resolution-engineer`), kept open and documented by human instruction |
| Commands run by reviewer | none (no Bash tool) |
| Test results observed by reviewer | none (agent-reported 443 passed, 0 failed; unverified by reviewer) |

### Non-blocking findings (verified by the reviewer)

| ID | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| NIT-1 | non-blocking | `modules/entity-resolution/src/main/scala/scholarchain/entityresolution/TextFolding.scala:50-51, :101-113`; `.../Names.scala:215` | `"\uD800​\uDC00"`: `fold` gives `""`; `TitleText.words` (removeFormat first) yields `"𐀀"` (U+10000) | Scaladoc claim "deleting before or after fold is the same" is false for lone surrogates around a Cf; output still deterministic. Reachable only via JSON `\u` escapes of lone surrogates (OpenAlex, Crossref, ORCID), not valid UTF-8. Fuzz test (`FormatCharacterPipelineSuite:412-418`) has no lone low surrogate | `entity-resolution-engineer` |
| NIT-2 | non-blocking | `modules/entity-resolution/src/main/scala/scholarchain/entityresolution/Names.scala:85-86, 98-103` | `"Christopher Frost" + U+200B + "0001"` (Cf is the only separator before the DBLP homonym number) | v2 parses family `frost0001`, no homonym suffix (v1: `frost` + suffix 0001); block `frost0001|c` instead of `frost|c` (recall loss) and `homonym_suffix_*` signal lost. Covered only by the general "Cf alone joins words" acceptance; no test or explicit note. Unlikely in DBLP output | `entity-resolution-engineer` |

## Reviewer report (verbatim)

The two-space harness indentation has been removed; otherwise verbatim.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-00, a focused read-only review of the uncommitted diff against HEAD d16e8c9 for the Phase 5 prerequisite (Phase 4 human gate, accepted deferral 1: Unicode Cf format characters were folded to spaces).
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I have no Bash tool; I used only Read, Grep and Glob.
TEST RESULTS: not observed. I could not run sbt. The figures the agent reported (core 252, entityResolution 75, hadoop 67, tests 49, 443 passed) are unverified.
VALIDATION RESULTS: I checked each item against the working-tree files.
1. Correctness.
   - TextFolding.fold (TextFolding.scala:116-138) checks `isFormat(cp)` before every other classification. That includes before the mark check, the Special table, letter/digit, apostrophe and hyphen handling. Format characters are therefore deleted, and the soft hyphen U+00AD is deleted even when keepHyphen is set.
   - The loop iterates by code point (codePointAt / charCount), so astral Cf characters such as tag characters are handled.
   - Zs, Zl, Zp, Cc, Cs, Co and Cn still fall through to the space branch.
   - Every name, title, venue and affiliation path goes through fold. This covers PersonName.parse, structured, nameWords, particles, homonym number and generational suffix (all on folded words), initials, firstGivenKey, coauthorAnchor, profileName, workKeys, TitleText.words (which also calls removeFormat before stripTrailingNote, Names.scala:210-218), textTokens, and every MatchFeatures.prepare input.
   - No production path in modules/**/src/main splits names or titles outside fold. Hadoop's TextNormalization (Shuffle.scala:36-46) only writes normalizedName / normalizedTitle, which no key reads, and it is unchanged.
2. Raw input is preserved. researcherKeys, coauthorAnchor and workKeys read rawName and title (BlockingKeys.scala:96, 107, 136, 145), and no function changes its input.
3. Over-merging.
   - The only new joins happen when a Cf character is the only thing between two words (for example Jun+ZWSP+Wang becomes junwang). This is documented in TextFolding.scala:36-54 and pinned by FormatCharactersSuite:60-70 and FormatCharacterPipelineSuite:341-344.
   - The joined form gets its own key ("junwang|-") and does not fall into "wang|j".
   - Confusables stay separate (FormatCharacterPipelineSuite:294-313). Particle joining and the vowel-digraph collapse produce the same keys as the clean spelling, not new merges.
4. Versioning.
   - BlockingKeys.Version is now "er-blocking.v2" and MatchFeatures.Version is "er-features.v2". Both bumps are justified: block keys and feature values both change for Cf input, and both versions feed into CandidateBlockId and MatchCandidateId.
   - A repo-wide grep (excluding docs/agent-runs/**) finds "er-blocking.v1" / "er-features.v1" only in history comments.
   - No .conf, .json, .yaml, .tsv or .properties file pins a version.
   - Hadoop production code reads EntityResolution.blockingKeysVersion and featuresVersion (BlockingJob.scala:85, Pipeline.scala:201, 224).
   - core test fixtures use unrelated tokens ("er-keys-1.0.0"), which is fine because the core contract only validates the token format.
   - HadoopPipeline.TransformVersion correctly stays "hadoop-transform.v2", since no Hadoop mapping changed.
   - job-contracts.md lines 92, 95 and 303 now say v2.
5. Tests.
   - I traced the tests marked [regression] against the v1 fold by hand. They do fail under v1. For example, "Jian Wa"+Cf+"ng" gives family "ng", "Mei"+Cf+"ling" gives level-1 key "zhao|m|mei", "Gra"+Cf+"ph" gives "gra_ph", SHY gives family "molina", and "00"+Cf+"01" loses the homonym suffix.
   - The same holds for FormatCharactersSuite's in-word, given-name, hyphen-adjacent, initials, profile, title and coauthor-anchor cases, and for MatchFeaturesSuite:393-409.
   - The fuzz comparison fold(x) == fold(removeFormat(x)) is not tautological for fold.
   - The Cf, Cn and Co sets are computed at runtime from the JDK, so the tests are not tied to one Unicode version.
   - The Hadoop test edits do not weaken anything. Literal pins became EntityResolution.* plus one literal "er-blocking.v2" pin (ShuffleAndParamsSuite:166-168). The mismatch guards still use v0 tokens (LocalPipelineSuite:649, 654).
6. Ownership. `git status` at session start lists only paths under modules/entity-resolution/** (entity-resolution-engineer), modules/hadoop/** test files and docs (hadoop-engineer) and tests/** (test-engineer). There are no constitutional, build, marker or production Hadoop changes.
ASSUMPTIONS: The working tree matches the gitStatus snapshot taken at session start. Each path was written by its matrix owner (I cannot check who wrote what).
RISKS: Cf characters that are the only separator between words now join the words (accepted and documented). See the nits below for two edge consequences.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human reviews this report and the observed sbt check output, then commits.
VERDICT: APPROVE
FINDINGS:
Blockers (critical or major): none.

Non-blocking:
- NIT-1 (entity-resolution-engineer). Files: /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/TextFolding.scala:50-51 and :101-113, and /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/Names.scala:215.
  - Problem: removeFormat can join a lone high surrogate and a lone low surrogate around a deleted Cf character into a valid code point. That breaks the documented claim that deleting before or after fold is the same.
  - Failing input: "\uD800​\uDC00". fold gives "" (both lone surrogates become spaces, ZWSP is deleted). TitleText.words calls removeFormat first, producing "𐀀" (U+10000, a letter), so the result is "𐀀".
  - How it can arise: only through JSON \u escapes of lone surrogates in OpenAlex, Crossref or ORCID input, not from valid UTF-8.
  - Impact: output stays deterministic, but the Scaladoc claim is wrong for this input. The fuzz test (FormatCharacterPipelineSuite:412-418) has no lone low surrogate, so it cannot catch this.
  - Fix: removeFormat could replace lone surrogates with a space, or the claim could be qualified.
- NIT-2 (entity-resolution-engineer). File: /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/Names.scala:85-86, 98-103.
  - Failing input: "Christopher Frost" + U+200B + "0001", where the Cf is the only separator before the homonym number.
  - Effect: in v2 this parses as family "frost0001" with no homonym suffix. v1 gave "frost" with suffix 0001.
  - Impact: the mention lands in block "frost0001|c" instead of "frost|c" (recall loss), and the homonym_suffix_* signal is lost.
  - This is covered only by the general "a Cf alone joins words" acceptance, with no test or explicit note. It is unlikely in DBLP output. Recommendation: document it or add a pinned test.
UNVERIFIED CONCERNS:
- I could not observe sbt check or the test counts.
- I could not see the pre-change contents of TextFoldingSuite.scala and BlockingKeysSuite.scala, because there is no git diff access and no diff file under docs/agent-runs/phase-05. I cannot confirm that no earlier assertion was removed or weakened. The current files contain only the v2 version pins, and no Cf-as-space assertion.
- I cannot confirm which agent identity wrote each file. I can only confirm that every path lies inside its matrix owner's territory.
- I cannot confirm that the JDK in use classifies U+180E as Cf (true for Unicode 6.3 and later, so JDK 9+). The fuzz test tolerates either classification.

## Orchestrator follow-up on the reviewer's unverified concerns

These are deterministic command outputs observed by the orchestrator after the review. They are not a re-review.

1. **Pre-change contents of TextFoldingSuite and BlockingKeysSuite.** The reviewer had no diff access, so the
   orchestrator ran `git diff` on both files. Output as transcribed below. Invisible characters (the
   removed Cf character, the Zl/Zp/Zs separators, the lone combining mark) may not have survived transcription
   byte for byte. For an exact copy, run `git diff -- <the two paths>` against HEAD `d16e8c9`.

```diff
diff --git a/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/BlockingKeysSuite.scala b/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/BlockingKeysSuite.scala
index fd6681d..9526626 100644
--- a/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/BlockingKeysSuite.scala
+++ b/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/BlockingKeysSuite.scala
@@ -21,8 +21,8 @@ class BlockingKeysSuite extends munit.FunSuite {
   private def rkeys(w: WorkRecord, i: Int) = researcherKeys(w, w.authors(i))
 
   test("pinned versions and level count") {
-    assertEquals(BlockingKeys.Version, "er-blocking.v1")
-    assertEquals(EntityResolution.blockingKeysVersion, "er-blocking.v1")
+    assertEquals(BlockingKeys.Version, "er-blocking.v2")
+    assertEquals(EntityResolution.blockingKeysVersion, "er-blocking.v2")
     assertEquals(BlockingKeys.Levels, 4)
     assertEquals(EntityResolution.blockingLevels, 4)
     assert(StableId.isToken(BlockingKeys.Version))
diff --git a/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/TextFoldingSuite.scala b/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/TextFoldingSuite.scala
index 0e3c2d6..33b03bc 100644
--- a/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/TextFoldingSuite.scala
+++ b/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/TextFoldingSuite.scala
@@ -29,7 +29,8 @@ class TextFoldingSuite extends munit.FunSuite {
   }
 
   test("control characters, tabs and newlines become separators; null and blanks fold to empty") {
-    assertEquals(fold("a\tb\u0000c\nd‍e", keepHyphen = false), "a b c d e")
+    assertEquals(fold("a\tb\u0000c\nd\u0085e", keepHyphen = false), "a b c d e")
+    assertEquals(fold("a b c d　e", keepHyphen = false), "a b c d e", "Zl Zp Zs")
     assertEquals(fold(null, keepHyphen = false), "")
     assertEquals(fold("  \t ", keepHyphen = false), "")
     assertEquals(fold("́", keepHyphen = false), "", "a lone combining mark")
```

   Observation: only the version pins changed in BlockingKeysSuite. In TextFoldingSuite one input changed: the
   old invisible character between `d` and `e` (a Cf character per ER-P5-00, which the old test expected to
   become a separator) was replaced by U+0085 (Cc), and a separator check (Zl, Zp, Zs; the characters are
   invisible in this rendering) was added. The old Cf-as-separator expectation contradicts the fix and is now
   covered in the opposite direction by `FormatCharactersSuite`. No other assertion was removed.

2. **Changed-file set.** `git status --porcelain` (in `prereq-unicode-test-report.md`) matches the reviewer's
   ownership check: 13 paths, all under `modules/entity-resolution/**`, `modules/hadoop/**` (tests and docs), and
   `tests/**`. No constitutional, build, `.claude/**`, or production Hadoop path changed.

3. **Not resolved by the orchestrator:** the agent identity that wrote each file (only territory is checkable;
   `enforce-ownership.sh` refused nothing reported in this session), the JDK classification of U+180E, and the
   sbt counts (agent-reported only).
