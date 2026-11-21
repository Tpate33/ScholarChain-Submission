# Phase 08 review report (orchestrator assembly, 2026-11-21)

code-reviewer verdicts and verified findings, condensed from the reviewer's final reports. Each finding lists severity, location, failure input, owner and status.

## Storage (SE-P8-01, SE-P8-02, SE-P8-03; TE-P8-01, TE-P8-03)
| Review | Verdict | Findings |
|---|---|---|
| CR-P8-01 | REQUEST_CHANGES | M1 major: per-item failures were not recorded in RunLedger on early exits, and a deterministic Permanent batch embed error blocked the load with no record (VectorLoad.scala). M2 major: one index could hold several models' vectors, and queries could not select a model (Vectors.scala L49, VectorStore.scala). m1: Phase 7 unscoped rows were silently ignored. m2: manifest documentation overstated ("queue"). n1: the unscoped rejection-set prefix also matches scoped sets. TE-P8-01 test changes: no findings. |
| CR-P8-02 | APPROVE | M1, M2, m1 and m2 closed. New: m-A minor, a partial rejection set carried the "complete" marker. m-B minor, the unnamed default reader could silently return another model's vectors. n-A nit, a split batch dropped an already-found rejection. n-B nit, leftover "queue" wording. |
| CR-P8-03 | APPROVE | m-B, m-A, n-A and n-B closed. Treating maxRejected bound exits as incomplete was judged correct. Open, non-blocking (storage-engineer): (1) minor, RL-8/RL-7 and LoadEntry/TableKeys docs still describe "highest generation" instead of "highest complete generation" (TableSpecs.scala L147-159, RunLedgerStates.scala L79-81, TableKeys.scala L103). (2) nit, on the two maxRejected bound exits a recording error replaces the original error (VectorLoad.scala L1238, L1358). n1 still open. |

## Rag (RE-P8-01, RE-P8-02)
| Review | Verdict | Findings |
|---|---|---|
| CR-P8-04 | REQUEST_CHANGES | 1 major: templated document types fell open to free text, so excerpts could carry raw counts and IDs. 2: maxContextBytes was not a real bound. 3: lexical denylist for withholding. 4: window unvalidated. 5: fail-closed paths untested. 6: rag imported storage.load.ContextText (module boundary). 7, 8: nits. |
| CR-P8-05 | APPROVE | Findings 1-6 closed. Open, non-blocking (rag-engineer): m1 minor, the stable-ID guard on allowlisted values is bypassed by `dblp/researcher:...` or `id:community:...` (SemanticContext.scala L449-452; defence in depth only). n1 nit, `private[semantic] retrieveFrom` is reachable from same-package production code. Unverified: DEL/C1 control characters in a curated title could make rag refuse a storage-valid document (fails closed). Nits 7 and 8 deferred. |

## Corpus smoke suite (TE-P8-04)
| Review | Verdict | Findings |
|---|---|---|
| CR-P8-06 | APPROVE | Open, non-blocking (test-engineer): m1, the assume condition and per-case coverage counts are not asserted (a coverage loss could become a silent skip). m2, the allowlisted-value check is weaker than rag's rule for other ID prefixes. m3, per-document-type counts are printed, not asserted. m4, test names and printed lines lack a "non-production plumbing" qualifier (this evidence carries the qualifier instead). n1, n2 nits. Confirmed: expectations come from the source data, not embedder output; the skip reflects a real data gap; mutation wrappers are read-only; no AWS or paid calls. |

All Phase 8 work is in modules/storage/**, modules/rag/**, prompts/**, tests/** and docs/agent-runs/phase-08/** (its owners). No AWS, network or paid-model call was found in any reviewed code.
