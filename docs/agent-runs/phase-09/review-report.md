# Phase 09 review report (orchestrator assembly, 2026-11-21)

code-reviewer verdicts and verified findings, condensed from the reviewer's final report.

## CR-P9-03: APPROVE (fresh review after repair 02; read-only, tests not re-run; relied on TE-P9-04: exit 0, 183 suites, 2232 passed, 1 skipped)

Open, non-blocking (rag-engineer):
| ID | Sev | Location | Failure input | Impact |
|---|---|---|---|---|
| R1 | minor | ClaimVerification.scala:171, 184, 192-198, 222, 238, 286-288; AnswerSynthesis.scala:167-180 | model assertion with subject `query:Ada-Lovelace-has-900-coauthors-and-is-the-top-bridge@2018-2022` (any valid StableId token) | model-chosen token text appears inside a "[PARTIALLY SUPPORTED: ... is not in the evidence]" label; labelled, never verified; suggested fix: show only subjects present in bundle facts, else a fixed placeholder |
| R2 | minor | ClaimVerification.scala:445-449, 457-462 | retrieved title with variation selectors, U+034F, overlay or stacked combining marks (Mn) | invisible or spoofed text inside quoted, attributed titles only; quote() comment overstates numeral escaping (CJK numerals shown) |
| R3 | nit | Workflow.scala:663-666, 727-729, 758-771 | audit put fails, answer withheld | withheld record keeps bundleId and "evidenced" step rows; data consistent; comment inexact |

CR-P9-02 closure: F1 CLOSED (residuals R1, R2), F2 CLOSED, F3 CLOSED, F4 CLOSED, F5 CLOSED.
Verified OK: v1/v2 files present, recorded in prompts/SUPERSEDED.md, unreferenced; v3 prompt pinned; earlier closures (M1, M3, M4, m1, m3, m5, m6) hold; gate items hold; module boundaries; no AWS/network/LLM client; NON-PRODUCTION labels; tests non-vacuous.
Unverified: sbt check not re-run by the reviewer; untracked v1/v2 files not diffable; storage not re-reviewed in depth (unchanged by repair 02); real Neptune/DynamoDB/LLM behaviour.
Still deferred by the human, not claimed fixed: m7, n1-n4, sbt benchmarkQueries alias, tests DEBUG logging.

## CR-P9-02: REQUEST_CHANGES (fresh review after repair 01; read-only, tests not re-run)

| ID | Sev | Location | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| F1 | major | rag agents/ClaimVerification.scala:135, 451-459, 482-485, 397-407, 428-442, 293-294, 137, 97-112, 218-222 | (a) "In W, ada is not the researcher with publication-count 2." with a supported publication-count and a has-context-document assertion ("not" licensed by the latter); (b) a supported title assertion licenses every title word (names, superlatives, injected text) unquoted and unlabelled; (c) non-ASCII numerals (superscript, circled, Roman) and enclosed letters escape the checks | VERIFIED claim prose that contradicts or exceeds its evidence; M2 not closed; prompt-injection route | rag-engineer |
| F2 | minor | Workflow.scala:716-725, 730-731, 105-111 | audit put fails (answer withheld), then persistAgain succeeds | stored audit says "answered" for an answer never delivered | rag-engineer |
| F3 | minor | Audit.scala:106-108; QueryPlanner.scala:633; Workflow.scala:341 | institution hint used for resolution | audit filter says applied=false although it decided identity | rag-engineer |
| F4 | nit | AnswerSynthesis.scala:20 | - | doc names a non-existent AnswerSynthesisSuite (pin is in WorkflowSuite) | rag-engineer |
| F5 | nit | BundleFacts.scala:34 | - | doc says schema v1; now v2 | rag-engineer |

CR-P9-01 closure: M1 CLOSED; M2 NOT CLOSED (F1); M3, M4, m1, m2, m3, m6 CLOSED; m4 CLOSED for single-call combinations (residual F2); m5 CLOSED against the human decision (accuracy defect F3). m7, n1-n4 deferred and not claimed fixed.
Verified OK: version pins (prompt v2, output schema v2, audit-record.v2, claim-verification.v2, claim-wording.v1, rag-path-traversal.v2); storage opaque audit row accepts v2; M1 flag survives every hand-off; gate items otherwise hold; module boundaries; no AWS/network/LLM; NON-PRODUCTION labels. Unreferenced v1 files: answer-synthesis.v1.txt, answer-synthesis-output.v1.schema.json, audit-record.v1.schema.json (note, not a finding).

## CR-P9-01: REQUEST_CHANGES (whole uncommitted Phase 9 change vs a5cbdab; read-only, no tests re-run)

| ID | Sev | Location | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| M1 | major | rag agents/PathFinder.scala:338, 353-364; EvidenceBuilder.scala:446-457; AnswerSynthesis.scala:643-647; PathFinderSuite.scala:104-108 | maxResults=2; S has strong edges to A, B and a weak direct edge to T; A-T edge | Found [S,A,T] (2 hops) VERIFIED while the true shortest path is 1 hop; cut flag ignored on a hit and not kept in evidence | rag-engineer |
| M2 | major | ClaimVerification.scala:109-126, 196-206 | claim text "Alice Smith and Bob Jones are in the same community and Alice is the strongest bridge…" with one valid assertion | VERIFIED and unlabelled; claim text not tied to its assertions (names, superlatives unchecked) | rag-engineer |
| M3 | major | ClaimVerification.scala:236-239; Workflow.scala:698; AnswerSynthesis.scala:189 | missingEvidence "the evidence shows Alice Smith is the leading collaborator in Europe" | model free text reaches the answer unverified | rag-engineer |
| M4 | major | ResearcherResolver.scala:176-180; ResearcherResolverSuite.scala:109 | name with > maxCandidates links | abstains with zero candidates; README stage 2 "Return multiple candidates" | rag-engineer |
| m1 | minor | Workflow.scala:473, 626-633 | 3 malformed outputs, maxAttempts=3 | audit records synthesisAttempts=0, null prompt/model versions, nonProductionModel=false | rag-engineer |
| m2 | minor | ClaimVerification.scala:121, 236-239; SemanticContextAgent.scala:148-151 | workflow gap "index researcher-profile-v1 is not configured" | correct missing-evidence lines dropped (Number regex has no word boundary) | rag-engineer |
| m3 | minor | Workflow.scala:622, 655; Audit.scala:233; GateAdversarialSuite:951-954 | evidence built, then abstain | audit/session bundleId points at a bundle never written | rag-engineer |
| m4 | minor | Audit.scala:218-241; Workflow.scala:661-670 | putAudit ok, putSession fails | stored audit says "answered" for a withheld answer | rag-engineer |
| m5 | minor | Audit.scala:36-63; audit-record.v1.schema.json:5 | any question | only plan hash and operation tags stored; README "Persist QueryPlan" | rag-engineer (human decision) |
| m6 | minor | rag benchmark/Benchmark.scala:210-279, 291-295 | cases without semantic expectation | checks pass by default; "26/26", "18/18" semantic counts overstate coverage | rag-engineer (runner); test-engineer (wording) |
| m7 | minor | storage load/EntityLinkLoad.scala:185-188, 214; ResearcherResolver.scala:229-231 | form with 2 mentions to X and 8 Unresolved | resolves to X with undecided mentions hidden | storage-engineer, then rag-engineer |
| n1 | nit | AnswerSynthesis.scala:189-246 | - | schema bounds duplicated as literals; chars vs bytes | rag-engineer |
| n2 | nit | storage OpeningLoad.scala:116 | - | entityLinks default None (fails closed) | storage-engineer |
| n3 | nit | AnswerSynthesis.scala:674-680 | - | injected titles verbatim, quoted and labelled (acceptable) | rag-engineer |
| n4 | nit | ClaimVerification.scala:218-231 | - | labelled claims keep fabricated text (gate allows labelling) | rag-engineer |

Verified OK (summary): ownership; deterministic planner with allowlist; closed parameterized graph templates; ambiguity abstains with all candidates for MultipleCandidates; metrics only from stored rows; bounded bundle with deterministic/semantic separation; versioned prompt and schemas pinned by tests; bounded retry; no chain-of-thought stored; no hardcoded limits besides n1/n2 and the AuditBounds ceiling; rag main imports only core, storage.model, storage.port; no network/AWS/LLM call; NON-PRODUCTION labels present; EntityLinkLoad and QueryAuditRecord create-once with conflict detection; drift suite present.

Unverified: sbt check not re-run by the reviewer; Neptune/DynamoDB behaviour; 256 KiB ceiling vs DynamoDB item limit; DirectCollaborators strongest-first order (assumed from PathFinder.scala:198).
