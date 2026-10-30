# ScholarChain dataset samples: attribution, licence, and provenance

Owner: `dataset-engineer`. Assembled 2026-10-29. Checksums: `datasets/samples/SHA256SUMS`.

These files are small **field-level excerpts** of real upstream records. They are not byte copies of the upstream dump files. The source APIs of DBLP, OpenAlex, Crossref, and ORCID were read on 2026-10-29 with a read-only fetch tool. The tool returns extracted values rather than raw bytes, so each value was transcribed into the upstream record vocabulary, and only fields listed in `datasets/schemas/*.md` were kept. Values were not edited, normalized, or corrected. Fields that were not retrieved are omitted rather than guessed. Several values were cross-checked with a second, independent request (noted below).

Byte-exact upstream copies of the same seed records can be fetched with `datasets/scripts/fetch-samples.sh --sample` (network, bounded, writes to the git-ignored `datasets/downloads/<dataset>/sample/`). See `datasets/dataset-manifest.yaml`.

## Overlap map (linkage test fixtures)

| Work | DBLP key | DOI | OpenAlex | Crossref | ORCID |
|---|---|---|---|---|---|
| Spanner (2013) | `journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13` | `10.1145/2491245` | `W3137759927` | yes | no ORCID record claims this DOI (search API, num-found 0) |
| Cyber security research in the Arab region (2021) | `journals/cacm/PopperMP21` | `10.1145/3447741` | `W3147033813` | yes | `0000-0002-2814-962X`, `0000-0001-6899-0651` (also claimed by `0000-0003-1909-0336`, not sampled) |
| Software development in disruptive times (2021) | `journals/cacm/Varajao21` | `10.1145/3453932` | `W3200477342` | yes | `0000-0002-4303-3908` (claim confirmed by search API; work entry not excerpted) |
| Bigtable (2006) | `conf/osdi/ChangDGHWBCFG06` | none in DBLP | not sampled | not applicable | not sampled |

## Edge cases deliberately covered

- **Large author team:** Spanner has 26 authors in every source (`n(n-1)/2 = 325` coauthor pairs).
- **Single author:** Varajao21 has zero coauthor pairs.
- **Unicode:** `Pöpper`, `João Varajão`, `Saitō` (OpenAlex display name) versus `Saito` (raw name). DBLP writes them as DTD entities (`&ouml;`, `&atilde;`), and ORCID writes the family name as `Poepper`.
- **Homonym disambiguation suffix:** DBLP names `Christopher Frost 0001` and `Sergey Melnik 0001`.
- **Repeated collaboration:** Bigtable and Spanner share four DBLP person IDs (Jeffrey Dean `d/JeffreyDean`, Sanjay Ghemawat `g/SGhemawat`, Wilson C. Hsieh `31/1764`, Andrew Fikes `71/5497`).
- **Missing fields:** Bigtable has no DOI. Three Spanner authorships in OpenAlex have `author.id = null`. The Crossref records carry no ORCID. The Varajão ORCID excerpt has an empty works list.
- **Title disagreement:** DBLP has the full title with a trailing period, OpenAlex has only the main title (`Spanner`), Crossref splits `title` and `subtitle`, and DBLP appends `(Awarded Best Paper!)` to Bigtable.
- **Page disagreement:** DBLP `pages` = `8` (article number) versus Crossref `1-22` for Spanner.
- **Identity conflict risk:** OpenAlex attaches ORCID iDs to 6 of 26 Spanner authors, but DBLP has no ORCID on those signatures and no ORCID record claims the DOI. These are OpenAlex assertions, not verified identity evidence. A matching author name is never evidence of identity.
- **Duplicate claims:** ORCID `0000-0001-6899-0651` has two work-summaries for one DOI from two sources (NYU Scholars, Scopus).
- **Name variants:** OpenAlex `display_name_alternatives`, ORCID other-name `Mihalis Maniatakos`, and OpenAlex `Wilson Cheng-yi Hsieh` versus DBLP `Wilson C. Hsieh`.

## Per-file provenance and licence

| File | Source and request (2026-10-29) | Licence / reuse terms (verified source) | Notes |
|---|---|---|---|
| `dblp/dblp-sample.xml` | dblp SPARQL endpoint `https://sparql.dblp.org/sparql` (record facts and signatures with ordinals). The key initials were checked against the author order. | CC0 1.0, secondary ODC-BY 1.0. Copyright Schloss Dagstuhl. Source: `https://dblp.org/xml/README.txt` ("dblp - Legal Information") and the release page `https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-10-03` | `mdate` and `url` omitted (not retrieved). `orcid` attribute written as a bare iD (form UNVERIFIED). Parsing requires `dblp.dtd` for the named entities. |
| `openalex/works-sample.jsonl` | `https://api.openalex.org/works/doi:<doi>`. Spanner authorships cross-checked with a second request (`/works/W3137759927?select=id,authorships`); all 26 rows agreed. | CC0 1.0. Source: `https://openalex.s3.amazonaws.com/LICENSE.txt` (CC0 1.0 Universal text) and `https://openalex.org/about` ("Our data is CC0") | Field subset. Volatile counts excluded. Topic ids of subfield, field, and domain not retrieved. |
| `openalex/authors-sample.jsonl` | `https://api.openalex.org/authors/A5113870699` | CC0 1.0 (as above) | One alternative name containing a backslash escape was dropped because its exact string could not be confirmed. |
| `openalex/institutions-sample.jsonl` | `https://api.openalex.org/institutions/I120250893` | CC0 1.0 (as above) | |
| `crossref/works-sample.jsonl` | `https://api.crossref.org/works/<doi>` | Crossref: "almost none of the metadata is subject to copyright, and you may use it for any purpose" (`https://www.crossref.org/documentation/retrieve-metadata/rest-api/`). **Abstracts may be copyrighted by publishers or authors**, so `abstract` is excluded from all samples. | `license` (article licence links), `reference`, `link`, and `abstract` excluded. |
| `orcid/0000-0002-2814-962X.xml` | `https://pub.orcid.org/v3.0/0000-0002-2814-962X/personal-details` and `/works` | CC0 1.0. Attribution requested: "Please give attribution to ORCID as the source of the Public Data File and link back to this page" (`https://info.orcid.org/public-data-file-use-policy/`). Policy also asks users to respect the privacy and publicity rights of the individuals. | Minimised: iD, public name, and one overlapping work group only. |
| `orcid/0000-0001-6899-0651.xml` | as above for `0000-0001-6899-0651` | as above | Keeps one public other-name as a name-variant fixture. |
| `orcid/0000-0002-4303-3908.xml` | `https://pub.orcid.org/v3.0/0000-0002-4303-3908/personal-details`, plus search API `q=doi-self:"10.1145/3453932"` (num-found 1) | as above | Empty works list. See the file comment. |

Attribution text for redistribution: "Contains data from dblp (Schloss Dagstuhl, CC0 1.0), OpenAlex (CC0 1.0, https://openalex.org), Crossref metadata (Crossref REST API), and the ORCID Public Data File / ORCID public API (CC0 1.0); source: ORCID, https://info.orcid.org/public-data-file-use-policy/."
