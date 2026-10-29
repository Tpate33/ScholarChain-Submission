# ScholarChain Agent Ownership Map

Constitutional file. Owner: `factory-engineer`. This map is authoritative for ownership, routing, authority boundaries, escalation, and human gates. Changes take effect only after human ratification and a fresh Claude Code session.

**Authority basis:** the "Agent authority and ownership matrix", "Required factory artifacts", "Required hook and permission behavior", and "Required MCP policy" sections of the course project specification (`README.md` at commit `d23d307`). Refinements to exact paths are marked *refinement* and are part of this ratification. Open questions are listed under [Known gaps](#known-gaps-and-ambiguities-pending-human-decision). Where they exist, enforcement fails closed.

## Topology

```text
Human architect  --(intent, work orders, ratification, commits)-->  Orchestrator (main session)
Orchestrator     --(one work order at a time)-->                    exactly one subagent
Subagent         --(structured final report)-->                     Orchestrator
```

- **Subagents do not invoke one another.** No agent file grants the Agent/Task tool. All routing passes through the orchestrator, which keeps an observable audit trail.
- The orchestrator is the main Claude Code session, not a file under `.claude/agents/`. It must not perform a specialist's work inline. `enforce-ownership.sh` limits its writes to `docs/agent-runs/**` (excluding human gate files).
- Each subagent starts fresh with its role file, `CLAUDE.md`, the work order, and any prior reports pasted into the work order.
- A blocked agent returns `STATUS: BLOCKED-ON` with `BLOCKED-ON OWNER` and `BLOCKED-ON EVIDENCE`. The orchestrator routes the dependency to that owner, validates the repair, and reruns the blocked agent in a fresh context.
- Reviewer findings are routed to the owning agent and never fixed by the reviewer.

## Artifact ownership matrix (transcribed from the specification)

| Agent | Creates and owns | Must not do | Primary gate |
|---|---|---|---|
| `factory-engineer` | `CLAUDE.md`, `docs/agents.md`, `.claude/**`, `.mcp.json` | Application, data, build, infrastructure, or deployment work | Human constitutional ratification and code review |
| `build-engineer` | `build.sbt`, `project/*`, `.scalafmt.conf`, local compose files, packaging, `.gitignore` | Application source, version-only maintenance, Terraform, deployment | `sbt check` and code review |
| `domain-engineer` | `modules/core/**`, shared domain records, stable identifiers, serialization contracts, and configuration contracts | Dataset retrieval, distributed jobs, storage adapters, service routes, infrastructure | Contract tests, `sbt check`, and code review |
| `dataset-engineer` | `datasets/**`, dataset manifest, download and sampling scripts, schema notes, license records | Hadoop analytics, Spark analytics, entity-match policy | Dataset validation tests and manifest review |
| `hadoop-engineer` | `modules/hadoop/**`, MapReduce jobs, Hadoop integration tests, counters, rejected-record formats | Spark jobs, storage clients, service APIs | Local or EMR sample run and deterministic output checks |
| `entity-resolution-engineer` | `modules/entity-resolution/**`, blocking keys, match features, thresholds, calibration tools, match decision schemas | Graph analytics, API synthesis, infrastructure | Gold-set evaluation and match-policy review |
| `spark-engineer` | `modules/spark/**`, graph construction, graph analytics, evidence-path generation | Hadoop parser ownership, runtime API, Terraform | Spark tests, sample run, output invariants |
| `storage-engineer` | `modules/storage/**`, loaders and adapters for S3, Neptune, DynamoDB, S3 Vectors, optional OpenSearch | Terraform, answer synthesis, runtime HTTP routes | Contract tests and bounded load test |
| `rag-engineer` | `modules/rag/**`, query plans, retrieval fusion, evidence bundles, prompts, claim verification | HTTP transport, cloud deployment, dataset acquisition | Retrieval and groundedness evaluation |
| `service-engineer` | `modules/services/**`, REST models, routes, health checks, runtime composition | Hadoop and Spark algorithms, Terraform, agent constitution | API tests and `sbt check` |
| `test-engineer` | Adversarial and cross-module tests under `tests/**` and designated test source trees | Production implementation | Exact pass and fail report; no production edits |
| `code-reviewer` | No implementation artifacts; review reports only | Any write or edit | Read-only verified verdict |
| `infra-engineer` | `infra/terraform/**`, deployment, smoke-test, reproduction, cleanup scripts, and optional GitHub workflows | `terraform apply`, live deployment, application source | Format, validate, plan, script syntax checks, security scan, human approval |
| `deploy-engineer` | Deployment execution reports and release records | Authoring Terraform or deployment scripts | Approved plan, clean tree, smoke tests, rollback gate |
| `experiment-engineer` | `experiments/**`, `load-tests/**`, experiment manifests, raw result collectors | Altering production algorithms to improve reported results | Reproducible run and immutable raw results |
| `documentation-engineer` | `README.md`, `docs/**` except `docs/agents.md`, `docs/agent-runs/**`, and `docs/incidents/**`, plus the final report, diagrams, and demo script | Inventing results or changing implementation | Link check, command replay, evidence review |
| `incident-responder` | `docs/incidents/**`, diagnosis reports, approved rollback and bounded recovery actions | Schema changes, destructive data repair, infrastructure authorship | Human escalation for data, security, or destructive actions |
| `dependency-updater` | Version values in the approved dependency ledger and dependency update reports | Build structure, application features, major upgrades without approval | `sbt check`, vulnerability review, code review |

Human ratification records under `docs/agent-runs/**` are human-owned evidence. The orchestrator may assemble agent final reports there, but no agent may rewrite a human decision.

## Path ownership (machine-checked)

This table is mirrored by `.claude/hooks/lib-ownership.sh`. `validate-agent-files.sh` fails if they disagree. The first matching row wins, so specific rows precede general ones. Owners that are not agents: `ORCHESTRATOR` (main session), `HUMAN` (human architect only), `GENERATED` (written only by build tooling), and `UNASSIGNED` (no agent may write; escalate).

<!-- ownership-table:begin -->
| Path pattern | Owner | Basis |
|---|---|---|
| `CLAUDE.md` | `factory-engineer` | matrix |
| `docs/agents.md` | `factory-engineer` | matrix |
| `.mcp.json` | `factory-engineer` | matrix |
| `.claude/settings.json` | `factory-engineer` | matrix (`.claude/**`) and Phase 0 seed |
| `.claude/agents/**` | `factory-engineer` | matrix and Phase 0 seed |
| `.claude/hooks/**` | `factory-engineer` | matrix and Phase 0 seed |
| `.claude/commands/**` | `factory-engineer` | matrix and Phase 0 seed |
| `.claude/.last-test-run` | `GENERATED` | refinement: written only by the sbt `markTestRun` task (Phase 2) |
| `.claude/settings.local.json` | `HUMAN` | refinement: personal, uncommitted |
| `.claude/**` | `UNASSIGNED` | gap G1: matrix says `.claude/**`, but the seed lists only the paths above |
| `docs/agent-runs/README.md` | `HUMAN` | amendment A1: spec "human-owned evidence" and Phase 1 item 7 (evidence format); content drafted by factory-engineer outside the repo, committed by the human |
| `docs/agent-runs/*/human-gate.md` | `HUMAN` | spec: human ratification records are human-owned |
| `docs/agent-runs/*/commit.txt` | `HUMAN` | amendment A2: spec "Human commits the accepted phase and records the commit hash" |
| `docs/agent-runs/**` | `ORCHESTRATOR` | spec: orchestrator may assemble agent final reports |
| `LICENSE` | `HUMAN` | gap G9: not in matrix |
| `build.sbt` | `build-engineer` | matrix |
| `project/**` | `build-engineer` | matrix (`project/*`) |
| `.scalafmt.conf` | `build-engineer` | matrix |
| `.gitignore` | `build-engineer` | matrix |
| `compose.yaml` | `build-engineer` | matrix: local compose files |
| `compose.yml` | `build-engineer` | matrix: local compose files |
| `docker-compose.yaml` | `build-engineer` | matrix: local compose files |
| `docker-compose.yml` | `build-engineer` | matrix: local compose files |
| `modules/core/**` | `domain-engineer` | matrix |
| `modules/hadoop/**` | `hadoop-engineer` | matrix |
| `modules/entity-resolution/**` | `entity-resolution-engineer` | matrix |
| `modules/spark/**` | `spark-engineer` | matrix |
| `modules/storage/**` | `storage-engineer` | matrix |
| `modules/rag/**` | `rag-engineer` | matrix |
| `prompts/**` | `rag-engineer` | refinement: matrix "prompts" plus the spec layout `prompts/runtime`, `prompts/schemas` |
| `modules/services/**` | `service-engineer` | matrix |
| `datasets/**` | `dataset-engineer` | matrix |
| `tests/**` | `test-engineer` | matrix |
| `infra/terraform/**` | `infra-engineer` | matrix |
| `scripts/**` | `infra-engineer` | matrix (deployment, smoke-test, reproduction, cleanup scripts) and Phases 12-13 |
| `.github/workflows/**` | `infra-engineer` | matrix: optional GitHub workflows |
| `experiments/**` | `experiment-engineer` | matrix |
| `load-tests/**` | `experiment-engineer` | matrix |
| `docs/incidents/**` | `incident-responder` | matrix |
| `README.md` | `documentation-engineer` | matrix |
| `reports/final-report.md` | `documentation-engineer` | matrix: the final report (Phase 17 path) |
| `docs/**` | `documentation-engineer` | matrix: `docs/**` except the rows above |
| `*` | `UNASSIGNED` | everything else fails closed |
<!-- ownership-table:end -->

**Phase 2 skeleton exemption:** `build-engineer` may create empty `.gitkeep` placeholders inside other agents' territories so that "empty module source roots" and "empty required top-level directories" (spec Phase 2) exist. It may never do this inside `factory-engineer`, `HUMAN`, `GENERATED`, or `ORCHESTRATOR` paths.

## Agent catalog and tool fences

| Agent | Tools | Write territory (enforced by hook) | Extra Bash fence (guard-dangerous.sh; Bash allowlist rows below are authoritative) |
|---|---|---|---|
| `factory-engineer` | Read, Grep, Glob, Write, Edit, Bash | constitutional files | none beyond global floor |
| `build-engineer` | Read, Grep, Glob, Write, Edit, Bash | build files, `.gitkeep` skeleton | global floor |
| `domain-engineer` | Read, Grep, Glob, Write, Edit, Bash | `modules/core/**` | global floor |
| `dataset-engineer` | Read, Grep, Glob, Write, Edit, Bash, WebFetch | `datasets/**` | global floor |
| `hadoop-engineer` | Read, Grep, Glob, Write, Edit, Bash | `modules/hadoop/**` | global floor |
| `entity-resolution-engineer` | Read, Grep, Glob, Write, Edit, Bash | `modules/entity-resolution/**` | global floor |
| `spark-engineer` | Read, Grep, Glob, Write, Edit, Bash | `modules/spark/**` | global floor |
| `storage-engineer` | Read, Grep, Glob, Write, Edit, Bash, mcp__aws-docs | `modules/storage/**` | global floor |
| `rag-engineer` | Read, Grep, Glob, Write, Edit, Bash | `modules/rag/**`, `prompts/**` | global floor |
| `service-engineer` | Read, Grep, Glob, Write, Edit, Bash | `modules/services/**` | global floor |
| `test-engineer` | Read, Grep, Glob, Write, Edit, Bash | `tests/**` only | global floor |
| `code-reviewer` | Read, Grep, Glob | none | all Bash blocked |
| `infra-engineer` | Read, Grep, Glob, Write, Edit, Bash, mcp__aws-api, mcp__terraform, mcp__aws-docs | `infra/terraform/**`, `scripts/**`, `.github/workflows/**` | no apply/destroy/state mutation; cannot run deploy, rollback, cleanup, or experiment scripts |
| `deploy-engineer` | Read, Grep, Glob, Bash, mcp__aws-api | none (gap G4) | allowlist only (see Bash allowlist): approved scripts with fixed shapes, plan checksum, terraform show, read-only git, enumerated read-only AWS with the read-only profile; no shell writes at all |
| `experiment-engineer` | Read, Grep, Glob, Write, Edit, Bash | `experiments/**`, `load-tests/**` | only agent allowed to run `scripts/run-experiment.sh` |
| `documentation-engineer` | Read, Grep, Glob, Write, Edit, Bash | `README.md`, `docs/**` (minus exclusions), `reports/final-report.md` | global floor |
| `incident-responder` | Read, Grep, Glob, Write, Edit, Bash, mcp__aws-api | `docs/incidents/**` | may run `scripts/rollback.sh`; no destructive repair |
| `dependency-updater` | Read, Grep, Glob, Edit, Bash, WebFetch | none until the ledger path is ratified (gap G5) | global floor; no shell writes at all |

The **global floor** applies to every identity, including the orchestrator. It covers secret reads, terraform apply/destroy/state mutation, force push, recursive forced deletion, destructive AWS/database operations, unreviewed EMR/ECS scale changes, constitution writes by non-factory identities, test-marker forgery, `scripts/cleanup.sh` (human only), and **any Bash command that is not an active row of the Bash allowlist** (files are written with Write/Edit under the same ownership map; shell syntax, inline interpreter code, and unlisted programs are refused by default).

### Bash allowlist (machine-checked)

Default deny. A Bash call runs only if it passes the character gate in `guard-dangerous.sh` and matches an ACTIVE row for the acting identity. This table mirrors `.claude/hooks/bash-allowlist.tsv` row for row (119 rows; source sha256 `eacc92bb776577d131d16850575f5b83e3565f333b6af9cd47558d31a425796d`). Group rows (`@GIT`, `@SBT-ALL`, `@AWS-RO`) are included by identities whose row names the group. `code-reviewer` has no rows. INACTIVE rows never match; each names the decision that activates it. The environment lists (`@env`, `@env-nonprod`) are empty until Phase 12 ratifies them.

Directives: `@projects` = core hadoop entityResolution sparkAnalytics storage rag services; `@env` = (empty); `@env-nonprod` = (empty).

<!-- bash-table:begin -->
| Identity | Status | Mode | Provenance | Command shape | Justification |
|---|---|---|---|---|---|
| `@GIT` | active | allow | - | `git status {--porcelain;--short;--branch}` | read-only git |
| `@GIT` | active | allow | - | `git diff {--stat;--name-only;--name-status;--cached;--staged;--no-ext-diff;--no-textconv;--unified=<INT>} <REV>* ?-- <PATH>*` | read-only git; --output, --ext-diff, --textconv, --no-index excluded by construction |
| `@GIT` | active | allow | - | `git log {--oneline;--stat;--name-only;--max-count=<INT>;--no-ext-diff} <REV>? ?-- <PATH>*` | read-only git; --format/--pretty/--grep/--output excluded |
| `@GIT` | active | allow | - | `git show {--stat;--name-only;--no-ext-diff;--no-textconv} <REVX>` | read-only git |
| `@GIT` | active | allow | - | `git rev-parse {--short;--abbrev-ref;--verify;--show-toplevel} <REV>?` | read-only git |
| `@GIT` | active | allow | - | `git ls-files {--others;--exclude-standard;--modified;--deleted} ?-- <PATH>*` | read-only git |
| `@GIT` | active | allow | - | `git branch --show-current` | read-only git |
| `@SBT-ALL` | active | allow | - | `sbt check` | definition of done (E1: writes target/ and, via markTestRun inside check, the test marker) |
| `@SBT-ALL` | active | allow | - | `sbt clean check` | definition of done from clean (E1) |
| `@SBT-ALL` | active | allow | - | `sbt compile` | E1 |
| `@SBT-ALL` | active | allow | - | `sbt Test/compile` | E1 |
| `@SBT-ALL` | active | allow | - | `sbt test` | E1 |
| `@AWS-RO` | active | allow | - | `aws sts get-caller-identity {!--profile:<RO>;--region:<REGION>;--output:<OUT>}` | read-only identity check; --profile mandatory (read-only IAM role is the real boundary) |
| `@AWS-RO` | active | allow | - | `aws ecs describe-services {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--cluster:<AWSID>;!--services:<AWSID>+}` | read-only verification |
| `@AWS-RO` | active | allow | - | `aws ecs describe-tasks {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--cluster:<AWSID>;!--tasks:<AWSID>+}` | read-only verification |
| `@AWS-RO` | active | allow | - | `aws ecs list-tasks {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--cluster:<AWSID>;--service-name:<AWSID>}` | read-only verification |
| `@AWS-RO` | active | allow | - | `aws ecs describe-task-definition {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--task-definition:<AWSID>}` | read-only verification |
| `@AWS-RO` | active | allow | - | `aws ecr describe-images {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--repository-name:<AWSID>;--image-ids:<IMGID>+}` | image digest check |
| `@AWS-RO` | active | allow | - | `aws cloudwatch describe-alarms {!--profile:<RO>;--region:<REGION>;--output:<OUT>;--alarm-names:<AWSID>+;--alarm-name-prefix:<AWSID>}` | alarm check |
| `@AWS-RO` | active | allow | - | `aws stepfunctions describe-execution {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--execution-arn:<AWSID>}` | pipeline status |
| `@AWS-RO` | active | allow | - | `aws stepfunctions list-executions {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--state-machine-arn:<AWSID>;--max-results:<INT>}` | pipeline status |
| `@AWS-RO` | active | allow | - | `aws emr describe-cluster {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--cluster-id:<AWSID>}` | EMR status (read-only) |
| `@AWS-RO` | active | allow | - | `aws emr list-steps {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--cluster-id:<AWSID>}` | EMR status (read-only) |
| `@AWS-RO` | inactive:MED-5-queue-url-not-expressible | allow | - | `aws sqs get-queue-attributes {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--queue-url:<AWSID>;--attribute-names:<AWSID>+}` | queue depth check; INACTIVE: a queue URL always contains // and https:, which AWSID rejects (MED-5); use mcp__aws-api. No URL class is added |
| `@AWS-RO` | active | allow | - | `aws elbv2 describe-target-health {!--profile:<RO>;--region:<REGION>;--output:<OUT>;!--target-group-arn:<AWSID>}` | service health check |
| `ORCHESTRATOR` | active | allow | - | `@GIT` | /deploy gate 4 |
| `ORCHESTRATOR` | active | allow | hook | `.claude/hooks/validate-agent-files.sh` | reproduction step: agent validation |
| `ORCHESTRATOR` | active | allow | hook | `.claude/hooks/verify-tests-ran.sh --check` | read-only check of uncommitted source against the test marker (not a deploy gate) |
| `ORCHESTRATOR` | active | allow | - | `java -version` | versions.md evidence |
| `ORCHESTRATOR` | active | allow | - | `sbt --version` | versions.md evidence |
| `ORCHESTRATOR` | active | allow | - | `terraform version` | versions.md evidence |
| `ORCHESTRATOR` | active | allow | - | `aws --version` | versions.md evidence |
| `ORCHESTRATOR` | active | allow | - | `python3 --version` | versions.md evidence |
| `ORCHESTRATOR` | active | allow | - | `claude --version` | versions.md evidence |
| `factory-engineer` | active | allow | - | `@GIT` | Required validation: complete diff |
| `factory-engineer` | active | allow | - | `bash -n <PATH:.claude/hooks/>` | Required validation: shell syntax (bash -n checks exactly one file) |
| `factory-engineer` | active | allow | - | `shellcheck <PATH:.claude/hooks/>+` | Required validation: shellcheck |
| `factory-engineer` | active | allow | - | `python3 -I -m json.tool .claude/settings.json` | Required validation: JSON (exactly one argument; a second would be an output file) |
| `factory-engineer` | active | allow | - | `python3 -I -m json.tool .mcp.json` | Required validation: JSON |
| `factory-engineer` | active | allow | - | `jq . .claude/settings.json` | Required validation: JSON (factory agent file names jq .) |
| `factory-engineer` | active | allow | - | `jq . .mcp.json` | Required validation: JSON |
| `factory-engineer` | active | allow | - | `test -x <PATH:.claude/hooks/>` | Required validation: executable bit |
| `factory-engineer` | active | allow | hook | `.claude/hooks/validate-agent-files.sh ?--probes` | Required validation and committed probe table |
| `build-engineer` | active | allow | - | `@GIT` | matrix row |
| `build-engineer` | active | allow | - | `@SBT-ALL` | Phase 2 |
| `build-engineer` | active | allow | - | `sbt fmt` | repo-wide formatting (E2 repo-wide; build-engineer only) |
| `build-engineer` | active | allow | - | `sbt package` | Phase 2 packaging |
| `build-engineer` | active | allow | - | `sbt packageJobs` | Phase 2 packaging (build-engineer's own alias) |
| `build-engineer` | active | ask | - | `sbt dockerLocal` | E7 local image build |
| `build-engineer` | active | allow | - | `sbt reload` | build maintenance |
| `build-engineer` | active | allow | - | `sbt sbtVersion` | versions evidence |
| `domain-engineer` | active | allow | - | `@GIT` | matrix row |
| `domain-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `domain-engineer` | active | allow | - | `sbt <SBT:core> <SBT:core>? <SBT:core>? <SBT:core>?` | own-module tasks; scalafmt writes own module only (E2) |
| `hadoop-engineer` | active | allow | - | `@GIT` | matrix row |
| `hadoop-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `hadoop-engineer` | active | allow | - | `sbt <SBT:hadoop> <SBT:hadoop>? <SBT:hadoop>? <SBT:hadoop>?` | own-module tasks (E2) |
| `hadoop-engineer` | inactive:B10-alias-pending | allow | - | `sbt localPipeline` | Phase 4 local bounded pipeline; alias authored by build-engineer |
| `entity-resolution-engineer` | active | allow | - | `@GIT` | matrix row |
| `entity-resolution-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `entity-resolution-engineer` | active | allow | - | `sbt <SBT:entityResolution> <SBT:entityResolution>? <SBT:entityResolution>? <SBT:entityResolution>?` | own-module tasks (E2) |
| `entity-resolution-engineer` | inactive:B10-alias-pending | allow | - | `sbt goldSetEval` | Phase 5 gold-set evaluation |
| `spark-engineer` | active | allow | - | `@GIT` | matrix row |
| `spark-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `spark-engineer` | active | allow | - | `sbt <SBT:sparkAnalytics> <SBT:sparkAnalytics>? <SBT:sparkAnalytics>? <SBT:sparkAnalytics>?` | own-module tasks (E2) |
| `spark-engineer` | inactive:B10-alias-pending | allow | - | `sbt sparkIT` | Phase 6 local Spark integration test |
| `storage-engineer` | active | allow | - | `@GIT` | matrix row |
| `storage-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `storage-engineer` | active | allow | - | `sbt <SBT:storage> <SBT:storage>? <SBT:storage>? <SBT:storage>?` | own-module tasks (E2) |
| `storage-engineer` | inactive:B10-alias-pending | ask | - | `sbt storageIT` | Phase 7 bounded storage integration (opt-in profile) |
| `rag-engineer` | active | allow | - | `@GIT` | matrix row |
| `rag-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `rag-engineer` | active | allow | - | `sbt <SBT:rag> <SBT:rag>? <SBT:rag>? <SBT:rag>?` | own-module tasks (E2) |
| `rag-engineer` | inactive:B10-alias-pending | allow | - | `sbt benchmarkQueries` | Phase 9 benchmark-query suite |
| `service-engineer` | active | allow | - | `@GIT` | matrix row |
| `service-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `service-engineer` | active | allow | - | `sbt <SBT:services> <SBT:services>? <SBT:services>? <SBT:services>?` | own-module tasks (E2) |
| `dataset-engineer` | active | allow | - | `@GIT` | matrix row |
| `dataset-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `dataset-engineer` | active | allow | git-ask | `<DSSCRIPT> {--sample;--verify;--dry-run} <PATH:datasets/>*` | E6 bounded sample/verify scripts; --full never (human runs full downloads) |
| `dataset-engineer` | active | allow | - | `shasum -a 256 <PATH:datasets/>+` | checksums |
| `test-engineer` | active | allow | - | `@GIT` | matrix row |
| `test-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `test-engineer` | active | allow | - | `sbt <SBTTEST> <SBTTEST>? <SBTTEST>? <SBTTEST>?` | per-project suites |
| `test-engineer` | inactive:G7-tests-project-pending | allow | - | `sbt tests/Test/scalafmt` | format test tree; project name pending Phase 2 and G7 |
| `infra-engineer` | active | allow | - | `@GIT` | matrix row |
| `infra-engineer` | active | allow | - | `terraform -chdir=infra/terraform fmt -check -recursive` | Terraform format check |
| `infra-engineer` | inactive:B6+ENV-unratified | ask | - | `terraform <TFCHDIR> init -input=false` | E3; .gitignore must cover .terraform/ (B6) |
| `infra-engineer` | inactive:ENV-unratified | allow | - | `terraform <TFCHDIR> validate` | Phase 12 |
| `infra-engineer` | inactive:B6+ENV-unratified | ask | - | `terraform <TFCHDIR> plan -input=false -out=tfplan` | E4; fixed -out; no -target/-var/-var-file/-destroy/-refresh-only |
| `infra-engineer` | active | allow | - | `bash -n <PATH:scripts/>` | shell syntax check of own scripts |
| `infra-engineer` | active | allow | - | `shellcheck <PATH:scripts/>+` | shell lint of own scripts |
| `infra-engineer` | active | allow | git | `scripts/check.sh` | approved check script (committed, unmodified) |
| `infra-engineer` | inactive:ENV-unratified | ask | git | `scripts/plan.sh <ENVNP>` | Phase 12 plan script |
| `infra-engineer` | inactive:B3 | ask | git | `scripts/package.sh` | B3 unresolved |
| `infra-engineer` | inactive:B3 | ask | git | `scripts/smoke-test.sh <ENVNP>` | B3 unresolved |
| `infra-engineer` | active | allow | - | `@AWS-RO` | read-only verification |
| `deploy-engineer` | active | allow | - | `@GIT` | matrix row |
| `deploy-engineer` | inactive:ENV-unratified | ask | git | `scripts/deploy.sh <ENV> <SHA40> <SHA256>` | /deploy |
| `deploy-engineer` | inactive:ENV-unratified | ask | git | `scripts/smoke-test.sh <ENV>` | /deploy |
| `deploy-engineer` | inactive:ENV-unratified | ask | git | `scripts/rollback.sh <ENV> <REV>` | failed in-flight deploy |
| `deploy-engineer` | inactive:B2+G6 | ask | git+args | `scripts/run-bounded-pipeline.sh <ENV> <PATH:datasets/> <PATH>` | /run-pipeline (operator pending G6) |
| `deploy-engineer` | inactive:B4 | ask | git | `scripts/archive-evidence.sh <ENV> <SHA40>` | evidence path and owner pending |
| `deploy-engineer` | inactive:ENV-unratified | allow | - | `shasum -a 256 <TFPLAN>` | plan checksum gate |
| `deploy-engineer` | inactive:ENV-unratified | allow | - | `terraform <TFCHDIR> show -no-color tfplan` | plan review; -json excluded (prints sensitive values) |
| `deploy-engineer` | active | allow | - | `@AWS-RO` | read-only verification |
| `experiment-engineer` | active | allow | - | `@GIT` | matrix row |
| `experiment-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `experiment-engineer` | inactive:ENV-unratified | ask | git+args | `scripts/run-experiment.sh <PATH:experiments/> <ENV>` | /experiment; manifest must be committed and unmodified |
| `incident-responder` | active | allow | - | `@GIT` | matrix row |
| `incident-responder` | inactive:ENV-unratified | ask | git | `scripts/rollback.sh <ENV> <REV>` | /rollback |
| `incident-responder` | inactive:ENV-unratified | ask | git | `scripts/smoke-test.sh <ENV>` | /incident, /rollback |
| `incident-responder` | active | allow | - | `@AWS-RO` | read-only diagnosis |
| `documentation-engineer` | active | allow | - | `@GIT` | matrix row |
| `documentation-engineer` | active | allow | - | `@SBT-ALL` | replay sbt check |
| `documentation-engineer` | active | allow | hook | `.claude/hooks/validate-agent-files.sh` | replay agent validation |
| `documentation-engineer` | active | allow | git | `scripts/check.sh` | replay check script |
| `dependency-updater` | active | allow | - | `@GIT` | matrix row |
| `dependency-updater` | active | allow | - | `@SBT-ALL` | sbt check |
<!-- bash-table:end -->

## Lifecycle routing (machine-checked)

Each lifecycle step has exactly one routed owner. The orchestrator uses this table to choose the subagent. Rows marked `human` are never delegated.

<!-- routing-table:begin -->
| Lifecycle step | Routed to | Gate |
|---|---|---|
| Agent constitution: agents, hooks, commands, settings, MCP, CLAUDE.md, docs/agents.md | `factory-engineer` | validate-agent-files.sh, code review, human ratification, session restart |
| Build definition, sbt projects, plugins, formatting rules, packaging, .gitignore | `build-engineer` | sbt check, code review |
| Dependency version bumps within approved major versions | `dependency-updater` | sbt check, vulnerability review, code review |
| Shared domain records, stable IDs, serialization and configuration contracts | `domain-engineer` | contract tests, sbt check, code review |
| Dataset manifest, bounded download and sampling scripts, schemas, licenses | `dataset-engineer` | dataset validation, manifest review |
| Hadoop MapReduce jobs: parse, normalize, block, team extraction, pair expansion, export | `hadoop-engineer` | local or EMR sample run, deterministic outputs, sbt check |
| Entity resolution: features, policy, thresholds, LLM adjudication, gold-set evaluation tooling | `entity-resolution-engineer` | gold-set evaluation, match-policy review |
| Spark graph construction, collaboration analytics, evidence paths, semantic summaries | `spark-engineer` | Spark tests, sample run, output invariants |
| Storage ports, adapters, loaders, embedding ingestion queue | `storage-engineer` | contract tests, bounded load test |
| Runtime query workflow, prompts, retrieval fusion, evidence bundles, claim verification | `rag-engineer` | retrieval and groundedness evaluation, sbt check |
| REST routes, API models, health checks, runtime composition | `service-engineer` | API tests, sbt check |
| Adversarial and cross-module tests | `test-engineer` | exact pass/fail report, no production edits |
| Read-only review of a diff or repository | `code-reviewer` | APPROVE or REQUEST_CHANGES with verified findings |
| Terraform authoring, plan, operational scripts, optional GitHub workflows | `infra-engineer` | fmt, validate, plan, script syntax, security scan, human approval |
| Deployment of an approved commit and plan, including rollback of that in-flight deployment | `deploy-engineer` | approved plan checksum, clean tree, smoke tests, rollback gate |
| Bounded cloud data-pipeline run through scripts/run-bounded-pipeline.sh (proposed, gap G6) | `deploy-engineer` | approved environment, manifest, cost bound, human approval |
| Experiments, load tests, evaluation runs, raw result collection | `experiment-engineer` | committed manifest, reproducible run, immutable raw results |
| README, docs, final report, diagrams, demo script, evaluation write-up | `documentation-engineer` | link check, command replay, evidence review |
| Incident diagnosis, incident reports, rollback of an already released revision | `incident-responder` | human escalation for data, security, destructive actions |
| Phase evidence assembly under docs/agent-runs | `orchestrator` | evidence format below |
| Ratification, human gate records, commits, gold-set labels, cleanup execution | `human` | human judgement |
| Release manifest (unresolved between deploy-engineer and documentation-engineer, gap G3) | `human` | human decides the owner before Phase 14 |
<!-- routing-table:end -->

## Authority boundaries

1. One artifact class has exactly one creating and owning agent.
2. An agent asked to edit another agent's artifact declines and names the correct owner.
3. Reviewer-class agents have no write tools. `code-reviewer` has only Read, Grep, and Glob.
4. Infrastructure authors may produce plans but may not apply or deploy them.
5. Deployment operators may execute approved scripts but may not author them.
6. Tests must be run before an agent may report implementation work complete.
7. Dangerous commands are blocked by hooks and permission rules.
8. Constitutional files require human ratification and a fresh session before they take effect.
9. One project phase uses one fresh orchestrator session and normally one commit.
10. Agent reports, committed contracts, and repository artifacts are the only memory that crosses sessions.
11. A blocked agent returns `BLOCKED-ON` with evidence and the required owner.
12. No agent approves its own work.

## Escalation rules

In every case the agent stops, makes no partial change, and returns `STATUS: BLOCKED-ON` with `BLOCKED-ON OWNER` and `BLOCKED-ON EVIDENCE`. The orchestrator presents it to the decider below.

| Trigger | Decider | Required evidence | Deterministic enforcement |
|---|---|---|---|
| **Constitutional change** (any file owned by `factory-engineer`, the authority matrix, tool widening, autonomy widening) | Human architect ratifies; change effective only after session restart | complete diff, per-file authority justification, privilege delta, audits, validation output | `enforce-ownership.sh`, the Bash allowlist (no row writes `.claude/**`, except the GENERATED test marker `.claude/.last-test-run` via `sbt check` / `sbt clean check`; any other write by opaque tools is detected only post hoc by the audit), `validate-agent-files.sh` |
| **Data destruction** (deleting or truncating S3 objects, DynamoDB items/tables, Neptune data, vectors/indexes; destructive data repair) | Human architect | affected resources, row/object counts, backup/restore path, reason | guard rules s3/dynamodb/neptune/s3vectors/database-destruction; permission deny list |
| **Security change** (IAM policies, KMS, Secrets Manager, security groups, public access, authentication, MCP or permission changes) | Human architect, with code review | policy diff, principal/resource scope, threat-model impact | Terraform plan review; kms-destructive and protection-removal guards; constitution guard |
| **Terraform apply or destroy** | Human architect approves a specific plan checksum | plan summary: add/change/destroy counts, stateful replacements, IAM changes, cost estimate, target account and Region | guard terraform-apply-destroy rule; permission deny; apply only inside `scripts/deploy.sh` |
| **Deployment** | Human architect approves commit SHA, plan checksum, account, Region, environment | clean tree, reviewer APPROVE, test report, cost bound | `/deploy` gates; only `deploy-engineer` may run `scripts/deploy.sh`; permission `ask` |
| **Expensive scale change** (EMR capacity, cluster creation or termination, ECS desired counts, Auto Scaling policies, experiments above approved bounds, optional OpenSearch) | Human architect | requested capacity, duration, cost estimate, manifest | guard emr/compute-scale-change rules; scaling policy only through Terraform |
| **Ambiguous ownership** (path not in the table, two plausible owners, matrix contradiction) | Human architect, via a matrix amendment prepared by `factory-engineer` | the path, candidate owners, spec citations | `UNASSIGNED` paths fail closed in `enforce-ownership.sh` |
| **Cleanup** | Human runs `scripts/cleanup.sh` in a terminal | before/after resource inventory, retained-evidence list | guard cleanup-human-only rule; permission deny |

## Human gates

- **Phase 1:** ratify this constitution, run the probes, record the ratification statement and commit hash, and restart. For probe 5 only, the human (never an agent) creates one harmless temporary Scala sample, untracked and never staged, outside every module tree. The human deletes it as soon as the Stop hook has refused completion. It is not application source, and the rule that no Scala source is created before Phase 2 still binds every agent.
- **Every phase:** review the agent report and reviewer verdict, accept or reject, disclose manual edits, commit, and record the hash.
- **Commits and pushes are human-only.** No agent or orchestrator Bash row contains `git add`, `commit`, `push`, `tag`, `merge`, or `rebase`; the human runs them in a terminal.
- **Phase 3:** confirm that no dataset license, identifier, or source is invented.
- **Phase 5:** label and review gold-set examples.
- **Phase 12:** review the Terraform plan and cost estimate. Sign the plan before any apply.
- **Phase 14:** verify the plan checksum, account, Region, cost bounds, and the absence of unexplained destroy or replace. Approve the deployment command.
- **Phase 15:** approve experiment manifests and cost bounds.
- **Cleanup:** approve and run the cleanup and evidence-archival location.

## Enforcement layers

| Layer | File | Event | Purpose |
|---|---|---|---|
| Permissions | `.claude/settings.json` `permissions` | every tool call | explicit allow / ask / deny lists; bypass mode disabled |
| Dangerous-command guard | `.claude/hooks/guard-dangerous.sh` | PreToolUse `Bash\|Read\|Grep\|Glob\|mcp__aws-api__.*` | Bash: default-deny allowlist (character gate, per-identity rows in `bash-allowlist.tsv`, token tripwires, script provenance); Read/Grep/Glob: secret-read; aws-api MCP: destructive and secret-dump verbs. Never emits allow |
| Bash allowlist (data) | `.claude/hooks/bash-allowlist.tsv` | read by `guard-dangerous.sh` on every Bash call | the Bash column of the authority matrix (see Bash allowlist) |
| Shell-write audit | `.claude/hooks/audit-bash-writes.sh` | PreToolUse `Bash` (`--pre`), PostToolUse `Bash` (`--post`) | detective backstop (detection after the fact, not prevention): content-hash diff (sha256 plus executable bits; size and mtime ignored, except for the test marker) of every git-visible path and of `.git/config`, `.git/info/exclude`, `.git/info/attributes`, `.git/hooks/*`; `--post` reports changes outside the acting identity's territory and any change to the test marker `.claude/.last-test-run` (content, executable bits, size, or mtime; recorded even when gitignored) made by a command other than exactly `sbt check` / `sbt clean check` (inside those two commands the audit cannot tell markTestRun's update from a touch by test code); `--pre` compares with the session baseline (the state at the end of the previous Bash call, plus the paths `post-write.sh` recorded for authorized writes since) and reports any other change as UNATTRIBUTED (the call is refused, the baseline is reset); sends Bash-changed Scala the identity owns through `format-scala.sh` and records the formatted result. Fails closed (exit 2) on: a missing pre snapshot (`--post`), a corrupt baseline, a session lock not acquired within 5 s, or a baseline that is missing after the session-started marker exists. A missing baseline with no started marker is a new session (baseline created, exit 0) |
| Post-write recorder | `.claude/hooks/post-write.sh` (with `.claude/hooks/lib-baseline.sh`) | PostToolUse `Write\|Edit\|MultiEdit\|NotebookEdit` | runs `format-scala.sh` first (unchanged behaviour), then re-hashes exactly the written path into the session baseline (lock, temp file + rename), only if the identity is valid and owns the path. Exits 2 without recording when python3 is missing, the identity is invalid, the path is outside the identity's territory, the baseline is missing after session start, the lock is not acquired within 5 s, or the baseline is corrupt. Records nothing and exits with format-scala's status (normally 0) when the payload names no path, the project directory cannot be entered or is not a git work tree, the path is outside the repository, or no Bash call has run yet this session (no baseline, no started marker) |
| Probe table (data) | `.claude/hooks/probes.tsv` | read by `validate-agent-files.sh --probes` | committed inert probes for every floor rule, identity class, C1-C3, Phase 1 probes, and `.git` writes |
| Ownership guard | `.claude/hooks/enforce-ownership.sh` | PreToolUse `Write\|Edit\|MultiEdit\|NotebookEdit` | one-writer rule, constitutional protection, test-territory fence |
| Formatter | `.claude/hooks/format-scala.sh` | invoked first by `post-write.sh` (PostToolUse `Write\|Edit\|MultiEdit\|NotebookEdit`) and by `audit-bash-writes.sh --post` for Bash-changed Scala | scalafmt after repository Scala writes; exit 2 with the install command or the `.scalafmt.conf` owner when either is missing; silent exit 0 when no repository Scala file changed |
| Constitution validator | `.claude/hooks/validate-agent-files.sh --hook` | PostToolUse `Write\|Edit\|MultiEdit` | revalidates after any constitutional write |
| Test gate | `.claude/hooks/verify-tests-ran.sh` | Stop, SubagentStop | refuses completion while source is newer than `.claude/.last-test-run`, and while the marker differs from the session audit baseline (changed after the last audited Bash call; skipped when the session has no baseline). `--check`: read-only uncommitted-source check, not commit evidence |
| Shared library | `.claude/hooks/lib-ownership.sh` | sourced | path-ownership map (case-insensitive), tree probes, identity resolution, JSON helpers |

Identity comes from the hook payload's `agent_type` field (present inside subagents). Without it, the identity is `ORCHESTRATOR`.

**Bash enforcement (default deny).** `guard-dangerous.sh` does not parse shell. A Bash command is accepted only if:

1. **Character gate.** Every byte is in `A-Z a-z 0-9 . _ / : = @ , + -` or a single ASCII space; no leading, trailing, or doubled space; at most 400 bytes and 24 tokens; no token starts with `=` or contains `==` (zsh `=` expansion). Every shell operator, quote, expansion, redirection, glob, comment, tab, and newline is outside the alphabet, so bash, sh, and zsh all split the command on single spaces and expand nothing.
2. **Allowlist match.** The space-separated tokens match an ACTIVE row for the acting identity in `.claude/hooks/bash-allowlist.tsv` (typed slots, no backtracking; see Bash allowlist). Unknown identities and `code-reviewer` have no rows.
3. **Tripwires.** Token-level floor checks on the matched argv: terraform apply/destroy/state, git push and force flags, destructive and mutating AWS verbs, EMR scale or termination, `scripts/cleanup.sh`, test-marker forgery, secret names, and dangerous programs. They fire only if the data file is mis-edited.
4. **Path resolution.** Path arguments must resolve inside the repository, outside `.git`, and away from secret material (symlinks included).
5. **Script provenance.** A row that runs a repository script requires the script and every file in its script directory (`scripts/**`, or `datasets/scripts/**`) to be committed and byte-identical to `HEAD` (raw bytes, `git hash-object --no-filters`, so clean filters cannot mask changes; same executable bit; no untracked, ignored, deleted, or symlinked entries), and the call must run from the repository root. dataset-engineer's own scripts return `ask` instead of deny. The provenance git calls share a 10-second budget; a timeout denies. Factory hook scripts are exempt; they already run as hooks.
6. **Identity.** `agent_type` absent means the orchestrator. Present but empty, null, the string `null`, a non-string, or not one of the 18 ratified agents: every checked tool call is denied. `enforce-ownership.sh` applies the same rule to Write/Edit/MultiEdit/NotebookEdit (shared function `scholarchain_identity_checked` in `lib-ownership.sh`); the audit and `post-write.sh` treat such identities as owning nothing.
7. **Build-output precondition.** Every `sbt` row is denied (rule `gitignore-missing`, naming `build-engineer` and decision B6) while the repository root has no regular `.gitignore` file.

The guard emits `deny` (exit 2, with the rule, the identity's allowed shapes, and what to do instead) or `ask`, or no decision. It never emits `allow`, so the `ask` rules in `settings.json` still apply. Agents are expected to write files only with Write/Edit (checked by `enforce-ownership.sh`); no allowlist row is itself a file-writing shell command. The opaque tools named in the allowlist (sbt and the test code it runs, approved scripts) can still write anywhere the OS user can; `audit-bash-writes.sh` detects such writes to git-visible paths only after the fact (see Limitations without an OS-level sandbox).

**Residual risk.** Opaque tools on the allowlist (`sbt`, including test code the calling agent wrote, and approved scripts) can write anything the OS user can. `audit-bash-writes.sh` detects content changes after the fact by sha256, for git-visible, non-ignored paths and the `.git` control files, both during a Bash call (attributed to the caller) and between calls (reported UNATTRIBUTED at the next call). It reports them with exit 2 and tells the agent to stop with `BLOCKED-ON human`. It cannot revert them. Gitignored paths and paths outside the repository are not audited. Bash-changed Scala the identity owns is sent through `format-scala.sh`, and a missing scalafmt or `.scalafmt.conf` yields exit 2. Global aliases or functions in the user's shell profile are outside agent control. The OS-level Bash sandbox is not enabled. `code-reviewer` review of the phase diff remains the final line of defense.

**Limitations without an OS-level sandbox (B11 off).** The hooks do NOT prevent every unauthorized write. The Bash allowlist decides which programs start; it cannot constrain what an opaque tool does once it runs. `sbt` (including the test code it compiles and runs, which the calling agent may have written) and the approved scripts execute as the same OS user as Claude Code. They can therefore write outside the repository (the user's shell profile, `~/.claude`, directories on `PATH`), modify or delete this audit's snapshots and baselines in `TMPDIR`, start processes that outlive the call, rewrite the test marker, and fabricate evidence files. `audit-bash-writes.sh` is post-hoc detection for git-visible, non-ignored paths and the `.git` control files; it is not prevention, it cannot see ignored paths or anything outside the repository, and it can be blinded by a process that tampers with its state files. The compensating control is `code-reviewer` review of all test code and scripts before they are run, plus human review of each phase diff. Enabling Claude Code's OS-level Bash sandbox (decision B11) would add a filesystem fence for all Bash children; it remains the human's decision and is not enabled.

**Inter-call reports, edits outside Claude Code, and concurrency.** Authorized Write/Edit/MultiEdit/NotebookEdit calls are recorded in the audit baseline by `post-write.sh`, so they are not reported. Any other change between two Bash calls of a session -- including the human's own IDE edits, `git` operations the human runs in a terminal, and background processes -- is reported at the next Bash call as an UNATTRIBUTED inter-call change, and that one call is refused. Operator remedy (the simplest honest one): the human reviews the reported paths (`git status`, `git diff`); the report has already reset the baseline to the current state, so the agent then re-runs the same command. A Stop/SubagentStop refusal because the test marker changed after the last audited Bash call (R1-b) has the matching remedy. The Stop hook also refuses when `stop_hook_active` is set, so the human first interrupts the turn (Esc). The human then reviews the marker and `git status`, and the orchestrator makes one Bash call (for example `git status --porcelain`). That call is refused with the UNATTRIBUTED report, but the report resets the baseline, and Stop is then allowed. No reset command exists for agents. Two known gaps remain. First (R5): `post-write.sh` re-hashes the whole written file, so an out-of-band change to that same file made before the Write/Edit (for example, a background process from earlier test code appending to it) is absorbed and not reported. This applies only to paths the writer owns, so no cross-owner change is blessed. Second (R1 residual): the test marker may change during `sbt check` / `sbt clean check`, so test code run by `sbt check` can advance the marker even if tests then fail. A failing `sbt check` is visible in that command's own output, which the agent must report verbatim. Third (R1-b residual): `verify-tests-ran.sh` (Stop/SubagentStop) refuses completion when the marker no longer matches the session baseline, so a delayed background touch before Stop is caught; it is not caught when the session has no baseline yet, when the baseline is missing or corrupt at Stop time, or when the touch lands after completion was allowed. Binding the marker to a passing run (and to the commit) is a Phase 2 `build-engineer` item for `markTestRun`. Parallel Bash calls (or concurrent writes) in one session are unsupported: the hooks serialize on a per-session lock and fail closed when it is held longer than 5 s. A lock left behind by a killed hook is stale; the human removes it (`rmdir <TMPDIR>/scholarchain-bash-audit-<uid>/session-<key>.lock`) after confirming no tool call is running.

**Settings mirror.** `.claude/settings.json` deny rules mirror the floor where the permission syntax allows. This covers secret paths for Read (and, best-effort, Grep/Glob) and Bash, and destructive AWS calls with leading global options or env prefixes. The hooks are authoritative. The exact matching semantics of multi-wildcard Bash deny rules are not verifiable from inside a session and must be confirmed by the post-restart probes.

## MCP servers: trust boundaries and environment variables

Project MCP servers in `.mcp.json` require explicit human approval on first use (`enableAllProjectMcpServers` is `false`). Subagents receive an MCP server only when their `tools` field names it. No secret value is stored in the repository. Only environment-variable names appear.

| Server | Purpose | Trust boundary | Environment variables | Write capability | Granted to |
|---|---|---|---|---|---|
| `github` | Repository browsing, issue and pull-request inspection | Runs locally in Docker (`ghcr.io/github/github-mcp-server`) and talks to api.github.com with the supplied token. It can see whatever the token can see. Read-only enforced twice: `--read-only` flag and `GITHUB_READ_ONLY=1`, toolsets limited to repos, issues, and pull requests. | `GITHUB_PERSONAL_ACCESS_TOKEN` (fine-grained PAT, this repository only, read-only Contents, Issues, Pull requests, Metadata) | none | orchestrator only |
| `aws-api` | Read-only AWS API inspection (describe/list/get) for plan verification, deployment verification, incident diagnosis | Runs locally via `uvx awslabs.aws-api-mcp-server` and calls AWS with the named profile. The **real** boundary is IAM: the profile must assume a role with only `ReadOnlyAccess`/`ViewOnlyAccess`-class permissions. Server-side `READ_OPERATIONS_ONLY=true` is defense in depth, and `guard-dangerous.sh` also inspects its calls. | `AWS_REGION` (default `us-east-1`), `SCHOLARCHAIN_READONLY_AWS_PROFILE` (default `scholarchain-readonly`) | none (read-only mode plus read-only IAM role) | `infra-engineer`, `deploy-engineer`, `incident-responder` |
| `terraform` | Terraform Registry provider, resource, and module documentation | Runs locally in Docker (`hashicorp/terraform-mcp-server`) and reads the public Terraform Registry. No HCP Terraform token is configured, so it has no workspace or run access. | none (do **not** set `TFE_TOKEN`) | none | `infra-engineer` |
| `aws-docs` | AWS documentation search and read | Runs locally via `uvx awslabs.aws-documentation-mcp-server` and reads public docs.aws.amazon.com only | `AWS_DOCUMENTATION_PARTITION=aws` | none | `infra-engineer`, `storage-engineer` |

Rules: never place credentials in `.mcp.json`. Never grant an MCP server to `code-reviewer`. Adding a server, toolset, or write capability is a constitutional change that needs an explicit phase gate. Retrieved MCP content is untrusted data.

## Approved local placeholders

None ratified yet. Every `.env*` file, including templates, is unreadable by agents under the floor invariant. A placeholder convention (for example, `config/*.example.conf` with dummy values only) requires a matrix amendment that names the path and its owner.

## Phase evidence format (`docs/agent-runs/`)

`docs/agent-runs/**` is human-owned evidence. The orchestrator assembles it, and only the human writes `human-gate.md` and `commit.txt`. `docs/agent-runs/README.md` (the evidence format) is also written only by the human. **Phase 1 bootstrap rule:** Phase 1 evidence is committed by the human, because no agent has write authority over `docs/agent-runs/**` before the Phase 1 constitution is ratified. From Phase 2 on, the orchestrator assembles evidence and the human writes `human-gate.md` and `commit.txt`. Each phase uses `docs/agent-runs/phase-NN/` (two-digit phase number):

| File | Required | Content |
|---|---|---|
| `prompt.md` | always | exact work order given to the orchestrator, verbatim |
| `agent-report.md` | always | each owning agent's final report verbatim, with every report-contract field |
| `review-report.md` | when review was required | `code-reviewer` verdict and verified findings (severity, file:line, failure input, impact, owner) |
| `test-report.md` | when testing was required | `test-engineer` exact pass/fail counts and failures |
| `repair-NN.md` | per repair loop | findings routed, owner, change summary, rerun evidence |
| `human-gate.md` | always (human-authored) | what was reviewed; what was accepted or rejected; manual changes; remaining risk; accepted commit hash |
| `commit.txt` | always | accepted commit SHA |
| `versions.md` | Phase 1 and when tooling changes | Claude Code version, model configuration when known, JVM, sbt, Scala, Terraform, AWS CLI, scalafmt versions |

Stored: work orders, final reports, deterministic command output, review findings, and human decisions. **Hidden model reasoning (chain-of-thought) is never stored**, requested, or accepted as evidence.

## Known gaps and ambiguities (pending human decision)

| ID | Gap | Current handling (fail-closed) | Decision needed |
|---|---|---|---|
| G1 | Matrix gives `factory-engineer` `.claude/**`, but the ratified Phase 0 seed lists only `.claude/agents/*`, `hooks/*`, `commands/*`, `settings.json`. | Other `.claude/**` paths are `UNASSIGNED`. `.last-test-run` is `GENERATED` and `settings.local.json` is `HUMAN`. | Confirm the narrow reading or amend the seed. |
| G2 | The Phase 1 work order asks `factory-engineer` to create `docs/agent-runs/README.md`. But the matrix makes `docs/agent-runs/**` human-owned evidence that the orchestrator assembles, excludes it from `documentation-engineer`, and the ratified seed forbids `factory-engineer` any doc other than `docs/agents.md`. | **Resolved by human decision 2026-10-28 (amendment A1):** row `docs/agent-runs/README.md` -> `HUMAN`. `factory-engineer` drafts the content outside the repository; the human commits it. | none |
| G3 | Release manifest: Phase 17 asks `documentation-engineer`, while the matrix gives "release records" to `deploy-engineer`. | Routed to `human`. | Pick one owner and a path. |
| G4 | `deploy-engineer` owns "deployment execution reports and release records" with no path. | `deploy-engineer` has no write tool. Reports are returned as final messages and archived by the orchestrator. | Ratify a path (for example `reports/deployments/**`) before granting Write. |
| G5 | Dependency ledger path is unknown until Phase 2, and the ledger is split between `build-engineer` (structure) and `dependency-updater` (values) in one file, which conflicts with one-writer-per-file. | `dependency-updater` cannot write anything yet. | After Phase 2, ratify a dedicated ledger file owned by `dependency-updater`, or document the value-only exception. |
| G6 | No agent is named for bounded cloud pipeline runs (`/run-pipeline`). Phases 4 and 6 ask module owners for local or bounded runs, and Phase 15 uses `experiment-engineer`. | Proposed: `deploy-engineer` (operator of approved scripts). Local runs stay with module owners as part of their gates. | Ratify or reassign. |
| G7 | "Designated test source trees" for `test-engineer` are not designated. Module test trees belong to module owners. | `test-engineer` writes `tests/**` only. | Designate any carve-outs (for example `modules/*/src/test/scala/adversarial/**`). |
| G8 | **Spec discrepancy.** Phase 1 probe 1 ("Ask `feature-implementer` to edit `build.sbt`") names an agent that is not in the required ScholarChain matrix. The name comes from the reference TaskForge repository. | **Decision recorded (human, 2026-10-28):** probe 1 is run literally with the name `feature-implementer`. No agent of that name exists or is created, and no other agent is substituted. The expected result is that the orchestrator reports that no such agent exists, names `build-engineer` as the owner of `build.sbt`, and returns `BLOCKED-ON human`; no agent is invoked and no edit happens. Any attempted write is refused by `enforce-ownership.sh`. The discrepancy is recorded in `docs/agent-runs/phase-01/human-gate.md`. | none (decision recorded) |
| G9 | `LICENSE`, `reports/**` other than the final report, packaging files (Dockerfiles), human gold-set labels, and the location of version records have no matrix owner. | `LICENSE` is `HUMAN`. Others are `UNASSIGNED`. | Assign paths and owners when Phase 2, 5, and 17 define them. |
| G10 | `README.md` currently holds the course specification and is owned by `documentation-engineer`, who rewrites it in Phase 17. | Spec cited by commit `d23d307`. | Human should preserve the spec (for example `docs/spec/project-spec.md`, created by `documentation-engineer` under a work order) before Phase 17. |
| G11 | Rollback has two plausible owners. | In-flight deployment rollback goes to `deploy-engineer`. Rollback of a released revision goes to `incident-responder`. | Ratify the split. |
| G12 | MCP server packages are unpinned (`uvx` latest, Docker `latest`). | Recorded risk. | Pin versions or digests after verifying them locally. |
| G13 | Phase 1 evidence (`docs/agent-runs/phase-01/`) must exist before Phase 1 is accepted. Its writers depend on the very constitution under ratification, and `commit.txt` records a human act (spec: "Human commits the accepted phase and records the commit hash"). | **Resolved by human decision 2026-10-28 (amendment A2):** row `docs/agent-runs/*/commit.txt` -> `HUMAN`, plus the Phase 1 bootstrap rule in "Phase evidence format". | none |

Proposed resolutions for G1-G13, with before/after rows and spec basis, are in the Phase 1 repair report (amendments A1-A11). G2 and G13 (amendments A1 and A2) and the G8 decision were approved by the human on 2026-10-28 and are part of the revision under ratification. None of the others is effective until the human ratifies it and restarts the session. Until then every remaining row keeps its fail-closed handling.
