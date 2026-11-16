# Phase 5 extraction result 02: retry from checkpoint e86a620 (evidence)

Recorded by the orchestrator, 2026-11-13. Not a human-gate record.

## Human report (verbatim transcription)

```text
I retried extraction from checkpoint e86a620. Exit status was 1.
The summary was: fetched 119, already present 171, not fetched 2,
bytes in ledger 698351.

The two misses were DBLP conf/armor/BustosFAR12 (expected author
absent) and Crossref DOI 10.23919/cinc49843.2019.9005722
(expected exactly one item).

Record the result and have dataset-engineer run the reviewed --verify
against the local archive. Report byte-range verification, dev/held-out
overlap checks, and saved counts by split. Keep the selection fixed
and preserve both misses in the evidence. If verification passes,
bring me the next step for generating the held-out labeling queue.
```

## Orchestrator checks

- `git log --oneline --max-count=3`: `e86a620 phase-05: checkpoint DBLP extraction repair and selection inputs`, `4c3ce6a …`, `5e324bd …`.
- First orchestrator Bash call after the run was refused by `audit-bash-writes.sh --pre` as UNATTRIBUTED for paths all under `datasets/samples/er-candidates/`: `DBLP-RELEASE-RANGES.tsv`, `FETCH-FAILURES.tsv`, `PROVENANCE.tsv`, and the new `dev/dblp/*.xml` and `heldout/dblp/*.xml` excerpts (119 files listed). Baseline reset; attributed to the human's `--sample` run (the files it writes). No path outside `datasets/samples/er-candidates/` was reported.

## FETCH-FAILURES.tsv as written by the retry (complete content)

```text
# Requests of the last --sample run that did not produce a stored record (DS-P5-01).
unit_id	split	criterion	source	kind	identifier	url	reason	at_utc
hr-juan-carlos-ruiz-garcia	heldout	homonym-rare	dblp	rec	conf/armor/BustosFAR12	release:dblp-2026-10-03.xml.gz	excerpt-check-failed: selection check failed: no author 'Juan-Carlos Ruiz-Garcia 0001'	2026-11-13T05:55:45Z
md-cinc-fotiadoukhv19	dev	multi-doi	crossref	work-by-doi	10.23919/cinc49843.2019.9005722	https://api.crossref.org/works?filter=doi:10.23919/cinc49843.2019.9005722&rows=2&select=DOI,title,subtitle,author,container-title,ISSN,issued,type,volume,issue,page,update-to,relation	expected exactly one Crossref item	2026-11-13T05:55:45Z
```

Two misses, kept separate:
- **dblp** `conf/armor/BustosFAR12` (heldout, unit `hr-juan-carlos-ruiz-garcia`): `excerpt-check-failed`, expected author `Juan-Carlos Ruiz-Garcia 0001` absent. Predicted by the DS-P5-09 in-memory inspection; selection unchanged.
- **Crossref** `10.23919/cinc49843.2019.9005722` (dev, unit `md-cinc-fotiadoukhv19`): "expected exactly one Crossref item"; third occurrence (DS-P5-01, 2026-11-13T04:55:53Z, 2026-11-13T05:55:45Z).

The previous run's failure file is preserved in `extraction-failure-01.md`.

## Next

DS-P5-10 (dataset-engineer): `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz`, reporting byte-range verification, dev/held-out overlap checks and saved counts by split; selection fixed.
