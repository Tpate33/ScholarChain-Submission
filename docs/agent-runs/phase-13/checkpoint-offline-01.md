# Phase 13 offline checkpoint 01 (orchestrator, 2026-11-24)

Unsigned record for human review. Nothing here ratifies, commits, deploys or approves AWS spending.
Base: HEAD `b1a8e06` (sanitized submission repository, origin Tpate33/ScholarChain-Submission).

## Reconciliation with the old private workspace

Read-only inventory CR-RECON-01: the private workspace tip `e08aa09` is the recorded original source
checkpoint of this repository (docs/publication.md:17). Nothing was transferred; the private repository
was not modified. Codex handoff: private `docs/agent-runs/aws-plan-01/offline-status.md`.

## Work orders and outcomes (agent reports in session; condensed)

| ID | Owner | Outcome |
|---|---|---|
| BE-P13-01 | build-engineer | Java base switched to `azul/zulu-openjdk:17-jre@sha256:f311a4ff90089550a0b9ccfcd049df828943a91920f9edb07fa381cddc19e07e`; BaseJvmProbe fails the build on empty libjvm.so or failed `java -version`; services build `--platform=linux/amd64 --network=none`; `.gitignore` env.local.conf rule. `sbt dockerLocal` built `scholarchain-services:0.1.0` (image e46b912917b0). |
| IE-P13-01 | infra-engineer | New scripts: check.sh, package.sh, smoke-test.sh, archive-evidence.sh, run-experiment.sh. |
| IE-P13-02 | infra-engineer | Account ID and admin ARN moved to ignored env.local.conf (+ .example); internet-facing ALB restricted to human CIDRs (runtime/alb.tf); smoke.conf + smoke-query.json; run-bounded-pipeline.sh shellcheck-clean. |
| IE-P13-03/04 | infra-engineer | Review repairs: check.sh no longer runs sbt (reads the test marker, fails on a changed tree); archive-evidence create/seal safety; smoke plaintext opt-in; teardown reachable without API values. |
| IE-P13-05/06 | infra-engineer | runtime/terraform.tfvars placeholders only; ten identifiers via human TF_VAR_*; plan.sh `require_runtime_inputs`; `@DATA_BUCKET@` token; refusal of ARNs/account IDs in tfvars. |
| FE-P13-01 (+repair 01) | factory-engineer | Five allowlist rows, all `inactive:FE-P13-01-pending-ratification`; proposals (not applied) for `runtime` slot classes and backgrounded `sbt check` attribution. |
| Redaction | orchestrator | Account ID replaced by `<redacted-account-id>` in 12 phase-06 evidence files. `phase-06/human-gate.md` lines 94, 97 left for the human. |

## Observed validation

- `sbt check` (build-engineer, after disk freed): 2,890 tests, 2,889 passed, 0 failed, 0 errors, 1 skipped, 243 suites, 560 s; marker written by markTestRun (git_head b1a8e06, worktree dirty). Earlier attempt failed only on ENOSPC.
- `terraform fmt -check -recursive`: pass. `bash -n` and shellcheck: 17/17 scripts pass.
- `validate-agent-files.sh`: RESULT PASS (139 rows, sha256 ba700bb240a5); `--probes` PASS (factory-engineer).
- Not run by agents: `terraform validate` of runtime (not allowlisted), check-offline.py, any new script, container probes, image architecture inspect.
- Human-reported (Terminal, 2026-11-24/25; not observed by agents): env.local.conf created and git-ignored; runtime `init -backend=false` PASS; runtime `validate` PASS; `check-offline.py` 14/14 PASS; `scholarchain-services:0.1.0` architecture amd64; `chmod +x` on the five new scripts; `phase-06/human-gate.md` account ID redacted (lines 94, 97). The external changes were reported UNATTRIBUTED by the audit and confirmed by the human.
- Public-safety review CR-P13-06: APPROVE (no account ID, keys, tokens, secrets or held-out data in the 42-file change set). Open: home-path prefixes in phase-05/06 evidence and docs/agents.md (pre-existing, non-secret); git history may still contain the account ID (human check before push).

## Historical exposure decision (2026-11-25)

- Human ran history-wide read-only checks in a Terminal. (1) Credential-pattern grep over all commits: matches only in `docs/agent-runs/phase-10/release-scan-cr-rel-01.md` and `docs/agent-runs/publication-01/parquet-audit.scala.txt`. (2) Tracked `.env`, `env.local.conf`, tfstate or tfplan files in any commit: none.
- Review CR-P13-07 (read-only, working tree): release-scan-cr-rel-01.md:29 is a proposed `grep -nE` pattern command; parquet-audit.scala.txt:21 is the `credential-assignment` regex. Both name key identifiers with no value. parquet-audit.json and scripts/ have no matches. Verdict HARMLESS; no rotation required. Limitation: earlier committed versions of those two paths were not read by the reviewer.
- Human decision: the 12-digit AWS account ID remains in pre-redaction public history (Phase 6 evidence, Terraform documentation). It is a non-secret identifier and is knowingly retained; history is not rewritten, to preserve cited commit SHAs and reproducibility. The current tree is redacted.

## Post-commit verification (2026-11-25)

- Checkpoint commit: `cc6c68cd9744da898a84e3f7d28f376eba8a7e3d` (observed by orchestrator: `git rev-parse HEAD`; parent `b1a8e06`).
- Human ran `sbt check` in a Terminal after the commit. Marker observed by orchestrator: written_by `sbt markTestRun`, git_head `cc6c68c…`, worktree clean, 243 suites, 2,889 passed, 0 failed, 0 errored, 1 skipped (2026-11-25T06:34:54Z). The marker change was reported UNATTRIBUTED by the audit (Terminal run) and matches the human's report.
- Human ran `scripts/check.sh` (human-reported, not observed): CHECK-OK; runtime Terraform validate PASS; offline runtime tests 14/14 PASS; script syntax, lint and configuration checks PASS; bootstrap, base and emr-run validate SKIPPED (not initialized).
- Phase 13 gate status: "clean checkout can run all non-cloud validation through the documented script" demonstrated on the committed tree (two-step: `sbt check`, then `scripts/check.sh`). Remaining gates require AWS: reproducible runtime plan with archived checksum, real smoke query, and running the non-destructive scripts in dev; README reproduction instructions (documentation-engineer).

## Reviews

CR-P13-01 REQUEST_CHANGES -> CR-P13-02 APPROVE -> CR-P13-03 APPROVE -> CR-P13-04 REQUEST_CHANGES -> CR-P13-05 APPROVE -> CR-P13-06 (public safety) APPROVE -> CR-P13-07 (history credential-pattern matches) APPROVE, HARMLESS. Open nits accepted: archive/plan hex-strip edge case; run-experiment token replay (no workload yet).
