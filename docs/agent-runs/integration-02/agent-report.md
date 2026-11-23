# Integration round 02: agent reports (verbatim)

## SV-INT-03a (service-engineer, 2026-11-23)

The NON-PRODUCTION local demo profile (SV-INT-03a, handoff criterion C) is in place and items 1-6 are met. `sbt services/test` passed: Total 158, Failed 0, Errors 0, Passed 158. That is 146 existing tests, all unchanged, plus 12 new ones.

One deviation you should know about before reading on: the demo is seeded from the hand-built Phase 9 rag fixture world, not from the Phase 6 run-1 Parquet corpus. Details are under ASSUMPTIONS.

STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: SV-INT-03a, the NON-PRODUCTION local demo profile (handoff.md criterion C). Production wiring (A) and the Secrets Manager source (B) were not started. The production mains and `ProductionServiceProvider` are unchanged.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/main/scala/scholarchain/services/localdemo/LocalDemo.scala
  - `LocalDemo`: argument parsing, composition, banner and `launch`.
  - `LocalDemoMain`: the one entry point.
  - `LocalDemoKeys`: the digest-fixture key source, package-private.
  - `LocalDemoArgs` and `StartedDemo`.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/main/scala/scholarchain/services/localdemo/LocalDemoWorld.scala
  - The strict seed decoder (`DemoSeed`).
  - The in-memory world, written through storage codecs and bound graph writes into `scholarchain.storage.fake.*`.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/main/resources/scholarchain/local-demo/world.v1.json: the committed seed (schema `scholarchain.services.local-demo-world.v1`, `nonProduction: true`).
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/main/resources/scholarchain/local-demo/api-keys.v1.json: the digest fixture. It holds SHA-256 digests only: client `local-demo-client`, digest `4b7ca96fd268ae0e0f04108a866de5259641cc2ca3554adf1eea0be4d82608e4`, scopes query, read and ops.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/test/scala/scholarchain/services/localdemo/LocalDemoSuite.scala: 12 feature tests.

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/main/scala/scholarchain/services/HttpServerAdapter.scala
  - `start` gained an optional `extraHeaders: Vector[(String, String)] = Vector.empty`, added to every response (router and admission paths).
  - It refuses null. The default leaves production behaviour unchanged, and no production caller passes it.
  - Justification: this is how the demo sets its profile header on every response. A test proves the production default adds no header.

COMMANDS RUN:
1. `git status --short`: refused by the guard (not an allowlist shape).
2. `git ls-files -- ...` (three calls to list sources and fixtures): succeeded.
3. `git diff --stat HEAD -- build.sbt`: no output, so build.sbt is unchanged.
4. `git grep ...`: refused by the guard's character gate.
5. `sbt services/compile`: the first run failed (two missing imports, `AnalysisRoles` and `SurfaceForms`, which come from `scholarchain.rag.agents`). After the fix: `[success]`.
6. `sbt services/testOnly scholarchain.services.localdemo.LocalDemoSuite`: refused by the guard.
7. `sbt services/test`, run 1: Total 158, Failed 4, Passed 154.
   - The key fixture held a placeholder digest, which made all four tests fail.
   - I read the real digest from the test's own failure output in `modules/services/target/test-reports/TEST-scholarchain.services.localdemo.LocalDemoSuite.xml`. It is computed by `MessageDigest` inside the test.
8. `sbt services/test`, run 2: `Passed: Total 158, Failed 0, Errors 0, Passed 158`, `[success]`.
9. `sbt services/scalafmtCheck services/Test/scalafmtCheck` and `sbt services/scalafmtCheckAll`: both refused by the guard.
10. `sbt services/scalafmtCheck`: `[success]` (checked 3 main sources).
11. `git status --porcelain`: shows the files listed above, plus the untracked AGENTS.md, skills/ and docs/agent-runs/integration-02/, which I did not touch.

TEST RESULTS:
- `sbt services/test`: Total 158, Failed 0, Errors 0, Passed 158.
- `LocalDemoSuite`, 12 of 12 passed:
  1. The key fixture holds only the SHA-256 of the documented key, never the raw key.
  2. Malformed key files are refused (8 variants).
  3. The seed is decoded strictly and builds 5 researchers and 3 coauthor edges. Bad seeds are refused (5 variants), and a seed row that breaks its core contract fails closed.
  4. Arguments: only `--port`, default 8080, 0 for an ephemeral port. Any host option or other argument is refused.
  5. The demo binds IPv4 loopback only. The check through the machine's non-loopback address is skipped when `getLocalHost` itself resolves to loopback.
  6. One loopback HTTP flow:
     - `POST /v1/query` returns 200, matches the frozen `query-sync` example, is VERIFIED, and every claim cites evidence that is in the bundle.
     - `GET /v1/evidence/{cited id}` returns 200 and matches the frozen example.
     - `GET /v1/explain/{queryId}` returns 200 and matches the frozen example.
     - `GET /v1/graph/nodes/{ada}/neighbors` returns Bob only.
     - A missing key and a wrong key each get 401 with a trace ID.
     - A replay under the same Idempotency-Key returns the same queryId and the same answer; a fresh key gets a new queryId.
  7. Every example question in the seed gets its documented status (four answered, one abstained).
  8. Every demo response carries `X-ScholarChain-Profile: local-demo; NON-PRODUCTION`, including /healthz, /readyz, 401 and 404. The same router behind the production default carries no such header.
  9. `launch` prints the NON-PRODUCTION banner and registers a shutdown hook; running the hook stops the server (connection refused afterwards).
  10. Bad arguments exit with code 2 and register no hook.
  11. The production path cannot reach the demo:
      - no demo class implements `ServiceProvider`;
      - no `META-INF/services` entry names it, and `ServiceLoader` finds no provider at all;
      - `ServiceMain.launch` still exits with code 1;
      - no production source in `scholarchain/services/*.scala` names `localdemo` or `LocalDemo`.
  12. All demo stores are `scholarchain.storage.fake.*`, the package the production provider already refuses.

VALIDATION RESULTS:
- `sbt services/scalafmtCheck` passed for main sources.
- I could not run a test-source format check under my allowlist. Every write went through the post-write formatter hook, but that check is unconfirmed until the human's `sbt check`.
- Full `sbt check` was not run, as the work order required.

Facts for build-engineer:
- **Main class:** `scholarchain.services.localdemo.LocalDemoMain`.
- **Arguments:** `[--port <0..65535>]`, default `8080`; `0` binds an ephemeral port. There is no host option: it always binds `127.0.0.1`.
- **Exit codes:** 0 when started (the JDK HttpServer dispatcher keeps the process alive), 2 for bad arguments, 1 when the start is refused.
- **Shutdown:** SIGTERM or Ctrl-C runs the shutdown hook. There is no drain window, and each shutdown stage waits at most 1 s.
- **Runtime classpath:** `services / Runtime / fullClasspath`, that is the services, rag, storage and core projects, plus upickle, typesafe-config, slf4j-api and logback (Runtime). The AWS SDK jars are present through storage, but no client is constructed.
- **Resources:** both seed files come from the services main resources (`scholarchain/local-demo/*.json`). No repository path is read at runtime, so the demo works from any working directory.
- **Two packaging notes (yours to decide):**
  - `services` currently has four main classes (`QueryJobsMain`, `EvidenceExplainMain`, `GraphMain`, `LocalDemoMain`). A native-packager stage needs an explicit main class or one launcher script per main.
  - `services / Compile / run` is not forked today. If you offer a `runMain` route, you may want to fork it so Ctrl-C exits cleanly.

Ready-to-run curl sequence (replace PORT if you change it):
```sh
KEY=scholarchain-local-demo-key-NOT-A-SECRET   # documented demo key, not a secret
BASE=http://127.0.0.1:8080
curl -s -i "$BASE/healthz"                     # 200, header X-ScholarChain-Profile: local-demo; NON-PRODUCTION
curl -s -X POST "$BASE/v1/query" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-0001" \
  -d '{"question":"Who are the coauthors of Ada Lovelace?","mode":"sync"}'
# From the JSON: QID=<queryId>; EV=<links.evidence[0]> (a path such as /v1/evidence/...),
# or a claims[].evidenceIds[] value appended to /v1/evidence/
curl -s "$BASE$EV" -H "Authorization: Bearer $KEY"                  # evidence item, 200
curl -s "$BASE/v1/explain/$QID" -H "Authorization: Bearer $KEY"     # explanation, 200
ADA=<ada's ID as printed in the startup banner>
curl -s "$BASE/v1/graph/nodes/$ADA/neighbors" -H "Authorization: Bearer $KEY"   # Bob only
curl -s -i -X POST "$BASE/v1/query" -H "Content-Type: application/json" \
  -d '{"question":"Who are the coauthors of Ada Lovelace?","mode":"sync"}'      # 401
curl -s -X POST "$BASE/v1/query" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-0001" \
  -d '{"question":"Who are the coauthors of Ada Lovelace?","mode":"sync"}'      # replay: same queryId
```
The banner also lists these example questions: the shortest path between Ada Lovelace and Dan Brown; which researchers bridge communities; who has the most distinct coauthors; and "Who are the coauthors of John Smith?", which abstains as ambiguous.

Report items required by the role file:
- **Routes:** unchanged. The demo serves `ServiceKind.All`:
  - `POST /v1/query`, `GET /v1/jobs/{jobId}`, `GET /v1/jobs/{jobId}/result`;
  - `GET /v1/evidence/{evidenceId}`, `GET /v1/explain/{queryId}`;
  - `GET /v1/graph/nodes/{nodeId}`, `GET /v1/graph/nodes/{nodeId}/neighbors`;
  - `GET /healthz`, `/readyz`, `/metrics`.
- **Status-code mapping:** the existing typed model, unchanged.
- **Timeouts (demo values):**
  - sync and async 10000 ms;
  - read request deadline 8000 ms, per read attempt 5000 ms, at most 3 attempts;
  - queue wait 5000 ms; breaker opens after 3 failures, for 60000 ms;
  - idempotency TTL 3600000 ms; grace 1 s.
- **Dependency graph:** services depends on core, rag and storage (ports and in-memory fakes only). No new module edge.
- **Authentication:** a Bearer API key, hashed with SHA-256 and compared in constant time against the digests in the fixture.

ASSUMPTIONS:
- **Seed source.** "Phase 9 corpus and fixtures" is satisfied by the hand-built Phase 9 rag fixture world (rag `AgentWorld`/`WorkflowWorld`, already mirrored by `ServiceWorld`), committed as a classpath JSON seed. The Phase 6 run-1 local corpus cannot be loaded from services main:
  - it is Parquet, and parquet-hadoop is Optional on storage, deliberately off the services Runtime classpath and enforced by `verifyDependencyScopes`;
  - its loader helpers are storage test classes.

  Loading the real corpus would need build-engineer to change the classpath policy.
- **Seed differences from `ServiceWorld`:**
  - Two extra entity links, "Dan Brown" and "Eve Adams", taken from the rag world's names, so the path questions resolve.
  - Snapshot token `local-demo-nonproduction` and work IDs `work:dblp:conf/demo/W<n>`.
  - Lineage and provenance are fixed synthetic values in code, as in the test fixture.
- **No semantic documents.** The vector indexes exist but are empty, so semantic questions retrieve no context.
- **Clocks.** The demo uses the system clock. The seeded entity links expire at epoch 1900000000 (2030-03-17).
- **Demo key.** `scholarchain-local-demo-key-NOT-A-SECRET` is a documented non-secret value, stated in the `LocalDemo` and `LocalDemoMain` scaladocs and printed in the banner.

RISKS:
- **Demo-only** (never on the production path):
  - storage's in-memory fakes and the synthetic seed world;
  - `NonProductionTemplateSynthesizer` and `NonProductionHashedTokenEmbedder`;
  - the local digest fixture and its non-secret key;
  - in-memory jobs, idempotency and ownership (one replica);
  - `StoreAccess.serialized`;
  - the profile header;
  - hardcoded demo bounds, port 8080 and loopback-only binding.
- **Still needed for production:**
  - SV-INT-03a A: the launcher wiring through `ProductionSettings.forLauncher`, `StorageClients` and the G4 read policy;
  - SV-INT-03a B: the Secrets Manager `ApiKeyDigestSource`;
  - the production answer model and embedder (rag-engineer, plus the human's decision on paid calls);
  - `META-INF/services` registration and staged launchers (build-engineer);
  - runtime Terraform (infra-engineer), deployment, and live AWS verification.
- **Packaging residual.** The demo classes and the two JSON fixtures sit in the services main jar, so they would ship in the Universal and Docker images. They are inert there: nothing loads them, `ServiceLoader` cannot select them, and the production sources never reference them. build-engineer can exclude them from the images if wanted.
- **Not enforced by provider code.** `ProductionServiceProvider` refuses `storage.fake` stores, but it has no explicit refusal of `scholarchain.services.localdemo` objects. The key source is package-private, so production code cannot reference it. I left the provider unchanged as ordered; SV-INT-03b could add an explicit refusal.
- **Loopback test.** The non-loopback reachability check is skipped on a machine whose `getLocalHost` is itself loopback.

MANUAL ACTION REQUIRED: The human runs the full `sbt check` in the Terminal after review. That also confirms test-source formatting, which my allowlist could not check.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of SV-INT-03a (together with A and B, per handoff E). Then build-engineer, for launcher staging of `scholarchain.services.localdemo.LocalDemoMain` (criterion D). Then the human's manual `sbt check` and one run of the local demo command sequence.

Orchestrator note: the report's "SV-INT-03a A" and "SV-INT-03a B" under RISKS refer to the SV-INT-03b order (criteria A and B).
