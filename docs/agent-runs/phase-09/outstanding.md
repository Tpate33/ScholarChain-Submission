# Phase 09 outstanding requirements and residuals (orchestrator, 2026-11-21)

Records state only; approves nothing. Phase 9 is accepted (pending the human gate) for LOCAL development scope only.

## Human decisions at closeout
- Marker reviewed: 183 suites, 2232 passed, 1 skipped, git_head=a5cbdab, expected Phase 9 changes. Audit recovery: first `git status --porcelain` refused as UNATTRIBUTED (.claude/.last-test-run), baseline reset; re-run succeeded with only Phase 9 paths.
- CR-P9-03 R1, R2, R3 accepted as documented residuals for the local closeout only. Not fixed.

## Accepted residuals (not fixed)
- R1 (minor): a model-chosen ID-shaped subject token (e.g. `query:<arbitrary token>`) can appear inside a "[PARTIALLY SUPPORTED: ... is not in the evidence]" label. Labelled, never VERIFIED. MUST be reassessed before real-LLM integration and before deployment.
- R2 (minor): invisible or stacked combining marks (Mn, variation selectors) can appear inside quoted, attributed retrieved titles; quote() comment overstates numeral escaping.
- R3 (nit): after a withheld answer the retry record keeps bundleId and "evidenced" step rows; data consistent, comment inexact.
- Deferred earlier: m7 (unresolved same-form mentions not recorded in entity links), n1 (schema bounds duplicated as literals; chars vs bytes), n2 (entityLinks opt-in default None), n3 (injected titles shown quoted and labelled), n4 (labelled claims show rendered text with problems).

## Mandatory outstanding requirements (not waived)
- Real LLM: no production model adapter or access; only the NON-PRODUCTION template synthesizer exists. Paid calls not authorized.
- Real embedding model: semantic retrieval uses the Phase 8 NON-PRODUCTION stand-in embedder; semantic benchmark results verify plumbing only, not retrieval quality.
- Corpus: local corpus is 249 units / 157 entity links; Phase 8 requires >= 10,000 vectors or an instructor-approved smaller corpus (no approval).
- Live AWS: Neptune query templates, DynamoDB tables (incl. EntityLinkCache, QuerySession audit row), S3 Vectors indexes, IAM, Terraform, bounded ingestion, throughput/latency/cost evidence. Phase 7 and Phase 8 live AWS items remain outstanding.
- DynamoDB item-size limit not verified against the 256 KiB audit ceiling (aws-docs MCP unavailable: uvx not on PATH).
- Evaluation: benchmark-query suite runs inside `sbt check` on the rag fixture world and the local corpus only; `sbt benchmarkQueries` alias row inactive (hook change not authorized); no production-scale retrieval or groundedness evaluation.
- Core RuntimeConfig keys for the runtime limits (result count, timeouts, budgets, candidates, audit bytes) not yet defined (domain-engineer); limits are explicit required values.
- Services wiring (Phase 10) must follow changed rag types (WorkflowResult, PersistReport, AuditConfig, abstention reasons).
- Process: full `sbt check` exceeds the 180 s foreground limit, so every agent run triggers an UNATTRIBUTED marker review; tests-project DEBUG logging (~7.7M lines) deferred (build-engineer); gap G7 (no tests-only allowlist shape for test-engineer, factory-engineer).
- Unreferenced history files kept by human decision: prompts v1/v2 and schemas v1/v2, recorded in prompts/SUPERSEDED.md.
