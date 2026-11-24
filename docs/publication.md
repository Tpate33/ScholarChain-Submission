# Sanitized publication candidate — unpublished

This is a private, local candidate for a separately approved independent public
repository. No public URL/repository, tag, push, Teams receipt or new human
acceptance is established by preparing these files. The original private repository
and its history must remain unchanged. Do not publish by forking or pushing original
private objects; publication requires its own reviewed repository and fresh approval.

The professor’s latest supplied instructions require a public, anonymously cloneable
repository and its URL submitted through Teams by November 25, 2026, 11 PM CST.
They supersede the original private-repository wording retained in README. The video,
final tag/full SHA, report links and submission receipt remain pending. General
classmate scale/model guidance is not an individual approved deviation.

## History, data and verification provenance

Original source checkpoint: `e08aa0938bed6cb704b8cff990a6796aa7f2af50`.
Original offline implementation: `09c3d4cda6b0935aa607dcddecfdbefab84b6ddb`.
The initial derived history tip, before candidate documentation, is
`621e34f6bb8972c52b7bd80cb4f196af005dcf9d`.
See the [publication evidence](agent-runs/publication-01/publication-record.md) and
[original-to-derived commit map](agent-runs/publication-01/commit-map.json).
Original acceptance records and SHA references are preserved as historical source
provenance. They do not become fresh ratification or signatures for derived commits.
The existing [release manifest](../release-manifest.json) is the historical Integration
02 manifest, not the canonical candidate publication record.

Only the two authorized historical paths were changed during history sanitization.
Application code, manifests and demo fixtures remain identical at that transition.
The [approved dev Parquet scan](agent-runs/publication-01/parquet-audit.json) covered
29 files, 2,809 rows, 73,665 string values and 3,025
footer strings: zero configured credential/private-evaluation indicators and zero
identity mismatches were reported. This is a bounded scanner result, not a universal
privacy guarantee or permission to inspect held-out data. No new label or held-out
reads are part of reproduction.

Original-history verification progressed through Integration 02 (2,851 tests),
production connections (2,882) and offline AWS preparation (**2,889 passed, zero
failures/errors, one skipped, 243 suites**). These historical gates and scoped reviews
are archived, not new sanitized clean-clone results.

A fresh local clone of candidate **Q `69d63538`** subsequently passed
`sbt -Dsbt.offline=true check pipelineDemoClasspath`: **2,886 passed, zero
failures/errors, four skipped, 243 suites**, exit 0, 472 seconds. Four worker CLI
relocation checks and 64 dependency-fence checks passed. All seven HTTP smoke checks
passed (six HTTP 200 responses and the expected unauthenticated 401). This used the
existing dependency cache and unchanged committed dev fixtures; it is not anonymous
public-clone evidence or a cold dependency-bootstrap result.

The three additional skips are `DecisionExportSuite`, `GroupingExportSuite` and
`SampleQueueProbeSuite`, which need generated
`modules/hadoop/target/test-work/local-pipeline/curated-a` output absent from a fresh
clone. The fourth is the existing semantic-neighbor skip. No tests were weakened and
no private labels were read. The original 2,889/one-skip result remains historical.

At Q the 939 behavior probes passed but the complete validator exited 1 on
ShellCheck imported-global/cross-file-assignment findings. The user subsequently
ratified a minimal candidate-only factory lint repair at `00bf656a`: two hooks,
five insertions and one deletion. The affected complete validator now **PASS, exit 0,
zero failures**, with **888 table + 51 fixture = 939 behavior probes passing**.
All nine shell hooks passed syntax and ShellCheck checks; JSON, role definitions,
registrations, executable bits, collision/orphan and ownership checks passed.
These are two explicit lint-only hook exceptions to the original baseline, with no
matrix, tool, guard-logic or privilege change. Application code, build definitions,
tests and manifests remain unchanged. No full application gate, HTTP replay or
Parquet scan was repeated after this repair; Q’s application results remain the
attributed results above, not new-head full-suite results. See the
[factory repair evidence](agent-runs/publication-01/factory-lint-repair.json).
Independent publication security review approved Q within its recorded scope; it
is not a new application-wide review or a universal privacy guarantee. See the
[publication record](agent-runs/publication-01/publication-record.md) for exact evidence.
Publication itself remains unauthorized; the scoped lint ratification is not general
acceptance of the candidate or permission to publish.

No successful AWS run, live store/model validation or elasticity result is
established. Container application verification is blocked by zero-byte `libjvm.so`
in the Java base; Docker recovery/new environments remain deferred.

## Credential-free local reproduction plan

Use the actual approved candidate/submission full SHA, not an earlier source clone.
Once a separate repository is approved and public, verify its anonymous HTTPS access
with no helpers, askpass, authorization headers or cookies; disable global/system Git
configuration for that check. Do not change `HOME`, use credentials or invent a public
URL. Record clone URL, resolved full SHA, tag if any, and initial clean status. Anonymous
access and candidate test success are separate gates.

Required: Git, Bash, JDK 17 including `javac`, sbt **1.11.7**, Python 3. Scala **3.3.6**
and other dependencies are pinned in `project/versions.properties`. Initial Maven
resolution may need network access; offline operation requires a separately documented
populated cache. No tool installation is implied. From an already fresh clone:

```sh
java -version
javac -version
python3 --version
sbt check pipelineDemoClasspath
python3 scripts/pipeline-demo-smoke.py --output target/clone-evidence
```

The commands above are reproduction instructions; the actual Q gate used the offline
flag and populated cache as recorded above. Do not add `sbt clean` to
an already fresh clone. The smoke owns/stops its loopback JVM and checks health,
query, graph, citation, explain, replay and missing-key authentication. It expects
three coauthors of Ailing Zeng supported by `work:dblp:conf/aaai/ZengCZ023` and the
original accepted analysis run ID. Archive exact command exits, gate counts, JSON and
startup output before removing generated artifacts. No AWS, paid calls or Docker are
needed. The DBLP-derived demo deliberately uses **in-memory stores, template answers
and hashed-token stand-in embeddings**; its claims do not validate concrete adapters
against remote services or real model inference.

### Historical pipeline boundary

The unchanged demo fixtures can be read without historical pin repair. Full historical
ER/Spark replay is different: `erDecisionGroupingDev` manifests pin `8c1bee…` and the
Spark configuration pins `148b4b…`; guards require ancestor identity plus unchanged
scoped code. Rewritten ancestry can fail those checks. Do not disable guards, reseal
old outputs, silently replace old IDs, or claim automatic full-pipeline reproduction.
New dev inputs/configuration and their pins require separate owner preparation and
review. Opt-in `sparkIT` also requires the three pinned dev archive directories named
in `project/spark-it.properties`, outside Git; do not substitute held-out artifacts.
Routine `check` exercises guards and fixtures, not actual held-out evaluation,
`sparkIT`, or `sparkSubmitLocal`. Corpus-scale/two-scale reproduction remains pending.

## Agent workflow exercise

All 18 historical role definitions, five project commands, hooks/settings, `.mcp.json`,
`CLAUDE.md` and `docs/agents.md` are tracked. Intentional untracked Codex `AGENTS.md` and
`skills/` are not prerequisites for the submitted Claude factory. This takeover is
reported separately rather than represented as a historical Claude phase replay.
Historical versions: Claude Code **2.1.312**, model **claude-opus-5-5**, Temurin
**17.0.17+10**, Python **3.12.5** in
[phase-01 versions](agent-runs/phase-01/versions.md). Record actual installed versions
and explicitly report unavailable executables; do not install tools or invoke models
as part of this publication preparation. Factory validation additionally needs `jq`;
ShellCheck is optional and its skip must be reported. External cloud-role MCP servers
and account/model access are not needed for this local validation.

The bounded local validation command is:

```sh
.claude/hooks/validate-agent-files.sh --probes
```

The committed probe table sends inert payloads to hooks; dangerous strings are not
executed. Disposable fixtures test ownership, provenance, stop and held-out refusal
behavior. Compare the exact exit/pass/fail output against archived phase-01 evidence;
changed versions/counts require explanation. This validation is not model-backed code
generation. No model-backed agent phase replay is claimed.

For a later separately approved model-backed replay, start a fresh main session with
`claude` at the candidate SHA after checking `claude --version`. Read `CLAUDE.md`,
`docs/agents.md`, the role file and archived work order; confirm settings, hook and
agent discovery. An exact bounded orchestration prompt is:

> Invoke only code-reviewer. Work order PUB-REPLAY-READONLY: read the service-engineer
> role, Integration 02 repair-01.md and review-report.md, then inspect the current
> ProductionLauncher failed-start workflow-factory guard and its existing regression
> test. No edits, execution, AWS/MCP calls, label reads or paid operations beyond this
> separately approved agent invocation. Compare behavior and report fields with the
> archived finding; report VERIFIED, NOT VERIFIED or BLOCKED-ON with exact file/line
> evidence. Return the role’s complete report contract; do not ratify this candidate.

The orchestrator must invoke the owner rather than edit inline, route verified defects
to service-engineer, and send owner changes back to an independent reviewer. A code
repair needs its targeted failing-before/passing-after test; run the full gate only
when behavior changes justify it. Compare reports, observable contracts and diffs,
not byte-identical prose. Ownership prerequisites/tool fences remain those in each
tracked role and the ownership map. No external role is automatically authorized.
Start a fresh session for the next phase. A full bounded generation/test-hardening
replay still needs its own approved work order and evidence; it is not claimed by
this read-only review exercise.
