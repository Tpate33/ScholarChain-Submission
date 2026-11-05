# Phase 5 prerequisite (Unicode format characters): work orders

> **PREREQUISITE WORK ONLY. THIS IS NOT COMPLETION OF PHASE 5.**

Work orders issued by the orchestrator, verbatim, in the order issued. Each was given to exactly one
subagent in a fresh context.

| # | ID | Agent | Reason |
|---|---|---|---|
| 1 | ER-P5-00 | `entity-resolution-engineer` | the fix (human prompt 1) |
| 2 | HD-P5-00a | `hadoop-engineer` | routed from ER-P5-00 `BLOCKED-ON hadoop-engineer` (see `prereq-unicode-repair-01.md`) |
| 3 | TE-P5-00 | `test-engineer` | regression assertions (human prompt 1) |
| 4 | RV-P5-00 | `code-reviewer` | focused review (human prompt 1) |

## 1. ER-P5-00 (entity-resolution-engineer)

```text
WORK ORDER ER-P5-00 (Phase 5 prerequisite; this is the only work authorized. Do NOT start any other Phase 5 work: no scoring, thresholds, decisions, adjudication, gold sets.)

Source: docs/agent-runs/phase-04/human-gate.md "Accepted deferrals", first item (human-recorded, owner entity-resolution-engineer):
"Unicode format characters inside names (ZWJ, ZWNJ, zero-width space, word joiner, soft hyphen, BOM, bidi marks) are folded to spaces and split family keys (TextFolding.scala:70-81), causing blocking recall loss."

Defect: modules/entity-resolution/src/main/scala/scholarchain/entityresolution/TextFolding.scala, `fold`: after NFKD and mark removal, every code point that is not a letter/digit/apostrophe/hyphen becomes a space. Format characters (Unicode general category Cf, e.g. U+00AD soft hyphen, U+200B ZWSP, U+200C ZWNJ, U+200D ZWJ, U+200E/U+200F LRM/RLM, U+202A-U+202E bidi embeddings/overrides, U+2060 word joiner, U+2066-U+2069 bidi isolates, U+061C ALM, U+FEFF BOM) are invisible and occur inside words, so e.g. "Wa​ng, Jun" folds to family "wa ng" instead of "wang" and lands in a different block.

Required:
1. Fix in your module only: delete (not space) format characters in `fold`, before any other per-code-point classification. Decide and document precisely which set you delete (recommended: Character.getType(cp) == Character.FORMAT, i.e. all Cf). Consider and document edge cases: Cf characters at word boundaries (e.g. "Wang​ Jun" must still give two words; "Jun​Wang" with no real space will join — state that this is accepted and why), soft hyphen vs real hyphen with keepHyphen, Persian ZWNJ, NFKD interaction. Whitespace (Zs/Zl/Zp) and controls (Cc) keep current behavior unless you justify a change. Update the TextFolding Scaladoc step list.
2. Raw names are preserved: AuthorMention.rawName, SourceAuthorProfile names, WorkRecord.title, and any stored raw text must be unchanged; only derived keys/features change. Add a test proving the input raw string is not altered and that keys are read from the raw name.
3. Versioning (provenance invariant): blocking key outputs change, so bump BlockingKeys.Version "er-blocking.v1" -> "er-blocking.v2". MatchFeatures also uses TextFolding, so if any feature value can change, bump its version (er-features.v1 -> v2) too; report which you bumped and why. Locate every reference to these version strings in the repository (git grep is allowed to you via @GIT? if not, use Grep). If any reference outside modules/entity-resolution/** must change for sbt check to pass (e.g. modules/hadoop/**, modules/core/**, tests/**, docs), do NOT edit it: list each path:line with its owner in RISKS / BLOCKED-ON. Note: tests/** is owned by test-engineer and will be updated in a follow-up work order; if only tests/** assertions on the literal version string fail, report them precisely rather than blocking.
4. Feature tests in your module (modules/entity-resolution/src/test/**) that fail without the fix: each listed Cf character inside a family name yields the same family block key and level-0 key as the clean name (e.g. "Wang, Jun" vs Wa+ZWSP+ng, Wa+ZWJ+ng, Wa+SOFT HYPHEN+ng, BOM+Wang, RLM-wrapped Wang, Wa+WJ+ng); same for given names/initials and work title tokens; idempotence (fold(fold(x)) == fold(x)); no change for names without Cf; version constant asserts the new value.
5. Run `sbt check` and report exact observed counts per project and totals, verbatim summary lines. If tests outside your module fail only because of the version bump, report the exact failing test names and messages.

Constraints: write only under modules/entity-resolution/**. No constitutional changes, no AWS, no commits/pushes. No hand-written test marker. Return the full report contract (STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE).
```

## 2. HD-P5-00a (hadoop-engineer)

```text
WORK ORDER HD-P5-00a (Phase 5 prerequisite, routed BLOCKED-ON from entity-resolution-engineer). Scope is strictly limited to the items below; no other Hadoop or Phase 5 work.

Context: entity-resolution-engineer (work order ER-P5-00) fixed TextFolding so invisible Unicode format characters (category Cf: ZWSP, ZWJ, ZWNJ, soft hyphen, BOM, bidi marks, word joiner, ...) are deleted instead of becoming spaces, and bumped BlockingKeys.Version "er-blocking.v1" -> "er-blocking.v2" and MatchFeatures.Version "er-features.v1" -> "er-features.v2" (uncommitted changes under modules/entity-resolution/**). `sbt check` now fails only on two Hadoop tests that hard-code the old literal:

1. modules/hadoop/src/test/scala/scholarchain/hadoop/pipeline/LocalPipelineSuite.scala:288  `assert(allBlocks.forall(_.blockingVersion == "er-blocking.v1"))`  -> "assertion failed"
2. modules/hadoop/src/test/scala/scholarchain/hadoop/ShuffleAndParamsSuite.scala:166  `assertEquals(src.version, "er-blocking.v1")` -> obtained er-blocking.v2

Required:
- Fix both assertions. Preferred: keep them meaningful but not brittle, i.e. compare against the version exposed by entity-resolution (as TestSupport.scala already does via EntityResolution), and, if you want a pin, also assert the literal "er-blocking.v2" in exactly one place so an unannounced version change is still caught. Do not weaken the assertion (it must still fail if blocks carry a different version than the production key source).
- Leave the intentional mismatch probes at LocalPipelineSuite.scala:646 ("er-blocking.v0") and :651 ("er-features.v0") unchanged unless they break.
- Update modules/hadoop/docs/job-contracts.md:92, :95 (er-blocking.v1 / er-features.v1) and :303 ("4 for er-blocking.v1") to v2, checking the level count is still 4 (BlockingKeys.Levels). Search your module for any other v1 literal and report it.
- Do not change any production Hadoop code. Write only under modules/hadoop/**. Do not touch modules/entity-resolution/** or tests/**.
- Run `sbt check` (once, foreground, not concurrently with another sbt run) and report the verbatim summary lines and per-project counts. Expected: 0 failures.

No constitutional changes, no AWS, no commits/pushes, never hand-write the test marker. Return the full report contract (STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE).
```

## 3. TE-P5-00 (test-engineer)

```text
WORK ORDER TE-P5-00 (Phase 5 prerequisite; scope limited to this item, no other Phase 5 work).

Source: docs/agent-runs/phase-04/human-gate.md "Accepted deferrals", first item: Unicode format characters inside names (ZWJ, ZWNJ, zero-width space, word joiner, soft hyphen, BOM, bidi marks) were folded to spaces and split family keys; "UnicodeSuite:161-165 does not assert this".

Change under test (uncommitted, do NOT edit it): entity-resolution-engineer (ER-P5-00) changed modules/entity-resolution/.../TextFolding.scala so every Unicode category Cf code point is deleted (first check per code point, before marks/transliteration/letters/apostrophes/hyphens); TitleText.words in Names.scala deletes Cf before stripping trailing editorial notes; BlockingKeys.Version is now "er-blocking.v2" and MatchFeatures.Version "er-features.v2". Raw names/titles must remain unchanged. hadoop-engineer (HD-P5-00a) updated two hadoop test literals. Current `sbt check`: 426 passed, 0 failed.

Documented intended behavior to attack:
- Cf inside a word joins it ("Wa"+ZWSP+"ng" -> "wang"); Cf next to a real space keeps two words; "Jun"+ZWSP+"Wang" -> "junwang" (accepted).
- Soft hyphen deleted even with keepHyphen; real hyphen still "-".
- Zs/Zl/Zp, Cc, Cs, Co, Cn still become separators.
- fold is idempotent and output never contains Cf.

Required (write only under tests/**):
1. Tighten tests/src/test/scala/scholarchain/tests/adversarial/UnicodeSuite.scala around lines 161-165: W3 and W4 (ZWJ/ZWNJ inside "Wang") currently only print keys. Assert they now equal "wang|j" and land in the same pipeline block as W1 (end-to-end through the real pipeline, as the suite already does), and that their rawName in the curated output still contains the original format characters byte-for-byte.
2. Add adversarial regression cases driven through the real pipeline and/or the public EntityResolution API, using fixtures under tests/src/test/resources/** if needed: each of U+00AD, U+200B, U+200C, U+200D, U+200E, U+200F, U+202A-U+202E, U+2060, U+2066-U+2069, U+061C, U+FEFF inside family name, given name, and work title; a leading BOM on a DBLP author; mixed Cf plus NFD combining marks; Cf adjacent to hyphen; a name made only of Cf characters (must yield no keys / be handled without crash, as for other unreadable names); confusable check still holds (Cf removal must not make a Cyrillic/Greek lookalike merge with Latin). Assert blocks/candidates carry blockingVersion "er-blocking.v2" and featureVersion "er-features.v2" (read from EntityResolution where possible, plus one literal pin).
3. Make sure each new assertion would have failed against the old behavior (v1 fold turned Cf into spaces); state which ones are regression-sensitive. If you find a real defect in production code, do not fix it: report it with exact input, expected, observed, and owner.
4. Run `sbt check` once (foreground, no concurrent sbt) and report verbatim summary lines and per-project counts.

No production edits, no constitutional changes, no AWS, no commits/pushes, never hand-write the test marker. Return the full report contract (STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE).
```

## 4. RV-P5-00 (code-reviewer)

```text
FOCUSED REVIEW RV-P5-00 (read-only). Review ONLY the uncommitted diff against HEAD d16e8c9 for the Phase 5 prerequisite: Phase 4 human gate (docs/agent-runs/phase-04/human-gate.md, "Accepted deferrals" item 1) — Unicode format characters (category Cf: ZWJ, ZWNJ, ZWSP, word joiner, soft hyphen, BOM, bidi marks) were folded to spaces in TextFolding.fold, splitting name blocking keys.

Changed files (verify the list yourself against the working tree; flag anything else changed):
- modules/entity-resolution/src/main/scala/scholarchain/entityresolution/TextFolding.scala (fold deletes all Cf as first per-code-point check; new isFormat, removeFormat; Scaladoc)
- modules/entity-resolution/src/main/scala/scholarchain/entityresolution/Names.scala (TitleText.words removes Cf before trailing-note strip)
- modules/entity-resolution/src/main/scala/scholarchain/entityresolution/BlockingKeys.scala (Version er-blocking.v2)
- modules/entity-resolution/src/main/scala/scholarchain/entityresolution/MatchFeatures.scala (Version er-features.v2)
- modules/entity-resolution/src/test/scala/scholarchain/entityresolution/{FormatCharactersSuite (new), BlockingKeysSuite, MatchFeaturesSuite, TextFoldingSuite}.scala
- modules/hadoop/src/test/scala/scholarchain/hadoop/ShuffleAndParamsSuite.scala, modules/hadoop/src/test/scala/scholarchain/hadoop/pipeline/LocalPipelineSuite.scala (version literal -> EntityResolution.blockingKeysVersion, one v2 pin)
- modules/hadoop/docs/job-contracts.md (v1 -> v2 at ~92, 95, 303)
- tests/src/test/scala/scholarchain/tests/adversarial/UnicodeSuite.scala, tests/src/test/scala/scholarchain/tests/adversarial/FormatCharacterPipelineSuite.scala (new)

Agent-reported (not verified by you; do not trust): sbt check passed, core 252, entityResolution 75, hadoop 67, tests 49 = 443 passed, 0 failed.

Check specifically:
1. Correctness: Cf deletion happens before every other classification; no path still converts Cf to space (fold, words, TitleText, PersonName parsing incl. particles, homonym numbers, initials, firstGivenKey, profile names, coauthor anchor, work keys); surrogate-pair/code point iteration correct; idempotence; NFKD interaction; soft hyphen vs keepHyphen; over-deletion (Zs/Zl/Zp/Cc must remain separators).
2. Raw names/titles preserved; keys still read rawName/title, not normalized fields.
3. Over-merging risk: does deleting Cf let confusables or distinct names merge (e.g. Cf as only word separator, "Jun"+ZWSP+"Wang")? Is the accepted behavior documented and bounded? Does any Cf deletion create new merges via the vowel-digraph collapse or particle joining?
4. Versioning/provenance: both bumps justified and complete; grep the whole repo (excluding docs/agent-runs/**) for remaining "er-blocking.v1" / "er-features.v1" literals and any place that hard-codes a version; check that core contracts or config do not pin v1.
5. Test quality: do the new tests actually fail without the fix (trace, don't assume)? Any tautological assertions (e.g. comparing fold(x) to fold(removeFormat(x)) only)? Did the hadoop test edits weaken assertions? JDK-dependent Cf enumeration risk.
6. Ownership: every changed path written by its owner per docs/agents.md; no constitutional, build, or marker edits; no production Hadoop change.

Return APPROVE or REQUEST_CHANGES with verified findings only (severity, file:line, failing input, impact, owning agent), separating blockers from non-blocking notes. State which items you could not verify. Use the full report contract.
```
