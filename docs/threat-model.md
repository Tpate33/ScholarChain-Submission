# Threat model

Assets include source/provenance records, credentials, query records, evidence,
resource budgets, and the accuracy of acceptance/test reports. Boundaries include
untrusted HTTP input, retrieved data, model output, storage adapters, and human cloud
approval. See source tests and `docs/agent-runs/integration-01/outstanding.md` for
known implementation limitations; this document is not a security certification.

| Threat | Implemented mitigation / verification boundary |
|---|---|
| Prompt injection or poisoned retrieved text | Retrieved text is data; structured evidence and deterministic claim verification constrain answers. Local template demo does not test a live model. |
| Query injection | Typed parameters and allowlisted graph templates; adversarial tests cover invalid input. Live Neptune behavior remains unverified. |
| Credential disclosure | Demo key is public and explicitly non-production; no AWS secrets needed. Production Secrets Manager integration remains incomplete. Never commit credential files or state. |
| Denial of service | Request/body/queue/graph/result bounds and timeouts exist; full protection and S3 streaming cancellation are not established. |
| Broken authorization | Authenticated query/read/ops scopes, missing-key rejection, query ownership and idempotency tests. Demo is single replica only. |
| Provenance tampering | Part checksums, schema validation, stable IDs and evidence pointers. Checksums do not by themselves prove source truth. |
| Destructive cloud actions | Reviewed plans and human execution gates; no cloud mutation by this Codex completion. |
| False evidence of completion | Logs and observed exit status; skipped tests reported; no manual marker editing; explicit limitations and outstanding requirements. |

Loopback binding prevents ordinary remote access to the demo but does not isolate it
from other local processes. Do not use its public key or in-memory state for real
users. Production TLS, IAM, network controls, deployment and live verification are
not supplied by this local demo.
