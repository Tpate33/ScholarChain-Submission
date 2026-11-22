# Phase 09 agent reports (orchestrator assembly, 2026-11-21)

Condensed from each agent's final report; report-contract fields that matter for acceptance are reproduced. The orchestrator did no specialist work.

## RE-P9-01 (rag-engineer): stages 1-5
- STATUS: COMPLETE. BLOCKED-ON: none (follow-ups below).
- FILES CREATED: modules/rag/src/main/scala/scholarchain/rag/agents/{AgentSupport, QueryPlanner, ResearcherResolver, CollaborationMetrics, PathFinder, CommunityAnalyst}.scala; prompts/schemas/query-plan-arguments.v1.schema.json; tests modules/rag/src/test/scala/scholarchain/rag/agents/{AgentWorld, QueryPlannerSuite, ResearcherResolverSuite, CollaborationMetricsSuite, PathFinderSuite, CommunityAnalystSuite}.scala. FILES MODIFIED: none.
- COMMANDS: sbt rag/compile; sbt rag/test x3 (final "Passed: Total 84, Failed 0"); sbt check x2 (first exit 1 at verifyRagSourceImports: literal U+202E in QueryPlannerSuite.scala:146, fixed; second exit 0, 406 s). All sequential.
- TEST RESULTS (final sbt check, as reported): core 621, entityResolution 464, hadoop 85, rag 84, sparkAnalytics 263, storage 419, tests "Total 194, Failed 0, Errors 0, Passed 193, Skipped 1"; root and services 0. markTestRun: git_head=a5cbdab, worktree=dirty, suites=174, passed=2129, skipped=1.
- Allowed plan operations: resolve-researcher, rank-researchers, get-metrics, direct-collaborators, shortest-path, component, community, bridge-researchers, k-core, team-size, temporal-trend, semantic-context, verify-claims (every plan ends with verify-claims). Fixed argument keys: name, researchers, intent, window.source, compare.toWindowStart, compare.toWindowEnd, filter.topic, filter.venue, filter.institution, ranking.rule. Policy version scholarchain.rag.query-planner.v1.
- Graph templates: direct-collaborators.v1, shortest-coauthorship-path.v1 (BFS bounded by depth, visited, graph calls, time); outcomes Found / NotConnectedWithin / Inconclusive. Every hop: both IDs, weight, publication IDs, per-year counts, first/last year, stored-row pointer.
- Identity resolution: EntityLinkCache `surface#<sha256>` lookup (normalization scholarchain.rag.surface-form.v1); more than maxCandidates abstains; expired / other-run / missing-node links excluded and reported; exactly one candidate resolves; institution hint must match a stored affiliation; otherwise abstain with all candidates.
- Assumptions: confidence from stored identity confidence; bridge ranking = cross-community degree (stored order); "strongest repeated collaboration" = joint publication count; default window and max hops from core config; other limits required explicit values (no core config key yet); topic/venue/institution are typed arguments.
- Risks / known failure modes: (1) no loader writes EntityLinkCache, so every name abstains (owner storage-engineer); (2) rule-based planner refuses lower-case / single-word names and phrases like "since 2015"; (3) timeout checked between store calls only; (4) truncation flag set when a query returns exactly its limit; (5) community view needs kv budget >= maxResults + 5; (6) a wrong value identical in graph and kv is not caught; (7) one shortest path, not all; (8) Neptune template support unverified.
- Follow-ups: storage-engineer EntityLinkCache loader (routed as SE-P9-01); domain-engineer core RuntimeConfig keys for the extra limits (deferred).

## SE-P9-01 (storage-engineer), first attempt: EntityLinkCache surface-form loader
- STATUS: BLOCKED-ON human. FILES CREATED/MODIFIED: none. TEST RESULTS: none.
- COMMANDS: `git status --short`, refused by `audit-bash-writes.sh --pre`: UNATTRIBUTED inter-call change to `.claude/.last-test-run` (owner GENERATED). The hook reset the baseline. Incident log: /var/folders/zt/pbpscx0s72b8jch7_wr7gc9m0000gn/T/scholarchain-bash-audit-501/session-f663cb79fdb1a9558b688b3d8d1bf4f4.incidents.log.
- Orchestrator note: the orchestrator's Stop hook had also refused completion for the same marker change (verify-tests-ran.sh R1-b). Probable cause, unverified: RE-P9-01's final `sbt check` was moved to the background by the harness after 180 s, so markTestRun wrote the marker after the audited Bash call ended.
- Read-only findings from the agent: key `TableKeys.EntityLinkCache.partition(sha256(normalize(name)))`; row decoded with `ItemCodecs.EntityLinkCodec`; resolver uses researcherId, matchedBy, candidateRank, sourceRunId (= served analysis run), expiresAtEpochSeconds; one query per name bounded at maxCandidates + 1.
- Next: human review of the marker, then SE-P9-01 rerun in a fresh context.

## SE-P9-01 rerun 1 (storage-engineer): EntityLinkCache surface-form loader
- STATUS: COMPLETE. BLOCKED-ON: none.
- FILES CREATED: modules/storage/src/main/scala/scholarchain/storage/load/EntityLinkLoad.scala; modules/storage/src/test/scala/scholarchain/storage/EntityLinkLoadSuite.scala (12 tests).
- FILES MODIFIED: storage load/LoadLedger.scala (private[load] LedgerSubject, runSubject), load/OpeningLoad.scala (OpeningLoadConfig.entityLinks: Option, default None), model/Records.scala (EntityLinkRecord.evidence, EntityLinkEvidence, EntityLinkSurfaceForm), model/ItemCodecs.scala, model/TableKeys.scala (doc), test OpeningFixture.scala (entityLinkConfig, configWithEntityLinks).
- COMMANDS: git ls-files/log/status/diff; sbt storage/Test/compile; sbt storage/test x2 (final "Passed: Total 431, Failed 0, Errors 0, Passed 431"). `git grep` and `sbt storage/testOnly ...` refused by the guard (no row). No sbt check (human instruction).
- Local corpus: 157 rows, 148 surface forms, 9 ambiguous, 0 empty names, from 286 identified mentions; EntityLinkCache 157 items; analysis table 1302 unchanged.
- Rows: sourceRunId = analysis run; candidateRank by supporting-mention count then researcher ID (display order only); matchedBy er-mention-raw-name; expiry from config checked against the injected clock; own RunLedger row; Conflict on different body or other researcher under a key.
- Risks: opt-in only (default config writes no links, so tests/** exact counts stay 9 ledger rows / 1302 items); normalization duplicated in storage (drift test needed in tests/**); diacritic-free spellings do not match; non-atomic pre-write check; stale expiry refused not replaced.
- Orchestrator decision: keep entity links opt-in (minimum change; no tests/** count churn). Corpus-backed consumers use `OpeningFixture.configWithEntityLinks`.

## RE-P9-02 (rag-engineer): stages 6-10, controls, workflow, benchmark suite
- STATUS: BLOCKED-ON storage-engineer (audit persistence only).
- FILES CREATED: rag agents/{SemanticContextAgent, EvidenceBuilder, BundleFacts, AnswerSynthesis, ClaimVerification, Audit, Workflow}.scala; rag benchmark/Benchmark.scala (BenchmarkRunner, main scope for reuse); prompts/runtime/answer-synthesis.v1.txt; prompts/schemas/{answer-synthesis-output.v1, audit-record.v1, benchmark-set.v1}.schema.json; test resources benchmarks/rag-fixture-world.v1.json; tests WorkflowWorld, WorkflowSuite (16), BenchmarkSuite (5).
- FILES MODIFIED: AgentSupport.scala (StoredRecordRef.recordJson), ResearcherResolver.scala (candidate carries link; normalization unchanged), AgentWorld.scala (test).
- COMMANDS: sbt rag/Test/compile; sbt rag/compile x5 (2 compile errors fixed); sbt rag/test x6 (final "Passed: Total 105, Failed 0, Errors 0, Passed 105"). Refused by guard: git grep variants, git ls-files -s, sbt rag/scalafmtCheck rag/Test/scalafmtCheck. No sbt check (human instruction).
- Benchmark rag-fixture-world.v1 (inside rag/test): graph 11/11 cases, 130/130 checks; semantic 2/2 cases, 26/26 checks (stand-in embedder: plumbing and filters only). Negative control fails wrong values.
- Verdict mapping: VERIFIED->supported, PARTIALLY_SUPPORTED->weakened, UNSUPPORTED->unsupported, CONFLICTING->contradicted (one-to-one; README tags kept in rag Verdict and audit record). UNSUPPORTED removed; PARTIAL and CONFLICTING kept with labels; answer text built only from kept claims plus fixed text.
- Versions: prompt scholarchain.rag.prompt.answer-synthesis.v1; schemas answer-synthesis-output.v1, audit-record.v1, benchmark-set.v1; evidence-builder.v1; claim-verification.v1; workflow.v1; model nonproduction-template-synthesizer.v1 (NON-PRODUCTION).
- Risks: text check covers numbers, IDs, windows, quotes only; labelled claims show original text; retrieved titles shown verbatim (descriptive only); filters not applied to graph retrieval; token budget only when model reports tokens; no production LLM adapter.
- Blocker: QuerySession/QueryStep records lack 14 audit fields; proposed create-once QueryAuditRow (sort key "audit", opaque auditJson + sha256, size bound, session TTL) via QuerySessionWriter.putAudit. Routed as SE-P9-02.

## SE-P9-02 (storage-engineer): query audit row
- STATUS: COMPLETE. BLOCKED-ON: none.
- FILES CREATED: modules/storage/src/test/scala/scholarchain/storage/QueryAuditSuite.scala (6 tests). FILES MODIFIED: storage model/Records.scala (QueryAuditRecord, AuditBounds), model/TableKeys.scala (QuerySession.audit), model/TableSpecs.scala (QS-3), model/ItemCodecs.scala (QueryAuditCodec), port/RuntimeStores.scala (QuerySessionWriter.putAudit).
- COMMANDS: git status/ls-files; `sbt storage/test` once: "Passed: Total 437, Failed 0, Errors 0, Passed 437". No sbt check (human instruction).
- Design: QueryAuditRecord schema scholarchain.storage.query-audit.v1; key pk query#<queryId>, sk audit; opaque JSON-object body, sha256 checked on encode and decode; configured AuditBounds (1..262144 bytes, no default); create-once (identical AlreadyPresent, different Conflict); session TTL.
- API for rag: `QueryAuditRecord.of(queryId, AuditRecord.SchemaVersion, AuditRecord.json(audit), expires)`; `sessions.putAudit(row, AuditBounds.of(cfg))`.
- Risks: 256 KiB ceiling not checked against the DynamoDB item limit (aws-docs MCP down: uvx missing); audit rows expire with the session TTL; recomputed `expires` on retry conflicts (rag must compute once); duplicate JSON keys.

## RE-P9-03 (rag-engineer): audit persistence wired
- STATUS: COMPLETE. BLOCKED-ON: none.
- FILES MODIFIED: rag agents/Audit.scala, agents/Workflow.scala; tests WorkflowWorld.scala, WorkflowSuite.scala (4 new tests); prompts/schemas/audit-record.v1.schema.json (x-notPersisted [], x-persistedAs scholarchain.storage.query-audit.v1; version kept v1).
- COMMANDS: sbt rag/test x2 (first compile error, fixed; second "Passed: Total 109, Failed 0, Errors 0, Passed 109"). Benchmark: graph 11/11 cases, 130/130 checks; semantic 2/2, 26/26. No sbt check (human instruction).
- Behaviour: pre-checks audit row before any write; order bundle, steps, audit, session last; one AuditStamp per query; persistAgain repeats identical rows (AlreadyPresent); AuditConfig.of(ttl, maxAuditJsonBytes) no default. Fail closed: answer withheld (Abstained AuditNotPersisted) if audit cannot be saved; refusals/abstentions keep outcome and report the error. Refused questions get an audit row but no session row. Stored audit JSON holds neither question text nor name.
- Risks: changed public types (WorkflowResult.auditWrite, PersistReport fields, AuditConfig.of, audit-not-persisted tag) for services; re-asking under one queryId conflicts by design; 262144 ceiling unchecked against DynamoDB.

## Human instruction recorded by the orchestrator (2026-11-21; the human's own record belongs in human-gate.md)
- Human reviewed the marker (174 suites, 2129 passed, 1 skipped, git_head=a5cbdab) and git status; changed paths match expected Phase 9 work.
- Audit recovery: orchestrator `git status --porcelain` ran without refusal (baseline already reset by SE-P9-01's refused call); output listed only docs/agent-runs/phase-09/, modules/rag/.../agents/ (main and test), prompts/schemas/query-plan-arguments.v1.schema.json, all untracked.
- Tests-project DEBUG logging fix deferred (optional). Focused module tests during implementation; one required final full `sbt check` after all edits.
