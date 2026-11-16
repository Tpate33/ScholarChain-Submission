# FE-P5-01: draft constitutional amendment G9-a / G9-b (NOT ratified, NOT applied)

Orchestrator archive notes (2026-11-06):
- This is the factory-engineer's final report, verbatim. It is a **draft for human ratification
  only**. No constitutional file was created or modified, as confirmed by `git status --porcelain`
  after hand-back: no `.claude/**`, `CLAUDE.md` or `docs/agents.md` change.
- The agent was interrupted once by an API usage limit and resumed.
- The harness flagged an instruction-shaped pattern ("settings-json") in the report. It
  corresponds to the agent's optional suggestion O1 (a settings.json `Read` deny for gold
  labels). It is recorded here as a proposal for the human and was not acted on.
- Tabs: the agent notes that the `.tsv` column separators must be real TAB characters. If this
  archived copy shows spaces, rebuild each line with one TAB between columns before applying.
- Do not apply any of this while a Claude Code session is open (see section 5).

---

FE-P5-01: draft constitutional amendment for the gold-label path (G9-a) and the gold-set evaluation command (G9-b). This is a draft for human ratification only.

I created and modified no files and made no Bash calls; I only read files. The live hooks read `lib-ownership.sh` and `bash-allowlist.tsv` on every tool call, so the diffs below must be applied only as described in section 5.

Note on tabs: in the `.tsv` blocks below, the column separators are real TAB characters. If your viewer turned them into spaces, rebuild each line with one TAB between columns.

## 1. Decisions and their reasons

**D1. One new row: `gold/**` -> `HUMAN`.** Everything the evaluation reads lives under it:
- `gold/entity-resolution/heldout-v1/er-gold-membership.jsonl`: the frozen membership manifest, `er-gold-membership.v1`.
- `gold/entity-resolution/heldout-v1/er-blind-label-view.jsonl`: the blind view, the only file labelers open.
- `gold/entity-resolution/heldout-v1/er-review-queue.jsonl`: the internal queue. It shows system decisions, so labelers must never see it.
- `gold/entity-resolution/heldout-v1/er-gold-labels.jsonl`: the human labels, `er-gold-label.v2`.

Why one row rather than several: separate rows would all have the owner `HUMAN`, so they would add rows to check without changing any decision.

Why the manifest and blind view also go in `gold/**` (I rejected writing them into `modules/entity-resolution/**` and freezing them by a human commit): the ER engineer is the agent being evaluated. If it owned any input to its own gate, it could rewrite that input with Write at any time, and only `git diff` would show it. That conflicts with "No agent approves its own work" (`CLAUDE.md`, Authority boundaries 12). Under `HUMAN`, no identity, the orchestrator included, can write these files.

**How "frozen before labeling" is enforced:**
- **Deterministic:**
  - `enforce-ownership.sh` blocks every Write, Edit, MultiEdit and NotebookEdit under `gold/**` for all 18 agents and the orchestrator. The match is case-insensitive and also catches `..` paths.
  - No Bash allowlist row writes `gold/**`. The new row writes nothing: `GoldSetEvalMain.scala:20` says "Writes nothing".
  - `ReviewQueueMain` refuses to overwrite an existing file (per the ER-P5-02 and ER-P5-05 reports).
  - `GoldLabels.ingest` recomputes `memberId` and refuses any label for a non-member.
- **Detected after the fact:** `sbt` runs code the agents wrote, and that code could still write there. `audit-bash-writes.sh --post` reports such a write as outside the caller's territory.
- **Procedural (human):** commit the manifest and blind view in a commit that contains no labels. Record the manifest's sha256 in `docs/agent-runs/phase-05/human-gate.md`. Commit the labels only in a later commit. `git log -- gold/` then proves the order.
- **Optional, not part of this amendment:** `entity-resolution-engineer` could print the manifest sha256 in the `GoldSetEvalMain` report so each run can be compared with the recorded hash. Separate work order.

**Placement and scope of the row:**
- It goes directly after `**/*.sbt`, so `gold/**/*.sbt` stays `UNASSIGNED` and the F1 rule "every other `*.sbt` path is `UNASSIGNED`" stays literally true. It comes before every agent territory.
- `gold/*` matches only paths that start at the repository root. `modules/entity-resolution/gold/...` stays with ER, `docs/gold/...` stays with documentation-engineer, and a root file named `gold` stays `UNASSIGNED`. Probes below prove each case.

**D2. Who generates the gold files.** The human runs the generators in a terminal. If an agent ran a command that writes into `HUMAN` paths, the ownership rule would be broken, so no agent or orchestrator row is added for this. The commands below need the fork and `baseDirectory` settings from D3, which are not in the build yet, so they work only after BE-P5-xx:
```
sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain membership datasets/er-candidates/split-assignment.tsv datasets/samples/er-candidates/PROVENANCE.tsv <heldout run dir> heldout gold/entity-resolution/heldout-v1/er-gold-membership.jsonl gold/entity-resolution/heldout-v1/er-blind-label-view.jsonl 240 <seed>"
sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain queue gold/entity-resolution/heldout-v1/er-gold-membership.jsonl <heldout run dir> gold/entity-resolution/heldout-v1/er-review-queue.jsonl"
```
- The 240 capacity and seed follow decisions-04.
- When the policy version changes, the human moves the old queue aside and regenerates it. The report prints `policy=...`, which shows which policy was evaluated.

**D3. The evaluation command: activate only `sbt goldSetEval` for `entity-resolution-engineer`, in mode `ask`.** The ratified row already exists as `inactive:B10-alias-pending`.
- Why `ask` rather than `allow`:
  - Each run reads held-out human labels and prints false positives and false negatives. Requiring human approval per run stops an evaluate-tune-evaluate loop, which decisions-01 item 4 forbids ("Keep evaluation labels out of policy development and threshold tuning") and the README:1247 gate checks.
  - The guard's own `ask` holds whatever permission mode the session uses. Today the command is also absent from the `settings.json` allow list.
- No orchestrator row and no human row is needed (humans run commands in a terminal, outside the hooks).
- No `reviewQueue` row is added.
- `settings.json` and `CLAUDE.md` do not change.

**Dependency: build-engineer adds the alias first** (separate work order, BE-P5-xx; `build.sbt` is not constitutional):
```scala
addCommandAlias(
  "goldSetEval",
  "entityResolution/runMain scholarchain.entityresolution.evaluation.GoldSetEvalMain " +
    "gold/entity-resolution/heldout-v1/er-gold-membership.jsonl " +
    "gold/entity-resolution/heldout-v1/er-review-queue.jsonl " +
    "gold/entity-resolution/heldout-v1/er-gold-labels.jsonl 10"
)
// on the entityResolution project:
Compile / run / fork := true,                                   // GoldSetEvalMain calls sys.exit
Compile / run / baseDirectory := (ThisBuild / baseDirectory).value  // relative paths resolve from the repo root
```
- Acceptance criteria:
  - `runMain` actually honours the fork and `baseDirectory` settings. If it does not, the command may instead be implemented as a task that passes absolute paths built from `ThisBuild / baseDirectory`.
  - Exit code 2 from `GoldSetEvalMain` makes sbt fail with a non-zero exit.
  - The command writes nothing outside `target/`.
  - It is not part of `check`.
  - `sbt check` passes.
- build-engineer has no row to run `goldSetEval` itself. The human checks it once in a terminal; with no gold files present it should fail with `refused: ... cannot read gold/...`.
- Use `maxErrors` = 10, because README:1233 asks for at least ten false positives and ten false negatives.

## 2. Diffs, file by file

### A. `.claude/hooks/lib-ownership.sh`
Basis: the matrix lists human gold labels as unowned (G9); spec Phase 5 gate "Gold labels are human-reviewed" (README.md:1245).
```diff
--- a/.claude/hooks/lib-ownership.sh
+++ b/.claude/hooks/lib-ownership.sh
@@ -44,6 +44,9 @@
     # sbt loads *.sbt in every subproject base as build policy (Phase 2 review F1):
     # only build.sbt and project/** above are writable; every other *.sbt fails closed.
     *.sbt)                                                      echo "UNASSIGNED" ;;
+    # --- human-owned gold set (amendment G9-a): frozen membership, blind view, queues, labels.
+    # After *.sbt so gold/**/*.sbt stays UNASSIGNED (F1); before every agent territory.
+    gold/*)                                                     echo "HUMAN" ;;
     # --- application modules --------------------------------------------------
     modules/core/*)                                             echo "domain-engineer" ;;
     modules/hadoop/*)                                           echo "hadoop-engineer" ;;
@@ -107,4 +110,5 @@
 docker-compose.yaml
 docker-compose.yml
 x/y.scala/x.sbt
+gold/x/y.scala
 modules/core/x/y.scala
```

### B. `.claude/hooks/enforce-ownership.sh` (wording of the message only, so the refusal names the new case)
```diff
--- a/.claude/hooks/enforce-ownership.sh
+++ b/.claude/hooks/enforce-ownership.sh
@@ -70,3 +70,3 @@
   HUMAN)
-    block "'$rel' is human-owned (human gate decisions, LICENSE, personal settings).
+    block "'$rel' is human-owned (human gate decisions, gold-set membership and labels under gold/, LICENSE, personal settings).
 No agent or orchestrator tool call may write it. Acting identity: $identity. Owner: human architect." ;;
```
This item can be dropped on its own; all probes rely only on the unchanged substring `is human-owned`.

### C. `.claude/hooks/bash-allowlist.tsv`, line 100
Basis: the row is already ratified, gated on B10; the matrix gate for ER is "Gold-set evaluation"; README:1236 "Run sbt check and the gold-set evaluation".
```diff
--- a/.claude/hooks/bash-allowlist.tsv
+++ b/.claude/hooks/bash-allowlist.tsv
@@ -99,3 +99,3 @@
 entity-resolution-engineer	active	allow	-	sbt <SBT:entityResolution> <SBT:entityResolution>? <SBT:entityResolution>? <SBT:entityResolution>?	own-module tasks (E2)
-entity-resolution-engineer	inactive:B10-alias-pending	allow	-	sbt goldSetEval	Phase 5 gold-set evaluation
+entity-resolution-engineer	active	ask	-	sbt goldSetEval	Phase 5 gold-set evaluation (amendment G9-b): build-engineer alias; reads gold/** and writes nothing; ask so every run is human-approved
 spark-engineer	active	allow	-	@GIT	matrix row
```
- Row count is unchanged (still 119). No other row changes.
- By reading `guard-dangerous.sh`, I confirmed:
  - the row passes `load()` (status `active`, mode `ask`, provenance `-`);
  - the lower-cased words `sbt goldseteval` match no floor rule or tripwire (`marktestrun`, `apply` and the others);
  - the `@SBT-ALL` and `.gitignore` preconditions still apply.

### D. `.claude/hooks/probes.tsv`: append after the current last line (461)
None of these rows has been run yet; I wrote them by reading the hooks. The human's `--probes` run is their first observation.
```
# FE-P5-01 (G9-a, G9-b): drafted by reading enforce-ownership.sh and guard-dangerous.sh; first observed by the human's --probes run.
BLOCK	enforce-ownership.sh	[G9-a] factory-engineer Write gold labels	{"tool_name": "Write", "agent_type": "factory-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] build-engineer Write gold labels	{"tool_name": "Write", "agent_type": "build-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] domain-engineer Write gold labels	{"tool_name": "Write", "agent_type": "domain-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] dataset-engineer Write gold labels	{"tool_name": "Write", "agent_type": "dataset-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] hadoop-engineer Write gold labels	{"tool_name": "Write", "agent_type": "hadoop-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] entity-resolution-engineer Write gold labels	{"tool_name": "Write", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] spark-engineer Write gold labels	{"tool_name": "Write", "agent_type": "spark-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] storage-engineer Write gold labels	{"tool_name": "Write", "agent_type": "storage-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] rag-engineer Write gold labels	{"tool_name": "Write", "agent_type": "rag-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] service-engineer Write gold labels	{"tool_name": "Write", "agent_type": "service-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] test-engineer Write gold labels	{"tool_name": "Write", "agent_type": "test-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] code-reviewer Write gold labels	{"tool_name": "Write", "agent_type": "code-reviewer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] infra-engineer Write gold labels	{"tool_name": "Write", "agent_type": "infra-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] deploy-engineer Write gold labels	{"tool_name": "Write", "agent_type": "deploy-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] experiment-engineer Write gold labels	{"tool_name": "Write", "agent_type": "experiment-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] documentation-engineer Write gold labels	{"tool_name": "Write", "agent_type": "documentation-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] incident-responder Write gold labels	{"tool_name": "Write", "agent_type": "incident-responder", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] dependency-updater Write gold labels	{"tool_name": "Write", "agent_type": "dependency-updater", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] orchestrator (agent_type absent) Write gold labels	{"tool_name": "Write", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] entity-resolution-engineer Edit frozen membership manifest	{"tool_name": "Edit", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-membership.jsonl", "old_string": "a", "new_string": "b"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] entity-resolution-engineer Write blind view	{"tool_name": "Write", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-blind-label-view.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] entity-resolution-engineer Write review queue	{"tool_name": "Write", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-review-queue.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] entity-resolution-engineer MultiEdit labels	{"tool_name": "MultiEdit", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "edits": []}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] test-engineer NotebookEdit under gold	{"tool_name": "NotebookEdit", "agent_type": "test-engineer", "tool_input": {"notebook_path": "@ROOT@/gold/x.ipynb", "new_source": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] entity-resolution-engineer traversal from own module into gold	{"tool_name": "Write", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/modules/entity-resolution/../../gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] dataset-engineer case variant GOLD/	{"tool_name": "Write", "agent_type": "dataset-engineer", "tool_input": {"file_path": "@ROOT@/GOLD/entity-resolution/x.jsonl", "content": "x"}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] build-engineer skeleton .gitkeep in gold (exemption excludes HUMAN)	{"tool_name": "Write", "agent_type": "build-engineer", "tool_input": {"file_path": "@ROOT@/gold/.gitkeep", "content": ""}}	is human-owned
BLOCK	enforce-ownership.sh	[G9-a] agent_type empty string Write gold	{"tool_name": "Write", "agent_type": "", "tool_input": {"file_path": "@ROOT@/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl", "content": "x"}}	Invalid acting identity
BLOCK	enforce-ownership.sh	[G9-a] gold/x.sbt stays UNASSIGNED (F1 precedes gold)	{"tool_name": "Write", "agent_type": "build-engineer", "tool_input": {"file_path": "@ROOT@/gold/x.sbt", "content": "x"}}	sbt build definition
BLOCK	enforce-ownership.sh	[G9-a] root file named gold is not captured (UNASSIGNED)	{"tool_name": "Write", "agent_type": "build-engineer", "tool_input": {"file_path": "@ROOT@/gold", "content": "x"}}	has no owner
ALLOW	enforce-ownership.sh	[G9-a] control: entity-resolution-engineer Write modules/entity-resolution/gold/x.jsonl (not over-captured)	{"tool_name": "Write", "agent_type": "entity-resolution-engineer", "tool_input": {"file_path": "@ROOT@/modules/entity-resolution/gold/x.jsonl", "content": "x"}}
ALLOW	enforce-ownership.sh	[G9-a] control: documentation-engineer Write docs/gold/notes.md (not over-captured)	{"tool_name": "Write", "agent_type": "documentation-engineer", "tool_input": {"file_path": "@ROOT@/docs/gold/notes.md", "content": "x"}}
ASK	guard-dangerous.sh	[G9-b] entity-resolution-engineer sbt goldSetEval (active, ask)	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "entity-resolution-engineer"}	sbt goldSetEval
BLOCK	guard-dangerous.sh	[G9-b] ORCHESTRATOR sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] code-reviewer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "code-reviewer"}	reviewer-read-only
BLOCK	guard-dangerous.sh	[G9-b] factory-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "factory-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] build-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "build-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] domain-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "domain-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] dataset-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "dataset-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] hadoop-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "hadoop-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] spark-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "spark-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] storage-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "storage-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] rag-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "rag-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] service-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "service-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] test-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "test-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] infra-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "infra-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] deploy-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "deploy-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] experiment-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "experiment-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] documentation-engineer sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "documentation-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] incident-responder sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "incident-responder"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] dependency-updater sbt goldSetEval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldSetEval"}, "agent_type": "dependency-updater"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] entity-resolution-engineer direct runMain with own paths	{"tool_name": "Bash", "tool_input": {"command": "sbt entityResolution/runMain scholarchain.entityresolution.evaluation.GoldSetEvalMain gold/a gold/b gold/c"}, "agent_type": "entity-resolution-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] entity-resolution-engineer case variant sbt goldseteval	{"tool_name": "Bash", "tool_input": {"command": "sbt goldseteval"}, "agent_type": "entity-resolution-engineer"}	no-allowlist-entry
BLOCK	guard-dangerous.sh	[G9-b] entity-resolution-engineer sbt check goldSetEval (no chaining)	{"tool_name": "Bash", "tool_input": {"command": "sbt check goldSetEval"}, "agent_type": "entity-resolution-engineer"}	no-allowlist-entry
```
- The probe for `entity-resolution-engineer` running `sbt goldSetEval` returns ASK only if the repository root has a regular `.gitignore` (it does today; existing `sbt check` probes depend on the same thing).
- It also returns ASK before the alias exists, because the probes only test the hook, never sbt.

### E. `docs/agents.md`
Basis: the ownership-table, bash-table and routing mirrors that `validate-agent-files.sh` checks; Known gaps G9; Human gates for Phase 5.
```diff
--- a/docs/agents.md
+++ b/docs/agents.md
@@ -77,2 +77,3 @@
 | `**/*.sbt` | `UNASSIGNED` | amendment F1 (Phase 2 review): sbt loads every subproject `*.sbt` as build policy, so only `build.sbt` and `project/**` above are writable (by `build-engineer`); first match wins, so the constitutional `.claude/**` rows above still take precedence |
+| `gold/**` | `HUMAN` | amendment G9-a (FE-P5-01): spec Phase 5 gate "Gold labels are human-reviewed" (README.md:1245); human instructions 2026-11-04 (blind labeling; labels kept out of policy development) and 2026-11-05 (freeze membership before labeling). Holds the frozen membership manifest, blind view, review queues, and labels, generated or written only by the human; placed after `**/*.sbt` so `gold/**/*.sbt` stays `UNASSIGNED` (F1) |
 | `modules/core/**` | `domain-engineer` | matrix |
@@ -131 +131 @@
-Default deny. A Bash call runs only if it passes the character gate in `guard-dangerous.sh` and matches an ACTIVE row for the acting identity. This table mirrors `.claude/hooks/bash-allowlist.tsv` row for row (119 rows; source sha256 `eacc92bb776577d131d16850575f5b83e3565f333b6af9cd47558d31a425796d`). Group rows ...
+Default deny. A Bash call runs only if it passes the character gate in `guard-dangerous.sh` and matches an ACTIVE row for the acting identity. This table mirrors `.claude/hooks/bash-allowlist.tsv` row for row (119 rows; source sha256 `<NEW-SHA256-OF-EDITED-TSV>`). Group rows ...
@@ -199 +199 @@
-| `entity-resolution-engineer` | inactive:B10-alias-pending | allow | - | `sbt goldSetEval` | Phase 5 gold-set evaluation |
+| `entity-resolution-engineer` | active | ask | - | `sbt goldSetEval` | Phase 5 gold-set evaluation (amendment G9-b): build-engineer alias; reads gold/** and writes nothing; ask so every run is human-approved |
@@ -286 +286 @@
-| Ratification, human gate records, commits, gold-set labels, cleanup execution | `human` | human judgement |
+| Ratification, human gate records, commits, gold-set membership generation and labels (`gold/**`), cleanup execution | `human` | human judgement |
@@ -326 +326 @@
-- **Phase 5:** label and review gold-set examples.
+- **Phase 5:** generate the frozen gold membership manifest and blind view into `gold/**` in a terminal and commit them before any label exists, recording the manifest sha256 in `human-gate.md`; label blindly from the blind view only and commit the labels in a later commit; generate the review queue; approve each `sbt goldSetEval` run (permission `ask`). No agent or orchestrator writes `gold/**`.
@@ -416 +416 @@
-| G9 | `LICENSE`, `reports/**` other than the final report, packaging files (Dockerfiles), human gold-set labels, and the location of version records have no matrix owner. | `LICENSE` is `HUMAN`. Others are `UNASSIGNED`. | Assign paths and owners when Phase 2, 5, and 17 define them. |
+| G9 | `LICENSE`, `reports/**` other than the final report, packaging files (Dockerfiles), and the location of version records have no matrix owner. Human gold-set labels: **resolved by human decision <RATIFICATION-DATE> (amendment G9-a):** row `gold/**` -> `HUMAN`, covering the labels, frozen membership manifest, blind view, and review queues; `sbt goldSetEval` activated for `entity-resolution-engineer` in mode `ask` (amendment G9-b). | `LICENSE` and `gold/**` are `HUMAN`. Others are `UNASSIGNED`. | Assign the remaining paths and owners when Phase 2 and 17 define them. |
```
Notes on this diff:
- Line 131 is shown shortened as `...` only to save space. The real change replaces exactly one value, the 64-hex sha256, and leaves the rest of the line, including "119 rows", unchanged.
- `<RATIFICATION-DATE>` is filled in by the human.

**No change** to `CLAUDE.md`, `.claude/settings.json`, `.mcp.json`, `.claude/agents/*` or `.claude/commands/*`. The role file `entity-resolution-engineer.md` already says, as a timeless rule, that gold labels are human-authored (law 5) and that the agent refuses to touch "human gold labels (human)". The specific paths belong in project memory and the alias, so they are not added to the role file.

## 3. Mirror updates the validator checks, and the sha256 procedure

| Mirror | Change |
|---|---|
| Ownership table vs `scholarchain_owner_of` (sample `gold/x/y.scala` -> `HUMAN`) | E row and A case |
| Table samples vs `SCHOLARCHAIN_ANCHORS` (sorted comparison) | A adds `gold/x/y.scala` |
| One owner row per pattern | `gold/**` is new and unique |
| bash-table vs `bash-allowlist.tsv`, row for row | C and E line 199 must be byte-identical, justification included |
| Embedded source sha256 | E line 131 |
| "119 rows" | unchanged: one row is edited in place, none added |
| Routing table | step text changes only; still exactly one routed owner (`human`) |
| probes.tsv | D |
| `guard-dangerous.sh --validate-allowlist` | the active row is a fixed program word and trips no tripwire |

**sha256:** I cannot compute it without Bash. After applying C (and before or after E), the human runs `shasum -a 256 .claude/hooks/bash-allowlist.tsv` in a terminal and puts the 64-hex value into line 131. As a cross-check, if the value is wrong, `validate-agent-files.sh` fails with `embedded source sha256 ... does not equal bash-allowlist.tsv sha256 <actual>`, and that `<actual>` must match the `shasum` output.

## 4. Privilege delta

| Who | Change | Classification |
|---|---|---|
| `entity-resolution-engineer` | gains exactly one Bash shape, `sbt goldSetEval`, with a human prompt on every run; reads gold inputs and prints metrics and errors; no new write territory | widened, within the ratified B10 row; the mode moves from the ratified `allow` to `ask`, which is narrower |
| `gold/**` | was `UNASSIGNED` (no agent can write); becomes `HUMAN` (no agent can write) | no agent change; it records human ownership |
| `gold/**/*.sbt` | stays `UNASSIGNED` | unchanged |
| The `HUMAN` refusal message | wording only | unchanged authority |
| All other agents and the orchestrator | no new rows, tools, territory or MCP | unchanged |

Still forbidden after the amendment:
- any agent or orchestrator write to `gold/**`;
- direct `runMain` with arbitrary paths;
- `sbt goldSetEval` by any other identity;
- any reviewQueue or membership generator run by an agent;
- the build-engineer skeleton exemption inside `gold/**`.

All floor invariants are untouched: no guard, hook registration, deny rule, formatter or test gate is weakened.

## 5. Activation procedure (in order)

1. The human reviews this draft. Optionally, code-reviewer reviews an archived copy, read-only.
2. Separate work order BE-P5-xx: build-engineer adds the alias and the fork and `baseDirectory` settings (section 1, D3); `sbt check` passes; code-reviewer reviews; the human accepts. The human checks the alias once in a terminal.
3. Close every Claude Code session for this repository. The hooks read the edited files live, so editing them inside an open session would activate the amendment early.
4. The human applies A, B, C, D and E in a terminal and inserts the sha256 (section 3).
5. In the terminal, `.claude/hooks/validate-agent-files.sh` passes, then `.claude/hooks/validate-agent-files.sh --probes` passes. Both must print `RESULT: PASS`.
6. The human records the ratification in `docs/agent-runs/phase-05/human-gate.md` and commits.
7. Start a fresh Claude Code session. After that, the data steps run in this order:
   - the dblp download and the hadoop-engineer held-out per-split run;
   - membership generation into `gold/**`, then a commit with the manifest sha256 recorded;
   - blind labeling and a label commit;
   - queue generation;
   - the orchestrator routes `entity-resolution-engineer` to run `sbt goldSetEval`, and the human approves the prompt.

**Must not happen before ratification:**
- no agent applies or partly applies these diffs;
- no edit to the live constitutional files while a session is open;
- no activation of the row before the alias exists;
- no gold-set evaluation.

Human gold work done while a session is open produces one UNATTRIBUTED refusal at the next Bash call. The remedy is the documented one: review `git status`, then re-run the same command.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: FE-P5-01 (Phase 5 main): draft constitutional amendment for the human-owned gold-label path and the gold-set evaluation command (proposal only, not activated)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash calls; Read only)
TEST RESULTS: not applicable (no behaviour change applied; no sbt run)
VALIDATION RESULTS: Nothing was executed; every result here comes from reading the code. Read: validate-agent-files.sh (mirror checks: ownership table vs owner_of and the sorted anchors, the B1 row-for-row check and embedded sha256, routing one-owner rule, probe runner and its ASK detection); guard-dangerous.sh (load() row grammar, rows_for, the dormant/entry-inactive logic, tripwire(), validate(), the sbt .gitignore precondition); enforce-ownership.sh (HUMAN branch, skeleton exemption, the UNASSIGNED *.sbt message, identity check); lib-ownership.sh; verify-tests-ran.sh (is_source ignores *.jsonl); settings.json (no goldSetEval allow rule); GoldSetEvalMain.scala (arguments and "Writes nothing"). From this reading I expect A-E to pass the validator and --probes once the sha256 is inserted. JSON parse, bash -n, the executable-bit check and the frontmatter check were not run; the human's validator run in step 5 runs all of them.
ASSUMPTIONS: No gold/ directory exists today (the session-start git status shows none). The README sets no top-level directory list that forbids gold/. The ER-reported behaviour (overwrite refusal, memberId validation) is accurate. The tsv row count is 119 (it is not re-counted; the edit is in place). Claude Code prompts for Bash commands not in the settings allow list, and the guard's ask applies anyway.
RISKS: (1) sbt runs ER-authored code, so evaluation code could write anywhere; this is detected after the fact, not prevented. Compensating control: code-reviewer reviews GoldSetEvalMain and its dependencies before the first run. (2) Leakage: entity-resolution-engineer can still Read gold labels with the Read tool. Optional narrowing O1, not included: add "Read(./gold/**/er-gold-labels*)" to the settings.json deny list. Its Grep/Glob coverage is best-effort and unverified until post-restart probes, and it blocks the orchestrator and reviewer too. (3) The FP/FN lists printed by goldSetEval are seen by the evaluated agent; the ask gate limits how often. (4) If runMain ignores the fork/baseDirectory settings, paths resolve from the module directory; the build-engineer acceptance check covers this. (5) The probe rows have not been observed passing yet. (6) Freezing the manifest relies partly on human commit order.
MANUAL ACTION REQUIRED: Human: ratify or reject G9-a, G9-b, the B message change and the optional O1; compute and insert the sha256; apply the diffs with all sessions closed; run the validator and --probes; commit; restart. Orchestrator: issue BE-P5-xx (alias) before activation.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review of this draft, then BE-P5-xx alias (build-engineer, sbt check, code-reviewer), then human apply, validator and --probes PASS, commit, fresh session
MATRIX BASIS: the inactive:B10-alias-pending row for entity-resolution-engineer `sbt goldSetEval`; the matrix gate "Gold-set evaluation" for entity-resolution-engineer; the Human gates entry for Phase 5 (gold-set labels); the human routing row "gold-set labels"; G9; precedent A1/A2 (HUMAN rows); README.md:1236 and 1245. Proposed amendments: G9-a (row gold/** -> HUMAN), G9-b (activate the row in mode ask).
PRIVILEGE DELTA: entity-resolution-engineer: widened by one Bash shape (`sbt goldSetEval`, mode ask) as already ratified for B10, with the mode narrowed from allow to ask. All other agents and the orchestrator: unchanged. gold/**: UNASSIGNED -> HUMAN, no agent gains a write.
FLOOR INVARIANTS: all intact. No change to dangerous-command guards, test-stop hooks, the stale-marker refusal, formatter behaviour, secret denies, terraform, AWS or EMR denies, force-push denies, or constitutional protection. Every refusal on the new path keeps an actionable message; B makes the HUMAN message name gold/.
AUDIT RESULTS: Collision: no new agent, command or hook, and no new hook registration; gold/* overlaps no agent pattern (first match after *.sbt). Orphan: no new scripts; bash-allowlist.tsv and probes.tsv stay referenced. One-writer: gold/** has exactly one owner row (HUMAN); gold/**/*.sbt stays UNASSIGNED by order.
COMPLETE DIFF: section 2, items A-E.
OUT-OF-SCOPE ITEMS: the goldSetEval alias and run settings (build-engineer); an optional manifest-sha256 line in the GoldSetEvalMain report (entity-resolution-engineer); reviewQueue or membership commands for agents (not proposed; generation stays human-run); the policy and label-schema decisions A/B/C and er-policy.v3 (human).
RATIFICATION REQUIRED: Yes. A human must review and ratify this amendment, apply it with no session open, see the validator and --probes pass, commit it, and start a fresh session. Nothing in it is effective before then.
```
