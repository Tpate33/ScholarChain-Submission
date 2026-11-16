# Phase 5 extraction failure 01: dblp release-layout-error and Crossref miss (evidence)

Recorded by the orchestrator, 2026-11-12/13. Not a human-gate record.

## Human report (verbatim transcription)

```text
I ran bash datasets/scripts/fetch-samples.sh --sample datasets/er-candidates.
It exited 1: fetched 0, already present 171, not fetched 121.

The DBLP extractor reported release-layout-error for conf/dac/0001AG12:
the record tag was not at the start of the line at offset 43784503.
No DBLP records were written. The Crossref lookup for
10.23919/cinc49843.2019.9005722 failed separately.

Preserve the failure evidence and route a focused repair to dataset-engineer.
Inspect the local archive around that offset and add a regression fixture
for the observed layout. Fix record boundary detection while preserving
exact source bytes, offsets, and validation. Keep the pinned archive and
selected pairs. Record the Crossref miss separately.

Run the local fixture checks and request focused review. Then show me the
files for the updated script checkpoint before I retry extraction.
```

## Repository state observed by the orchestrator

- Commits: `4c3ce6a phase-05: record verified DBLP release metadata` (the manifest-only commit) on top of `5e324bd` (script checkpoint).
- First orchestrator Bash call after the run was refused by `audit-bash-writes.sh --pre` as UNATTRIBUTED for exactly one path, `datasets/samples/er-candidates/FETCH-FAILURES.tsv` (rewritten by the human's `--sample` run); baseline reset; attributed to the human's run. `git status --porcelain` then showed no new paths beyond the known Phase 5 set (no dblp excerpt directory, no `DBLP-RELEASE-RANGES.tsv`), consistent with "No DBLP records were written".
- `datasets/samples/er-candidates/FETCH-FAILURES.tsv` is untracked, so `git diff` shows nothing; its content was read directly.

## FETCH-FAILURES.tsv as written by the run (summary of the 121 data rows)

Header: `unit_id	split	criterion	source	kind	identifier	url	reason	at_utc`

### A. dblp extraction (120 rows, one failure cause)

All 120 `source=dblp kind=rec` rows (file lines 3-122) carry the same release-level reason and timestamp; the failing record is `conf/dac/0001AG12` (unit `hr-karthik-chandrasekar`, heldout), and every other selected dblp key fails with it because the whole release step is refused:

```text
hr-jiayuan-ye	dev	homonym-rare	dblp	rec	conf/ccs/0001MMBS22	release:dblp-2026-10-03.xml.gz	release-layout-error: the record tag of key conf/dac/0001AG12 is not at the start of a line (line at offset 43784503). No dblp record was written.	2026-11-13T04:55:53Z
...
hr-karthik-chandrasekar	heldout	homonym-rare	dblp	rec	conf/dac/0001AG12	release:dblp-2026-10-03.xml.gz	release-layout-error: the record tag of key conf/dac/0001AG12 is not at the start of a line (line at offset 43784503). No dblp record was written.	2026-11-13T04:55:53Z
...
or-csur-deldjoonm21	heldout	ordinary	dblp	rec	journals/csur/DeldjooNM21	release:dblp-2026-10-03.xml.gz	release-layout-error: the record tag of key conf/dac/0001AG12 is not at the start of a line (line at offset 43784503). No dblp record was written.	2026-11-13T04:55:53Z
```

### B. Crossref lookup (1 row, separate cause)

```text
md-cinc-fotiadoukhv19	dev	multi-doi	crossref	work-by-doi	10.23919/cinc49843.2019.9005722	https://api.crossref.org/works?filter=doi:10.23919/cinc49843.2019.9005722&rows=2&select=DOI,title,subtitle,author,container-title,ISSN,issued,type,volume,issue,page,update-to,relation	expected exactly one Crossref item	2026-11-13T04:55:53Z
```

This is the same Crossref record that failed in the earlier DS-P5-01 run ("expected exactly one Crossref item"); it is unrelated to the dblp failure.

## Notes

- The next `--sample` run rewrites `FETCH-FAILURES.tsv` completely; this file is the preserved copy of the evidence.
- The pinned archive, its sidecar, `selection.tsv` and `split-assignment.tsv` are unchanged by the run (no git-visible change other than FETCH-FAILURES.tsv; the archive is git-ignored and was verified before the run).
