# Phase 1 work orders

Phase 1 had one original work order and several follow-up instructions and repair rounds. Each is reproduced verbatim
below. Anything that could not be recovered from the session transcripts is marked as unavailable, not reconstructed.

## 1. Original work order

Source: the course project specification, `README.md` at commit `d23d307`, section "Phase 1: the factory builds the
ScholarChain factory" (lines 901-963), given to the orchestrator as the Phase 1 work order.

```text
Use the factory-engineer agent to create the complete ScholarChain agent system from the authority matrix in the course project specification.

Create:

1. CLAUDE.md
- Identify ScholarChain as a Scala 3, Hadoop, Spark, AWS, Graph-RAG project.
- Define module dependency boundaries.
- Define sbt check as the repository definition of done.
- State the one-owner-per-artifact rule.
- Require feature tests for every behavior change.
- Require provenance for every data transformation and runtime claim.
- Require secrets only through Secrets Manager or approved local placeholders.
- Require all AWS infrastructure through Terraform.
- Forbid direct cloud-console mutation as an implementation path.
- Import docs/agents.md.

2. docs/agents.md
- Transcribe the complete artifact ownership table for all required agents.
- Add a lifecycle-to-agent routing table.
- Add escalation rules for constitutional changes, data destruction, security changes, Terraform apply, deployment, expensive scale changes, and ambiguous ownership.
- State that subagents do not invoke one another and that all routing passes through the orchestrator.

3. All remaining .claude/agents/*.md files
- Use precise routing descriptions in the vocabulary of this project.
- Use the narrowest practical tools field.
- Give code-reviewer no Edit or Write tool.
- Give infra-engineer authoring and read-only cloud inspection tools but no apply or live deployment authority.
- Give deploy-engineer execution authority only through approved scripts and approved environments.
- Give test-engineer write access only to test territories.
- Require each agent to decline and route out-of-scope work.
- Include a structured report contract.

4. .claude/settings.json and hooks
- PostToolUse formatter for Scala writes.
- PreToolUse dangerous-command guard.
- Stop hook requiring current tests after source changes.
- Agent-file validation hook or script.
- Explicit allow and deny lists.
- Deny reads of secrets and .env files.
- Deny terraform apply and destroy.
- Deny force push.
- Deny destructive S3, DynamoDB, Neptune, S3 Vectors, ECR, KMS, and EMR commands.

5. .mcp.json
- GitHub access.
- AWS API inspection with no unrestricted write authority.
- Terraform documentation or provider inspection.
- AWS documentation access where available.
- Document every server's trust boundary and environment variables.

6. .claude/commands
- deploy, rollback, incident, run-pipeline, and experiment commands.
- Every command must name the responsible agent and gates.

7. docs/agent-runs/README.md
- Define the phase evidence format.
- State that hidden model reasoning is not stored.

Do not create build files or application source.

Validate every JSON file with a parser, every shell script with bash -n, all hook executable bits, all agent frontmatter, unique agent names, ownership completeness, routing collisions, routing orphans, and reviewer tool fences.

Present the complete diff, per-file authority justification, audit results, validation output, and post-ratification probe instructions. Stop for human ratification.
```

## 2. Follow-up instructions and repair-round work orders

     One subsection per instruction, in chronological order, verbatim. Any instruction that cannot be recovered
     gets the "unavailable" line instead. -->

### 2.1 Repair-01 work order (session a9fda787), verbatim

Source: `/private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/a9fda787-7fc8-4182-9643-766cf780f849/scratchpad/factory/rev5/phase-01-evidence/prompt.md`, section 2, "the repair work order as received by factory-engineer".

```text
Phase 1 FAILED an independent human-commissioned review. You are repairing your own unratified Phase 1 diff in /Users/taufeeqpatel/Desktop/ScholarChain. Nothing is committed yet. Your earlier artifacts are untracked files in the working tree, and your earlier evidence is in /private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/a9fda787-7fc8-4182-9643-766cf780f849/scratchpad/factory/rev5/ (phase-01.diff, validator.txt, cases.tsv, run-probes.sh, probe-stop.sh, probe-validator-negative.sh).

Hard rules: do not ratify, commit, push, deploy, or start Phase 2. Do not run destructive commands, read real secrets (no ~/.ssh, ~/.aws, or .env reads), or make live AWS calls. Test guards only by piping inert JSON payloads into hook scripts, using disposable fixtures in the scratchpad. Your own hooks are live in this session. If a hook blocks one of your test commands, put the payloads in scratchpad files and pipe them in rather than weakening the guard. Preserve every existing safety invariant.

STEP 1 - TRIAGE FIRST. Read the professor's spec (README.md), your Phase 0 seed (.claude/agents/factory-engineer.md), and the existing Phase 1 files. Sort each finding below into one of two buckets:
  (A) fixable under your existing ratified authority, without widening anyone's authority or changing the ownership matrix;
  (B) needs a constitutional amendment or ownership change that the human must approve first.
Then apply ONLY the bucket A fixes. For bucket B, write the exact proposed amendment text: the file, the before and after rows, and the spec basis. Put it in your report and in a scratchpad file, not in the repo. Then STOP.

Findings from the review:
1. docs/agent-runs/README.md is missing. Resolve its ownership through an explicit proposed amendment for human approval before anyone writes it. Name the proposed writer (orchestrator, human, or documentation-engineer; the documentation-engineer agent description currently claims docs/agent-runs/**, which conflicts with your G2 note, so reconcile this).
2. MAJOR: the Bash guard does not enforce file ownership. Bash mutation paths can write unauthorized files even though the equivalent Write-tool requests are blocked. Examples: > and >> redirection, tee, cp, mv, sed -i, perl -i, dd of=, install, ln, truncate, touch, rm, heredocs, python/node -c writes, git checkout/restore of paths, and `bash -c`/`sh -c`/`eval` wrappers. Fix this enforceably. Where reliable parsing is impossible, fail closed: for agents with no shell-write needs, deny Bash forms that can mutate files. Add regression probes for each form.
3. MAJOR: AWS destructive detection can be bypassed by global options before the service name, e.g. `aws --region us-east-1 s3 rm ...`, `aws --profile x --output json dynamodb delete-table`, `aws --endpoint-url ... --no-cli-pager ...`, env-var prefixes (`AWS_PROFILE=x aws ...`), `command aws`, `/usr/local/bin/aws`, `env aws`, and wrappers like `xargs aws` and `sh -c 'aws ...'`. Parse past global options and their values. Cover s3/s3api, dynamodb, neptune/neptunedb/neptune-graph, s3vectors, ecr, kms (schedule-key-deletion, disable-key), and emr (terminate-clusters, modify-instance-groups/scale). Also mirror this in the settings.json deny patterns where feasible. Add regression probes for every equivalent form.
4. MAJOR: secret-read protection does not consistently cover private-key locations: ~/.ssh/**, $HOME/.ssh, /Users/*/.ssh, id_rsa*, id_ed25519*, *.pem, *.key, ~/.aws/credentials, ~/.aws/config, ~/.gnupg, .env and .env.*, and secrets/**. Make this apply to Read/Grep/Glob tool inputs, to Bash (cat, less, head, tail, grep, cp, base64, xxd, tar, scp, and so on), and to settings.json deny rules. Add probes, using only fixture paths. Never read real files.
5. format-scala.sh reports success when scalafmt or .scalafmt.conf is missing. When a .scala file was written and scalafmt or the config is missing, it must fail with an actionable message: a non-zero exit that the harness surfaces, giving the install command and the owner of .scalafmt.conf (build-engineer). Otherwise use a verified safe fallback. It must still exit 0 harmlessly when no Scala file changed, and it must never create or modify the config. Add probes.
6. Ownership gaps G1-G12 in docs/agents.md: dependency ledger, packaging artifacts, release records/manifest, deploy reports, test territories, human evidence, unowned files, and the spec in README.md versus documentation-engineer, among others. Do not invent authority. For each gap, give a proposed resolution with the spec basis, and say whether it is bucket A (a documentation clarification only, with no authority change) or bucket B.
7. Phase 1 evidence is missing. The required prompt, agent report, validation outputs, diffs, and human approval record must be preserved under the spec's evidence structure through an authorized writer. Propose who writes what under docs/agent-runs/phase-01/ and with what content. Prepare the content in the scratchpad (e.g. scratchpad/phase-01-evidence/) for an authorized writer to commit. Do not write it into the repo unless you are the authorized writer under the current ratified matrix.
8. The professor's probe 1 names `feature-implementer`, which is not in the required agent matrix. Document the discrepancy explicitly in docs/agents.md (bucket A if it is documentation only). Propose a compliant resolution (e.g. treat it as a placeholder to be mapped to the owning module agent by human decision, or ask the professor) instead of silently substituting service-engineer, and mark it as needing a human decision.

Also double-check that code-reviewer's tool fence and the deploy allowlist are still intact after your fixes.

STEP 2 - VALIDATE after the bucket A fixes:
- Parse all JSON with python3 -m json.tool and jq.
- Run bash -n on all hooks and check their executable bits.
- Run .claude/hooks/validate-agent-files.sh.
- Re-run the full earlier probe suite plus the new regression probes. Report exact pass/fail counts, and list any probe whose expected result changed, with the reason.

STEP 3 - REPORT, using your report contract, with:
- the triage table (finding, bucket, reason, spec basis)
- the bucket A changes made, as a diff of only this repair (save the full diff to scratchpad/phase-01-repair.diff and give its sha256)
- the exact tests and commands run, with their observed output
- the proposed amendments (bucket B) in full text
- remaining blockers
- an explicit statement of whether the professor's Phase 1 acceptance criteria are satisfied (they will not be until bucket B is ratified and the evidence exists)
Then STOP for human approval.
```

### 2.2 Later repair rounds and follow-up instructions

Every later instruction is reproduced verbatim in `work-orders-and-instructions.md`, extracted from the session
transcripts. That covers every message the human typed in sessions 5a5ff524 and a9fda787, and every work order the
orchestrator delegated to a subagent. This includes the allowlist guard redesign, the C1-C5 and NEW-1..NEW-6
repair rounds, the reviewer passes, the final targeted repair round (2026-10-28), and the build-engineer
`.gitignore` work order (B6/R3).

Unavailable instructions, if any: [list them here, e.g. "Repair-round instruction for rev3: not recoverable from the preserved transcripts; not reconstructed."]

## 3. Post-restart probe session (2026-10-28)

The probe-session instructions are recorded with their outcomes in `post-restart-probes.md`.
