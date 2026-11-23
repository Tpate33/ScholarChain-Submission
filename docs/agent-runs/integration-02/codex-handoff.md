# Integration round 02: handoff to Codex (orchestrator, 2026-11-23)

Records state only; approves nothing. Deadline: 2026-11-25 23:00 America/Chicago.

## Repository state

- Branch `main`, HEAD `bdbf5df` (integration-01 closeout; main == origin/main at session start). Nothing from round 02 is committed.
- Binding inputs: `handoff.md` (acceptance criteria A-E), `prompt.md` (human orders verbatim and work orders), `agent-report.md` (SV-INT-03a report verbatim), all in this directory.
- Human decisions this round: an expired API-key cache that cannot refresh -> **503, never stale keys** (B3); deadline mode (freeze optional features and refactoring).
- Do not touch: `AGENTS.md`, `skills/` (UNASSIGNED), hooks, `.claude/**`, the test marker.

## Agent and process state at handoff time

- **SV-INT-03b (service-engineer) STOPPED.** It returned its final report and the harness notified that the task completed. It reported that its last sbt build finished before it stopped. No other agents or sessions were running (ListAgents). An orphaned sbt or JVM process was not checked by an OS process listing; optionally run `ps -ax | grep -E '[s]bt|[j]ava'` once.
- SV-INT-03b report (summary, STATUS: PARTIAL):
  - **A is complete:** `sbt services/test` passed 173, 0 failed, 0 errors, 28 s. This was agent-observed, after all round-02 edits, including the demo.
  - **B is BLOCKED-ON build-engineer:** add `software.amazon.awssdk:secretsmanager:2.57.8` (BOM `aws-sdk-v2.bom=2.57.8` in `project/versions.properties`) to the services Compile classpath, and add `"secretsmanager"` to `DependencyScopes.AwsModules` (`project/DependencyScopes.scala:95`).
  - **`sbt services/scalafmtCheck` was not run.**
- Agent findings:
  - The production mains (`scholarchain.services.QueryJobsMain`, `EvidenceExplainMain`, `GraphMain`; no arguments) no longer use ServiceLoader.
  - **Do not add the `META-INF/services` registration (D2):** it would break the EntryPointsSuite and LocalDemoSuite checks that no provider is on the classpath.
  - Without AWS config the mains exit 1 with a named reason. With a valid config they exit 1 with `ApiKeysNotBuilt` (B missing), before any client is built.
  - The production provider refuses any object from a `scholarchain.services` sub-package (demo isolation).

## Acceptance criteria status

| Criterion | Status | Evidence |
|---|---|---|
| C: NON-PRODUCTION local demo (`scholarchain.services.localdemo.LocalDemoMain`) | **Done per agent report; unreviewed** | `sbt services/test` 158 passed / 0 failed / 0 errors (agent-observed, before SV-INT-03b edits). LocalDemoSuite 12/12 covers the loopback flow: query -> cited evidence -> explain -> graph neighbors, 401, Idempotency-Key replay, banner, profile header, shutdown hook, no ServiceLoader reachability |
| A: production launcher wiring (`forLauncher`, `StorageClients`, G4 read bounds, CR-INT-07-3 scaladoc, workflow fails closed, localdemo refusal) | **Done per agent report; unreviewed; format check not run** | services/test 173/0/0 (agent-observed). New tests: ProductionLauncherSuite 9, WorkflowStoreBoundsSuite 4, LocalDemoIsolationSuite 2 |
| B: Secrets Manager `ApiKeyDigestSource` with 503 on failed refresh | **Not started; BLOCKED-ON build-engineer** (secretsmanager artifact) | See agent state above |
| D: launchers (stage scripts; documented commands) | Not started | owner build-engineer. Skip the `META-INF/services` registration (see above) |
| E: reviews (one for A-C, one for D), human full `sbt check`, demo replay | Not started | - |
| Demo/run documentation (`docs/demo.md` or similar) | Not started | owner documentation-engineer |

## Files changed (uncommitted)

Demo (SV-INT-03a, completed):
- `modules/services/src/main/scala/scholarchain/services/localdemo/LocalDemo.scala`: args (`--port`, default 8080, 0 = ephemeral), composition, banner, `launch`, `LocalDemoMain`, package-private digest-fixture key source.
- `modules/services/src/main/scala/scholarchain/services/localdemo/LocalDemoWorld.scala`: strict seed decoder; in-memory world built on `scholarchain.storage.fake.*`.
- `modules/services/src/main/resources/scholarchain/local-demo/world.v1.json`: **synthetic fixture seed** (5 researchers, 3 coauthor edges, Phase 9 fixture world; NOT real DBLP data).
- `modules/services/src/main/resources/scholarchain/local-demo/api-keys.v1.json`: SHA-256 digest of the demo key only.
- `modules/services/src/test/scala/scholarchain/services/localdemo/LocalDemoSuite.scala`: 12 tests.
- `modules/services/src/main/scala/scholarchain/services/HttpServerAdapter.scala` (M): optional `extraHeaders` on `start` (default empty; production unchanged). SV-INT-03b may also have touched it.

Production wiring (SV-INT-03b, criterion A, unreviewed):
- `ProductionLauncher.scala` (new): launcher wiring via `forLauncher` and `StorageClients`.
- `WorkflowStoreBounds.scala` (new): G4 read-policy and timeout bounds around workflow store reads.
- `ProductionComposition.scala`, `ServiceMain.scala` (M): wiring changes.
- Tests: `LauncherFixture.scala`, `ProductionLauncherSuite.scala`, `WorkflowStoreBoundsSuite.scala` (new), and `localdemo/LocalDemoIsolationSuite.scala` (new, appeared after this file was first written, probably the localdemo-refusal test). New files appearing after the stop request mean SV-INT-03b was still writing.
- All under `modules/services/src/{main,test}/scala/scholarchain/services/`. The agent reports that they compile and pass (173/0/0). The orchestrator did not observe that run itself.

## Tests run this round

- Only agent-run `sbt services/test` (SV-INT-03a): 158/0/0. The log is the sbt console output in the agent transcript. Surefire XML: `modules/services/target/test-reports/`.
- No full `sbt check` was run this round. The test marker is stale, so the Stop hook refuses completion for source changes; this is expected until the human's full check.
- Last full check (integration-01): 2817 passed, 4 skipped, log `/Users/taufeeqpatel/ScholarChain-archive/integration-01/manual-check-20261123-000133.txt`.

## Review findings and blockers

- No round-02 review has run. Open integration-01 residuals are listed in `../integration-01/outstanding.md` (not waived).
- Known gaps from SV-INT-03a:
  - the demo uses the synthetic fixture world, because loading the Phase 6 Parquet output needs a classpath-policy change (build-engineer);
  - there are no semantic documents, so vector questions retrieve nothing;
  - the demo classes ship in the services main jar (inert);
  - the test-source format check is unconfirmed.
- Possible B blocker: the AWS SDK `secretsmanager` artifact may not be on the services classpath (build-engineer adds it at the BOM version).

## Next steps (priority order for the deadline)

1. Run `sbt services/scalafmtCheck` and `sbt services/Test/scalafmtCheck` (neither has been run on the A files).
2. Demo launcher (minimum): add a forked `runMain` or sbt-native-packager stage for `scholarchain.services.localdemo.LocalDemoMain` (build.sbt). The demo works from the classpath alone (`services / Runtime / fullClasspath`); resources load from the jar.
3. Write minimal run/demo docs from the command sequence below, labelling the fixtures as NON-PRODUCTION and synthetic.
4. Review, then the human's full `sbt check` and one demo replay, then the human commits.
5. Only if time allows: add the secretsmanager artifact (build), then implement B (503 on failed refresh, never stale keys).

## Demo commands (from the SV-INT-03a report; not yet replayed by a human)

Start the demo. Before step 2 the main is not forked, so Ctrl-C may not exit cleanly:
```sh
cd /Users/taufeeqpatel/Desktop/ScholarChain
sbt "services/runMain scholarchain.services.localdemo.LocalDemoMain --port 8080"
```
In a second Terminal:
```sh
KEY=scholarchain-local-demo-key-NOT-A-SECRET   # documented non-secret demo key
BASE=http://127.0.0.1:8080
curl -s -i "$BASE/healthz"     # expect 200 and X-ScholarChain-Profile: local-demo; NON-PRODUCTION
curl -s -X POST "$BASE/v1/query" -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-0001" -d '{"question":"Who are the coauthors of Ada Lovelace?","mode":"sync"}'
# set QID=<queryId>, EV=<links.evidence[0]> from the response
curl -s "$BASE$EV" -H "Authorization: Bearer $KEY"
curl -s "$BASE/v1/explain/$QID" -H "Authorization: Bearer $KEY"
curl -s "$BASE/v1/graph/nodes/<ADA_ID_FROM_BANNER>/neighbors" -H "Authorization: Bearer $KEY"
curl -s -i -X POST "$BASE/v1/query" -H "Content-Type: application/json" -d '{"question":"Who are the coauthors of Ada Lovelace?","mode":"sync"}'   # expect 401
```
Other banner questions: the shortest path Ada Lovelace -> Dan Brown; bridging researchers; most distinct coauthors; "John Smith" (abstains as ambiguous).

## Fixtures and stubs versus production

- Demo only: in-memory `storage.fake` stores, the synthetic seed, `NonProductionTemplateSynthesizer`, `NonProductionHashedTokenEmbedder`, the digest fixture with its public key, and single-replica in-memory jobs and idempotency.
- Still outstanding (unmet assignment requirements):
  - production answer model and embedder (paid calls need human approval);
  - criteria A and B unreviewed or incomplete;
  - launchers and ServiceLoader registration;
  - demo over real DBLP-derived data;
  - runtime Terraform, packaging and smoke scripts;
  - deployment and live AWS verification;
  - experiments;
  - Phase 17 documentation and the final report.
