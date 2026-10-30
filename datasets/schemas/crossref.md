# Crossref public data file (JSON Lines): raw schema note

- Schema note version: `crossref-raw/1.0.0` (ScholarChain). Owner: `dataset-engineer`.
- Upstream documentation: `https://www.crossref.org/services/metadata-retrieval/public-data-file/`, `https://www.crossref.org/documentation/retrieve-metadata/bulk-downloads/`, `https://www.crossref.org/documentation/retrieve-metadata/rest-api/`.
- Snapshot pinned: "March 2026 Public Data File from Crossref" (blog post dated 2026-03-17. It covers records registered "up to and including part of March 2026").
- Sample: `datasets/samples/crossref/works-sample.jsonl` (field-subset excerpts from `https://api.crossref.org/works/<doi>`).

## File structure

- The Academic Torrents distribution (infohash `b5ee0e102689b3e67023dd024694c0f5f124646f`) lists 35,909 files named like `142.jsonl.gz`. The bulk-download page also describes a requester-pays S3 object in bucket `api-snapshots-reqpays-crossref`. The documented example key is for 2025 (`March-2025-public-data-file-from-crossref.tar`). **The 2026 object key is UNVERIFIED.**
- Each line is assumed to be one work object with the same field names as the REST API `message` object. This line structure is **UNVERIFIED until the first file is downloaded**. The `--verify` step of `fetch-crossref.sh` must check it on the first downloaded part.

## Fields used

| Field | Type | Nullability | Notes |
|---|---|---|---|
| `DOI` | string `10.<prefix>/<suffix>` | required | Bare DOI, without a URL prefix. Case-insensitive |
| `type` | string | required | e.g. `journal-article` |
| `title[]` | array of strings | may be empty | Main title only. The subtitle is separate |
| `subtitle[]` | array of strings | may be empty | |
| `container-title[]`, `short-container-title[]` | array of strings | may be empty | venue |
| `ISSN[]`, `issn-type[]` | strings and `{type, value}` objects | optional | |
| `volume`, `issue`, `page` | string | optional | |
| `published-print`, `published-online`, `issued` | `{date-parts: [[Y, M?, D?]]}` | optional | Partial dates are common. `issued` is the earliest |
| `publisher`, `member`, `prefix`, `source` | string | required | `member` is a string ID (`320`) |
| `author[]` | array, **ordered** | optional | `given` (optional), `family`, `sequence` (`first`, `additional`), `ORCID` (optional URL), `authenticated-orcid` (optional bool), `affiliation[].name` |
| `URL` | string | required | `https://doi.org/<DOI>` |
| `indexed.date-time`, `deposited.date-time` | ISO timestamp | required | Used for incremental updates |
| `is-referenced-by-count`, `references-count` | int | volatile | Not used for analytics |

Excluded: `abstract` (**may be copyrighted by publishers or authors**, per the Crossref REST API page), `reference[]`, `link[]`, `license[]` (article licence pointers, not metadata licences), and `funder[]` (possible later enrichment).

## Linkage fields

`doi` (lowercase), `normalized_title` (title plus subtitle), `author_sequence` (`family`, `given`), `author_orcid` (when supplied), `issued_year`, `container_title`, `ISSN`.

## Privacy

Author names, optional ORCID iDs, and free-text affiliation strings. Keep only the fields above. Affiliation strings are kept verbatim for evidence but are not resolved to people.
