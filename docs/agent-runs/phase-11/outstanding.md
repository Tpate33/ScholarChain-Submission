# Phase 11 outstanding items (orchestrator, 2026-11-22)

Records state only; approves nothing. Scope delivered: the Phase 11 adversarial tests (README.md:1673-1702) over the LOCAL Phase 10 implementation, the code review, and two owner repairs. Nothing is deployed, packaged or connected to AWS. No commit, push, live AWS or model call was made.

## Gate status (README.md:1740-1746), from the evidence in this directory
- **No critical or major verified finding remains.** CR-P11-02 returned APPROVE; CR-P11-01-1 (major, R1) and -2 to -6 and U1 are closed.
- **Every production behaviour changed in this phase has a relevant test** (CR-P11-02 §5). The 21 required categories each have at least one test (test-report.md §4).
- **All tests pass under their declared profiles.** Final `sbt check` (repair 02): exit 0, suites=207, passed=2388, skipped=1, 0 failed. Reported by service-engineer; the orchestrator did not observe it. Live-AWS (P) tests were not run: no approved profile, and the `storageIT` row is inactive.
- **No reviewer wrote production code.** code-reviewer has no write tools.
- **Repair records identify owners and rerun evidence:** repair-01.md, repair-02.md.

## Pending human decisions at closeout
1. **Test marker audit report (repair 02).** markTestRun's write (suites=207, passed=2388, skipped=1, git_head=a49317a, worktree=dirty) was attributed to a concurrent read-only `git branch --show-current` call. Review the marker and `git status`, or rerun `sbt check` with nothing else running.
2. **CR-P11-01-2 policy.** Fabricated evidence IDs appear in client text only as a count. The orchestrator chose this; the human may overrule it, in which case T1(d) and RepairR1Suite T6 change.
3. **Audit-record schema v2 to v3** (repair 01, new `verifierVersion` field). Accept the contract change.
4. **Idempotency digest change** (repair 02). The digest now hashes an ASCII-escaped rendering, which changes it for non-ASCII questions. Harmless while the store is in memory.
5. **R1 unblocking conditions** (RG-P11-R1 §3): (a) to (d) are now met in local scope. A real answer model still needs paid-call authorization, plus the items below.

## Open findings (not blocking the gate)
| ID | Severity | Item | Owner |
|---|---|---|---|
| CR-P11-02-1 | minor (P) | Explain rejects superseded audit-schema rows (v2) as corrupt (502); this becomes real with durable QuerySession storage plus a schema bump. ReadServices.scala:460 | service-engineer, with rag-engineer; before durable stores |
| CR-P11-02-2 | nit (L) | A model-chosen small count of fabricated IDs appears in client text | rag-engineer |
| CR-P11-02-3 | nit (L) | Misplaced comment, ServiceRuntime.scala:59 | service-engineer |
| CR-P11-01 U2 | unverified | Router echoes unknown query-parameter names in 400 bodies (Router.scala:382-383) | service-engineer |
| CR-P11-01 U3 | unverified (P) | ReadPolicy retries Transient errors with no backoff (Resilience.scala:359) | service-engineer, storage-engineer |
| CR-P11-02 U-a | unverified | `render(escapeUnicode = true)` escapes every non-ASCII unit; rests on the U1 test result | service-engineer |
| T-1, T-2, T-3 | test nits | A never-firing check (AbstractChunkInjection :104-111); T14's answered branch never runs; an assert inside `finally` (GraphEdgeCases :79-81) | test-engineer (optional) |
| Cat 10 runtime | coverage gap | "profile present, topic or institution absent" is not tested at runtime (ingest-level coverage exists) | test-engineer |
| Phase 9 | residuals | Omission or cherry-picking of true facts; R2 (combining marks in quoted titles) | rag-engineer; before a real model |

## (P) items carried into integration (test-report.md §5)
- Neptune adapter client timeouts (storage-engineer) and Terraform (infra-engineer).
- DynamoDB adapter conditional-check mapping, plus durable ownership, job and idempotency stores (storage-engineer, SE-P11-01; design and routing in agent-report.md). T6's stubs can be reused.
- S3 Vectors adapter backoff and limits (storage-engineer).
- Secrets Manager `ApiKeyDigestSource` and the production ServiceProvider (service-engineer); ALB and JVM timeouts and deployed logging (infra-engineer).
- Core `services.*` and `storage.dynamodb.*` config keys (domain-engineer; the proposed keys are in agent-report.md, SE-P11-01 ROUTING ITEMS). These include evidenceScanLimit <= max-query-limit, which closes CR-P10-05 U-c.
- Real embedder and real AnswerModel (rag-engineer, storage-engineer; paid calls need approval).
- EMR-scale runs (hadoop-engineer, spark-engineer, human).
- Launchers or images per service (build-engineer).

## SE-P11-01 (storage integration), paused by the human
Nothing implemented. The design, the routing items and one human decision (a crash-bound TTL for in-flight idempotency records) are in agent-report.md. Two storage tests (TableSpecsSuite, RuntimeStoresTypeSuite) pin the table counts and must be updated deliberately when the work resumes. Reissue after Phase 11 acceptance.

## Process
Every full `sbt check` exceeds the 180 s foreground limit and is backgrounded. Concurrent tool calls during the run cause the marker write to be attributed to them (UNATTRIBUTED, or flagged as forgery), and the Stop hook then refuses until the human reviews the marker. This happened twice in this phase. The durable fix (a supported way to run check, or a faster check) belongs to build-engineer and factory-engineer and needs human authorization for hook changes.
