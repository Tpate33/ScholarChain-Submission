# Phase 3 review report (code-reviewer, review 1)

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 3 gate review (read-only) of the uncommitted Phase 3 changes against HEAD e127773: everything new under datasets/** and modules/core/**.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: Not observed by reviewer; agent-reported core 139/139 and sbt check success.
VALIDATION RESULTS: Not observed; datasets/scripts/validate-manifest.sh has never been executed.

VERDICT: REQUEST_CHANGES (1 verified major finding)

Gate items:
1. Every record has provenance: PASS (19 data record contracts requiresProvenance=true; RunManifest is the provenance root; Contract.scala:130-136; ValidationSuite.scala:42-64).
2. Wire-format contract tests meaningful: PASS with gap m5 (10 literal goldens, WireContractSuite.scala:25-68; coauthor-edge hash checked against hand-written netstring, StableIdSuite.scala:46-58; other hashes captured from implementation output).
3. Dataset manifest validation: NOT PASSED (never executed). By reading, expected to parse and pass; validator checks every spec-required field, but some nested values only for presence (m1); false-failure path (m6).
4. Sample scripts do not silently fetch large files: PASS on safety (no-flag exit 2; DBLP --full needs SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD; others refuse --full exit 3; https-only, byte/time caps, atomic writes, sha256-sidecar idempotency); see M1, m3, m7.
5. Pair-ordering and time-window tests: PASS (PairAndWindowSuite.scala).
6. No invented dataset values: no internal inconsistency found; unverifiable values marked UNVERIFIED/pending; external values listed for human spot-check.

Other checks: stable ID formulas match work-order principles (StableIds.scala:349-363, 563-589); all six validation rejections implemented and tested; no hardcoded buckets/endpoints/account IDs/thresholds/model IDs/windows in core main; config precedence matches docs (ConfigLoader.scala:109-117 vs 155-186); core imports nothing internal; no ownership violation; no secrets; ORCID samples minimal.

FINDINGS:
dataset-engineer
- M1 [major] lib-datasets.sh:89 (also :12, :53, :98; fetch-dblp.sh:9; dataset-manifest.yaml:37, :155-156; ATTRIBUTION.md:7): default output dir datasets/work/<dataset>/ instead of the ratified, already-ignored /datasets/downloads/ (.gitignore:34-35; Phase 1 decision A2; BuildGuard.scala:27). fetch-samples.sh --sample writes non-minimised ORCID XML, API JSON, FETCH-LOG.tsv to git-visible paths; fetch-dblp.sh --full writes 1.03 GB there. Personal data/mailto committable via git add; audit hashes 1 GB on every Bash call. Fix: default to datasets/downloads/.
- m1 [minor] dsutil.py:376-381: REQUIRED_NESTED checks only key presence (license.name: null, format.compression: "", documentation: [] pass).
- m2 [minor] dsutil.py:350-355: ORCID privacy check does not ban person-level external-identifier, biography, distinction, invited-position, membership, qualification, service, despite manifest promise (dataset-manifest.yaml:484).
- m3 [minor] lib-datasets.sh:23-24, 164; fetch-samples.sh:25: byte budget per process; one --sample run can fetch up to 4 x 20 MB.
- m4 [minor] fetch-crossref.sh:40-41; lib-datasets.sh:170: mailto logged in FETCH-LOG.tsv; not URL-encoded.
- m6 [minor] dsutil.py:293-300: os.walk counts .DS_Store under datasets/samples -> false FAIL on macOS.
- m7 [minor] fetch-dblp.sh:86-89: corrupt complete .part resumed with curl -C - never self-heals, no instruction.
- n2 [nit] validate-manifest.sh:38: defaults to --verify with no flag, contrary to lib-datasets.sh:6 contract.
- n3 [nit] dsutil.py:228, 493: allow-list prefix match admits s3://openalex-anything.
domain-engineer
- m5 [minor] WireContractSuite.scala:84-93: no frozen golden for CoauthorPairCandidate, PublicationTeam, AuthorshipRecord, AnswerResponse, Venue, Institution, Topic, GraphNode, standalone EvidenceItem, standalone AuthorMention; renaming endpointA passes all tests.
- m8 [minor] Fixtures.scala:44, WireContractSuite.scala:14 use sourceSchemaVersion "dblp.schema.v1" while dataset-manifest.yaml:107 declares "dblp-raw/1.0.0".
- n1 [nit] TmpDumpSuite.scala:1-6: file name != class GraphModelCoverageSuite; stale "pending a human rename" comment.
- n4 [nit] Fixtures.scala:6-9 claims no real fact, but line 110 uses real CACM ISSN 0001-0782.
- n5 [nit] ValidationError.scala:73-76: message says four-digit year but 0-999 accepted.

External values for human spot-check: DBLP (drops.dagstuhl.de URLs, DOI 10.4230/dblp.xml.2026-10-03, MD5 cbd329100ea1bbb2873fa2f6d414ea00, 1.03 GB, CC0/ODC-BY quote, DTD date, pids, Spanner/Bigtable details, orcid attributes); OpenAlex (release 2026-10-14, manifest totals, CC0, W/A/I/S/T IDs, 6 ORCID assertions); Crossref (blog, 208 GB, DOI 10.13003/nggf-vt1j, torrent infohash b5ee0e10..., 223.05GB/35,909 files, requester-pays bucket, 2026-03-17, member 320); ORCID (figshare 30375589, DOI 10.23640/07243.30375589.v1, file 58834837, 46328319190 B, dates, put-codes, client IDs, EID, DOI claim by 0000-0002-4303-3908).

UNVERIFIED CONCERNS: test counts and validator not observed; run ID uniqueness depends on callers incrementing attempt; manifest subset_rule hardcodes 2015-2024 / first 10,000; curl --max-filesize on chunked responses; OpenAlex works manifest.json may exceed 5 MB cap (fails loudly); nested null provenance error kind unconfirmed; WorkRecord does not check workId namespace vs source.

MANUAL ACTION REQUIRED: human rename TmpDumpSuite.scala; set exec bits, commit, run validate-manifest.sh --verify and fetch-samples.sh --dry-run/--verify; spot-check external values.
BLOCKED-ON OWNER: none
NEXT GATE: route M1, m1-m4, m6, m7, n2, n3 to dataset-engineer; m5, m8, n1 (comment), n4, n5 to domain-engineer; re-review with validator output observed.
```

# Phase 3 review report (code-reviewer, review 2, after repair-01)

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 3 re-review after repair-01 (read-only), datasets/** and modules/core/** against HEAD e127773.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not observed; domain-engineer reports core 167/167.
VALIDATION RESULTS: not observed; validate-manifest.sh and fetch scripts never executed.

VERDICT: APPROVE. No verified critical or major finding remains. All 14 review-1 findings fixed; n1 PARTIAL only because the file rename is a human action. 4 new nits.

FINDING STATUS:
dataset-engineer: M1 FIXED (lib-datasets.sh:96-108; no "datasets/work" remains); m1 FIXED (dsutil.py:229-240, :465-467; residual N3); m2 FIXED (dsutil.py:273-303, :432-438; manifest :484, :511; samples contain no banned tags); m3 FIXED (fetch-samples.sh:28-43; lib-datasets.sh:189-243; residual N2); m4 FIXED (lib-datasets.sh:140-165, :204-252; fetch-crossref.sh:43); m6 FIXED (dsutil.py:262-267, :368-391); m7 FIXED (fetch-dblp.sh:89-104; see U2); n2 FIXED (validate-manifest.sh:34-59); n3 FIXED (dsutil.py:250-257, :579).
domain-engineer: m5 FIXED (WireContractSuite.scala:70-107, :134-169; StableIdSuite.scala:60-85, netstring lengths hand-checked); m8 FIXED (Fixtures.scala:47, :58; WireContractSuite.scala:14, :71, :73 match manifest :107, :236); n1 PARTIAL (comment removed; human rename pending); n4 FIXED (Fixtures.scala:124, ISSN 0000-0019 check digit verified); n5 FIXED (ValidationError.scala:73-81; PairAndWindowSuite.scala:105-126).

REGRESSION REVIEW: sourceSchemaVersion validation (Provenance.scala:61-91) accepts all four manifest values (:107, :236, :367, :481) and all SchemaVersions constants; WorkRecord namespace check (Bibliographic.scala:105-135) correct (OpenAlex/DBLP works cannot use DOI-keyed workId: documented choice); new goldens and hash pins correct, no existing hash changed; no bash errors found by reading.

NEW FINDINGS (nits, none blocks):
- N1 [nit] dataset-engineer, lib-datasets.sh:100-101, :109: trailing-slash output dir refused with misleading "empty segments" message; ${rel%/} never takes effect. Usability only.
- N2 [nit] dataset-engineer, lib-datasets.sh:227-235: curl --retry 2 can overrun budget ~3x (bounded, ~60 MB at default). Already in owner RISKS.
- N3 [nit] dataset-engineer, dsutil.py:229-240: non-empty nested mapping with null values (e.g. integrity.upstream_checksum.algorithm: null) passes m1 check. No current case.
- N4 [nit] domain-engineer, WireContractSuite.scala:145-169: deleting a frozen() call still passes the coverage test. Already in owner RISKS.

GATE ITEMS: 1 PASS; 2 PASS; 3 NOT PASSED (not executed; expected to pass by reading, except SHA256SUMS byte match and 300 KiB total cannot be checked by reading); 4 PASS; 5 PASS; 6 PASS by reading (external values still need human spot-check).

UNVERIFIED CONCERNS: U1 ds_urlencode high-byte behavior under macOS bash 3.2 (non-ASCII mailto only); U2 fetch-dblp.sh:93-94 server without Range support -> curl exit 33 aborts before MD5 check, corrupt .part not deleted; U3 SHA256SUMS not recomputed by reviewer; U4 sbt counts agent-reported; U5 Venue fixture with work-key sourceRecordId (Fixtures.scala:126) semantically odd but valid.

MANUAL ACTION REQUIRED: human rename TmpDumpSuite.scala -> GraphModelCoverageSuite.scala; set exec bits on six entry scripts and commit; run validate-manifest.sh --verify (record "manifest validation: N checks, 0 failures"); run validate-manifest.sh with no flag (expect exit 2); run fetch-samples.sh --dry-run and --verify; spot-check external values from review 1.
BLOCKED-ON OWNER: none
NEXT GATE: human executes validator to close gate 3, then accepts and commits Phase 3. N1-N3 to dataset-engineer and N4 to domain-engineer are optional.
```

# Phase 3 review report (code-reviewer, review 3: focused review of repair-02, HOCON configuration)

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Focused read-only review of repair-02 only: build.sbt and project/versions.properties (typesafe-config=1.4.3 added to core), ConfigLoader.scala, ConfigLoaderSuite.scala. Review-2 N1-N4 and U1-U5 deferred, not re-raised.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read and Grep only; grepped modules/** for com.typesafe, ConfigFactory, render, ConfigRenderOptions, systemEnvironment, systemProperties, .resolve()
TEST RESULTS: not run by reviewer; agent-reported core 174/174.
VALIDATION RESULTS:
  1. Build: build.sbt:45 typesafeConfig = javaDep("com.typesafe", "config", "typesafe-config"); build.sbt:138 core libraryDependencies ++= Seq(upickle.value, typesafeConfig.value), Compile scope, no .dependsOn; versions.properties:25 typesafe-config=1.4.3 (14 entries, was 13). No other project/plugin/alias/scope/graph change. Only use of com.typesafe in modules/** is ConfigLoader.scala via ConfigFactory.parseString; no render/ConfigRenderOptions; uPickle remains the only JSON library.
  2. Isolation: sole entry ConfigFactory.parseString(content, parseOptions(path)) at ConfigLoader.scala:302; no load/defaultReference/defaultOverrides/systemProperties/systemEnvironment/resolve/render; env input only in resolve (272-284), gated by SCHOLARCHAIN_CFG_ and ConfigKeys.byEnv.
  3. Precedence and validation unchanged (files layered leaf by leaf :269, env last :280, no defaults, MissingKey carries envName :464, configSchemaVersion required per file :382-388 and not settable from env, unknown file keys -> UnknownKey :404).
  4. Includes: RejectingIncluder (:204-218) implements all four includer interfaces; withFallback returns itself; every include form reaches a throwing method before any resource is opened; tests would fail without it.
  5. Substitutions: rejection rests on Config.isResolved (never resolve()); walk reports each unresolved path, recursing into lists of objects and nested objects; "<unknown>" fallback (:326) still rejects; += rejected and tested. Harmless quirk: `k = ${X}` later overridden by `k = literal` is accepted; the substitution is discarded, never evaluated.
  6. Numbers: literal text via getString (:368); integers exact; 7.0, 7e0, 9007199254740993.0 rejected for randomSeed; 1e999 rejected by range checks; negatives fail range checks; out-of-Long integers -> InvalidValue; quoted numbers are strings. Probability [0,1] and reject <= accept checks still apply (:505-511, :583-590).
  7. Errors: parseString wrapped in NonFatal (:303-320); every ConfigException -> MalformedFile or IncludeRejected; no non-fatal escape from load found. InvalidValue echoes configured values as before (incl. aws.accountId).
  8. Tests meaningful; system-property test safe (Test / fork := true at build.sbt:85, no testForkedParallel; properties restored in finally; systemProperties() never called).
ASSUMPTIONS: Typesafe Config 1.4.3 internals as described (library source not read this session; version pinned, tests freeze behaviour); diff checked against current file contents, not git output.
RISKS: unshaded config-1.4.3 in fat job jars may conflict with an EMR-supplied copy (watch Phases 4 and 6); include rejection depends on Typesafe's includer contract (tests would catch a regression on a version bump).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance of repair-02; optional nits to domain-engineer at the human's discretion.

VERDICT: APPROVE

FINDINGS (domain-engineer, nits only):
- NIT-1 ConfigLoaderSuite.scala:345-351: CONFIG_FORCE_* entries go only into the loader's env Map, so that half of the test cannot detect accidental use of Typesafe's real System.getenv override; the system-property half is meaningful. Test name overclaims slightly.
- NIT-2 ConfigLoaderSuite.scala:234: comment says "A later object replaces an earlier scalar" but the code tests list-replaces-list.
- NIT-3 ConfigLoader.scala:350: nested-list paths report only the innermost index (e.g. "notAKey[0]" for [[${?X}]]); substitution in an object inside a list reports a path without the index. File still rejected; reporting imprecision only.

UNVERIFIED CONCERNS:
- U-A: very deeply nested input may raise StackOverflowError (fatal for NonFatal), escaping load contrary to ConfigLoader.scala:33. Operator-supplied files, low exposure.
- U-B: MalformedFile carries Typesafe's parse message (:310), which may quote a token from the offending line; a secret wrongly placed in a config file could partly appear in an error.
- U-C: Typesafe reads the config.trace system property at class initialization; with config.trace=loads it writes trace lines to stderr; believed not sensitive for parseString, not checked.
```
