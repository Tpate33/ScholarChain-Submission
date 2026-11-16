# Amendment FE-P5-01 rev 2 (DRAFT, not applied) — factory-engineer FE-P5-02 report, verbatim

Saved by the orchestrator, 2026-11-13, per decisions-14.md. **This is a draft for human review and ratification only. Nothing in it is effective. No constitutional file was changed.** It supersedes `amendment-fe-p5-01.md` (rev 1) wherever the two differ. The harness flagged the report as containing an instruction-shaped pattern (`settings-json`): that is the proposed `.claude/settings.json` diff in section 3.G, a proposal for the human, not an instruction to any agent. In the `.tsv` blocks, columns must be single TAB characters when applied; rebuild them with TABs if this copy shows spaces.

---

FE-P5-02: FE-P5-01 rev 2, a draft constitutional amendment covering G9-a (gold path), G9-b (localPipeline and goldSetEval rows, two variants) and G9-c (fence on reading held-out labels). This is a draft for human ratification only. It supersedes FE-P5-01 rev 1 (`docs/agent-runs/phase-05/amendment-fe-p5-01.md`) wherever the two differ.

I wrote no files and made no Bash calls. I only read: decisions-01 to decisions-14, amendment-fe-p5-01.md, CLAUDE.md, docs/agents.md, bash-allowlist.tsv, lib-ownership.sh, guard-dangerous.sh, audit-bash-writes.sh, verify-tests-ran.sh, validate-agent-files.sh, probes.tsv (lines 420-461), settings.json, .gitignore and GoldSetEvalMain.scala. Every result below comes from reading the code. None of it has been run.

In every `.tsv` block below, columns are separated by one real TAB. If the archived copy shows spaces, rebuild each line with TABs. `bash-allowlist.tsv` is read as ASCII, so every row is plain ASCII.

## 0. A defect in rev 1 that rev 2 fixes

Since decisions-06 (m4), `GoldSetEvalMain` refuses final held-out metrics unless it is given `--manifest-sha256=<hex>`. Without it, a held-out run that meets the minimum prints coverage only and exits 2 (GoldSetEvalMain.scala:282-292).

Rev 1's alias passes no SHA, so `sbt goldSetEval` could never produce final held-out metrics. An allowlist row takes no free arguments, so the SHA has to come from a fixed source. Rev 2 puts it in a human-owned freeze record: `gold/entity-resolution/heldout-v1/er-gold-membership.sha256` (one line, 64 lowercase hex characters). The sbt task reads that file and passes it as `--manifest-sha256=`.

It must not be a literal in `build.sbt`. That would make build-engineer the writer of the human's freeze value, which breaks one-writer and "no agent approves its own work".

## 1. G9-a: gold path ownership (rev 2)

**D1. One row: `gold/**` -> `HUMAN`.** It is placed directly after `**/*.sbt`, so `gold/**/*.sbt` stays `UNASSIGNED`. This is unchanged from rev 1. Layout, with each split kept separate as decisions-14 requires:

- `gold/entity-resolution/heldout-v1/`
  - `er-gold-membership.jsonl`: frozen membership manifest
  - `er-gold-membership.sha256`: freeze record, new in rev 2
  - `er-blind-label-view.jsonl`: blind view
  - `er-review-queue.jsonl`: review queue
  - `er-gold-labels.jsonl`: human labels
- `gold/entity-resolution/dev-v1/`: the same file names. Only needed if the human decides to label a dev set (decision D4).

Only the human writes under `gold/**`, from a terminal. That includes the membership, blind-view and queue generators, the labels and the `.sha256` record. No agent or orchestrator row writes there. The skeleton `.gitkeep` exemption does not apply, because the owner is `HUMAN`.

**D2. Which agents may read which gold files (G9-c, recommended, new in rev 2):**

| File | Agents (orchestrator included) | Why |
|---|---|---|
| held-out `er-gold-labels*` | **never**, by Read, Grep or git diff/show. Only the evaluation entry point reads it, inside sbt | decisions-01 item 4: keep evaluation labels out of policy development and threshold tuning |
| held-out membership, `.sha256`, blind view, queue | may Read by exact path | no label content; reviewers and ER need them to check provenance and the freeze |
| dev labels | readable (not fenced), subject to D4 | decisions-05: "development evaluation may remain separate" |
| any Grep under `gold/` | refused | Grep prints content; agents use Read on exact files instead |

**D3. Recommended storage for held-out labels (L1, needs a human decision, D5).** The human keeps `gold/entity-resolution/heldout-v1/er-gold-labels.jsonl` gitignored until the held-out evaluation is accepted. They record its sha256 in `human-gate.md` at each approved evaluation run, and commit it with the final evaluation evidence.

Why this is recommended: while the file is ignored, it is not in any git diff or show, not in the audit snapshot, and (expected, not verified) not searched by repo-wide Grep, since ripgrep honours `.gitignore`.

How the freeze order is still shown: membership, blind view and the `.sha256` record are committed before any label exists.

What it needs: build-engineer adds the anchored line `/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl` to `.gitignore`. That file is not constitutional, so this is a separate work order.

The alternative, L2, commits labels as soon as they exist. That leaves two residual leaks (section 6).

## 2. G9-b: the B10 rows, in two variants

Shared facts:

- Both rows are B10 placeholders that are already ratified but inactive: `hadoop-engineer sbt localPipeline` (allow) and `entity-resolution-engineer sbt goldSetEval` (allow).
- The identities do not change. The orchestrator gets no new row.
- Every sbt row keeps the `.gitignore` precondition, the character gate and the tripwires. The new words (`localpipeline*`, `goldseteval*`) trip no tripwire or FLOOR pattern; I checked this by reading `tripwire()`, `FLOOR` and `validate()`.

**Variant (b), per split (recommended).** It keeps dev and held-out separate, as decisions-14 asks. The ratified row 96 becomes two rows and row 100 becomes two rows, so the total goes from 119 to 121.

```tsv
hadoop-engineer	active	allow	-	sbt localPipelineDev	Phase 5 local bounded pipeline, dev split (amendment G9-b rev 2, B10): build-engineer alias; fixed inputs; writes only its gitignored output directory under modules/hadoop
hadoop-engineer	active	ask	-	sbt localPipelineHeldout	Phase 5 local bounded pipeline, held-out split (amendment G9-b rev 2, B10): build-engineer alias; fixed inputs; writes only its gitignored output directory under modules/hadoop; ask on every run
entity-resolution-engineer	active	allow	-	sbt goldSetEvalDev	Phase 5 dev-split evaluation diagnostics (amendment G9-b rev 2, B10): build-engineer alias; fixed dev inputs under gold/; never final metrics; writes nothing
entity-resolution-engineer	active	ask	-	sbt goldSetEvalHeldout	Phase 5 held-out gold-set evaluation (amendment G9-b rev 2, B10): build-engineer alias; fixed held-out inputs under gold/ and the human-recorded freeze sha256; writes nothing; ask on every run
```

Modes:

- **Held-out evaluation: `ask`.** Every run prints false positives and false negatives that are derived from the held-out labels. A human prompt per run stops an evaluate-tune-evaluate loop.
- **Held-out pipeline: `ask`.** Its output is the population the frozen membership was drawn from. The human should approve each regeneration so the run used for the freeze can be identified.
- **Dev rows: `allow`,** the mode B10 ratified. Dev diagnostics are allowed by decisions-05.
- If D4 decides there is no dev label set, set the `goldSetEvalDev` row to `inactive:G9-dev-set-undecided` instead of `active`.

**Variant (a), single aliases.** The rows are edited in place, so the total stays at 119.

```tsv
hadoop-engineer	active	ask	-	sbt localPipeline	Phase 5 local bounded pipeline (amendment G9-b rev 2, B10): build-engineer alias; runs the dev and the held-out split as two separate runs with fixed inputs and separate gitignored output directories under modules/hadoop; ask because it includes the held-out split
entity-resolution-engineer	active	ask	-	sbt goldSetEval	Phase 5 held-out gold-set evaluation (amendment G9-b rev 2, B10): build-engineer alias; fixed held-out inputs under gold/ and the human-recorded freeze sha256; writes nothing; ask on every run
```

Costs of (a):

- The dev and held-out pipelines cannot be approved separately.
- `entity-resolution-engineer` gets no dev evaluation command, because the SBT class only allows compile, test and scalafmt.

**Acceptance criteria for BE-P5-01 (build-engineer) that the constitutional rows depend on.** These are not constitutional; they are listed so the rows stay true.

1. Each alias or task has fixed arguments only. The held-out evaluation reads the SHA from the `.sha256` file and refuses with a clear message if the file is missing or malformed. The dev evaluation needs no SHA.
2. `Compile / run / fork := true` and a fixed working directory. Relative paths must resolve from the repository root, or the task must pass absolute paths built from `ThisBuild / baseDirectory`.
3. External configuration: `GoldSetEvalMain` requires `SCHOLARCHAIN_CONFIG_FILES`. Agents cannot set environment variables (an `X=y` prefix matches no row), so the task must set a fixed, committed config path through the forked JVM's `envVars`. Which config file, and who owns it, is for build-engineer and entity-resolution-engineer.
4. Pipeline outputs go to fixed per-split directories inside hadoop territory that are gitignored and survive `sbt clean`. Recommended: `modules/hadoop/local-runs/dev/` and `modules/hadoop/local-runs/heldout/`, plus one anchored `.gitignore` line, `/modules/hadoop/local-runs/` (build-engineer). Under `target/`, any agent's allowed `sbt clean check` would delete the run the frozen membership came from. The pipeline should refuse to overwrite an existing run, or write to a run-id subdirectory. How it reports distinct candidate pairs is for HD-P5-01.
5. None of these tasks is part of `check`, and `sbt check` still passes.
6. Nothing lands in a git-visible path outside the caller's territory. That includes the repository root, where forked JVM side files such as `derby.log`, `metastore_db/` or `hs_err_pid*.log` could appear.

**What the audit hook will see. No change to `audit-bash-writes.sh` is proposed; adding exemptions would weaken the detector.**

- `goldSetEval*`: compile output under `target/` only. Those paths are already ignored (`/target/`, `/modules/*/target/`, `/project/target/`), so they are not hashed. Reports go to stdout, so there is nothing to report.
- `localPipeline*`, output under `modules/hadoop/local-runs/` (ignored): not hashed, nothing reported.
- `localPipeline*`, output under `modules/hadoop/` but not ignored: hashed and attributed to `hadoop-engineer`, so not a violation. But it shows in `git status`, and the audit fails closed if git-visible content passes 1 GiB.
- `localPipeline*`, any output elsewhere that is git-visible (`datasets/**`, `gold/**`, the root, `runs/`): reported as an OWNERSHIP VIOLATION, exit 2.
- Hadoop local-mode scratch in `/tmp/hadoop-<user>` is outside the repository and is not audited (residual).
- UNATTRIBUTED reports: the human's terminal writes into git-visible `gold/**` while a session is open (membership, blind view, queue, `.sha256`) cause one refused Bash call. The documented remedy applies. Under L1, label edits are ignored and cause no report.

## 3. Constitutional files touched, and the diffs

### A. `.claude/hooks/lib-ownership.sh`
Unchanged from rev 1: the `gold/*` case and the anchor line.

```diff
@@ -46,2 +46,5 @@
     *.sbt)                                                      echo "UNASSIGNED" ;;
+    # --- human-owned gold set (amendment G9-a): per-split membership, freeze sha256, blind views,
+    # queues, labels. After *.sbt so gold/**/*.sbt stays UNASSIGNED (F1); before every agent territory.
+    gold/*)                                                     echo "HUMAN" ;;
     # --- application modules --------------------------------------------------
@@ -109,2 +112,3 @@
 x/y.scala/x.sbt
+gold/x/y.scala
 modules/core/x/y.scala
```

### B. `.claude/hooks/enforce-ownership.sh`
Unchanged from rev 1. This only changes the HUMAN refusal message, and can be dropped on its own.

```diff
-    block "'$rel' is human-owned (human gate decisions, LICENSE, personal settings).
+    block "'$rel' is human-owned (human gate decisions, gold-set membership, freeze records and labels under gold/, LICENSE, personal settings).
```

### C. `.claude/hooks/bash-allowlist.tsv`
- Variant (b): replace line 96 with the two `hadoop-engineer` rows from section 2, and line 100 with the two `entity-resolution-engineer` rows.
- Variant (a): replace lines 96 and 100 in place with the two variant (a) rows.
- No other row changes.

### D. `.claude/hooks/guard-dangerous.sh` (G9-c, new; only adds refusals)

```diff
@@ -17 +17,3 @@
-# Read/Grep/Glob: secret-read check on the raw fields and their resolved (symlink-free) paths.
+# Read/Grep/Glob: secret-read check on the raw fields and their resolved (symlink-free) paths, and the
+# held-out gold-label fence (G9-c): no Read of gold/**/heldout*/er-gold-labels*, no Grep under gold/.
+# Bash: git diff/show naming a gold/ path is refused (G9-c); git log/ls-files (names only) stay allowed.
@@ -51,1 +53,7 @@
 SEG_BAD = re.compile(r'(^|/)(\.\.|\.git)(/|$)', re.I)
+# G9-c (FE-P5-02): held-out human gold labels reach no agent tool; only the evaluation entry point (the
+# gold-set evaluation sbt command, run inside sbt) reads them. Matched on lower-cased repo-relative paths.
+HELDOUT_LABELS = re.compile(r'(^|/)gold/(.+/)?heldout[^/]*/(.+/)?er-gold-labels[^/]*$')
+HL_DO = ('Use the coverage and error report printed by the gold-set evaluation command (docs/agents.md, Bash allowlist; '
+         'human-approved). Membership, freeze, blind-view and queue files under gold/ may be Read by exact path. To search '
+         'code, give Grep a path inside your module. Anything else: return STATUS: BLOCKED-ON human.')
@@ after def tripwire(...) ... return None @@
+def heldout_label_bash(toks):
+    low = [t.lower() for t in toks]
+    if os.path.basename(low[0]) != 'git' or len(low) < 2 or low[1] not in ('diff', 'show'):
+        return None
+    for t in low[2:]:
+        p = os.path.normpath(t.split(':', 1)[1] if ':' in t else t)
+        if p == 'gold' or p.startswith('gold/') or HELDOUT_LABELS.search(p):
+            return ('heldout-label-read', 'git %s on %r could print human gold-set labels (G9-c)' % (low[1], t), HL_DO)
+    return None
+
+def repo_rels(v):
+    # lower-cased repo-relative forms of v (as given, normalized, and symlink-resolved); '.' when v is the
+    # repository root or one of its ancestors (a search there reaches gold/); paths elsewhere are omitted
+    out, ab, lr = [], os.path.normpath(os.path.join(CWD, v)), ROOT.lower()
+    for p in (ab, os.path.realpath(ab)):
+        r = p.lower().rstrip(os.sep) or os.sep
+        if r == lr or r.startswith(lr + os.sep):
+            out.append(os.path.relpath(r, lr))
+        elif lr.startswith(r.rstrip(os.sep) + os.sep):
+            out.append('.')
+    return out
+
+def gold_check(tool, ti):
+    if tool == 'Glob':
+        return    # Glob lists names, never content
+    v = ti.get('file_path' if tool == 'Read' else 'path')
+    rels = repo_rels(v) if isinstance(v, str) and v else ['.']
+    for rel in rels:
+        if tool == 'Read' and HELDOUT_LABELS.search(rel):
+            deny('heldout-label-read', 'Read %r: held-out human gold labels are read only by the evaluation entry point, never by an agent tool (G9-c).' % v, HL_DO)
+        if tool == 'Grep' and (rel == 'gold' or rel.startswith('gold/')):
+            deny('heldout-label-read', 'Grep under gold/ (%r) could print held-out human gold labels (G9-c).' % v, HL_DO)
+    g = ti.get('glob')
+    if tool == 'Grep' and '.' in rels and isinstance(g, str) and re.search(r'gold|heldout|label', g, re.I):
+        deny('heldout-label-read', 'Grep from the repository root with glob %r could reach gold-set label files (G9-c).' % g, HL_DO)
@@ in bash_check, directly after `if tw: deny(*tw)` @@
+    hl = heldout_label_bash(toks)
+    if hl:
+        deny(*hl)
@@ at the end of read_check (after the home/system-root loop) @@
+    gold_check(tool, ti)
```

Notes on D:
- It only adds refusals. It never emits allow, and it keeps exactly one `<<'PYEOF'` heredoc, which `validate-agent-files.sh` checks.
- `validate()` is unaffected: `heldout_label_bash` is not part of `tripwire()`, so no active row is flagged.

### E. `.claude/hooks/probes.tsv`: append after line 461
Drafted from reading the code; the human's `--probes` run is the first observation.

- The rev 1 G9-a block (rev 1 section 2.D, lines 144-176) is kept unchanged.
- The rev 1 G9-b block (lines 177-198) is kept **only for variant (a)**. Its `sbt goldSetEval` ASK probe would fail under (b), where `sbt goldSetEval` matches no row.

Then add these lines:

```tsv
# FE-P5-01 rev 2 (FE-P5-02): G9-a additions, G9-b variant (b), G9-c. Drafted by reading the hooks; first observed by the human's --probes run.
BLOCK	enforce-ownership.sh	[G9-a2] entity-resolution-engineer Write freeze sha256	{"tool_name": "Write", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-membership.sha256", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a2] build-engineer Write freeze sha256	{"tool_name": "Write", "agent_type": "build-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-membership.sha256", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a2] entity-resolution-engineer Write dev labels	{"tool_name": "Write", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/dev-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a2] hadoop-engineer Write gold from a pipeline path	{"tool_name": "Write", "agent_type": "hadoop-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-review-queue.jsonl", "content": "x"}}	is human-owned
ALLOW	enforce-ownership.sh	[G9-a2] control: hadoop-engineer Write modules/hadoop/local-runs/dev/x	{"tool_name": "Write", "agent_type": "hadoop-engineer", "tool_input": {"file_path": "@ROOT@/modules/hadoop/local-runs/dev/x", "content": "x"}}
ALLOW	guard-dangerous.sh	[G9-b2] hadoop-engineer sbt localPipelineDev (active, allow)	{"tool_name": "Bash", "tool_input": {"command": "sbt localPipelineDev"}, "agent_type": "hadoop-engineer"}
ASK	guard-dangerous.sh	[G9-b2] hadoop-engineer sbt localPipelineHeldout (active, ask)	{"tool_name": "Bash", "tool_input": {"command": "sbt localPipelineHeldout"}, "agent_type": "hadoop-engineer"}	sbt localPipelineHeldout
ALLOW	guard-dangerous.sh	[G9-b2] entity-resolution-engineer sbt goldSetEvalDev (active, allow)	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalDev"}, "agent_type": "entity-resolution-engineer"}
ASK	guard-dangerous.sh	[G9-b2] entity-resolution-engineer sbt goldSetEvalHeldout (active, ask)	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	sbt goldSetEvalHeldout
BLOCK	guard-dangerous.sh	[G9-b2] entity-resolution-engineer sbt localPipelineDev	{"tool_name": "Bash", "tool_input": {"command": "sbt localPipelineDev"}, "agent_type": "entity-resolution-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] entity-resolution-engineer sbt localPipelineHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt localPipelineHeldout"}, "agent_type": "entity-resolution-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] hadoop-engineer sbt goldSetEvalDev	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalDev"}, "agent_type": "hadoop-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] hadoop-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "hadoop-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] ORCHESTRATOR sbt localPipelineDev	{"tool_name": "Bash", "tool_input": {"command": "sbt localPipelineDev"}}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] ORCHESTRATOR sbt localPipelineHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt localPipelineHeldout"}}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] ORCHESTRATOR sbt goldSetEvalDev	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalDev"}}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] ORCHESTRATOR sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] code-reviewer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "code-reviewer"}	reviewer-read-only
BLOCK	guard-dangerous.sh	[G9-b2] factory-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "factory-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] build-engineer sbt localPipelineHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt localPipelineHeldout"}, "agent_type": "build-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] build-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "build-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] test-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "test-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] documentation-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "documentation-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] experiment-engineer sbt goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalHeldout"}, "agent_type": "experiment-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] entity-resolution-engineer old single name sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "entity-resolution-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] hadoop-engineer old single name sbt localPipeline	{"tool_name": "Bash", "tool_input": {"command": "sbt localPipeline"}, "agent_type": "hadoop-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] entity-resolution-engineer case variant sbt goldsetevalheldout	{"tool_name": "Bash", "tool_input": {"command": "sbt goldsetevalheldout"}, "agent_type": "entity-resolution-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] entity-resolution-engineer chaining dev and held-out	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEvalDev goldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] entity-resolution-engineer sbt check goldSetEvalHeldout	{"tool_name": "Bash", "tool_input": {"command": "sbt check goldSetEvalHeldout"}, "agent_type": "entity-resolution-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b2] entity-resolution-engineer direct runMain with own paths	{"tool_name": "Bash", "tool_input": {"command": "sbt entityResolution/runMain scholarchain.entityresolution.evaluation.GoldSetEvalMain gold/a gold/b gold/c"}, "agent_type": "entity-resolution-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-c] entity-resolution-engineer Read held-out labels	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] orchestrator (agent_type absent) Read held-out labels	{"tool_name": "Read", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] code-reviewer Read held-out labels	{"tool_name": "Read", "agent_type": "code-reviewer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] test-engineer Read held-out labels	{"tool_name": "Read", "agent_type": "test-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] hadoop-engineer Read held-out labels	{"tool_name": "Read", "agent_type": "hadoop-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] entity-resolution-engineer Read case variant	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/GOLD/Entity-Resolution/HELDOUT-v1/ER-GOLD-LABELS.jsonl"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] entity-resolution-engineer Read via traversal from own module	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/modules/entity-resolution/../../gold/entity-resolution/heldout-v1/er-gold-labels.jsonl"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] entity-resolution-engineer Read later held-out version	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v2/er-gold-labels.partial.jsonl"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] entity-resolution-engineer Grep path gold	{"tool_name": "Grep", "agent_type": "entity-resolution-engineer", "tool_input": {"pattern": "match", "path": "@ROOT@/gold"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] entity-resolution-engineer Grep path held-out dir	{"tool_name": "Grep", "agent_type": "entity-resolution-engineer", "tool_input": {"pattern": "match", "path": "@ROOT@/gold/entity-resolution/heldout-v1"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] entity-resolution-engineer Grep root glob er-gold-labels	{"tool_name": "Grep", "agent_type": "entity-resolution-engineer", "tool_input": {"pattern": "match", "glob": "**/er-gold-labels*"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] entity-resolution-engineer Grep root glob *gold*	{"tool_name": "Grep", "agent_type": "entity-resolution-engineer", "tool_input": {"pattern": "match", "path": "@ROOT@", "glob": "*gold*"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] entity-resolution-engineer Grep from repo parent with label glob	{"tool_name": "Grep", "agent_type": "entity-resolution-engineer", "tool_input": {"pattern": "match", "path": "@ROOT@/..", "glob": "**/er-gold-labels*"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] entity-resolution-engineer git show HEAD:gold labels	{"tool_name": "Bash", "tool_input": {"command": "git show HEAD:gold/entity-resolution/heldout-v1/er-gold-labels.jsonl"}, "agent_type": "entity-resolution-engineer"}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] ORCHESTRATOR git diff HEAD -- gold	{"tool_name": "Bash", "tool_input": {"command": "git diff HEAD -- gold"}}	heldout-label-read
BLOCK	guard-dangerous.sh	[G9-c] ORCHESTRATOR git diff -- ./gold/entity-resolution	{"tool_name": "Bash", "tool_input": {"command": "git diff -- ./gold/entity-resolution"}}	heldout-label-read
ALLOW	guard-dangerous.sh	[G9-c] control: entity-resolution-engineer Read held-out membership manifest	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-membership.jsonl"}}
ALLOW	guard-dangerous.sh	[G9-c] control: entity-resolution-engineer Read freeze sha256	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-membership.sha256"}}
ALLOW	guard-dangerous.sh	[G9-c] control: entity-resolution-engineer Read blind view	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-blind-label-view.jsonl"}}
ALLOW	guard-dangerous.sh	[G9-c] control: entity-resolution-engineer Read review queue	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-review-queue.jsonl"}}
ALLOW	guard-dangerous.sh	[G9-c] control: entity-resolution-engineer Read dev labels (not fenced; decision D4)	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/dev-v1/er-gold-labels.jsonl"}}
ALLOW	guard-dangerous.sh	[G9-c] control: entity-resolution-engineer Read module test fixture named er-gold-labels	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/modules/entity-resolution/src/test/resources/er-gold-labels.jsonl"}}
ALLOW	guard-dangerous.sh	[G9-c] control: entity-resolution-engineer Grep own module with glob *Gold*.scala	{"tool_name": "Grep", "agent_type": "entity-resolution-engineer", "tool_input": {"pattern": "x", "path": "@ROOT@/modules/entity-resolution", "glob": "*Gold*.scala"}}
ALLOW	guard-dangerous.sh	[G9-c] control: entity-resolution-engineer Glob gold/** (names only)	{"tool_name": "Glob", "agent_type": "entity-resolution-engineer", "tool_input": {"pattern": "gold/**"}}
ALLOW	guard-dangerous.sh	[G9-c] control: ORCHESTRATOR git log --stat -- gold (names only)	{"tool_name": "Bash", "tool_input": {"command": "git log --stat -- gold"}}
ALLOW	guard-dangerous.sh	[G9-c] control: ORCHESTRATOR git ls-files -- gold	{"tool_name": "Bash", "tool_input": {"command": "git ls-files -- gold"}}
```

- For variant (a): drop the `[G9-b2]` lines, keep rev 1's G9-b block, and add these three:
  - `ASK hadoop-engineer "sbt localPipeline"`, want substring `sbt localPipeline`
  - `BLOCK entity-resolution-engineer "sbt localPipeline"`, want `no-allowlist-entry`
  - `BLOCK ORCHESTRATOR "sbt localPipeline"`, want `no-allowlist-entry`
- Identities without a probe line have no row by construction; `--validate-allowlist` and the B1 mirror check that.
- If D4 sets the dev evaluation row inactive, change its probe from ALLOW to BLOCK with want `entry-inactive`.

### F. `docs/agents.md`
Hunks are matched by content. Line numbers are approximate.

```diff
@@ Path ownership table, after the `**/*.sbt` row @@
+| `gold/**` | `HUMAN` | amendment G9-a (FE-P5-01 rev 2): spec Phase 5 gate "Gold labels are human-reviewed" (README.md:1245); human decisions 2026-11-04 (blind labeling; labels kept out of policy development and tuning), 2026-11-05 (freeze membership before labeling), 2026-11-06 (recorded manifest SHA-256 for final held-out metrics), 2026-11-13 (separate dev and held-out runs). Holds per-split membership manifests, freeze records (`*.sha256`), blind views, review queues and labels, written only by the human; placed after `**/*.sbt` so `gold/**/*.sbt` stays `UNASSIGNED` (F1) |
@@ Bash allowlist intro @@
-... This table mirrors `.claude/hooks/bash-allowlist.tsv` row for row (119 rows; source sha256 `eacc92bb776577d131d16850575f5b83e3565f333b6af9cd47558d31a425796d`). ...
+... This table mirrors `.claude/hooks/bash-allowlist.tsv` row for row (121 rows; source sha256 `<NEW-SHA256>`). ...
    (variant (a): keep "119 rows"; replace only the sha256)
@@ Bash allowlist rows (variant (b)) @@
-| `hadoop-engineer` | inactive:B10-alias-pending | allow | - | `sbt localPipeline` | Phase 4 local bounded pipeline; alias authored by build-engineer |
+| `hadoop-engineer` | active | allow | - | `sbt localPipelineDev` | Phase 5 local bounded pipeline, dev split (amendment G9-b rev 2, B10): build-engineer alias; fixed inputs; writes only its gitignored output directory under modules/hadoop |
+| `hadoop-engineer` | active | ask | - | `sbt localPipelineHeldout` | Phase 5 local bounded pipeline, held-out split (amendment G9-b rev 2, B10): build-engineer alias; fixed inputs; writes only its gitignored output directory under modules/hadoop; ask on every run |
-| `entity-resolution-engineer` | inactive:B10-alias-pending | allow | - | `sbt goldSetEval` | Phase 5 gold-set evaluation |
+| `entity-resolution-engineer` | active | allow | - | `sbt goldSetEvalDev` | Phase 5 dev-split evaluation diagnostics (amendment G9-b rev 2, B10): build-engineer alias; fixed dev inputs under gold/; never final metrics; writes nothing |
+| `entity-resolution-engineer` | active | ask | - | `sbt goldSetEvalHeldout` | Phase 5 held-out gold-set evaluation (amendment G9-b rev 2, B10): build-engineer alias; fixed held-out inputs under gold/ and the human-recorded freeze sha256; writes nothing; ask on every run |
    (variant (a): the two in-place rows, rendered "| `id` | status | mode | prov | `spec` | why |" from section 2)
@@ Global floor paragraph @@
-... It covers secret reads, terraform apply/destroy/state mutation, ...
+... It covers secret reads, held-out gold-label reads (G9-c: no Read of `gold/**/heldout*/er-gold-labels*`, no Grep under `gold/`, no git diff/show naming a `gold/` path), terraform apply/destroy/state mutation, ...
@@ Lifecycle routing @@
-| Ratification, human gate records, commits, gold-set labels, cleanup execution | `human` | human judgement |
+| Ratification, human gate records, commits, gold-set membership generation, freeze records and labels (`gold/**`), cleanup execution | `human` | human judgement |
@@ Human gates @@
-- **Phase 5:** label and review gold-set examples.
+- **Phase 5:** generate each split's frozen membership manifest and blind view into `gold/**` in a terminal; write the manifest's sha256 to `er-gold-membership.sha256` and to `human-gate.md`; commit them before any label exists; label blindly from the blind view only (held-out labels stay gitignored until the held-out evaluation is accepted, decision L1); generate the review queue; approve each held-out pipeline run and each held-out evaluation run (permission `ask`). No agent or orchestrator writes `gold/**` or reads held-out labels.
@@ Enforcement layers, Dangerous-command guard row @@
-... Read/Grep/Glob: secret-read; aws-api MCP: ...
+... Read/Grep/Glob: secret-read and the held-out gold-label fence (G9-c); Bash: also git diff/show of `gold/` paths (G9-c); aws-api MCP: ...
@@ Residual risk paragraph: append @@
+**Held-out label fence (G9-c) residuals.** sbt-run code (including tests) can read any file the OS user can, so `test-engineer` and `code-reviewer` review any code that names `gold/`. If held-out labels are ever git-visible (decision L2), revision-only `git diff <rev> <rev>` and `git show <commit>` can print them, and a repository-wide Grep without a glob can search them. The evaluation output itself lists false positives and false negatives to the caller; the `ask` mode limits how often that happens.
@@ Known gaps G9 @@
-| G9 | `LICENSE`, `reports/**` other than the final report, packaging files (Dockerfiles), human gold-set labels, and the location of version records have no matrix owner. | `LICENSE` is `HUMAN`. Others are `UNASSIGNED`. | Assign paths and owners when Phase 2, 5, and 17 define them. |
+| G9 | `LICENSE`, `reports/**` other than the final report, packaging files (Dockerfiles), and the location of version records have no matrix owner. Human gold-set labels: **resolved by human decision <RATIFICATION-DATE> (amendment FE-P5-01 rev 2):** `gold/**` -> `HUMAN` (G9-a); the B10 rows activated as <variant (a) or (b)> (G9-b); held-out labels fenced from every agent tool (G9-c). | `LICENSE` and `gold/**` are `HUMAN`. Others are `UNASSIGNED`. | Assign the remaining paths and owners when Phase 2 and 17 define them. |
```

### G. `.claude/settings.json` (optional; each part can be ratified on its own)
- **S1 (variant (b) only).** Without it, the dev rows still prompt under default permission mode. The guard still decides per identity.
- **S2 (recommended).** Defense in depth for G9-c. Its coverage of Grep and Glob is best effort and is checked only after restart. It also binds the orchestrator, which is intended.

```diff
       "Bash(sbt fmt)",
+      "Bash(sbt localPipelineDev)",
+      "Bash(sbt goldSetEvalDev)",
       "Bash(terraform fmt -check *)",
@@ deny @@
       "Read(**/id_dsa*)",
+      "Read(./gold/**/heldout*/er-gold-labels*)",
       "Edit(**/.env)",
```

### Not touched
`CLAUDE.md`, `.mcp.json`, `.claude/agents/*`, `.claude/commands/*`, `audit-bash-writes.sh`, `post-write.sh`, `verify-tests-ran.sh`, `format-scala.sh`, and `validate-agent-files.sh`. Its existing mirror checks already cover the new rows. `verify-tests-ran.sh` `is_source` ignores `.jsonl` and `.sha256`, so gold files never trip the Stop gate.

## 4. Privilege delta

| Who | Change | Classification |
|---|---|---|
| `hadoop-engineer` | (b): +`sbt localPipelineDev` (allow) and +`sbt localPipelineHeldout` (ask), replacing one inactive ratified `sbt localPipeline` (allow). (a): `sbt localPipeline` becomes active with mode ask. No new write territory. | widened within B10. Held-out narrowed to ask. The renamed/split shapes need ratification. |
| `entity-resolution-engineer` | (b): +`sbt goldSetEvalDev` (allow) and +`sbt goldSetEvalHeldout` (ask), replacing the inactive `sbt goldSetEval` (allow). (a): `sbt goldSetEval` becomes active with mode ask. | same as above |
| Every identity, orchestrator included | loses Read of held-out labels, Grep under `gold/` or a root Grep with a gold/heldout/label glob, and git diff/show naming `gold/` paths | narrowed (G9-c) |
| `gold/**` | UNASSIGNED -> HUMAN; still no agent writer | ownership recorded |
| Orchestrator | no new row | unchanged |
| settings.json | S1 adds two allow entries (only the guard-permitted identity can match them); S2 adds one deny | S1 widened prompt-free use, within the guard; S2 narrowed |

## 5. Validation after ratification (human, terminal, all sessions closed)

1. Apply A to G, depending on the variant and options chosen.
2. Run `shasum -a 256 .claude/hooks/bash-allowlist.tsv` and put the value into the docs/agents.md intro line.
3. Run `bash -n .claude/hooks/guard-dangerous.sh` and `bash -n .claude/hooks/lib-ownership.sh`. Run `shellcheck` if it is installed.
4. Run `.claude/hooks/guard-dangerous.sh --validate-allowlist`. It should print PASS with 121 rows under (b), or 119 under (a).
5. If G was applied, run `python3 -I -m json.tool .claude/settings.json`.
6. Run `.claude/hooks/validate-agent-files.sh`, then `.claude/hooks/validate-agent-files.sh --probes`. Both must print `RESULT: PASS`.
7. Run `git diff --stat` and confirm only the intended constitutional files changed. Record the ratification in `human-gate.md`, commit, and **start a fresh Claude Code session**.
8. After restart, run S2 probes by hand: have the orchestrator Read a dummy held-out labels path and confirm the refusal names `heldout-label-read`. Confirm that a repository-wide Grep does not return gold content.

## 6. Items that need a separate human decision

- **D1, variant:** (b) per-split, recommended, or (a) single aliases.
- **D2, who runs the held-out evaluation:**
  - H1 (recommended): `entity-resolution-engineer` with ask. This matches the matrix gate and the identity of the ratified B10 row.
  - H2: only the human, in a terminal. No agent row; the report is pasted into a work order. This is the strongest separation between the evaluated agent and the answer key.
- **D3, policy freeze before a held-out run:** whether the human confirms before each held-out ask that `modules/entity-resolution/**` is committed and clean, and that a policy change made after seeing held-out results requires disclosure or a fresh held-out set. The guard cannot enforce this for sbt rows. Provenance checks only cover script rows.
- **D4, dev set:** whether a human-labeled dev set exists. If not, the `goldSetEvalDev` row stays inactive. If it does, whether dev labels stay readable by entity-resolution-engineer (the default in this draft).
- **D5, held-out label storage:** L1 (gitignored until the evaluation is accepted; sha256 recorded per run; recommended) or L2 (committed immediately; residual leaks through whole-commit git show/diff and repository-wide Grep). L2 can be strengthened with a HUMAN-owned root `.rgignore` containing `/gold/`. That would need its own ownership row, and I have not verified that Claude Code's Grep honours it.
- **D6, orchestrator:** no new row is proposed, and none should be added.
- **D7, ER-P5-14 generators:** the membership, queue and blind-view commands write `gold/**` and stay human-run, as in rev 1 D2. If ER wants a dev review queue for diagnostics outside `gold/`, it needs its own gitignored path in ER territory (build-engineer `.gitignore` line).
- **D8, settings options:** S1 and S2.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: FE-P5-02 (Phase 5): prepare FE-P5-01 rev 2 (gold/** ownership, read fence for held-out labels, localPipeline and goldSetEval B10 rows in two variants) for human review; prepare only, no files, no Bash
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read only; no Bash calls, per the work order)
TEST RESULTS: not applicable (nothing applied, no sbt run)
VALIDATION RESULTS: Nothing was executed. Read-only analysis: guard-dangerous.sh load/match/rows_for/tripwire/validate/read_check/bash_check (new rows parse, are fixed program words and trip nothing; G9-c adds only deny paths and keeps exactly one PYEOF heredoc); validate-agent-files.sh (ownership mirror and anchors, B1 row-for-row check and embedded sha256, probe runner ALLOW/ASK/BLOCK semantics); audit-bash-writes.sh (ignored paths are not hashed; cross-territory writes are reported); verify-tests-ran.sh is_source (gold files are not source); GoldSetEvalMain.scala:282-292 (a held-out run needs --manifest-sha256). JSON parse, bash -n, shellcheck, test -x and the frontmatter checks were NOT run; the human runs them in section 5.
ASSUMPTIONS: The tsv has 119 data rows (counted by reading lines 31-168). No gold/ directory exists today. README.md:1245 is cited as in rev 1 and was not re-verified. BE-P5-01 will deliver fixed-argument aliases or tasks meeting the section 2 criteria. HD-P5-01 output directories can go under modules/hadoop/local-runs/<split>/. Claude Code's Grep honours .gitignore (ripgrep default; not verified).
RISKS: (1) sbt-run code can read held-out labels or write anywhere (no OS sandbox); this is detected after the fact only, and the compensating control is review. (2) The held-out evaluation output shows false positives and false negatives to the evaluated agent; the ask mode limits how often. (3) Under L2, whole-commit git show/diff and repository-wide Grep can expose labels. (4) Forked JVM side files in the repository root would be reported as UNASSIGNED writes. (5) Pipeline output under target/ would be deleted by any allowed sbt clean check. (6) None of the probe lines has been observed passing. (7) S2 Grep/Glob coverage is best effort. (8) Splitting and renaming the B10 shapes is itself an amendment.
MANUAL ACTION REQUIRED: Human: decide D1-D8; review this draft (optionally have code-reviewer review an archived copy); after BE-P5-01 (aliases, .gitignore lines) is accepted, apply A-G with every session closed, insert the sha256, run section 5, commit, restart. Orchestrator: save this report as the rev 2 draft; route BE-P5-01 acceptance criteria 1-6 and the .gitignore lines (L1 labels path, modules/hadoop/local-runs/) to build-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human decisions D1-D8 and review of this draft; BE-P5-01 alias with sbt check and code-reviewer; human apply with sessions closed; validator and --probes PASS; commit; fresh session; only then any localPipeline* or goldSetEval* run
MATRIX BASIS: ratified B10 rows `hadoop-engineer sbt localPipeline` and `entity-resolution-engineer sbt goldSetEval` (inactive:B10-alias-pending); matrix gates "Local or EMR sample run" (hadoop-engineer) and "Gold-set evaluation" (entity-resolution-engineer); human routing row "gold-set labels"; Human gates Phase 5; G9; A1/A2 HUMAN-row precedent; decisions-01 item 4, decisions-04, decisions-05, decisions-06 (m4, m5), decisions-14. Proposed: G9-a rev 2 (gold/** -> HUMAN, adds the .sha256 freeze record and the dev split), G9-b rev 2 (variant a or b), G9-c (held-out label read fence), S1/S2 optional.
PRIVILEGE DELTA: see section 4. hadoop-engineer and entity-resolution-engineer each widened within B10 (held-out rows narrowed to ask); every identity narrowed by G9-c; orchestrator unchanged; gold/** UNASSIGNED -> HUMAN.
FLOOR INVARIANTS: all intact. No guard, deny rule, hook registration, formatter, test gate, stale-marker refusal, secret deny, terraform/AWS/EMR deny, force-push deny or constitutional protection is weakened. G9-c and S2 only add refusals, each with an actionable message (rule heldout-label-read, with what to do instead).
AUDIT RESULTS: Collision: no new agent, command or hook registration; the new shapes are unique and per identity; gold/* overlaps no agent pattern (first match after *.sbt). Orphan: no new hook scripts; bash-allowlist.tsv and probes.tsv stay referenced; the new guard functions are called from read_check and bash_check. One-writer: gold/** has one owner row (HUMAN); gold/**/*.sbt stays UNASSIGNED; the freeze record is human-owned, not in build.sbt.
COMPLETE DIFF: section 3, items A-G (variant-dependent hunks marked).
OUT-OF-SCOPE ITEMS: aliases, tasks, fork/envVars and .gitignore lines (build-engineer, BE-P5-01); pipeline output directories and distinct-pair reporting (hadoop-engineer, HD-P5-01); per-split generator commands and an optional --manifest-sha256-file argument (entity-resolution-engineer, ER-P5-14); config file ownership for SCHOLARCHAIN_CONFIG_FILES (build-engineer with entity-resolution-engineer/domain-engineer); all gold/** content (human).
RATIFICATION REQUIRED: Yes. A human must review and ratify this amendment (choosing the variant and options), apply it with no Claude Code session open, see validate-agent-files.sh and --probes pass, commit, and start a fresh session. Nothing here is effective before then, and no localPipeline* or goldSetEval* command may run before then.
```

---

# FE-P5-02 addendum (after the ER-P5-14 and HD-P5-01 input), verbatim

Orchestrator note: where this addendum and the rev 2 text above differ, the addendum wins (the agent's own rule). Still a draft; nothing applied.

FE-P5-02 addendum: changes to FE-P5-01 rev 2 after the ER-P5-14 and HD-P5-01 input. The rev 2 report I already sent is still the base. Where the two differ, this addendum wins. Nothing has been written, run or activated.

I only read files: the two coordinator messages and `ReviewQueueMain.scala` lines 240-299. I made no Bash calls and wrote nothing. Variant (b), one alias per split, is now the only variant drafted. Variant (a) is withdrawn because hadoop-engineer and entity-resolution-engineer both want per-split commands.

## 1. G1: membership argument order (ER-P5-14) — rev 1's command was wrong

I checked `ReviewQueueMain.scala:274-277`. It reads the arguments as `... <manifest> <blind view> <seed> [capacity]`. Rev 1 section 1 D2 printed `... 240 <seed>`, so `240` would be taken as the seed and the real seed as the capacity. The run fails with `capacity must be an integer`.

Corrected command. The human runs it in a terminal; the quotes needed mean no agent row is possible. It uses HD-P5-01's output directory:

```
sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain membership datasets/er-candidates/split-assignment.tsv datasets/samples/er-candidates/PROVENANCE.tsv modules/hadoop/target/local-pipeline/er-candidates/heldout heldout gold/entity-resolution/heldout-v1/er-gold-membership.jsonl gold/entity-resolution/heldout-v1/er-blind-label-view.jsonl <SEED> 240"
```

The `queue` mode loads the configuration (lines 263-266). It must use the same split config as the evaluation (see G3).

**No agent or orchestrator row is added for any `ReviewQueueMain` mode.** That covers membership, queue and diagnostics, which all stay human-run, as ER-P5-14 asked.

## 2. G2: how the held-out SHA reaches the evaluation — two options

| | Option F (recommended): `--manifest-sha256-file=` | Option E: hash embedded in the alias |
|---|---|---|
| Where the freeze value lives | `gold/entity-resolution/heldout-v1/er-gold-membership.sha256`, written only by the human (covered by the `gold/**` -> `HUMAN` row; no new row) | a literal in `build.sbt`, written by build-engineer from `human-gate.md` |
| Code change | ER adds `--manifest-sha256-file=<path>`. It refuses a missing file, a symlink, or anything other than exactly 64 lowercase hex characters plus an optional newline. It refuses if both SHA flags are given. ER defines and tests this. | none in ER. build-engineer edits the alias each time the membership is re-frozen |
| One-writer and self-approval | the freeze record stays human-owned. No agent can change the value the evaluation is checked against | the value agents are checked against sits in an agent-owned file. Only review of the `build.sbt` diff catches a wrong or altered hash. It also mixes a one-run fact into build definition |
| Constitutional rows | same as below; justification says "human-owned freeze record" | same row shape; justification says "freeze sha256 embedded by build-engineer" |
| Probes | add `BLOCK` for ER, build-engineer and orchestrator Write of the `.sha256` file (already in rev 2 G9-a2), and `ALLOW` for ER Read of it (already in rev 2 G9-c) | the `.sha256` file is not needed; drop those probes |

Final `goldSetEval*` rows under Option F. Columns are separated by one TAB, and every row is ASCII:

```tsv
entity-resolution-engineer	active	allow	-	sbt goldSetEvalDev	Phase 5 dev-split evaluation diagnostics (amendment G9-b rev 2, B10): build-engineer alias; fixed dev inputs and config under gold/; never final metrics; writes nothing
entity-resolution-engineer	active	ask	-	sbt goldSetEvalHeldout	Phase 5 held-out gold-set evaluation (amendment G9-b rev 2, B10): build-engineer alias; fixed held-out inputs, config and the human-owned freeze record er-gold-membership.sha256 under gold/; writes nothing; ask on every run
```

Under Option E, the held-out justification instead ends with: `...fixed held-out inputs and config under gold/; freeze sha256 embedded in the alias by build-engineer from human-gate.md; writes nothing; ask on every run`.

## 3. G3: `SCHOLARCHAIN_CONFIG_FILES`

**Not recommended:** setting the variable in the Claude Code process environment. Every agent and every sbt run would inherit it. It is invisible to review, and no row accepts an `X=y` prefix (correctly).

**Recommended:**
- The `goldSetEval*` tasks fork the JVM and set `SCHOLARCHAIN_CONFIG_FILES` through the forked run's `envVars`, pointing at a fixed per-split file. This is a build-engineer acceptance criterion.
- The file is `gold/entity-resolution/<split>-v1/er-config.conf`, owned by the human under the existing `gold/**` row, so no new row is needed.
- The same file is used for the human's `queue` run of that split, so the configuration-mismatch check in `GoldSetEvalMain` (thresholds and band recorded on each row) matches by construction.
- The human then fixes which settings are evaluated. That also blocks an agent-side tuning loop on held-out data.

**Is a ratified placeholder path needed?** No, provided the file holds no secret values. Thresholds, versions, model IDs and secret names or ARNs are configuration, not secrets. "Approved local placeholders" covers stand-ins for secret material, and every `.env*` file stays unreadable.
- If the config schema ever needs a secret value, stop. That needs its own placeholder amendment naming the path and its owner.
- If the human would rather keep the evaluation config in an agent-owned path (for example ER resources, or a new `config/**` row), that is decision D9. Its tradeoff: ER could change the evaluated settings between held-out runs. Each run's `settings` line would show it, and the ask gate limits it.

## 4. HD-P5-01 folded in (variant (b) only)

Final `localPipeline*` rows. Columns are separated by one TAB, and every row is ASCII:

```tsv
hadoop-engineer	active	allow	-	sbt localPipelineDev	Phase 5 local bounded pipeline, dev split (amendment G9-b rev 2, B10): build-engineer alias for hadoop/Test/runMain ErSplitPipelineMain dev; reads datasets/samples/er-candidates/dev and the committed dev run manifest; writes only gitignored modules/hadoop/target/local-pipeline/er-candidates/dev
hadoop-engineer	active	ask	-	sbt localPipelineHeldout	Phase 5 local bounded pipeline, held-out split (amendment G9-b rev 2, B10): build-engineer alias for hadoop/Test/runMain ErSplitPipelineMain heldout; reads datasets/samples/er-candidates/heldout and the committed held-out run manifest; writes only gitignored modules/hadoop/target/local-pipeline/er-candidates/heldout; ask on every run
```

- **Dedicated rows are required.** HD-P5-01 is right: the `<SBT:hadoop>` slot only admits `compile`, `Test/compile`, `test`, `scalafmt`, `Test/scalafmt` and `scalafmtCheck` (`SBT_TASKS`, guard line 66). Neither `runMain` nor arguments can pass the character gate or the slot.
- **Audit: nothing to report, and also nothing observed.** Output under `modules/hadoop/target/` is gitignored (`/modules/hadoop/target/`), so it is never hashed and is not reported. The flip side is that a write there by any process is invisible to the audit. That is acceptable for derived output. Its integrity comes from determinism and the run manifest, not from the audit.
- **Correction to rev 2, section 2 criterion 4.** Withdraw my suggested `.gitignore` line `/modules/hadoop/local-runs/`. HD-P5-01 keeps **committed** run manifests in `modules/hadoop/local-runs/`, and ignoring that directory would hide them. Those manifests are hadoop-engineer territory and git-visible. Writes by hadoop-engineer (Write/Edit, or the pipeline under the hadoop rows) are own-territory and not reported; any other identity's write is reported.
- **Risk: `sbt clean` deletes the held-out run.** Any identity's allowed `sbt clean check` removes `modules/hadoop/target/`. The held-out run that the frozen membership and queue were generated from would be gone. Mitigations, for the human to choose (D10):
  - (i) Accept it, because the pipeline is deterministic. The run manifest plus a recorded output digest let `sbt localPipelineHeldout` regenerate the run, and the frozen membership's `membershipRunId` and member check detect any difference. I recommend that hadoop-engineer print an output digest.
  - (ii) Move the output to a gitignored directory outside `target/`, inside `modules/hadoop/`. That needs one anchored `.gitignore` line from build-engineer, and it must not be `local-runs/`.
- **`Test/runMain` dependency:** the alias runs code from the hadoop test classpath. That is fine for local runs, but code-reviewer should review `ErSplitPipelineMain` before the first run, because it is opaque code executed under an allow row.
- **ER dev diagnostics:** `target/er-review/dev-diagnostics/<run>/` sits under the gitignored root `/target/` and is human-run (ReviewQueueMain `diagnostics`). Its ownership is UNASSIGNED but it is ignored, so it is not audited. No row is needed. `sbt clean` removes it too.

## 5. Changes to the rev 2 diffs

- **Item C (`bash-allowlist.tsv`):**
  - Replace line 96 with the two `localPipeline*` rows in section 4.
  - Replace line 100 with the two `goldSetEval*` rows in section 2 (Option F wording, or Option E).
  - The table goes from 119 to 121 rows.
- **Item F (`docs/agents.md`):**
  - The Bash table gets the same four rows, rendered as `| `id` | status | mode | prov | `spec` | why |`, with "121 rows" and the new sha256.
  - The `gold/**` ownership row's description becomes: "Holds per-split membership manifests, freeze records (`*.sha256`), evaluation configuration (`er-config.conf`, no secret values), blind views, review queues and labels, written only by the human".
  - The Phase 5 human-gate bullet adds: "write `er-config.conf` per split and use it for both the queue run and the evaluation".
  - Drop every variant (a) hunk.
- **Item E (`probes.tsv`):**
  - Drop rev 1's G9-b block and the variant (a) lines.
  - Replace the rev 2 control line `[G9-a2] control: hadoop-engineer Write modules/hadoop/local-runs/dev/x` with:

```tsv
ALLOW	enforce-ownership.sh	[G9-a2] control: hadoop-engineer Write committed run manifest	{"tool_name": "Write", "agent_type": "hadoop-engineer", "tool_input": {"file_path": "@ROOT@/modules/hadoop/local-runs/er-candidates.dev.run-manifest.json", "content": "x"}}
BLOCK	enforce-ownership.sh	[G9-a2] entity-resolution-engineer Write held-out eval config	{"tool_name": "Write", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-config.conf", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a2] entity-resolution-engineer Write hadoop run manifest	{"tool_name": "Write", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/modules/hadoop/local-runs/er-candidates.heldout.run-manifest.json", "content": "x"}}	owned by
ALLOW	guard-dangerous.sh	[G9-c] control: entity-resolution-engineer Read held-out eval config	{"tool_name": "Read", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-config.conf"}}
```

- The `owned by` substring in the cross-owner probe is an assumption: I did not re-read `enforce-ownership.sh`'s cross-owner message in this run. If `--probes` fails on that line only, replace the substring with the hook's actual wording.
- **Items A, B, D and G** are unchanged. S1 still lists `Bash(sbt localPipelineDev)` and `Bash(sbt goldSetEvalDev)`.

## 6. New and updated decisions

- **D1 settled:** variant (b).
- **D9:** where the evaluation config lives. Recommended: human-owned `gold/**/er-config.conf`.
- **D10:** the held-out run output under `target/` (deleted by `sbt clean`): accept with determinism plus a digest, or move it outside `target/`.
- **D11:** Option F or Option E for the freeze SHA. Recommended: F.
- D2 to D8 are unchanged.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: FE-P5-02 addendum: fold the ER-P5-14 (G1-G3, output paths) and HD-P5-01 (per-split aliases, output and manifest paths) input into the FE-P5-01 rev 2 draft; prepare only
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read only: ReviewQueueMain.scala lines 240-299)
TEST RESULTS: not applicable
VALIDATION RESULTS: Nothing was executed. Read-only: G1 confirmed at ReviewQueueMain.scala:274-277 (order is seed, then capacity); queue mode requires the configuration (lines 263-266). The new rows parse under guard-dangerous.sh load() and parse_spec (fixed words), are ASCII, contain no pipe characters, and trip no tripwire. JSON parse, bash -n, test -x and frontmatter checks were not run; the human runs them after ratification (rev 2 section 5).
ASSUMPTIONS: ErSplitPipelineMain takes the split as its only argument and writes only under modules/hadoop/target/local-pipeline/er-candidates/<split>/ (HD-P5-01 report, not re-verified). The ScholarChain config can be complete without secret values. sbt forked runs honour envVars (build-engineer to confirm). The cross-owner refusal message contains "owned by" (not re-verified).
RISKS: sbt clean removes the held-out run and the ER dev diagnostics (D10). Ignored outputs are not audited. Under Option E the freeze value is agent-owned. The probe lines have not been observed passing. Test-classpath pipeline code runs under an allow row and needs code-reviewer review before its first run.
MANUAL ACTION REQUIRED: Human: decide D9-D11 (plus D2-D8 from rev 2); use the corrected G1 command order. Orchestrator: archive this addendum with rev 2. Give build-engineer (BE-P5-01) the fork plus envVars pointing at gold/**/er-config.conf, the fixed --manifest-sha256-file argument (Option F), and the per-split aliases. Give entity-resolution-engineer the --manifest-sha256-file work order if F is chosen. Ask hadoop-engineer for an output digest if D10(i) is chosen.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human decisions; BE-P5-01 and ER changes with sbt check and code-reviewer; human applies the amendment with all sessions closed; validator and --probes PASS; commit; fresh session
MATRIX BASIS: as in rev 2 (B10 rows; matrix gates for hadoop-engineer and entity-resolution-engineer; G9; decisions-01, 04, 05, 06, 14), plus decisions-06 m4 (recorded manifest SHA-256) for G2.
PRIVILEGE DELTA: unchanged from rev 2 variant (b). hadoop-engineer gains localPipelineDev (allow) and localPipelineHeldout (ask); entity-resolution-engineer gains goldSetEvalDev (allow) and goldSetEvalHeldout (ask); all identities narrowed by G9-c; no ReviewQueueMain row for anyone; orchestrator unchanged; no new ownership row (config and freeze record fall under gold/** HUMAN).
FLOOR INVARIANTS: all intact; nothing in this addendum weakens a guard, deny rule, hook, formatter or test gate.
AUDIT RESULTS: Collision: four unique shapes, one identity each. Orphan: no new hooks or files beyond rev 2. One-writer: gold/** HUMAN covers manifests, freeze records, config, blind views, queues and labels; modules/hadoop/local-runs/** stays hadoop-engineer; no artifact has two owners.
COMPLETE DIFF: rev 2 section 3, as amended by section 5 of this addendum.
OUT-OF-SCOPE ITEMS: alias, fork and envVars settings (build-engineer); --manifest-sha256-file (entity-resolution-engineer); output digest and output location (hadoop-engineer); config content and all gold/** files (human).
RATIFICATION REQUIRED: Yes. A human must review and ratify rev 2 together with this addendum, apply it with no session open, see the validator and --probes pass, commit, and restart before any localPipeline* or goldSetEval* row is active or run.
```
