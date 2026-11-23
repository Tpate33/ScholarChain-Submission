# Runbook

## Local use

Follow [the replayable demo](demo-script.md). Stage once after source changes, then
run the wrapper or smoke replay. No AWS/model credentials are needed. Use JDK 17.
To test the repository run `sbt check`; it may take several minutes. Do not run
concurrent builds in the same checkout. Preserve target evidence before cleaning.

| Symptom | Action |
|---|---|
| Demo is not staged | `bash scripts/pipeline-demo.sh --stage` (DBLP-derived) or `bash scripts/local-demo.sh --stage` (synthetic) |
| Address in use | Stop your prior demo or use `--port 0` for an ephemeral port. |
| 401 | Use the documented public demo key; do not supply production credentials. |
| Query abstains | Inspect the reason and evidence; ambiguity is an intentional result. |
| Missing production provider/API-key/model | Production is incomplete; use the explicitly non-production demo for local reproduction. |
| sbt cache permission denied in Codex | Approve the scoped local build cache access; do not alter hooks/markers. |
| Repeated Claude test-marker hook | Preserve the log; stop competing writers; use the documented human-run check recovery. Do not forge a marker. |

## Deployment and rollback

There is no verified runtime deployment in this release state. Do not infer one from
adapter code or successful local checks. Historical reviewed-plan scripts are
`scripts/plan.sh`, `scripts/deploy.sh`, and `scripts/cleanup.sh`; their scope is the
implemented Terraform stacks, not a complete runtime environment. Apply/cleanup
require human approval and a direct interactive Terminal. No safe universal rollback
command is claimed. Inspect the exact reviewed plan and prior release before acting.

## Evidence and cleanup

The smoke replay terminates only its own child and preserves JSON/startup logs in
the requested output directory. An interactive demo stops with Ctrl-C. It creates no
cloud resources. Phase A cleanup was recorded as successful on November 19; base
stack/buckets/logs were intentionally retained. Current live inventory has not been
rechecked. Do not run Phase B or delete retained evidence as part of local cleanup.
For incidents preserve errors, revision, configuration identifiers and logs without
secret contents; obtain a reviewed correction before cloud mutation.
