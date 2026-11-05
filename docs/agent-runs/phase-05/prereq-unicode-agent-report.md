# Phase 5 prerequisite (Unicode format characters): agent reports

> **PREREQUISITE WORK ONLY. THIS IS NOT COMPLETION OF PHASE 5.**

Final reports of the owning agents, verbatim, in the order received. The only change is that the
two-space indentation the Claude Code harness adds to every line of a subagent hand-back has been
removed. The code-reviewer report is in `prereq-unicode-review-report.md`.

| # | Work order | Agent | STATUS |
|---|---|---|---|
| 1 | ER-P5-00 | `entity-resolution-engineer` | BLOCKED-ON hadoop-engineer |
| 2 | HD-P5-00a | `hadoop-engineer` | COMPLETE |
| 3 | TE-P5-00 | `test-engineer` | COMPLETE |

---

## 1. entity-resolution-engineer (ER-P5-00)

ER-P5-00 is done inside modules/entity-resolution, but `sbt check` fails and the test marker was not refreshed. The only failures are two hadoop tests that hard-code the old version string "er-blocking.v1"; I can't edit them. Every test in my module passes, and so does every adversarial test under tests/** (including UnicodeSuite).

STATUS: BLOCKED-ON
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-00 (Phase 5 prerequisite): Unicode format characters (category Cf) were turned into spaces in TextFolding.fold, which split names into different blocks. Source: docs/agent-runs/phase-04/human-gate.md, first item under "Accepted deferrals".

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/FormatCharactersSuite.scala (18 feature tests)

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/TextFolding.scala
  - `fold` now deletes format characters. This is the first check for each code point, before marks, the transliteration table, letters, apostrophes or hyphens.
  - New public helpers: `isFormat(cp)`, true exactly when `Character.getType(cp) == Character.FORMAT` (all of Cf), and `removeFormat(s)`.
  - The Scaladoc step list now has 8 steps (new step 2) plus a "Format characters" policy section.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/Names.scala
  - `TitleText.words` deletes Cf before it strips the trailing editorial note, so an invisible character after `)` or `.` can no longer hide a note such as "(Awarded Best Paper!)". Scaladoc updated.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/BlockingKeys.scala
  - `Version` changed from "er-blocking.v1" to "er-blocking.v2", with a history note.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/MatchFeatures.scala
  - `Version` changed from "er-features.v1" to "er-features.v2", with a history note.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/BlockingKeysSuite.scala: version pin set to v2.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/MatchFeaturesSuite.scala
  - Version pin set to v2.
  - New test "format characters inside names and titles do not change any feature (er-features.v2)".
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/TextFoldingSuite.scala
  - The control-character test used an invisible format character (likely ZWJ) after "d" and expected "d e". The new rule would make that "de".
  - I replaced it with U+0085 (NEL, a control character) and added a check that U+2028, U+2029, U+00A0 and U+3000 (line, paragraph and space separators) still separate words.

COMMANDS RUN:
- `git ls-files -- modules/entity-resolution`: ok
- `git ls-files -- modules/hadoop modules/core tests docs datasets`: ok
- `git grep -n er-features modules/entity-resolution`: DENIED by guard-dangerous.sh (no allowlist entry). I had no Grep tool, so I found references by reading the likely files (see RISKS).
- `sbt entityResolution/test`: Passed: Total 75, Failed 0, Errors 0, Passed 75
- `sbt check` (background): exit 1
- `sbt check` (foreground, started by mistake while the background run was still going): exit 1, output truncated.
- `sbt check` (background, rerun alone so the earlier overlap couldn't affect it): exit 1. This is the authoritative result below.
- `git status --porcelain`

TEST RESULTS (authoritative standalone `sbt check`, quoted verbatim):
```
[info] Passed: Total 252, Failed 0, Errors 0, Passed 252
[info] Passed: Total 75, Failed 0, Errors 0, Passed 75
[error] Failed: Total 67, Failed 2, Errors 0, Passed 65
[error] Failed tests:
[error] 	scholarchain.hadoop.pipeline.LocalPipelineSuite
[error] 	scholarchain.hadoop.ShuffleAndParamsSuite
[error] (markTestRun) sbt.TestsFailedException: Tests unsuccessful
[error] Total time: 90 s (0:01:30.0), completed Nov 4, 2026, 9:48:24 AM
```
- Totals: 394 tests, 392 passed, 2 failed, 0 errors.
- The 75 line is entityResolution (it matches the module run exactly). The 67 line is hadoop, since it lists both failing hadoop suites. The 252 line covers the remaining suites, which in this output include core and the tests/** adversarial suites (UnicodeSuite, MissingFieldsSuite and the rest). sbt printed no project names, so I can't split 252 between core and tests.
- Scalafmt, build guard, version ledger and compile all passed (the earlier foreground run shows those [success] lines).

The two failures, verbatim:
1. `scholarchain.hadoop.pipeline.LocalPipelineSuite.full job graph over the samples: counters, outputs, contracts, determinism, idempotency`
   - `munit.FailException: modules/hadoop/src/test/scala/scholarchain/hadoop/pipeline/LocalPipelineSuite.scala:288 assertion failed`
   - `288:    assert(allBlocks.forall(_.blockingVersion == "er-blocking.v1"))`
2. `scholarchain.hadoop.ShuffleAndParamsSuite.the production key source delegates to entity-resolution (keys, version, levels)`
   - `munit.ComparisonFailException: ShuffleAndParamsSuite.scala:166`
   - `assertEquals(src.version, "er-blocking.v1")`, obtained `er-blocking.v2`

Both assert the old literal string. The pipeline itself works with v2: the candidates in the same run carry `"blockingVersion":"er-blocking.v2","featureVersion":"er-features.v2"`, and the other 6 LocalPipelineSuite tests and 9 ShuffleAndParamsSuite tests pass. No tests/** test failed.

VALIDATION RESULTS:
- Which characters are deleted: all of Unicode category Cf as the running JDK reports it (Temurin 17.0.17). That covers U+00AD, U+061C, U+200B-U+200F, U+202A-U+202E, U+2060, U+2066-U+2069, U+FEFF and tag characters (U+E0001, U+E0020-U+E007F); a test asserts each listed one is Cf.
- Behaviour that stays the same: whitespace and line/paragraph separators (Zs, Zl, Zp), control characters (Cc), surrogates (Cs), private use (Co) and unassigned (Cn) still become spaces.
- Edge cases, each covered by a test:
  - "Wang"+ZWSP+" Jun" still gives two words. "Jun"+ZWSP+"Wang" joins to "junwang". This is accepted because Cf is not a word separator in Unicode, the string shows no visible gap, and splitting inside words is what lost blocking recall.
  - Soft hyphen is deleted even with keepHyphen: Garcia+SHY+Molina gives "garciamolina", while a real hyphen still gives "garcia-molina".
  - A Persian word written with ZWNJ stays one word.
  - NFKD doesn't create or decompose Cf characters, so deleting after NFKD gives the same result as deleting before. A combining mark that follows a Cf character is still removed.
  - fold(fold(x)) == fold(x) holds for all test inputs, and the output never contains a Cf character.
- Work-order key cases: "Wang, Jun" with ZWSP, ZWJ, soft hyphen, a leading BOM, RLM on both sides of "Wang", or a word joiner all give family key "wang" and the same 4 keys as the clean name ("wang|j", ..., "wang|j|jun|example|2021"). This also holds for every listed Cf character in the family name, in the given name, and in both, and for initials, profile structured and display names, coauthor anchors, work keys and title tokens.
- Raw text is unchanged: after keys and features are computed, rawName, title and the profile's familyName equal the original strings code unit for code unit and still contain the Cf characters. Keys come from rawName and title: a garbled normalizedName or normalizedTitle doesn't change them, and a different rawName does.
- Both version bumps are needed:
  - er-blocking v1 to v2: blocking-key outputs change for any input that contains a Cf character.
  - er-features v1 to v2: every name, title, venue and affiliation feature goes through TextFolding, so feature values change for inputs with Cf characters. The new MatchFeaturesSuite test shows the v2 features of a version with Cf characters equal those of the clean version, which v1 would not do.
- Gold-set evaluation: not run and not part of this work order.
- The new tests fail without the fix by construction (old fold maps "Wa"+U+200B+"ng" to "wa ng", not "wang"). I did not run them against the old code.

ASSUMPTIONS:
- The deleted set depends on the JDK's Unicode tables. A JDK upgrade that adds Cf code points would change keys, and that would need a new version.
- Only derived keys and features change. Cf characters stay in rawName and title, and the work order asked for that.
- The TextFoldingSuite fixture's invisible character was a Cf character (likely ZWJ). The Edit tool matched the displayed text, and I replaced it with explicit escapes.

RISKS:
- Places outside my module that still say v1 (I couldn't search with grep, so I read the likely files; there may be others):
  - modules/hadoop/src/test/scala/scholarchain/hadoop/pipeline/LocalPipelineSuite.scala:288, literal "er-blocking.v1", breaks `sbt check` (owner: hadoop-engineer)
  - modules/hadoop/src/test/scala/scholarchain/hadoop/ShuffleAndParamsSuite.scala:166, literal "er-blocking.v1", breaks `sbt check` (owner: hadoop-engineer)
  - modules/hadoop/docs/job-contracts.md:92 and :95, test values `er-blocking.v1` and `er-features.v1`; :303 says "4 for `er-blocking.v1`". Wrong docs only, no test failure (owner: hadoop-engineer)
- These need no change:
  - LocalPipelineSuite.scala:646 ("er-blocking.v0") and :651 ("er-features.v0") are intentional mismatch probes and still pass.
  - modules/hadoop/src/test/scala/scholarchain/hadoop/TestSupport.scala reads the version through EntityResolution, not a literal.
  - tests/src/test/scala/scholarchain/tests/adversarial/UnicodeSuite.scala has no version literal and passed. Its W3 and W4 records (ZWJ and ZWNJ inside "Wang") only print their keys. Under v2 those keys would be "wang|j" (an inference; the printed value is in the output but I did not check it), so test-engineer may want to assert that.
  - docs/agent-runs/** are historical phase evidence and should stay v1.
- Curated outputs produced with er-blocking.v1 / er-features.v1 are not comparable with v2 outputs. The run ID changes because the manifest parameter values change.
- "Jun"+ZWSP+"Wang" now joins into one word. This is accepted and documented, but a name whose only separator is an invisible character now gets a different family key.

MANUAL ACTION REQUIRED:
- Route the two hadoop test literals and the job-contracts.md lines to hadoop-engineer (bump to "er-blocking.v2", or read EntityResolution.blockingKeysVersion, which is what TestSupport already does). Then rerun `sbt check` so markTestRun refreshes .claude/.last-test-run. Until then the Stop hook refuses completion because entity-resolution sources are newer than the marker. I did not touch the marker.
- Optional: ask test-engineer to tighten UnicodeSuite W3/W4 to assert "wang|j".

BLOCKED-ON OWNER: hadoop-engineer

BLOCKED-ON EVIDENCE:
- modules/hadoop/src/test/scala/scholarchain/hadoop/pipeline/LocalPipelineSuite.scala:288 `assert(allBlocks.forall(_.blockingVersion == "er-blocking.v1"))` fails with "assertion failed".
- modules/hadoop/src/test/scala/scholarchain/hadoop/ShuffleAndParamsSuite.scala:166 `assertEquals(src.version, "er-blocking.v1")` fails with "values are not the same", obtained er-blocking.v2.
- The bump is required by the work order and the provenance rule, because blocking-key outputs change.

NEXT GATE: hadoop-engineer updates the v1 literals, then `sbt check` passes (expected 394 tests, 0 failures) and the marker refreshes. After that comes code review of this diff and human acceptance of the Phase 5 prerequisite.

---

## 2. hadoop-engineer (HD-P5-00a)

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: HD-P5-00a (Phase 5 prerequisite, routed BLOCKED-ON from entity-resolution-engineer): fix the two Hadoop tests that hard-code "er-blocking.v1", update the version references in job-contracts.md to v2, and report any other v1 literals.

Summary: Both failing assertions are fixed, the docs now say v2, and `sbt check` passes with 0 failures. No production Hadoop code changed.

FILES CREATED: none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/pipeline/LocalPipelineSuite.scala
  - At line 288 the check is now `allBlocks.forall(_.blockingVersion == EntityResolution.blockingKeysVersion)`, with the clue "every block carries the production key source version".
  - The assertion is no weaker than before: it still fails if any block carries a version other than the production key source's.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/ShuffleAndParamsSuite.scala
  - At line 166, `src.version` is now compared with `EntityResolution.blockingKeysVersion`.
  - It is also pinned to the literal "er-blocking.v2", and this is the only literal pin in the module, so an unannounced version change still fails here. A comment explains the pin.
  - `assertEquals(src.levels, 4)` is unchanged.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/docs/job-contracts.md
  - Line 92: `er-blocking.v1` changed to `er-blocking.v2`.
  - Line 95: `er-features.v1` changed to `er-features.v2`.
  - Line 303: "(4 for `er-blocking.v1`)" changed to "(4 for `er-blocking.v2`, `BlockingKeys.Levels`)". I confirmed `BlockingKeys.Levels` is still 4 in modules/entity-resolution/.../BlockingKeys.scala.
- The intentional mismatch probes "er-blocking.v0" and "er-features.v0" are unchanged. They now sit at LocalPipelineSuite.scala:649 and :654 because my edit shifted the lines, and they pass.

COMMANDS RUN:
- `git ls-files -- modules/hadoop` (succeeded)
- `sbt check` (once, in the foreground, exit success, "[success] Total time: 108 s (0:01:48.0), completed Nov 4, 2026, 9:52:13 AM")

TEST RESULTS (verbatim summary lines from `sbt check`):
```
[info] Passed: Total 252, Failed 0, Errors 0, Passed 252
[info] Passed: Total 75, Failed 0, Errors 0, Passed 75
[info] Passed: Total 67, Failed 0, Errors 0, Passed 67
[info] Passed: Total 0, Failed 0, Errors 0, Passed 0   (rag, root, services, sparkAnalytics, storage: "No tests to run")
[info] Passed: Total 32, Failed 0, Errors 0, Passed 32
[info] markTestRun: core: production sources=16, test sources=16, passed=252
[info] markTestRun: entityResolution: production sources=5, test sources=6, passed=75
[info] markTestRun: hadoop: production sources=18, test sources=8, passed=67
[info] markTestRun: rag/root/services/sparkAnalytics/storage: passed=0
[info] markTestRun: tests: production sources=0, test sources=7, passed=32
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=d16e8c9717f806f6f4018b56beb456382c5ed23a, worktree=dirty, suites=33, passed=426, skipped=0)
[success] Total time: 108 s (0:01:48.0), completed Nov 4, 2026, 9:52:13 AM
```
426 tests passed, 0 failed, 0 errors. Only `sbt check` wrote the marker.

The full output (195,826 lines, 23.6 MB) is saved at /Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/3fc468f6-b6be-4de0-b5a4-26e276af7abf/tool-results/b0izb8bgx.txt

VALIDATION RESULTS:
- scalafmt check passed, and the hadoop test sources compiled.
- Production `EntityResolutionKeySource` (modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/BlockingJob.scala:84-86) takes both its version and its level count from `EntityResolution`, so no production change was needed.
- `TestSupport.blockingParams` and `TestSupport.candidateParams` already take their versions from `EntityResolution`.
- Other v1 literals found in the module, all fixture versions with nothing to do with entity resolution, left unchanged:
  - `"fixture-keys.v1"` at TestSupport.scala:130
  - `"fixture-single-level.v1"` at TestSupport.scala:147
- No other `er-blocking.v1` or `er-features.v1` literal turned up in the files I read: both suites, TestSupport, JobParams, BlockingJob (lines 1-90), and the job-contracts.md lines named in the work order.

ASSUMPTIONS:
- "Any other v1 literal" means entity-resolution version literals. Fixture key-source versions are test plumbing and stay as they are.
- The search was not exhaustive. I have no Grep tool, and the Bash allowlist has no grep or `git grep` row, so I checked by reading the likely files rather than scanning every file in the module. Unread files: DblpParser, normalizers, Pipeline, CandidateJob, and the rest of job-contracts.md.

RISKS:
- A stale `er-features.v1` or `er-blocking.v1` could remain in prose in the unread parts of job-contracts.md or in comments in unread files. It cannot be in an executed assertion, because all 67 hadoop tests pass against v2. A reviewer search (`code-reviewer` has Grep) would settle it.
- `sbt check` ran on a dirty worktree that includes entity-resolution-engineer's uncommitted ER-P5-00 changes, so the marker reflects that combined state.

MANUAL ACTION REQUIRED: The human reviews and commits the combined ER-P5-00 and HD-P5-00a diff.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: `code-reviewer` review of the combined diff, including a Grep for "er-blocking.v1" and "er-features.v1" under modules/hadoop/**. Then the orchestrator can resume entity-resolution-engineer's Phase 5 work.

---

## 3. test-engineer (TE-P5-00)

STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: TE-P5-00 (Phase 5 prerequisite). This covers the Phase 4 accepted deferral 1: Unicode format characters (Cf) inside names and titles. Nothing else was done.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/adversarial/FormatCharacterPipelineSuite.scala (16 tests)

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/adversarial/UnicodeSuite.scala
  - Removed the print-only W3/W4 line from the test "invisible format characters, RTL text and Turkish i give deterministic keys". R1 is still printed.
  - Added a test: "ZWJ and ZWNJ inside Wang: key wang|j, same pipeline block and candidate as W1, raw kept".

No resource fixtures were added. All fixtures are generated in test code from explicit hex code points.

COMMANDS RUN:
- `git status --short` (twice), `git ls-files -- ...` (twice), `git diff --stat`, `git diff -- <hadoop files>`
- `sbt check`: run once, foreground, no concurrent sbt. Output was about 27 MB, mostly Hadoop DEBUG/INFO logging.

TEST RESULTS (from the observed `sbt check` output, exact):
Summary lines, verbatim:
```
[info] Passed: Total 252, Failed 0, Errors 0, Passed 252
[info] Passed: Total 75, Failed 0, Errors 0, Passed 75
[info] Passed: Total 67, Failed 0, Errors 0, Passed 67
[info] Passed: Total 0, Failed 0, Errors 0, Passed 0   (rag, root, services, sparkAnalytics, storage: "No tests to run")
[info] Passed: Total 49, Failed 0, Errors 0, Passed 49
[info] markTestRun: core: production sources=16, test sources=16, passed=252
[info] markTestRun: entityResolution: production sources=5, test sources=6, passed=75
[info] markTestRun: hadoop: production sources=18, test sources=8, passed=67
[info] markTestRun: rag/root/services/sparkAnalytics/storage: passed=0
[info] markTestRun: tests: production sources=0, test sources=8, passed=49
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=d16e8c9717f806f6f4018b56beb456382c5ed23a, worktree=dirty, suites=34, passed=443, skipped=0)
[success] Total time: 115 s (0:01:55.0), completed Nov 4, 2026, 10:00:53 AM
```

| Project | Passed | Failed | Ignored |
|---|---|---|---|
| core | 252 | 0 | 0 |
| entityResolution | 75 | 0 | 0 |
| hadoop | 67 | 0 | 0 |
| tests | 49 | 0 | 0 |
| rag, root, services, sparkAnalytics, storage | 0 | 0 | 0 |
| **Total** | **443** | **0** | **0** |

The previous baseline was 426. The difference of 17 is 1 new UnicodeSuite test plus 16 FormatCharacterPipelineSuite tests. There are no failing tests, so no owner needs to be notified.

What each new test covers ([R] = regression-sensitive: it fails under the old v1 behaviour, where Cf characters became spaces; [G] = guard: it passes under both v1 and v2):

**UnicodeSuite (new test), [R]**
- W3 (ZWJ) and W4 (ZWNJ) now have key0 `wang|j` and keys(0..1) `wang|j`, `wang|j|jian`. Under v1 they were `ng|j`.
- Their level-0 membership blockId equals W1's, with blockKey `wang|j`.
- The membership blockingVersion equals `EntityResolution.blockingKeysVersion`.
- The W1/W3 and W1/W4 candidates exist, with `name.family_exact` = 1 and `name.first_given_agree` = 1.
- rawName equals the input with the same UTF-8 bytes, contains U+200D or U+200C, and is the same in `normalize/mentions`.

**FormatCharacterPipelineSuite.** The real 5-stage pipeline runs on 78 generated DBLP records, with maxBlockSize 50 and the real entity-resolution key source. Each of the 18 work-order characters (U+00AD, 200B, 200C, 200D, 200E, 200F, 202A-202E, 2060, 2066-2069, 061C, FEFF) is placed inside a family name, a given name, and a work title.
1. [G] All 78 records are accepted and 0 rejected. Every rawName and title is byte-identical to the input and still contains its Cf character.
2. [R] Family group (F*, 19 records) plus B1: keys `wang|j`, `wang|j|jian`, one level-0 pipeline block. Candidates against the baseline have `family_exact`, `family_key_agree`, `first_given_agree`, `best_variant_sim` and `token_jaccard` all = 1. Under v1 the family would be `ng`.
3. [R] Given-name group (G*): keys `zhao|m`, `zhao|m|meiling`, one block, `first_given_agree` = 1, `given_sim` = 1, `token_jaccard` = 1. Under v1 the level-1 key was `zhao|m|mei`.
4. [R] Title group (T*) plus TN1: work keys `graph_mining`, `graph_mining|methods`, `graph_mining|methods|2021`; normalizeTitle gives `graph mining methods`; one work block; `title.exact`, `title.sim` and `title.token_jaccard` = 1. TN1 has a ZWSP after "(Best Paper)"; under v1 the note was not stripped.
5. [G] Leading BOM on a DBLP author and on its coauthor gives the clean keys. v1 already trimmed a leading Cf.
6. [R] Cf mixed with NFD combining marks (`o`+U+0308+ZWSP, `o`+ZWJ+U+0308, SHY inside "Christina"): keys `popper|c`, `popper|c|christina`, one block, `family_exact` = 1.
7. [R] Cf next to a hyphen, and the soft hyphen (H1-H4): key `garciamolina|a`, one block, `family_exact` = 1. Also checks fold: SHY gives `garciamolina` even with keepHyphen, a real hyphen gives `garcia-molina` or `garcia molina`, and `-`+ZWSP and SHY+`-` give `garcia-molina`.
8. [R] Word joiner inside the particle "va|n" gives `vanbeethoven|l`. ZWSP inside the homonym number "00|01" still parses as homonym 0001 (`frost|c`), with homonym_suffix_both and homonym_suffix_agree = 1.
9. [G] Confusables: the Cyrillic а + ZWNJ name gives `wаng|j` (Cyrillic) and the Greek ο + ZWSP name gives `pοpper|c` (Greek). Neither shares a block with the Latin name, and no candidate against a Latin mention has `family_exact` = 1.
10. [G] Names made only of format characters (N1, N2): no keys, normalizeName gives "", not blocked. Blocking MISSING_KEY = 2, and exactly those two mention IDs are flagged `missing-key`. The readable coauthor keeps keys `nox|l...|-|2021` (no anchor from the unreadable name). No crash.
11. [G] Documented accepted behaviour: Jun+ZWSP+Wang gives `junwang|-`; Wang+ZWSP+space+Jun gives `jun|w`.
12. [G] Every block, membership and candidate (both researcher and work) carries `EntityResolution.blockingKeysVersion` and `featuresVersion`, plus literal pins `er-blocking.v2` and `er-features.v2`. No derived key contains a Cf character, and checkContracts passes. The literal pins would fail against the v1 constants.
13. [R] Every Cf code point on the JDK (more than 100, all 18 listed included) is deleted inside a word in both hyphen modes, and a Cf character on its own folds to "".
14. [G] Zs, Zl, Zp, Cc, Co, Cn (sampled) and lone surrogates (Cs) still give `wa ng`. This protects against deleting too much.
15. [R] Seeded fuzz (seed 441, 3000 strings over Cf, astral, mark, confusable, separator, digit and lone-surrogate symbols) checks:
    - fold(x) == fold(removeFormat(x)) in both hyphen modes
    - fold is idempotent
    - the output has no Cf, no Cc, no `|`, and no double or edge spaces
    - researcher keys, TitleText.words and work keys are the same for x and removeFormat(x)
    - keys have no Cf and no empty component
16. [G] Literal version pins.

VALIDATION RESULTS:
- `sbt check` passed. Scalafmt check passed for the tests tree, and the tests project compiled 2 changed sources.
- No production defect was found: every adversarial case matched the documented v2 behaviour.
- "Regression-sensitive" was established by hand-tracing the v1 fold (Cf turned into a space, then spaces collapsed) and the v1 TitleText (no removeFormat before the trailing-note strip). It was not established by running v1 code, which would have required reverting production files I do not own.

ASSUMPTIONS:
- An all-Cf author name is expected to be accepted at normalize and flagged `missing-key` at blocking, the same as other unreadable names. That is the observed behaviour.
- The test project baseline before this order was 32 tests. This follows from 426 − 252 − 75 − 67.

RISKS:
- The hadoop `AuthorMention.normalizedName` (TextNormalization.name: NFC, `\s` collapse, lower-case) still keeps Cf characters. For example, W3's normalizedName is "jian wa‍ng", which differs from W1's. This is per contract ("mechanical, no matching policy"), and keys do not read it. Any downstream consumer that matched on normalizedName would still split these names. Owner if a change is wanted: hadoop-engineer (policy decision for the human).
- The accepted joining behaviour ("Jun"+ZWSP+"Wang" gives `junwang`) is pinned as documented. Whether a ZWSP should count as a separator remains a policy choice for entity-resolution-engineer.
- The JDK's Unicode tables decide which characters are Cf (Java 17.0.17 observed). Test 13 enumerates them at runtime, so a JDK upgrade could change the set.
- `sbt check` output is about 27 MB of Hadoop DEBUG logging, which makes logs hard to review. Owner: build-engineer (log config). This is not a test defect.

MANUAL ACTION REQUIRED: Human review and commit of the two test files, together with the uncommitted ER-P5-00 and HD-P5-00a changes. No commits or pushes were made.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer reviews the test diff (UnicodeSuite.scala and FormatCharacterPipelineSuite.scala) together with the ER-P5-00 and HD-P5-00a changes, then the human accepts the deferral and commits.
