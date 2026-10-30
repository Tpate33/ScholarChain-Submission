# Phase 3 human gate

Decider: Taufeeq Patel
Date: 2026-10-29

## Decision
I accept Phase 3: the ScholarChain shared data contracts (modules/core), the
dataset manifest, bounded dataset scripts, legal samples, and schema notes
(datasets/**), together with repair-01 and repair-02.

I accept the build change in repair-02: com.typesafe:config 1.4.3 is added to
the version ledger and to the core project. It is the only new dependency.

## Checks I ran personally (my terminal, repository root)
- datasets/scripts/validate-manifest.sh --verify
  Result: manifest validation: 313 checks, 0 failures.
- datasets/scripts/fetch-samples.sh --dry-run
  Result: all four datasets completed; 0 bytes fetched.
- datasets/scripts/fetch-samples.sh --verify
  Result: all datasets completed successfully.
- sbt check after the rename, before repair-02
  Result: core 167 passed, 0 failed, 0 errors.
- Final sbt check after repair-02
  Result: 174 passed, 0 failed, 0 errors.
- I checked the test marker .claude/.last-test-run. It matches my final run:
  passed=174, suites=10, 0 failed, 0 errored, 0 skipped,
  2026-10-29T23:47:57Z.
- I reviewed git status and the diff of build.sbt and
  project/versions.properties.
- The --sample (network) and --full modes were not run.

## Reviewer-reported behavior (code-reviewer, read-only; not run by me)
- Review 1: REQUEST_CHANGES (1 major, 13 minor/nit findings).
- Review 2: APPROVE after repair-01. All 14 review-1 findings were fixed; the
  file rename was done by me. Reported by reading the code:
  - every record carries provenance;
  - all 20 record types have literal JSON goldens checked in both directions;
  - the coauthor-edge, mention, and graph-edge ID formulas are pinned to
    hand-written encodings;
  - pair-ordering and time-window tests are correct;
  - validation rejects missing provenance, confidence outside [0,1],
    unsupported schema versions, malformed IDs, non-canonical pairs, and
    start year > end year;
  - nothing is hardcoded;
  - no ownership rule was broken.
- Review 3: APPROVE for repair-02 (HOCON). Reported by reading the code:
  - include and substitution rejection is enforced;
  - precedence is unchanged;
  - the only new dependency is Typesafe Config, used only for parsing;
  - uPickle remains the only JSON library.
- The reviewer ran no commands; its conclusions come from reading code.

## Agent-reported results (not run by me)
- build-engineer: sbt packageJobs built both job jars with no merge
  conflicts.
- domain-engineer: sbt check passed 174/174 after repair-02, consistent with
  my final run.

## Phase 3 gate items
- Every record has provenance: accepted (reviewer-reported).
- Wire-format contract tests pass: accepted. They pass in my sbt check; the
  reviewer reported their coverage and meaning.
- Dataset manifest validation passes: accepted (run by me: 313 checks,
  0 failures).
- Sample scripts do not silently fetch large files: accepted. My dry run
  fetched 0 bytes. The reviewer reported that no mode flag means exit 2 and
  that --full is human-only.
- Pair-ordering and time-window tests pass: accepted. They pass in my
  sbt check; the reviewer reported they are correct.
- No dataset license, identifier, or source is invented: accepted within the
  verification limits below.

## Decisions made in this phase
- Configuration format: I rejected strict-JSON configuration files as
  insufficient for the "HOCON or YAML" requirement and chose HOCON through
  Typesafe Config (repair-02). Precedence is unchanged: SCHOLARCHAIN_CFG_*
  environment variables, then later files, then earlier files, with no
  defaults. HOCON include and substitution features are rejected.
- Dataset portfolio: DBLP plus OpenAlex, ORCID, and Crossref (the spec's
  recommended minimum).
- Download area: fetched data goes only to the git-ignored datasets/downloads/
  (review 1 M1). No .gitignore change was needed.

## External source verification
Independently verified:
- ORCID release: published 2025-10-20, data as of 2025-10-01, CC0 1.0, XML
  format (official release page, checked by me).
- General reuse terms for DBLP, OpenAlex, Crossref, and the ORCID Public Data
  File (checked using Codex).
- DBLP release date, DOI, displayed size, and published MD5 (Codex).
- OpenAlex release date 2026-10-14 (Codex).
- Crossref 2026-03-17 announcement, nearly 180 million records, 208 GB, and
  dataset DOI (Codex).

Not independently verified (recorded as the dataset-engineer's claims):
- Exact OpenAlex manifest totals.
- ORCID summaries archive size, figshare file and article IDs, and whether a
  2026 ORCID file exists.
- The Crossref torrent infohash, size, and file count, and the requester-pays
  bucket.
- The DBLP DTD change date and person IDs.
- All individual sample values (keys, IDs, put-codes, author lists, titles,
  pages). The reviewer checked only their internal consistency.
- Observed sizes and our SHA-256 of every full corpus remain
  pending-human-download.

## Manual changes (disclosed)
- Renamed modules/core/src/test/scala/scholarchain/core/TmpDumpSuite.scala to
  GraphModelCoverageSuite.scala, the class it contains, using mv in my
  terminal. Content is unchanged.
- Set the executable bit on the six entry scripts under datasets/scripts/
  (validate-manifest, fetch-dblp, fetch-openalex, fetch-crossref, fetch-orcid,
  fetch-samples) with chmod +x. Content is unchanged.
- Ran the validations and sbt check in my own terminal. The audit hook reported
  these changes as UNATTRIBUTED. That is expected, and each report listed only
  the paths I changed (the last one listed only the test marker).

## Accepted deferrals and limitations
- Optional review findings deferred: review 2 nits N1-N4 and concerns U1-U5;
  review 3 nits NIT-1 to NIT-3 and concerns U-A to U-C.
- The new dataset bash code was exercised only in --dry-run and --verify. The
  --sample network mode was never run.
- Typesafe Config is bundled unshaded into the Hadoop and Spark job jars. A
  classpath conflict with an EMR-supplied copy must be watched in the Phase 4
  and Phase 6 sample runs.
- The HOCON include rejection relies on Typesafe Config 1.4.3 internals. The
  tests should catch a change on a version bump.
- Later producers must emit sourceSchemaVersion as <dataset>-raw/<x.y.z> or
  scholarchain.<record>.v<N>.
- Open dataset questions remain for later phases: the DBLP venue subset (needed
  for Phase 4), the OpenAlex acquisition route, the Crossref acquisition
  channel, and ORCID storage and privacy.
- Real configuration files (for example config/*.conf) have no owner yet. A
  factory-engineer matrix amendment, my ratification, and a session restart
  are required before any are created.
- Carried over from Phase 2: the Spark/EMR runtime decision (F2) before
  Phase 6, and Docker image digest pinning.

## Commit record
The accepted commit hash will be recorded in commit.txt in a follow-up
commit. Phase 4 has not started.
