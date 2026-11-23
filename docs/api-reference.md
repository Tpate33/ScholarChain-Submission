# API route reference

Source of truth: `modules/services/src/main/scala/scholarchain/services/Router.scala`
and the committed API schema resources. Demo base URL: `http://127.0.0.1:8080`.
Bearer key: `scholarchain-local-demo-key-NOT-A-SECRET` (public placeholder).

| Method | Route | Required scope |
|---|---|---|
| GET | /healthz | none |
| GET | /readyz | none |
| GET | /metrics | ops |
| POST | /v1/query | query |
| GET | /v1/jobs/{jobId} | query |
| GET | /v1/jobs/{jobId}/result | query |
| GET | /v1/evidence/{evidenceId} | read |
| GET | /v1/explain/{queryId} | read |
| GET | /v1/graph/nodes/{nodeId} | read |
| GET | /v1/graph/nodes/{nodeId}/neighbors | read |

DBLP-derived POST JSON: `{"question":"Who are the coauthors of Ailing Zeng?","mode":"sync"}`.
The original synthetic profile uses Ada Lovelace instead.
Send Content-Type application/json and an optional Idempotency-Key. See
[demo-script.md](demo-script.md) for replayed commands and [verification.md](verification.md)
for observed behavior. The replay extracts dynamic query/evidence/node IDs instead
of assuming identifiers. Responses expose provenance/evidence and verification
status. Abstention is an explicit outcome, not an invented answer. Every demo
response identifies its profile: `pipeline-demo; DBLP-DERIVED; NON-PRODUCTION`
for the real-data profile, or `local-demo; NON-PRODUCTION` for synthetic data.

The route table describes implemented routes; only the subset named in the replay
has been exercised by the staged-process smoke check. The broader services suite
covers additional behaviors. Production authentication and deployment remain incomplete.
