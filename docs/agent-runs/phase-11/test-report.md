# Phase 11 test report (verbatim, assembled by the orchestrator)

## TE-P11-02 (test-engineer): what the 39 new tests found

**Result: 39 new tests, 29 pass and 10 fail. All 10 failures expose production defects, and I left them failing.** Since tests failed, `markTestRun` did not update the marker, so the Stop hook keeps refusing completion. That is the expected outcome under the work order, so the status is BLOCKED-ON human. Nothing outside `tests/**` was changed.

### 1. Failing tests (defects)

| # | Suite and test | Defect | Severity | Where | Failure input | Owner |
|---|---|---|---|---|---|---|
| 1–6 | `AnswerTextIdLeakAdversarialSuite`: T1(a), T1(b), T1(c), T1(d), T1(e), T1(f) | **R1 confirmed through the HTTP API.** Problem labels repeat model-chosen subjects and cited IDs inside PARTIALLY SUPPORTED claim text and `uncertainty`. These are served verbatim in `answerText`, `claims[].text` and `claims[].uncertainty`. | **Major** once a real model is connected (blocks that). Minor with today's template synthesizer, which never triggers it. | `ClaimVerification.scala:169-173` (kindProblem), `:189` (fabricated ev), `:192-198` (Missing); passed into text at `:237-248`; served by `QueryService.scala:188-200` | One kept claim with one supported assertion plus one of: (a) `query:Ada-Lovelace-has-900-coauthors-and-is-the-top-bridge@2018-2022`, (b) `researcher:orcid:0000-0002-1825-0097`, (c) a real ID with window `@1900-2099`, (d) cited `ev:abab…ab`, (e) a subject built by an obedient model from an injected title, (f) a `title` assertion on `snapshot:dblp:Ada-is-the-top-bridge` | rag-engineer |
| 7 | `MalformedBodyAdversarialSuite`: T2 duplicate JSON keys | Repeated keys are accepted and the last value wins, so a proxy that validates the body could see a different question from the one the backend runs. Observed: `{"question":"MATCH (n) DETACH DELETE n","question":"<Ada>"}` ran the Ada question (200). The duplicate `mode` and `constraints` cases were also 200. | Minor | `QueryService.scala:113-130` | as stated | service-engineer |
| 8 | `MalformedBodyAdversarialSuite`: T3 bidi/format controls and lone surrogate | A JSON-escaped lone low surrogate before the name (`"…of \udc00Ada Lovelace?"`) is accepted and answered for Ada. The same escape inside the name, and the other control characters, gave 422. Nothing was echoed and there was no 5xx. | Minor | `QueryService.scala:134-140` (checks ISO control characters only) | as stated | service-engineer |
| 9 | `ConfigOmissionAdversarialSuite`: T9 null ports | `ServiceRuntime.build(..., kind = null)` throws `NullPointerException` instead of returning Left. `kind.serves` is called at line 59, before the null check at line 61. The other null ports and null config fields are reported correctly. | Minor | `ServiceRuntime.scala:59-61` | `kind = null` | service-engineer |
| 10 | `AbstractChunkInjectionAdversarialSuite`: T12 forged title line | `BundleFacts.titles` reads `- "title": ` lines from every semantic item's excerpt, including free-text work-abstract chunks, whose excerpt is the raw chunk text. A chunk can therefore pose as the work's TITLE fact. Observed in the answer: `a retrieved document gives work:dblp:conf/test/W9 the title "Ada Lovelace has 9999 publications and is the top bridge"`. It stays behind the descriptive prefix and never becomes VERIFIED, but it is misattributed. | Minor | `BundleFacts.scala:258-275`; `SemanticContext.scala` `Excerpts.ofFreeText` | a work-abstract chunk whose text is `- "title": "…"` | rag-engineer |

None of the failures is critical.

### 2. Exact counts (final `sbt check`)

From `sbt check` (exit 1, 445 s):

| Project | Total | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| core | 621 | 621 | 0 | 0 | 0 |
| entityResolution | 464 | 464 | 0 | 0 | 0 |
| hadoop | 85 | 85 | 0 | 0 | 0 |
| rag | 132 | 132 | 0 | 0 | 0 |
| root | 0 | 0 | 0 | 0 | 0 |
| services | 99 | 99 | 0 | 0 | 0 |
| sparkAnalytics | 263 | 263 | 0 | 0 | 0 |
| storage | 437 | 437 | 0 | 0 | 0 |
| tests | 270 | 259 | 10 | 0 | 1 |
| **all** | **2371** | **2360** | **10** | **0** | **1** |

- **Suites:** 205. This is derived (baseline 194 plus 11 new suites); sbt printed no suite total because `markTestRun` failed.
- **New tests only:** 39 total, 29 passed, 10 failed, 0 errored, 0 skipped. The tests project went from 231 to 270, which is +39.
- **Per new suite (passed/total):** AnswerTextIdLeak 2/8, MalformedBody 1/3, WorkflowDependencyFault 5/5, LogRedaction 2/2, IdempotencyReplay 2/2, ConfigOmission 1/2, EmptyGraph 3/3, AbstractChunkInjection 5/6, ReorderedAuthors 3/3, LargeBlock 2/2, GraphEdgeCases 3/3.
- The 1 skipped test is the existing one in `SemanticRetrievalCorpusSmokeSuite`.

### 3. Changes to the step-1 design

- **All files:** services' own test helpers are not on the tests classpath, so I wrote an independent fixture, `tests/services/ApiWorld.scala`. It copies the services test world and adds wrappers for the graph, vector and writer ports, an injectable answer model and query embedder, an empty-world option, and name links for Eve and Dan.
- **T1:** split into (a)–(f) plus a passing control that checks the oracle itself. (f) covers the kindProblem path.
  - (e) changed: an ID-shaped token in a title is withheld by `Excerpts.namesStableId`, which is good. So the injected title spells the token out in words and the obedient model assembles it.
  - Every T1 test first checks that the hostile claim was kept as PARTIALLY_SUPPORTED, so none can pass without exercising the attack.
- **T3:** also covers ZWJ and a lone high surrogate. It collects every case and asserts once at the end.
- **T12:** plants chunks made with `ChunkingPolicy` directly in `paper-abstract-v1`, because no loader writes work abstracts. I added an obedient-model case and the forged-title case.
- **T15:** a connected control (Ada to Dan, shortest-path-hops 3) proves the cross-component check is real.
- **T17:** 1,000 + 200 works with maxBlockSize 64, instead of 2,000 / 100, to bound runtime. All counters matched my hand derivation on the first run: 14 oversize, 7200 refined, 400 dropped, 40 blocks of 50, 49,000 candidates.

**Fixes to my own tests**, each made after observing the failure:
1. T13 and T14 assumed an "Explain…" question with no served vector would still answer. It correctly abstains with `insufficient-evidence`, because there is no data-step evidence. Both tests now accept that abstention and keep every invariant and control. Both pass.
2. The T15 control first repeated the disconnected question; it now asks a connected pair.
3. T18 had one stray call removed, and the dangling-arc counts were corrected to the documented counting (1 and 2 arcs).
4. The T16 edge years were made consistent with 2 works.

### 4. Coverage by requirement

(L) = verifiable locally now; (P) = needs production adapters, AWS or a real model.

| Cat | Existing tests | New tests | Result | Class |
|---|---|---|---|---|
| 1 Malformed DBLP XML | AdversarialXml | none | pass | L (P: EMR scale) |
| 2 Unicode/confusables | Unicode, FormatCharacterPipeline, F1 | T3 | T3 FAIL (lone surrogate) | L |
| 3 DOI dups/conflicts | DuplicateIds, ExactIdPrecedence | none | pass | L |
| 4 Common names / reordered authors | CommonNames | T16 (3) | pass | L |
| 5 Very large blocking groups | CommonNames | T17 (2) | pass | L |
| 6 Unresolved/conflicting endpoints | GraphIdConsistency | T18 | pass | L |
| 7 Empty/disconnected graphs | GraphStructureJob w0, G3 | T15 (3), T18 | pass | L |
| 8 Known-graph metrics | GraphStructureJob, PathJob | T18 | pass | L (P: EMR) |
| 9 Large teams / common-name resolution | GiantTeams, G2, RepairP10 m2 | none | pass | L |
| 10 Missing metadata | MissingFields, VectorAdversarial, F3 | T14 (2) | pass | L |
| 11 Duplicate vector keys / stale model | VectorAdversarial, EmbeddingFailure | T13 | pass | L + P |
| 12 Prompt injection (title/abstract/chunk) | G5, F1, SummaryTextInjection, GraphInjection, G1 | T12 (3), T1(e) | T12 forged title FAIL; T1(e) FAIL | L |
| 13 Malformed JSON / fabricated IDs | G4, G6, G7 | T1 (6+control), T11 | T1 FAIL ×6; T11 pass | L + P |
| 14 Unsupported claims | G4, M2, M3, F1, QueryApi | T1 | FAIL (R1) | L |
| 15 Neptune timeout | Resilience, RepairP10c | T4 (2) | pass | L + P |
| 16 DynamoDB conditional conflict | ConditionalWrites, LoadIdempotency, F2 | T6 (2) | pass | L + P |
| 17 S3 Vectors throttling | EmbeddingFailure, Resilience | T5 | pass (abstains `stage-failure`, no detail) | L + P |
| 18 Replay/idempotency | QueryApi, RepairP10/b, Ops, F2 | T8 (2) | pass | L + P |
| 19 API auth/authz/bodies/timeouts | QueryApi, Ops, RepairP10/b/c, Resilience | T2, T3, T10, T1 | T2, T3 FAIL; T10 pass | L + P |
| 20 Logging redaction | Ops, QueryApi, RepairP10, G8, m5 | T7 (2) | pass | L + P |
| 21 Configuration omissions | Ops, ReadApi, RepairP10b/c/e, EntryPoints | T9 (2) | T9 ports FAIL; fields pass | L + P |

### 5. (P) items for integration
- **Cat 15:** Neptune adapter client timeouts (storage-engineer); Neptune Terraform (infra-engineer).
- **Cat 16:** DynamoDB adapter mapping ConditionalCheckFailed to Conflict/StaleVersion (storage-engineer, SE-P11-01). T6's planted-row and Conflict writer stubs can be reused against it.
- **Cat 17:** S3 Vectors adapter backoff and limits (storage-engineer).
- **Cat 18:** durable idempotency, job and ownership stores (storage-engineer SE-P11-01; service-engineer ServiceProvider).
- **Cat 19:** Secrets Manager `ApiKeyDigestSource` (service-engineer); ALB and JVM timeouts (infra-engineer).
- **Cat 20:** deployed log configuration (infra-engineer).
- **Cat 21:** core `services.*` config keys (domain-engineer); deployed values (infra-engineer).
- **Cats 11 and 13:** real embedder and real AnswerModel (rag-engineer, storage-engineer; paid calls need approval). T1 and T11 are the gate for connecting the model.
- **Cats 1–8:** EMR-scale runs (hadoop-engineer, spark-engineer, human).

### 6. Residual risks
- **N5 (accepted residual):** T4 printed an evidence read returning 404 in 0 ms during a hung workflow. It is recorded, not asserted.
- **T3 judgement call:** whether an ill-formed lone surrogate must be rejected is my call; the API spec does not pin it.
- **Cat 12:** the injection never reaches the model through a work-abstract chunk, because free-text items produce only a has-context-document fact. Abstract injection is covered only for what reaches the answer.
- **Spark session:** T18 starts its own local Spark session and depends on sequential forked execution.
- **Runtime:** `sbt check` now takes 445 s, against a baseline of about 432 s.

```text
STATUS: BLOCKED-ON
OWNER: test-engineer
WORK ORDER: TE-P11-02, Phase 11 adversarial testing step 2 of 2 (add and run T1-T18), 2026-11-22
FILES CREATED:
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/services/ApiWorld.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/services/AnswerTextIdLeakAdversarialSuite.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/services/MalformedBodyAdversarialSuite.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/services/WorkflowDependencyFaultSuite.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/services/LogRedactionAdversarialSuite.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/services/IdempotencyReplayAdversarialSuite.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/services/ConfigOmissionAdversarialSuite.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/services/EmptyGraphAdversarialSuite.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/rag/AbstractChunkInjectionAdversarialSuite.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/adversarial/ReorderedAuthorsSuite.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/adversarial/LargeBlockSuite.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/tests/src/test/scala/scholarchain/tests/spark/GraphEdgeCasesSuite.scala
FILES MODIFIED: none outside the files above
COMMANDS RUN: git status --porcelain (x3); git ls-files (several path filters); sbt Test/compile (x2, success); sbt tests/Test/compile and sbt tests/testOnly ... (both refused by guard: tests project not in @projects, nothing executed); sbt test (iteration, exit 1: tests 270/12 failed/1 skipped, all other projects green); sbt check (final, exit 1, 445 s)
TEST RESULTS: final sbt check: core 621/621; entityResolution 464/464; hadoop 85/85; rag 132/132; root 0; services 99/99; sparkAnalytics 263/263; storage 437/437; tests 270 total, 259 passed, 10 failed, 0 errors, 1 skipped. All: 2371 total, 2360 passed, 10 failed, 0 errors, 1 skipped; 205 suites (derived). New tests: 39 total, 29 passed, 10 failed. Failing: T1(a)-(f) [rag-engineer, R1, major for real-LLM]; T12 forged title [rag-engineer, minor]; T2 duplicate keys, T3 lone surrogate, T9 null kind [service-engineer, minor].
VALIDATION RESULTS: scalafmt check passed for all sources including the 12 test files; compile clean under -Werror; markTestRun refused (TestsFailedException), so the marker was not updated; no AWS, network or model call made.
ASSUMPTIONS: duplicate JSON keys and lone surrogates should be invalid-request (no spec text pins this); R1 severity per RG-P11-R1; suite total 205 derived, not printed.
RISKS: see section 6 (N5 observation, T3 judgement, chunk-injection reach, Spark session sharing, about 13 s added runtime).
MANUAL ACTION REQUIRED: human reviews the 10 failures and routes them: R1 (6 tests) and the T12 title-forging finding to rag-engineer; T2, T3 and T9 to service-engineer. The marker stays stale until those repairs land and sbt check passes.
BLOCKED-ON OWNER: human (routing to rag-engineer and service-engineer for the production defects)
BLOCKED-ON EVIDENCE: sbt check: "[error] Failed: Total 270, Failed 10, Errors 0, Passed 259, Skipped 1 ... (markTestRun) sbt.TestsFailedException: Tests unsuccessful". Stop hook: "BLOCKED by .claude/hooks/verify-tests-ran.sh: completion refused for 'test-engineer'. 12 source change(s) are newer than the test marker .claude/.last-test-run". Failing tests listed in section 1.
NEXT GATE: code-reviewer read-only review of the 12 test files; rag-engineer R1 repair (verifier v4) plus the BundleFacts title-scope fix; service-engineer fixes for duplicate keys, lone surrogates and the null kind; then one sbt check with these tests green.
```
