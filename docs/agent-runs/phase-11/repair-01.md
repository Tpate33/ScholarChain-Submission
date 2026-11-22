# Phase 11 repair 01 (rag-engineer, RG-P11-02)

Findings routed: CR-P11-01-1 (MAJOR, R1 subject echo), CR-P11-01-2 (minor, fabricated `ev:` echo; orchestrator chose count-only, human may overrule), CR-P11-01-3 (minor, free-text title forging). Owner: rag-engineer.

Change summary (orchestrator): placeholder `(a subject not in the evidence)` for any subject that is not a bundle fact subject in every problem label; verifier `scholarchain.rag.claim-verification.v4`; new audit field `verifierVersion` (audit-record schema v3, v2 superseded); fabricated evidence IDs shown only as a count; `title` facts read only from work-summary items under `Excerpts.shown`. tests/** untouched.

Rerun evidence: final `sbt check` exit 1, 431 s; all 2380, passed 2376, failed 3, skipped 1. rag 141/141 (+9 RepairR1Suite). The 7 rag-owned tests/** tests (T1(a)-(f), T12 forged title) PASS. Remaining 3 failures: service-engineer-owned T2, T3, T9.

## Agent report (verbatim)

STATUS: BLOCKED-ON
OWNER: rag-engineer
WORK ORDER: RG-P11-02, Phase 11 repair 01 (CR-P11-01-1, -2, -3), 2026-11-22

All three rag findings are fixed. All 7 rag-owned tests in tests/** now pass, and I did not edit them. The one full `sbt check` has exactly 3 failures, as predicted. All 3 are in tests/** and owned by service-engineer. Because tests failed, markTestRun did not update the marker and my SubagentStop gate refuses. That refusal is expected.

What changed:
- **CR-P11-01-1, subject echo (R1, major).** `ClaimVerificationAgent.verify` now shows a subject in a problem label only if it is the subject of a bundle fact. Any other subject becomes the fixed placeholder `(a subject not in the evidence)`. This applies to the Missing, SupportedUncited, Conflicting and NotProof labels, the kindProblem label and the render-problem label. Subjects that are in the bundle are still shown, and a regression test checks this.
- **Verifier version.** Bumped to `scholarchain.rag.claim-verification.v4`. The prompt (`answer-synthesis.v3`) and output schema (`answer-synthesis-output.v3`) are unchanged.
- **Audit records the version.** The audit record had no field for it, so I added `verifierVersion` (always serialized, null when nothing was verified). That required audit record schema v3 (`prompts/schemas/audit-record.v3.schema.json`); v2 is now listed in `prompts/SUPERSEDED.md`. The field sits last with a default, so code that builds an `AuditRecord` positionally still compiles. Kept claims and the answer also carry v4 as their provenance `policyVersion`.
- **CR-P11-01-2, fabricated `ev:` IDs (minor).** Client text now shows only a count: `cites N evidence ID(s) not in the evidence bundle`. The raw IDs stay internal in `ClaimResult.fabricatedEvidenceIds`.
  - **Policy point for the human:** count-only was the orchestrator's choice and matches the test-engineer oracle. The human may overrule it.
- **CR-P11-01-3, forged titles (minor).** `BundleFacts` now reads `title` facts only from `work-summary` items, and only values that `Excerpts.shown(WorkSummary, "title", _)` would show (the same ShownFacts and stable-ID rules `ofDocument` applies). Work-abstract chunks and other free text no longer yield titles. They still yield `has-context-document`.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/rag/src/test/scala/scholarchain/rag/agents/RepairR1Suite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/prompts/schemas/audit-record.v3.schema.json

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/agents/ClaimVerification.scala (subject placeholder, count-only fabricated label, v4, docs)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/agents/BundleFacts.scala (titles only from work-summary items, under `Excerpts.shown`)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/agents/Audit.scala (`verifierVersion`, `@serializeDefaults(true)`, schema v3)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/agents/Workflow.scala (sets `verifierVersion` when claims were verified)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/rag/src/test/scala/scholarchain/rag/agents/RepairP9bSuite.scala (F1 control version pin moved v3 to v4)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/rag/src/test/scala/scholarchain/rag/agents/RepairP9Suite.scala (audit schema version pin moved v2 to v3)
- /Users/taufeeqpatel/Desktop/ScholarChain/prompts/SUPERSEDED.md (audit-record v2 superseded; verifier v4 note)

COMMANDS RUN:
1. `git status --porcelain` (twice) and `git status --short`: ok.
2. `git ls-files` with path filters (several): ok.
3. Two `git grep` calls: refused by the guard (no allowlist row); nothing ran. I inspected code with Read instead.
4. `sbt rag/test`, first run: compile error. My local name `label` clashed with an existing local; I renamed it `subjectLabel`.
5. `sbt rag/test`, second run: rag Total 141, Failed 1. The failure was the RepairP9Suite audit-schema v2 pin, which I then updated.
6. `sbt test`, iteration run in the background: exit 1, tests project 270 total, 3 failed (the service suites), every other project green.
7. `sbt check`, the single final run in the background: exit 1, 431 s, "(markTestRun) sbt.TestsFailedException".

TEST RESULTS (final `sbt check`, as printed):

| Project | Total | Passed | Failed | Skipped |
|---|---|---|---|---|
| core | 621 | 621 | 0 | 0 |
| entityResolution | 464 | 464 | 0 | 0 |
| hadoop | 85 | 85 | 0 | 0 |
| rag | 141 | 141 | 0 | 0 |
| root | 0 (no tests) | 0 | 0 | 0 |
| services | 99 | 99 | 0 | 0 |
| sparkAnalytics | 263 | 263 | 0 | 0 |
| storage | 437 | 437 | 0 | 0 |
| tests | 270 | 266 | 3 | 1 |
| all (summed) | 2380 | 2376 | 3 | 1 |

- rag went from 132 to 141: the 9 new RepairR1Suite tests (T1-T8 plus the in-bundle regression) all pass.
- The 7 rag-owned tests/** tests, by name, all pass:
  - AnswerTextIdLeakAdversarialSuite T1(a): PASS
  - AnswerTextIdLeakAdversarialSuite T1(b): PASS
  - AnswerTextIdLeakAdversarialSuite T1(c): PASS
  - AnswerTextIdLeakAdversarialSuite T1(d): PASS
  - AnswerTextIdLeakAdversarialSuite T1(e): PASS
  - AnswerTextIdLeakAdversarialSuite T1(f): PASS
  - AbstractChunkInjectionAdversarialSuite "T12 free abstract text is never re-labelled as a work title (forged title line in a chunk)": PASS
  - The T1 control, T11 and the other T12, T13 and T14 tests also pass.
- The existing PARTIALLY_SUPPORTED, CONFLICTING and F1 tests pass, including WorkflowSuite, RepairP9bSuite and the gate suite G4 and F1 tests.
- The skipped test is the existing one in SemanticRetrievalCorpusSmokeSuite.

VALIDATION RESULTS:
- `sbt check` got past the format check and compiled under -Werror; it failed only at the test step.
- BenchmarkSuite inside `sbt check`: graph 11/11 cases (87/87 checks), semantic 2/2 cases (3/3 checks). The `sbt benchmarkQueries` row is inactive (B10).
- No network, AWS, model or paid call was made. I did not touch tests/**, modules/services/**, modules/core/**, build files, hooks or the marker.

ASSUMPTIONS:
- I reasoned that the new RepairR1Suite tests fail without the change, but I did not observe it. On HEAD the same code paths fail the tests/** oracle (observed by TE-P11-02), and T8 needs the new `verifierVersion` field, so it would not compile there.
- "The audit records the new version" needed a new audit field, which meant audit schema v3. Storage accepts the new schema version (the WorkflowSuite and RepairP9Suite round-trip tests pass).
- Count-only fabricated-ID labels, as the work order chose.

RISKS:
- The audit schema moved from v2 to v3. Any consumer outside this repository that pins v2 would need updating; none exists in the repository.
- The subject length bound is unchanged. Subjects are still unbounded in validation, but they are no longer echoed unless they are a bundle fact subject.
- Outstanding before a real model is connected: omission or cherry-picking of true facts, and R2 (combining marks in quoted titles).

MANUAL ACTION REQUIRED:
- The human confirms or overrules the count-only policy for fabricated evidence IDs (CR-P11-01-2).
- The orchestrator routes the 3 service failures to service-engineer, then code-reviewer reviews this diff.

BLOCKED-ON OWNER: service-engineer

BLOCKED-ON EVIDENCE: final `sbt check`: "[error] Failed: Total 270, Failed 3, Errors 0, Passed 266, Skipped 1 ... Failed tests: scholarchain.tests.services.ConfigOmissionAdversarialSuite, scholarchain.tests.services.MalformedBodyAdversarialSuite ... (markTestRun) sbt.TestsFailedException: Tests unsuccessful". The failing tests are:
- MalformedBodyAdversarialSuite "T2 duplicate JSON keys (question twice, mode twice) are 400, workflow never called": QueryService.scala:113-130.
- MalformedBodyAdversarialSuite "T3 bidi/format controls and a lone surrogate: never 5xx, never echoed, never resolved": lone-low-surrogate gives 200 answered (QueryService.scala:134-140).
- ConfigOmissionAdversarialSuite "T9 every null port of the composition is a Left naming it, never an exception": NullPointerException on `kind.serves`, because `kind` is null (ServiceRuntime.scala:46-71).

The SubagentStop gate refuses because source changes are newer than the marker. I did not write the marker.

NEXT GATE: service-engineer repairs T2, T3 and T9. Then one `sbt check` with every test green (which refreshes the marker), then code-reviewer reviews the full Phase 11 repair diff.
