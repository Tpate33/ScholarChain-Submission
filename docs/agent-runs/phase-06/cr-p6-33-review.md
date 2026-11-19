# Phase 06 CR-P6-33: code-reviewer review of the applied FE-P6-06 diff (2026-11-18)

- Read, Grep, Glob only; diff reconstructed from the 7 files and the reports. Nothing under gold/, target/ or ~/.aws.

## Verdict: APPROVE

- Scope matches the ratification (A1-A3, B0-B5, D1, C3, D1-D7; CR-P6-30 m1, m2, n1, n2); C1 and n3 not applied.
- Deviations: (a) PLAN_FILE instead of a SECRET_PATH term (guard:63-67, 572-598): necessary because secretish() feeds the token tripwire (:342-344) and path-escape check (:558); departs from the literal ratified text, **human to acknowledge**. (b) n1 flip (probes.tsv:879 ALLOW, :880 BLOCK) correct. (c) Grep content pattern exempt (guard:574) sound; Glob pattern checked. (d) SC1087 fix (validate-agent-files.sh:135) behaviour-neutral; accept with disclosure. (e) "Region confirmed" accurate (human-gate.md:101).
- Guard: fullmatch (:168); `stack()` (:185-187) rejects `..`, trailing `/`, extra segments, prod, `environments/dev/bootstrap`, nothing while `@env` empty; TFPLAN realpath check (:553-559); cleanup carve-out exact; PLAN_FILE covers `tfplan`, `x.tfplan`, `tfplan.json`, `x.tfplan.bak`, a `tfplan` directory, any case, not `TfPlanParser.scala`, on raw, realpath and normpath; shasum and show rows reach bash_check end with no decision.
- No widening: no apply, deploy, `--stage`/`--execute`; deploy.sh row `inactive:G6-phase6-human-terminal` (tsv:161); cleanup.sh only via static rows; `--profile` exactly `scholarchain-readonly`.
- Mirror 134 rows (99 identity + 35 group, hand-counted); settings.json `Read(**/tfplan)`, `Read(**/*.tfplan)` at :85-86.
- `--probes` (not observed; traced by reading): all 150 new rows (probes.tsv 790-952) and 13 new fixture probes expected PASS (some BLOCK rows pass via "Floor rule also matched", validate-agent-files.sh:309); pre-existing rows touched by the REGION pin, `@env`, carve-out and fence (131, 159-169, 224, 264-270, 371-428) do not flip. Expected only the 5 shellcheck FAIL lines, `RESULT: FAIL (5 failures)`.

## Findings (factory-engineer; none block commit B)

- m1 (minor) validate-agent-files.sh:67-68, :36-38: 5 untouched hooks fail `shellcheck -S warning` now that shellcheck is installed; `--hook` exits 2 on every constitutional write and the gate is permanently red. Not a regression (HEAD would fail the same; 6 -> 5). Follow-up work order.
- N1 (nit) guard:603-606 (mcp_check), :157-158, :558: secretish() has no tfplan term (MCP `file://…/tfplan`, PATH symlink to a tfplan). Low impact.
- N2 (nit) guard:572-577, agents.md:517: Grep glob `{tfplan,x}` / `tfpl?n` passes PLAN_FILE; residual wording relies on .gitignore, which ripgrep `--glob` overrides (Grep tool behaviour unverified). Low impact.
- N3 (nit) guard:188-189, tsv:167: TFCHDIR and the show row's literal `tfplan` get no realpath check (planted symlink needed; no agent Write can create one).
- N4 (nit) agents.md:571: "Rows whose script does not exist are `inactive:script-absent`" inaccurate (package.sh, infra smoke-test.sh, archive-evidence.sh keep other reasons; check.sh rows active).
- N5 (nit) fe-p6-06-application-report.md:12 unobserved range wording. **Fixed by the orchestrator.**

## Unverified

Grep tool and .gitignore with globs; settings.json delta beyond the two visible lines; doc sha not recomputed; `--probes` in full (macOS TMPDIR realpaths); carried TF_CLI_* / ~/.terraformrc, `terraform show` backend credentials, BASH_ENV.

Manual action: human runs `.claude/hooks/validate-agent-files.sh --probes | grep -E '^(FAIL|NOTE|RESULT)'`; accept commit B only if the FAIL lines are exactly the 5 shellcheck lines; acknowledge deviation (a).
