# ER candidate sample set: file formats (work order DS-P5-01)

Owner: `dataset-engineer`. Schema version: `er-candidates-raw/1.0.0`. Manifest section:
`datasets/dataset-manifest.yaml` key `er_candidates`.

## Purpose

These are real upstream records for building entity-resolution review pairs. They are not labels.
No file contains a match label, an expected outcome, a score, or a decision. A shared author name is
never evidence of identity.

## Layout

```text
datasets/er-candidates/selection.tsv            fixed record list (unit, criterion, source, kind, identifier, dblp selection facts)
datasets/er-candidates/split-assignment.tsv     dev/heldout per selection unit (written before any fetch; rule in its header)
datasets/samples/er-candidates/PROVENANCE.tsv   one row per stored file: URL or release byte range, UTC time, HTTP status, bytes, sha256, licence
datasets/samples/er-candidates/FETCH-FAILURES.tsv  requests of the last --sample run that stored nothing, with the reason
datasets/samples/er-candidates/DBLP-RELEASE-RANGES.tsv  one row per dblp excerpt: release, checksums, header/record/footer byte ranges
datasets/samples/er-candidates/<split>/dblp/<key with / replaced by __>.xml
datasets/er-candidates/fixture/fixture-release.xml  extractor fixture (never extracted into samples)
datasets/samples/er-candidates/<split>/openalex-works/<doi-safe>-<sha8>.json
datasets/samples/er-candidates/<split>/openalex-authors/<A-ID>.json
datasets/samples/er-candidates/<split>/crossref-works/<doi-safe>-<sha8>.json
```

`<split>` is `dev` or `heldout`. Run any downstream pipeline once per split directory, never over both
at once. A combined run would create candidate pairs that cross the split.

## Labeler visibility: columns that must be withheld (RV-P5-02 finding 5)

Several bookkeeping columns encode **dblp's own identity claims**. Anyone who sees them can copy dblp's
answer instead of judging the records. They must never be shown to human gold-set labelers, and must
never be copied into a review queue row or a label file:

| File | Column or part | What it reveals |
|---|---|---|
| `selection.tsv` | `criterion` | `homonym-*` units mean "dblp says these are two different persons". `name-variant` and `orcid-vs-none` units mean "dblp says these are one person". `preprint-vs-vor`, `conf-vs-journal` and `multi-doi` units name the work relation dblp asserts |
| `selection.tsv` | `expect_author` | the dblp author string, including dblp's homonym suffix (`Jiayuan Ye 0001` vs `0002`) |
| `selection.tsv` | `expect_doi` (the column) | which DOI the selection tied to which unit and dblp record. The DOI value itself is not hidden: it is the record's own `ee` link, and the blind view shows DOIs by design. What is withheld is the unit-to-DOI association (RV-P5-07 NIT-7) |
| `selection.tsv`, `split-assignment.tsv`, `PROVENANCE.tsv`, `FETCH-FAILURES.tsv` | `unit_id` | the grouping itself; name-variant and ORCID unit IDs (for example `nv-208-1029-*`, `ov-*`) encode the dblp person pid |
| `PROVENANCE.tsv`, `FETCH-FAILURES.tsv`, `split-assignment.tsv` | `criterion` | as above |
| file paths | the `<split>` directory and the unit grouping | which records were selected together |

These are source facts used for selection and verification, not match labels, and no policy code reads
`selection.tsv`. The ER blind view (owner: `entity-resolution-engineer`) already excludes `criterion` and
unit IDs, as reported by code-reviewer in RV-P5-05 (`BlindView.scala:141-143, 237-268`). dblp homonym
suffixes inside record content are masked by the blind view (`DblpMasking`, same owner). Whether the blind view
covers every column above is for that owner to confirm. Nothing in `datasets/**` enforces it.

## Exact byte content of each file kind

Every file holds the unmodified bytes of a single upstream response or a single release byte range.
None is re-serialized, pretty-printed, re-encoded, or wrapped by ScholarChain.

| Directory | Bytes | Shape a reader must handle |
|---|---|---|
| `openalex-works/` | body of `GET https://api.openalex.org/works/doi:<doi>?select=id,doi,title,display_name,publication_year,type,authorships,primary_location,biblio,ids,primary_topic,is_retracted` | One JSON work object on a single line with no trailing newline. It has the same field names as the snapshot `works` entity (`datasets/schemas/openalex.md`), restricted to the selected root fields. No `abstract_inverted_index`. |
| `openalex-authors/` | body of `GET https://api.openalex.org/authors/<A-ID>?select=id,orcid,display_name,display_name_alternatives,last_known_institutions,ids` | One JSON author object on a single line with no trailing newline. |
| `crossref-works/` | body of `GET https://api.crossref.org/works?filter=doi:<doi>&rows=2&select=DOI,title,subtitle,author,container-title,ISSN,issued,type,volume,issue,page,update-to,relation` | **Envelope**: `{"status":"ok","message-type":"work-list","message":{...,"items":[<one work object>],...}}`. The work object is `message.items[0]`, with REST API field names (`datasets/schemas/crossref.md`). No `abstract` is requested. This is NOT the one-work-per-line shape that the Phase 4 Crossref normalizer reads. |
| `dblp/` (release excerpt, the only dblp form) | three exact byte ranges of the decompressed `dblp-2026-10-03.xml.gz` (rule below) | One record under a `<dblp>` root, with the release's own 96-byte header. Observed in DS-P5-09: `<?xml version="1.0" encoding="ISO-8859-1"?>`, then `<!DOCTYPE dblp SYSTEM "dblp-2023-06-28.dtd">`, then `<dblp>`, one per line. The bytes are ISO-8859-1 as declared. The record may end directly before `</dblp>` with no newline between them (see Record boundaries). |

## dblp release excerpts (work order DS-P5-03)

**Offline only.** No script requests a dblp per-record URL. `https://dblp.org/robots.txt` (read
2026-11-05) disallows `/rec/xml`, `/*.xml` and `/*.json`. Every dblp record file is cut from the pinned
release `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz`, which only the human downloads with
`SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full`.

**Gate before extraction.** Each check below fails closed for the whole dblp step: no dblp file is written
and no network request is made. The release is read **once**: every compressed byte that is decompressed
and scanned is hashed on the way, and the rest of the file is hashed after the scan. No excerpt is
written until both digests match, so the excerpts come from exactly the bytes that were checked (RV-P5-02
nit 11). A checksum mismatch is reported even when the layout also fails.
1. The manifest pin `datasets.dblp.snapshot.id` is `dblp-2026-10-03` (else `release-pin-mismatch`).
2. The release file exists (else `release-missing`).
3. Its `.sha256` sidecar exists (else `release-sidecar-missing`).
4. Its MD5 equals `datasets.dblp.integrity.upstream_checksum.value`, and its SHA-256 equals the sidecar
   (else `release-checksum-mismatch`).

**Construction rule.** Offsets and lengths count bytes of the decompressed stream. The release is read once,
line by line.

| Range | Offset + length | Bytes |
|---|---|---|
| header | `0 + H` | every line up to and including the first line whose stripped text is `<dblp>` (within the first 64 KiB) |
| record | `R + L` | from the `<` of the record start tag `<article\|inproceedings\|proceedings\|book\|incollection\|phdthesis\|mastersthesis\|www\|data ...>` that carries `key="<key>"`, through the `>` of its first end tag `</type>`, plus the one line terminator (`\n` or `\r\n`) directly after that end tag if there is one (DS-P5-09) |
| footer | `F + N` | the last non-blank line of the stream, which must be `</dblp>` |

**Record boundaries (DS-P5-09).** The 2026-11-13 extraction stopped at `conf/dac/0001AG12`. Its line, at decompressed offset 43784503, is
`</inproceedings><inproceedings mdate="2018-11-06" key="conf/dac/0001AG12">\x0a` (escaped; tag at offset 43784519).
Its end-tag line is `</inproceedings><inproceedings mdate="2018-11-06" key="conf/dac/OdawaraHIYD87">\x0a`. In the whole
release, 4942406 of 12998506 keyed start tags directly follow the previous record's end tag on the same line
(`datasets.dblp.format.record_layout_observed`). The start tag of a wanted record must be in one of these positions:
1. at the start of its line;
2. directly after exactly one record end tag (`</type2>`) on the same line, with nothing else before it on that line. The previous record ends on the same line;
3. directly after the end tag of the previous wanted record on the same line.

After its end tag, the line must end, or the next record's start tag must follow directly. No other record
start tag may occur between the start tag and the end tag. Record elements do not nest in dblp, and field
text cannot hold a literal `<`, so these tags are the record boundaries. Every offset is the exact byte
position in the decompressed stream, and the record bytes are copied unchanged. A record that ends directly
before the next record has no trailing newline in its range, so its excerpt file reads `...</type></dblp>`
followed by the footer's own line end. Each wanted key is searched wherever its start tag sits on a line. A key
whose start tag follows another record on the same line is therefore refused, not missed. Before DS-P5-09 such a
key would have been reported as `key-not-in-pinned-release`.

The excerpt file is `header + record + footer`, unmodified. It is then checked:
- it is well-formed XML (named entities mapped as in `parse_xml_file`, DTD not loaded);
- it holds exactly one record, with the requested key;
- it carries the selection facts `expect_doi` (in an `ee` element) and `expect_author`.

**Layout refusals** (`release-layout-error`, whole step refused):
- no `<dblp>` header line;
- a wanted key whose start tag is not at the start of a line and is not directly preceded by exactly one record
  end tag. Examples: preceded by field text, by white space, by two end tags, or by a whole other record on the
  same line. The message shows the escaped bytes before the tag (at most 120);
- a wanted key that occurs twice;
- a wanted record that reaches another record start tag (on the same line or a later one), or the end of the
  stream, before its end tag;
- anything after a wanted record's end tag on its line other than the line end or the next record's start tag,
  including `</dblp>` on the same line. The message shows the escaped bytes (at most 80);
- a last non-blank line that is not `</dblp>`;
- a stream that cannot be decompressed.

**Read-only inspection.** `fetch-samples.sh --dry-run datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz`
runs the same one-pass MD5 and SHA-256 gate. It prints nothing about the content unless both digests match. It then prints:
- the escaped header and last lines;
- how record start and end tags sit on their lines across the whole release;
- escaped byte windows around the first 3 wanted records whose start tag is not at the start of a line;
- the scanner's verdict for every selected dblp key, including the in-memory excerpt content check.

It writes nothing and makes no network request. Bytes are escaped as printable ASCII, with every other byte
and the backslash as `\xNN`.

**Per-record refusals:**
- `key-not-in-pinned-release`: the key is not in the release. It is never substituted from another source, and the pin is not changed;
- `record-exceeds-cap`: the record range is larger than 200,000 bytes;
- `byte-budget-reached`: writing the excerpt would take the ER sample (the bytes already in `PROVENANCE.tsv`
  plus the bytes written in this run) over the one 8 MiB budget. Each excerpt is counted before it is
  written, and nothing is written for a refused one (RV-P5-02 finding 3);
- `excerpt-check-failed`;
- `excerpt-write-failed`: the bytes written to `<file>.part` do not hash to the excerpt. The `.part` is removed and no row is written;
- `excerpt-state-inconsistent`: a `PROVENANCE.tsv` row exists, but its range row is missing or disagrees with it or with the file. The record is not counted as present, and the message names the manual repair.

All refusals are rows of `FETCH-FAILURES.tsv`.

**Write order: all-or-nothing from the next run's point of view** (DS-P5-04, RV-P5-02 finding 4).
1. Write the bytes to `<file>.part`, fsync it, and re-hash it. On a mismatch, remove the `.part` and fail the record with `excerpt-write-failed`.
2. `os.replace` the `.part` to `<file>`.
3. Write the range row through an atomic rewrite of `DBLP-RELEASE-RANGES.tsv` (temporary file, then rename). This replaces any uncommitted row for the same file.
4. Append the `PROVENANCE.tsv` row last. This append is the commit point.

`--sample` counts a dblp record as present only when its `PROVENANCE.tsv` row, its range row and the file all agree.
- **No `PROVENANCE.tsv` row:** any leftover file, `.part` or range row comes from an interrupted write. It is reported as `RECOVER` and replaced by re-extraction, which gives the same bytes from the pinned release.
- **A line torn by an interrupted append** (no trailing newline, and fewer tab-separated columns than a row): it is dropped with a `REPAIRED` message before the file is read.
- **A last line without a trailing newline that has a full row's columns or more**: it may be a complete row (for example after a hand edit), or a row cut inside its last column by an interrupted write; the bytes cannot tell the two apart. It is never dropped. `--sample` stops with exit 2 before any fetch or extraction and leaves the file unchanged. The human checks every column, including the last (in `PROVENANCE.tsv` the free-text `note`, which `--verify` cannot check), and adds the newline only if the row is complete; otherwise the human deletes the line, so the record is fetched or extracted again (RV-P5-03 N1, RV-P5-07 NIT-4).
- **File names:** a dblp key that contains `__` is refused by the selection parser, because `a/b__c` and `a__b/c` would share a file name. Two planned records that map to one file (compared case-insensitively) are refused as well (RV-P5-02 nit 10).
- Messages written to `FETCH-FAILURES.tsv` name repository-relative paths only, including the release-level reasons `release-missing`, `release-sidecar-missing` and `release-checksum-mismatch` (RV-P5-03 N2, RV-P5-07 NIT-3).
- **`--verify`** fails on every inconsistent state.

**Byte-range ledger (`DBLP-RELEASE-RANGES.tsv`).** One row per excerpt file, with columns:
- `file`, `record_key`;
- `release_id`, `release_file`, `release_md5`, `release_gz_sha256`;
- `header_offset`, `header_length`, `record_offset`, `record_length`, `footer_offset`, `footer_length`;
- `excerpt_bytes`, `excerpt_sha256`.

The `PROVENANCE.tsv` row of the same file repeats these facts:
- `request_url` is `release:<file> sha256=<gz sha256> header=<H offset>+<H length> record=<R>+<L> footer=<F>+<N>`;
- `http_status` is `-`;
- `sha256` equals `excerpt_sha256`.

**Verification.**
- `fetch-samples.sh --verify datasets/er-candidates` (offline, no release needed) checks for every dblp file:
  - it has a range row;
  - its pin values match the manifest;
  - the ranges are ordered;
  - the lengths add up to the file size;
  - both SHA-256 values agree;
  - `request_url` matches the range row;
  - the file's three slices follow the rule.
- `fetch-samples.sh --verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz` repeats the release gate. It then compares the release bytes at every recorded offset with the stored file. It never exits 0 unless the release was actually read and passed the gate. If verification stops before the release check (for example, no split file yet), the run fails (RV-P5-02 finding 8).
- **Cross-split entity check (RV-P5-02 finding 6, RV-P5-07 MINOR-2).** Per split, these identifiers are collected and normalised (`openalex-author:A…`, `orcid:` plus the bare upper-case iD), and nothing else:
  - OpenAlex works: `authorships[].author.id` and `authorships[].author.orcid`;
  - OpenAlex authors: `id` and `orcid`;
  - Crossref works: `message.items[].author[].ORCID` (added by DS-P5-06);
  - dblp excerpts: the `orcid` attributes of author and editor elements.

  Not collected: institution IDs, Crossref affiliation and funder identifiers, dblp `pid` attributes (their form in the dump is unverified), and every name string, because a matching name is not identity. If any collected ID occurs in both dev and heldout, verification **fails** and lists the ID and its units. The remedy is a human decision on `selection.tsv`, never an edit of `split-assignment.tsv`. At the 2026-11-11 baseline the committed sample had 0 such IDs without the Crossref ORCIDs. On 2026-11-12, with them, it still had 0, and the number of distinct IDs checked stayed at 508: every Crossref-asserted ORCID iD in the sample was already collected from an OpenAlex record of the same split.

**Fixture.** `fetch-samples.sh --verify datasets/er-candidates/fixture` runs the same extraction code on `datasets/er-candidates/fixture/fixture-release.xml`, gzipped into a temporary directory. That file holds the four records of `datasets/samples/dblp/dblp-sample.xml` in the release layout. The run:
- exercises every refusal above;
- covers realistic release lines: `mdate`, `publtype` and `cdate` attributes, unwanted and prefix-key records between wanted ones, and the declarations ISO-8859-1, UTF-8 and US-ASCII (the real one is UNVERIFIED);
- runs `er_fetch` end to end on throw-away roots: FETCH-FAILURES rows, exit code, already-present skips, and crash recovery;
- tests the `fetch-dblp.sh --full` transfer function against a curl stub. It covers bounds, stall, HTTP 416 (with curl exit 22, and with curl exit 0), cannot resume, a shell-escaped `rm` step for a path with a quote, spaces and `;`, wrong MD5, a failing MD5 tool (`.part` kept), success with the transferred byte count, and a final file without a sidecar (re-checked, never blessed if wrong);
- **executes** `fetch-dblp.sh --full` itself on byte-for-byte copies of the three scripts in a throw-away repository root, with a `.invalid` URL. It checks exit 3 without or with a wrong confirmation, the curl exit code passed through, wrong MD5, success with the final log line, and a re-run with 0 bytes fetched (RV-P5-03 N6);
- covers the 8 MiB budget for dblp excerpts, the `__` key refusal, the cross-split entity check (a straddling ORCID fails), `--with-release` passing after extraction and failing when the release was never read, and a complete last row without a newline (not dropped);
- DS-P5-06 (RV-P5-07): refuses symbolic links on the `--full` write path (`datasets/downloads` itself, also through the executed `fetch-dblp.sh --full`; a directory component; the `.part` linked to an existing file; `.sha256.tmp`; `.sha256`; the final file) with exit 2 before curl is called, keeping the link and writing nothing through it; fails the cross-split check for a Crossref author ORCID in heldout that equals a dev OpenAlex authorship ORCID, with a passing same-name control; checks that release-level FETCH-FAILURES reasons carry no absolute path, that curl gets `-q` first, and that HTTP 416 with a failing MD5 tool claims no MD5 mismatch;
- DS-P5-09 (release-layout-error of 2026-11-13):
  - **shared-lines positive case.** Every newline between a record end tag and the next record start tag is removed, so each wanted start tag sits directly after an end tag on the same line. All 4 records are extracted. The offsets equal an independent search, and the record ranges end at the end tag (3 with no newline, the last with its newline). The offline check, the `--with-release` comparison and `er_fetch` end to end pass;
  - **read-only inspection.** It writes nothing and prints the escaped windows and the verdict;
  - **slice rule.** A record slice that is cut short fails it;
  - **fail-closed cases** (`release-layout-error`, nothing written): field text, white space, two end tags, or a whole one-line record before a wanted start tag; a start tag inside an open wanted record; text between an end tag and the next start tag; `</dblp>` on the last record's end line;
- writes nothing to the repository;
- traps any network call.

The OpenAlex file for a DOI may hold the same OpenAlex work (same `W` ID) as the file for another DOI,
when OpenAlex has merged a preprint with its version of record or a conference paper with its journal
version. Both files are kept: that merge is itself evidence. `PROVENANCE.tsv` notes when the `doi` field
of the body differs from the requested DOI.

## Known source-side misses (not dblp extraction)

A source-side miss is a network lookup that the source answered, but whose body is not the requested record.
It is recorded in `FETCH-FAILURES.tsv` and is never substituted. It is separate from dblp release extraction.

| Unit (split) | Source | Identifier | Reason | Seen |
|---|---|---|---|---|
| `md-cinc-fotiadoukhv19` (dev, multi-doi) | Crossref works by DOI filter | `10.23919/cinc49843.2019.9005722` | `expected exactly one Crossref item` | DS-P5-01 `--sample`; again 2026-11-13T04:55:53Z (evidence: `docs/agent-runs/phase-05/extraction-failure-01.md`, section B) |

- **What is known.** The response passed the HTTP and JSON checks, but `message.items` did not hold exactly one work. A rejected body is never stored, so zero items and two or more items cannot be told apart (UNVERIFIED in the manifest).
- **Not related to the dblp failure** of the same run: dblp records are never requested over the network.
- **The OpenAlex work for the same DOI** is stored as its own selection row (`dev/openalex-works/10.23919_cinc49843.2019.9005722-1a6002da.json`). It is not a replacement for the Crossref record.
- **What a retry would change.** DS-P5-09 did not retry it. The record has no `PROVENANCE.tsv` row, so every later `--sample` run requests the same URL once more. That run stores the response only if Crossref then returns exactly one item with this DOI; otherwise it writes the same failure row again.
- **What a retry would not change.** The dblp extraction, `selection.tsv`, `split-assignment.tsv`, the unit's split, or any other record. No other source, DOI spelling or query replaces it.

## Provenance columns (`PROVENANCE.tsv`)

`file`, `unit_id`, `split`, `criterion`, `source`, `kind` (`rec`, `work-by-doi`, `author`), `record_id`
(dblp key, OpenAlex W or A ID, Crossref DOI), `request_url` (exact URL, or `release:<file> sha256=... header=...+... record=...+... footer=...+...` for dblp),
`retrieved_at_utc`, `http_status`, `bytes`, `sha256`, `license`, `note`.

## Personal data

The files contain public bibliographic personal data: author names as published, OpenAlex, Crossref and dblp
ORCID assertions, OpenAlex author IDs, institution IDs, and raw affiliation strings. No ORCID record
is fetched. OpenAlex author records are limited to `id, orcid, display_name, display_name_alternatives,
last_known_institutions, ids`. No email address, postal address, biography, or employment history is
requested.
