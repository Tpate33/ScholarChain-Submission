# DE-P5-04 final report (dataset-engineer; verbatim)

Archived by the orchestrator, 2026-11-14. Work order: D13 option D snapshot tokens in the dataset manifest (decisions-19, section 8 step 2).

---

The two API snapshot identifiers are now in the manifest, and validation checks them against PROVENANCE.tsv. Before I recorded anything, I checked the committed ledger: every OpenAlex row (152) and every Crossref row (19) was retrieved on 2026-11-05, with no other date for either source. So I did not need to stop.

Keys added to `/Users/taufeeqpatel/Desktop/ScholarChain/datasets/dataset-manifest.yaml`, for hadoop-engineer to read:
```
er_candidates.api_snapshots.openalex.snapshot_id    = "openalex-api-2026-11-05"
er_candidates.api_snapshots.openalex.source         = openalex
er_candidates.api_snapshots.openalex.retrieval_date = "2026-11-05"
er_candidates.api_snapshots.openalex.ledger_rows    = 152
er_candidates.api_snapshots.openalex.derived_from   = "datasets/samples/er-candidates/PROVENANCE.tsv"
er_candidates.api_snapshots.crossref.snapshot_id    = "crossref-api-2026-11-05"
er_candidates.api_snapshots.crossref.source         = crossref
er_candidates.api_snapshots.crossref.retrieval_date = "2026-11-05"
er_candidates.api_snapshots.crossref.ledger_rows    = 19
er_candidates.api_snapshots.crossref.derived_from   = "datasets/samples/er-candidates/PROVENANCE.tsv"
er_candidates.api_snapshots.derivation              = (text: rule, D13 option D basis, option B rejected, dblp keeps datasets.dblp.snapshot.id)
```
The `snapshot_id` values are meant to be copied verbatim. The bulk snapshot IDs (`openalex-2026-10-14`, `crossref-public-data-file-2026-03`) are deliberately not used.

The new check in `datasets/scripts/dsutil.py` fails when:
- a recorded token is not `<source>-api-<date>` built from the ledger;
- the recorded date or row count disagrees with the ledger;
- a source's ledger rows span more than one retrieval date;
- a timestamp is malformed;
- a field or entry is missing.

The fixture has 10 new cases that cover those failures, including using a bulk snapshot ID in place of the API ID.

The work order also asked whether a schema note exists: `datasets/schemas/er-candidates.md` does, and it now documents the new keys.

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: DE-P5-04 (Phase 5; human decision D13 option D; amendment docs/agent-runs/phase-05/amendment-fe-p5-06.md; decisions-16.md, decisions-19.md)
FILES CREATED: none
FILES MODIFIED:
  /Users/taufeeqpatel/Desktop/ScholarChain/datasets/dataset-manifest.yaml (new er_candidates.api_snapshots block; last_reviewed 2026-11-12 -> 2026-11-14)
  /Users/taufeeqpatel/Desktop/ScholarChain/datasets/scripts/dsutil.py (new er_api_snapshot_observed, er_api_snapshot_checks, er_validate_api_snapshots; new fixture _er_fixture_api_snapshots; validate() runs the derivation check right after the S3 checks so its lines appear near the top of the output; main() prints a SUMMARY line for each failed check before the totals)
  /Users/taufeeqpatel/Desktop/ScholarChain/datasets/schemas/er-candidates.md (new section documenting the api_snapshots fields)
COMMANDS RUN:
  git ls-files -- datasets
  datasets/scripts/validate-manifest.sh --verify  (before any change: 2303 checks, 0 failures)
  datasets/scripts/validate-manifest.sh --verify  (new check added, manifest not yet changed, run to observe the ledger dates: exit 1, the only FAIL was "er_candidates.api_snapshots present"; output truncated in the middle)
  datasets/scripts/validate-manifest.sh --verify  (same state, check moved earlier in the output; observed "INFO er-api-snapshot: openalex rows in datasets/samples/er-candidates/PROVENANCE.tsv retrieved on 2026-11-05: 152" and "... crossref ... retrieved on 2026-11-05: 19"; exit 1 with the same single expected FAIL)
  datasets/scripts/validate-manifest.sh --verify  (after the manifest change)
  datasets/scripts/fetch-samples.sh --verify datasets/er-candidates/fixture
  datasets/scripts/fetch-samples.sh --verify datasets/er-candidates
  sbt check  (foreground)
  git status --short
TEST RESULTS:
  sbt check: [success] Total time: 136 s. markTestRun wrote .claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=75, passed=908, skipped=0). Per project: core passed=299, entityResolution passed=384, hadoop passed=80, tests passed=145 ("Passed: Total 145, Failed 0, Errors 0, Passed 145"); rag, root, services, sparkAnalytics and storage have 0 tests.
  ER fixture: "er-fixture: 130 checks, 0 failures (temporary directory removed; repository not written)". This includes the 10 new "fixture: api snapshot ..." cases, all PASS.
VALIDATION RESULTS:
  validate-manifest.sh --verify: "manifest validation: 2321 checks, 0 failures" (2303 before, plus 18 new er-api-snapshot checks, all PASS).
  fetch-samples.sh --verify datasets/er-candidates: "er-verify: 1966 checks, 0 failures"; 290 stored files.
  Ledger evidence (PROVENANCE.tsv, recomputed by the validator; the INFO lines print one line per distinct date):
    openalex: 152 rows, the only retrieval date is 2026-11-05. This matches er-verify per split: dev openalex 45 + dev openalex-author 6 + heldout openalex 87 + heldout openalex-author 14 = 152.
    crossref: 19 rows, the only retrieval date is 2026-11-05 (dev 6 + heldout 13).
    I read 76 OpenAlex rows by hand (ledger lines 3-78, 2026-11-05T20:04:49Z to 20:08:27Z); all the rest were counted by the validator.
ASSUMPTIONS:
  - The retrieval date is the UTC date part of retrieved_at_utc.
  - OpenAlex covers both kinds, work-by-doi and authors-of-work.
  - dblp gets no API snapshot ID: its excerpts keep datasets.dblp.snapshot.id = dblp-2026-10-03.
  - ledger_rows is recorded and checked so that a later --sample run that changes the ledger is caught.
  - sbt check does not appear to run the dataset validation; the two are separate gates and both were run.
RISKS:
  - datasets/scripts/dsutil.py now differs from HEAD, so later runs of the dataset scripts hit the provenance "ask" until the human commits it.
  - The known Crossref miss (10.23919/cinc49843.2019.9005722) is retried on every --sample run. If it succeeds on a day other than 2026-11-05, validation fails by design, because one Crossref token would no longer cover all Crossref rows. That needs a human decision, not a value edit.
  - The harness flagged docs/agent-runs/phase-05/amendment-fe-p5-06.md as matching an instruction-shaped pattern (settings-json). I read it only as data and acted on nothing in it.
MANUAL ACTION REQUIRED:
  - Human: review and commit the three datasets/** files, so the dataset scripts are byte-identical to HEAD again.
  - Phase 3-style gate: confirm that no identifier was invented. Both values come from the committed PROVENANCE.tsv retrieval dates.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Manifest review by the human or code-reviewer. Then a hadoop-engineer work order to copy er_candidates.api_snapshots.{openalex,crossref}.snapshot_id verbatim into the snapshot provenance of the OpenAlex and Crossref ER records.
```

No UNATTRIBUTED audit change was reported during the run. I made no network fetch, no download and no --full run, and touched no file outside `datasets/**`.
