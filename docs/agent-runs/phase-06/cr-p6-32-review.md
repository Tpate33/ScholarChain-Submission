# Phase 06 CR-P6-32: code-reviewer focused review of IE-P6-03 (2026-11-18)

## Verdict: APPROVE

- env.conf (:39-40 etc.) matches the human inputs; no secret.
- deploy.sh:93-107 quota gate: read-only, emr-run only (STACK bound to plan checksum and meta), readonly threshold 8, fails closed (exit 4 call/value, exit 3 below 8).
- cleanup.sh `allow-expired`: safe, keep. Positional only (common.sh:123-150); plan.sh:35, deploy.sh:54, run-bounded-spark.sh:109 call without it; only cleanup.sh:54 passes it; other cleanup gates unchanged.
- Provider `= 6.72.0` at bootstrap/main.tf:15, base/versions.tf:21, emr-run/main.tf:30; module `>= 6.0.0, < 7.0.0`; no resource sets `region`; no lock files yet (tf_init refuses until committed).
- shellcheck directives hide no current defect (every `$C_*`/`$ENV_*` is a real key; every SC2015 instance ends in `die`); `\~/*` equivalent to `"~/"*`. No new defect.

## Findings

- m1 (minor, ORCHESTRATOR): owner and expiration provenance missing; prompt-account-checks.md:27 contradicted env.conf. **Fixed by the orchestrator**: prompt-setup-choices.md archives the human message verbatim; prompt-account-checks.md annotated.
- m2 (minor, infra-engineer): file-wide SC2154/SC2034 disables (run-bounded-spark.sh:2, common.sh:2) would mask future variable typos. Deferred (no typo exists; set -u fails closed at runtime).
- n1 (nit, infra-engineer): deploy.sh:102 non-JSON quota response exits with jq's status, not 4 (still fails closed). Deferred.
- n2 (nit, infra-engineer): file-level SC2015 disables in emr scripts broader than needed. Deferred.

## Unverified

aws:PrincipalArn under `aws login`; get-service-quota response for a never-changed quota (fails closed); KMS endpoint in use1-az4; 6.72.0 behaviour until init and validate.
