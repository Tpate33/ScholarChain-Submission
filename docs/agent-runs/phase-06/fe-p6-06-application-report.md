# Phase 06 FE-P6-06 application: factory-engineer report (2026-11-18)

- Work order: prompt-fe-p6-06-application.md. HEAD 692c173. Ratification read by the agent at human-gate.md line 105 (wording differs slightly from the work order's quote; same meaning).
- STATUS: BLOCKED-ON human (observation of the `--probes` summary only). Diff complete: 7 files, 313 insertions, 45 deletions.
- Files modified: .claude/hooks/guard-dangerous.sh, .claude/hooks/bash-allowlist.tsv, .claude/hooks/probes.tsv, .claude/hooks/validate-agent-files.sh, .claude/settings.json, CLAUDE.md, docs/agents.md.
- Applied: A1-A3, B0-B5 with D1, CR-P6-30 m1, m2, n1, n2, C3 probes, D1-D7. Not applied (as ordered): C1 optional deny rules, n3 rows.

## Validation (as reported)

- Baseline before edits: `validate-agent-files.sh` exit 1 `RESULT: FAIL (6 failures)` (all `shellcheck -S warning`; shellcheck now installed); `--probes` exit 1, output visible only to probes.tsv line 265.
- After: `validate-agent-files.sh` exit 1 `RESULT: FAIL (5 failures)`: `FAIL  shellcheck .claude/hooks/audit-bash-writes.sh`, `… enforce-ownership.sh`, `… lib-baseline.sh`, `… post-write.sh`, `… verify-tests-ran.sh` (hooks not touched; pre-existing). Other checks PASS: JSON, `bash -n` all 9 hooks, shellcheck guard-dangerous.sh, validate-agent-files.sh (newly passing), format-scala.sh, lib-ownership.sh; executable bits; frontmatter of 18 agents; `bash-allowlist.tsv parses (99 identity rows, 3 groups)`; `docs/agents.md Bash allowlist block matches bash-allowlist.tsv (134 rows, sha256 6e2fcfe92e95)` (full 6e2fcfe92e95a1e06accda18a98a61772a85134b3ea92557c142313c81f4dc80); guard heredoc 646 lines parses as Python; active-row tripwire check PASS for every new row.
- `--probes` after edits: exit 1; output again cut at probes.tsv line 265 (tool display cap); lines 1-265 all PASS; lines 266 onward NOT OBSERVED (pre-existing rows 266-789 and the 150 new FE-P6-06 rows at probes.tsv 790-952, corrected after CR-P6-33 N5) and all fixture probes (13 new) NOT OBSERVED. Reasoned expectation (not observed): `NOTE  888 table probes run`, `NOTE  51 fixture probes run`, the 5 shellcheck FAIL lines, `RESULT: FAIL (5 failures)`.
- `python3 -I -m json.tool` settings.json and .mcp.json exit 0; `test -x` guard and validator exit 0; one Read of a nonexistent `*.tfplan` path refused by the settings layer.
- Commands refused by the guard: one `wc -l`. **An attempted temporary local edit making `pass()` in validate-agent-files.sh print nothing (to fit the output cap) was refused by the auto-mode classifier as undermining oversight; the agent did not retry or work around it.**

## Deviations and refinements within the ratified items (as reported)

- (a) `tfplan` fence scoped to Read/Grep/Glob via a separate `PLAN_FILE` regex in `read_check`, not added to `SECRET_PATH`, because `secretish()` feeds the Bash tripwire and path-escape checks and would refuse the `shasum -a 256 <TFPLAN>` and `terraform … show -no-color tfplan` rows; fixture probes show both still ALLOW.
- (b) n1: `shellcheck scripts/cleanup.sh.bak` expected ALLOW (matches A3); `bash -n scripts/cleanup.sh.bak` BLOCK.
- (c) Grep's content `pattern` not path-checked for tfplan; Glob's `pattern` is.
- Behaviour-neutral, outside the list: validate-agent-files.sh:135 `$ft` -> `${ft}` (SC1087 error), fixture heading text, guard header comments.
- settings.json: `"Read(**/tfplan)"`, `"Read(**/*.tfplan)"` after `"Read(**/*.tfstate.*)"`; no Grep()/Glob() rules (no precedent).
- CLAUDE.md: one sentence in the Secrets invariant (state only through scripts/plan.sh; saved plans only through deploy-engineer's shasum and show rows; no direct agent read).
- Assumed Region confirmed (gate entry), so docs say "confirmed".

## Diff summary

Guard: header comments; `PLAN_FILE = re.compile(r'(^|/)([^/]*[._-])?tfplan([._-][^/]*)?(/|$)', re.I)`; RX REGION `us-east-1`, STACK, EC2FILTER, S3PREFIX; `stack(d)`; TFCHDIR and TFPLAN via `stack`; `cleanup_static_check`; `PLAN_DO`, `plan_file_check` (deny `secret-read` except Grep `pattern`), called on raw keys, realpath and normpath. Allowlist: classes comment; `@env dev`, `@env-nonprod dev`; 10 @AWS-RO rows; sparkSubmitLocal rows; infra init (ask), validate, plan inactive superseded, plan.sh (ask, git), dry-run (allow, git+args), check-submit-args (allow, git); deploy.sh `inactive:G6-phase6-human-terminal`; smoke-test, rollback, run-experiment `inactive:script-absent`; shasum and show active; 134 rows. probes.tsv: 150-row FE-P6-06 section (including n1, n2, m1 fence). validate-agent-files.sh: two fixture blocks (9 provenance probes; 4 plan-file probes). docs/agents.md: D1-D7 and m1 text.

## Privilege delta (as reported)

build-engineer and spark-engineer + sparkSubmitLocal (ask); infra-engineer + init (ask), validate, plan.sh (ask), dry-run, check-submit-args, cleanup static checks, 10 @AWS-RO; narrowed REGION and direct plan retired; deploy-engineer + shasum and show, 10 @AWS-RO, deploy.sh inactive (D1); incident-responder + 10 @AWS-RO; every identity narrowed by the tfplan Read/Grep/Glob fence. Ownership, autonomy and tools unchanged. Floor invariants intact.

## Manual action (as reported)

Human, in a Terminal at the repository root: `.claude/hooks/validate-agent-files.sh --probes | grep -E '^(FAIL|NOTE|RESULT)'` and record the lines; any FAIL other than the 5 shellcheck lines is a defect to route back before commit B. Human decision: a later factory-engineer order for the 5 pre-existing shellcheck failures.

Commit B files (exactly 7): .claude/hooks/guard-dangerous.sh, .claude/hooks/bash-allowlist.tsv, .claude/hooks/probes.tsv, .claude/hooks/validate-agent-files.sh, .claude/settings.json, CLAUDE.md, docs/agents.md (plus orchestrator evidence under docs/agent-runs/phase-06 at the human's discretion).
