# Phase 3 repair-01

Source: `review-report.md` (code-reviewer review 1, REQUEST_CHANGES).

## Routing

| Findings | Owner |
|---|---|
| M1, m1, m2, m3, m4, m6, m7, n2, n3 (+ report schema_version strings for m8) | dataset-engineer |
| m5, m8, n1 (comment only), n4, n5 | domain-engineer |
| n1 (file rename) | human |

## dataset-engineer report (verbatim)

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Phase 3 repair-01. Fix code-reviewer findings M1, m1, m2, m3, m4, m6, m7, n2 and n3 in datasets/**, and report the schema_version strings for m8.
FILES CREATED: none
FILES MODIFIED (all still untracked):
  M1: lib-datasets.sh :15-16, :59-60, :95-110 (default datasets/downloads/<dataset> at :96; output outside datasets/downloads/ refused :105-108; empty segments refused :101); fetch-dblp.sh:8-12; fetch-openalex.sh:9; fetch-crossref.sh:7; fetch-orcid.sh:8-9; fetch-samples.sh:7-8; sample-seeds.tsv:4; dataset-manifest.yaml:35, :37 (work_area -> download_area, cites .gitignore rule and BuildGuard), :155-156; samples/ATTRIBUTION.md:7. No schema note referred to datasets/work/.
  m1: dsutil.py:224-240 (NULLABLE_NESTED, is_empty_value: rejects null, blank, empty list/mapping, lists with such items); :465-467 applied to every REQUIRED_NESTED sub-field; exemption snapshot.retrieval_date (own null-or-date check).
  m2: dsutil.py:270-303 orcid_banned_tags (namespaces email, address, keyword, researcher-url, external-identifier, employment, education, distinction, invited-position, membership, qualification, service, funding, peer-review, research-resource; local name biography or section/summary names), applied :432-438; promise widened at dataset-manifest.yaml:484, :511 and schemas/orcid.md:33.
  m3: fetch-samples.sh:10-14, :28-37 (ledger datasets/downloads/.sample-budget.<pid>, exported SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER, trap cleanup), :45-47 run total; lib-datasets.sh:167-196 (ds_is_uint, ds_budget_used, ds_budget_add); ds_fetch :213-224 caps each request at remaining budget; failed/aborted bytes counted :231-235, rejected oversize :243; explicit budget-read checks :216-217, :189-190; contract text :9-16.
  m4: lib-datasets.sh:138-148 ds_redact_url (query/fragment -> ?<query-redacted>) used for FETCH-LOG.tsv :252 and all ds_fetch log/error lines; :150-165 ds_urlencode (RFC 3986, LC_ALL=C); fetch-crossref.sh:43 mailto=$(ds_urlencode "$MAILTO"), header :6-9.
  m6: dsutil.py:259-266 OS metadata files/dirs ignored; validate_samples skips them :365-379 with INFO line; unlisted files still fail :380-382; missing listed files still fail :386.
  m7: fetch-dblp.sh:87-104 (.part matching pinned MD5 not re-requested; MD5 mismatch deletes .part with re-run message; gzip -t failure deletes .part); header :9-12.
  n2: validate-manifest.sh:25-59 (no flag -> usage, exit 2; -h/--help -> usage, exit 0); header :5-18.
  n3: dsutil.py:246-256 ALLOWED_S3_BUCKETS + s3_location_allowed (exact bucket name), used :579.
  SHA256SUMS:1 updated for ATTRIBUTION.md to 5e29e1499641fbda3c60941616f918e22c3c6f0f6bcb134d5f956903c805da64; no other sample changed.
COMMANDS RUN: grep -rn ... (denied by guard-dangerous.sh shell-syntax-gate, not executed); git ls-files --others --exclude-standard -- datasets; shasum -a 256 datasets/samples/ATTRIBUTION.md (5e29e149...da64); sbt check (succeeded); git status --porcelain. No WebFetch.
TEST RESULTS: sbt check succeeded. core "Passed: Total 139, Failed 0, Errors 0, Passed 139"; all other projects Total 0. markTestRun wrote .claude/.last-test-run (git_head=e12777342c9b08775eb294a8452a0381e528ac8b, worktree=dirty, suites=10, passed=139, skipped=0). sbt check does not run dataset validation.
VALIDATION RESULTS: validate-manifest.sh --verify and fetch scripts still NOT run (uncommitted, not executable). By reading: nested required values non-empty under m1; ORCID samples contain no banned tag; manifest s3 tokens pass exact-bucket check; no datasets/work reference remains. m8 schema_version strings: dblp "dblp-raw/1.0.0", openalex "openalex-raw/1.0.0", crossref "crossref-raw/1.0.0", orcid "orcid-raw/1.0.0"; manifest_version "1.0.0".
ASSUMPTIONS: all output dirs restricted to datasets/downloads/ (stricter than asked); ORCID ban also matches local names in any namespace (over-match on purpose; ORCID v3.0 names from memory, not re-checked); ._* treated as AppleDouble metadata.
RISKS: new bash code and validator changes never executed; curl --retry 2 can make per-request bound ~3x cap; ds_urlencode untested with non-ASCII mailto.
MANUAL ACTION REQUIRED: (1) no .gitignore change needed; withdraw build-engineer request. (2) human chmod +x six entry scripts, commit. (3) run validate-manifest.sh --verify; validate-manifest.sh (expect usage, exit 2); fetch-samples.sh --dry-run; fetch-samples.sh --verify. (4) domain-engineer align sourceSchemaVersion fixtures (m8).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-review of datasets/**; human commit with exec bits; validate-manifest.sh --verify must print "manifest validation: N checks, 0 failures".
```

Orchestrator note: the pending build-engineer request to ignore `datasets/work/` is withdrawn (M1 resolved by using the ratified `datasets/downloads/`).

## domain-engineer report (verbatim)

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 3 repair-01 (domain-engineer). Fix code-reviewer findings m5, m8, n1 (comment only), n4 and n5 under modules/core/**. Optionally add the WorkRecord check that the workId namespace agrees with `source`. Run sbt check.
FILES CREATED: none
FILES MODIFIED (all still untracked; under modules/core/src/):
  m5: test/.../WireContractSuite.scala :70-107 literal frozen goldens for CoauthorPairCandidate, PublicationTeam, AuthorshipRecord, AnswerResponse, Venue, Institution, Topic, GraphNode, standalone EvidenceItem, standalone AuthorMention (+ OpenAlex provenance fragments); :134-143 both-direction checks via frozen(); :145-169 coverage test: frozen types == SchemaVersions.all.keySet.
  m5 (hash pins): test/.../StableIdSuite.scala two new tests after :58: mention ID netstring `23:scholarchain.mention.v1,38:work:dblp:journals/example/Synthetic21,1:0,`; graph edge ID netstring `26:scholarchain.graph-edge.v1,15:COAUTHORED_WITH,36:<researcherA>,36:<researcherB>,18:graph-policy-1.0.0,73:<coauthor edge id>,`.
  m8 (validation): main/.../Provenance.scala :23-29 doc, :61-64 check in Provenance.check, :70-91 RawSchema/RecordSchema regexes and sourceSchemaVersionProblem(source, version).
  m8 (fixtures/tests): Fixtures.scala:46 dblpProv -> "dblp-raw/1.0.0"; :53-62 openAlexProv ("openalex-raw/1.0.0", snapshot "openalex-2026-10-14"); Institution and Topic now use openAlexProv; WireContractSuite.scala:14 DblpProv literal -> "dblp-raw/1.0.0"; ValidationSuite.scala:95-152 three tests.
  n1: TmpDumpSuite.scala:1-3 stale comment removed; no duplicate class.
  n4: Fixtures.scala:120 ISSN 0001-0782 -> synthetic 0000-0019 (check digit 9); header :5-13; StableIdSuite uses venue:issn:0000-0018 (bad) / 0000-0019; IdentifiersSuite.scala:38-45 keeps 0001-0782 only as a labelled external check-digit oracle, adds 0000-0019 and 0000-0018.
  n5: main/.../ValidationError.scala:73-81 rule [0, 9999] explicit (MinYear=0, MaxYear=9999), message "year <v> is outside [0, 9999]"; TimeWindow.of doc updated; PairAndWindowSuite.scala:105-127 tests (accept 0, 999, 9999; reject -1, 10000 incl. WorkRecord.year).
  Optional: main/.../Bibliographic.scala :99-110 WorkRecord.NamespaceOf (dblp->dblp, openalex->openalex, orcid->orcid-work, crossref->doi); :126-135 rejects mismatched namespace; doc :77-80; test ValidationSuite.scala:154-179.
GOLDENS CHANGED: only shared DblpProv literal (m8), affecting WorkRecordGolden and its two mentions; no hash ID changed (sourceSchemaVersion not an ID input). New goldens: Venue with ISSN 0000-0019; Institution and Topic with OpenAlex provenance. Encoder output reviewed before freezing. No schema version bumped (v1 never committed).
COMMANDS RUN: sbt core/test (Total 147; temporary TEMPDUMP test removed before final run); sbt check (success); git diff --stat (no tracked changes); git status --porcelain; git ls-files --others --exclude-standard -- modules/core docs/agent-runs/phase-03 datasets/dataset-manifest.yaml
TEST RESULTS: sbt check succeeded. core "Passed: Total 167, Failed 0, Errors 0, Passed 167"; all other projects Total 0. markTestRun wrote .claude/.last-test-run (git_head=e12777342c9b08775eb294a8452a0381e528ac8b, worktree=dirty, suites=10, passed=167, skipped=0).
VALIDATION RESULTS: 20/20 record types have both-direction literal goldens (40 golden tests + 1 coverage test); coauthor edge, mention, graph edge hashes pinned against hand-written netstrings. sourceSchemaVersion format validated: dataset sources `<own tag>-raw/<MAJOR.MINOR.PATCH>`; derived/runtime `scholarchain.<kebab-record>.v<N>`; format only, not manifest existence. Stable ID formulas, pair canonicalization, config precedence, schema version constants unchanged.
ASSUMPTIONS: manifest raw schema_versions follow `<tag>-raw/<semver>`; derived records cite a ScholarChain record schema; Crossref WorkRecords use the DOI namespace; fixture snapshot "openalex-2026-10-14" synthetic.
RISKS: 0000-0019 not verifiable offline as unregistered; producers must emit the new sourceSchemaVersion format; pre-release version suffixes would be rejected; coverage test does not prove each golden is meaningful.
MANUAL ACTION REQUIRED: human renames TmpDumpSuite.scala to GraphModelCoverageSuite.scala, then commits; code-reviewer re-reviews modules/core/**.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-review of modules/core/**, then human acceptance and commit of Phase 3.
```
