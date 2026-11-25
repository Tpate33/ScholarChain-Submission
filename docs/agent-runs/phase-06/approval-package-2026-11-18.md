# Phase 06 approval-and-commit package (orchestrator, 2026-11-18)

For the human's review. Nothing here ratifies anything or approves AWS deployment, uploads or spending.

## 1. Sequence

1. Human: run the two missing checks (section 4), review section 2, append gate entry A (section 3), run commit A (section 5). Commit A holds reviewed implementation and evidence only; no constitutional file.
2. Orchestrator: routes factory-engineer to apply the ratified FE-P6-06 (with D1 and the CR-P6-30 amendments), then code-reviewer reviews the applied constitutional diff (CR-P6-33). The guard reads the allowlist live, so the new rows are usable from the moment of application; nothing in them reaches AWS until base exists.
3. Human: after CR-P6-33 APPROVE, append gate entry B, run commit B (section 6), quit Claude Code and start a fresh session with the resume prompt (section 7).
4. Fresh session: probes, cleanup.sh checks (if not done in step 1), `sbt sparkSubmitLocal`, digests, then bootstrap plan preparation for the human's Terminal.

## 2. FE-P6-06 with D1: exact scope and trade-offs

Scope (fe-p6-06-proposal.md, CR-P6-30 APPROVE), with D1 and the amendments:

| Change | Who gains what | Trade-off |
|---|---|---|
| `@env = @env-nonprod = dev`; REGION class pinned to `us-east-1` | Rows with `<ENV>`, `<ENVNP>`, TFCHDIR, TFPLAN become matchable (active rows only) | Narrows every read-only AWS row to us-east-1 |
| `sbt sparkSubmitLocal` (build-engineer, spark-engineer; ask) | Local compatibility gate | One HTTPS download from archive.apache.org (sha512-checked) plus Maven Central jars; ~1.4 GB under ignored target/; child code is opaque (same as sparkIT) |
| infra-engineer `terraform -chdir=<stack> init -input=false -backend=false` (ask), `validate` (allow) | Offline validation | Downloads and runs signed provider binaries; writes the lock file in infra territory |
| infra-engineer `scripts/plan.sh dev {bootstrap,base,emr-run}` (ask, committed script) | Plans with the read-only profile; never apply | Agent can read Terraform state and the plan file: the **state-access fix** (CR-P6-30 m1) adds a constitutional sentence allowing state access only through plan.sh, plus `Read(**/tfplan)` and `Read(**/*.tfplan)` denies and a `tfplan` term in the guard's secret-path rule, so the plan file itself cannot be opened by Read. First bootstrap and base plans still need your Terminal (deploy-profile exception refuses inside Claude Code) |
| infra-engineer `run-bounded-spark.sh --dry-run dev <committed config>` (allow), `scripts/emr/check-submit-args.sh` (allow) | No AWS call; prints planned calls and tokens | Tokens are non-secret hashes; agents cannot run `--stage`/`--execute` |
| `bash -n scripts/cleanup.sh`, option-free `shellcheck` naming cleanup.sh | Static checks only | Execution of cleanup.sh stays refused for every identity |
| deploy-engineer `shasum -a 256 <stack>/tfplan`, `terraform -chdir=<stack> show -no-color tfplan` (allow) | Plan checksum and review | Prints non-sensitive plan attributes; `-json` excluded |
| **D1**: `scripts/deploy.sh` row stays inactive (`G6-phase6-human-terminal`); deploy.sh also refuses CLAUDECODE and non-TTY | You run every apply yourself | No agent can apply even with a live `aws login` session |
| Ten `@AWS-RO` rows (EMR list/describe/get, describe-release-label, get-service-quota, describe-instance-type-offerings with one filter, get-ebs-encryption-by-default, bounded list-objects-v2) | Read-only verification with profile `scholarchain-readonly` only | Usable only after base creates R5; R5 is ReadOnlyAccess and can read every object in the account (also via the aws-api MCP) |
| Probes and docs/agents.md rows, plus CR-P6-30 n1 (shellcheck carve-out probe expectation) and n2 (extra edge probes) | Enforcement tests | n3 (extra read rows) not included |

Residual accepted earlier (CR-P6-28): opaque tools running as your OS user could use the admin profile while an `aws login` session is valid; the guard's fence covers direct agent `aws` calls only.

Security change in infra/terraform (accept or reject separately): IAM R1 EMR service role (managed AmazonEMRServicePolicy_v2, PassRole to R2 only); R2 instance role (prefix-scoped S3, deletes only in `output/spark/.spark-*`, explicit no-delete on run=/load=/input/artifacts/config); R3 operator (add-steps, cancel, describe on tagged clusters; terminate only tagged clusters; denies create, modify, scale, PassRole, deletes); R5 read-only (ReadOnlyAccess, assumable only by `scholarchain-admin`); bucket policies (TLS only; deletes only by the admin user); KMS key (account-root key policy); private subnet with S3 gateway and KMS interface endpoint, no internet path; in-transit encryption between nodes off.

Nine version pins (project/versions.properties; BE-P6-09):

```
spark.distribution.url=https://archive.apache.org/dist/spark/spark-3.5.5/spark-3.5.5-bin-hadoop3-scala2.13.tgz
spark.distribution.file=spark-3.5.5-bin-hadoop3-scala2.13.tgz
spark.distribution.sha512=a462656d2a87afcf81f24e4dea9a99e6666ed5c8ff7ba0cc9c936188498c549cf852d0ebb475325761d6cf645d1184d527be9a609503d587bb47d5532b533aa1
spark.distribution.scala-library=2.13.8
spark.distribution.hadoop-client=3.3.4
spark.runtime.scala-library=2.13.16
hadoop-aws.s3a=3.3.4
hadoop-aws.s3a.jar-sha1=a65839fbf1869f81a1632e09f415e586922e4f80
aws-sdk-bundle.s3a=1.12.262
```

Verified from official pages: URL, file name, sha512, hadoop-client 3.3.4, hadoop-aws 3.3.4 and its sha1, SDK bundle 1.12.262. Not verified: `spark.distribution.scala-library=2.13.8` (the gate refuses if the tarball differs); `spark.runtime.scala-library=2.13.16` comes from the scala3-library 3.3.6 POM in the local Coursier cache. Provider `hashicorp/aws = 6.72.0` (infra pin) verified by your init (signed by HashiCorp) and validate.

## 3. Gate entry A (append to docs/agent-runs/phase-06/human-gate.md before commit A)

```markdown
## Phase 6 EMR preparation: implementation acceptance and account setup (2026-11-18)

I reviewed SP-P6-05, SP-P6-06, BE-P6-09 (with repair 01) and IE-P6-02 (with repairs
01-03) and IE-P6-03, and the reviews CR-P6-27 (REQUEST_CHANGES, repaired), CR-P6-28,
CR-P6-29, CR-P6-31 and CR-P6-32 (APPROVE). I accept them for commit as preparation
only. Last agent sbt check: 1570 passed, 0 failed, 0 errored, 0 skipped (marker
2026-11-18T05:23:07Z). I ran locally: terraform init -backend=false and validate for
bootstrap, base and emr-run (hashicorp/aws 6.72.0, signed by HashiCorp; configuration
valid) and scripts/emr/check-submit-args.sh (RESULT PASS). <Add: shellcheck
scripts/cleanup.sh and bash -n scripts/cleanup.sh results, or "not yet run".>

Manual setup outside Terraform (identity and account bootstrap, disclosed):
- Personal AWS paid account <redacted-account-id>; root MFA enabled; no root access keys.
- Budget ScholarChain-monthly, USD 10/month, created in the console.
- IAM user scholarchain-admin created in the console: console password, MFA,
  no access keys, AdministratorAccess and SignInLocalDevelopmentAccess.
  CLI sign-in with aws login (profile scholarchain-login); caller identity
  arn:aws:iam::<redacted-account-id>:user/scholarchain-admin. AWS Organizations and IAM
  Identity Center not enabled.
- Service quota L-1216C47A (us-east-1) increase requested 2026-11-18 13:17 (-06:00),
  request 6878646f637c437d9413cf7d19b0cdd7bm5zIMb4; approved by AWS Support to
  16 vCPUs at 13:26; effective value <rechecked value / not yet rechecked>.
- Read-only checks run: emr-7.14.0 available; m5.xlarge in use1-az6, az2, az1,
  az5, az4.

Decisions: Option A runtime; Region us-east-1; environment dev; AZ use1-az4;
owner tag taufeeq; expiration 2026-11-26; deployment operator is me in a Terminal
(D1); cluster 1 primary + 1 core m5.xlarge unchanged. I confirm the nine version
pins in project/versions.properties (two scala-library values to be confirmed by
sbt sparkSubmitLocal) and hashicorp/aws = 6.72.0.

I accept for planning the security design in infra/terraform (IAM R1, R2, R3 with
tag-scoped emergency terminate, R5 ReadOnlyAccess readable by scholarchain-admin
only, bucket and key policies, private subnet with S3 gateway and KMS endpoints,
in-transit encryption off). This does not approve any apply.

I ratify FE-P6-06 (fe-p6-06-proposal.md) with D1 and the CR-P6-30 amendments m1
(state access only through scripts/plan.sh; tfplan Read denies and secret-path
term), m2 (fixture probes reviewed in the applied diff), n1 and n2, for
application by factory-engineer and review before commit. Deferred nits: CR-P6-29
n4 residuals, CR-P6-30 n3, CR-P6-31 n1 (fixed), CR-P6-32 m2, n1, n2.

Phase 5 remains accepted. Phase 6 remains open. AWS deployment, uploads and
spending remain pending my approval of specific signed plans.
```

## 4. Missing evidence only

| Check | Status |
|---|---|
| `terraform fmt -check -recursive` | Established (IE-P6-03 final run exit 0; no .tf changed since) |
| shellcheck of plan.sh, deploy.sh, lib/common.sh, run-bounded-spark.sh, emr/*.sh | Established (IE-P6-03, exit 0) |
| shellcheck of scripts/cleanup.sh | **Missing** |
| `bash -n scripts/cleanup.sh` | **Missing** |
| init and validate (3 stacks), check-submit-args.sh | Established (your run) |

Optional now in your Terminal (or by infra-engineer after FE-P6-06 is applied):

```sh
shellcheck scripts/cleanup.sh; echo "shellcheck exit $?"
bash -n scripts/cleanup.sh; echo "bash -n exit $?"
```

Quota recheck:

```sh
aws service-quotas get-service-quota --service-code ec2 --quota-code L-1216C47A --region us-east-1 --profile scholarchain-login --query Quota.Value --output text
```

## 5. Commit A (implementation and evidence; no constitutional file)

```sh
cd /Users/taufeeqpatel/Desktop/ScholarChain
chmod +x scripts/plan.sh scripts/deploy.sh scripts/run-bounded-spark.sh scripts/cleanup.sh \
  scripts/emr/install-spark-dist.sh scripts/emr/submit-collaboration-job.sh scripts/emr/check-submit-args.sh
git add build.sbt project/LocalRuns.scala project/SparkSubmitLocal.scala project/versions.properties \
  modules/spark infra/terraform scripts docs/agent-runs/phase-06
git status --short
git diff --cached --name-only -- infra/terraform | grep -c terraform.lock.hcl
git ls-files -s -- scripts
git commit -m "Phase 6: prepare bounded EMR run (packaging, local spark-submit gate, Terraform, run scripts)"
git rev-parse HEAD
```

Expected:
- `git status --short`: every line starts with `M ` or `A ` (staged), nothing with `??` or ` M`, and nothing under `.claude/`, `gold/`, `target/` or `.terraform/`.
- The grep count prints `3` (the three lock files).
- `git ls-files -s -- scripts`: mode `100755` for the seven scripts above; `100644` for `scripts/lib/common.sh` and `scripts/.shellcheckrc`; `scripts/.gitkeep` and `scripts/run-bounded-pipeline.sh` (earlier phase) unchanged.

## 6. Commit B (after factory-engineer applies FE-P6-06 and CR-P6-33 approves)

Gate entry B (append):

```markdown
## Phase 6 FE-P6-06 application (2026-11-18)

I reviewed the applied constitutional diff (factory-engineer FE-P6-06 application,
code-reviewer CR-P6-33 <verdict>) and the validate-agent-files.sh output. I accept it.
It takes effect after this commit and a session restart. AWS deployment, uploads and
spending remain pending my approval.
```

```sh
git add .claude/hooks/guard-dangerous.sh .claude/hooks/bash-allowlist.tsv .claude/hooks/validate-agent-files.sh \
  .claude/hooks/probes.tsv .claude/settings.json docs/agents.md CLAUDE.md docs/agent-runs/phase-06
git status --short
git commit -m "Phase 6: ratify FE-P6-06 allowlist rows for EMR preparation (D1)"
git rev-parse HEAD
```

The exact file list comes from the application report; stage only the files it names. Then quit Claude Code and start a fresh session in the repository.

## 7. Resume prompt (fresh session after commit B)

```
Phase 6 resume after FE-P6-06 ratification, commit <commit B SHA>. Read HEAD and
docs/agent-runs/phase-06/handoff-2026-11-17.md and approval-package-2026-11-18.md.
Confirm HEAD and a clean tree. Have factory-engineer run
.claude/hooks/validate-agent-files.sh --probes and record the result. If not already
recorded, have infra-engineer run shellcheck scripts/cleanup.sh and bash -n
scripts/cleanup.sh. Then have build-engineer run sbt sparkSubmitLocal (first call
downloads, second runs) and record the inventory and verdict; route any failure to
its owner. Then have infra-engineer fill the artifact digests from the committed
scripts and the sparkSubmitLocal inventory and run --dry-run, and prepare the
bootstrap plan, which I will run in my Terminal. Reuse passing evidence for
unchanged code and defer unrelated nits. AWS deployment, uploads and spending
remain pending my approval of each signed plan.
```
