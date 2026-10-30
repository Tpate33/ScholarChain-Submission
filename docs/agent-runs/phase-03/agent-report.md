# Phase 3 agent reports (verbatim)

## dataset-engineer (run 1)

```text
STATUS: BLOCKED-ON
OWNER: dataset-engineer
WORK ORDER: Phase 3 (dataset half): dataset manifest, bounded scripts, legal samples with checksums, schema notes, runnable manifest validation, sbt check.
FILES CREATED:
  datasets/dataset-manifest.yaml
  datasets/scripts/lib-datasets.sh
  datasets/scripts/dsutil.py
  datasets/scripts/sample-seeds.tsv
  datasets/scripts/validate-manifest.sh
  datasets/scripts/fetch-dblp.sh
  datasets/scripts/fetch-openalex.sh
  datasets/scripts/fetch-crossref.sh
  datasets/scripts/fetch-orcid.sh
  datasets/scripts/fetch-samples.sh
  datasets/schemas/dblp.md
  datasets/schemas/openalex.md
  datasets/schemas/crossref.md
  datasets/schemas/orcid.md
  datasets/schemas/identifiers.md
  datasets/samples/ATTRIBUTION.md
  datasets/samples/SHA256SUMS
  datasets/samples/dblp/dblp-sample.xml
  datasets/samples/openalex/works-sample.jsonl
  datasets/samples/openalex/authors-sample.jsonl
  datasets/samples/openalex/institutions-sample.jsonl
  datasets/samples/crossref/works-sample.jsonl
  datasets/samples/orcid/0000-0002-2814-962X.xml
  datasets/samples/orcid/0000-0001-6899-0651.xml
  datasets/samples/orcid/0000-0002-4303-3908.xml
FILES MODIFIED: none
COMMANDS RUN:
  git status --porcelain (twice); git ls-files datasets; git ls-files .claude datasets project build.sbt
  shasum -a 256 <9 sample files> (in 3 batches; the first single call was refused by the guard for being over 400 bytes, nothing ran)
  datasets/scripts/validate-manifest.sh --verify -> exit 126 "(eval):1: permission denied: datasets/scripts/validate-manifest.sh"
  sbt check -> success
  About 45 read-only WebFetch calls to official pages and APIs, listed under each dataset below.
TEST RESULTS: sbt check succeeded. Every project (core, entityResolution, hadoop, rag, root, services, sparkAnalytics, storage, tests) reported "Passed: Total 0, Failed 0, Errors 0, Passed 0" (there are no tests yet). markTestRun wrote .claude/.last-test-run (git_head=e12777342c9b..., worktree=dirty, suites=0, passed=0, skipped=0). Format check and compile succeeded.
VALIDATION RESULTS:
  - Manifest validation: NOT RUN (exit 126, permission denied). I checked the parser and validator by reading them line by line, not by running them.
  - Sample checksums: computed with shasum -a 256 and recorded in datasets/samples/SHA256SUMS. 9 files, about 45 KB in total.
  - fetch-*.sh --dry-run / --verify / --sample: NOT RUN (same reason, and the scripts are uncommitted).
ASSUMPTIONS:
  - Selected portfolio: DBLP plus OpenAlex, ORCID and Crossref. This is the spec's recommended minimum. I chose Crossref over Semantic Scholar because I could verify Crossref's reuse terms and could not verify Semantic Scholar's in this run.
  - Samples are field-level excerpts. I transcribed real values from the official APIs and wrote them in each dataset's own record format. They are not byte copies of the dumps, and ATTRIBUTION.md says so. Fields I did not retrieve (DBLP mdate and url, ORCID put-codes for other-names) are omitted rather than guessed.
  - DBLP records came from the official SPARQL endpoint (https://sparql.dblp.org/sparql), because dblp.org/rec/*.xml and the mirrors returned a bot-protection page. I checked each key's author initials against the author order.
  - The S3 raw prefix uses the template s3://{raw_bucket}/raw/<dataset>/snapshot_date=YYYY-MM-DD/. The bucket comes from external configuration, and no real bucket name, account ID or ARN appears anywhere. The validator enforces this.
  - Fetched data goes to datasets/work/, never into datasets/samples/.
RISKS:
  - The validator needs Python 3 and uses its own strict YAML parser with stdlib only, so it does not depend on PyYAML. It has never been executed, so its first run may expose defects.
  - All values passed through a summarizing fetch tool. The Spanner OpenAlex authorships and the dblp MD5 were each read twice and agreed. Most other values were read once.
  - Untracked files under datasets/work/ will show up in git and in the audit until datasets/work/ is gitignored.
MANUAL ACTION REQUIRED:
  1. Human: make the six entry scripts executable before committing:
     chmod +x datasets/scripts/validate-manifest.sh datasets/scripts/fetch-dblp.sh datasets/scripts/fetch-openalex.sh datasets/scripts/fetch-crossref.sh datasets/scripts/fetch-orcid.sh datasets/scripts/fetch-samples.sh
  2. Human: commit, then run from the repository root:
     datasets/scripts/validate-manifest.sh --verify
     datasets/scripts/fetch-samples.sh --dry-run
     datasets/scripts/fetch-samples.sh --verify
     Optional, bounded network fetch of under 20 MB: datasets/scripts/fetch-samples.sh --sample
     Alternatively, the orchestrator can rerun dataset-engineer after the commit to run these.
  3. build-engineer: add datasets/work/ to .gitignore.
  4. Human: answer the open dataset questions listed below.
BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: "datasets/scripts/validate-manifest.sh --verify" -> "(eval):1: permission denied: datasets/scripts/validate-manifest.sh", exit 126. Files created with Write are not executable, and dataset-engineer has no allowlisted command to set the bit. The guard also requires scripts to be committed and identical to HEAD.
NEXT GATE: The human sets the exec bits and commits. Then "datasets/scripts/validate-manifest.sh --verify" must print "manifest validation: N checks, 0 failures". After that, code-reviewer checks that no license, identifier, source, size or checksum is invented.
```

Supplementary report sections (selected datasets, identifier formats, linkage keys, sample coverage, open questions) are reproduced in the orchestrator's Phase 3 summary and in `datasets/schemas/identifiers.md`.

### Selected datasets (verified 2026-10-29)

| Dataset | Pinned snapshot | License | Verified at | Expected size |
|---|---|---|---|---|
| DBLP (seed) | `dblp-2026-10-03.xml.gz` (DOI 10.4230/dblp.xml.2026-10-03) | CC0 1.0 (ODC-BY 1.0 secondary) | https://dblp.org/xml/README.txt, DROPS release page | 1.03 GB; upstream MD5 `cbd329100ea1bbb2873fa2f6d414ea00` |
| OpenAlex | release 2026-10-14, JSONL | CC0 1.0 | https://openalex.s3.amazonaws.com/LICENSE.txt, https://openalex.org/about | 755,513,952,946 bytes / 586,176,148 records total; works 675,298,563,638 bytes / 464,765,485 records |
| Crossref | March 2026 public data file | metadata largely not subject to copyright; abstracts excluded | Crossref REST API docs, 2026 release blog | "208 GB" (blog); torrent page 223.05 GB / 35,909 files |
| ORCID | `ORCID_2025_10` (data as of 2025-10-01) | CC0 1.0, attribution requested | https://info.orcid.org/public-data-file-use-policy/, figshare API | summaries 46,328,319,190 bytes; no upstream MD5 |

### Open dataset questions

1. Which venues define the DBLP functional subset (>= 100,000 works)? Fallback: all article and inproceedings records 2015-2024.
2. Copy all OpenAlex works (~675 GB) to the raw zone, or extract only DOI matches via the API? API key/limits unverified.
3. Crossref acquisition channel: 2026 requester-pays key unverified (requester pays transfer); alternative is the torrent.
4. Is storing the ORCID file (46 GB of personal data) acceptable, and is there a 2026 release? None listed on 2026-10-29.
5. DBLP unverified: full-dump encoding, exact form of the `orcid` attribute, person IDs in the XML, uncompressed size and record count. Sample has no `www` person records.
6. Crossref and ORCID unverified: 2026 Crossref file line structure; internal layout of the ORCID 2025 summaries archive.
7. ORCID sample 0000-0002-4303-3908 has no work entry (DOI claim confirmed via ORCID search only).

## domain-engineer (run 1)

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 3, domain half (docs/agent-runs/phase-03/prompt.md). Shared domain records, stable IDs, enums, configuration loader, validation, and contract tests in modules/core.
FILES CREATED (modules/core/src/):
  main/scala/scholarchain/core/{Canonical,ValidationError,Enums,Identifiers,StableIds,Contract,Provenance,Bibliographic,Identity,Collaboration,Graph,Runtime}.scala
  main/scala/scholarchain/core/config/{ScholarChainConfig,ConfigLoader}.scala
  test/scala/scholarchain/core/{Fixtures,CanonicalSuite,IdentifiersSuite,StableIdSuite,PairAndWindowSuite,EnumCodecSuite,WireContractSuite,SchemaVersionSuite,ValidationSuite,TmpDumpSuite}.scala
  test/scala/scholarchain/core/config/ConfigLoaderSuite.scala
FILES MODIFIED: none
COMMANDS RUN: git ls-files -- modules/core project; git ls-files --others --exclude-standard -- datasets/schemas datasets/dataset-manifest.yaml; sbt core/compile (5 runs, all succeeded); sbt core/test (2 runs; first used a temporary dump test to print wire output, reviewed before freezing as goldens; second passed 139/139); sbt check (succeeded); git status --porcelain; git ls-files --others --exclude-standard -- modules/core
TEST RESULTS: core "Passed: Total 139, Failed 0, Errors 0, Passed 139"; other projects Total 0. markTestRun: "core: production sources=14, test sources=11, passed=139"; "wrote .claude/.last-test-run (git_head=e12777342c9b08775eb294a8452a0381e528ac8b, worktree=dirty, suites=10, passed=139, skipped=0)". Per suite: CanonicalSuite 7, GraphModelCoverageSuite 3, WireContractSuite 43, ValidationSuite 21, StableIdSuite 12, IdentifiersSuite 9, EnumCodecSuite 8, SchemaVersionSuite 6, ConfigLoaderSuite 14, PairAndWindowSuite 16.
VALIDATION RESULTS: verifyBuildSources OK; verifyVersionLedger OK (13 entries; no dependency added); scalafmtSbtCheck and scalafmtCheckAll OK; Test/compile OK under -Werror; core imports only uPickle/ujson and the JDK.
ASSUMPTIONS:
  A1 Config files are strict JSON (RFC 8259, a syntactic subset of HOCON) parsed by the pinned ujson; no new library, no hand-written YAML/HOCON parser. No comments/includes/substitutions. Full HOCON would route BLOCKED-ON build-engineer (com.typesafe:config). Human to confirm.
  A2 SourceType = dblp, openalex, orcid, crossref, scholarchain-derived, scholarchain-runtime. Other optional datasets omitted (no manifest entry; adding a tag is a wire-format change).
  A3 Only ORCID (MOD 11-2 checked) is a validated canonical external researcher ID; DBLP pids and OpenAlex A-IDs are ExternalIds / anchors for `sc` IDs.
  A4 Policy/model/prompt/run versions live only in Provenance; EvidenceItem.workId optional; MatchDecision adds conflictingTargetIds, rulesFired, decidedAt.
  A5 Unknown JSON fields rejected; new fields require a schema version bump.
  A6 No mutation testing; "fails without the behavior" rests on exact goldens and exact error values, not an observed red run.
RISKS:
  R1 Researcher IDs switch from `sc` to `orcid` when an ORCID is validated later; downstream alias/redirect not designed.
  R2 DBLP key, pid, ROR checks are format checks on observed forms; ROR checksum not verified; unusual keys fail closed.
  R3 Full 64-hex SHA-256 IDs increase storage size.
  R4 SortedMap/Option fields may need explicit Spark encoders; Spark should consume JSON/DataFrames.
  R5 teamSize >= 2 and metrics consistency rules (C <= T, M <= T) may surface data issues as rejections.
MANUAL ACTION REQUIRED:
  1 Human: rename modules/core/src/test/scala/scholarchain/core/TmpDumpSuite.scala to GraphModelCoverageSuite.scala (class is GraphModelCoverageSuite), then re-run sbt check.
  2 Human: confirm A1 (strict JSON config) or request HOCON via com.typesafe:config (build-engineer).
  3 Real environment config files (e.g. config/*.json) have no owner in docs/agents.md; factory-engineer matrix amendment needed before any are created.
  4 Empty .gitkeep placeholders in modules/core/src/{main,test}/scala remain; harmless.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of modules/core (verify no invented license, identifier, or source; fixtures synthetic: ORCIDs 0000-0000-0000-0001 / 0000-0000-0000-001X, DOI prefix 10.1234, DBLP stream `example`), then human Phase 3 gate (A1 decision, file rename) and sbt check re-run.
```

Supplementary sections (sample JSON, stable ID formulas, pair canonicalization, config precedence, schema versions) were returned in full in the domain-engineer's final message and are summarized in the orchestrator's Phase 3 summary; the frozen goldens live in `WireContractSuite.scala`.

Domain-engineer unresolved dataset questions:
1. Exact `sourceSchemaVersion` strings each producer writes ("dblp.schema.v1" used in fixtures only); align with manifest schema versions.
2. Whether DBLP `ee` arXiv links populate WorkRecord.arxivId.
3. Whether OpenAlex-asserted ORCIDs without corroboration may back a `researcher:orcid` ID (entity-resolution policy).
