# DBLP XML: raw schema note

- Schema note version: `dblp-raw/1.0.0` (ScholarChain). Owner: `dataset-engineer`.
- Upstream schema: `https://dblp.org/xml/dblp.dtd`. The latest DTD change is "DTD changes of June 28, 2023" (`https://dblp.org/xml/CHANGES.txt`). The DTD file is dated 2023-06-28 in the `https://dblp.org/xml/` listing.
- Snapshot pinned: dated monthly release `dblp-2026-10-03.xml.gz` (DOI `10.4230/dblp.xml.2026-10-03`).
- Sample: `datasets/samples/dblp/dblp-sample.xml` (reconstructed excerpt, see `datasets/samples/ATTRIBUTION.md`).

## File structure

- One gzip-compressed XML document. The root element is `dblp`, and its children are records: `article | inproceedings | proceedings | book | incollection | phdthesis | mastersthesis | www | person | data` (DTD root declaration).
- Non-ASCII characters are written as **named character entities declared in `dblp.dtd`** (for example, `ouml` is `&#246;` and `atilde` is `&#227;`). A parser must load the DTD or supply an equivalent entity table, or it fails on `&ouml;`.
- The XML declaration's encoding of the full dump is **UNVERIFIED** (it was not read in this run). The sample is pure ASCII.
- Only publication records (`article`, `inproceedings`, `proceedings`, `book`, `incollection`, `phdthesis`, `mastersthesis`) are in scope for WorkRecord. `www` (person and home-page records), `person`, and `data` records must be routed to a separate output or skipped and counted. They must never be treated as works. The sample contains no `www` record (gap U-DBLP-3).

## Fields used

| Element / attribute | Type | Cardinality | Nullability | Notes |
|---|---|---|---|---|
| record element name | enum (above) | 1 | required | `publtype` attribute (NMTOKENS, optional) marks informal or withdrawn items |
| `@key` | string | 1 | required (`#REQUIRED`) | DBLP record key, the primary source record ID |
| `@mdate` | date `YYYY-MM-DD` | 0..1 | `#IMPLIED` | last modification. Absent from the sample (not retrieved) |
| `@cdate` | date | 0..1 | `#IMPLIED` | |
| `author` | string | 0..n, **ordered** | optional | Author sequence is significant. Text may carry a 4-digit homonym suffix (`Christopher Frost 0001`) |
| `author/@orcid` | ORCID iD | 0..1 per author | `#IMPLIED` | Value form in the XML dump is **UNVERIFIED** (bare iD assumed). The dblp RDF exposes `https://orcid.org/<iD>`. Parsers must accept both |
| `editor` | string | 0..n, ordered | optional | Editors are not authors. How they are treated is decided by `hadoop-engineer` and `entity-resolution-engineer` |
| `title` | mixed text | 1 | required in practice | Usually ends with `.`. May contain inline markup elements (see the DTD; the exact element list was not checked in this run) and editorial notes such as `(Awarded Best Paper!)` |
| `year` | int | 0..1 | optional | |
| `journal` | string | 0..1 | articles | abbreviated name, for example `Commun. ACM` |
| `booktitle` | string | 0..1 | inproceedings | abbreviated, for example `OSDI` |
| `volume`, `number`, `pages` | string | 0..1 | optional | `pages` may be an article number (`8`) rather than a range |
| `ee` | URL | 0..n | optional | electronic edition. A DOI appears as `https://doi.org/<doi>`. Non-DOI publisher URLs also occur |
| `crossref` | DBLP key | 0..1 | optional | parent proceedings key, for example `conf/osdi/2006` |
| `url` | string | 0..1 | optional | dblp TOC-relative URL. Absent from the sample (not retrieved) |

## Identifiers

- **DBLP record key:** slash-separated path such as `journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13` or `conf/osdi/ChangDGHWBCFG06`. The observed pattern is `<type-stream>/<venue>/<local>`, for example `journals/cacm/PopperMP21`. No official grammar was found, so treat it as an opaque string and never parse meaning from it. Resolvable as `https://dblp.org/rec/<key>`.
- **DBLP person ID (pid):** observed forms `11/3019`, `69/6042-1`, `d/JeffreyDean`, `g/SGhemawat`. URI form `https://dblp.org/pid/<pid>`. It is available from dblp RDF and SPARQL. How pids appear in the XML dump (through `www` records) is **UNVERIFIED**.
- **DOI:** extracted from `ee` values with the prefix `https://doi.org/` (older records may use `http://dx.doi.org/`, as dblp RDF `owl:sameAs` shows).
- **OMID (OpenCitations Meta):** present in dblp RDF (`dblp:omid`, for example `https://w3id.org/oc/meta/br/061903526968`). Whether it is present in the XML dump is UNVERIFIED. Not used.

## Linkage fields

`dblp_key`, `doi` (from `ee`), `author_orcid` (from `author/@orcid`), `normalized_title`, `normalized_author_sequence`, `year`, `venue` (`journal` or `booktitle`), `volume`, `number`, `pages`.

## Privacy

Author names and (where present) ORCID iDs are personal data that dblp publishes openly. ScholarChain keeps names and ORCID iDs only as needed for linkage. `www` records may carry affiliation notes. Those are not extracted in Phase 4 unless a later work order requires them.
