# Phase 5 (main) review reports

## RV-P5-02: code-reviewer, dataset fetch and extraction changes (DS-P5-01, DS-P5-03), verbatim

Orchestrator note: read-only review (Read, Grep, Glob only). It ran in parallel with ER-P5-05, which
writes only `modules/entity-resolution/**`. The reviewer did not author any reviewed work.

---

## RV-P5-02 review: dataset fetch and extraction changes (DS-P5-01, DS-P5-03)

**Verdict: APPROVE.** I found no blocker or major issue. Eight minor findings and four nits are listed below. All of them belong to dataset-engineer.

I read the current working-tree files. I could not diff them against HEAD because I have no Bash. So "unchanged since HEAD" claims, such as "`--full` is unchanged", are UNVERIFIED. Everything below describes the code as it stands now.

### 1. The download command, as verified from the code

Command: `SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full`

- **Mode parsing** (lib-datasets.sh:66-110): exactly one mode flag is accepted. The optional output directory defaults to `datasets/downloads/dblp`. Any other directory must be a relative path under `datasets/downloads/` with no `..`, `.` or empty segments.
- **Before the gate:** five manifest values are read locally with `python3 -I dsutil.py get` (fetch-dblp.sh:26-30). There is no network access before the gate.
- **Confirmation gate:** `ds_require_full_confirmation "$SNAP_ID"` (fetch-dblp.sh:74; lib-datasets.sh:274-278). It exits 3 unless the variable equals `datasets.dblp.snapshot.id`, which is `dblp-2026-10-03` (manifest:70).
- **URL and host:** `https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz` (manifest:45), host drops.dagstuhl.de.
- **https only:** curl runs with `--proto '=https' --proto-redir '=https' --location`. Redirects are followed only to https, but to any host.
- **Size cap:** `--max-filesize 2000000000`.
- **Resume:** `-C - -o "$dest.part"`.
  - If an existing `.part` already matches the pinned MD5, curl is skipped (fetch-dblp.sh:83-84).
  - If curl fails or is interrupted, `set -e` exits non-zero and the `.part` file is kept for the next resume.
- **Time bound: none.** There is no `--max-time`, `--connect-timeout` or `--speed-limit`. A stalled transfer runs until the human interrupts it.
- **Fetches nothing else:** the `--full` branch (fetch-dblp.sh:73-105) makes exactly one curl call. The DTD and the `.md5` file are not fetched.

### 2. Output paths

| Writer | Path | Git status |
|---|---|---|
| `--full` | `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part` (during the transfer) | ignored (`/datasets/downloads/`, .gitignore:35) |
| `--full` | `.../dblp-2026-10-03.xml.gz` and `.../dblp-2026-10-03.xml.gz.sha256` | ignored |
| ER `--sample` | `datasets/er-candidates/split-assignment.tsv` (only if absent; otherwise compared and the run refuses on any difference) | visible |
| ER `--sample` | `datasets/samples/er-candidates/{dev,heldout}/dblp/<key with / replaced by __>.xml` (written as `.part`, then `os.replace`) | visible |
| ER `--sample` | rows appended to `PROVENANCE.tsv` and `DBLP-RELEASE-RANGES.tsv`; `FETCH-FAILURES.tsv` rewritten | visible |
| `--fixture` | `tempfile.TemporaryDirectory` in TMPDIR, removed afterwards | outside the repository |

- `--full` writes nothing outside `datasets/downloads/`.
- No script under datasets/ refers to `.claude`, `.last-test-run` or `docs/agent-runs` (grep found nothing).

### 3. Checksum checks

- **MD5 against the pin `cbd329100ea1bbb2873fa2f6d414ea00`:**
  - `--full` checks it on `.part` before the rename (fetch-dblp.sh:90-94).
  - `fetch-dblp.sh --verify` checks it (line 54).
  - `er_dblp_open_release` checks it before any extraction (dsutil.py:1003) and again in `--with-release` (dsutil.py:1227).
  - The pin is read from the manifest. `er_dblp_pin` also requires the snapshot id to be `dblp-2026-10-03` and the value to be 32 lowercase hex characters (dsutil.py:964-982).
- **`gzip -t`:** `--full` runs it after the MD5 check (line 95) and `fetch-dblp.sh --verify` runs it (line 55). ER extraction does not run `gzip -t`. Instead, a decompression error during the scan becomes `release-layout-error` (dsutil.py:1058-1059).
- **sha256 sidecar:**
  - `--full` writes it only after the MD5 and gzip checks pass (line 100).
  - `--verify` requires it (`ds_is_current`, line 53).
  - ER extraction requires it to exist and to match (dsutil.py:992-1008).
- **Per-excerpt sha256:**
  - Computed in memory, then re-hashed after the write (dsutil.py:1128-1130).
  - Offline `--verify` checks the ranges row, the ledger row and the file all agree (dsutil.py:1209).
- **`--verify --with-release`:**
  - Re-runs the release gate.
  - Compares each row's recorded `.gz` sha256 with the local release.
  - Decompresses the release and compares the bytes at every recorded header, record and footer offset with the stored file (dsutil.py:1224-1268).

None of these checks can be skipped silently. The ER extraction gate has no override. The only path that does not re-check the MD5 is the `--full` early exit when the file already matches its sidecar (line 77), and that sidecar is only ever written after the MD5 check passed.

### 4. Failure behaviour, by mode

| Failure | What happens |
|---|---|
| Interrupted download | Non-zero exit from `set -e`. `.part` is kept and resumed on the next run. No final file. |
| `--full` MD5 mismatch | `.part` deleted, `ds_die` exits 1. |
| `--full` gzip corrupt | `.part` deleted, exits 1. |
| ER: release missing / sidecar missing / sidecar mismatch / MD5 mismatch / pin mismatch / layout error (including an undecompressable stream) | The whole dblp step is refused. Every pending dblp item gets a FETCH-FAILURES row (dsutil.py:1150-1152, 1426-1437), the run returns 1, and no dblp file is written. The scan finishes before any write (dsutil.py:1078-1101, 1153-1160). No network request is made. |
| Key not in the release / record over the 200,000-byte cap / excerpt check failed (well-formedness, expected DOI, expected author) | That record only: FETCH-FAILURES row, no file, exit 1. |
| `fetch-dblp.sh --verify`: sidecar missing or mismatched, MD5 mismatch, gzip failure | `ds_die`, non-zero. |

- No network path for dblp exists: dblp items have `urls=[]`, and `run()` raises if it is handed a dblp item (dsutil.py:809, 1342-1344).
- The OpenAlex and Crossref requests still run in the same `--sample` call. The one failed Crossref DOI will be requested again.

### 5. Remaining network calls

- **ER `--sample`:** api.openalex.org (`/works/doi:…`, `/authors/A…`) and api.crossref.org (`/works?filter=doi:…`).
  - Calls go through `subprocess.run` with an argument list, no shell, https only, `--max-time 60`.
  - DOIs are percent-encoded, and author IDs are checked against `^A[0-9]{4,12}$`.
- **`fetch-dblp.sh --sample`:** dblp.org/xml/dblp.dtd and the drops.dagstuhl.de `.md5` file.
- **`fetch-dblp.sh --full`:** drops.dagstuhl.de only.
- **Other `--sample` children (taken from the manifest):** openalex.s3.amazonaws.com, api.openalex.org, api.crossref.org, pub.orcid.org, api.figshare.com.
- **Confirmed:** no code path builds a `dblp.org/rec`, `dblp.uni-trier.de`, or dblp `*.xml` / `*.json` URL. The only such strings left are comments and the historical rows in FETCH-FAILURES.tsv.

### 6. Other items you asked me to verify

- **Byte ranges:** the ranges row (release id, file, MD5, `.gz` sha256, and offset plus length for header, record and footer, plus excerpt bytes and sha256) is enough to rebuild each excerpt exactly. `--with-release` really re-reads those offsets.
- **Provenance of the 171 stored records:** PROVENANCE.tsv has 171 rows, each with URL, timestamp, HTTP status, bytes, sha256 and licence. Of these, 20 are OpenAlex authors, and none is a dblp row.
  - I spot-checked `heldout/openalex-works/10.1002_cae.22316-57099d3b.json`: its id W3048614748 and its DOI match the ledger row.
  - I spot-checked `dev/crossref-works/10.1155_2023_9869061-5983b8b2.json`: it is a single item and the DOI matches.
  - The sha256 values themselves are UNVERIFIED, because I cannot hash files.
- **Split:** `sha256("scholarchain-er-split.v1|"+unit_id)`, ranked within each criterion, with the first ceil(3n/5) going to heldout (dsutil.py:758-774). It reads only unit IDs.
  - The committed file matches the rule for the counts I checked: 7→5, 4→3 and 3→2.
  - A unit never spans both splits. Each identifier belongs to exactly one unit (dsutil.py:742-744).
- **Fixture:** it calls the real `er_dblp_step`, `er_read_ranges`, `er_check_ranges_offline` and `er_check_ranges_release`, and checks offsets with an independent search.
  - The network trap replaces both `er_curl` and `subprocess.run`, and no other network client is imported (only `urllib.parse`). The claim holds.
- **Ownership:** every changed path is under `datasets/**`, which belongs to dataset-engineer.
- **Injection:** I found no shell construction from untrusted data. TSV cells have tabs and newlines stripped. File names are built from sanitised DOIs plus a hash, from author IDs checked against a regex, or from dblp keys checked against a regex with `/` replaced by `__`, so a key cannot escape its directory.

### FINDINGS

All owned by **dataset-engineer**.

1. **MINOR, fetch-dblp.sh:87-88. No time bound on the download.**
   - Scenario: the network stalls mid-transfer. Curl has no `--max-time`, `--speed-limit` or `--speed-time`, so it waits indefinitely.
   - Impact: the human must interrupt it. Data is safe: `.part` is kept and resumable, and the MD5 check still guards the result.
   - Fix: add `--speed-limit` / `--speed-time` (or `--connect-timeout`) and state the bound in the manifest.
2. **MINOR, fetch-dblp.sh:83-89. A failed resume can get stuck.**
   - Scenario: `.part` is complete but its MD5 is wrong, or DROPS ignores Range. `curl -C -` then fails (HTTP 416 or a "cannot resume" error), `set -e` exits before the MD5 branch can delete `.part`, and every re-run fails the same way.
   - Impact: it fails closed, but gives no recovery message. The human has to delete `.part` by hand.
   - Fix: when curl fails, print the recovery step.
3. **MINOR, dsutil.py:1332 and 1434-1435. dblp excerpt bytes are not counted against the 8 MiB budget.**
   - Scenario: the dblp step adds to `st['fetched']` but never to `st['total']`, so the "one 8 MiB budget" in the manifest bounds line is not enforced while writing.
   - Impact: oversize is only caught afterwards by `er_verify` (line 1559). The realistic total is about 200 KB; the worst case is 120 × 200 KB.
4. **MINOR, dsutil.py:1127-1137. Excerpt write and its two ledger rows are not atomic.**
   - Scenario: a crash or Ctrl-C after the PROVENANCE append but before the RANGES append.
   - Impact: the next `--sample` treats the record as present (line 1415-1418) and never repairs it, so `--verify` keeps failing with "must be a release excerpt with a row in DBLP-RELEASE-RANGES.tsv" until someone fixes it by hand.
   - Related case: if the post-write sha check at 1129-1130 raises, the file is left on disk with no ledger row. `--verify` does flag that file.
5. **MINOR, selection.tsv (the `criterion` and `expect_author` columns, lines 13-112, and unit IDs such as `nv-208-1029-*` and `ov-*`). These carry dblp's own identity claims and could act as labels.**
   - What they reveal: homonym units mean "dblp says these are two different persons" (for example `Jiayuan Ye 0001` / `0002`). Name-variant and ORCID units mean "dblp says these are one person", and their unit IDs encode the dblp person pid.
   - These are source facts, not policy output, and no module code reads selection.tsv (grep of `modules/` found nothing).
   - Impact: if human labelers or the ER queue can see the criterion, the unit ID or the `expect_*` columns, heldout gold labels may simply copy dblp's assertions.
   - Fix: record in the manifest and schema that these columns must be withheld from labelers. The entity-resolution-engineer should confirm the queue hides them.
6. **MINOR, dsutil.py:1568-1572. The cross-split entity overlap check only reports.**
   - The check covers OpenAlex author IDs and ORCID iDs only, and it prints `INFO`; it never fails. dblp excerpts, once they exist, are not included.
   - Impact: "no entity straddles the splits" is guaranteed only at the unit and identifier level. The same real person appearing in a dev unit and a heldout unit would not fail verification.
7. **MINOR, dsutil.py:1660-1783. The fixture leaves several paths untested.**
   - Not exercised: `release-pin-mismatch`, `record-exceeds-cap`, the `er_fetch` orchestration (writing FETCH-FAILURES rows, the exit code, skipping already-present records), and realistic release lines.
   - On the last point: the fixture records have no `mdate` / `publtype` attributes, there are no unwanted records between the wanted ones, and the declaration is US-ASCII rather than the encoding of the real release.
   - The agent disclosed the first two gaps. Because of the others, "fails closed with FETCH-FAILURES rows" was verified by reading the code, not by any test.
8. **MINOR, dsutil.py:1482-1485. `--verify --with-release` can pass without reading the release.**
   - Scenario: `split-assignment.tsv` is absent and there are no records. `er_verify` returns early and the command exits 0.
   - This is not reachable with the current tree, because the split file exists.
9. **NIT, selection.tsv:10.** The comment still says `dblp/rec = https://dblp.org/rec/<key>.xml`, which contradicts offline-only. It does not affect parsing or the split. The agent disclosed it; a comment-only edit needs the human's agreement.
10. **NIT, dsutil.py:809. Two dblp keys can map to the same file name.**
    - `ident.replace('/', '__')` maps `a/b__c` and `a__b/c` to the same file.
    - None of the current keys contains `__`. A collision would be caught by `er_read_ranges` "listed twice".
11. **NIT, dsutil.py:1147-1149. Checksum and scan read the file separately.** The release is hashed in one pass and scanned in a second. If the file were replaced in between, which needs a concurrent `--full`, the excerpts would come from bytes that were not hashed.
12. **NIT, fetch-dblp.sh:99-100.** A crash between `mv` and writing the sidecar leaves a file without a sidecar, and the next run downloads 1 GB again. Wasteful but safe.

### UNVERIFIED CONCERNS

- I could not diff against HEAD, so "`--full` is unchanged" and the exact DS-P5-03 diff are not confirmed.
- The sha256 values of the 171 stored records and of the fixture were not recomputed.
- The agent-reported results (fixture 36/0, er-verify 1011/0, validate 1346/0, `sbt check` 604 passed) were not observed by me.
- The layout and encoding of the real release, and which keys it contains, are unknown until it has been downloaded.
- Behaviour of `curl -C -` against DROPS (Range support, 416 handling) is unknown.
- Whether the same real person appears in units on both sides of the split was not determined.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-02 (Phase 5 main): read-only review of DS-P5-01/DS-P5-03 dataset fetch and extraction changes
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not run (reviewer has no Bash); agent-reported results not independently observed
VALIDATION RESULTS: code-level verification of the download command, output paths, checksum gates, fail-closed behaviour, network hosts, byte-range ledger, split rule, fixture wiring, ownership and injection safety, as described above
ASSUMPTIONS: the working tree as read is the diff under review; .gitignore /datasets/downloads/ is the committed rule
RISKS: real release layout unverified (the extractor refuses the whole release on mismatch rather than extracting wrongly); no time bound on --full; dblp identity assertions in selection.tsv could anchor gold labels if shown to labelers
MANUAL ACTION REQUIRED: human decides whether to authorize the download; human decides whether minor findings 1-8 must be repaired first; human confirms that labelers will not see criterion, unit_id or expect_* columns
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator routes the minor and nit findings (optional) to dataset-engineer and presents the reviewed command to the human; the download stays unauthorized until the human decides
VERDICT: APPROVE
FINDINGS: 8 MINOR, 4 NIT, all owned by dataset-engineer (numbered list above); finding 5 also needs confirmation from entity-resolution-engineer
UNVERIFIED CONCERNS: HEAD diff; file hashes; agent-reported test counts; real release layout and key coverage; DROPS Range behaviour; entity-level overlap across splits
```

---

## RV-P5-03: code-reviewer, focused re-review after DS-P5-04, verbatim

Orchestrator note: read-only review (no Bash). It ran in parallel with ER-P5-06, which writes only
`modules/entity-resolution/**`. The reviewer's note that the DS-P5-04 report was not yet archived
when it read is accurate: the report was archived during the review.

---

## RV-P5-03: focused re-review of the dataset download and extraction scripts after DS-P5-04

**Verdict: APPROVE.** Findings 1, 2, 4 and 7 from RV-P5-02 are fixed in the code. There is no blocker and no major issue. I found one new MINOR issue and five NITs, all owned by dataset-engineer. I could not run anything (Read, Grep and Glob only), so every observed result below comes from reading code, not from running it.

### 1. Re-check of findings 1, 2, 4 and 7

**Finding 1: download time bound. FIXED.**
- The constants are at `lib-datasets.sh:278-281`.
- The curl call at `lib-datasets.sh:323-326` passes `--connect-timeout 30 --speed-limit 1024 --speed-time 120` and has no `--max-time`.
- The same bounds are recorded in the manifest under `datasets.dblp.retrieval.full_transfer_bounds` (`dataset-manifest.yaml:82`).

**Finding 2: a failed resume could get stuck. FIXED.**
- errexit is no longer relied on:
  - `fetch-dblp.sh:84` calls `ds_full_download ... || rc=$?`, so errexit is off inside the function.
  - Every step inside has an explicit check: `mkdir || return 1`, `code="$(curl …)" || rc=$?`, `mv || return 1`, and sidecar write `|| return 1`.
  - `fetch-dblp.sh:85` then calls `ds_die … "$rc"`, so every curl failure exits non-zero with curl's own exit code.
- Recovery messages (`lib-datasets.sh:293-304`), checked in this order:
  - `28:*` (stall or connect timeout): `.part` kept, re-run to resume.
  - `*:416`: `.part` kept, manual `rm`.
  - `33:*` (server will not resume): `.part` kept, manual `rm`.
  - `63:*` (over the size cap): `.part` kept, stop and report.
  - Any other exit: `.part` kept, re-run; `rm` if it repeats.
  - `ds_full_recovery` never deletes the file.
- Automatic deletion happens only in two places, both after curl exited 0:
  - MD5 differs from the pin (`lib-datasets.sh:333-338`).
  - `gzip -t` fails (`lib-datasets.sh:340-343`).
- **No corrupt `.part` can be renamed as valid.** The only `mv` (`lib-datasets.sh:345`) comes after the MD5 equals the pin and `gzip -t` passes, on every path. That includes the shortcut where an existing `.part` already matches the pin: it is re-hashed at 332 and gzip-tested at 340.

**Finding 4: excerpt write not atomic. FIXED.** The write order is `er_write_excerpt`, `dsutil.py:1192-1220`. Each crash point:

| Crash point | Next `--sample` | `--verify` |
|---|---|---|
| During `er_write_part` (1205) | No PROVENANCE row, so RECOVER and re-extract. The `'wb'` open truncates the leftover `.part`. | The `.part` is under `datasets/samples/er-candidates`, so the on-disk walk flags it (1686-1694). |
| Hash mismatch (1207-1211) | `.part` removed; `excerpt-write-failed` row. | Clean (fixture O6). |
| After `os.replace`, before the ranges row (1212-1218) | No PROVENANCE row, so the file is re-extracted. | "file without a provenance ledger row" (1693-1694). |
| During the ranges rewrite: stale `DBLP-RELEASE-RANGES.tsv.tmp` (1178-1183) | The old ranges file is intact. The `.tmp` is overwritten the next time an extraction runs. | The `.tmp` is under the samples directory and not excluded, so it is flagged. |
| After the ranges row, before PROVENANCE (fixture O3) | RECOVER; `er_ranges_put` replaces the row for the same file (1176). | "range row … has no dblp ledger row" (1275-1276). |
| Torn PROVENANCE append | `er_repair_torn_tail` (1429-1433), then re-extract. | `er_tail_complete` check (1619-1624). |
| PROVENANCE row without a consistent ranges row or file | `excerpt-state-inconsistent`, not counted as present (1544-1552). | `er_check_ranges_offline`. |

Two notes on this walk:
- The repair, ranges and ledger temporary files have fixed names next to their targets. `.part` paths come from item paths that are sanitised (dblp keys with `/` replaced by `__`, regex-checked author IDs, DOI safe-names plus a hash). So neither `.tmp` nor `.part` can leave `datasets/samples/er-candidates/`.
- The torn-line repair can drop a complete row. See new finding N1.

**Finding 7: fixture gaps. FIXED.**
- Pin mismatch (O7), record-exceeds-cap, the end-to-end `er_fetch` path (O1-O6), and realistic release layouts are now exercised.
- The new parts call the real functions:
  - `er_fetch`, `er_verify`, `er_dblp_step`.
  - Fault injection swaps the module globals `er_ledger_commit` and `er_write_part`, and both are restored in `finally`.
  - `ds_full_download` is run by sourcing the real `lib-datasets.sh` (`dsutil.py:2288`).
- The curl stub is confined:
  - The child environment is replaced completely (`dsutil.py:2286-2287`): PATH is the stub directory first, then the system directories; there is no `BASH_ENV`; `HOME` is the temporary directory.
  - The URL is `https://fixture.invalid/…`.
  - The stub exits 99 on any unexpected mode.
  - The real `subprocess.run` is passed only to this one function; everywhere else it stays trapped (2362-2373).
- Gap: `fetch-dblp.sh --full` itself is checked only by text match (2342-2347). The wrapper's gate and its exit-code pass-through are not executed (NIT N6).

### 2. Network paths

**No dblp per-record URL is reachable.**
- dblp items have `urls=[]` (`dsutil.py:811`).
- `run()` refuses any dblp item (1456-1458).
- No code builds a `dblp.org/rec`, `dblp.uni-trier.de` or dblp `.xml`/`.json` URL. The grep hits are comments only.

**Every remaining network call:**

| Caller | Hosts |
|---|---|
| `er_curl` (ER `--sample` only) | api.openalex.org (`/works/doi:…`, `/authors/A…`) and api.crossref.org (`/works?filter=doi:…`) |
| `ds_fetch` in `fetch-dblp.sh --sample` | dblp.org/xml/dblp.dtd and the drops.dagstuhl.de `.md5` file |
| `ds_fetch` in the other `fetch-<dataset>.sh --sample` scripts | Unchanged per the manifest |
| `ds_full_download` (`fetch-dblp.sh --full`, human only) | drops.dagstuhl.de |

### 3. New findings

All are owned by **dataset-engineer**.

**N1. MINOR, `dsutil.py:1145-1160`, called at 1429-1433: the torn-line repair can drop a complete row.**
- `er_repair_torn_tail` drops the last line whenever the file does not end in `\n`. It never checks whether that line is a complete 14-column row.
- Failure input: `PROVENANCE.tsv` whose last line is a complete, valid row but has no final newline. This is plausible after a hand edit made to follow the `excerpt-state-inconsistent` repair instruction (1549-1551) in an editor that strips the final newline.
- Impact:
  - That row is removed.
  - If it is an OpenAlex or Crossref row, the next `--sample` sees `rel not in ledger` (1460), fetches the record again over the network, and `os.replace` overwrites the committed file (1513) with a new body and a new `retrieved_at_utc`.
  - So stored evidence and provenance change for a record that was intact. It is reported (`REPAIRED`), not silent.
  - A dblp row would only be re-extracted, giving the same bytes.
- The fixture (O4) tests only a half-written row.
- Current state: both committed TSVs end in a newline (checked), so nothing is affected today.
- Fix direction: drop the tail only when it has fewer columns than the header; otherwise report it and stop.

**N2. NIT, `dsutil.py:1549-1552`: an absolute local path can enter a committed-ready file.**
- The `excerpt-state-inconsistent` reason includes `p = os.path.join(root, rel)`, and `root` is the absolute `DS_ROOT`.
- That reason is written to `FETCH-FAILURES.tsv`, so the human's home directory path would land in that file and make it depend on the machine.
- Use `rel` instead.

**N3. NIT, `lib-datasets.sh:297, 299, 303`: the recovery commands do not escape quotes.**
- `rm '$part'` is printed as-is. If the repository path or a human-supplied output directory contains `'`, the printed command breaks, and a crafted directory name could inject commands if the line were copy-pasted.
- The path never comes from network data; it comes from `DS_ROOT`, the human's argument (only `..`, `.` and empty segments are rejected), and the committed manifest URL basename.
- Use `printf %q`.

**N4. NIT, `lib-datasets.sh:332-337`, with `fetch-dblp.sh:27, 79`: an MD5 tool failure deletes a good download.**
- `ds_need` never checks for `md5` or `md5sum`.
- If `ds_md5` fails after a completed transfer, `got` is empty, the check reports a mismatch, and a good 1 GB `.part` is deleted with the message "MD5 mismatch … got ,".
- This is safe (it fails closed) but wasteful. `md5` is present on macOS and `md5sum` on Linux.

**N5. NIT, `lib-datasets.sh:361`: the final log line understates the download.** After a successful `--full`, `ds_finish` logs "fetched 0 bytes", because `DS_FETCHED_BYTES` is never updated on that path.

**N6. NIT, `dsutil.py:2342-2347`: the `--full` wrapper is only text-checked.** `ds_require_full_confirmation` (exit 3) and the `ds_die … "$rc"` pass-through in `fetch-dblp.sh` are confirmed by reading the code, not by any fixture run.

### 4. Scope and open items

**Changed paths:**
- The dataset-related changed paths in the session git snapshot are all under `datasets/**`.
- The changes under `modules/core/**` and `modules/entity-resolution/**` in the same working tree belong to other work orders. I cannot attribute them because I cannot run a diff.

**Stored data appears unchanged:**
- `PROVENANCE.tsv` has 171 data rows, none of them dblp, the same as at RV-P5-02.
- `FETCH-FAILURES.tsv` has 121 rows.
- `DBLP-RELEASE-RANGES.tsv` does not exist.
- There are no stray `.part` or `.tmp` files.
- Byte-for-byte identity of `selection.tsv`, `split-assignment.tsv` and the stored records is UNVERIFIED: these files are untracked, so there is no HEAD to compare against, and I cannot hash them.

**Open items not silently changed:**

| RV-P5-02 item | Where it stands |
|---|---|
| 3 (8 MiB budget) | Still `st['fetched'] += n` with no addition to the total (`dsutil.py:1565`). The manifest bounds line (566) still claims one 8 MiB budget. |
| 5 (labeler visibility of dblp identity columns) | Not addressed in the schema. Routed to ER-P5-06 blind view in decisions-04. |
| 6 (cross-split overlap check only reports) | Still INFO only (1704-1708). |
| 8 (`--with-release` can pass without the release) | Still an early return (1612-1615). |
| Nit 9 | `selection.tsv:10` comment unchanged. |
| Nit 10 | `__` mapping unchanged (811). |
| Nit 11 | Hash and scan still read the release separately (1229-1231). |
| Nit 12 | `mv` still precedes the sidecar write (`lib-datasets.sh:345-346`). |

All of these remain recorded as open in `review-report.md` and `decisions-04.md`.

**Command safety:** no command is built from untrusted data. The ER curl call uses an argument list with no shell, and the full-download curl arguments are all quoted variables.

### 5. Download command packet (verified from code; the download is NOT authorized and is not recommended here)

**Command** (from the repository root):
```
SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full
```
This matches the manifest (`dataset-manifest.yaml:80`). Whether `fetch-dblp.sh` has its executable bit is UNVERIFIED.

**Before the network:**
1. Argument parsing; the output directory defaults to `datasets/downloads/dblp`.
2. `ds_need python3 curl awk`.
3. Five manifest reads with `python3 -I`.
4. Gate: exit 3 unless the variable equals `dblp-2026-10-03` (`fetch-dblp.sh:78`).
5. `ds_need gzip`.
6. If `dest` already matches its `.sha256` sidecar, the script returns 0 with no network.
7. If an existing `.part` already has the pinned MD5, curl is skipped.

**Host and URL:** `https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz`. Redirects are followed only to https, but to any host.

**Exact curl call** (`lib-datasets.sh:323-326`). There is no `--max-time`, no `--retry` and no `--silent`, so the progress meter appears on stderr.
```
curl --fail --location --proto '=https' --proto-redir '=https' --max-filesize 2000000000 \
  --connect-timeout 30 --speed-limit 1024 --speed-time 120 --write-out '%{http_code}' \
  -A "ScholarChain-dataset-sampler/1.0 (CS441 course project; bounded sample fetch)" \
  -C - -o "<repo>/datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part" "<URL above>"
```

**Bounds:**
- Connect: 30 s.
- Stall: below 1024 bytes/s for 120 s gives exit 28.
- Size cap: 2,000,000,000 bytes.
- No total time limit.

**Output paths** (all git-ignored under `/datasets/downloads/`, `.gitignore:35`):
- `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part` during the transfer.
- `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz` and `.xml.gz.sha256` after success.

**Checksum checks and when each runs:**

| Check | When |
|---|---|
| MD5 of an existing `.part` against the pin `cbd329100ea1bbb2873fa2f6d414ea00` | Before curl; skips the transfer if it matches |
| MD5 of `.part` against the pin | After curl exits 0 (line 332) |
| `gzip -t .part` | After the MD5 check passes (340) |
| `mv` to the final name, then sha256 sidecar written | Only after both checks pass (345-346) |
| Sidecar, MD5 and `gzip -t` | Later, in `fetch-dblp.sh --verify` (55-60) |
| Release MD5 and sidecar | Before any extraction in ER `--sample`, and in `--verify --with-release` |

**Failure behaviour:**

| Failure | What happens |
|---|---|
| curl exit 28 | `.part` kept, RECOVERY "re-run to resume", exit 28 |
| HTTP 416 | `.part` kept, manual `rm` named, exit with curl's code. See UNVERIFIED below. |
| curl exit 33 | `.part` kept, manual `rm`, exit 33 |
| curl exit 63 | `.part` kept, "do not re-run; report", exit 63 |
| Any other curl exit | `.part` kept, re-run (`rm` if it repeats), exit with curl's code |
| Completed transfer, MD5 mismatch | `.part` DELETED, message says why, exit 1 |
| MD5 matches but `gzip -t` fails | `.part` DELETED, exit 1 |
| Ctrl-C | `.part` kept, no message |
| Success | Logs size and sha256 and asks for them to be recorded in the manifest, exit 0 |

**UNVERIFIED for this packet:**
- **Real curl may not return an error on HTTP 416 when resuming.** As I recall from curl's source, a 416 reply to a resume request is not treated as a failure, so curl exits 0. If so:
  - the `*:416` branch never runs;
  - the `.part` is deleted automatically through the MD5-mismatch path;
  - the 416 fixture check tests a stub, not real curl behaviour;
  - the manifest claim at `dataset-manifest.yaml:83` that a `.part` is "never deleted automatically after a failed transfer" would be inaccurate for this case.

  Safety is unaffected either way, because a wrong file is deleted and never renamed.
- DROPS Range support.
- The real release size and layout.
- The executable bit on `fetch-dblp.sh`.

---

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-03 (Phase 5 main): focused read-only re-review of dataset download and extraction scripts after DS-P5-04
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not run (reviewer has no Bash); the 61 fixture checks and other agent-reported counts were not observed by me
VALIDATION RESULTS: code-level verification of findings 1, 2, 4 and 7 (bounds, recovery per curl exit, .part keep/delete rules, no rename without MD5 + gzip -t, crash-point walk with --verify detection, fixture uses the real code and confines the curl stub); network-path inventory; open items 3, 5, 6, 8 and nits 9-12 unchanged in code and still recorded; committed TSVs end with a newline; PROVENANCE 171 rows, FETCH-FAILURES 121 rows, no ranges file, no stray .part or .tmp
ASSUMPTIONS: the working tree as read is the DS-P5-04 result; the DS-P5-04 agent report was not archived under docs/agent-runs/phase-05 when I read it, so I used the claims stated in the work order
RISKS: torn-line repair can drop a complete hand-edited last row and cause a network re-fetch that overwrites a committed record (N1); HTTP 416 handling by real curl may differ from the documented and stub-tested behaviour (safe either way); the real release layout is still unknown
MANUAL ACTION REQUIRED: the human decides whether N1 (MINOR) and nits N2-N6 are repaired before acquisition; the download remains unauthorized
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator presents the download command packet to the human; optional routing of N1-N6 to dataset-engineer
VERDICT: APPROVE
FINDINGS: N1 MINOR dsutil.py:1145-1160 torn-tail repair drops a complete row without a final newline (dataset-engineer); N2 NIT dsutil.py:1549-1552 absolute path in FETCH-FAILURES reason (dataset-engineer); N3 NIT lib-datasets.sh:297,299,303 unescaped single-quoted rm path (dataset-engineer); N4 NIT lib-datasets.sh:332-337 an MD5 tool failure deletes a good .part, and the md5 tool is not checked by ds_need (dataset-engineer); N5 NIT lib-datasets.sh:361 "fetched 0 bytes" after --full (dataset-engineer); N6 NIT dsutil.py:2342-2347 the fetch-dblp.sh --full wrapper is only text-checked (dataset-engineer). RV-P5-02 findings 1, 2, 4 and 7 verified fixed.
UNVERIFIED CONCERNS: real curl exit status on HTTP 416 with -C - (likely 0, so the 416 RECOVERY branch is dead and the .part is auto-deleted through the MD5 path, contradicting dataset-manifest.yaml:83 and fetch-dblp.sh:16-18); DROPS Range support; byte-identity of selection.tsv, split-assignment.tsv and stored records (untracked, no hashing available); fixture count of 61 and other agent-reported results; executable bit of fetch-dblp.sh; attribution of non-datasets working-tree changes to other work orders
```

---

## RV-P5-04: code-reviewer, comprehensive Phase 5 review, verbatim

Orchestrator note: read-only review (no Bash). It ran in parallel with TE-P5-01 (test-engineer,
`tests/**` only), which was not reviewed here. The scope was modules/core/** and
modules/entity-resolution/** after ER-P5-07.

---

## RV-P5-04 review report (code-reviewer, read-only)

**Verdict: REQUEST_CHANGES.** There is one verified MAJOR finding, in gold-label evaluation arithmetic. Everything else is MINOR or NIT. The matching policy, the leakage structure, membership and masking conform to the human decisions. The two Option A gaps reported by ER-P5-07 are real, and the LLM path is a third, interface-only path the report did not name.

### Explicit answers

**README Phase 5 gate (README.md:1242-1247)**
1. **Exact-ID conflicts do not silently merge: MET.**
   - Any T1 disagreement (orcid, dblp-pid, doi, arxiv) goes to step 2 and becomes CONFLICT or REJECTED before any accept branch (Resolver.scala:405-432).
   - Core refuses `conflicts` on an ACCEPTED decision (MatchDecision.scala:506-511).
   - Blocking disagreements cap every inferred ACCEPTED to UNRESOLVED (Resolver.scala:503-517).
2. **The LLM can abstain: MET.**
   - Abstain maps to UNRESOLVED on the llm-adjudicated path (Adjudication.scala:155-156; core Enums.scala:85).
   - Evidence is not required for abstain (AdjudicationProtocol.scala:253-256).
   - Tested in AdjudicationSuite.scala:130.
3. **Examples are traceable to source records: MET.** MemberEndpoint carries dataset, record ID and snapshot (GoldMembership.scala:18-24). EndpointRef adds schema and record run ID. Labels are refused unless the memberId recomputes from the endpoints (GoldLabels.scala:175-183).
4. **Gold labels are human-reviewed: MET.** Nothing exists under `gold/` (Glob is empty). The only label fixtures are in GoldSetEvaluationSuite and are marked "SYNTHETIC TEST FIXTURES ONLY" (line 21). No production code writes labels; generated queue rows with label fields are refused (GoldLabels.scala:104-108).
5. **Precision and recall are reported separately: MET.** Segments are per entity kind × {all, 4 paths, not-generated}, with no cross-kind aggregate (GoldSetEvaluation.scala:447-462). The arithmetic is undermined by finding M1.
6. **No leakage from evaluation labels into matching logic: MET** (see the leakage verdict).

**Leakage verdict: no leakage found.**
- Read in full: the matching path (`policy/`, `adjudication/`, `identifiers/`, and the root package). It has no file I/O except `getResourceAsStream` of the policy JSON and the prompt (grep for java.nio/Files/Paths/env/process). It imports nothing from `review`/`evaluation`, and no gold type names appear in it.
- Membership (GoldMembership, SelectionUnits) reads only `CandidatePair` (kind, endpoints, blocking version, run) and dataset TSVs. The sampler key is `sha256(SampleRule, seed, memberId)`, which does not depend on outcomes.
- `withConfig` is the only threshold override, and no entry point calls it.
- LeakageStructureSuite is real but lexical. Together with my reading it is sufficient. It does not by itself forbid file reads from the matching path (NIT N1).
- Residual process risk (not code): thresholds are in the policy file. Nothing stops a human from tuning them on held-out sensitivity output. This must stay a procedural rule.

**README:1242 reading: ACCEPTED via DOI while dblp-keys differ is NOT a silent merge.**
- What happens (Resolver.scala:468-480, test PolicyV3Suite.scala:298-307):
  - The pair is ACCEPTED on the exact-id path at 0.99.
  - Reason code `block-inferred-acceptance.dblp-key.overridden-by.doi` and rule `exact-id.override.dblp-key.by.doi` are recorded.
  - `record-key-disagreement.dblp-key` is always added.
  - The disagreement values can be recomputed from the endpoint records.
- Why it is not silent:
  - README:1221/1242 targets disagreement of *authoritative* identifiers.
  - P-2 (decisions-05, approved) makes a dblp-key a per-record key, not an identity assertion. Differing keys are the normal DBLP preprint/venue or duplicate split.
  - The override is explicit and auditable.
- Residual risks:
  - The overridden values are not stored inline in the decision; that is a core constraint, and ER-P5-07 Risk 5 discloses it.
  - Two DBLP records carrying one wrongly copied DOI would be merged. That is the inherent DOI-is-T1 risk, not a policy defect.

**Option A gaps: both CONFIRMED by reading, plus a third (LLM) path.**
- **Gap 1 (inferred acceptance).** The deterministic title-year-authors rule (Resolver.scala:587-599) and score acceptance (602-611) do not check publication stage or venue.
  - Failure input: an older DBLP CoRR record (arXiv ee only, no DOI) against a Crossref or OpenAlex VoR of the same year, with the same title and author sequence. There is no DOI disagreement (one side has no DOI) and no dblp-key BD (cross-source), so the pair is ACCEPTED on the deterministic path at 0.95.
  - When both sides are DBLP records, the dblp-key BD caps it to UNRESOLVED. When the preprint carries a 10.48550 DOI, the T1 DOI disagreement gives CONFLICT.
- **Gap 2 (arXiv agreement).** arXiv is T1 and accepts with no corroboration (Resolver.scala:468-480; er-policy.v3.json:141 documents the limit).
  - Only DBLP emits arXiv IDs (DblpMapping.scala:194-260; OpenAlex and Crossref set `arxivId = None`).
  - Failure input: a DBLP venue record whose ee lists the arXiv ID, against the older CoRR record without a DOI. The arXiv T1A overrides the dblp-key BD, so the pair is ACCEPTED.
- **Gap 3 (not in the ER-P5-07 report).** The adjudication prompt asks whether endpoints are "the same published work" (er-adjudication-prompt.v1.txt:2-4) with no stage definition. A band-eligible preprint/VoR work pair could be LLM-accepted. It is interface-only today (finding m3).
- **Other paths checked: no further cross-stage accept.**
  - The dblp-key and scopus-eid agreements are the same record.
  - DOI agreement is per-version.
  - Cluster agreements need corroboration. The venue clause defeats CoRR-vs-journal pairs, and the stage clause defeats arXiv-only vs DOI-only pairs.
  - Researcher paths are not stage-relevant.

**Conformance to the human decisions: conforms, with the MINOR deviations below.**
- decisions-05:
  - Option A guide: er-label-guide.v1; label v3 requires labelingTarget and guideVersion.
  - Narrow precedence: steps 0-8 match decision-brief-01 §2.2.
  - P-1, P-2 and the OpenAlex/S2 author treatment are as specified.
  - Cluster corroboration covers W, MAG, s2-corpus and s2-paper.
  - P-3: v2 is refused by file schema and by policy name.
  - P-4: the wording is enforced by validation.
  - P-6: the weight is 0.0.
  - Unsure counts as reviewed, is reported separately and is excluded from binary metrics.
  - `--provisional` is refused with exit 2.
  - Reviewed and definitive counts are printed side by side.
- decisions-01:
  - Plausibility uses only non-identifier features.
  - OpenAlex author and work dispositions are documented per scheme.
  - The prompt stays in ER resources.
  - The blind view hides every system field.
- decisions-03: membership is policy-independent and memberId is policy-free.
- decisions-04:
  - Constant-marker masking.
  - Capacity 240 with a census on shortfall.
  - Create-once manifest.
  - The 200 minimum.
  - The sha256 freeze check is optional (m4).

### Verified findings, by owner

**entity-resolution-engineer**

- **M1 (MAJOR): label whitespace is validated trimmed but evaluated untrimmed, so gold metrics are silently inconsistent.**
  - Where: GoldLabels.scala:142 validates `g.label.map(_.trim)`. GoldSetEvaluation.scala:222 (`labelOf = g.label.getOrElse("")`), 237, 296-300 (`definitive = reviewed - unsure`) and 477 compare the raw string.
  - Failure input: a label line with `"label": "match "` or `" unsure"`. Ingest accepts it.
  - Impact:
    - The pair counts as human-reviewed.
    - It is in no TP/FP/TN/FN/unresolved/conflict cell and is not counted as unsure.
    - It is listed under no exclusion reason.
    - `Counts.definitive` and coverage denominators include it, while `Coverage.definitiveLabels` and `EvaluationReport.definitiveGoldLabels` exclude it.
    - A mistyped "unsure" becomes a phantom definitive label.
    - The result is an internally inconsistent final report with no refusal, contrary to the all-or-nothing ingest contract and to decisions-04 (report exclusions).
  - No test covers it.
  - Fix: refuse non-canonical label strings, or normalize once at ingest.
- **m1 (MINOR): threshold sensitivity counts rows the policy never decides by score.**
  - Where: GoldSetEvaluation.scala:369-375 includes every row whose path is `probabilistic`. Blocked rows keep that path (Resolver.scala:507-517, `base.copy` keeps the path).
  - Failure input: a dblp-key-blocked pair with p = 0.9 is counted as TP or FP at accept ≥ 0.85, although v3 can never accept it.
  - Impact: the sensitivity table misstates the policy's behaviour at other thresholds.
- **m2 (MINOR): Option A gaps 1 and 2 (above).**
  - Where: Resolver.scala:468-480 and 587-611.
  - Impact: preprint/VoR false positives against Option A labels. These are disclosed and await a human decision (whether the stage clause should apply to inferred and arXiv acceptance).
- **m3 (MINOR): the adjudication prompt v1 does not encode the Option A work target.**
  - Where: er-adjudication-prompt.v1.txt:2-4, "the same published work".
  - Impact: once wired, the LLM answers a different question than the labels. This needs a prompt v2 before any adjudication evaluation.
- **m4 (MINOR): the manifest freeze is optional for final held-out metrics.**
  - Where: GoldSetEvalMain.scala:131-140 and 158-159.
  - Failure input: running without `--manifest-sha256` prints `freeze=not-checked` and still exits 0 with "final" metrics.
  - decisions-04 requires a frozen membership. This should be mandatory for held-out final metrics.
- **m5 (MINOR): the evaluation output does not identify the split.**
  - Where: Coverage and EvaluationReport have no `split` field (GoldSetEvaluation.scala:132-190), and GoldSetEvalMain.scala:50-89 never prints it.
  - Impact: a dev manifest produces exit 0 "final" metrics that look the same as held-out metrics. decisions-05 asks to keep dev evaluation separate.
- **m6 (MINOR): thresholds and model/prompt versions are absent from the evaluation output, and the config is never applied.**
  - Where:
    - GoldSetEvalMain.scala:69-74 prints only the policy, blocking and feature versions. README:1237 requires the thresholds and the model and prompt versions.
    - `MatchPolicy.withConfig` (MatchPolicy.scala:569) is called by no entry point. ReviewQueueMain.scala:248 and 264 use `loadDefault()`.
  - Impact: `scholarchain.config.v2` threshold and band values are silently ignored, and the effective thresholds are not reported.
- **m7 (MINOR): arXiv version handling differs between agreement and disagreement, and the policy text claims otherwise.**
  - Where: ExactIdentifiers.scala:89-97 folds `vN` only for disagreement. Agreements come from exact equality (MatchFeatures.scala:219-222). er-policy.v3.json:137 says "versions vN are compared as one value".
  - Failure input: `2101.00001v1` against `2101.00001v2` gives neither agreement nor disagreement (ConflictPolicySuite.scala:382-388), so the pair falls to inference.
  - Impact: recall loss and a documentation claim with no implementation behind it. There is no over-merge.
- **m8 (MINOR): title-markup normalization (README:1217) exists but is not applied.**
  - Where: TitleMarkup.scala:8-12, "NOT WIRED". It is disclosed and needs er-features.v3 with hadoop-engineer.
- **m9 (MINOR, needs a human decision): the blind view shows algorithmic identity assignments.**
  - Where: BlindView.scala:311 and 333-334 show OpenAlex A IDs (and, for works, W IDs).
  - Impact: labelers can anchor on the same cluster IDs the exact-id path uses, which makes that path's precision partly self-confirming. decisions-04 masked only DBLP.

**domain-engineer**

- **m10 (MINOR): credential redaction is incomplete for repository URLs.**
  - Where: Identifiers.scala:282-291. The redaction only matches `scheme://userinfo@` with no `/` in the userinfo. It is used at SourceIdentity.scala:26 and 38.
  - Failure inputs:
    - `user:hunter2@github.com:org/repo`: neither UrlForm nor ScpForm matches, so "not a repository URL" is raised with the raw value.
    - `https://github.com/org/repo/tree/main?access_token=ghp_X`: the deep link is refused and the token is echoed.
  - Impact: a secret is copied into a ValidationError message. No current dataset carries repository URLs (low exposure).

**NITs (entity-resolution-engineer)**
- N1: LeakageStructureSuite (63-75) is purely lexical. Add an assertion that the matching path uses no `java.nio`/`Files`/`Path`.
- N2: Delimiter neutralization (AdjudicationProtocol.scala:25-26) is bypassable with Cf characters (`BEGIN​_CANDIDATE_DATA`). Impact is bounded by strict response validation.
- N3: The FN error list contains only REJECTED/match (GoldSetEvaluation.scala:485). Gold matches left UNRESOLVED, CONFLICT or not-generated are not exemplified. F1 uses decided-only recall; it is documented.
- N4: `writeNew` (GoldMembership.scala:438-450) can leave a partial manifest on an I/O error. CREATE_NEW then blocks a retry, which fails closed.

### Other checks passed
- **Correctness:**
  - Step order matches §2.2.
  - Corroboration clause bounds are as documented; missing inputs fail; `title.sim < 1` handles empty titles.
  - Plausibility definitions match the policy text.
  - `id.other.conflict` weight is 0.
  - Band eligibility requires probabilistic, UNRESOLVED and empty conflicts, and is re-checked in `Adjudication.eligible`.
- **Adjudication:**
  - StrictJson refuses duplicates, depth, trailing content and surrogates.
  - Evidence must come from `allowedEvidence`; core re-validates it.
  - The cache key is (inputHash, prompt, model, policy); only valid responses are cached.
- **Identifiers and sampling:**
  - `memberId = gm:sha256(er-gold-member.v1, kind, canonical pair)`.
  - `rowId = rq:sha256(er-review-queue-row.v3, memberId, policyVersion)`.
  - The sampler is seeded, proportional (largest remainder), independent of input order and blind to outcomes.
- **Label ingestion:** checks masked references (recomputed from the manifest), schema v3, labelingTarget and guide version.
- **Contracts:**
  - match-decision.v2 and config.v2 rules are present; v1 versions are retired.
  - Schema versions are consistent with ER-P5-07's list.
  - The module graph is respected: entityResolution depends on core only (build.sbt:141-143), and hadoop imports no policy, review or evaluation code.
- **Preservation:** at session start `git status` showed no modified tracked ER files (Names, BlockingKeys, MatchFeatures, TextFolding, FormatCharactersSuite). MatchFeatures scheme lists are explicit, so er-features.v2 is unchanged.
- **Configuration:** no hardcoded model, endpoint, bucket or account (grep). The model ID comes from `models.llmModelId`, and `calibrated: false`.
- **Ownership:** changes are limited to modules/core/** and modules/entity-resolution/**; nothing is under gold/**. Test temp files go under target/.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-04 (Phase 5 main): comprehensive read-only review of the Phase 5 entity-resolution changes
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: none run by reviewer; ER-P5-07 reports sbt check 680 passed, 0 failed (agent-reported, not observed by me)
VALIDATION RESULTS: README gate items 1-6 met in code (M1 undermines evaluation arithmetic); leakage: none found; README:1242 reading: not a silent merge; Option A gaps 1-2 confirmed, plus an LLM-prompt gap (interface-only)
ASSUMPTIONS: git status snapshot at session start reflects the reviewed tree; datasets/** and tests/** out of scope; DBLP is the only arXiv-ID producer (verified in DblpMapping/JsonSources)
RISKS: Option A false positives via the deterministic, score and arXiv paths; labelers can anchor on visible OpenAlex IDs; held-out thresholds could be tuned by hand from sensitivity output (procedural); the LeakageStructureSuite check is lexical only
MANUAL ACTION REQUIRED: human decisions on Option A gaps 1-3 and on showing OpenAlex A/W IDs in the blind view; decide whether --manifest-sha256 is mandatory for held-out metrics
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: route M1 and m1, m3-m9 plus N1-N4 to entity-resolution-engineer and m10 to domain-engineer; rerun sbt check; focused re-review of the repairs
VERDICT: REQUEST_CHANGES
FINDINGS: M1 MAJOR GoldLabels.scala:142 / GoldSetEvaluation.scala:222,237,296-300,477 (entity-resolution-engineer); m1 GoldSetEvaluation.scala:369-375 (ER); m2 Resolver.scala:468-480,587-611 (ER, human decision); m3 er-adjudication-prompt.v1.txt:2-4 (ER); m4 GoldSetEvalMain.scala:131-140,158-159 (ER); m5 GoldSetEvaluation.scala:132-190 / GoldSetEvalMain.scala:50-89 (ER); m6 GoldSetEvalMain.scala:69-74, ReviewQueueMain.scala:248,264, MatchPolicy.scala:569 (ER); m7 ExactIdentifiers.scala:89-97 vs MatchFeatures.scala:219-222, er-policy.v3.json:137 (ER); m8 TitleMarkup.scala:8-12 (ER, needs hadoop-engineer for v3 features); m9 BlindView.scala:311,333-334 (ER, human decision); m10 Identifiers.scala:282-291 (domain-engineer); NITs N1 LeakageStructureSuite.scala:63-75, N2 AdjudicationProtocol.scala:25-26, N3 GoldSetEvaluation.scala:485, N4 GoldMembership.scala:438-450 (ER)
UNVERIFIED CONCERNS: (1) An OpenAlex W record fetched by a preprint DOI may carry the VoR DOI after the merge, so a DOI agreement could pair a "preprint-lookup" record with the VoR; depends on the DS-P5-01 record contents, not read. (2) Legacy DBLP person-index URLs (db/indices/a-tree/x/Name_0001:Given) would not be masked by DblpMasking; DBLP person records are not currently in selection units. (3) Masking a pid value as a whole value could over-mask an unrelated numeric pair such as a volume/issue "11/3019" (agent-disclosed). (4) Strict corroboration (same year, venue Jaccard >= 0.8) may leave most correct cross-source cluster agreements UNRESOLVED; the size is unknown until held-out data exists.
```

---

## RV-P5-05 (code-reviewer) — focused re-check of the decisions-06 repairs, 2026-11-11, verbatim

**Verdict: REQUEST_CHANGES.** One verified MAJOR remains. A shared preprint (arXiv) DOI still accepts a preprint against a mixed-stage published record on the exact-id path, with the stage verdict `uncertain`. Every other repair in scope is fixed in code and has tests that would fail without the fix: D1–D5, the Option A stage target on the inferred, arXiv and LLM paths, prompt v2, m9, m4 and m5. I ran nothing (Read, Grep and Glob only).

### Verification of the ordered repairs

- **D1 / M1: FIXED.**
  - `GoldLabels.scala:81-86, 157-165`: `ingest` accepts only exact `match`, `non-match` or `unsure`. Nothing is trimmed and refusal stays all-or-nothing.
  - `GoldSetEvaluation.scala:465, 471-476`: `evaluate` refuses non-canonical labels built in code.
  - Tests: ER `GoldSetEvaluationSuite.scala:518-540` covers padded, case, soft-hyphen and ZWSP labels, plus a forged label refused by `evaluate`. The TE test at `MembershipEvaluationAdversarialSuite.scala:407-429` is unchanged in form.
- **D2 / m10: FIXED.**
  - `Identifiers.scala:316-319`: `errorDisplay(RepositoryUrl, _)` always returns `<repository-url withheld>`.
  - Every `normalizeRepositoryUrl` reason is a fixed string (`:260-296`).
  - `SourceIdentity.scala:26, 38` route through `errorDisplay`. `ExternalId.check` (`Provenance.scala:133`) has no repository scheme.
- **D3: FIXED.** `Identifiers.scala:230, 283-286`: a non-empty query is refused except on github.com. An empty `?` and a fragment are dropped.
- **D4: FIXED.** `DblpMasking.scala:69-70`: the separator class is now `[\s\p{Z}\p{Cf}]+`.
- **D5: FIXED.**
  - `DisplayDecoding.scala`: total decoder, at most 8 layers, every layer inspected.
  - `DblpMasking.scala:103-121`: the whole token is masked, and pid values found in decoded layers are masked across the pair.
- **Option A stage target, inferred paths and LLM: FIXED.**
  - `Resolver.scala:351-361, 552-588`: steps 7 and 8 cap ACCEPTED to UNRESOLVED when the stage is not established, and remove band eligibility.
  - `Adjudication.scala:62-66`: work pairs need `stage.exists(_.established)`.
  - Cluster corroboration requires `identifierCompatible` plus the venue clause, so corroborated implies an established stage (`Corroboration.scala:84, 151-152`).
  - A shared arXiv ID decides only for `same-stage-preprint` (`Resolver.scala:355-359`). It still counts for the step 2 T1-disagreement CONFLICT.
  - ACCEPTED never carries conflicts: steps 6a/6b pass none, and core enforces it.
- **Prompt v2 states the target; v3 and prompt v1 are retired but preserved: FIXED.**
  - Prompt text: `er-adjudication-prompt.v2.txt:7-18, 24-29`.
  - Retirement: `MatchPolicy.scala:344-367` and `AdjudicationProtocol.scala:107-111, 199-200`. The v1/v2/v3 JSON files and prompt v1 still exist.
- **m9: FIXED** (`OpenAlexMasking.scala`; `BlindView.scala:381-382, 427-471`).
  - Covered: case, percent-encoding, URL forms and `scheme:value` identifiers.
  - The endpoint ID becomes a `masked-endpoint:` reference, and an OpenAlex `sourceRecordId` becomes the marker.
  - Label ingest recomputes the references through the same `BlindView.labelRef`.
  - I found no bypass on the fields the view emits.
- **m4: FIXED** (`GoldSetEvalMain.scala:195-205`). A held-out evaluation that meets the minimum without `--manifest-sha256` prints coverage only and exits 2.
- **m5: FIXED.** `split` is in `Coverage` and `EvaluationReport`, and is read from the manifest (`GoldSetEvaluation.scala:228-234`). Every line ends ` split=<s>`, dev lines start with `DEV`, and dev status is `DevStatus`, never `FinalStatus`.
- **TE-P5-02:** the 7 tests assert the decided v4 behaviour and were strengthened, not weakened:
  - `ClusterCorroborationAdversarialSuite.scala:233-367` checks rules, reasons, verdicts, both orientations and LLM ineligibility, with positive controls.
  - `MatchContractCrossModuleSuite` pins v4 and prompt v2, has a key-set-complete outcome map, and refuses v1, v2, v3, v5 and prompt v1/v3.
  - The D2/D3 tests (`MatchContractCrossModuleSuite.scala:496-513`) and the D4/D5 tests (`BlindViewLeakageAdversarialSuite.scala:287-327`) still assert the original failing behaviour.
  - Byte-for-byte "unchanged" cannot be confirmed: the files are untracked, so there is no HEAD to compare against.
- **Ownership:** every reported change is inside the owner's territory. No `gold/**` file exists outside `target/test-work`, and no fabricated labels or metrics were found.

### FINDINGS

**(a) New findings from this review, all owned by entity-resolution-engineer**

1. **MAJOR: a shared preprint DOI still merges a preprint with a mixed-stage published record.**
   - Where:
     - `Resolver.scala:355-361`: `arxivBlocked` looks only at `ExactIdScheme.Arxiv`.
     - `Resolver.scala:516-528`: step 6a accepts on any DOI agreement.
     - DOI agreement is set intersection (`ExactIdentifiers.scala:104-110`).
     - `Corroboration.scala:58-62` itself treats a `10.48550/` DOI as a preprint marker.
   - Failing input (synthetic):
     - A = DBLP CoRR record `{doi 10.48550/arxiv.2101.00001, arxiv 2101.00001}`.
     - B = a published record carrying both `doi 10.1145/x` and `doi 10.48550/arxiv.2101.00001`. This is possible through `DblpMapping.scala:36, 246-249` (extra DOIs go to `additionalIds`) and `OrcidNormalizer.scala:39`.
   - Result: DOI agreement, no DOI disagreement, so ACCEPTED on exact-id at 0.99. Any dblp-key block is overridden. The stage verdict is `uncertain` (Preprint vs Mixed).
   - Impact:
     - The 10.48550 DOI is the arXiv ID in DOI form, so this is the same "published record lists its preprint's identifier" failure the v4 arXiv rule closes. It breaks decisions-06 on two counts: "a shared arXiv ID must not by itself merge" and "uncertain stage → leave it unresolved".
     - The mixed record also accepts against the VoR through the published DOI, so transitive grouping would join the preprint and the VoR.
     - `er-policy.v4.json:137` claims a DOI agreement "is already publication-stage evidence". That does not hold for a preprint DOI listed on a mixed record.
     - No test covers this: PolicyV4Suite tests only `{arx, doi1}` on both sides.
     - No instance in the current sample: the one 10.48550 OpenAlex record has a single DOI.
   - Fix direction: when every shared DOI has a preprint prefix, treat the agreement like an arXiv agreement (decides only for `same-stage-preprint`). The human may instead rule that the README DOI rule overrides this.
2. **MINOR: the arXiv ID and its 10.48550 DOI are never related.**
   - A `{arxiv 2101.00001}` against B `{doi 10.48550/arxiv.2203.00002}` has no agreement and no T1 disagreement. The stage is `same-stage-preprint`, so the deterministic rule or the score ACCEPTS two different arXiv submissions.
   - This was already true under v3, but v4 now explicitly treats it as an established stage (`Corroboration.scala:181`, `PolicyV4Suite.scala:79, 136`).
   - Exposure is narrow: old CoRR records with only an arXiv link, against OpenAlex arXiv-DOI records.
3. **NIT: `Adjudication.eligible` trusts the stored verdict.** `Adjudication.scala:66` checks `stage.exists(_.established)`, but `StageResult` is a public case class and the verdict is not recomputed. A forged `StageResult(Preprint, Published, _, SamePublished)` passes. The ER-P5-08 claim "including forged copies" holds only for outcomes with no stage.
4. **NIT: `evaluate` does not refuse duplicate member IDs built in code** (`GoldSetEvaluation.scala:248, 516`). `reviewedPairs = labels.size` would then differ from `coverage.humanReviewed`. Ingest prevents this on the CLI path. Also, `coverage()` is public and still maps a non-canonical label to `""`.
5. **NIT: freeze check race.** `GoldSetEvalMain.scala:157-168` hashes the manifest and then reads it again separately (time-of-check, time-of-use). The SHA requirement only proves that the caller supplied the file's own hash. "Recorded" stays a procedural guarantee.

**(b) Earlier items: status checked in code**

- **RV-P5-04, all still open (entity-resolution-engineer):**
  - **m1:** `GoldSetEvaluation.scala:400-402` still counts every probabilistic-path row. This is worse under v4, because stage-blocked rows keep the probabilistic path.
  - **m6:** `GoldSetEvalMain.scala:98` prints no thresholds, model or prompt version. `withConfig` has no caller; `ReviewQueueMain.scala:248, 264` use `loadDefault`.
  - **m7:** agreements use exact equality (`MatchFeatures.scala:219-222`), while `er-policy.v4.json:142` still says "versions vN are compared as one value".
  - **m8:** `TitleMarkup.scala:8` is still marked NOT WIRED.
  - **N1:** LeakageStructureSuite has no `java.nio` ban.
  - **N2:** `AdjudicationProtocol.scala:25-26` is still bypassable with Cf characters.
  - **N3:** FN examples are still only REJECTED/match (`GoldSetEvaluation.scala:527`).
  - **N4:** `GoldMembership.scala:438-450` can still leave a partial file.
  - Also open: TE-P5-01's comma-form homonym characterization.
- **RV-P5-03, all still open (dataset-engineer):**
  - **N1:** `dsutil.py:1145-1160` torn-tail repair is unchanged.
  - **N2:** `dsutil.py:1549-1552` still writes an absolute path.
  - **N3:** `lib-datasets.sh:297, 299, 303` still print `rm '$part'` unescaped.
  - **N4:** `ds_md5` is unchecked at `:332`.
  - **N5:** the log line at `:361` is unchanged.
  - **N6:** the `--full` wrapper is still only text-checked (not re-read in detail).
- **RV-P5-02, still open (dataset-engineer):**
  - **Item 3:** `dsutil.py:1565` adds no bytes to the total.
  - **Item 5:** schema-side labeler visibility of the dblp identity columns. In code, the blind view excludes criterion and unit IDs (`BlindView.scala:141-143, 237-268`).
  - **Item 6:** the cross-split check is INFO only (`:1705`).
  - **Item 8:** early return at `:1612-1615`.
  - **Nits 9-12:** not changed by any Phase 5 work order seen; not re-read in detail.

**(c) Human and process gates (all open)**

- Gold labeling of at least 200 human-reviewed pairs: no labels exist.
- Gold-set evaluation run.
- FE-P5-01 ratification and the `goldSetEval` alias (allowlist row still `inactive:B10-alias-pending`).
- Full dblp download, still unauthorized.
- `sbt check` duration and log volume:
  - TE-P5-02 reports 120 s, under the 180 s auto-background limit with little margin.
  - 27.3 MB of Hadoop DEBUG/INFO output truncates the console summary.
  - Both belong to build-engineer.
- Human decision on finding 1: DOI-rule precedence versus the stage target.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-05 (Phase 5): independent read-only re-check of the decisions-06 repairs (D1-D5, Option A stage target / er-policy.v4 / prompt v2, m9, m4, m5, TE-P5-02)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not run by reviewer. Reported by TE-P5-02, not observed: sbt check core 299/0, entityResolution 302/0, hadoop 67/0, tests 120/0; markTestRun suites=63 passed=788
VALIDATION RESULTS: D1, D2, D3, D4, D5, inferred/score/LLM stage caps, arXiv cross-stage rule, prompt v2 and retirements, m9, m4, m5 verified fixed in code with tests that fail without the fix; TE-P5-02 tests assert the decided v4 behaviour and are not weakened; ACCEPTED never carries conflicts; one remaining cross-stage exact-id accept path (finding 1)
ASSUMPTIONS: the working tree as read is the post-TE-P5-02 state; the tests/er and evaluation files are untracked, so "unchanged" for the D1-D5 tests is judged by content against the TE-P5-01 descriptions, not by diff
RISKS: preprint/VoR over-merge via a shared 10.48550 DOI on records with several DOIs (DBLP multi-ee, ORCID self IDs); MAG IDs stay visible in the blind view (not covered by the instruction); v4 raises the manual-decision load; held-out thresholds could still be tuned by hand from the sensitivity output (procedural)
MANUAL ACTION REQUIRED: human decides whether a preprint-prefix DOI agreement is subject to the stage target (finding 1) or whether the README DOI rule takes precedence; the gates in (c) above
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: route finding 1 (and optionally 2-5) to entity-resolution-engineer after the human decides on finding 1; one foreground sbt check; focused re-review of finding 1
VERDICT: REQUEST_CHANGES
FINDINGS: (1) MAJOR Resolver.scala:355-361,516-528 (with Corroboration.scala:58-62, er-policy.v4.json:137): a shared 10.48550 preprint DOI ACCEPTS a preprint against a mixed published record at an uncertain stage (entity-resolution-engineer); (2) MINOR Corroboration.scala:181 / PolicyV4Suite.scala:79,136: an arXiv ID and a 10.48550 DOI of another submission are same-stage-preprint and can be inferred-ACCEPTED (entity-resolution-engineer); (3) NIT Adjudication.scala:66: the stored stage verdict is trusted, not recomputed (entity-resolution-engineer); (4) NIT GoldSetEvaluation.scala:248,516: evaluate does not refuse duplicate memberIds built in code; coverage() maps non-canonical labels to "" (entity-resolution-engineer); (5) NIT GoldSetEvalMain.scala:157-168: hash-then-reread race; the SHA requirement is procedural (entity-resolution-engineer). Still open: RV-P5-04 m1, m6, m7, m8, N1-N4; RV-P5-03 N1-N6; RV-P5-02 items 3, 5, 6, 8 and nits 9-12
UNVERIFIED CONCERNS: whether hooks, settings and the test marker are untouched (the session git status snapshot was truncated and I cannot diff); byte-identity of the five D1-D5 tests against TE-P5-01 (untracked files); real-data frequency of records with both a preprint DOI and a published DOI (DBLP multi-ee, ORCID); the TE-P5-02 counts and the 120 s duration
```

---

## RV-P5-06 (code-reviewer) — focused re-review of the RV-P5-05 finding 1 fix (ER-P5-09, TE-P5-03), 2026-11-11, verbatim

**Verdict: APPROVE.** The fix does what decisions-07 Option 1 asks. I found no critical, major or minor defect in it. There are two nits and one point the human needs to decide. I ran nothing; I used only Read, Grep and Glob. The test counts below are as the human reported them.

### Checks 1–7

**1. Preprint-DOI gate: verified.**
- `Resolver.scala:380-384` marks the DOI agreement as preprint-only when every shared DOI starts with a prefix from `p.workCorroboration.preprintDoiPrefixes` (`:293-294`). It blocks that agreement unless `stage.bothPreprint` holds.
- The block removes DOI from the accepting agreements (`:385-392`). The pair ends at step 6c: UNRESOLVED on exact-id with confidence 0.0 (`:573-585`).
- Each blocked outcome carries:
  - rule `exact-id.unresolved.preprint-doi-agreement-cross-stage` (`:147`)
  - reason `preprint-doi-agreement-cross-stage`
  - reason `publication-stage-<verdict>` (`:425-436`)
- The rule is symmetric. `PolicyV5Suite:145-178` and `ClusterCorroborationAdversarialSuite:460-488` (64 cases) test both orientations.

**2. No fallback path accepts the blocked pair: verified for every path in scope.**
- **Bibliographic rules and score:** steps 7 and 8 run only when no identifier agrees at all (`:586-589`). A blocked DOI still counts as agreeing, so the pair never gets there.
- **Cluster-ID corroboration:** the stage clause needs `identifierCompatible` (`Corroboration.scala:84, 151-152`). Both sides carry the preprint DOI, so it holds only when both are pure preprints, which is exactly when the DOI is not blocked.
- **Arxiv:** the arxiv block uses the same `!bothPreprint` condition (`:375-376`). If the DOI is blocked, the arxiv agreement is blocked too.
- **LLM:** the outcome's path is ExactId, so `Adjudication.eligible` is false (`Adjudication.scala:63`). TE uses a fake adjudicator that always answers accept, and it is called 0 times.
- **Independent dblp-key or scopus-eid agreement (human decision point, not a finding):** ER-P5-09's assumption holds in code.
  - Input: A = `{dblp-key k, doi 10.48550/arxiv.X}` (stage: preprint) against B = `{dblp-key k, doi 10.1145/y, doi 10.48550/arxiv.X}` (stage: mixed).
  - Path: no conflicts, no first-tier (T1) agreement left, and dblp-key is a second-tier (T2) scheme that accepts on agreement (`er-policy.v5.json:150-156`). Step 6b ACCEPTS at 0.99 (`Resolver.scala:563-572`) while the stage is `uncertain`.
  - This matches the letter of decisions-07 ("relies solely on shared preprint DOIs"). The acceptance comes through a record key, not through the DOI or a fallback.
  - It is the same behaviour v4 already had for arXiv. The documentation states it ("when no other identifier decides", `Resolver.scala:277`).
  - But it does accept a pair whose stage is uncertain, and no test pins it either way. The human should confirm that per-record keys (dblp-key, scopus-eid) may decide whatever the stage.

**3. Publication DOIs, T1 conflicts and ACCEPTED: verified.**
- A shared publication DOI still decides, including when a preprint DOI is also shared. Tests: `PolicyV5Suite:241-266` and TE (c1).
- Step 2 still uses `identifying`, which includes the blocked DOI. So a T1 disagreement next to a shared preprint DOI is still CONFLICT, with the DOI recorded as a ContradictedAgreement (`:487-504`; tests `PolicyV5Suite:270-302` and TE).
- ACCEPTED outcomes pass no conflicts at steps 6a and 6b, and core enforces this.

**4. Normalization: no bypass found.**
- The agreement values the gate reads come from `c.exactIdAgreements`.
- `MatchCandidate` validation checks those values with `ExactId.checkSet` (`Candidates.scala:384`). `Resolver.decide` runs that validation first (`Resolver.scala:306`).
- A DOI must be canonical: `^10\.\d{4,9}/[!-~]+$`, lower-case (`Identifiers.scala:47-53`). That refuses upper case, URL forms, `doi:`, whitespace, a percent-encoded `/`, and non-ASCII look-alikes. `\d` matches ASCII digits only.
- `EndpointIdentifiers.inconsistencies` requires those agreements to equal the endpoint-set intersection (`:83-85`). Hadoop-built endpoints come from validated `WorkRecord` DOIs and `additionalIds` (`Bibliographic.scala:229, 292`).
- Non-canonical values inside `EndpointIds` only change the stage reading. They fail safe: a non-prefixed value counts as a published DOI, which makes the side mixed and blocked.
- The plain `startsWith` is therefore safe as wired. TE (c2) checks 11 raw forms plus an attack step, and it passed.

**5. Versioning: verified.**
- `FileSchemaVersion` is `er-policy-file.v5` and `DefaultResource` is `er-policy.v5.json`. v4 is in both retired sets, and `RetiredV4Resource` is kept (`MatchPolicy.scala:336-372`).
- `er-policy.v4.json` is preserved.
- v5 corrects the DOI rationale (`er-policy.v5.json:137`), and the v5 definition text matches the code (`:15-17, 60`).
- No main code or config hardcodes v4 (core config only has the key).
- Keeping prompt v2 is justified: blocked pairs never reach the LLM. The prompt text and the label guide (`er-label-guide.v1.txt:22-35`) do not conflict with v5.

**6. Tests: verified.**
- Both required regressions exist:
  - (a) the published record with its own DOI plus the preprint DOI: `PolicyV5Suite:145-178` and TE.
  - (b) two preprints sharing a DOI: `PolicyV5Suite:212-237` and TE.
- The (a) tests would fail without the fix, because v4 ACCEPTED these pairs at 0.99. The (b) and (c) tests are guards.
- The module v4→v5 edits are pin changes only: MatchPolicySuite, PolicyV3/V4, Resolver, Adjudication, ReviewQueue, CuratedRunReader and GoldMembership suites, plus the cross-module suite. Each changed line swaps the version string, and the v3, v2 and prompt-v1 refusals are still present.
- MatchContractCrossModuleSuite pins v5 and prompt v2. It refuses v1–v4, v6 and prompts v1/v3, and its expected-outcome map gained a preprint-DOI entry and stays complete.
- The D1–D5 regression tests are still present: `GoldSetEvaluationSuite:518`, `DblpMaskingSuite:71, 118`, `BlindViewLeakageAdversarialSuite:287, 308`, `MatchContractCrossModuleSuite:492-527`. "Unchanged" can only be judged by content, because these files are untracked.

**7. Ownership: verified as far as I can see.**
- ER-P5-09 changed files only under `modules/entity-resolution/**`, and TE-P5-03 only under `tests/**`.
- No `gold/**` file exists outside `target/test-work`. No labels or metrics were invented: ER-P5-09 states no gold-set evaluation was run.
- I cannot diff, so I cannot confirm hooks and settings are untouched.

**Characterization change: acceptable, no human decision required.**
- Two mixed records with different publication DOIs that share one preprint DOI are now UNRESOLVED (`PolicyV5Suite:304-311`).
- That is the fail-safe reading of "prevent automatic acceptance", and the decision did not ask for a CONFLICT result here.
- Making it CONFLICT would mean treating disjoint publication DOIs as a T1 disagreement despite a shared DOI. That would be a new policy rule, and the human can raise it later if wanted.

### FINDINGS

**(a) New, both owned by entity-resolution-engineer**
1. **NIT, `Resolver.scala:425-436` with `:563-572`:** the rule `exact-id.unresolved.preprint-doi-agreement-cross-stage` is added to every outcome. On the dblp-key input above, an ACCEPTED decision therefore lists a rule whose ID says "unresolved". The provenance reads as contradictory. The arXiv rule at `:141` has the same pattern from before.
2. **NIT, `MatchPolicy.scala:558-561`:** prefix validation only requires `startsWith("10.")`.
   - A policy file with `"10.48550/ARXIV"` (upper case) validates, but canonical DOIs are lower case, so it never matches and the decisions-07 block is silently off (fails open).
   - A prefix without the trailing slash over-blocks (fails safe).
   - Requiring a canonical lower-case prefix that ends in `/` would close this. Exposure is low, because the policy file is a versioned, reviewed resource.

**(b) Still open (re-checked only where this fix could touch them)**
- **RV-P5-05 findings 2–5 (entity-resolution-engineer):** all open.
  - 2: an arXiv ID and a 10.48550 DOI are still never related. The fix does not cover the no-agreement case.
  - 3: `Adjudication.scala:66` unchanged.
  - 4 and 5: not touched.
- **RV-P5-04 (entity-resolution-engineer):** m1, m6, m8 and N1–N4 not touched. m7 is now carried into v5: `er-policy.v5.json:142` still says "versions vN are compared as one value".
- **Also not touched:** RV-P5-03 N1–N6 and RV-P5-02 items 3, 5, 6, 8 and nits 9–12 (dataset-engineer), and TE-P5-01's comma-form homonym characterization.

**(c) Human and process gates**
- Gold labeling of at least 200 human-reviewed pairs, then the gold-set evaluation run.
- FE-P5-01 ratification and the `goldSetEval` alias.
- The full dblp download, still unauthorized.
- `sbt check` duration and log volume: 128 s on the human run, close to the 180 s auto-background limit, which caused recovery-04. Owner build-engineer, not yet routed.
- The dblp-key / scopus-eid decision point above.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-06 (Phase 5): focused read-only re-review of the fix for RV-P5-05 finding 1 (ER-P5-09, TE-P5-03; decisions-07 Option 1)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not run by reviewer. Human-reported (recovery-04.md): sbt -no-colors check, 802 passed, 64 suites, 0 failures/errors/skips; core 299, entityResolution 312, hadoop 67, tests 124; marker suites=64 tests_passed=802 (orchestrator Read)
VALIDATION RESULTS: checks 1-7 verified in code (see above); no bypass of the preprint-prefix check through any input reachable from Resolver.decide; v5 default, v4 retired and preserved, v4 rationale corrected, prompt v2 justified; regressions (a) and (b) present, and the (a) tests fail without the fix; pin edits only change pins; D1-D5 tests present
ASSUMPTIONS: the working tree as read is the state the human's sbt check covered; untracked files are judged by content, not by diff
RISKS: per-record-key (dblp-key, scopus-eid) agreements still ACCEPT pairs whose stage is uncertain or cross-stage; a mis-cased preprint prefix in a future policy file would silently disable the block; RV-P5-05 finding 2 is still open
MANUAL ACTION REQUIRED: human confirms or overrides the ER-P5-09 assumption that an independent dblp-key or scopus-eid agreement may decide while the preprint DOI is blocked; the gates in (c)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance decision on Phase 5 finding 1 (optionally route nits 1-2 and the decision point to entity-resolution-engineer); remaining Phase 5 gates in (c)
VERDICT: APPROVE
FINDINGS: (1) NIT Resolver.scala:425-436,563-572: rule exact-id.unresolved.preprint-doi-agreement-cross-stage is recorded on ACCEPTED outcomes (step 6b via dblp-key/scopus-eid) (entity-resolution-engineer); (2) NIT MatchPolicy.scala:558-561: preprintDoiPrefixes validation accepts non-canonical prefixes; an upper-case prefix silently disables the block (entity-resolution-engineer). Human decision point (not a finding): Resolver.scala:563-572 ACCEPTS {dblp-key k, 10.48550/arxiv.X} vs {dblp-key k, 10.1145/y, 10.48550/arxiv.X} at 0.99 with stage uncertain; untested. Still open: RV-P5-05 findings 2-5; RV-P5-04 m1, m6, m7 (now also er-policy.v5.json:142), m8, N1-N4; RV-P5-03 N1-N6; RV-P5-02 items 3, 5, 6, 8, nits 9-12
UNVERIFIED CONCERNS: whether hooks, settings and modules/core are untouched by ER-P5-09/TE-P5-03 (no diff available; the git status snapshot was truncated and shows core files modified from earlier work); the test counts are human-reported and the console output was not seen; how often real data has a preprint DOI and a published DOI on one record alongside a shared dblp-key or scopus-eid
```

---

## RV-P5-07 (code-reviewer) — review of the DS-P5-05 acquisition repairs and the full dblp download command, 2026-11-12, verbatim

**Verdict: APPROVE.** I found no critical or major issue. There are 2 MINOR findings and 6 NITs; one NIT is owned by entity-resolution-engineer and the rest by dataset-engineer. This review is from reading the code only (Read, Grep, Glob). I ran nothing, so every count the agent reported is unverified.

### 1. Status of each repair

| Item | Fixed in code? | Would a check fail without the fix? |
|---|---|---|
| RV-P5-02 #3 (8 MiB budget) | Yes. `dsutil.py:1309-1314` checks `st['total'] + size` before each write. `st['total']` starts from the bytes already in the ledger (1538) and is passed in at 1657. | Yes. `_er_fixture_budget_and_names` (2207-2220) and O10 (2492-2503). |
| #5 (schema side) | Yes, as documentation (`er-candidates.md:30-49`, manifest:682). The key is required at `dsutil.py:636`. | Only the key's presence is checked. Nothing in datasets/** enforces that the columns stay hidden, and the documentation says so. See section 5. |
| #6 (cross-split check) | Yes. It now fails verification (`dsutil.py:1838-1847`) and includes dblp `orcid` attributes and ID-form normalisation. | Yes. O8 (2462-2480, a lower-case `x` ORCID straddling the splits) plus a passing control (2306-2310). There is a coverage gap (MINOR-2). |
| #8 (`--with-release`) | Yes. `cmd_er_verify` fails when `released is None` (1854-1856). `er_check_ranges_release` returns False when the release gate fails. | Yes. O9 (2482-2488) and the passing case in O1 (2311-2315). |
| Nit 9 | Not edited (correct, waiting for the human). The proposed text for `selection.tsv:10` is **accurate**: `er_item` gives dblp rows `urls=[]` and cuts them from the pinned release (811-819). Separately, lines 7-8 still say "the fetched dblp record"; "extracted" would be consistent. | – |
| Nit 10 (`__` in keys) | Yes. Keys containing `__` are refused (735-739), and file names are compared case-insensitively (1462-1469). | The `__` case is tested (2223-2233). The case-insensitive collision case is not (NIT-6). |
| Nit 11 (one pass) | Yes. `_HashingReader` plus `drain()` (994-1060). Both digests are checked before `read_err` and before anything is written. | No test fails without it; the existing cases would also pass with two passes (NIT-6). |
| Nit 12 (sidecar) | Yes. Atomic sidecar write (145-152). A final file with no sidecar is re-checked against the MD5 pin and `gzip -t` (347-355). | Yes (2645-2660). |
| RV-P5-03 N1 (torn row) | Yes (1198-1223). | Yes, O4b (2374-2392). The docstring's premise is wrong (NIT-4). |
| N2 (absolute path) | **Partly.** `excerpt-state-inconsistent` now uses the relative path. Release-level reasons still write the absolute path into FETCH-FAILURES.tsv (NIT-3). | Only `excerpt-state-inconsistent` is tested (2403). |
| N3 (`rm` command) | Yes. `printf %q` (321). | Yes (2616-2625). |
| N4 (MD5 tool) | Yes. `ds_md5` checks its own output (123-136), `ds_need_md5` runs before the transfer, and a tool failure keeps the `.part` (385-388). | Yes (2631-2636). |
| N5 (log line) | Yes (369-373). | Yes (2641, 2741). |
| N6 (`--full` wrapper) | Yes. The real `fetch-dblp.sh --full` is run on copies of the scripts (2676-2748). | Yes. |
| HTTP 416 with curl exit 0 | Yes (380-383). It returns 22 and never checks, deletes or renames the `.part`. | Yes, stub `range416ok` (2607-2611). |

The HTTP 416 change is correct. As far as I recall, curl's `http_should_fail` does not treat a 416 reply to a resume request as an error, so real curl probably exits 0 with code 416. That branch is now handled. Without it, an existing `.part` would have been MD5-checked and deleted.

### 2. What the download command does, read from the code

Command: `SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full`

**Before any network access, in order:**
1. `ds_parse_args`: exactly one mode flag. The output directory defaults to `datasets/downloads/dblp`; any other must be relative, under `datasets/downloads/`, with no empty, `.` or `..` segments (66-110).
2. `ds_need python3 curl awk`.
3. Five local manifest reads with `python3 -I dsutil.py get`. This code imports only `urllib.parse`, so it has no network.
4. Confirmation gate (83, 407-411): exits 3 unless the variable equals `dblp-2026-10-03`.
5. `ds_need gzip` and `ds_need_md5`.
6. If the final file matches its sidecar, return 0 (340). If the final file has no sidecar, re-check its MD5 and `gzip -t`, then write the sidecar (347-355).
7. If an existing `.part` already has the pinned MD5, curl is skipped (359).

**URL and host:** exactly one curl call, to `https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz` (manifest:45). The fixture checks that the `full)` branch contains no other curl call (2668-2673).

**Exact curl call** (365-368):
`curl --fail --location --proto '=https' --proto-redir '=https' --max-filesize 2000000000 --connect-timeout 30 --speed-limit 1024 --speed-time 120 --write-out '%{http_code}' -A "<UA>" -C - -o "<dest>.part" "<URL>"`

- **Not set:** `--max-time`, `--retry`, `--max-redirs` (curl's default applies) and `-q`.
- **Bounds:** 30 s to connect; a transfer below 1024 bytes/s for 120 s is a stall (exit 28); 2,000,000,000-byte cap.
- **No total time limit.** A trickle just above 1 KiB/s would take about 12 days for 1.03 GB.

**Redirect risk.** curl follows redirects to any https host. The pinned MD5 (`cbd329100ea1bbb2873fa2f6d414ea00`) followed by `gzip -t` is the integrity gate. MD5 collisions are practical, but they would require control of the published original file. Forging a second file that matches an existing pinned MD5 is not practical. A redirect host would see the user agent, the client IP and the Range header; no credentials are sent. The downloaded bytes are never executed: they are gzip-tested, scanned line by line, and only excerpts are parsed with ElementTree. Whether that MD5 is the authentic DROPS value comes from DS-P5-02, which I cannot re-check.

**Resume:** `-C -` appends to an existing `.part`. If the server ignores the Range header, curl exits 33 and the `.part` is kept with a manual `rm` step.

**Where it writes:**
- `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part`
- then `dblp-2026-10-03.xml.gz`, `.xml.gz.sha256.tmp` (briefly) and `.xml.gz.sha256`.
- All are git-ignored (`.gitignore:35` `/datasets/downloads/`).
- Deletions are only `rm -f "$part"`, in two places: after curl exits 0 and the MD5 differs (392), and after the MD5 matches but `gzip -t` fails (397).
- `mv -f` overwrites only its own final file and sidecar. Nothing else is deleted.

**Checksum gates, in order:**
1. MD5 of `.part` equals the pin (385-395).
2. `gzip -t` (396-400).
3. `mv` to the final name (401).
4. sha256 sidecar written atomically (402).

The only success path that skips the MD5 is the early exit when the file matches its sidecar (340), and this script writes a sidecar only after the MD5 check. `--verify`, ER extraction and `--with-release` all re-check the MD5. No success is reported without an MD5 check having passed at some point.

**Failure table from DS-P5-05 (each row checked against the code):**

| Failure | Behaviour, as the code does it |
|---|---|
| curl exit 28 | `.part` kept, exit 28 |
| HTTP 416, curl exit 22 or 0 | `.part` kept, escaped `rm` step printed, exit 22 |
| curl exit 33 | `.part` kept, exit 33 |
| curl exit 63 | `.part` kept, "stop and report", exit 63 |
| Any other curl exit | `.part` kept, exit with curl's code (`fetch-dblp.sh:91` passes it through) |
| Transfer complete, MD5 or `gzip -t` fails | `.part` deleted, exit 1 |
| MD5 tool fails after the transfer | `.part` kept, exit 1 |
| Ctrl-C | `.part` kept |

All match, with two caveats:
- The `*:416` text claims the MD5 is not the pin even when the earlier MD5 attempt failed (NIT-5).
- With `set -u`, every variable in `ds_full_download` is assigned before it is used.

**Shell built from untrusted data:** none. There is no `eval`. The HTTP code goes only into string comparisons and log text, and the printed `rm` path is `%q`-escaped.

**Symlinks:** the script follows them. There is no `-L` check on the output directory, `dest`, `.part` or the sidecar temporary file (MINOR-1).

### 3. Network paths and paths the scripts touch

- **No per-record dblp request and no dblp.uni-trier.de request exists.** dblp items have `urls=[]` (818), `run()` refuses dblp items (1548-1550), and dsutil imports no HTTP client.
- **One dblp.org request remains:** `fetch-dblp.sh --sample` fetches `https://dblp.org/xml/dblp.dtd` (70, from manifest:51). It is not a record, robots.txt does not disallow it, and it is not part of `--full`.
- `--full` contacts drops.dagstuhl.de only, plus any https host it is redirected to.
- No script under `datasets/scripts` refers to `.claude`, `.last-test-run`, `modules/`, `tests/` or `docs/agent-runs` (grep found nothing).

### 4. Cross-split check (#6)

- **It cannot be skipped.** There is no flag. Every early return in `er_verify` either is itself a failure or happens when no records exist. A record that is skipped (missing, not planned, failed content check) is a failure in its own right.
- **It uses identifiers only.** `er_entity_key` keys are OpenAlex A IDs and ORCID iDs. `er_dblp_entity_keys` reads only the `orcid` attribute of author and editor elements; names and homonym suffixes are never used (1704-1714).
- **Gap: ORCIDs that Crossref asserts are not collected** (MINOR-2).

### 5. Labeler visibility (#5) compared with BlindView.scala

What the blind view emits (`BlindView.scala:23-114`):
- per row: the row fields;
- per endpoint: `endpointId` (masked when it is a dblp person or an OpenAlex A/W ID), `sourceDataset`, `sourceRecordId`, `snapshot`, the name fields (homonym numbers stripped), affiliations, institution IDs, identifiers and claimed works;
- per work: `workId`, title, year, venue, authors, identifiers, `eeLinks` and `urls`.

Against the documented list:

| Documented item | Can the blind view show it? |
|---|---|
| `criterion`, `unit_id` | Not emitted. Both are in `HiddenFields` (237-270), and no field is derived from them. |
| `expect_author` | Not copied. The record's own author name is shown with the homonym number stripped (`DblpMasking`). |
| split and unit grouping in file paths | No field carries a file path. |
| `expect_doi` | **The value is shown by design**, as `doi:` identifiers and `ee` links (289-291), because "DOIs stay visible" (168). What can be withheld is the column, meaning which DOI the selection tied to the unit, not the DOI itself. The documentation says "what it reveals: which DOI dblp attaches to the record", and the record shows that anyway. BlindViewSuite also asserts that expect_doi values never appear (NIT-7). |

Not verified: whether `snapshot`, `sourceRecordId` or `workId` of ER-candidate records could carry a split or unit name. That depends on the curated-run configuration (owner entity-resolution-engineer, with hadoop-engineer's run settings).

### FINDINGS

**dataset-engineer**

**MINOR-1. `lib-datasets.sh:95-110, 150-151, 356, 365-368, 392, 397, 401`: the `--full` write path follows symlinks.**
- What happens: `ds_resolve_out` checks only the text of the path. `mkdir -p`, `curl -o <part>` (append mode with `-C -`), `printf > $1.sha256.tmp` and `mv` all follow links.
- Failure input: `datasets/downloads` or `datasets/downloads/dblp/full` is a symlink to a directory outside the repository, or `dblp-2026-10-03.xml.gz.part` is a symlink to an existing file.
- Impact: up to 2 GB written outside `datasets/downloads/`. With `-C -`, curl appends release bytes to the file the link points to. After an MD5 mismatch, `rm -f` removes only the link and leaves the corrupted file.
- Contributing factor: a committed symlink named `datasets/downloads` would not be matched by the directory-only pattern `/datasets/downloads/`.
- The precondition is a symlink planted beforehand, so this is not major. The same library refuses symlinks for the budget ledger (202).
- Fix direction: refuse `-L` on each component, on `dest`, `.part` and the sidecar temporary file.

**MINOR-2. `dsutil.py:1808-1819`: the cross-split check ignores ORCID iDs asserted by Crossref.**
- Only `source == 'openalex'` and `'dblp'` rows are collected. Crossref `author[].ORCID` values are not.
- Committed sample: 13 Crossref ORCID assertions in 8 files (6 dev, 7 heldout). None of them is shared between those two lists (I compared them by hand), but they are never compared with the OpenAlex or dblp IDs of the other split.
- Failure input: a Crossref work in a heldout unit asserts an ORCID that appears only in a dev OpenAlex authorship. Verification passes.
- Impact: dev/heldout separation is incomplete. `er-candidates.md:151` says "Every OpenAlex author ID and ORCID iD is collected", which overclaims.

**NIT-3. N2 is only partly fixed (`dsutil.py:1032, 1035, 1053-1057`, reached through 1657, 1659-1660 and 1674-1679).**
- The release path is `os.path.join(DS_ROOT, …)` and DS_ROOT is absolute. `release-missing`, `release-sidecar-missing` and `release-checksum-mismatch` reasons embed that path, and they are written to FETCH-FAILURES.tsv.
- Failure input: `fetch-samples.sh --sample datasets/er-candidates` before the download. Every dblp row's reason then contains `/Users/<user>/…/dblp-2026-10-03.xml.gz`.
- This contradicts `er-candidates.md:127` and manifest:614 ("FETCH-FAILURES reasons carry no absolute path").

**NIT-4. `dsutil.py:1201-1202`: the N1 docstring premise is false.**
- A row truncated inside its last column keeps all `ncols` columns. Such a row is not dropped; the run stops with exit 2. That is the safe direction.
- But the message (1213-1216) tells the human "if it is a complete row, add the missing final newline". For a PROVENANCE row, the last column is free-text `note`, so a truncated note could be accepted and `--verify` would not notice.

**NIT-5. `lib-datasets.sh:365` and `326` with `359`.**
- (a) curl has no `-q`/`--disable`, so the human's `~/.curlrc` applies (for example `insecure`, `proxy`, `url`, `retry`). The fixture sets `HOME` to a temporary directory, so this is untested. The MD5 pin still guards the content.
- (b) If the MD5 attempt before curl fails, a later 416 prints "its MD5 is not the pin, so it is corrupt" and suggests `rm`. That could make the human delete a good file.

**NIT-6. Fixture gaps.**
- Nit 11 (one-pass hashing) has no case that fails with two passes, and no combined checksum-plus-layout failure case.
- The case-insensitive file-name collision check (`dsutil.py:1462-1469`) has no fixture case, for example two dblp keys that differ only in case.

**NIT-7 (part dataset-engineer, part entity-resolution-engineer). `expect_doi` "must be withheld" conflicts with BlindView showing DOIs.**
- dataset-engineer: `er-candidates.md:40` and manifest:682 present the `expect_doi` value as hidden information. It is the record's own `ee` DOI, which the blind view shows by design (`BlindView.scala:168, 289-291`). The withheld thing should be the column, that is, the unit-to-DOI association.
- entity-resolution-engineer: `BlindViewSuite.scala:332-336` with `selectionLeaks` (203-209) flags any expect_doi substring in a blind line. It passes only because the fixtures do not contain those records. On real heldout dblp excerpts it would report a false leak for correct behaviour.

**entity-resolution-engineer:** NIT-7, second part, as above.

### UNVERIFIED CONCERNS

- I ran nothing. The counts (er-fixture 81/0, manifest validation 1350/0, er-verify 1012/0, 508 distinct IDs) are the agent's.
- How real curl handles 416 on resume (exit 0 is my recollection), DROPS Range support, and curl's default `--max-redirs`.
- Whether the MD5 pin is the authentic DROPS value. The real release's layout, encoding and key coverage.
- The executable bit of `fetch-dblp.sh`, and the HEAD diff, because the files are modified or untracked.
- Whether `snapshot`, `sourceRecordId` or `workId` in the blind view could encode a split or unit name for ER-candidate inputs.
- Whether Crossref ORCIDs overlap OpenAlex or dblp IDs across the splits.
- That Python's gzip reads only through `read`/`readinto` on every Python version used.

### Download command summary for the human

- **Command** (one line, from the repository root):
  `SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full`
  If the script is not executable: `SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 bash datasets/scripts/fetch-dblp.sh --full`
- **What it fetches:** one https GET of `https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz`, about 1.03 GB. Redirects are https-only but may go to any host.
- **Where it writes:** `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part`, then `dblp-2026-10-03.xml.gz` and `.xml.gz.sha256`. These are git-ignored. Nothing else is written or deleted.
- **Bounds:** 30 s to connect; a stall below 1024 B/s for 120 s ends with exit 28 and is resumable; 2 GB cap; no total time limit.
- **Gates, in order:** confirmation variable (otherwise exit 3); `.part` MD5 equals `cbd329100ea1bbb2873fa2f6d414ea00`; `gzip -t`; rename; atomic sha256 sidecar.
- **Failure behaviour:** every curl failure and every 416 keeps the `.part`, prints a RECOVERY line, and exits non-zero (28, 22, 33, 63 or curl's own code). A completed transfer with a wrong MD5 or failing gzip is deleted and exits 1. A failing MD5 tool keeps the `.part` and exits 1. Re-running resumes the download, or skips it if the file is already verified.
- **Residual risks:**
  - It follows symlinks (MINOR-1).
  - `~/.curlrc` applies (NIT-5).
  - It can run for a long time with no total limit.
  - MD5 is the only integrity pin against redirect hosts.
  - The release layout is still unknown until extraction.
  - The later ER `--sample` that extracts dblp records also makes OpenAlex and Crossref network requests in the same call, which is a separate decision.
- **Prerequisites:**
  1. Commit the reviewed `datasets/scripts/**`. `fetch-dblp.sh`, `lib-datasets.sh` and `dsutil.py` are modified and `fetch-er-candidates.sh` is untracked. Committing them makes the bytes you run equal the bytes reviewed, and it lets later agent `--verify` calls pass the script-provenance gate, which requires every file in the script directory to match HEAD. No agent row can run `--full`; only you run it.
  2. Confirm that `datasets/downloads`, `datasets/downloads/dblp` and `datasets/downloads/dblp/full` are real directories or absent, not symlinks (`ls -ld`), and that no stray `.part` symlink exists.
  3. Have about 1.1 GB of free disk.
  4. Check `~/.curlrc` (and any `CURL_HOME`/proxy environment variables).
  5. Have python3, curl, awk, gzip, and md5 or md5sum installed.
  6. Afterwards, run `datasets/scripts/fetch-dblp.sh --verify`, and route the observed size and sha256 to dataset-engineer to record in the manifest.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-07 (Phase 5): independent read-only review of the DS-P5-05 dataset acquisition repairs and of the full dblp download command, for a separate human decision (decisions-09 item 3)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not run (reviewer has no Bash); agent-reported er-fixture 81/0, manifest validation 1350/0, er-verify 1012/0 not observed
VALIDATION RESULTS: by reading code: every DS-P5-05 item was traced to code and to a fixture case (table in section 1); the --full path was traced end to end (gate, single URL, curl options and bounds, resume, 416 with exit 0, MD5 then gzip -t then rename then sidecar, deletion limited to its own .part); no per-record dblp or uni-trier network path, the only dblp.org request is the DTD in --sample; no script touches .claude, the test marker, modules/**, tests/** or docs/agent-runs; the cross-split check cannot be skipped and uses identifiers only; BlindView fields compared with the labeler-visibility list
ASSUMPTIONS: the working tree as read is the DS-P5-05 result; .gitignore /datasets/downloads/ is the rule in force; curl treats a 416 reply to a resume request as success (from memory of curl's source)
RISKS: --full follows symlinks; ~/.curlrc applies; no total time limit; MD5 is the only integrity pin against redirect hosts; Crossref ORCIDs are outside the cross-split check; real release layout unknown
MANUAL ACTION REQUIRED: human decides on the download (separately); human agrees or not to the nit-9 text (accurate as proposed); commit datasets/scripts/** before relying on the provenance gate; check for symlinks and ~/.curlrc before running; decide whether MINOR-1 and MINOR-2 are repaired before acquisition
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator presents the download command summary to the human; optional routing of MINOR-1, MINOR-2 and NIT-3 to NIT-7 (dataset-engineer) and of NIT-7, second part (entity-resolution-engineer)
VERDICT: APPROVE
FINDINGS: dataset-engineer: MINOR-1 lib-datasets.sh:95-110,150-151,356,365-368,392,397,401 the --full write path follows symlinks; MINOR-2 dsutil.py:1808-1819 cross-split check omits Crossref-asserted ORCIDs (er-candidates.md:151 overclaims); NIT-3 dsutil.py:1032,1035,1053-1057 via 1657-1660,1674-1679 release-* reasons write an absolute path into FETCH-FAILURES.tsv (contradicts er-candidates.md:127, manifest:614); NIT-4 dsutil.py:1201-1202,1213-1216 the N1 premise is false and the repair message can accept a truncated note; NIT-5 lib-datasets.sh:365 no -q (~/.curlrc applies), and 326 with 359 the 416 text claims an MD5 mismatch when the MD5 could not be computed; NIT-6 no failing-without-fix fixture for one-pass hashing (nit 11) or the case-insensitive collision check (dsutil.py:1462-1469); NIT-7 er-candidates.md:40 and manifest:682 present the expect_doi value as withheld although BlindView shows DOIs by design. entity-resolution-engineer: NIT-7 BlindViewSuite.scala:332-336 the expect_doi leak assertion contradicts BlindView.scala:168,289-291 and passes only because the fixtures lack those records. All RV-P5-02 #3, #5 (documentation), #6, #8, nits 10-12 and RV-P5-03 N1, N3-N6 verified fixed; N2 partly fixed; nit 9 not edited, proposed text accurate.
UNVERIFIED CONCERNS: agent-reported counts; real curl 416 exit status and default max-redirs; DROPS Range support; authenticity of the MD5 pin; real release layout, encoding and keys; executable bit of fetch-dblp.sh; HEAD diff; whether blind-view snapshot, sourceRecordId or workId encode a split or unit for ER-candidate inputs; Crossref ORCID overlap with OpenAlex or dblp IDs across the splits; gzip module read path on other Python versions
```

---

## RV-P5-08 (code-reviewer) — focused re-review of ER-P5-11 and TE-P5-04, 2026-11-12, verbatim

**RV-P5-08 verdict: APPROVE.** ER-P5-11 and TE-P5-04 have no critical or major defect. I found one new minor defect (an arXiv-version bypass of the decisions-09 block) and three nits. One gap needs a human decision. I ran nothing; I used only Read, Grep and Glob. Test counts are as the human reported them.

### 1. decisions-09 item 1 (er-policy.v7): verified
- **Where the block applies.** `Resolver.scala:488-489` sets `arxivBlocked` (a shared arXiv agreement, and the two sides are not both pure preprints). `:520-521` holds back record keys only when `arxivBlocked && t1a.isEmpty`. `:524-526` removes them from `deciding`. The pair ends at step 6c (`:711-725`): UNRESOLVED, exact-id, 0.0.
- **Codes are recorded only where the block decides.** `recordKeyRules` and `recordKeyReasons` (`:559-565`) are added only in step 6c.
- **Conflict steps unchanged.** Step 2 (`:624-651`) uses `identifying`, which still includes the held-back key. Step 3 (`:652-663`) still sees the held-back key in `t2a`.
- **ACCEPTED never carries conflicts.** Steps 6a and 6b pass no conflicts (`:688-710`).
- **No fallback accepts the pair:**
  - Steps 7 and 8 (bibliographic rules, score) run only when nothing identifying agrees (`:711`).
  - Cluster corroboration needs `identifierCompatible` (`Corroboration.scala:84, 151-152`). Both sides carry the arXiv marker, so it holds only for two pure preprints, where the arXiv ID is not blocked.
  - The LLM is never reached: the path is exact-id (`Adjudication.scala:63`).
- **ER-P5-11's assumption about publication DOIs holds.** A shared publication DOI makes `t1a` non-empty, so step 6a accepts (PolicyV7Suite:287-308; TE controls (a)).
- **What the decisions cover.**
  - decisions-08 covers only "the preprint DOI rule leaves a pair unresolved". That needs a shared DOI agreement whose DOIs all carry a preprint prefix.
  - decisions-09 covers only "a shared arXiv ID leaves a pair unresolved". That needs an exact arXiv agreement.
  - decisions-06 / Option A limits the stage target to inferred and arXiv-based acceptance (`Corroboration.scala:155-159`).
  - None of them covers a record key on a pair with no recognised shared preprint identifier.
- **The gap. It needs a human decision; it is not a finding.** Each of these inputs is ACCEPTED at 0.99 on exact-id through the record key:
  - (a) `{key, arXiv X}` vs `{key, publication DOI}`: stage cross-stage. Pinned as a positive control at `PolicyV7Suite:263-285` and as a labelled characterization in TE (`ArxivRecordKeyScoreGateAdversarialSuite:319-331`).
  - (b) `{key, arXiv X}` vs `{key}`: cross-stage.
  - (c) `{key, preprint DOI}` vs `{key}`: `PolicyV6Suite:217-240`.
  - (d) `{key, arxiv 2101.00001}` vs `{key, doi 10.48550/arxiv.2101.00001, doi 10.1145/y}`: stage uncertain. Here the same arXiv ID is shared in two notations, but they are never related (RV-P5-05 #2).
  - Under labeling Option A these are non-matches, so they would show up as false positives in the gold evaluation.
  - If the human chooses to block them, both pins above must change.

### 2. decisions-08 (er-policy.v6) in v7: still holds
- The preprint-DOI block and its record-key block are unchanged (`Resolver.scala:493-497, 518-519`).
- The PolicyV6Suite regressions still pass under v7. When both triggers apply, both rules are recorded (PolicyV7Suite:242-259).

### 3. m1 score gate
- **The probe is a correct predictor.** `Thresholds` has no constructor check (`MatchPolicy.scala:58`).
  - At −∞/−∞ every finite score is ACCEPTED, and at +∞/+∞ every score is REJECTED.
  - The path is chosen before the thresholds are read, and both caps (blocking disagreement, stage) are independent of the thresholds (`:727-764`).
  - So "ACCEPTED on the probabilistic path ⇔ acceptReachable and score ≥ accept" holds at any threshold pair for the configured policy.
  - `withConfig` changes only the thresholds and the band (`:672-675`). The band is read only in the UNRESOLVED branch, which neither probe reaches.
- **Sensitivity counts only reachable acceptances** (`GoldSetEvaluation.scala:588-595`). Capped rows go to `blockedAccept` (with a per-rule breakdown), `blockedReject` and `gateUnknown`.
- **Gap the probes do not model.** LLM adjudication at other thresholds is not modelled: `Adjudication.eligible` depends on the band (`Adjudication.scala:65`). See NIT-2.

### 4. m6 configuration and settings output: verified
- Both entry points apply the external config through `withConfig`, with no fallback:
  - `GoldSetEvalMain` (`:205-216, 235`).
  - `ReviewQueueMain` queue and diagnostics modes (`:227-239, 306, 322`).
  - No other main-code caller of `loadDefault` exists.
- Every row records its thresholds (`ReviewQueue.scala:231`).
- The evaluation refuses any mismatch in policy, blocking, features, thresholds or band, model or prompt (`GoldSetEvaluation.scala:643-670`).
- The settings line reports the model as "none (no adjudicator ran)" unless a row carries a valid adjudication (`:773`; `Resolver.llmVersions`).
- I found no hardcoded thresholds or model IDs in ER main code (grep).

### 5. N3, N4, #4, #5, nit 2: verified
- **N3.** False-negative examples are drawn in turn from REJECTED, UNRESOLVED, CONFLICT and NOT-GENERATED (`:740-747, 562-566, 769`). `--max-errors` below 10 is refused.
- **N4.** The temp file is written and forced to storage, then linked to the target; a failed run removes the files it created (`ReviewQueueMain:204-219`). The no-hard-link fallback has the disclosed check-then-rename window (NIT-3).
- **#4.** `labelProblems` runs in both `evaluate` and `coverage`, and `coverage` returns `Either`.
- **#5.** `readFileWithSha256` reads once and hashes the parsed bytes (`GoldMembership.scala:444-467`).
- **Nit 2.** The prefix pattern is `^10\.[0-9]{4,9}/$` (`MatchPolicy.scala:402, 589-595`). An empty list still passes (`:588`, `Option(Vector()).nonEmpty`) and still silently disables the preprint-DOI block.

### 6. Row schema v4 and blind view: verified
- The blind view is built from manifest members and run records only (`ReviewQueueMain:112-133`). The system decision is never an input.
- `HiddenFields` includes `thresholds` and `scoreGate` (`BlindView.scala:268-269`).
- I found no path from labels into tuning beyond the procedural risk already noted (thresholds could be tuned by hand from the sensitivity output).

### 7. Tests: legitimate, not weakened
- The D1–D5 tests are present (GoldSetEvaluationSuite:537, DblpMaskingSuite:71/92, BlindViewLeakageAdversarialSuite:287/308, the MatchContract D2/D3 tests).
- **ER's two self-updated tests are justified:**
  - The GoldSetEvaluationSuite false-negative expectation (`:212-218`) now matches N3.
  - The PolicyV6Suite characterization became a regression (`:289-307`), as decisions-09 orders.
- **TE-P5-04's updates keep their behavioural assertions:**
  - The freeze, `--provisional`, 199/200, and TP=159 FP=0 checks are unchanged in substance.
  - Added: refusal without config, refusal of retired configs, refusal of mismatched rows.
- TE's m1 test independently checks every grid point against `Resolver.decide` and is not vacuous (at (0.3, 0.1), blockedAccept = 2).
- The test counts are consistent with the work: 324 + 27 = 351 (entityResolution) and 124 + 5 = 129 (tests).

### 8. Ownership
- ER-P5-11's files are all under `modules/entity-resolution/**`, and TE-P5-04's under `tests/**`.
- `gold/**` exists only under `target/test-work`. No labels or metrics were fabricated; both reports state that no gold evaluation ran.

### Remaining Phase 5 acceptance blockers
- **(a) New:** MINOR-1 (below), NIT-1 to NIT-3, and the human decision on the gap in section 1.
- **(b) Still open for entity-resolution-engineer, all confirmed in code:**
  - RV-P5-04 m7: `er-policy.v7.json:142` still says "versions vN are compared as one value". MINOR-1 is a consequence.
  - RV-P5-04 m8: `TitleMarkup.scala:8` still says NOT WIRED.
  - RV-P5-04 N1 (no `java.nio` ban in LeakageStructureSuite) and N2 (Cf-character bypass of the adjudication delimiters): not touched.
  - RV-P5-05 #2: `Corroboration.scala:181` is unchanged.
  - RV-P5-05 #3: `Adjudication.scala:66` still trusts the stored stage verdict.
  - arXiv rule on ACCEPTED outcomes: `arxivRules` is still in `alwaysRules` (`Resolver.scala:567`).
  - RV-P5-07 NIT-7 (ER part): not reviewed here.
  - TE-P5-01 comma-form homonym: DblpMasking was not changed.
  - The empty preprint-prefix list (from nit 2).
- **(c) Human and process gates:**
  - Gold labeling of at least 200 pairs (none exist), then the held-out gold-set evaluation with the recorded manifest SHA-256.
  - FE-P5-01 ratification and the `goldSetEval` alias (allowlist row still inactive).
  - The full DBLP download is held (DS-P5-06).
  - `sbt check` takes about 121 s, close to the 180 s auto-background limit, and its Hadoop log volume is large. Owner build-engineer, not yet routed.
  - The human decision on the record-key gap in section 1.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-08 (Phase 5): focused read-only re-review of ER-P5-11 (entity-resolution-engineer) and TE-P5-04 (test-engineer)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not run by the reviewer. Human-reported (decisions-10.md): `sbt -no-colors check` core 299/0, entityResolution 351/0, hadoop 67/0, tests 129/0; marker suites=69 passed=846 skipped=0.
VALIDATION RESULTS: items 1–8 verified in code as described above. One new MINOR and three NITs. One human decision point.
ASSUMPTIONS: The working tree I read is the state the human's `sbt check` covered. Untracked files are judged by their content, since I cannot diff. JDK `Files.move` semantics without REPLACE_EXISTING are taken from the JDK documentation.
RISKS:
- Record keys still ACCEPT cross-stage or uncertain-stage pairs that share no recognised preprint identifier (section 1, inputs (a)–(d)).
- MINOR-1 lets a versioned arXiv ID bypass the decisions-09 block.
- `GoldSetEvalMain` refuses to run without `models.llmModelId` even when no adjudication ran.
- Held-out thresholds could still be tuned by hand from the sensitivity output (procedural).
MANUAL ACTION REQUIRED:
- Human: decide whether a dblp-key or scopus-eid may ACCEPT a pair whose stage is cross-stage or uncertain when no preprint identifier is recognised as shared (inputs (a)–(d)).
- Human: decide whether MINOR-1 is fixed with m7 (version-folded arXiv agreement) or inside the stage block.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Route MINOR-1 and NIT-1 to NIT-3 to entity-resolution-engineer (optional for acceptance). Get the human decision on the record-key gap. Then the remaining Phase 5 gates in (c).
VERDICT: APPROVE
FINDINGS:
- MINOR-1 (entity-resolution-engineer). `Resolver.scala:488-489, 516-526`, with `MatchFeatures.scala:219-222` (agreement uses exact equality) and `ExactIdentifiers.scala:89-110` (versions are folded only when checking for disagreement).
  - Input: A = {dblp-key k, arxiv 2101.00001v1}; B = {dblp-key k, arxiv 2101.00001v2, doi 10.1145/y}. The same happens with scopus-eid.
  - Result: there is neither an arXiv agreement nor an arXiv disagreement, so `arxivBlocked` is false and step 6b ACCEPTS at 0.99 with stage `uncertain`.
  - Expected: v7's own text treats versions of one ID as one value (`er-policy.v7.json:142`). decisions-09 then requires UNRESOLVED with `record-key-agreement-blocked-by-arxiv-stage`.
  - Impact: a preprint is merged with its published version through the record key. No test covers it. Exposure is narrow: both sides need versioned arXiv IDs.
- NIT-1 (entity-resolution-engineer). `GoldSetEvaluation.scala:146-174, 772`: the report JSON `settings.configuredModelId` always carries the configured model ID, even when `adjudicationModel` is "none (no adjudicator ran)". A JSON reader could take it for the model that was used. The text settings line is correct.
- NIT-2 (entity-resolution-engineer). `GoldSetEvaluation.scala:451-457, 577-579`: llm-adjudicated rows are in the sensitivity table, but only their score is modelled. LLM band eligibility and adjudication at other thresholds are not represented, and the method text does not say so. No impact today, because no adjudicator runs.
- NIT-3 (entity-resolution-engineer, disclosed). `CreateOnceFile.scala:53-58`: the no-hard-link fallback `Files.move` checks and then renames. A target created inside that window could be replaced. This only applies on filesystems without hard links.
UNVERIFIED CONCERNS:
- `Files.createTempFile` gives POSIX mode 0600, and the hard link keeps it, so manifests, blind views and queues may now be owner-only readable (a behaviour change, harmless). This is from JDK documentation and was not observed.
- I cannot diff, so I cannot confirm that hooks, settings and modules/core were untouched. The git status snapshot is truncated, and the core files it lists come from earlier work.
- How often real data has versioned arXiv IDs, or a record key shared between preprint and published records, is unknown.
- RV-P5-07 NIT-7 (ER part) was not reviewed here.

---

## RV-P5-09 (code-reviewer) — focused re-review of DS-P5-06 and the full dblp download command, 2026-11-12, verbatim

**Verdict: APPROVE.** DS-P5-06 fixes both minor findings (MINOR-1 and MINOR-2), nit 9 and the optional items (NIT-3, NIT-4, NIT-5(a)(b), NIT-7 dataset side). I found no critical, major or minor defect. There are three new nits, all owned by dataset-engineer. I only read files (Read, Grep, Glob) and ran nothing, so every count below is the agent's, not mine.

### 1. MINOR-1: symlinked download paths (`lib-datasets.sh`)
- **Download area is fixed.** `DS_DOWNLOADS="$DS_DIR/downloads"` is assigned unconditionally at `:35`, after `DS_ROOT` is computed. Setting it in the environment, or before the library is sourced, is overwritten. `fetch-dblp.sh` never sets it. Only the fixture overrides it, after sourcing (`dsutil.py:2694`).
- **What `ds_full_refuse_links` checks** (`:367-386`, using `-L`, which never follows a link):
  - that `dest` lies under `$DS_DOWNLOADS/` (the pattern is quoted, so it matches literally);
  - `datasets/downloads` itself;
  - every component below it, including `dest` itself (the loop covers the last segment);
  - `.part`, `.sha256` and `.sha256.tmp`.
  - `ds_resolve_out` already rejects empty, `.` and `..` segments, so the walk cannot be dodged.
- **When it runs.**
  - `:393`: first thing in `ds_full_download`, before any network access, before the `ds_is_current` reads and before the no-sidecar branch (MD5, `gzip -t`, sidecar write at `:401-409`).
  - `:411`: after `mkdir -p`, before the `.part` MD5 read and before curl.
  - `:451`: after curl, before the MD5 read, `rm -f` and `mv -f`.
  - `ds_write_sidecar` (`:154-161`) refuses a linked `.sha256.tmp` or `.sha256`, removes a stale non-link tmp file, and creates the tmp file under `set -C`.
  - The confirmation gate (exit 3) runs earlier, in `fetch-dblp.sh:87`.
- **Reads that happen outside a fresh check** (none of them writes):
  - `ds_bytes_of` (`wc -c`) at `:435` and inside `ds_full_recovery`, after curl and before the `:451` re-check.
  - On a curl failure or a 416 (`:439-448`) the function returns without a third check. That path only reads the size.
  - `fetch-dblp.sh:96-97` reads `dest` and its sidecar after success.
  - No path writes, appends, renames or deletes through a link that existed at check time.
- **Residual check-then-write window.** The agent disclosed this. It is real but narrow:
  1. Between `:411` and the moment curl opens `-o` in append mode. This window includes an MD5 of an existing `.part`, which can take seconds on about 1 GB. A link swapped in here is followed, and curl appends to its target.
  2. Between `:451` and `mv -f`. A swapped link is renamed as a link, and the next run refuses it. `rm -f` removes only the link.
  3. Between the `-L` check and `mv -f` in `ds_write_sidecar`.
  All three need a concurrent process that can already write to `datasets/downloads` as the same user. That process could do the damage directly, so this is acceptable.
- **Not covered by `-L`: hard links** (NIT-A below).

### 2. MINOR-2: Crossref ORCIDs in the cross-split check (`dsutil.py`)
- **Collection.** `er_crossref_entity_keys` (`:1735-1749`) reads only `message.items[].author[].ORCID`. It does not read given, family, name, sequence or affiliation. It normalises through `er_asserted_orcid_key` (`:1713-1719`), which `er_dblp_entity_keys` (`:1722-1732`) now shares. `er_verify` adds the result per split (`:1861-1864`).
- **Shape matches the stored records.** Requests use `/works?filter=doi:…&rows=2` (`:829`), and all 19 stored Crossref files are `message-type":"work-list"`. The `select` list includes `author` (`:685`).
- **Committed sample.**
  - 13 `"ORCID"` values in 8 Crossref files (6 dev, 7 heldout), all in `https://orcid.org/` form with escaped slashes.
  - I spot-checked 3 (0000-0003-3450-7443, 0000-0002-2490-2573, 0000-0001-9871-8771). Each also appears in an OpenAlex record of the same split.
  - That is consistent with the claim that the distinct-ID count stayed at 508.
- **Other ORCID sources in stored records.**
  - OpenAlex works `authorships[].author.orcid` and OpenAlex authors `orcid` were already collected.
  - OpenAlex `ids.orcid` duplicates `orcid`.
  - Crossref `editor` is not selected.
  - dblp `orcid` attributes on author and editor elements are collected.
  - I found no uncollected ORCID source.
- **Documentation is now accurate:** `er-candidates.md:151-157` and `manifest:683`. Older Phase 3 lines (`manifest:390, 410`, "none in the samples") refer to the Phase 3 Crossref sample, not ER candidates, so they are not a contradiction.

### 3. Nit 9 (`selection.tsv`)
- **Line 10** is byte-for-byte the proposal recorded at `agent-report.md:3083`, which the human approved.
- **Line 7** reads "checked against the extracted dblp record".
- I found no remaining "fetched" in lines 1-11.
- **Row digest.** The INFO line (`dsutil.py:1770-1773`) hashes `splitlines(True)` lines that do not start with `#`, so it covers the header and data rows with their line endings, and nothing else. The reasoning holds: the digest was taken once before the edit and twice after, and all three match. I cannot hash, so the digest equality itself is agent-reported.

### 4. Optional items
- **NIT-3: fixed.**
  - Both callers pass `shown=pin['rel']`, which is relative (`:992, :1305-1306, :1439`).
  - Fixture O11 (`:2550-2569`) asserts that the absolute root does not appear in the reason and that the relative directory does. It would fail on the old code.
- **NIT-4: fixed.**
  - The docstring and message are at `:1201-1224`.
  - The fixture asserts the new text (`:2429`).
- **NIT-5(a): fixed.**
  - `-q` is the first curl argument at `:265`, `:430` and `dsutil.py:930`.
  - curl reads its config file unless `-q` is the very first argument, so this disables `~/.curlrc` (from curl's documentation).
  - The fixture checks only the argument order the stub received, for the full download (`:2732`). It does not test real curl or `ds_fetch`/`er_curl`, and `HOME` is a temporary directory.
- **NIT-5(b): fixed.**
  - `pre` is one of match, mismatch, failed or unchecked (`:415-422`); the 416 messages are at `:339-347`.
  - The fixture covers 416 with curl exit 22 and exit 0 under a failing MD5 stub (`:2754-2759`). It would fail on the old text.
- **NIT-7, dataset side: fixed** (`er-candidates.md:40`, `manifest:682`).

### 5. End-to-end trace of `--full`
- **Order of steps:**
  1. `ds_parse_args` and `ds_resolve_out`.
  2. `ds_need python3 curl awk`.
  3. Five local `python3 -I dsutil.py get` reads.
  4. Confirmation check (exit 3).
  5. `ds_need gzip`, `ds_need_md5`.
  6. Link check (exit 2).
  7. Current file: exit 0. No sidecar: MD5, then `gzip -t`, then sidecar.
  8. `mkdir -p`, then the link check again.
  9. `.part` MD5 check before the request (a match skips curl).
  10. One curl call.
  11. Link check again.
  12. MD5 pin, then `gzip -t`, then `mv -f`, then the sidecar.
- **One URL.** The fixture asserts that the `full)` branch has no other curl call.
- **Writes:** only `.part`, the final file, `.sha256.tmp` and `.sha256` under `datasets/downloads/dblp/full/`.
- **Deletions:** only `rm -f "$part"` at `:459` and `:464`.
- **Failure table:** every row in DS-P5-06 matches the code, including exit 33 with an escaped `rm` and the symlink row.
- **No per-record dblp request.** dblp items have `urls=[]` (`:815-819`). The only dblp.org URL is the DTD, used in `--sample`.
- **No script touches protected paths.** Grep finds no `.claude`, `last-test-run`, `modules/`, `tests/` or `docs/agent-runs` reference anywhere in `datasets/scripts`.
- **The DS-P5-06 summary is accurate,** except the `CURL_HOME` remark (see Unverified concerns).

### 6. Fixture
- **Would each new case fail without its fix?**
  - Download root, directory component and wrapper: `mkdir -p` and curl would write into the "outside" directory.
  - `.part` link: the stub's `>>` would append to the victim, and `rm -f` would remove the link.
  - Final-file link: curl would be called.
  - Crossref straddle (0 failures without the fix) and control ("3 distinct IDs" would be 2): both would fail.
  - NIT-3, 416 with a failing MD5 tool, and the NIT-4 text: all would fail on the old code.
  - `-q`: would fail.
  - The agent's caveat stands: none of these was run against the old code.
- **Gap (NIT-C):** every symlink case is stopped by the first check.

### FINDINGS (all dataset-engineer)
- **NIT-A. A hard-linked `.part` or final file is not refused** (`lib-datasets.sh:382-384`, with `:430-433`, `:459`, `:468`).
  - Failure input: `ln /Users/x/other-file datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part` (same filesystem).
  - `-L` is false for a hard link, so curl `-C -` appends up to 2 GB to the outside inode. After the MD5 mismatch, `rm -f` removes only the repository name, and the outside file stays corrupted.
  - Same impact as the old MINOR-1 file case, but git cannot create a hard link, so this needs a local plant.
  - Fix direction: refuse when the link count is greater than 1, or the path is not a regular file.
- **NIT-C. The defence-in-depth checks are untested** (`:411`, `:451`, and `ds_write_sidecar :154-161` including `set -C`). All 7 cases create the link before the call and are stopped at `:393`. Removing those lines would leave the fixture green.
- **NIT-E. `manifest:83` contradicts itself.** It still begins by describing HTTP 416 as "already at least the release length but its MD5 is not the pin… delete the .part by hand". Its own later DS-P5-06 sentence says not to delete when the MD5 could not be computed. The opening clause should be qualified.

### UNVERIFIED CONCERNS
- I observed none of the reported counts: er-fixture 98/0, manifest validation 1350/0 (three runs), 508 IDs, digest `c018a5f1…` over 281 lines, or the SHA-256 values.
- Whether real curl honours `-q`, and with which curl version.
- With `-q`, `CURL_HOME` should have no effect: it only locates the curlrc file, so the "CURL_HOME still applies" remark in `manifest:82` and the DS-P5-06 summary is probably moot. Environment variables that I believe still apply: `https_proxy`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY`, `CURL_CA_BUNDLE`, `SSL_CERT_FILE`, `SSL_CERT_DIR` (trust anchors) and `SSLKEYLOGFILE`. This is from curl documentation as I recall it.
- Bash noclobber: as I recall bash's source, it adds `O_EXCL` only when the target does not exist. A `.sha256.tmp` swapped during the window for a link to a FIFO or device would be opened without `O_EXCL`, so "(O_EXCL)" at `lib-datasets.sh:147-148` and in the manifest is slightly overstated. This only matters inside the race window.
- I cannot diff, so I cannot confirm that only the comment lines of `selection.tsv` and only the header of `fetch-dblp.sh` changed.
- The authenticity of the MD5 pin, DROPS Range support, the real release layout, and the executable bit of `fetch-dblp.sh`.

### Download command summary for the human
- **Command** (one line, from the repository root):
  `SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full`
  If the script is not executable, put `bash ` before `datasets/scripts/fetch-dblp.sh`.
- **What it fetches:** one HTTPS GET of `https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz`, about 1.03 GB. Redirects stay on HTTPS but may go to any host.
- **curl options:** `-q --fail --location --proto =https --proto-redir =https --max-filesize 2000000000 --connect-timeout 30 --speed-limit 1024 --speed-time 120 --write-out %{http_code} -A <UA> -C - -o <part> <URL>`.
- **What it writes:** only `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part`, then `dblp-2026-10-03.xml.gz`, `.xml.gz.sha256.tmp` (briefly) and `.xml.gz.sha256`. All are git-ignored.
- **Bounds:**
  - 30 s to connect;
  - a transfer below 1024 B/s for 120 s is a stall (exit 28) and can be resumed;
  - 2 GB size cap;
  - no total time limit.
- **Gates, in order:**
  1. Confirmation variable (otherwise exit 3).
  2. Required tools present.
  3. Symlink refusal (exit 2) before any network access, repeated before the transfer and before the rename.
  4. A file that already matches its sidecar: exit 0, no network.
  5. MD5 equals `cbd329100ea1bbb2873fa2f6d414ea00`.
  6. `gzip -t`.
  7. Rename.
  8. Atomic sha256 sidecar.
- **Failure behaviour:**
  - Any curl failure or HTTP 416: the `.part` is kept, a RECOVERY line is printed, and the command exits non-zero (28, 22, 33, 63, or curl's own code).
  - The 416 message depends on the earlier MD5 result. Only "mismatch" suggests `rm`.
  - A completed transfer that fails the MD5 or `gzip -t` is deleted (exit 1).
  - If the MD5 tool fails, the `.part` is kept (exit 1).
  - A symlink gives exit 2, and nothing is written through it.
  - A re-run resumes the download, or skips it if the file is already verified.
- **Residual risks:**
  - A link swapped in during the check-then-write window, or a pre-planted hard link (NIT-A), is not refused.
  - Proxy and CA-bundle environment variables still apply.
  - No total time limit.
  - The MD5 is the only integrity pin against redirect hosts.
  - The real release layout is unknown.
  - The later ER `--sample` also makes OpenAlex and Crossref requests in the same call.
- **Prerequisites:**
  1. Commit the reviewed `datasets/scripts/**`, so the bytes you run equal the bytes reviewed and the provenance gate passes.
  2. Optionally check with `ls -ld` that the paths are real directories, and that the `.part` (if present) has a link count of 1.
  3. About 1.1 GB of free disk.
  4. Check the proxy and CA environment variables.
  5. python3, curl, awk, gzip, and md5 or md5sum installed.
  6. Afterwards run `datasets/scripts/fetch-dblp.sh --verify` and route the observed size and sha256 to dataset-engineer.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-09 (Phase 5): focused read-only re-review of DS-P5-06 (dataset-engineer) and the updated full dblp download command (decisions-10 item 1)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not run by the reviewer; agent-reported er-fixture 98/0 and manifest validation 1350/0 (x3), 508 distinct IDs, selection digest c018a5f1... over 281 lines, none observed
VALIDATION RESULTS: by code reading: MINOR-1 fixed (DS_DOWNLOADS fixed in the library; -L on downloads root, every component, dest, .part, .sha256, .sha256.tmp at lib-datasets.sh:393/411/451 plus ds_write_sidecar; no write, rename or delete through a link present at check time); MINOR-2 fixed (Crossref message.items[].author[].ORCID collected and normalised, identifiers only, shape matches the stored work-list files; 3 of the 13 sample ORCIDs spot-checked as also present in same-split OpenAlex records); nit 9 line 10 exact, line 7 "extracted", digest covers exactly the non-comment bytes; NIT-3, NIT-4, NIT-5(a)(b), NIT-7 dataset side fixed; full command traced end to end and the DS-P5-06 summary is accurate; no per-record dblp network path; no script references .claude, the test marker, modules/**, tests/** or docs/agent-runs; the new fixture cases would fail without their fixes
ASSUMPTIONS: the working tree I read is the DS-P5-06 result; curl -q semantics and bash noclobber semantics are taken from documentation and source as I recall them
RISKS: check-then-write window (disclosed); hard-linked .part not refused (NIT-A); re-checks untested (NIT-C); proxy and CA environment variables still apply; no total time limit; MD5 is the only pin against redirect hosts
MANUAL ACTION REQUIRED: human decides on the download; commit datasets/scripts/** before running; optionally check link counts and environment variables
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator presents the download command summary to the human (decisions-10 item 1); NIT-A, NIT-C and NIT-E can optionally be routed to dataset-engineer
VERDICT: APPROVE
FINDINGS: dataset-engineer: NIT-A lib-datasets.sh:382-384 (with 430-433, 459, 468): a hard-linked .part or final file passes -L, so curl -C - appends to the outside inode and rm -f leaves it corrupted (input: ln <outside-file> datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part); NIT-C dsutil.py:2828-2867, 2943-2955: all 7 symlink cases are stopped at lib-datasets.sh:393, so the re-checks at :411 and :451 and ds_write_sidecar :154-161 (set -C) are untested; NIT-E dataset-manifest.yaml:83: the opening 416 clause still says the MD5 is not the pin and to delete by hand, contradicting the later DS-P5-06 'failed' sentence in the same string
UNVERIFIED CONCERNS: agent-reported counts, digests and SHA-256s; whether real curl honours -q; CURL_HOME is probably moot under -q while proxy, CA-bundle and SSLKEYLOGFILE environment variables apply (from curl documentation as I recall it); bash noclobber does not use O_EXCL when the link target exists and is not a regular file, so "(O_EXCL)" is slightly overstated inside the race window; I cannot diff to confirm comment-only edits in selection.tsv and fetch-dblp.sh; authenticity of the MD5 pin, DROPS Range support, real release layout, executable bit of fetch-dblp.sh
```

---

## RV-P5-10 (code-reviewer) — focused review of ER-P5-12 (er-policy.v8) and TE-P5-05, 2026-11-12, verbatim

**RV-P5-10 verdict: APPROVE.** ER-P5-12 (er-policy.v8) and TE-P5-05 carry out decisions-11 items 2 and 3 correctly. I found no critical or major defect: one new minor (provenance) and two nits, all owned by entity-resolution-engineer. I only read files (Read, Grep, Glob) and ran nothing. Test counts are as the human reported them in verification-v8.md.

### 1. Part A: record keys need an established stage (verified)
- **The gate.** `Resolver.scala:585-586`: `recordKeyBlockedByStage = if (!researcher && !stageEstablished && t1a.isEmpty) recordKeyAgreements`. It is combined with the v6 and v7 blocks at `:587-588` and removed from `deciding` at `:589-591`. A pair with nothing else deciding ends at step 6c (`:789-803`): UNRESOLVED, exact-id, 0.0.
- **Where the codes appear.** The v8 rule and reason, plus `publication-stage-<verdict>`, are added only on the 6c outcome (`:626-636`, `:800-801`). `arxivFoldReasons` is the only new always-recorded code (`:639-642`).
- **Conflict steps unchanged.**
  - Step 2 (`:702-729`) still uses `identifying`, which includes the held-back key.
  - Step 3 (`:730-741`) still sees the held-back key in `t2a`.
  - Steps 4 and 5 are researcher-only in practice.
- **ACCEPTED never carries conflicts.** Steps 6a and 6b pass none (`:766-788`). Steps 7 and 8 return an ACCEPTED base only when `bd.isEmpty && !stageBlocks` (`:810`).
- **Researcher pairs are unaffected** (the `!researcher` guard).
- **No fallback can accept a blocked pair.**
  - Steps 7 and 8 are unreachable once `identifying` is non-empty.
  - The LLM needs the probabilistic path and an established stage (`Adjudication.scala:63-66`).
  - A blocked pair is always a work pair with no established stage. Corroboration needs `identifierCompatible` plus venue (`Corroboration.scala:80-86, 151-152`). Given how `PublicationStage.of` works (`:180-186`), that combination means `same-stage-preprint` or `same-stage-venue`, which is not the blocked case.
- **Other schemes.** No other work scheme can ACCEPT on exact-id at a cross-stage or uncertain stage.
  - openalex-work, mag, s2-corpus and s2-paper are `accept-if-corroborated`, so they need corroboration.
  - repository-url is evidence-only.
  - doi and arxiv are T1. A shared publication DOI still decides at any stage (6a), as decided.
- **Is `same-stage-venue` sound?** It is consistent with the ratified Option A model (decisions-06, er-policy.v4), and it needs no preprint marker on either side plus the venue clause. For a preprint to merge with a published version through a record key, all of these would have to hold:
  - the preprint carries no arXiv ID and no 10.48550 DOI;
  - it carries a non-preprint venue that agrees with the published record's venue;
  - it shares the same DBLP or Scopus record key.

  I consider that residual risk, not a defect (see Unverified concerns). Preprint DOIs from other servers (10.1101, 10.2139 and so on) count as "published", but then two different DOIs are a T1 disagreement, so they cannot merge silently.

### 2. Part B: arXiv base-ID comparison, m7 (verified)
- **One normalisation.** `ExactIdentifiers.agreement` (`:127-144`) and `disagreement` (`:165-175`, through `compareCanonical`) both use `comparable` (`:90-97`). Their conditions are complementary, so they can never both hold.
  - v1/v2 and bare/vN agree.
  - Distinct new/new, old/new and old/old IDs disagree, giving step-2 CONFLICT, or REJECTED when the pair is implausible.
- **Originals kept** in `policyAgreements` (`Resolver.scala:529-532`) and in contradicted-agreement values (`:690-696`, which reads `identifying`, built from `policyAgreements`).
- **Agreement is still stage-gated** (`arxivBlocked`, `:545-546`).
- **RV-P5-08 MINOR-1 is now UNRESOLVED** with both the v7 and v8 codes (TE suite `:692-737`; PolicyV8Suite `:497`).
- **Malformed versions.**
  - `V2` (upper case) fails the core regex (`Identifiers.scala:126-127`) and is dropped as absent.
  - `v0` and `v01` pass core and fold to their base. That is harmless, because distinct bases stay distinct.
  - Old-style IDs with a `v` inside the archive name (for example `solv-int/9901001v2`) strip correctly, because the regex is anchored at `$`.
  - Subject-class forms: see NIT-2.
- **Is keeping MatchFeatures unchanged safe?** Yes, as far as decisions go.
  - `id.arxiv.*` is not in the v8 work weights (`er-policy.v8.json:214-228`).
  - Plausibility never reads identifier features.
  - Its only main-code reader is the consistency check in `EndpointIdentifiers.scala:86-104`, which uses the same raw equality as MatchFeatures, so the two stay consistent.
  - BlindView hides `features` and `exactIdAgreements` (`BlindView.scala:257, 267`).
  - The LLM request includes features (`AdjudicationProtocol.scala:146, 164`), but a pair with any arXiv agreement or disagreement never reaches the probabilistic path, so the LLM never sees the stale flag.
  - Sensitivity counts only score-path rows.
  - The flag *is* persisted, though, in review-queue rows and in MatchDecision features (MINOR-1).
- **Not bumping er-features is justified.** Candidate computation is unchanged and Hadoop is unaffected. The policy version is what records the change.

### 3. ER's two extra test changes: legitimate
- **PolicyV3Suite:504-521.** The record-key cases now carry the venue agreement, which is what makes the stage `same-stage-venue`; it is not corroboration (`o.corroboration == None` is still asserted). A new assertion pins `{key}` vs `{key}` with no venue to UNRESOLVED with the v8 reason. This is stronger, not weaker.
- **AdjudicationSuite:386-455.**
  - The v1/v2 case is asserted as ACCEPTED on exact-id and not eligible.
  - The "two preprints" eligible case now uses arXiv vs an arXiv-form DOI, and `stage.established` is asserted for eligible cases.
  - The candidate thunks are a sound workaround for the fixture-key collision.
  - Every negative case is kept.

### 4. TE-P5-05: not weakened, not vacuous
- **ArxivRecordKeyScoreGate.** `assertArxivKeyBlocked` now requires both the v7 and v8 codes. The CHARACTERIZATION at `:326-368` is now a regression: UNRESOLVED exact-id 0.0, CrossStage, v8 rule and reason, no v7 code, no conflicts, 0 LLM calls, valid decision.
- **UnicodeNormalizationRegressionSuite:144-173** asserts the decided behaviour: ACCEPTED 0.99 at same-stage-preprint, the fold reason, `policyAgreements` = both versions, and the candidate unchanged.
- **New suite (RecordKeyStageArxivBaseIdAdversarialSuite):**
  - 96-case loop with a count assertion (`:240`).
  - Low-threshold no-fallback and forged-band refusal.
  - Controls that must still accept: same venue, two pure preprints, publication DOI.
  - T1 and step-3 CONFLICT checks.
  - Revisions, distinct submissions, the contradicted-agreement values, and MINOR-1.
  - Fixtures build `id.*` features and `exactIdAgreements` the er-features.v2 way (`ErFixtures.scala:102-113, 149`), so they are not hand-forged.
  - The 66×66 oracle (`base = replaceFirst("v[0-9]+$","")`) is independent of `comparable`, which uses a different regex plus an `isValidArxiv` guard. A non-vacuity check is at `:632`.
- **Version pins** in MatchContractCrossModuleSuite, MembershipEvaluation and ClusterCorroboration are v8, with v7 added to the retired lists (`:286, 352, 404`; Membership `:555`). D1–D5 and the PolicyV3–V7 behavioural tests are intact.
- **Counts are consistent:** 351 + 17 = 368 and 129 + 10 = 139.

### 5. Versioning
- `FileSchemaVersion` is v8 and `DefaultResource` points to v8.
- v1–v7 are in both retired sets, and v1–v7 resources are kept as history (`MatchPolicy.scala:336-401`).
- No non-test config pins a policy version, and core config is generic.
- The v8 text, including "versions vN are compared as one value" (`er-policy.v8.json:142`), matches the code.
- Prompt v2 already says that arXiv v1 and v2 are one entity (`er-adjudication-prompt.v2.txt:10`), so keeping it unchanged is justified.

### 6. Ownership and evidence
- ER files are under `modules/entity-resolution/**` and TE files under `tests/**`.
- `gold/**` exists only under `target/test-work`.
- Both reports state that no gold evaluation ran. No labels or metrics were fabricated.

### Remaining Phase 5 acceptance blockers
- **(a) New:** MINOR-1, NIT-1 and NIT-2 (below). None of them blocks acceptance.
- **(b) Still open for entity-resolution-engineer:**
  - **RV-P5-05 #2:** open. `er-policy.v8.json:137` says arXiv IDs and arXiv-form DOIs "are not compared"; `AdjudicationSuite:391` relies on that. The record-key exposure (input (d)) is closed by Part A. A missed T1 disagreement (`{arxiv X}` vs `{doi 10.48550/arxiv.Y}`) remains.
  - **RV-P5-05 #3:** open (`Adjudication.scala:66`).
  - **RV-P5-04 m8:** open (`TitleMarkup.scala:8` still says NOT WIRED).
  - **RV-P5-04 N1 and N2:** ER says untouched; I did not re-verify.
  - **RV-P5-08 NIT-1:** open (`GoldSetEvaluation.scala:155`). NIT-2 is done (`:458`). NIT-3 is open.
  - **Empty preprint-prefix list:** open (`MatchPolicy.scala:595`).
  - **arXiv rule on ACCEPTED outcomes:** open (`Resolver.scala:613, 644`). Input: `{arxiv X, doi D}` vs `{arxiv X, doi D}` (both mixed) is ACCEPTED through the DOI, with `exact-id.unresolved.arxiv-agreement-cross-stage` in `rulesFired`.
  - **RV-P5-07 NIT-7 (ER part) and the TE-P5-01 comma-form homonym:** not reviewed here; still open per the reports.
- **(c) Human and process gates:**
  - Commit the dataset checkpoint and run the full DBLP download (download-checkpoint-01.md).
  - Gold labeling of at least 200 pairs, then the held-out gold-set evaluation.
  - FE-P5-01 ratification and the `goldSetEval` alias (allowlist row inactive).
  - `sbt check` now takes 166 s against the 180 s limit, and the Hadoop log volume is large. Owner build-engineer, not yet routed.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-10 (Phase 5): focused read-only review of ER-P5-12 (er-policy.v8) and TE-P5-05 against decisions-11 items 2 and 3
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not run by the reviewer. Human-reported (verification-v8.md): sbt -no-colors check core 299, entityResolution 368, hadoop 67, tests 139; 873 passed, 71 suites, 0 failed/errored/skipped; marker consistent per orchestrator
VALIDATION RESULTS: items 1-6 verified in code as above; Part A gate at Resolver.scala:585-591 with no accepting fallback; Part B one normalisation (ExactIdentifiers.scala:90-175); tests legitimate and not vacuous; versioning and policy text accurate; ownership consistent
ASSUMPTIONS: the working tree I read is the state the human's sbt check covered; arXiv subject-class identifier semantics (NIT-2) are from arXiv documentation as I recall it
RISKS: same-stage-venue relies on extracted stage markers (a preprint record with no arXiv/10.48550 marker but a matching non-preprint venue and a shared record key would be accepted); preprintDoiPrefixes is only 10.48550/; review-queue rows and MatchDecision carry a stale id.arxiv.conflict=1 next to exact-id.agree.arxiv (MINOR-1); sbt check duration near the 180 s limit
MANUAL ACTION REQUIRED: human decides whether MINOR-1 is fixed before gold labeling (labelers do not see it; auditors do); remaining gates in (c)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: optionally route MINOR-1, NIT-1 and NIT-2 to entity-resolution-engineer; then the human gates in (c) (dataset checkpoint and download, gold labeling and held-out evaluation, FE-P5-01 ratification and goldSetEval alias, build-engineer sbt check duration)
VERDICT: APPROVE
FINDINGS:
- MINOR-1 (entity-resolution-engineer; a MatchDecision field would be domain-engineer). The folded arXiv agreement is never persisted. ResolutionOutcome.policyAgreements (Resolver.scala:155, 687) is in memory only: Grep finds no reader outside Resolver and tests. ReviewQueue.scala:217-218 writes o.exactIdAgreements and o.features, and Resolver.toMatchDecision (:1011-1030) writes features. Input: A={arxiv 2101.00001v1}, B={arxiv 2101.00001v2}, two pure preprints -> ACCEPTED exact-id 0.99 with rule exact-id.agree.arxiv, while the stored row has exactIdAgreements=[] and features id.arxiv.agree=0, id.arxiv.conflict=1. Impact: the stored evidence contradicts the deciding rule. The original values used are recoverable only by re-reading the endpoint records, so "originals kept in policyAgreements" (ER-P5-12 report, Resolver Scaladoc :395) holds only in memory. Fix direction: add the policy agreements to the review row (row schema v5), or document that provenance is the endpoint records.
- NIT-1 (entity-resolution-engineer). ExactIdentifiers.scala:150-157: agreesOnlyByComparable is false whenever any value is shared byte for byte. Input: A={2101.00001v1, 2101.00002}, B={2101.00001v2, 2101.00002} -> no arxiv-agreement-by-base-submission-id reason, although 2101.00001 agrees only through the fold. The outcome is correct; the reason code under-reports. The TE "overlap" case (:502-509) covers only a same-base overlap.
- NIT-2 (entity-resolution-engineer, pre-existing before v8). Old-style subject-class forms: comparable("math.AG/0101001") != comparable("math/0101001"), so the pair is a T1 arXiv disagreement: CONFLICT when plausible, REJECTED otherwise. arXiv treats these as one submission (from arXiv documentation as I recall it). Safe direction (never a merge), but a false conflict. Not tested. er-policy.v8.json:142 is silent on it.
UNVERIFIED CONCERNS:
- I cannot diff, so I cannot confirm that hooks, settings, core and prompts are untouched in this round. The git status snapshot is truncated, and its modified core files predate this work order.
- How often real records lack stage markers while sharing a record key and a venue (same-stage-venue residual) is unknown.
- Whether the subject-class equivalence (NIT-2) occurs in the selected datasets is unknown.
- RV-P5-04 N1 and N2, RV-P5-07 NIT-7 (ER part) and the TE-P5-01 comma-form homonym were not re-verified here.
- TE's count of 13 stale tests is taken from its report; I did not enumerate the pre-change failures.
```

---

## RV-P5-11 (code-reviewer) — focused review of DS-P5-09 (dblp record-boundary fix), 2026-11-13, verbatim

**Verdict: APPROVE.** I found no critical, major or minor defect. There are four nits, all owned by dataset-engineer. I only used Read, Grep and Glob and ran nothing. Every count, digest and run result below is the agent's, not something I observed. I could not diff against `5e324bd`/`4c3ce6a`, so "unchanged" claims are judged from the current code.

### 1. Boundary correctness (`_DblpScanner`, `datasets/scripts/dsutil.py:1095-1191`)

**Where a record's bytes start and end.**
- The start is `off + m.start()`, the `<` of the keyed start tag (`:1145`, `:1158`).
- The first chunk is `line[s:]` and continuation lines are taken whole (`:1166`). The last chunk is `line[chunk:stop]`.
- `stop` is the end of the line, which includes `\n` or `\r\n` (`:1169-1170`, `ER_LINE_ENDS`). Otherwise it is `e`, the position just after the `>` of the end tag, when a record open tag follows directly (`:1171-1172`).
- So a record is exactly "`<` of the start tag through the `>` of the first `</type>`, plus the directly following line ending". It is contiguous, and the offsets are decompressed-stream offsets (`self.off` adds up `len(line)`, `:1130`).
- Header and footer handling is the same as described: header lines up to the `<dblp>` line, footer is the last non-blank line.
- `--with-release` (`:1505-1525`) reads the same decompressed stream by offset, so it is consistent.

**Cases checked:**
- **End tag of a different element.** The end tag is searched as `</` + its own type (`:1161`). Any record open tag before that end tag is refused (`:1163`). For a wanted `<www>`, a line like `</article><article key=...>` is refused, never cut.
- **Inner element that looks like a record.** `ER_DBLP_OPEN_TAG` needs white space or `>` after the type name, so `<booktitle>` does not match. A real inner record start is refused.
- **Key attribute with `>` or quotes.** `[^"]+` ends the key at the quote. A `>` in an earlier attribute makes `[^>]*` fail, so the key is reported absent rather than cut wrong (NIT-1).
- **Malformed cuts.** `er_check_content` (`:850-857`) parses the excerpt as XML and requires exactly one record with the key, before any write and again in `--verify` (`:1930`). A wrong cut would fail as not well-formed or as the wrong record count.
- **BustosFAR12.** Its failure is "no author", so its slice parsed as one well-formed record with the right key. That points to a selection-fact problem, not a scanner problem.
- **`</dblp>` footer.** `</x></dblp>` after a wanted record fails with "text follows". After an unwanted record it fails in `finish()`, because the last line's stripped text is not `</dblp>`.
- **Streaming.** The scan iterates over whole lines from `GzipFile`, so a gzip block boundary cannot split a tag. A start tag split across lines is not matched, so the key is reported absent (NIT-1). That cannot happen on the pinned release: all 120 keys were found (agent-reported).
- **`\r\n` line endings** are handled by `ER_LINE_ENDS`. A bare `\r` before the next start tag is refused.
- **ISO-8859-1 bytes.** Everything is compared as bytes, and keys are ASCII.
- **Nesting assumption.** The code assumes records do not nest, and enforces it for wanted records (`:1163`). Unwanted records are not validated, but they are never cut.
- **CDATA and comments** are not modelled. See Unverified concerns.

### 2. Fail-closed behaviour
- **Accepted start positions.** Exactly three:
  - `s == 0`;
  - `s == boundary`, where `boundary` is set only after a wanted record ended at `e` with `ER_DBLP_OPEN_TAG.match(line, e)`, so this rule is sound;
  - `ER_DBLP_END_TAG.fullmatch(line, 0, s)`.
  Anything else raises at `:1153`.
- **After the end tag**, only the line end or a directly following open tag is accepted. Anything else raises at `:1174`.
- **Duplicates, an unclosed record, a missing `<dblp>` and a missing `</dblp>`** all raise.
- **Order of checks.** `ErReleaseError` from the scan is held in `read_err` and raised only after the MD5 and then the SHA-256 checks (`:1065-1079`). In `er_dblp_step` (`:1385-1418`) a refused release returns every item failed, before any write.
- **Unchanged and still in place:** the 200000-byte cap (`ER_CAPS :691`, `:1224`), the content checks (`:1229`), the 8 MiB budget checked before each write (`:1403`), and the write order .part, re-hash, `os.replace`, ranges row, PROVENANCE commit (`:1347-1375`).
- **Offline end-tag check** (`:1493-1501`): it requires `rec` to end with `</type>` plus an optional line ending. A slice that spans two records still passes this check, but the content check in `--verify` (exactly one record) catches it.

### 3. `cmd_er_inspect` and `--dry-run` with the release
- **Writes and network.** It reads the manifest, selection and ledger (`er_read_ledger` does no torn-row repair) and the release. I found no write call and no network call on its path.
- **Shell routing writes nothing.** `fetch-samples.sh:40-50` takes the 3-argument branch before the `--sample` budget-ledger code, so it never reaches `mkdir` or `printf >`.
- **Release path argument.** It must equal the manifest-pinned path (`:45-46`). If `ds_manifest_get` fails, `pinned` is wrong and the run dies, so this is fail-closed.
- **Nothing is printed before the gate.** All output comes after `er_dblp_read_release` returns, that is, after both digests match.
- **Output is bounded and escaped.** It prints at most 3 examples per class, 3 windows, and one line per selected key, all through `er_escape`. Every byte that is not printable ASCII, and the backslash, is shown as `\xNN`, so no control bytes reach the terminal.
- **Allowlist shape.** `<DSSCRIPT> --dry-run <PATH:datasets/>*` fits, with git-ask provenance until the scripts are committed.

### 4. Fixture (`_er_fixture_shared_lines`, `:2527-2670`)
- **It uses the real code.** The positive case runs the real `er_dblp_step`, and the `_DblpScanner` class check runs the real scanner. Offsets are compared with an independent `_fx_tag_span`, and the end-to-end check runs `er_fetch`, `er_verify` and `--with-release`.
- **The precondition is sound.** All 4 wanted tags are not preceded by a newline, and only 8 newlines differ from the realistic layout (`:2537-2543`). The old line-start rule refuses exactly this layout, so the positive case would fail on the old code.
- **The 7 negative cases are real refusals** through `_fx_expect_refusal`: every item fails with `release-layout-error`, the expected text appears, and `datasets/samples/er-candidates` is never created. Each case also asserts that its replacement actually applied (`:2665`).
- **The inspection test** compares a file walk before and after (`:2622-2625`).
- **The existing `tag-not-at-line-start` case** still matches the new message.

### 5. Crossref miss and BustosFAR12
- **Crossref miss.** It is recorded separately and accurately in `dataset-manifest.yaml:614-625` and `:726-727`, and in `er-candidates.md:212-225`. It matches `extraction-failure-01.md` section B. There is no retry and no substitution, and the OpenAlex row is explicitly not a substitute.
- **BustosFAR12.** It is recorded honestly (`manifest:729-731`). `selection.tsv:25` still has its expect_author. I cannot diff `selection.tsv` or `split-assignment.tsv`, but git status shows no change to them beyond the existing untracked state.

### 6. Pin and scope
- **Pin unchanged.** The URL (`manifest:45`), snapshot id (`:70`) and MD5 (`:109`) are as before.
- **No new network path.** The only curl call is the existing `er_curl`.
- **No protected references.** A grep of `datasets/scripts` finds no `.claude`, `last-test-run`, `modules/`, `tests/`, `docs/agent-runs`, `urlopen` or `socket`.
- **No writes outside the allowed areas.** The fixture writes only to a temporary directory, and `--sample` writes only under `datasets/`.

### Files for the new script checkpoint
- **Required.** The provenance gate needs all of `datasets/scripts/**` committed and identical to HEAD. The modified ones are:
  - `datasets/scripts/dsutil.py` (agent sha256 `b70cd144…3594`)
  - `datasets/scripts/fetch-samples.sh` (`055166b7…b354`)
  - `datasets/scripts/fetch-er-candidates.sh` (`904620c1…c42f`)
  - The other 7 files in `datasets/scripts/` are shown unmodified by git status. Check with `git status -- datasets/scripts` that nothing there is untracked.
- **Recommended in the same commit** (they document the reviewed rule):
  - `datasets/dataset-manifest.yaml` (`10c1b568…4310`)
  - `datasets/schemas/er-candidates.md` (`6645d751…2998`)
- **Your decision.** `datasets/er-candidates/` (`selection.tsv`, `fixture/fixture-release.xml`) is still untracked, and the extraction and fixture read it.
- **Do not include** `datasets/samples/er-candidates/FETCH-FAILURES.tsv`. The retry rewrites it, and its evidence is kept in `extraction-failure-01.md`.
- **Check the hashes yourself.** Compare these SHA-256 values with `shasum -a 256` before committing. I could not compute them.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: RV-P5-11 (Phase 5): focused read-only review of DS-P5-09 (dataset-engineer), dblp record-boundary fix
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not run by the reviewer; agent-reported er-fixture 120/0, manifest validation 1350/0, inspection "scanner accepts the release layout; 120 dblp keys: excerpt-check-failed 1, ok 119"; none observed
VALIDATION RESULTS: by code reading: record slice = "<" of keyed start tag through ">" of first own end tag plus directly following line ending, contiguous decompressed offsets; three accepted start positions, everything else release-layout-error; scan error held until after MD5 then SHA-256 gate; cap, content check, 8 MiB budget and all-or-nothing write order intact; er_check_content (exactly one well-formed record with the key) backs every cut before write and in --verify; inspect path has no write or network call and prints only after the gate, escaped and bounded; positive fixture would fail on the line-start rule, the 7 negatives are real fail-closed checks; Crossref miss and BustosFAR12 recorded separately and accurately; pin/URL/MD5/snapshot unchanged; no protected-path references
ASSUMPTIONS: the working tree is the DS-P5-09 result; dblp.xml has no comments/CDATA and one start tag per line (agent observation "No line holds two start tags")
RISKS: 357 start tags after other bytes in the release would refuse the whole release if later selected (fail closed); BustosFAR12 will fail excerpt-check-failed in the retry; the retry requests the Crossref URL once more; the release-layout-error text now embeds up to 120 escaped release bytes in every FETCH-FAILURES row
MANUAL ACTION REQUIRED: human verifies the SHA-256s of the checkpoint files, commits datasets/scripts/** (plus manifest and schema notes as recommended), then retries --sample
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human script checkpoint commit, then extraction retry and "--verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz"; nits optionally routed to dataset-engineer
VERDICT: APPROVE
FINDINGS: dataset-engineer:
  NIT-1 dsutil.py:1100-1102 and er-candidates.md:104-106 overstate "a wanted record can never be reported absent because of where its tag sits". A start tag split across lines (input: '<article mdate="2020-01-01"\n key="conf/x/Y">'), key='...' in single quotes, or a '>' inside an earlier attribute does not match ER_DBLP_ANY_START (:976), so the key is reported key-not-in-pinned-release, not refused. Impact on the pinned release: none (all 120 keys found, agent-reported). Fix direction: qualify the claim, or refuse a line holding key="<wanted>" without a matching start tag.
  NIT-2 agent-report.md:3631: the start classes do not add up. 57 after-end-tag + 56 line-start = 113 of 119 cut records; 6 are unaccounted for (probably after-wanted-record). Report accuracy only.
  NIT-3 dataset-manifest.yaml:644: fixture.verified_on still says "2026-11-12 (DS-P5-06 additions...)", although covers (:643) now lists DS-P5-09 cases verified 2026-11-13. Stale record.
  NIT-4 dsutil.py:2592-2595: if the positive shared-lines case regresses, rng[items[1]['rel']] / open() raise KeyError or FileNotFoundError and abort the whole fixture (still non-zero, via cmd_er_fixture :3394-3397), so later checks are not reported. Guard these lines on the earlier check passing.
UNVERIFIED CONCERNS: every agent count, digest and the five SHA-256s; that the old scanner would have reported a key after a whole same-line record as absent (er-candidates.md:105-106; I cannot read the old code); that dblp-2026-10-03 has no XML comments or CDATA holding record-like tags (not modelled, but the MD5 pin plus the per-excerpt XML parse limit the impact); the cause of the BustosFAR12 author mismatch; that selection.tsv and split-assignment.tsv are byte-unchanged (I cannot diff untracked files); that the other datasets/scripts files equal HEAD.
```

---

## CR-P5-07 (code-reviewer): focused leakage gate review at f952eff, 2026-11-15, verbatim

Orchestrator note: the work order asked for a read-only review of the Phase 5 gate item "the reviewer verifies no data leakage from evaluation labels into matching logic" (README.md at d23d307) at HEAD `f952eff83888b6fb786062de49ae204e056be3e8`. The human chose this focused pass ("(ii)") and approved the work order as written. The report below is the agent's final message, copied verbatim from its handback. Nothing was routed: the two minor findings stay open until the human decides whether to defer them (to be recorded in `human-gate.md`). The repository-wide Grep the reviewer disclosed matched only held-out *pipeline* paths under `modules/hadoop/target/**`, which stay agent-readable under decisions-22; no held-out label file or held-out evaluation output was opened.

```text
I found no leak from evaluation labels into matching logic at f952eff. Verdict: APPROVE for this gate item. There are no critical or major findings. There are two minor test-strength findings, and neither one is a real path to the labels today.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P5-07 (Phase 5). Focused review of the gate item "no data leakage from evaluation labels into matching logic" at HEAD f952eff83888b6fb786062de49ae204e056be3e8.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only). One Grep with path gold/entity-resolution/heldout-v1/er-config.conf was refused by guard-dangerous.sh (heldout-label-read). I then read that config by exact path, as the guard allows. One repository-wide Grep for "a961035|71ff551" with glob {modules,datasets,project,tests}/** also matched paths under modules/hadoop/target/**. Disclosure: the output showed the file names and the omitted-line markers. No target/ content was printed, and none was used. No held-out labels file, held-out evaluation output, .env or secret was opened.
TEST RESULTS: not run by the reviewer. I relied on the human-recorded clean-tree sbt check at f952eff: exit 0, 921 passed, 0 failed, 1 skipped (docs/agent-runs/phase-05/test-report.md).
VALIDATION RESULTS:
Q1. Can matching logic read gold, labels, the queue, blind views or evaluation results? No.
- File and stream access. In the matching path (root package, identifiers/, policy/, adjudication/), the only java.io/java.nio use is as follows:
  - MatchPolicy.scala:637-644: resourceText reads a classpath resource through getResourceAsStream. It is called only with fixed constants:
    - DefaultResource = "/scholarchain/entityresolution/policy/er-policy.v8.json" (MatchPolicy.scala:380), via loadDefault (:647-648).
    - The prompt and response-schema resources (AdjudicationProtocol.scala:104, :113, :208-211).
  - ConfigEvidence.scala:3, :47: Path is used only to normalize the path string of a config file the loader has already read.
  - There is no scala.io, no Files.* call and no sys.props in the matching path.
- Classpath. gold/ is not on any classpath. build.sbt has no gold resource mapping: its only gold mentions are the goldSetEval tasks at :50-58, :202-203 and :375-377. Only resource files under modules/entity-resolution/src/main/resources are packaged.
- Environment and config. The only env access is ConfigEvidence.system() (ConfigEvidence.scala:62). Its only callers are GoldSetEvalMain (:360) and ReviewQueueMain (:336), not Resolver, Scoring or features. The entityResolution config record has six fields only: policyVersion, acceptThreshold, rejectThreshold, llmBandLower, llmBandUpper and promptVersion (modules/core/.../ScholarChainConfig.scala:85-92). No key names a labels path. MatchPolicy.withConfig (MatchPolicy.scala:659-686) uses only those fields.
- Class references:
  - Matching-path imports are limited to core, policy, identifiers and the root package. Resolver.scala:8-22, Adjudication.scala:6-7 and AdjudicationProtocol.scala:8 import only policy.
  - Labels are read only in evaluation: GoldSetEvalMain.scala:108 and :393-397, and GoldLabels.
  - Evaluation never calls Resolver. It consumes queue rows that were already decided (GoldSetEvaluation.scala:291 describes it as a pure function of queue rows and labels).
- Hadoop. modules/hadoop/src/main has no reference to gold, review or evaluation. It imports only scholarchain.entityresolution.EntityResolution plus CandidateJob's root-package imports (CandidateJob.scala:27, BlockingJob.scala:27, Pipeline.scala:25).
- Build. project/LocalRuns.scala passes the labels path only to goldSetEval (:902-914). The pipeline refuses to run while the labels file exists (allow set at :263-280).
- Prompt v2 shares the publication-stage target definition with er-label-guide.v1 (AdjudicationProtocol.scala:97-98). That is a shared definition, not label data.

Q2. Do the policy values derive from labels? No. They are fixed constants.
- er-policy.v8.json:
  - "calibrated": false (:6).
  - thresholds accept 0.85 and reject 0.2 (:22).
  - llmBand 0.4 to 0.7 (:23).
  - promptVersion "er-adjudication-prompt.v2" (:28).
  - Weights and caps are hand-set; the confidenceDefinition at :7 says so.
- gold/entity-resolution/heldout-v1/er-config.conf:34-39 has the same values: policyVersion er-policy.v8, 0.85, 0.2, 0.4, 0.7, er-adjudication-prompt.v2. Its line 3 says they are "the values of the default policy er-policy.v8.json, unchanged".
- Timeline. a961035 (policy v8) comes before the held-out membership freeze (01f1799) and before the labels (labeledAt 2026-11-15). Dev labels do not exist (D4). So no labels existed to fit against.
- No calibration path:
  - The only file writer in any module's main code is review/CreateOnceFile.scala:44-57.
  - "calibrat" appears in main code only in documentation strings.
  - GoldSetEvaluation.sensitivity (:571-610) only reports metrics at alternative thresholds. It writes no policy, threshold or feature.

Q3. Does LeakageStructureSuite enforce Q1? Only partly. It checks names, not I/O.
- It bans "evaluation" and "review" as words and ReviewQueue/ReviewQueueRow/SystemDecision in the matching path (LeakageStructureSuite.scala:63-75).
- It bans the gold-label type names and the literal "er-gold-label" outside evaluation (:43-51, :77-82).
- It checks that the default policy text has no "gold", "label" or "review" (:163-167).
- It has no ban on java.nio, java.io, scala.io, getResource, Files, sys.env or a "gold/" path literal. It also scans only modules/entity-resolution main, not hadoop.
- RV-P5-04 N1 is therefore a test-strength gap only, not a leak: as Q1 shows, no such I/O exists in matching code today.

Q4. Any reason to doubt that the approved code is the committed code? None visible.
- 71ff551 changed only modules/hadoop/local-runs/er-candidates.{dev,heldout}.run-manifest.json. Both now pin codeVersion a961035e8863a1c4b3337557bb17fbab05a1182a (:4).
- 01f1799 and f952eff touched gold/ only, per the orchestrator's commit list.
- No matching-logic file changed after a961035.
ASSUMPTIONS:
- The commit file lists in the work order are accurate.
- The working tree equals HEAD for modules/**, project/** and build.sbt.
- "Matching logic" means entity-resolution main code outside review/ and evaluation/, plus the hadoop callers of EntityResolution.
RISKS:
- GoldSetEvaluation.sensitivity prints held-out metrics at alternative thresholds to the human Terminal. That invites manual tuning after the held-out result. This is a governance risk covered by D3(c): any policy, feature or threshold change after a held-out result needs disclosure and a fresh held-out set. It is not a code leak.
- MatchPolicy.resourceText is private[entityresolution] but takes any path string. Today only constants are passed.
MANUAL ACTION REQUIRED: none for this verdict. Recommended: when accepting, keep honoring D3(c) after reading the held-out sensitivity output.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human records this gate item's verdict in docs/agent-runs/phase-05/human-gate.md. The orchestrator may route the two minor test-strength findings to entity-resolution-engineer and test-engineer in a later work order (not required for this gate).
VERDICT: APPROVE
FINDINGS:
- entity-resolution-engineer:
  - [minor] modules/entity-resolution/src/test/scala/scholarchain/entityresolution/LeakageStructureSuite.scala:63-82
    - Problem: the structural leakage test checks names only. It has no ban on file, resource or env access, or on a "gold/" path literal, in the matching path. This restates RV-P5-04 N1.
    - Failure input: add to policy/Scoring.scala
      `scala.io.Source.fromFile("gold/entity-resolution/heldout-v1/" + "er-" + "gold-labels.jsonl").mkString`
      or a `sys.env` lookup of a labels path. None of the banned tokens appears, so every test in the suite still passes.
    - Impact: a future label leak into scoring would not be detected by sbt check. No such code exists today (Q1), so this is a test-strength gap and not a leak.
    - Suggested direction, not a fix: in matching-path sources, also ban `java.io.File`, `java.nio.file`, `scala.io`, `Files.`, `sys.env`, `System.getenv`, `getResource` (except at MatchPolicy.scala:638), and the literal `gold`.
- test-engineer:
  - [minor] tests/** (no file today)
    - Problem: no cross-module leakage test covers modules/hadoop/src/main, which calls the entity-resolution matching path (CandidateJob.scala:27, BlockingJob.scala:27, Pipeline.scala:25).
    - Failure input: a hadoop job that reads gold/entity-resolution/heldout-v1/er-gold-labels.jsonl through the Hadoop FileSystem and filters candidates on it. No test fails.
    - Impact: today modules/hadoop/src/main has no gold, review or evaluation reference (verified by Grep), so this is a test-strength gap only.
UNVERIFIED CONCERNS:
- I could not confirm by git that CR-P5-05 and CR-P5-06 reviewed byte-for-byte the a961035 content. I relied on the orchestrator's commit lists.
- er-policy.v8.json:164 cites "7 of 8 sampled preprint-versus-published units". I did not trace which sample this came from. With no dev labels (D4) and the held-out membership frozen after a961035, it cannot be a gold label. It may come from unlabeled sample inspection, which is not label leakage.
```
