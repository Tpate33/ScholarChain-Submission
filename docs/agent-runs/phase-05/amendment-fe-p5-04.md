# FE-P5-04: D15(c) revision to amendment FE-P5-03 (factory-engineer report, verbatim)

Archived by the orchestrator, 2026-11-14. Draft only; not ratified, not applied. Work order basis: decisions-16.md. Every `.tsv` line below is meant to be TAB-separated; this transcription may show spaces.

---

FE-P5-04: the D15(c) revision to amendment FE-P5-03, plus every change it forces. This is a proposal only. I wrote no files, committed nothing and activated nothing. Below, "the archive" means docs/agent-runs/phase-05/amendment-fe-p5-03.md, and line numbers refer to it unless I say otherwise.

Every `.tsv` line below is meant to be TAB-separated ASCII. If a copy shows spaces, put the TABs back.

## Key findings

**1. sbt keeps a copy of the held-out output (new risk, I expect but have not observed this).** In BE-P5-02's draft, `LocalRuns.goldSetEval` sends GoldSetEvalMain's output through `streams.value.log`. sbt normally saves a task's log under the root project's `target/streams/_global/goldSetEvalHeldout/_global/streams/out` (that file is what `sbt last` replays).
- So even when only the human runs `goldSetEvalHeldout`, its metrics and false-positive/false-negative listings could be left on disk where any agent's Read could reach them.
- Under D15(c), that has to be closed in two places:
  - **build-engineer (out of scope for me):** for the held-out split, send the child process's output straight to the terminal (`ProcessBuilder.inheritIO()` or `redirectOutput(INHERIT)`), not through `log`.
  - **Constitutional backstop (R6 below, recommended):** deny Read and Grep of that output.
- The human should check it once with a dev run: after `sbt goldSetEvalDev`, list `target/streams/_global/goldSetEvalDev/`.

**2. Running it "in a Terminal" is what matters.** Permission rules and hooks only govern Claude Code tool calls, so a settings.json deny cannot block the human's own shell. But if the human runs the task with `!` inside a Claude Code session, the output lands in that session's context and the orchestrator sees it. That defeats D15(c). The procedure text below therefore says: a separate Terminal, with no Claude Code session as the channel.

**3. Not covered by D15:** `sbt localPipelineHeldout` stays with hadoop-engineer under `ask`. Its output is candidate pairs, not labels. If the human also wants the held-out pipeline run to be human-only, that is a separate decision.

---

## 1. Revised section 6 entries

Replace the D2 bullet (archive line 573):
```diff
-- **D2:** held-out evaluation is run by `entity-resolution-engineer` with `ask`. decisions-15 item 1 ("Require approval for ... held-out evaluation") and the settled rows. Human-only runs (old H2) stay possible by declining the prompt.
+- **D2 (superseded by D15(c); human decision, decisions-16, 2026-11-14):** only the human runs held-out evaluation, in a separate Terminal and never through a Claude Code session. Before activation the human uses the `GoldSetEvalMain` runMain form; after activation, `sbt goldSetEvalHeldout`. No agent and not the orchestrator has an allowlist row for it, and the guard refuses it for every identity with `heldout-eval-human-only`. decisions-15 item 1 ("Require approval for ... held-out evaluation") is met in its strongest form: instead of approving an agent's run, the human performs it. The held-out pipeline run (`sbt localPipelineHeldout`) stays with `hadoop-engineer` under `ask`.
```
Replace the D15 bullet and its diff block (archive lines 608-616):
```diff
-- **D15 = new: held-out evaluation output reaches the agent** (BE risk 7).
-  - (a) Accept it with ask, plus one role sentence (below).
-  - (b) A summary-only held-out mode (an ER flag plus a BE task change), with error listings only in a human Terminal run.
-  - (c) Human-only held-out evaluation.
-  - **(a).** Optional constitutional hunk, a narrowing, in `.claude/agents/entity-resolution-engineer.md`, Non-negotiable law 5:
+- **D15: held-out evaluation output reaches the agent** (BE risk 7). **Decided (human, decisions-16, 2026-11-14): (c) human-only held-out evaluation.** This supersedes D2 and my earlier recommendation (a). Options (a) and (b) are withdrawn. Consequences, all part of this amendment:
+  1. no allowlist row for `sbt goldSetEvalHeldout` (section 1);
+  2. a named guard refusal `heldout-eval-human-only` for every identity, checked before allowlist matching, plus a tripwire so that `--validate-allowlist` fails if an active row ever names the task (section 3.4);
+  3. settings S3 denies `sbt goldSetEvalHeldout` instead of asking (section 5);
+  4. the `entity-resolution-engineer` role file keeps law 5's no-tuning sentence and gains law 9: never run, request or read held-out evaluation or its output before the human accepts it (section 6 hunk below);
+  5. recommended R6: no agent Read or Grep of the held-out output sbt keeps under `target/` (section 3.4);
+  6. the Phase 5 human gate: the human runs the evaluation in a separate Terminal, records only the exit status, runId and hashes before acceptance, and keeps metrics and listings out of the repository and out of every work order until acceptance (section 2.6).
+  Constitutional hunk, a narrowing, in `.claude/agents/entity-resolution-engineer.md` (exact diff in item 4 of this report):
```
Other section 6 text:
- D4 becomes settled: "no dev label set for now; `goldSetEvalDev` stays an active row and refuses without dev files".
- D3, D5, D8, D10, D12, D13 and D14 become settled at the preferred options: (b)+(c); L1; S1+S2+S3; (i)+(ii); allow+S1; D; no.

## 2. Allowlist

The `entity-resolution-engineer sbt goldSetEvalHeldout` (ask) row is **dropped**. Archive line 100 is now replaced by **one** row.

bash-allowlist.tsv, final section 1 replacement set:
- Line 96 is replaced by two rows, unchanged from FE-P5-03 1.1: hadoop-engineer `sbt localPipelineDev` (allow) and `sbt localPipelineHeldout` (ask).
- Line 100 is replaced by exactly this one row (justification unchanged from FE-P5-03):
```
entity-resolution-engineer	active	allow	-	sbt goldSetEvalDev	Phase 5 dev-split evaluation diagnostics (amendment G9-b, FE-P5-03; decisions-15 items 1 and 2; BE-P5-02): human-owned gold/entity-resolution/dev-v1/ files, freeze file and --expect-split=dev; never final metrics; writes only target/local-runs/dev/
```
docs/agents.md mirror: apply the FE-P5-03 1.2 diff without its last `+` line (the goldSetEvalHeldout row):
```diff
-| `entity-resolution-engineer` | inactive:B10-alias-pending | allow | - | `sbt goldSetEval` | Phase 5 gold-set evaluation |
+| `entity-resolution-engineer` | active | allow | - | `sbt goldSetEvalDev` | Phase 5 dev-split evaluation diagnostics (amendment G9-b, FE-P5-03; decisions-15 items 1 and 2; BE-P5-02): human-owned gold/entity-resolution/dev-v1/ files, freeze file and --expect-split=dev; never final metrics; writes only target/local-runs/dev/ |
```
The intro line becomes `(120 rows; source sha256 \`<NEW-SHA256>\`)`.

Counts:
- The table has **120 rows** (was 121).
- Identity rows: **95** (was 96); 3 groups.
- Expected validator lines: `bash-allowlist.tsv parses (95 identity rows, 3 groups)` and `matches bash-allowlist.tsv (120 rows, sha256 <first 12 hex>)`.

Explicit deny: default deny alone would refuse the command, but with the generic `no-allowlist-entry` message, whose tail suggests asking factory-engineer for a new shape. That is the wrong route. I therefore propose the named rule `heldout-eval-human-only` (hunk in section 3.4 below). It is a pure narrowing.

## 3. Section 3 revisions (guard)

### 3.2: replace the "Still reads them" bullet and the "Compensating controls" ask item
```diff
-- **Still reads them:** `entity-resolution-engineer`'s `sbt goldSetEvalHeldout` (ask) reads the labels inside the forked JVM. That is intended: sbt is an opaque tool, and no fence applies inside it. The build's `git hash-object` also reads the label bytes but prints nothing.
+- **Still reads them:** only the human's own Terminal run of `sbt goldSetEvalHeldout` (D15(c)) reads the labels, inside the forked JVM. No agent or orchestrator call can start it: the named rule `heldout-eval-human-only` refuses it. The build's `git hash-object` also reads the label bytes but prints nothing.
-  - `ask` on every held-out run, so the human approves each one;
+  - held-out evaluation is human-only (D15(c)): no identity can start it, and its output goes to the human's Terminal only (BE inheritIO change; R6 backstop);
```

### 3.3: replace the paragraph
```diff
-`goldSetEvalHeldout` passes GoldSetEvalMain output through the sbt log, so entity-resolution-engineer sees held-out metrics and false-positive and false-negative listings. The fence cannot stop that, by design. The controls are the per-run ask, and D15 (my recommendation: accept it with ask, plus a one-sentence role rule that held-out results are never used to change policy). The stronger alternatives are a summary-only held-out mode (D15 b) or human-only held-out evaluation (D15 c).
+Resolved by D15(c). No agent runs `goldSetEvalHeldout`, so its output never enters an agent's tool result. One residual remains: sbt saves task logs (expected at `target/streams/_global/goldSetEvalHeldout/_global/streams/out`; to be observed). This is closed by build-engineer sending the held-out child output straight to the terminal (out of scope) and backstopped by R6.
```

### 3.4: hunks to add to the guard-dangerous.sh diff (on top of FE-P5-03 3.4)

```diff
@@ header, after the G9-c comment lines @@
+# Bash: sbt goldSetEvalHeldout is human-only (D15(c), FE-P5-04): refused for every identity before allowlist
+# matching (rule heldout-eval-human-only), and a tripwire so --validate-allowlist fails any active row naming it.
+# Read/Grep: held-out evaluation output that sbt keeps under target/ is fenced (R6, rule heldout-eval-output-read).
@@ G9-c block after SEG_BAD: replace the comment and HL_DO @@
-# G9-c (FE-P5-03): held-out human gold labels reach no agent tool; only the held-out evaluation command
-# (sbt goldSetEvalHeldout, inside sbt) reads them. Patterns apply to lower-cased repo-relative paths.
+# G9-c (FE-P5-03): held-out human gold labels reach no agent tool; only the held-out evaluation, which the
+# human runs in a Terminal (sbt goldSetEvalHeldout, D15(c)), reads them. Patterns apply to lower-cased repo-relative paths.
-HL_DO = ('Held-out labels are read only by the held-out evaluation command (sbt goldSetEvalHeldout, human-approved). '
+HL_DO = ('Held-out labels are read only by the held-out evaluation, which the human runs in a Terminal (sbt goldSetEvalHeldout, D15(c)). '
@@ after GIT_VIEW / HL_DO (R6 constants) @@
+HELDOUT_OUT_READ = re.compile(r'^target/(streams/(.+/)?goldsetevalheldout|local-runs/heldout)(/|$)')
+HELDOUT_OUT_GREP = re.compile(r'^target(/streams(/.*)?|/local-runs(/heldout(/.*)?)?)?$')
+HO_DO = ('Held-out evaluation output is for the human only (decision D15(c)). Read or Grep dev output (target/local-runs/dev/, '
+         'the goldSetEvalDev streams) or your own module instead; if held-out results are needed, return STATUS: BLOCKED-ON human.')
+HE_DO = ("Held-out gold-set evaluation is human-only (decision D15(c)): the human runs 'sbt goldSetEvalHeldout' in a separate Terminal, "
+         "outside every Claude Code session. Do not run, request, or retry it; entity-resolution-engineer may run 'sbt goldSetEvalDev'. "
+         "Name the held-out run as the human's NEXT GATE, or return STATUS: BLOCKED-ON human.")
@@ directly before def tripwire(toks): @@
+def heldout_eval_bash(toks):
+    low = [t.lower() for t in toks]
+    if os.path.basename(low[0]) == 'sbt' and any('goldsetevalheldout' in t for t in low[1:]):
+        return ('heldout-eval-human-only', 'sbt goldSetEvalHeldout prints held-out metrics and false-positive and false-negative '
+                'listings, so no agent and not the orchestrator runs it (decision D15(c)); no identity has an allowlist row for it.', HE_DO)
+    return None
+
@@ first lines of tripwire(), after "s, prog = ..." @@
+    he = heldout_eval_bash(toks)
+    if he:
+        return he
@@ bash_check: after the cwd-outside-repo check, before "hits = [...]" @@
+    he = heldout_eval_bash(toks)
+    if he:
+        deny(*he)
@@ gold_check: inside "for rel in rels:", after the two G9-c checks (R6, recommended) @@
+        if tool == 'Read' and HELDOUT_OUT_READ.search(rel):
+            deny('heldout-eval-output-read', 'Read %r: sbt keeps held-out evaluation output (metrics, false-positive and false-negative listings) there; it is for the human only (D15(c)).' % v, HO_DO)
+        if tool == 'Grep' and (HELDOUT_OUT_GREP.search(rel) or HELDOUT_OUT_READ.search(rel)):
+            deny('heldout-eval-output-read', 'Grep %r could search held-out evaluation output kept under target/ (D15(c)).' % v, HO_DO)
```

Order and effects, from reading `bash_check` and `main`:
- `invalid-identity`, `unknown-identity`, `reviewer-read-only`, the character gate and `cwd-outside-repo` still fire first. So code-reviewer, feature-implementer, empty `agent_type`, `;`/`&&`, a double space and quotes keep their existing rules.
- The rule matches only when the program's basename is `sbt`, so `git log -- …goldSetEvalHeldout…` is unaffected.
- `validate()`: the active rows' literals (`sbt goldSetEvalDev`, `sbt <SBT:…>`) do not trip the rule, so `--validate-allowlist` still passes. If someone adds an active goldSetEvalHeldout row, it now fails.
- R6 is a Read/Grep-only narrowing. If the human drops R6, also drop the R6 probes and the R6 parts of the docs hunks.

### D10(ii) and R4
- D10(ii) is included as drafted in FE-P5-03 3.4.
- R4 (the `fnmatch` import, `SAFE_GREP_TYPES`, `LABEL_SAMPLES` and the R4 block) is **not** applied, because D5 = L1. The R4 probe block in archive lines 518-527 is dropped.

### 3.5: revised docs/agents.md hunks for G9-c and D15(c)
```diff
@@ Global floor paragraph (replaces the FE-P5-03 3.5 version) @@
-... It covers secret reads, terraform apply/destroy/state mutation, ...
+... It covers secret reads, held-out gold-label reads (G9-c: no Read of `gold/**/heldout*/er-gold-labels*`, no Grep under `gold/` or repository-wide with a gold/heldout/label glob, no `git diff`/`git show` that names a `gold/` path other than the non-label files or would print a held-out label file), held-out evaluation (D15(c): `sbt goldSetEvalHeldout` is human-only, and its output kept under `target/` is not readable), terraform apply/destroy/state mutation, ...
@@ Bash enforcement item 3 @@
-3. **Tripwires.** Token-level floor checks on the matched argv: terraform apply/destroy/state, git push and force flags, destructive and mutating AWS verbs, EMR scale or termination, `scripts/cleanup.sh`, test-marker forgery, secret names, and dangerous programs. They fire only if the data file is mis-edited.
+3. **Tripwires.** Token-level floor checks on the matched argv: terraform apply/destroy/state, git push and force flags, destructive and mutating AWS verbs, EMR scale or termination, `scripts/cleanup.sh`, test-marker forgery, held-out gold-set evaluation (`sbt goldSetEvalHeldout`, D15(c)), secret names, and dangerous programs. They fire only if the data file is mis-edited.
@@ Bash enforcement, after item 7 (D10(ii) included, so no "(only with D10(ii))" prefix) @@
+8. **Held-out label fence (G9-c).** `git diff`/`git show` may not name a `gold/` path other than the exact membership, freeze, config, blind-view and queue files, and a content-printing `git diff`/`git show` whose scope includes `gold/` is refused when git lists a held-out label file in that scope.
+9. **Held-out run protection (D10(ii)).** Every `sbt` command containing `clean` is denied (rule `heldout-run-protected`) while `modules/hadoop/target/local-pipeline/er-candidates/heldout/` exists.
+10. **Human-only held-out evaluation (D15(c)).** Any `sbt` command with a token containing `goldSetEvalHeldout` (any case) is denied for every identity, the orchestrator included (rule `heldout-eval-human-only`), before allowlist matching. `--validate-allowlist` fails if an active row names it. The human runs it in a separate Terminal; hooks and permission rules do not apply there.
@@ Enforcement layers, Dangerous-command guard row @@
+| ... | Bash: default-deny allowlist (character gate, per-identity rows in `bash-allowlist.tsv`, token tripwires, script provenance, held-out gold-label fence on git diff/show, human-only held-out evaluation); Read/Grep/Glob: secret-read, the held-out gold-label fence (G9-c) and the held-out evaluation output fence (D15(c) R6); aws-api MCP: destructive and secret-dump verbs. Never emits allow |
@@ after the Residual risk paragraph (replaces the FE-P5-03 version) @@
+**Held-out label fence (G9-c) and human-only evaluation (D15(c)) residuals.** sbt-run code (including tests) can read any file the OS user can, so code-reviewer reviews any code that names `gold/`. Held-out evaluation output reaches only the human's Terminal: no identity can start `sbt goldSetEvalHeldout`, and R6 fences the copy sbt may keep under `target/`. Output the human pastes into a session, a work order or `docs/agent-runs/**` is not fenced; the Phase 5 human gate governs that. Under D5 = L1 the labels are gitignored, so they are not audited and (expected, verified after restart) not searched by repository-wide Grep. Copies of label bytes outside `gold/**` are not fenced.
```

## 4. Settings S3 (revised; S1 and S2 unchanged)
```diff
@@ ask @@
       "Bash(./scripts/archive-evidence.sh *)",
+      "Bash(sbt localPipelineHeldout)",
       "Bash(aws *)",
@@ deny @@
       "Bash(./scripts/cleanup.sh*)",
+      "Bash(sbt goldSetEvalHeldout)",
+      "Bash(sbt *goldSetEvalHeldout*)",
       "Bash(*/.ssh/*)",
```
- `Bash(sbt goldSetEvalHeldout)` moves from ask to **deny**. The second line catches forms with other tokens; how its multi-wildcard pattern matches can only be observed after restart, like the existing ones. The guard stays authoritative.
- **It does not block the human's Terminal run.** `.claude/settings.json` permission rules and hooks are checked only on Claude Code tool calls. A zsh in Terminal.app runs `sbt` directly and never reads them. This is the same reason `scripts/cleanup.sh` is denied in settings yet stays human-runnable.
- The human must not use `!` bash mode inside a Claude Code session for this. Whatever the permission behaviour of `!` is (I have not verified it), its output would enter the session context and reach the orchestrator.

Optional S2 extension, paired with R6:
```diff
       "Read(./gold/**/heldout*/er-gold-labels*)",
+      "Read(./target/streams/**/goldSetEvalHeldout/**)",
+      "Read(./target/local-runs/heldout/**)",
```

## 5. entity-resolution-engineer role file (exact diff)

Law 5's sentence still applies. It governs what happens after acceptance, when results become known, and D3(c). Law 9 is new. Both are narrowings.
```diff
-5. Gold-set labels are human-authored. Never generate, edit, or infer them, and never let evaluation labels leak into matching logic.
+5. Gold-set labels are human-authored. Never generate, edit, or infer them, and never let evaluation labels leak into matching logic. Never change policy, features, or thresholds in response to held-out evaluation output.
 6. Precision, recall, F1, confusion matrix, and coverage are reported separately for researcher and work resolution and by match path. They are never hidden behind one aggregate.
 7. Thresholds and policy are versioned configuration, not hardcoded constants.
 8. Every behavior change ships with a feature test. `sbt check` must pass.
+9. Held-out gold-set evaluation is human-only. Never run, request, or schedule `sbt goldSetEvalHeldout` or any `GoldSetEvalMain` run on a held-out split. Before the human has accepted a held-out evaluation as final, never read held-out labels or its output (metrics, confusion matrices, false-positive or false-negative listings), wherever they are stored. Your evaluation runs are `sbt goldSetEvalDev` diagnostics, always labelled dev. If a work order needs held-out results, return `STATUS: BLOCKED-ON` with `BLOCKED-ON OWNER: human`.
@@ Procedure @@
-3. Run `sbt check` and the gold-set evaluation the work order names.
-4. Report the policy version, features, thresholds, model and prompt versions, confusion matrix, unresolved rate, threshold sensitivity, and representative false positives and false negatives.
+3. Run `sbt check` and, when the work order names it, `sbt goldSetEvalDev`. Held-out evaluation is the human's (law 9).
+4. Report the policy version, features, thresholds, model and prompt versions, confusion matrix, unresolved rate, threshold sensitivity, and representative false positives and false negatives, from dev diagnostics labelled dev. Final held-out metrics come only from the human's run.
@@ Refusal boundaries @@
-... infrastructure (`infra-engineer`), or human gold labels (human). You never invoke other agents.
+... infrastructure (`infra-engineer`), or human gold labels and held-out evaluation runs (human). You never invoke other agents.
```
The frontmatter (name, description, tools) is unchanged, and the description does not overlap any other routing trigger.

## 6. Probes

Of the 190 FE-P5-03 lines, **25 change**; all others are unchanged. In the new lines below, the label `[G9-b]` becomes `[D15c]` and the last field becomes `heldout-eval-human-only`.

Line 317 (old):
```
ASK	guard-dangerous.sh	[G9-b] entity-resolution-engineer sbt goldSetEvalHeldout (active, ask)	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	sbt goldSetEvalHeldout
```
New:
```
BLOCK	guard-dangerous.sh	[D15c] entity-resolution-engineer sbt goldSetEvalHeldout (human-only, no row)	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-human-only
```
Comment line 375 and lines 376-385 and 387-393: in each old line the last field is `no-allowlist-entry` and the label starts `[G9-b]`. Line 386 (code-reviewer, `reviewer-read-only`) is unchanged. New block:
```
# --- D15(c) every other identity, sbt goldSetEvalHeldout (human-only)
BLOCK	guard-dangerous.sh	[D15c] factory-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "factory-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] build-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "build-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] domain-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "domain-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] dataset-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "dataset-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] hadoop-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "hadoop-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] spark-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "spark-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] storage-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "storage-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] rag-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "rag-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] service-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "service-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] test-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "test-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[G9-b] code-reviewer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "code-reviewer"}	reviewer-read-only
BLOCK	guard-dangerous.sh	[D15c] infra-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "infra-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] deploy-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "deploy-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] experiment-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "experiment-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] documentation-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "documentation-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] incident-responder sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "incident-responder"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] dependency-updater sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "dependency-updater"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] ORCHESTRATOR sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}}	heldout-eval-human-only
```
Lines 397, 398, 401, 403, 404, 406 and 414. Each old line ends in `no-allowlist-entry`; the new lines are:
```
BLOCK	guard-dangerous.sh	[D15c] entity-resolution-engineer case sbt goldsetevalheldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldsetevalheldout"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] entity-resolution-engineer case sbt GoldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt GoldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] entity-resolution-engineer chained dev then held-out tasks	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalDev goldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] entity-resolution-engineer held-out task twice	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout goldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] entity-resolution-engineer sbt check goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt check goldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] entity-resolution-engineer trailing --expect-split=dev	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout --expect-split=dev"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-human-only
BLOCK	guard-dangerous.sh	[D15c] entity-resolution-engineer absolute sbt path	{"tool_name": "Bash", "tool_input": {"command": "/usr/local/bin/sbt goldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-human-only
```
Lines 410-413 (shell-syntax-gate), 415 (runMain, `no-allowlist-entry`), 417 (`unknown-identity`) and 418 (`invalid-identity`) are unchanged, because those checks fire before the new rule.

Appended, D15(c) core (2 lines):
```
# --- D15(c) project-scoped form and sbt-only scope
BLOCK	guard-dangerous.sh	[D15c] entity-resolution-engineer project-scoped entityResolution/goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt entityResolution/goldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-human-only
ALLOW	guard-dangerous.sh	[D15c] control: ORCHESTRATOR git log naming a goldSetEvalHeldout path (rule is sbt-only)	{"tool_name": "Bash", "tool_input": {"command": "git log --oneline -- docs/goldSetEvalHeldout.md"}}	
```
Appended, R6 (8 lines, only if R6 is adopted):
```
# --- D15(c) R6 held-out evaluation output under target/
BLOCK	guard-dangerous.sh	[D15c R6] entity-resolution-engineer Read held-out streams log	{"tool_name": "Read", "tool_input": {"file_path": "@ROOT@/target/streams/_global/goldSetEvalHeldout/_global/streams/out"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-output-read
BLOCK	guard-dangerous.sh	[D15c R6] ORCHESTRATOR Read held-out streams log	{"tool_name": "Read", "tool_input": {"file_path": "@ROOT@/target/streams/_global/goldSetEvalHeldout/_global/streams/out"}}	heldout-eval-output-read
BLOCK	guard-dangerous.sh	[D15c R6] entity-resolution-engineer Read held-out scratch	{"tool_name": "Read", "tool_input": {"file_path": "@ROOT@/target/local-runs/heldout/eval-jvm-1/hs_err_pid1.log"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-output-read
BLOCK	guard-dangerous.sh	[D15c R6] entity-resolution-engineer Grep root target	{"tool_name": "Grep", "tool_input": {"pattern": "precision", "path": "@ROOT@/target"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-output-read
BLOCK	guard-dangerous.sh	[D15c R6] ORCHESTRATOR Grep target/streams	{"tool_name": "Grep", "tool_input": {"pattern": "precision", "path": "@ROOT@/target/streams"}}	heldout-eval-output-read
BLOCK	guard-dangerous.sh	[D15c R6] entity-resolution-engineer Grep held-out scratch	{"tool_name": "Grep", "tool_input": {"pattern": "x", "path": "@ROOT@/target/local-runs/heldout"}, "agent_type": "entity-resolution-engineer"}	heldout-eval-output-read
ALLOW	guard-dangerous.sh	[D15c R6] control: Read dev streams log	{"tool_name": "Read", "tool_input": {"file_path": "@ROOT@/target/streams/_global/goldSetEvalDev/_global/streams/out"}, "agent_type": "entity-resolution-engineer"}	
ALLOW	guard-dangerous.sh	[D15c R6] control: Read dev scratch	{"tool_name": "Read", "tool_input": {"file_path": "@ROOT@/target/local-runs/dev/x.log"}, "agent_type": "entity-resolution-engineer"}	
```
Totals:
- FE-P5-03 lines: 190, still 190, with 25 changed.
- New total: **649 table probes** (647 + 2) without R6, or **657** with R6.
- The R4 block stays out (D5 = L1).
- Expected validator line: `NOTE 649 table probes run` (or 657).

## 7. Section 7, revised

Before activation, add to step 8:
```diff
-8. With the same export: `sbt "entityResolution/runMain ... GoldSetEvalMain ... --expect-split=heldout"`. Exit 0 = final, 2 = refused, 3 = below the minimum. Then `unset SCHOLARCHAIN_CONFIG_FILES`.
+8. Human only (D15(c)), in a separate Terminal with no Claude Code session used as the channel (never `!` inside a session). With the same export: `sbt "entityResolution/runMain scholarchain.entityresolution.evaluation.GoldSetEvalMain $G/er-gold-membership.jsonl $G/er-review-queue.jsonl $G/er-gold-labels.jsonl --manifest-sha256-file=$G/er-gold-membership.jsonl.sha256 --expect-split=heldout"`. Exit 0 = final, 2 = refused, 3 = below the minimum. Then `unset SCHOLARCHAIN_CONFIG_FILES`. Record in `human-gate.md` only the exit status, the printed membership, config and labels sha256 values, and the pipeline runId. Keep the metrics and listings outside the repository and out of every work order until you accept the held-out evaluation as final; from then on D3(c) applies.
```
After activation, step 8:
```diff
-- Step 8 becomes `sbt goldSetEvalHeldout`: no export, and no `SCHOLARCHAIN_CFG_*` may be set. It needs all five gold files committed (L2), or four committed plus the ignored labels file (L1 after the BE change). It is run by `entity-resolution-engineer` (ask) or by the human in a Terminal.
+- Step 8 becomes `time sbt goldSetEvalHeldout`, run **only by the human** in a separate Terminal (D15(c)); every agent and orchestrator call is refused with `heldout-eval-human-only`, and settings denies it. No export, and no `SCHOLARCHAIN_CFG_*` may be set. It needs four gold files committed plus the ignored labels file (D5 = L1, after the BE change). Recording rules as in step 8 above. After the run, confirm that `target/streams/_global/goldSetEvalHeldout/` holds no evaluation output (the BE inheritIO change); otherwise archive and delete it in the Terminal before the next session.
```
Who may run what after activation:
```diff
-- hadoop-engineer: `sbt localPipelineDev` (allow) and `sbt localPipelineHeldout` (ask).
-- entity-resolution-engineer: `sbt goldSetEvalDev` (allow) and `sbt goldSetEvalHeldout` (ask).
-- Every other identity, the orchestrator included: none of the four.
-- No agent: any runMain form, any `ReviewQueueMain` mode, or any `gold/**` write.
+- hadoop-engineer: `sbt localPipelineDev` (allow, S1) and `sbt localPipelineHeldout` (ask, S3).
+- entity-resolution-engineer: `sbt goldSetEvalDev` (allow, S1) only.
+- Human only, in a Terminal: `sbt goldSetEvalHeldout` (D15(c)), every runMain form, every `ReviewQueueMain` mode, and every `gold/**` write.
+- Every other identity, the orchestrator included: none of the four tasks; `sbt goldSetEvalHeldout` is refused for all with `heldout-eval-human-only`.
```

### Section 2.6, revised docs hunks
```diff
@@ Lifecycle routing, human row @@
+| Ratification, human gate records, commits, gold-set membership generation, freeze records, evaluation configs and labels (`gold/**`), held-out gold-set evaluation runs (D15(c)), cleanup execution | `human` | human judgement |
@@ Lifecycle routing, entity-resolution row (gate column only) @@
-| Entity resolution: features, policy, thresholds, LLM adjudication, gold-set evaluation tooling | `entity-resolution-engineer` | gold-set evaluation, match-policy review |
+| Entity resolution: features, policy, thresholds, LLM adjudication, gold-set evaluation tooling | `entity-resolution-engineer` | dev gold-set diagnostics, the human's held-out gold-set evaluation (D15(c)), match-policy review |
@@ Human gates, Phase 5 (replaces the FE-P5-03 text) @@
+- **Phase 5:** in a terminal (preferably with no Claude Code session open), generate each split's frozen membership manifest and blind view into `gold/entity-resolution/<split>-v1/`; write `er-config.conf`; write the freeze record with `shasum -a 256 er-gold-membership.jsonl > er-gold-membership.jsonl.sha256` and record the value in `human-gate.md`; commit membership, blind view, freeze record and config before any label exists; label blindly from the blind view only; generate the review queue with the same config; approve every held-out pipeline run (permission `ask`). **Run the held-out evaluation yourself** (`sbt goldSetEvalHeldout`) in a separate Terminal, never through a Claude Code session (D15(c)). Before you accept it as final, record only its exit status, runId and sha256 values in `human-gate.md`, and keep its metrics and listings out of the repository and out of every work order. Held-out labels stay gitignored until the held-out evaluation is accepted (D5 = L1). No agent or orchestrator writes `gold/**`, reads held-out labels, or runs or reads the held-out evaluation.
@@ Known gaps, G9 row (replaces the FE-P5-03 text) @@
+| G9 | `LICENSE`, `reports/**` other than the final report, packaging files (Dockerfiles), and the location of version records have no matrix owner. Gold set: **resolved by human decisions <RATIFICATION-DATE> (amendments FE-P5-03, FE-P5-04):** `gold/**` -> `HUMAN` (G9-a); `sbt localPipelineDev`/`sbt localPipelineHeldout` (hadoop-engineer) and `sbt goldSetEvalDev` (entity-resolution-engineer) replace the B10 placeholders, and `sbt goldSetEvalHeldout` is human-only with no row (G9-b, D15(c)); held-out labels and held-out evaluation output fenced from every agent tool (G9-c, R6). | `LICENSE` and `gold/**` are `HUMAN`. Others are `UNASSIGNED`. | Assign the remaining paths when Phase 17 defines them. |
```
FE-P5-03 2.5 narrative: change "`goldSetEval*`: reads gold/** and writes only scratch" to "`goldSetEvalDev` (ER) and `goldSetEvalHeldout` (human only): read gold/** and write only scratch under `target/local-runs/<split>/`".

## 8. Section 9, revised privilege delta rows
```diff
-| entity-resolution-engineer | +`sbt goldSetEvalDev` (allow); +`sbt goldSetEvalHeldout` (ask): held-out labels are read only inside that approved JVM, and its output reaches ER; the inactive `sbt goldSetEval` row is removed; optional D15 role sentence | widened within B10; the role sentence narrows |
+| entity-resolution-engineer | +`sbt goldSetEvalDev` (allow, S1). No held-out evaluation row; the guard refuses `sbt goldSetEvalHeldout` by name (D15(c)). The inactive `sbt goldSetEval` row is removed. Role: law 5 sentence plus new law 9 (never runs, requests or reads held-out evaluation or its output before acceptance); with R6, no Read/Grep of held-out output under `target/` | dev half widened within B10; the held-out half of the B10 intent withheld and moved to the human (narrowed) |
-| All 18 agents and the orchestrator | lose Read of held-out labels, ... with R4, repository-wide Grep without a safe glob or type; with D10(ii), `sbt clean check` while the held-out run exists | narrowed |
+| All 18 agents and the orchestrator | lose Read of held-out labels, Grep under `gold/` or at the root with a gold/heldout/label glob, and git diff/show naming non-OK gold paths or printing held-out label files; any `sbt` command naming `goldSetEvalHeldout` (named refusal); with R6, Read/Grep of held-out evaluation output under `target/`; with D10(ii), any `sbt` command containing `clean` while the held-out run exists. R4 not applied (L1) | narrowed |
-| Settings | S1 removes the prompt for the owners' two dev commands; S2 and S3 add deny and ask rules | S1 widened (within the guard); S2 and S3 narrowed |
+| Settings | S1 removes the prompt for the owners' two dev commands; S2 adds deny rules (optionally the R6 paths); S3 adds ask for `sbt localPipelineHeldout` and deny for `sbt goldSetEvalHeldout` | S1 widened (within the guard); S2 and S3 narrowed |
```
The hadoop-engineer, `gold/**` and Orchestrator rows are unchanged; the orchestrator gains no rows.

## 9. Section 8 application procedure, deltas
- Step 2 (non-constitutional): add the build-engineer inheritIO item (finding 1) and the R6 observation.
- Step 3: the ER role diff is now part of the amendment (recommended), and settings S1-S3 are chosen.
- Step 4: `--validate-allowlist` PASS with **95** identity rows, B1 at **120 rows**, and `NOTE 649 table probes run` (657 with R6).
- Step 7 adds two post-restart observations: the orchestrator's `sbt goldSetEvalHeldout` is refused with `heldout-eval-human-only`; and, after the first dev evaluation, whether `target/streams/_global/goldSetEvalDev/` holds evaluation output.

## 10. Other preferred choices
These need no new text beyond the selections above:
- D3: (b) is build-engineer's and (c) is the human-gate rule; no constitutional text.
- D4: settled wording only.
- D5 = L1: drop R4 and its 8 probes; keep R1-R3 and R5.
- D8: S1 and S2 as drafted; S3 revised per item 4.
- D10(i)+(ii): D10(ii) included as item 9, without the "(only with D10(ii))" prefix.
- D12: allow + S1, as drafted.
- D13: non-constitutional.
- D14: no change.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: FE-P5-04 (Phase 5): revise amendment FE-P5-03 for the human's preferred choices (decisions-16), chiefly D15(c) human-only held-out evaluation superseding D2; exact before/after hunks for section 6 (D2, D15), allowlist and mirror, guard rule, settings S3, the ER role file, probes, section 7, section 9, and out-of-scope consequences. Proposed diff only.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- one wc -l call: refused by guard-dangerous.sh (no-allowlist-entry); nothing executed
- .claude/hooks/validate-agent-files.sh: RESULT: PASS (0 failures)
- .claude/hooks/validate-agent-files.sh --probes: RESULT: PASS (0 failures); NOTE 457 table probes run; all fixture probes PASS
- one tail call on the persisted probe output: refused (no-allowlist-entry); I read the file with Read instead
- git diff --stat -- .claude CLAUDE.md docs/agents.md .mcp.json: empty
- git ls-files --others --exclude-standard -- .claude CLAUDE.md docs/agents.md .mcp.json: empty
TEST RESULTS: not applicable (nothing applied; no sbt run)
VALIDATION RESULTS: Baseline only, current tree:
- JSON: settings.json and .mcp.json parse.
- Shell syntax: bash -n PASS on all 9 hooks; shellcheck not installed.
- Executable bits: all 6 referenced hooks present and executable; no duplicate registrations.
- Frontmatter: 18 agent files with name, description and explicit tools; the code-reviewer fence holds.
- Ownership: 45 rows PASS.
- Guard: one PYEOF heredoc, 503 lines, parses.
- bash-allowlist.tsv: 94 identity rows, 3 groups; mirror 119 rows, sha256 eacc92bb7765.
- --probes: 457 table probes PASS.
The proposed hunks were checked by reading guard-dangerous.sh (tripwire, validate, bash_check order, read_check, main) and the archive. None has been executed.
ASSUMPTIONS:
- Held-out evaluation is human-only; the held-out pipeline run (localPipelineHeldout) stays with hadoop-engineer under ask.
- sbt keeps the goldSetEvalHeldout task log under target/streams/_global/goldSetEvalHeldout/ (expected, not observed).
- Claude Code Grep honours .gitignore for repository-wide searches (to be observed).
- The probe runner matches the last field as a substring of the guard output, as in FE-P5-03.
- ER may still Read held-out membership, blind view, queue and config (unchanged FE-P5-03 controls): they contain no labels.
RISKS:
1. Output the human pastes into a session, work order or docs/agent-runs is not fenced; this is a procedural control.
2. Without BE's inheritIO change, held-out output persists under target/streams; R6 is the backstop, and only for agent Read/Grep. sbt-run code can still read it (no OS sandbox).
3. R6's Grep refusal of target/ and target/streams also blocks harmless searches of build output.
4. How the S3 multi-wildcard deny pattern matches is unverified until after restart.
5. None of the 25 changed or 10 new probe lines has been observed.
6. The deny() tail text still mentions BLOCKED-ON factory-engineer for new shapes; HE_DO overrides it in substance.
MANUAL ACTION REQUIRED:
- Human: confirm D15(c) consequences 1-6, decide whether to adopt R6 (plus the optional S2 extension), and ratify the combined FE-P5-03 + FE-P5-04 text.
- Human: after the first dev evaluation, check target/streams/_global/goldSetEvalDev/.
- Orchestrator: archive this report as amendment-fe-p5-04.md; route the inheritIO item to build-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review and ratification of FE-P5-03 as revised by FE-P5-04; BE-P5-02 apply (with the inheritIO, L1 and D3(b) deltas) with sbt check and code-reviewer APPROVE; human applies the constitutional files with no session open; validator and --probes PASS (120 rows, 95 identity rows, 649 or 657 table probes); commits C, A, B; fresh session; observations; dev evidence run; held-out sequence run by the human only, with separate approval.
MATRIX BASIS:
- Relied on: ratified B10 placeholder `entity-resolution-engineer sbt goldSetEval` (Phase 5 gold-set evaluation), now activated only for the dev half; Lifecycle routing human row (gold-set labels); Human gates Phase 5; decisions-15 item 1; decisions-16 (D15(c)).
- Proposed amendments: drop the held-out ER row; guard rule heldout-eval-human-only (plus its tripwire); R6 (optional); settings S3 deny; ER law 5 sentence and law 9; docs hunks above.
PRIVILEGE DELTA: entity-resolution-engineer: +goldSetEvalDev (allow), held-out evaluation withheld (narrowed versus B10 intent), role laws narrowed, R6 narrowed. hadoop-engineer: unchanged from FE-P5-03. All identities and the orchestrator: narrowed (named refusal; R6). Settings: S1 widened within the guard; S2 and S3 narrowed. No tool or ownership widening beyond the ratified B10 intent.
FLOOR INVARIANTS: all intact. Every FE-P5-04 change adds a refusal or narrows access. No dangerous-command guard, test-stop hook, stale-marker refusal, formatter or Scala auto-format, secret-read deny, terraform apply/destroy deny, destructive AWS or recursive S3 deny, EMR guard, force-push deny or constitutional-write protection is touched. The new refusals name their rules and give a "do instead" message.
AUDIT RESULTS:
- Collision: rule names heldout-eval-human-only and heldout-eval-output-read are unique; the allowlist loses one row and gains no duplicate; the S3 deny entries duplicate no existing rule; no new agent, command or hook registration.
- Orphan: no new files; the new functions are called from tripwire, bash_check and gold_check; the role file no longer implies ER runs held-out evaluation.
- One-writer: unchanged from FE-P5-03 (gold/** HUMAN; target/ is ignored build output with no new owner row).
COMPLETE DIFF: no controlled file was changed (git diff --stat of the controlled files is empty; no untracked controlled files). The full proposed diff is FE-P5-03 sections 1-6 as amended by items 1-10 above.
OUT-OF-SCOPE ITEMS:
- build-engineer: keep goldSetEvalHeldout as the human's fixed Terminal task (recommended; it carries the committed-file, L1 ignored-labels, D3(b) dirty-tree, freeze-file and SCHOLARCHAIN_CFG_* checks).
- build-engineer: send the held-out child process output straight to the terminal (inheritIO), not through streams.value.log (new, recommended).
- build-engineer: the 60 s bound's agent-tool-limit rationale no longer applies to held-out; keeping it is harmless, and raising it for held-out only is optional; no LocalRuns check needs to change.
- entity-resolution-engineer: the L1 `labels file=... sha256=...` print line stays recommended, because it binds the hash to the bytes actually evaluated; the human's own shasum in step 7 is the fallback. No summary-only mode is needed (D15(b) withdrawn).
- Human: every held-out evaluation run and how its output is handled.
RATIFICATION REQUIRED: Yes. A human must review and ratify this revision together with FE-P5-03, apply the constitutional files with no Claude Code session open, see validate-agent-files.sh and --probes pass, commit, and start a fresh session. Nothing here is effective before then, and the held-out run stays pending separate human approval.
```
