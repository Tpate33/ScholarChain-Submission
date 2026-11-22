# CR-REL-01 pre-publication release scan (code-reviewer, read-only, 2026-11-21, HEAD 3d9da3e)

Orchestrator-condensed record of the reviewer's final report. Records state only; approves nothing. No file was changed, no history was rewritten, no evidence removed.

Verdict: REQUEST_CHANGES; publication NOT READY. No credential or secret found in the committed tree (inferred tracked set; reviewer has no Bash). History not scanned.

| ID | Sev | Location | Issue | Owner |
|---|---|---|---|---|
| F1 | major | infra/terraform/environments/dev/env.conf:10,12,31; infra/terraform/README.md:17; 13 files in docs/agent-runs/phase-06/ (e.g. human-gate.md:94,97; aws-bootstrap-base-2026-11-19.md; ie-p6-04-report.md; quota-request-2026-11-18.md; prompt-account-checks.md) | Real AWS account ID committed (29 occurrences, 15 files). Not a credential; README.md:2943 lists account IDs under "must not share". | HUMAN decision; infra-engineer (env.conf, terraform README); evidence files HUMAN |
| F2 | minor | phase-06 records, runs/spark-dev-run-01.conf:10, env.conf:27-31 | Real resource identifiers (KMS ARN, VPC/subnet/SG IDs, cluster and instance IDs, bucket names, IAM user/role and profile names). Not secret; matters if resources still exist (base stack retained). | HUMAN; infra-engineer |
| F3 | minor | /Users/taufeeqpatel/... in 110 tracked files under docs/agent-runs/**; docs/agents.md:391; env.conf:39; phase-05/labels-import-01.md:9,26,72; infra/terraform/README.md:17 | Name, macOS username, local paths public; docs/agents.md:391 `cd` not portable for a fresh clone. | HUMAN; factory-engineer (docs/agents.md:391); infra-engineer |
| F4 | minor | docs/agent-runs/phase-05/human-gate.md:140-196 with tracked gold/entity-resolution/heldout-v1/er-review-queue.jsonl | No label file committed, but per-segment confusion counts plus per-member system outcomes allow partial inference of held-out labels (spec README.md:2941). | HUMAN |
| F5 | info | README.md:2933-2943, 2955 | Spec says private repo and lists items not to share; class-wide notice requires public. README.md is still the course spec (gap G10). | HUMAN; documentation-engineer |
| F6 | info | .gitignore:41; gold/** | No gold label file tracked; er-config.conf has dummy values. Not every review-queue row checked (guard fence). | none |
| F7 | info | rag fixtures and tests (e.g. rag-fixture-world.v1.json:204,255; CollaborationWorkflowGateAdversarialSuite.scala:656; SummaryTextInjectionAdversarialSuite.scala:35) | Prompt-injection strings are clearly scoped adversarial test data; none addresses a grader or scores. | none |
| F8 | info | whole tree | No grader-manipulation text found. Note: opening the clone in Claude Code loads CLAUDE.md and the project hooks in .claude/settings.json, which run local shell scripts. | none |
| F9 | info | build.sbt:864-882; project/SparkItRuns.scala; ER probe suites | No external file required by `sbt check`. Opt-in tasks need external state: sparkIT (~/ScholarChain-archive), run-bounded-spark.sh (archive + target jars), sparkSubmitLocal (downloads Spark), erDecisionGroupingDev (prior localPipelineDev), full dataset fetches. Document prerequisites. | documentation-engineer |
| F10 | info | .gitignore | Large local files (DBLP dump, .terraform, target/) are ignored. | none |
| F11 | info | .mcp.json, build.sbt, scripts/lib/common.sh, IdentifiersSuite.scala | No credential values; matches are variable names or synthetic test strings. | none |

Not performed: Git history scan; full review-queue row check (guard fence heldout-label-read); `.env*` existence (guard fence secret-read); tracked status and sizes (no git ls-files).

Read-only commands proposed for the human's Terminal (not run; no results claimed):
```sh
git ls-files -- gold
git ls-files -- '.env*' '*.tfstate' '*.tfstate.*' '*tfplan*' '*.pem' '*.key' '*credentials*'
git log --all --name-only --format= -- gold | sort -u
git log --all --diff-filter=A --name-only --format= | sort -u | grep -Ei '(^|/)\.env|tfstate|tfplan|\.pem$|id_rsa|credentials|er-gold-labels'
git log --all -p -- . ':(exclude)gold' | grep -nE 'AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|BEGIN [A-Z ]*PRIVATE KEY|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{20,}|aws_secret_access_key|aws_session_token|X-Amz-Signature'
git log --all --oneline -S<AWS_ACCOUNT_ID>   # substitute the 12-digit account ID locally; never commit the literal
git log --all --oneline -G'[0-9]{12}' -- infra docs/agent-runs
git rev-list --objects --all | git cat-file --batch-check='%(objecttype) %(objectname) %(objectsize) %(rest)' | awk '$1=="blob" && $3>1000000' | sort -k3 -n
gitleaks git --redact -v .
trufflehog git file://. --no-update --only-verified
```
Caution: `git log -p | grep` prints matching lines; check label files by name only.
