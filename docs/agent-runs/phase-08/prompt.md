# Phase 08 work order (human, 2026-11-21), verbatim

Messages from the human starting Phase 8 in the continuing orchestrator session (the orchestrator noted that CLAUDE.md asks for one fresh session per phase and offered a fresh-session prompt; the human chose to continue here).

> I committed Phase 7 local closeout as a62f85b: "Phase 7: local storage closeout; live AWS outstanding". Only docs/agent-runs/phase-07/commit.txt remains untracked; it records that commit. Leave it intact.
>
> Proceed to Phase 8 using the handoff and project specification. Prioritize the smallest working implementation needed to finish the project. Defer cosmetic improvements and optional tooling. Reuse valid Phase 7 evidence and fix only concrete blockers.
>
> Keep outstanding AWS work explicit. No paid API calls, cloud operations, deployment, deletions, automatic commits, or hook changes are authorized. Identify any model/access decision needed before dependent work while continuing independent local work.

> The hash record is now committed as ef2d7df. commit.txt correctly points to the implementation commit a62f85b. git status --short is empty.
>
> Continue the authorized minimum Phase 8 work. Do not reopen Phase 7 cosmetic work.

## Specification
README.md Phase 8 (L1436-1482): storage-engineer and rag-engineer in separate work orders; bounded embedding work queue (SQS or manifest-driven batch); S3 Vectors write batches; authoritative text in ordinary S3; compact metadata (vector key, researcher/community ID, window, topic/institution IDs, document type, source dataset, model version, text hash, run ID); skip unchanged text by content hash and embedding model version; failures and retry counts in RunLedger; rag: deterministic rendering templates, chunking policy, embedding model versioning, retrieval smoke tests with known neighbours, compact semantic-context format; five indexes; local contract tests and a bounded AWS ingestion of ≥10,000 vectors or an instructor-approved smaller corpus; report throughput, rejected records, duplicate handling, query latency samples, index counts, metadata schema, model version. Gate: same key never silently refers to different text; model version stored; source text fetchable via S3 pointer; retrieval smoke tests pass; deterministic facts from graph/metric stores; cost and object counts reported.

## Open decisions (human)
- Production embedding model and access (paid; not authorized).
- Local stand-in embedder for retrieval smoke tests (free, deterministic, labelled non-production) — recommended, not yet approved.
- Corpus size (249 units locally vs ≥10,000 required; no instructor approval).
- Queue design: manifest-driven locally (recommended); SQS is an AWS item.
