# OpenAlex snapshot (JSON Lines): raw schema note

- Schema note version: `openalex-raw/1.0.0` (ScholarChain). Owner: `dataset-engineer`.
- Upstream documentation: `https://help.openalex.org/access/snapshot/` and `https://help.openalex.org/tutorials/download-the-snapshot/`.
- Snapshot pinned: release `2026-10-14` (`RELEASE_NOTES.txt` "RELEASE 2026-10-14"; the `date` field of `s3://openalex/data/jsonl/manifest.json` is `2026-10-14`).
- Samples: `datasets/samples/openalex/{works,authors,institutions}-sample.jsonl` (field-subset excerpts from the OpenAlex API, which serves the same entity objects).

## File structure

- `s3://openalex/data/jsonl/<entity>/updated_date=YYYY-MM-DD/part_NNNN.gz`. Each part is gzip-compressed JSON Lines with up to 400,000 records (help page). A partition holds the records last changed on that date.
- `s3://openalex/data/jsonl/<entity>/manifest.json` lists `files[]` with `url` and `meta.{content_length, record_count}`, plus top-level `date`, `format`, `entity`, `record_count`, and `content_length`. The combined `data/jsonl/manifest.json` nests entities. Per the help page, the manifest is written last, so its presence marks a complete format.
- `works/deleted_ids.csv.gz` lists deleted work IDs (help page). It must be applied on incremental updates.
- A Parquet copy exists under `data/parquet/`. ScholarChain uses JSONL only.
- **No checksums or ETags are documented** for parts. Integrity uses the manifest `content_length` and `record_count` per part, plus our own SHA-256 recorded at copy time.

## Entities used and fields

### works (`W` IDs)

| Field | Type | Nullability | Notes |
|---|---|---|---|
| `id` | URL string `https://openalex.org/W<digits>` | required | source record ID |
| `doi` | URL string `https://doi.org/<doi>` | nullable | |
| `title`, `display_name` | string | nullable | May be truncated relative to the publisher title (sample: `Spanner`) |
| `publication_year` | int | nullable | |
| `publication_date` | `YYYY-MM-DD` string | nullable | |
| `type` | string | required | e.g. `article` |
| `language` | string | nullable | |
| `ids` | object | required | keys observed: `openalex`, `doi`, `mag` (MAG ID as a digit string) |
| `primary_location.source` | object | nullable | `id` (`S<digits>`), `display_name`, `issn_l`, `type` |
| `biblio` | object | fields nullable | `volume`, `issue`, `first_page`, `last_page` as **strings** |
| `primary_topic` | object | nullable | `id` (`T<digits>`), `display_name`, and nested `subfield`, `field`, `domain` |
| `authorships[]` | array, **ordered** | may be empty | `author_position` (`first`, `middle`, `last`), `author.{id, display_name, orcid}`, `institutions[]`, `countries[]`, `is_corresponding`, `raw_author_name` |
| `authorships[].author.id` | URL `https://openalex.org/A<digits>` | **nullable** (3 of 26 Spanner authorships are null) | |
| `authorships[].author.orcid` | URL `https://orcid.org/<iD>` | nullable | An OpenAlex assertion. It may be inferred and is **not** proof of identity |
| `authorships[].institutions[]` | object | may be empty | `id` (`I<digits>`), `display_name`, `ror` (URL), `country_code`, `type` |
| `updated_date`, `created_date` | timestamp strings | required | |

Not used: abstracts (`abstract_inverted_index`), citation lists, counts. They are volatile or out of scope.

### authors (`A` IDs)

`id`, `orcid` (nullable), `display_name`, `display_name_alternatives[]` (name variants, may contain BibTeX-style escapes), `ids`, `last_known_institutions[]`, `works_count`, `cited_by_count`, `updated_date`, `created_date`.

### institutions (`I` IDs)

`id`, `ror`, `display_name`, `country_code`, `type`, `ids` (`openalex`, `ror`, `grid`, `wikipedia`, `wikidata`, which is nullable), `geo.{city, country}`, `updated_date`, `created_date`.

### Possible later entities (not sampled)

`sources` (`S`), `topics` (`T`). Their fields will be noted when a work order selects them.

## Linkage fields

`doi` (strip `https://doi.org/`, lowercase), `openalex_work_id`, `openalex_author_id`, `author_orcid`, `institution_ror`, `normalized_title`, `publication_year`, `author_sequence`, `source_issn_l`.

## Privacy

Author names, ORCID iDs, and institution affiliations are personal or quasi-personal data. Keep only the fields above. Do not store email or other contact fields (none are used). Affiliations are kept at institution level (ID, ROR) for the institution filter required by the project.
