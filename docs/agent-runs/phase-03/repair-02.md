# Phase 3 repair-02: HOCON configuration files

Trigger: human decision on 2026-10-29. The README Phase 3 work order requires "a configuration loader using environment variables plus versioned HOCON or YAML files". Configuration files as strict JSON (domain-engineer assumption A1) were judged insufficient. The human chose the Typesafe/Lightbend Config library.

## Routing

| Change | Owner |
|---|---|
| Add a pinned `com.typesafe:config` to the version ledger and to `core` (Compile scope) | build-engineer |
| Switch `ConfigLoader` to parse HOCON files, preserving precedence and validation | domain-engineer |
| Focused review of this change | code-reviewer |

Optional findings from review 2 (N1-N4, U1-U5) stay deferred by human decision.

## build-engineer report (verbatim)

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 3 repair-02. Add pinned com.typesafe:config to the ledger and to `core` only, in Compile scope. Assess the effect on hadoop and sparkAnalytics fat jars. Run sbt check.
FILES CREATED: none
FILES MODIFIED: project/versions.properties; build.sbt
Ledger line added in the "Libraries" section, after `logback=1.5.18`:
  typesafe-config=1.4.3
build.sbt diff:
  @@ -42,6 +42,7 @@
   val logbackClassic = javaDep("ch.qos.logback", "logback-classic", "logback")
  +val typesafeConfig = javaDep("com.typesafe", "config", "typesafe-config")
   val hadoopClientApi = javaDep("org.apache.hadoop", "hadoop-client-api", "hadoop")
  @@ -134,7 +135,7 @@ lazy val core = project
       name := "scholarchain-core",
  -    libraryDependencies += upickle.value
  +    libraryDependencies ++= Seq(upickle.value, typesafeConfig.value)
     )
Nothing else changed: no other projects, plugins, aliases, scopes, or module graph, and nothing under modules/**.
Resolved artifact: com.typesafe:config:1.4.3 (plain Java artifact, no Scala suffix), Compile scope. Resolved: Test/compile, check, and assembly all succeeded; POM in the Coursier cache (repo1.maven.org/maven2/com/typesafe/config/1.4.3/config-1.4.3.pom).
Transitive dependencies: none at compile or runtime. The POM lists only four test-scope dependencies (config-test-lib_2.12, org.jacoco.agent, lift-json_2.12, junit-interface), which are not passed on. License in the POM: Apache-2.0.
COMMANDS RUN:
  1. sbt Test/compile -> [success] Total time: 3 s
  2. sbt check -> success
  3. sbt packageJobs -> success; built scholarchain-hadoop-jobs-0.1.0.jar and scholarchain-spark-jobs-0.1.0.jar; each "2 file(s) merged using strategy 'Rename'" and "9 file(s) merged using strategy 'Discard'"; no merge conflicts
  4. git diff -- build.sbt project/versions.properties
TEST RESULTS (sbt check): verifyBuildSources "Build sources OK"; verifyVersionLedger "Version ledger OK: 14 pinned entries ...; no literal versions in 2 build file(s)"; scalafmt checks and Test/compile passed; core "Passed: Total 167, Failed 0, Errors 0, Passed 167"; every other project Total 0; markTestRun "wrote .../.claude/.last-test-run (git_head=e12777342c9b08775eb294a8452a0381e528ac8b, worktree=dirty, suites=10, passed=167, skipped=0)".
VALIDATION RESULTS: ledger verifier accepts the new pinned entry; no literal versions; both fat jars assemble with the new dependency.
ASSUMPTIONS: 1.4.3 accepted as the latest known 1.4.x release (Maven Central not queried; WebFetch not in toolset; version values belong to dependency-updater). Ledger key `typesafe-config` follows the existing style.
RISKS:
  - Fat-jar conflict with cluster-supplied Typesafe Config: config-1.4.3 classes are bundled unshaded into both job jars. EMR images or extra libraries may put another copy on the classpath, and cluster jars load first by default (Spark userClassPathFirst=false; mapreduce.job.user.classpath.first=false). Typesafe Config 1.x is generally backward compatible, so the risk is moderate to low. If a conflict appears in a Phase 4 or 6 EMR sample run, the fix is an sbt-assembly ShadeRule for com.typesafe.config.** in jobAssembly (not added, since assembly did not fail).
  - Typesafe Config is a configuration parser, not a second JSON library. Reviewers should make sure core does not use Config's JSON rendering as a serialization path.
  - Pre-existing: the Spark Scala 2.13 vs EMR 2.12 runtime target is still pending decision F2.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the two-file diff; domain-engineer moves ConfigLoader to Typesafe Config / HOCON (the test "HOCON-only syntax and non-object files are rejected (strict JSON subset)" must change); sbt check again; watch for classpath conflicts in the first EMR sample runs (Phases 4 and 6).
```

## domain-engineer report (verbatim)

The run was interrupted by a usage limit and resumed with SendMessage. The agent confirmed that no partial write had been applied before it continued.

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 3 repair-02. Config files are now HOCON parsed with Typesafe Config (com.typesafe:config:1.4.3); includes and substitutions rejected; no ConfigFactory.load, system-property or environment fallback; no JSON rendering; precedence and validation unchanged; ConfigLoaderSuite updated.
FILES CREATED: none
FILES MODIFIED: modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala; modules/core/src/test/scala/scholarchain/core/config/ConfigLoaderSuite.scala (ScholarChainConfig.scala unchanged)
COMMANDS RUN:
  1. git status --porcelain (expected paths only)
  2. git diff -- build.sbt project/versions.properties (typesafeConfig in core; ledger typesafe-config=1.4.3)
  3. sbt core/testOnly scholarchain.core.config.ConfigLoaderSuite -> DENIED by guard-dangerous.sh (no allowlist row); nothing ran
  4. sbt core/test -> Failed: Total 174, Failed 2, Passed 172: (a) test expectation for a list merged over a string (key "storage.neptuneEndpoint" not "...[0]"), test fixed and case added; (b) randomSeed = 9007199254740993.0 accepted, real defect, fixed
  5. sbt core/test -> Failed: Total 174, Failed 1, Passed 173 (defect (b) still present)
  6. sbt core/test -> Passed: Total 174, Failed 0, Errors 0, Passed 174
  7. sbt check -> [success]; Build sources OK; Version ledger OK, 14 pinned entries; scalafmt check passed; Test/compile passed; core "Passed: Total 174, Failed 0, Errors 0, Passed 174"; others Total 0; markTestRun wrote .claude/.last-test-run (git_head=e12777342c9b08775eb294a8452a0381e528ac8b, worktree=dirty, suites=10, passed=174, skipped=0)
TEST RESULTS: sbt check passed: 174 passed, 0 failed, 0 errors, 0 skipped, 10 suites. ConfigLoaderSuite 21 tests (was 15). Strict-JSON test replaced. New tests: HOCON features accepted (comments, unquoted keys, dotted paths, multi-line objects; HOCON, dotted and strict JSON forms give equal configs and digests); syntax is HOCON regardless of file name; 64-bit integer precision, inexact decimals rejected; duplicate keys merge inside one file without breaking file-over-file precedence; include directives of every form rejected (include "x", file(), required(file()), classpath(), url(), missing file, top level and nested, real file on disk); substitutions rejected with key path (${HOME}, ${?HOME}, ${?SCHOLARCHAIN_CFG_ENVIRONMENT}, ${?undefined}, internal reference, concatenation, +=, in list, list element, in overlay); environment and system properties never enter except through SCHOLARCHAIN_CFG_ (config.file, config.resource, config.override_with_env_vars, runtime.retrievalTopK, environment, CONFIG_FORCE_* have no effect); non-object roots and syntax errors rejected as MalformedFile naming the file; empty document gives MissingSchemaVersion. Existing tests adapted to .conf with the same assertions.
VALIDATION RESULTS: every config rule in the work order has a passing test; wire-contract and schema-version suites unchanged and passing.
(a) Precedence (unchanged): 1. SCHOLARCHAIN_CFG_<KEY> (camelCase -> UPPER_SNAKE segments joined by "__"); 2. files in SCHOLARCHAIN_CONFIG_FILES, a later file overriding an earlier one leaf key by leaf key; 3. no defaults, a missing key is MissingKey(path, envVar). Unknown vars/keys are UnknownKey; configSchemaVersion cannot be set from the environment; env syntax unchanged.
(b) Format: one HOCON document per .conf file with an object root; syntax always CONF (content only, never the file name); top-level string configSchemaVersion required, only "scholarchain.config.v1" supported; key table unchanged; strict JSON still accepted. aws.accountId must be quoted; numbers unquoted; bridge.randomSeed integer literal (64-bit) or digit string, decimal literals rejected.
(c) Rejection mechanism: ConfigFactory.parseString(content, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false).setOriginDescription(path).setIncluder(RejectingIncluder)); never load/defaultReference/defaultOverrides/systemProperties/systemEnvironment/resolve/render. RejectingIncluder implements ConfigIncluder, ConfigIncluderFile, ConfigIncluderURL, ConfigIncluderClasspath; withFallback returns itself; every include throws IncludeAttempt(target) at parse time, mapped to ConfigError.IncludeRejected(file, target). Parsed config never resolved; if !isResolved, tree walk catching ConfigException.NotResolved returns SubstitutionRejected(file, keyPath). Other ConfigException -> MalformedFile(file, message).
(d) Digest unchanged (ScholarChainConfig and digest untouched; HOCON, dotted and strict-JSON forms give byte-identical digests).
ASSUMPTIONS: parseString on readFile content (keeps pure load(env, readFile) API; setAllowMissing has no effect on a string); numbers read via v.atKey("n").getString("n") literal text then BigDecimal (Typesafe turns whole doubles into longs); randomSeed decimals now rejected and integers above 2^53 now accepted exactly; quoted dotted key is one literal segment (unknown key); new ConfigError cases IncludeRejected and SubstitutionRejected; MalformedFile message says "not valid HOCON".
RISKS: include rejection depends on Typesafe 1.4.3 internals (frozen by tests); literal-text number path relies on getString coercion (tested); system-property test mutates JVM-global properties and restores them; config.trace read for logging only; unshaded config jar may conflict on EMR.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: focused code-reviewer review of ConfigLoader.scala and ConfigLoaderSuite.scala (RejectingIncluder, unresolved-tree walk, number literal-text path), then human acceptance of repair-02.
```
