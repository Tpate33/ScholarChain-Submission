# Phase 06 FE-P6-06: factory-engineer proposal, allowlist rows and guard changes for Option A (2026-11-17)

- PROPOSAL ONLY. No file edited (the allowlist is read live by the guard). Takes effect only after code review, human ratification, a factory-engineer application work order and a session restart.
- Commands: `wc -l …` refused (shell-syntax-gate, >400 bytes); `.claude/hooks/validate-agent-files.sh` exit 0 "RESULT: PASS (0 failures)" (95 identity rows, 3 groups; docs block matches tsv, 120 rows, sha256 71034e28f6b0); `python3 -I -m json.tool` on settings.json and .mcp.json exit 0; `git status --porcelain` (no constitutional path changed); `git ls-files -- scripts infra .github` (3 tracked); `git ls-files --others --exclude-standard -- scripts infra/terraform` (23 untracked). shellcheck not installed. Nothing in the proposal validated or observed; verdicts are from reading guard-dangerous.sh.
- Assumptions: environment `dev`; Region us-east-1 **pending human confirmation**; CLAUDECODE set and no TTY in agent Bash (unobserved); stub scripts smoke-test.sh, rollback.sh, run-experiment.sh, package.sh, archive-evidence.sh absent; `terraform show` of a saved plan needs no backend credentials (unverified; fails closed).

Row notation: IDENTITY | STATUS | MODE | PROV | SPEC | JUSTIFICATION (one TAB between fields in the tsv).

## A. guard-dangerous.sh changes (needed because the option-set grammar only accepts `-` alternatives, TFCHDIR/TFPLAN do not match the L2 stacks, AWSID rejects `=` and `,`)

A1 slot classes in RX:
```
-RX = {'INT': ..., 'REGION': r'[a-z]{2}-[a-z]+-[0-9]', ...
-      'OUT': r'json|text', 'RO': r'scholarchain-readonly'}
+RX = {'INT': ..., 'REGION': r'us-east-1', ...
+      'OUT': r'json|text', 'RO': r'scholarchain-readonly', 'STACK': r'bootstrap|base|emr-run',
+      'EC2FILTER': r'Name=instance-type,Values=[a-z][a-z0-9-]*\.[a-z0-9]+',
+      'S3PREFIX': r'(?!.*//)[A-Za-z0-9][A-Za-z0-9._=/-]{0,255}'}
```
REGION exactly us-east-1 (narrows every @AWS-RO row; a different Region is a one-literal amendment); STACK bootstrap|base|emr-run; EC2FILTER exactly one `Name=instance-type,Values=<one type>` (no `:`, no file:// or JSON); S3PREFIX letters, digits, `. _ = / -`, ≤256, no `//`, no `:`.

A2 TFCHDIR and TFPLAN in cls_ok:
```
-    env = DIRS.get('@env', ())
-    if c == 'TFCHDIR':
-        return t.startswith('-chdir=infra/terraform/environments/') and t.split('/')[-1] in env and t.count('/') == 3
-    if c == 'TFPLAN':
-        m = re.fullmatch(r'infra/terraform/environments/([a-z0-9-]+)/tfplan', t)
-        return m is not None and m.group(1) in env
+    env = DIRS.get('@env', ())
+    def stack(d):    # FE-P6-06: one Terraform root of layout L2; nothing matches while @env is empty
+        m = re.fullmatch(r'infra/terraform/(bootstrap|environments/([a-z0-9-]+)/(base|emr-run))', d)
+        return bool(env) and m is not None and (m.group(2) is None or m.group(2) in env)
+    if c == 'TFCHDIR':
+        return t.startswith('-chdir=') and stack(t[len('-chdir='):])
+    if c == 'TFPLAN':
+        return t.endswith('/tfplan') and stack(t[:-len('/tfplan')])
```
TFPLAN stays PATHLIKE (path-escape check applies).

A3 cleanup carve-out in tripwire():
```
+def cleanup_static_check(low):
+    # FE-P6-06: bash -n and shellcheck read scripts/cleanup.sh without executing it; the matched allowlist row
+    # already fixed the argument classes. Anything else naming cleanup.sh stays refused (cleanup-human-only).
+    if low[0] == 'bash':
+        return low[1:] == ['-n', 'scripts/cleanup.sh']
+    return low[0] == 'shellcheck' and len(low) > 1 and not any(t.startswith('-') for t in low[1:])
 ...
-    if any('cleanup.sh' in t for t in low):
+    if any('cleanup.sh' in t for t in low) and not cleanup_static_check(low):
```
Reuses existing rows `bash -n <PATH:scripts/>` and `shellcheck <PATH:scripts/>+`. Residual: inherited `BASH_ENV` for `bash -n` (pre-existing).

## B. bash-allowlist.tsv

- B0: classes comment adds STACK EC2FILTER S3PREFIX; directives `@env<TAB>dev`, `@env-nonprod<TAB>dev`; inactive rows still never match.
- B1: `build-engineer | active | ask | - | sbt sparkSubmitLocal | <J1>` and `spark-engineer | active | ask | - | sbt sparkSubmitLocal | <J1>`. J1 = "Phase 6 local spark-submit compatibility gate (amendment FE-P6-06; BE-P6-09; Option A local runtime validation): root task, no arguments; refuses unless the code scope build.sbt project modules/core modules/spark is committed and byte-identical to HEAD; on a cache miss one HTTPS GET of the sha512-pinned Spark distribution into target/spark-dist/ (that invocation stops, never a pass) plus Coursier jar resolution; two client-mode runs (local[2], local-cluster[2,1,1024]) on loopback in a create-once target/spark-submit-local/attempt-<n>/; child environment strips AWS_*, SCHOLARCHAIN_*, GIT_*, SPARK_*, HADOOP_* and JVM-option variables; no AWS call; writes no test marker; never part of check; child code opaque". No tripwire collision (no `clean`, no held-out substring, no marker token). Residuals: network and opaque child code (ask); `sbt clean check` forces re-download; ~600 s (run in background); CR-P6-27 m2, m3, n3 must be fixed before its evidence is accepted.
- B3 infra-engineer:
  - line 132 -> `infra-engineer | active | ask | - | terraform <TFCHDIR> init -input=false -backend=false | FE-P6-06 (B6 closed: .gitignore covers .terraform/ and tfplan): provider download from the Terraform Registry; no backend, no state; writes <stack>/.terraform/ (ignored) and <stack>/.terraform.lock.hcl (infra territory; the human commits it); -upgrade, -from-module, -plugin-dir and -backend-config excluded by construction` (residual: provider binaries; ~/.terraformrc or TF_CLI_CONFIG_FILE could redirect).
  - line 133 -> `infra-engineer | active | allow | - | terraform <TFCHDIR> validate ?-no-color | FE-P6-06: offline validation of one stack after init; no credentials; writes nothing`
  - line 134 -> `infra-engineer | inactive:superseded-by-plan-sh | ask | - | terraform <TFCHDIR> plan -input=false -out=tfplan | E4; superseded by scripts/plan.sh (profile selection, clean tree, plan meta); never activated`
  - line 138 -> `infra-engineer | active | ask | git | scripts/plan.sh <ENVNP> <STACK> | FE-P6-06 (replaces the Phase 12 placeholder): plan of one stack, never apply; read-only profile from the committed env.conf (the deploy-profile first-plan exception refuses inside Claude Code and without a TTY); terraform init with the real backend and -lockfile=readonly, validate, plan -lock=false -out=tfplan; writes only <stack>/tfplan and <stack>/.terraform/ (ignored); AWS read calls only; inert until base creates the read-only role` (residuals: reads state, prints non-sensitive attributes; plan runs data sources: review must confirm no `external`/`http`; env.conf aws_readonly_profile only checked against the deploy profile name: narrowing suggested for infra-engineer; first bootstrap and base plans human-Terminal only).
  - new `infra-engineer | active | allow | git+args | scripts/run-bounded-spark.sh --dry-run <ENVNP> <PATH:infra/terraform/environments/> | FE-P6-06: dry run only (no AWS call, no repository write; temporary directory under TMPDIR removed on exit); run config committed and unmodified; hashes local artifacts and the input archive under the home directory; prints planned calls, bounds and the two confirmation tokens; --stage and --execute are human-Terminal only` (git+args is narrower than requested; tokens printed are deterministic non-secret hashes, unusable by agents).
  - new `infra-engineer | active | allow | git | scripts/emr/check-submit-args.sh | FE-P6-06: local self-check of the EMR step wrapper's argument construction (CR-P6-27 M1); no AWS, HDFS or network call; writes nothing; needs the committed executable bit`
  - Scanner: no row (B5 open; checkov not installed). Draft for later: `infra-engineer | inactive:B5-checkov-not-installed | allow | - | checkov --directory infra/terraform --framework terraform --compact --quiet --skip-download | security scan` (flags unverified).
- B4 deploy-engineer: line 151 -> `deploy-engineer | active | allow | - | shasum -a 256 <TFPLAN> | plan checksum gate (FE-P6-06): bootstrap, base and emr-run plan files`; line 152 -> `deploy-engineer | active | allow | - | terraform <TFCHDIR> show -no-color tfplan | plan review (FE-P6-06); -json excluded (prints sensitive values)`. deploy.sh: finding: no CLAUDECODE or TTY refusal; with a valid admin `aws login` session it runs non-interactively. **Recommended D1**: line 146 -> `deploy-engineer | inactive:G6-phase6-human-terminal | ask | git | scripts/deploy.sh <ENV> <SHA40> <SHA256> | /deploy; Phase 6 (FE-P6-06): the human runs deploy.sh in a Terminal with the admin aws login session; activation is a later amendment` (plus a CLAUDECODE/TTY refusal in deploy.sh by infra-engineer). Alternative D2: activate (widening). Lines 147, 148, 156, 158, 159 -> `inactive:script-absent`.
- B5 ten new `@AWS-RO | active | allow | - |` rows, each prefixed `{!--profile:<RO>;--region:<REGION>;--output:<OUT>`: emr list-clusters `--active`; list-bootstrap-actions, list-instance-groups, get-auto-termination-policy `!--cluster-id:<AWSID>`; describe-step `!--cluster-id:<AWSID>;!--step-id:<AWSID>`; describe-release-label `!--release-label:<AWSID>`; service-quotas get-service-quota `!--service-code:<AWSID>;!--quota-code:<AWSID>`; ec2 describe-instance-type-offerings `--location-type:<AWSID>;!--filters:<EC2FILTER>`; ec2 get-ebs-encryption-by-default; s3api list-objects-v2 `!--bucket:<AWSID>;--prefix:<S3PREFIX>;!--max-items:<INT>` (no get-object, delimiter or starting-token). All within 400 bytes and 24 tokens; none dropped; a prefix starting with a verb plus hyphen trips aws-destructive-verb (fails closed). Effective only after base creates R5.

## C. settings, MCP, probes

- C1 settings.json: no change required. Optional deny rules: `Bash(scripts/run-bounded-spark.sh --stage*)`, `Bash(./scripts/run-bounded-spark.sh --stage*)`, `Bash(scripts/run-bounded-spark.sh --execute*)`, `Bash(./scripts/run-bounded-spark.sh --execute*)`, `Bash(*SCHOLARCHAIN_CONFIRM_SPARK_RUN*)`, `Bash(*SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE*)` (multi-wildcard semantics unverified).
- C2 aws-api MCP: SCHOLARCHAIN_READONLY_AWS_PROFILE = scholarchain-readonly and AWS_REGION us-east-1 already the defaults; no .mcp.json change. Residual: under R5 the MCP can get-object every object (explicit human acceptance).
- C3 probes (probes.tsv): ASK build-engineer and spark-engineer `sbt sparkSubmitLocal`; BLOCK other identities and ORCHESTRATOR (code-reviewer reviewer-read-only); BLOCK variants with clean, check, sparkIT, project prefix, -D, lower case (no-allowlist-entry) and with held-out task names (heldout-eval-human-only, heldout-pipeline-human-only). Region ALLOW us-east-1, BLOCK us-west-2 and profile scholarchain-admin. Terraform ASK init for the three stacks; ALLOW validate; BLOCK wrong dirs, prod, missing -backend=false, -upgrade, direct plan, `../x`, apply, state list, deploy-engineer validate. plan.sh, run-bounded-spark.sh, check-submit-args.sh refused shapes (wrong arity, `all`, prod, `./`, other identities, --stage, --execute, env-prefixed, datasets path). Cleanup carve-out ALLOW `bash -n scripts/cleanup.sh`, `shellcheck scripts/cleanup.sh scripts/plan.sh`; BLOCK `.bak` (cleanup-human-only), execution forms, extra args, options, deploy-engineer. deploy-engineer ALLOW shasum and show for the three stacks; BLOCK old path and `-json`; D1 BLOCK deploy.sh (entry-inactive). @AWS-RO ALLOW one probe per new shape; BLOCK missing step-id, file:// filter, missing max-items, `file:` prefix, get-object, missing profile, admin profile, add-steps, get-managed-scaling-policy; code-reviewer BLOCK. Fixture probes in validate-agent-files.sh (committed stubs for plan.sh, run-bounded-spark.sh, check-submit-args.sh and a run config; ASK plan.sh x3; ALLOW dry-run; BLOCK dry-run with modified config (script-provenance); ALLOW check-submit-args), as given verbatim in the agent report.

## D. docs/agents.md text

- D1: recompute "(N rows; source sha256 …)" (expected 134: 99 identity + 35 group); environment sentence -> "`@env` = `@env-nonprod` = dev (amendment FE-P6-06); `<REGION>` is pinned to us-east-1, pending the human's Region confirmation."; directives line; one Markdown row per tsv row, text identical.
- D2 tripwires: "`cleanup.sh` is refused in every token except the static checks `bash -n scripts/cleanup.sh` and `shellcheck <paths>` without options (FE-P6-06)."
- D3 slot classes sentence (TFCHDIR, TFPLAN, STACK, EC2FILTER, S3PREFIX, REGION as in A).
- D4 catalog fences: infra-engineer "…; may run `scripts/plan.sh` (read-only profile), `scripts/run-bounded-spark.sh --dry-run` only, and the static checks `bash -n`/`shellcheck` of `scripts/cleanup.sh`"; deploy-engineer "approved scripts with fixed shapes (deploy.sh inactive in Phase 6: human Terminal, G6), plan checksum and terraform show for the bootstrap, base and emr-run plans, read-only git, enumerated read-only AWS with the read-only profile; no shell writes at all".
- D5 lifecycle row: "Phase 6 bounded EMR Spark run: `scripts/run-bounded-spark.sh --stage` and `--execute`, the `scripts/deploy.sh` applies and the cleanup phases (G6-a) | `human` | signed plan checksum, reviewed dry-run, cost bound, human approval".
- D6 human gate: "Phase 6 (Option A): confirm the Region; fill and commit env.conf, the emr-run tfvars and the run config; commit scripts with +x; run the first bootstrap and base plans, every deploy.sh apply, `--stage`, `--execute` and cleanup in a Terminal; sign each plan checksum."
- D7 gaps: G6 resolved for this run as human Terminal (G6-a); new ENV row; B3 unchanged; B5 open.

## Privilege delta

build-engineer and spark-engineer + `sbt sparkSubmitLocal` (ask). infra-engineer + init -backend=false (ask), validate, plan.sh (ask), dry-run, check-submit-args, cleanup static checks, 10 @AWS-RO shapes; narrowed REGION; direct plan row retired. deploy-engineer + shasum and show (active), 10 @AWS-RO; deploy.sh unchanged under D1; narrowed REGION. incident-responder + 10 @AWS-RO; narrowed REGION. Others unchanged. Ownership unchanged. Every new write or network row is `ask`. Floor invariants intact (no apply outside deploy.sh, no mutating AWS verb, no EMR termination or scale row, cleanup execution refused).

## Risks (as reported)

Opaque tools under the same OS user could use the admin profile during a valid `aws login` session (accepted CR-P6-28); R5 reads every object; plan.sh CLAUDECODE/TTY refusals unobserved; `terraform show` credentials unverified; REGION narrowing intended; pre-existing: active `scripts/check.sh` rows point at a missing file (deny script-provenance).

## Human decisions

Confirm Region; D1 (recommended) or D2; scanner choice; optional settings deny rules; commit scripts with +x and infra/terraform before provenance rows can pass; then application work order, restart, `.claude/hooks/validate-agent-files.sh --probes`. Out of scope, for infra-engineer: CLAUDECODE/TTY refusal in deploy.sh; pin env.conf aws_readonly_profile. Build-engineer: CR-P6-27 m2, m3, n3.
