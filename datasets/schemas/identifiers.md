# Source identifier formats (input to `domain-engineer` for SourceType and ID namespaces)

- Schema note version: `identifiers/1.0.0`. Owner: `dataset-engineer`.
- "Observed" means that the form was seen in an official source response on 2026-10-29 (see `datasets/samples/ATTRIBUTION.md`). Patterns are descriptive and drawn from observed values. They are **not** official grammars unless an official grammar is cited. Stable ID functions and validation regexes belong to `domain-engineer`.

| Source type | Identifier | Observed raw forms | Canonical form proposed for comparison | Examples (real, from samples) |
|---|---|---|---|---|
| DBLP | record key | `journals/tocs/<local>`, `conf/osdi/<local>` (slash-separated path) | as-is (case-sensitive opaque string) | `journals/cacm/PopperMP21`, `conf/osdi/ChangDGHWBCFG06` |
| DBLP | person pid | `11/3019`, `69/6042-1`, `d/JeffreyDean` (RDF URI `https://dblp.org/pid/<pid>`) | pid string without URI prefix | `11/3019` |
| DBLP | disambiguated author name | `<name> NNNN` (4-digit suffix) | keep the full string. The suffix is a DBLP-internal disambiguation | `Christopher Frost 0001` |
| all | DOI | DBLP `ee` `https://doi.org/<doi>`. OpenAlex `https://doi.org/<doi>`. Crossref `DOI` bare. ORCID `external-id-value` bare | bare `10.<registrant>/<suffix>`, lowercased (DOIs are case-insensitive) | `10.1145/2491245` |
| OpenAlex | work, author, institution, source, topic ID | `https://openalex.org/W<digits>`, `A<digits>`, `I<digits>`, `S<digits>`, `T<digits>` | the short ID (letter plus digits) | `W3137759927`, `A5113870699`, `I120250893`, `S103482838`, `T10772` |
| OpenAlex | MAG ID | `ids.mag` digit string | digit string (legacy, informational only) | `3137759927` |
| ORCID | ORCID iD | bare `0000-0002-2814-962X` (ORCID XML, DBLP XML assumed) or URI `https://orcid.org/0000-0002-2814-962X` (OpenAlex, dblp RDF) | bare 19-character form `NNNN-NNNN-NNNN-NNN[0-9X]` | `0000-0002-4303-3908` |
| ORCID | work put-code | integer attribute | `<iD>/work/<put-code>` | `0000-0001-6899-0651/work/107628996` |
| ROR | institution | `https://ror.org/<9 chars>` | the 9-character suffix | `00e5k0821` |
| Crossref | member ID | string of digits | string | `320` |
| ISSN | serial | `NNNN-NNNX`, and ISSN-L in OpenAlex | as-is | `0001-0782` |
| Scopus | EID (appears inside ORCID) | `2-s2.0-<digits>` | as-is (informational, no Scopus dataset) | `2-s2.0-85103197452` |
| OpenCitations | OMID (appears in dblp RDF only) | `https://w3id.org/oc/meta/br/<digits>` | not used | `https://w3id.org/oc/meta/br/061903526968` |

## Linkage keys (for entity resolution; policy and thresholds are not set here)

Exact-identifier keys, in the order of the spec's evidence hierarchy:

1. `doi` (all four sources)
2. `orcid_id` (DBLP `author/@orcid`, OpenAlex `authorships[].author.orcid`, Crossref `author[].ORCID`, ORCID record)
3. `openalex_id` (OpenAlex only. No other selected source carries it)
4. `dblp_key` and `dblp_pid` (DBLP only)

Bibliographic keys: `normalized_title`, `publication_year`, `normalized_author_sequence`, `venue` (`journal` or `booktitle`, OpenAlex `source`, Crossref `container-title`, ISSN), `volume`, `issue`, `pages`.

Context keys: `institution_ror` (OpenAlex), `affiliation_string` (Crossref), `orcid_other_names`, `openalex_display_name_alternatives`.

**A matching author name is never evidence of identity.** A source's ORCID assertion is evidence with provenance (who asserted it), not ground truth. In the samples, OpenAlex attaches ORCID iDs to 6 Spanner authors that neither DBLP nor any ORCID record corroborates.
