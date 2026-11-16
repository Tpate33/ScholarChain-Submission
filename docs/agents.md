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
| `docs/agent-runs/**/*.sbt` | `UNASSIGNED` | amendment F1 (Phase 2 review): no sbt build definition in phase evidence |
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
| `**/*.sbt` | `UNASSIGNED` | amendment F1 (Phase 2 review): sbt loads every subproject `*.sbt` as build policy, so only `build.sbt` and `project/**` above are writable (by `build-engineer`); first match wins, so the constitutional `.claude/**` rows above still take precedence |
| `gold/**` | `HUMAN` | amendment G9-a (FE-P5-03): spec Phase 5 gate "Gold labels are human-reviewed" (README.md at d23d307); human decisions 2026-11-04 (blind labeling; labels kept out of policy development and tuning), 2026-11-05 (freeze membership before labeling), 2026-11-06 (recorded manifest SHA-256 for final held-out metrics), 2026-11-13 (decisions-15: human-owned config, one human-recorded hash file, separate dev and held-out runs). Holds, per split `gold/entity-resolution/<split>-v1/`, the membership manifest, the freeze record `er-gold-membership.jsonl.sha256` (exactly the `shasum -a 256` line plus LF), the evaluation config `er-config.conf` (config.v2, dummy non-secret values), the blind view, the review queue and the labels; written only by the human in a terminal; placed after `**/*.sbt` so `gold/**/*.sbt` stays `UNASSIGNED` (F1) |
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

**sbt build definitions (amendment F1, Phase 2 review).** sbt loads every `*.sbt` file in a subproject base directory as settings for that project, so such a file could rewrite the definition of done. Only the root `build.sbt` and files under `project/**` are build definitions, and both belong to `build-engineer`. Every other `*.sbt` path is `UNASSIGNED` and fails closed for every identity, including module owners, `test-engineer`, `documentation-engineer`, the orchestrator under `docs/agent-runs/**`, and `build-engineer` itself, **except** paths under the constitutional rows `.claude/agents/**`, `.claude/hooks/**`, and `.claude/commands/**`. Those remain `factory-engineer`, because constitutional rows match first. `.claude` is not an sbt project base, and `project/BuildGuard.scala` would abort the sbt load if such a file existed. A new subproject build file requires a matrix amendment. `project/BuildGuard.scala` (owned by `build-engineer`) enforces the same rule inside `sbt check`.

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

The **global floor** applies to every identity, including the orchestrator. It covers secret reads, held-out gold-label reads (G9-c: no Read of `gold/**/heldout*/er-gold-labels*`, no Grep under `gold/` or repository-wide with a gold/heldout/label glob, no `git diff`/`git show` that names a `gold/` path other than the non-label files or would print a held-out label file), held-out runs (D15(c) and decisions-21: `sbt goldSetEvalHeldout` and `sbt localPipelineHeldout` are human-only, and the held-out evaluation output kept under `target/` is not readable), terraform apply/destroy/state mutation, force push, recursive forced deletion, destructive AWS/database operations, unreviewed EMR/ECS scale changes, constitution writes by non-factory identities, test-marker forgery, `scripts/cleanup.sh` (human only), and **any Bash command that is not an active row of the Bash allowlist** (files are written with Write/Edit under the same ownership map; shell syntax, inline interpreter code, and unlisted programs are refused by default).

### Bash allowlist (machine-checked)

Default deny. A Bash call runs only if it passes the character gate in `guard-dangerous.sh` and matches an ACTIVE row for the acting identity. This table mirrors `.claude/hooks/bash-allowlist.tsv` row for row (120 rows; source sha256 `71034e28f6b00faecfc09be9ab13e3204b207b80b78c034a7cc760fcf040d707`). Group rows (`@GIT`, `@SBT-ALL`, `@AWS-RO`) are included by identities whose row names the group. `code-reviewer` has no rows. INACTIVE rows never match; each names the decision that activates it. The environment lists (`@env`, `@env-nonprod`) are empty until Phase 12 ratifies them.

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
| `hadoop-engineer` | active | allow | - | `sbt localPipelineDev` | Phase 5 bounded local pipeline, dev split (amendment G9-b, FE-P5-03; decisions-15 items 1 and 3; BE-P5-02): root task forking ErSplitPipelineMain dev on fixed committed inputs; 90 s bound; writes only modules/hadoop/target/local-pipeline/er-candidates/dev/ and target/local-runs/dev/ |
| `entity-resolution-engineer` | active | allow | - | `@GIT` | matrix row |
| `entity-resolution-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `entity-resolution-engineer` | active | allow | - | `sbt <SBT:entityResolution> <SBT:entityResolution>? <SBT:entityResolution>? <SBT:entityResolution>?` | own-module tasks (E2) |
| `entity-resolution-engineer` | active | allow | - | `sbt goldSetEvalDev` | Phase 5 dev-split evaluation diagnostics (amendment G9-b, FE-P5-03; decisions-15 items 1 and 2; BE-P5-02): human-owned gold/entity-resolution/dev-v1/ files, freeze file and --expect-split=dev; never final metrics; writes only target/local-runs/dev/ |
| `entity-resolution-engineer` | active | allow | - | `sbt erDecisionGroupingDev` | Phase 6 dev-only ER decision then grouping run (amendment FE-P6-01; BE-P6-01, BE-P6-02; reviewed CR-P6-09 check 6, CR-P6-10, CR-P6-11 APPROVE at commit 7b72681): root task forking DecisionRunMain then GroupingRunMain on the committed modules/entity-resolution/local-runs/ inputs and the one complete localPipelineDev attempt; 45 s per stage; refuses SCHOLARCHAIN_CFG_*, held-out spellings, gold/ and target/streams/ paths, a code scope (build.sbt project modules/core modules/entity-resolution) not clean against HEAD, and a manifest codeVersion other than HEAD or an ancestor with only the two manifests changed since; create-once, never overwrites a run; writes only target/local-runs/dev/ |
| `spark-engineer` | active | allow | - | `@GIT` | matrix row |
| `spark-engineer` | active | allow | - | `@SBT-ALL` | sbt check |
| `spark-engineer` | active | allow | - | `sbt <SBT:sparkAnalytics> <SBT:sparkAnalytics>? <SBT:sparkAnalytics>? <SBT:sparkAnalytics>?` | own-module tasks (E2) |
| `spark-engineer` | active | allow | - | `sbt sparkIT` | Phase 6 opt-in local Spark integration task (amendments FE-P6-04, FE-P6-05; build BE-P6-03 and BE-P6-03 repair 01 committed in e8c6ecc; reviewed CR-P6-16, CR-P6-17 APPROVE; row reviewed CR-P6-18 APPROVE): root task, no arguments, over the verified dev archive at <user.home>/<archive.root.home-relative> from project/spark-it.properties holding exactly the three pinned run directories (curated, decisions, assignments); NOFOLLOW layout check and bounded NOFOLLOW tree walk; refuses held-out, gold/, target/streams/ and target/local-runs/heldout segments and any inherited SCHOLARCHAIN_CFG_*; one forked JVM, working directory the attempt, with inherited AWS_*, SCHOLARCHAIN_*, GIT_* and MUNIT_* removed; 120 s bound; the task's own files only under modules/spark/target/spark-it/ (created if absent), in one create-once attempt-<n>/ (run-config.properties, runner/, tmp/ as java.io.tmpdir, out/, junit-result.txt, result.txt), never reused or deleted by the task, removed by sbt clean; sbt also writes its usual build output (meta-build under project/target/, core and sparkAnalytics Compile, Test and SparkIT classes under modules/core/target/ and modules/spark/target/) and task logs under target/streams/, which carry the child's output; code in the fork (tests, Spark, Hadoop) is opaque and can write wherever the OS user can; never part of check; writes no test marker; PASS only from the runner's nonce-bound per-class result file |
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
| Hadoop MapReduce jobs: parse, normalize, block, team extraction, pair expansion, export | `hadoop-engineer` | dev local sample run (`sbt localPipelineDev`) or EMR sample run, deterministic outputs, sbt check (the held-out local run is the human's, decisions-21) |
| Entity resolution: features, policy, thresholds, LLM adjudication, gold-set evaluation tooling | `entity-resolution-engineer` | dev gold-set diagnostics, the human's held-out gold-set evaluation (D15(c)), match-policy review |
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
| Ratification, human gate records, commits, gold-set membership generation, freeze records, evaluation configs and labels (`gold/**`), held-out local pipeline runs (decisions-21), held-out gold-set evaluation runs (D15(c)), cleanup execution | `human` | human judgement |
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
- **Phase 5:** in a terminal (preferably with no Claude Code session open), generate each split's frozen membership manifest and blind view into `gold/entity-resolution/<split>-v1/`; write `er-config.conf`; write the freeze record with `shasum -a 256 er-gold-membership.jsonl > er-gold-membership.jsonl.sha256` and record the value in `human-gate.md`; commit membership, blind view, freeze record and config before any label exists; label blindly from the blind view only; generate the review queue with the same config. **Run both held-out tasks yourself** (`sbt localPipelineHeldout` before any held-out labels file exists, `sbt goldSetEvalHeldout` after labelling), each as a plain batch sbt command in your regular Terminal after the pre-run checklist in [Held-out runs](#held-out-runs-phase-5-build-checks-accepted-limitations-and-terminal-checklist): never with `!` inside a Claude Code session, never via `sbt --client`, an sbt server, BSP/IDE, or the interactive sbt shell (D15(c), decisions-21). After the held-out pipeline run, archive its attempt directory outside the repository and record its runId and `summary.txt` sha256 in `human-gate.md` (D10(i)). Before you accept the held-out evaluation as final, record only its exit status, runId and sha256 values in `human-gate.md`, and keep its metrics and listings out of the repository and out of every work order. After any held-out result, a change to policy, features or thresholds needs disclosure in `human-gate.md` and a fresh held-out set before new final claims (D3(c)). Held-out labels stay gitignored until the held-out evaluation is accepted (D5 = L1). No agent or orchestrator writes `gold/**`, reads held-out labels, runs either held-out task, or reads the held-out evaluation.
- **Phase 12:** review the Terraform plan and cost estimate. Sign the plan before any apply.
- **Phase 14:** verify the plan checksum, account, Region, cost bounds, and the absence of unexplained destroy or replace. Approve the deployment command.
- **Phase 15:** approve experiment manifests and cost bounds.
- **Cleanup:** approve and run the cleanup and evidence-archival location.

### Held-out runs (Phase 5): build checks, accepted limitations, and Terminal checklist

Both held-out tasks, `sbt localPipelineHeldout` and `sbt goldSetEvalHeldout`, are human-only (D15(c), decisions-21; amendment FE-P5-07). No agent and not the orchestrator can start either: the guard refuses them by name (rules `heldout-pipeline-human-only` and `heldout-eval-human-only`), `settings.json` denies them, and no allowlist row names them. Both tasks also refuse inside any agent Bash call, because there is no terminal there. The human runs each as a plain batch sbt command from the repository root in a regular Terminal: never with `!` inside a Claude Code session, never through `sbt --client` or sbtn, an sbt server, BSP or an IDE, or the interactive sbt shell. No hook runs in that Terminal, so the checklist below is the human's own control. The held-out pipeline's output stays readable by agent Read (see R6 coverage below and the held-out residuals paragraph under Enforcement layers); the human inspects the run's Terminal output and records what `human-gate.md` needs.

**R6 coverage (decisions-22).** R6 protects held-out **evaluation** output only. Its fences are the guard's Read and Grep refusals (rule `heldout-eval-output-read`) for `target/streams/**/goldsetevalheldout` and `target/local-runs/heldout` (any case), its Grep refusal for `target`, `target/streams` and everything under it, and `target/local-runs`, and the `settings.json` Read denies `Read(./target/streams/**/goldSetEvalHeldout/**)` and `Read(./target/local-runs/heldout/**)`. The held-out **pipeline**'s candidate data and `summary.txt` under `modules/hadoop/target/local-pipeline/er-candidates/heldout/`, and its sbt envelope log (the `localPipelineHeldout` task directory under `target/streams/`), remain agent-readable: no rule refuses an agent Read of them, and Grep is refused only where the `target/streams` Grep fence applies. No labels or metrics are in them; the labels file is read only by `sbt goldSetEvalHeldout`, and the pipeline's own JVM scratch under `target/local-runs/heldout/` lies inside the R6 fence. No new fence is added in this round (decisions-21, decisions-22).

**What the build checks** (D3(b) as implemented in `project/LocalRuns.scala`, owner `build-engineer`; BE-P5-04, BE-P5-05; reviewed in CR-P5-06). Before it forks any JVM, each held-out task refuses unless all of the following hold, in this order:

1. **Launch (BE-2), checked first.** The command came from the batch command line (command channel none; the interactive shell's `console0` and every `network-N` channel of a thin client, BSP or IDE refuse); the sbt launcher arguments contain none of `--server`, `--detach-stdio`, `--client`, `-client`, `--jvm-client`, `-bsp`, `--bsp`, `shell`; `shell` is not queued; a console is present (JDK 22 and later: `Console.isTerminal()` is true, and a JDK 22 or later without that method refuses; earlier JDKs: `System.console()` is non-null and `jdk.console` is unset); and `/dev/fd/1` and `/dev/fd/2` are character devices other than `/dev/null` (an unreadable file type refuses).
2. **Index flags.** `git ls-files -v -z` over the scope `build.sbt project modules tests datasets gold` lists at least one tracked file and only `H` entries; `S` (skip-worktree), lower-case tags (assume-unchanged) and `M` (unmerged) refuse.
3. **Changes and ignored paths.** One call, `git status --porcelain=v1 -z --ignored=matching --untracked-files=all` over the same scope. Any modified, staged, deleted, renamed or untracked path refuses. Every ignored path must equal, as an exact string, an entry of the allow set: the `target/` directory of each sbt project whose base is in the scope (`modules/*/target/`, `tests/target/`), `project/target/`, `project/project/target/`, `datasets/downloads/` (kept by the human, decisions-21), and, for `goldSetEvalHeldout` only, `gold/entity-resolution/heldout-v1/er-gold-labels.jsonl`. Every other ignored path refuses, wherever its ignore rule comes from (any `.gitignore`, `.git/info/exclude`, `core.excludesFile`), including every `.DS_Store` (refusal kept by the human, decisions-21), `project/metals.sbt`, `project/project/metals.sbt` and `project/.bloop/`. Consequently `localPipelineHeldout` refuses while the held-out labels file exists.
4. **Root `.gitignore`.** A regular, symlink-free file, committed at HEAD and byte-identical to it (`git hash-object --no-filters`), for both held-out tasks.
5. **Git hygiene and failure.** Every inherited `GIT_*` variable is removed from the build's git processes (stdin closed, stderr discarded); the clean-tree calls run with `--no-optional-locks -c core.fsmonitor=false -c core.untrackedCache=false`; each git call is bounded at 30 s and killed on timeout. Any git start failure, non-zero exit, timeout or incomplete output refuses (fail closed).

Task-specific checks follow. The pipeline requires its committed, unmodified run manifest and sample ledgers and a bounded, symlink-free samples tree. The evaluation refuses any inherited `SCHOLARCHAIN_CFG_*` (checked right after the launch check), requires the four committed gold files byte-identical to HEAD, and requires the labels file to be a regular, symlink-free, git-ignored file. Both tasks send the forked child's output straight to the Terminal, never through an sbt log.

**Remaining gaps** (the build does not check these; "checklist" marks items the checklist below covers at run time). No guard expansion is made in Phase 5 (decisions-21).

1. BE-6: git configuration and attributes (`core.checkStat`, `core.trustctime`, clean and smudge filters, `core.attributesFile`, `.git/info/attributes`, the default global attributes file) can make `git status` report a clean tree while tracked bytes differ (accepted, see below; checklist).
2. Files placed by hand inside the allowed `target/` directories, for example a `core-site.xml` in `modules/hadoop/target/scala-*/test-classes/`, which is on `hadoop/Test/fullClasspath`; whether sbt's resource copy removes it is unverified (checklist, optional item).
3. Root launcher files `.jvmopts` and `.sbtopts` (and `.sbtrc` and the global `sbtopts` files), outside the scope (checklist).
4. `SBT_OPTS`, `JAVA_OPTS`, `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS` in sbt's environment (checklist).
5. JDK selector files (`.java-version`, `.tool-versions`, `.sdkmanrc`, mise files) (checklist).
6. `~/.sbt` global settings and plugins (checklist).
7. Dependency caches outside the repository (Coursier, Ivy, `~/.sbt/boot`); a modified cached jar would be on the classpath. Not covered.
8. Global git configuration (`~/.gitconfig`, `$XDG_CONFIG_HOME/git/config`), which the build's git calls read and the Bash audit does not see (checklist).
9. Pseudo-terminal wrappers (`script`, tmux logging, IDE terminals) pass the launch check, and their logs can capture held-out output (checklist: do not use them).
10. Submodule ignore settings: a committed gitlink with `submodule.<name>.ignore=all` would hide changes inside it, because no `--ignore-submodules=none` is passed (checklist).
11. Compile-before-check race: task dependencies compile before the clean-tree check, so an edit made and reverted in between is not seen; likewise a change after the check, during the run (checklist: no other writer open).
12. The allow path (console present, both descriptors character devices, channel none, nothing queued) has not been observed in a real Terminal; the first held-out run is its first observation, and the task refuses if a signal differs.

**BE-6: accepted limitation for Phase 5 (decisions-21).** CR-P5-06 finding BE-6 (minor) is accepted for Phase 5 without a build change. Rationale: both failure inputs need a deliberate change to git configuration or attributes by someone with write access to `.git/config`, `~/.gitconfig` or an attributes file. No agent Write/Edit and no allowlisted path argument can write `.git/**`; sbt-run code could, but `audit-bash-writes.sh` reports changes to `.git/config` and `.git/info/attributes` after the fact, while `~/.gitconfig` and `~/.config/git/attributes` are outside the audit. Both held-out tasks are now run only by the human, who performs the checklist below immediately before each run; its items 2-6 detect both CR-P5-06 failure inputs, including a raw-byte comparison of every tracked file in scope with HEAD and an index-to-HEAD comparison. And the human decided no guard expansion this round. Residual: a change made between the checklist and the run. Owner of the permanent fix: `build-engineer`, in a later phase, by work order: compare raw bytes (`git hash-object --no-filters --stdin-paths`) of every `git ls-tree -r -z HEAD` entry in scope with its HEAD blob (refusing symlink and gitlink modes explicitly), require `git diff-index --cached --quiet HEAD` over the scope, and keep the untracked and ignored listing (CR-P5-06 suggested fix; pinning `core.checkStat` and `core.trustctime` alone does not close the filter route).

**Deferred (decisions-21).** The HD-1 duplicate-source check stays in `modules/hadoop` (`ErSplitPipeline.checkManifest`, refused before any job; `hadoop-engineer`). The broader core unique-source validation (at most one snapshot per source in `inputSnapshots`; `domain-engineer`, with contract tests, after confirming that no stage needs two snapshots of one source) is deferred to a later work order. There is no Phase 5 change.

**Pre-run Terminal checklist.** Run these from the repository root, in the Terminal you will use for the run, immediately before each held-out task, and stop at the first unexpected result. They are for the human's Terminal only and are not agent Bash shapes. Expected results marked "from tool documentation" were not observed on this machine.

1. No other writer or sbt server. Close every Claude Code session on this repository and quit any IDE, Metals or BSP client that has it open.
   ```sh
   cd /Users/taufeeqpatel/Desktop/ScholarChain
   ps -ax -o pid=,command= | grep -E '[x]sbt\.boot|[s]bt-launch|[s]btn|[m]etals|[b]loop|[s]bt\.ForkMain'
   ls -la project/target/active.json
   ```
   Expected: `ps` prints nothing that belongs to this repository; `ls` prints `No such file or directory` (an sbt server writes this file while it runs, from tool documentation; if it exists and no sbt process runs, it is stale and you may delete it).
2. Git configuration and gitlinks (BE-6).
   ```sh
   git config --show-origin --get-regexp 'core\.(checkstat|trustctime|attributesfile|fsmonitor|excludesfile|hookspath|untrackedcache)|filter\.|^submodule\.'
   git ls-files -s -- build.sbt project modules tests datasets gold | grep -vE '^100(644|755) '
   ```
   Expected: no output from either (the first exits with status 1), or only `git config` entries you recognise and accept. Any `core.checkstat`, `core.trustctime`, `core.attributesfile` or `filter.` entry: stop and remove it. Any line from the second command is a symlink or gitlink in scope: stop.
3. Attribute and exclude files.
   ```sh
   ls -la .git/info/attributes ~/.config/git/attributes
   git ls-files -- .gitattributes '*/.gitattributes'
   cat .git/info/exclude
   ```
   Expected: `No such file or directory` for both attributes files (if `XDG_CONFIG_HOME` is set, check `$XDG_CONFIG_HOME/git/attributes` instead of `~/.config/git/attributes`); no output from `git ls-files`; `.git/info/exclude` holds only git's default comment lines (each starting with `#`) or entries you added and recognise.
4. Attributes in effect over the whole scope, and on representative files.
   ```sh
   git ls-files -- build.sbt project modules tests datasets gold | git check-attr --stdin -a
   git check-attr -a -- build.sbt project/LocalRuns.scala modules/hadoop/src/main/scala/scholarchain/hadoop/JobParams.scala
   ```
   Expected: no output from either.
5. Index flags (what D3(b) item 2 checks).
   ```sh
   git ls-files -v -- build.sbt project modules tests datasets gold | grep -v '^H '
   ```
   Expected: no output.
6. Raw bytes equal HEAD (detects both BE-6 failure inputs at the moment you run it).
   ```sh
   git diff-index --cached --quiet HEAD -- build.sbt project modules tests datasets gold && echo "index equals HEAD"
   cmp <(git ls-tree -r HEAD -- build.sbt project modules tests datasets gold | awk '{print $3}') <(git ls-tree -r HEAD -- build.sbt project modules tests datasets gold | cut -f2 | git hash-object --no-filters --stdin-paths) && echo "work tree bytes equal HEAD"
   test "$(git hash-object --no-filters -- .gitignore)" = "$(git rev-parse HEAD:.gitignore)" && echo ".gitignore equals HEAD"
   ```
   Expected, exactly: `index equals HEAD`, `work tree bytes equal HEAD`, `.gitignore equals HEAD`. A `cmp` difference, a `hash-object` error or a missing line: stop.
7. Remove `.DS_Store` files in scope (the build refuses them and Finder recreates them, so do this just before the run).
   ```sh
   find build.sbt project modules tests datasets gold -name .DS_Store -print
   find build.sbt project modules tests datasets gold -name .DS_Store -delete
   find build.sbt project modules tests datasets gold -name .DS_Store -print
   ```
   Expected: the first command may list files (build-engineer reported `project/.DS_Store` and `project/project/.DS_Store` on 2026-11-14); the third prints nothing.
8. Status over the scope, exactly as the build runs it (D3(b) item 3).
   ```sh
   git status --porcelain=v1 --ignored=matching --untracked-files=all -- build.sbt project modules tests datasets gold
   ```
   Expected: only lines starting with `!! `, each one of `datasets/downloads/`, `modules/<name>/target/` (core, entity-resolution, hadoop, rag, services, spark, storage), `project/target/`, `project/project/target/` and `tests/target/`, plus, before `goldSetEvalHeldout` only, `gold/entity-resolution/heldout-v1/er-gold-labels.jsonl`. Not every target line need appear. Any other line means the task will refuse. Use `--ignored=matching`: with `--untracked-files=all`, plain `--ignored` lists every file inside each ignored directory.
9. Held-out labels file and run order.
   ```sh
   ls -la gold/entity-resolution/heldout-v1/er-gold-labels.jsonl
   git check-ignore -v gold/entity-resolution/heldout-v1/er-gold-labels.jsonl
   ```
   Expected before `sbt localPipelineHeldout`: `No such file or directory` (the pipeline refuses while the labels file exists). Run the pipeline before you create labels; to re-run it later, first move the labels file outside the repository (its sha256 is already recorded) and move it back before the evaluation. Expected before `sbt goldSetEvalHeldout`: a regular file (the `ls` mode starts with `-`, not `l`), and `git check-ignore -v` names the L1 line of `.gitignore`.
10. Launcher, JVM-option and JDK-selector files outside the build's scope.
    ```sh
    ls -la .jvmopts .sbtopts .sbtrc .java-version .tool-versions .sdkmanrc .mise.toml mise.toml ~/.sbtrc /etc/sbt/sbtopts /opt/homebrew/etc/sbtopts /usr/local/etc/sbtopts
    ```
    Expected: `No such file or directory` for every path, or only files you recognise (inspect them with `cat`). The `.sbtrc` and global `sbtopts` locations are from tool documentation.
11. Environment, names only, so no value is printed.
    ```sh
    env | grep -E '^(SBT_|JAVA_|_JAVA_OPTIONS|JDK_JAVA_OPTIONS|GIT_|SCHOLARCHAIN_|AWS_)' | cut -d= -f1
    ```
    Expected: no output, or only names you set on purpose and accept (for example `JAVA_HOME`). Unset (`unset NAME`) any of `SBT_OPTS`, `SBT_NATIVE_CLIENT`, `JAVA_OPTS`, `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, every `GIT_*`, every `SCHOLARCHAIN_*` and every `AWS_*` in this Terminal first. The tasks strip `GIT_*` from their git calls and `AWS_*` and `SCHOLARCHAIN_*` from the forked JVM, and `goldSetEvalHeldout` refuses `SCHOLARCHAIN_CFG_*`, but the sbt JVM itself reads the JVM-option variables.
12. Global sbt settings and plugins.
    ```sh
    find ~/.sbt -maxdepth 3 \( -name '*.sbt' -o -name '*.scala' \) -not -path '*/boot/*' -print
    ```
    Expected: no output, or only files you recognise (global settings and plugins load into every build).
13. Optional: unexpected files in the allowed classpath directories.
    ```sh
    find modules -path '*/target/scala-*/*classes/*' -type f ! -name '*.class' ! -name '*.tasty' | sort
    find modules -path '*/src/*/resources/*' -type f | sort
    ```
    Expected: every file in the first list has a counterpart in the second (same module, same name relative to `classes/` or `test-classes/` and `resources/`). An unexpected file, for example a `core-site.xml`: stop.
14. Run one task, in the planned order.
    ```sh
    time sbt -Dsbt.supershell=false localPipelineHeldout
    time sbt -Dsbt.supershell=false goldSetEvalHeldout
    ```
    Keep stdin, stdout and stderr on the Terminal: no `<`, `>`, `|`, `tee`, `script`, tmux logging or logging IDE terminal (a pseudo-terminal wrapper passes the launch check but can write held-out output to a file). Expected first task lines: `<task>: plain batch sbt with stdout and stderr on a terminal (JDK ...)`, `<task>: git_head=... git_worktree=...`, then `<task>: clean tree in build.sbt project modules tests datasets gold (decision D3(b)): ...`. sbt exits 0 on success and non-zero on any refusal or failure, and the error line names the reason. Never run `sbt clean`, or delete `modules/hadoop/target/`, in this Terminal while `modules/hadoop/target/local-pipeline/er-candidates/heldout/` holds an attempt you have not archived (D10); no hook protects your Terminal.
    Supershell: `-Dsbt.supershell=false` only turns off sbt's progress display, which could redraw over child output written straight to the Terminal (CR-P5-06). In `launchProblems` no signal reads a `-D` property except `jdk.console`, and the flag is not a refused launcher argument, so by reading the code it does not change the verdict. Unverified: not observed in a real Terminal. If a task refuses with the flag, read the printed reason, then retry without it.

## Enforcement layers

| Layer | File | Event | Purpose |
|---|---|---|---|
| Permissions | `.claude/settings.json` `permissions` | every tool call | explicit allow / ask / deny lists; bypass mode disabled |
| Dangerous-command guard | `.claude/hooks/guard-dangerous.sh` | PreToolUse `Bash\|Read\|Grep\|Glob\|mcp__aws-api__.*` | Bash: default-deny allowlist (character gate, per-identity rows in `bash-allowlist.tsv`, token tripwires, script provenance, held-out gold-label fence on git diff/show, human-only held-out evaluation and held-out pipeline, held-out run protection); Read/Grep/Glob: secret-read, the held-out gold-label fence (G9-c) and the held-out evaluation output fence (D15(c) R6); aws-api MCP: destructive and secret-dump verbs. Never emits allow |
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
3. **Tripwires.** Token-level floor checks on the matched argv: terraform apply/destroy/state, git push and force flags, destructive and mutating AWS verbs, EMR scale or termination, `scripts/cleanup.sh`, test-marker forgery, held-out gold-set evaluation and held-out pipeline (`sbt goldSetEvalHeldout`, D15(c); `sbt localPipelineHeldout`, decisions-21), secret names, and dangerous programs. They fire only if the data file is mis-edited.
4. **Path resolution.** Path arguments must resolve inside the repository, outside `.git`, and away from secret material (symlinks included).
5. **Script provenance.** A row that runs a repository script requires the script and every file in its script directory (`scripts/**`, or `datasets/scripts/**`) to be committed and byte-identical to `HEAD` (raw bytes, `git hash-object --no-filters`, so clean filters cannot mask changes; same executable bit; no untracked, ignored, deleted, or symlinked entries), and the call must run from the repository root. dataset-engineer's own scripts return `ask` instead of deny. The provenance git calls share a 10-second budget; a timeout denies. Factory hook scripts are exempt; they already run as hooks.
6. **Identity.** `agent_type` absent means the orchestrator. Present but empty, null, the string `null`, a non-string, or not one of the 18 ratified agents: every checked tool call is denied. `enforce-ownership.sh` applies the same rule to Write/Edit/MultiEdit/NotebookEdit (shared function `scholarchain_identity_checked` in `lib-ownership.sh`); the audit and `post-write.sh` treat such identities as owning nothing.
7. **Build-output precondition.** Every `sbt` row is denied (rule `gitignore-missing`, naming `build-engineer` and decision B6) while the repository root has no regular `.gitignore` file.
8. **Held-out label fence (G9-c).** `git diff`/`git show` may not name a `gold/` path other than the exact membership, freeze, config, blind-view and queue files, and a content-printing `git diff`/`git show` whose scope includes `gold/` is refused when git lists a held-out label file in that scope.
9. **Held-out run protection (D10(ii)).** Every `sbt` command with a `clean` token is denied (rule `heldout-run-protected`) while `modules/hadoop/target/local-pipeline/er-candidates/heldout/` exists under the project root the hook receives (`CLAUDE_PROJECT_DIR`). `validate-agent-files.sh --probes` tests both states in its temporary fixture repository, never in the real tree: `service-engineer` `sbt clean check` with the directory absent (no deny) and present (`heldout-run-protected`) (FE-P5-06, decisions-18).
10. **Human-only held-out runs (D15(c), decisions-21).** Any `sbt` command with a token containing `goldSetEvalHeldout` (rule `heldout-eval-human-only`) or `localPipelineHeldout` (rule `heldout-pipeline-human-only`), in any case, is denied for every identity, the orchestrator included, before allowlist matching; when both appear, the evaluation rule is reported. `--validate-allowlist` fails if an active row names either. The human runs them in a regular Terminal (see Held-out runs under Human gates); hooks and permission rules do not apply there.

The guard emits `deny` (exit 2, with the rule, the identity's allowed shapes, and what to do instead) or `ask`, or no decision. It never emits `allow`, so the `ask` rules in `settings.json` still apply. Agents are expected to write files only with Write/Edit (checked by `enforce-ownership.sh`); no allowlist row is itself a file-writing shell command. The opaque tools named in the allowlist (sbt and the test code it runs, approved scripts) can still write anywhere the OS user can; `audit-bash-writes.sh` detects such writes to git-visible paths only after the fact (see Limitations without an OS-level sandbox).

**Residual risk.** Opaque tools on the allowlist (`sbt`, including test code the calling agent wrote, and approved scripts) can write anything the OS user can. `audit-bash-writes.sh` detects content changes after the fact by sha256, for git-visible, non-ignored paths and the `.git` control files, both during a Bash call (attributed to the caller) and between calls (reported UNATTRIBUTED at the next call). It reports them with exit 2 and tells the agent to stop with `BLOCKED-ON human`. It cannot revert them. Gitignored paths and paths outside the repository are not audited. Bash-changed Scala the identity owns is sent through `format-scala.sh`, and a missing scalafmt or `.scalafmt.conf` yields exit 2. Global aliases or functions in the user's shell profile are outside agent control. The OS-level Bash sandbox is not enabled. `code-reviewer` review of the phase diff remains the final line of defense.

**Held-out label fence (G9-c) and human-only held-out runs (D15(c), decisions-21) residuals.** sbt-run code (including tests) can read any file the OS user can, so code-reviewer reviews any code that names `gold/`. No identity can start `sbt goldSetEvalHeldout` or `sbt localPipelineHeldout`, and the forked child output of both reaches only the human's Terminal. R6 protects held-out evaluation output only: the Read/Grep fences on `target/streams/**/goldsetevalheldout` and `target/local-runs/heldout`, plus the `settings.json` Read denies for the same two paths. The held-out pipeline's candidate data and `summary.txt` under `modules/hadoop/target/local-pipeline/er-candidates/heldout/`, and its sbt envelope log, remain agent-readable; no labels or metrics are in them (decisions-22; no new fence this round). Output the human pastes into a session, a work order or `docs/agent-runs/**` is not fenced; the Phase 5 human gate governs that. Under D5 = L1 the labels are gitignored, so they are not audited and (expected, verified after restart) not searched by repository-wide Grep. Copies of label bytes outside `gold/**` are not fenced.

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

`gold/entity-resolution/<split>-v1/er-config.conf` is evaluation configuration with dummy, non-secret values (amendment G9-a). It is not a secret placeholder; a config that ever needs a secret value requires its own placeholder amendment.

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
| G9 | `LICENSE`, `reports/**` other than the final report, packaging files (Dockerfiles), and the location of version records have no matrix owner. Gold set: **resolved by human decisions 2026-11-14 (amendments FE-P5-03, FE-P5-04, FE-P5-05, FE-P5-06, FE-P5-07):** `gold/**` -> `HUMAN` (G9-a); `sbt localPipelineDev` (hadoop-engineer) and `sbt goldSetEvalDev` (entity-resolution-engineer) replace the B10 placeholders, and `sbt localPipelineHeldout` and `sbt goldSetEvalHeldout` are human-only with no row (G9-b, D15(c), decisions-21); held-out labels and held-out evaluation output fenced from every agent tool (G9-c, R6); held-out run protected from `sbt clean` (D10(ii)), tested by two validator fixture checks (FE-P5-06). | `LICENSE` and `gold/**` are `HUMAN`. Others are `UNASSIGNED`. | Assign the remaining paths when Phase 17 defines them. |
| G10 | `README.md` currently holds the course specification and is owned by `documentation-engineer`, who rewrites it in Phase 17. | Spec cited by commit `d23d307`. | Human should preserve the spec (for example `docs/spec/project-spec.md`, created by `documentation-engineer` under a work order) before Phase 17. |
| G11 | Rollback has two plausible owners. | In-flight deployment rollback goes to `deploy-engineer`. Rollback of a released revision goes to `incident-responder`. | Ratify the split. |
| G12 | MCP server packages are unpinned (`uvx` latest, Docker `latest`). | Recorded risk. | Pin versions or digests after verifying them locally. |
| G13 | Phase 1 evidence (`docs/agent-runs/phase-01/`) must exist before Phase 1 is accepted. Its writers depend on the very constitution under ratification, and `commit.txt` records a human act (spec: "Human commits the accepted phase and records the commit hash"). | **Resolved by human decision 2026-10-28 (amendment A2):** row `docs/agent-runs/*/commit.txt` -> `HUMAN`, plus the Phase 1 bootstrap rule in "Phase evidence format". | none |

Proposed resolutions for G1-G13, with before/after rows and spec basis, are in the Phase 1 repair report (amendments A1-A11). G2 and G13 (amendments A1 and A2) and the G8 decision were approved by the human on 2026-10-28 and are part of the revision under ratification. None of the others is effective until the human ratifies it and restarts the session. Until then every remaining row keeps its fail-closed handling.
