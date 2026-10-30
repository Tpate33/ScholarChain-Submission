# ORCID public data file (summaries XML): raw schema note

- Schema note version: `orcid-raw/1.0.0` (ScholarChain). Owner: `dataset-engineer`.
- Upstream documentation: `https://info.orcid.org/documentation/integration-guide/working-with-bulk-data/` and `https://info.orcid.org/public-data-file-use-policy/`. Release record: `https://doi.org/10.23640/07243.30375589.v1` (figshare article 30375589, "ORCID Public Data File 2025", published 2025-10-20. Per its description, it is a snapshot of all public data for records created or claimed as of October 1, 2025).
- Samples: `datasets/samples/orcid/<iD>.xml` (minimised excerpts written from the ORCID public API v3.0).

## File structure

- Since 2018 the file is XML only. It contains `ORCID_2025_10_summaries.tar.gz` (one XML file per public record, in folders grouped by "the last three digits" of the iD, as the ORCID page words it) and eleven `ORCID_2025_10_activities_{0..9,X}.tar.gz` archives with per-section activity XML. ScholarChain uses **summaries only**.
- The record root element is `record:record` (namespace `http://www.orcid.org/ns/record`). The summary layout (works summaries inside `activities:activities-summary`) follows the ORCID v3.0 record schema. Whether the 2025 summaries files contain exactly this layout is **UNVERIFIED until download**.

## Namespaces used

`record` `http://www.orcid.org/ns/record`, `common` `http://www.orcid.org/ns/common`, `person` `http://www.orcid.org/ns/person`, `personal-details` `http://www.orcid.org/ns/personal-details`, `other-name` `http://www.orcid.org/ns/other-name`, `activities` `http://www.orcid.org/ns/activities`, `work` `http://www.orcid.org/ns/work` (all observed in the public API XML on 2026-10-29).

## Fields used

| Path | Type | Nullability | Notes |
|---|---|---|---|
| `common:orcid-identifier/common:path` | ORCID iD | required | source record ID |
| `person:person/person:name/personal-details:given-names` | string | nullable (name may be private) | |
| `person:person/person:name/personal-details:family-name` | string | nullable | May be transliterated (`Poepper` versus DBLP `Pöpper`) |
| `person:person/other-name:other-names/other-name:other-name/other-name:content` | string | 0..n | name variants |
| `activities:works/activities:group` | element | 0..n | One group per set of matching external IDs |
| `activities:group/common:external-ids/common:external-id` | `type`, `value`, `normalized`, `relationship` | 0..n | Types observed: `doi`, `eid` (Scopus), `source-work-id`, `issn` (`part-of`) |
| `work:work-summary/@put-code` | int | required | ORCID-local work ID |
| `work:work-summary/common:source` | element | required | `source-client-id` or `source-orcid`, `source-name`, and optional `assertion-origin-*`. Must be preserved for provenance. How the source is weighted as evidence is decided by `entity-resolution-engineer` |
| `work:work-summary/work:title/common:title` | string | required | |
| `work:work-summary/work:type` | string | required | e.g. `journal-article` |
| `work:work-summary/common:publication-date/common:year,month,day` | strings | each optional | |
| `work:work-summary/work:journal-title` | string | optional | |

**Deliberately not used:** `email:*`, `address:*`, `keyword:*`, `researcher-url:*`, `external-identifier:*` (person-level), and the biography. The affiliation sections (`employment`, `education`, `distinction`, `invited-position`, `membership`, `qualification`, `service`) and the `funding`, `peer-review`, and `research-resource` sections are also not used unless a later, human-approved work order requires them (privacy minimisation, law 5). `datasets/scripts/dsutil.py` (`orcid_banned_tags`) fails validation if a committed ORCID sample contains an element in any of these namespaces or with any of these section names. Work-level `common:external-ids` are used and are not affected.

## Linkage fields

`orcid_id`, `work_doi` (from `external-id-type = doi`, `relationship = self`, normalized value, lowercase), `given_names`, `family_name`, `other_names`, `work_title`, `publication_year`.

## Identifier format

ORCID iD: `NNNN-NNNN-NNNN-NNNX`. Sixteen characters in four hyphen-separated groups. The last character is a digit or `X` (a check character). URI form `https://orcid.org/<iD>`. The check-character algorithm (ISO 7064 MOD 11-2 per ORCID documentation) was **not re-verified in this run** (no ORCID support page was fetched). Validation code should cite the ORCID documentation when it is implemented.

## Privacy

ORCID records are personal data published by the individual. The ORCID use policy asks reusers to "Respect the rights of privacy and publicity of the individuals featured in the Public Data File" and to give attribution. ScholarChain keeps the iD, public names, and DOI-bearing work summaries only. It applies the annual refresh so that records made private or deleted upstream disappear at the next snapshot.
