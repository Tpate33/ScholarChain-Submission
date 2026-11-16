# Phase 5 download checkpoint: files to stage and preflight commands (orchestrator assembly)

Prepared 2026-11-12 per decisions-11.md item 1. Facts below come from `git ls-files --others --exclude-standard -- datasets`, `git diff --stat -- datasets` and `git ls-files -- datasets/scripts`, run by the orchestrator at preparation time, and from the DS-P5-06 and RV-P5-09 reports. The human reviews, stages and commits; no agent commits.

## Why these files

`guard-dangerous.sh` script provenance requires every file in the script directory (`datasets/scripts/**`) to be committed and byte-identical to HEAD, with no untracked, ignored, deleted or symlinked entries. `fetch-dblp.sh --full` also reads the download URL, MD5 pin and snapshot id from `datasets/dataset-manifest.yaml`, so the manifest is part of the reviewed bytes.

### Required for the provenance checkpoint (6 files)

| Path | Git state | Reviewed in |
|---|---|---|
| `datasets/scripts/dsutil.py` | modified | RV-P5-02, RV-P5-03, RV-P5-07, RV-P5-09 |
| `datasets/scripts/lib-datasets.sh` | modified | RV-P5-02, RV-P5-03, RV-P5-07, RV-P5-09 |
| `datasets/scripts/fetch-dblp.sh` | modified | RV-P5-02, RV-P5-03, RV-P5-07, RV-P5-09 |
| `datasets/scripts/fetch-samples.sh` | modified (30 lines changed; earlier Phase 5 dataset work, not changed by DS-P5-05/06) | RV-P5-02 (as part of the DS-P5-01/03 changes) |
| `datasets/scripts/fetch-er-candidates.sh` | untracked (must be added, or the provenance gate refuses) | RV-P5-02 |
| `datasets/dataset-manifest.yaml` | modified | RV-P5-02, RV-P5-07, RV-P5-09 |

Unmodified tracked files already in the script directory (no action): `fetch-crossref.sh`, `fetch-openalex.sh`, `fetch-orcid.sh`, `sample-seeds.tsv`, `validate-manifest.sh`.

SHA-256 values reported by dataset-engineer after DS-P5-06 (agent-reported, for your comparison):

| File | SHA-256 |
|---|---|
| `datasets/scripts/dsutil.py` | `b28e6edfe29a697940d4cdbbe923ed70cd1e55560af148469c2fabe9b5ecad61` |
| `datasets/scripts/lib-datasets.sh` | `5e20b42c324b86d01162e8447d6a5cf6d0bc20900c7282ac89e719a6a184d833` |
| `datasets/scripts/fetch-dblp.sh` | `89a7d07d884628608fd4fd5d7276ef0dffe485f343202b6448d3739317b20e0e` |
| `datasets/dataset-manifest.yaml` | `dd249d615b525743d1ce82a494538992a3451b8c3b49f7f4e0e7316ba396b650` |

No recorded value exists for `fetch-samples.sh` or `fetch-er-candidates.sh`.

### Optional dataset files (your choice; not needed for the provenance gate)

These are Phase 5 dataset artifacts reviewed in RV-P5-02/07/09; including them keeps the scripts and the data they verify together:
- `datasets/schemas/er-candidates.md` (untracked)
- `datasets/er-candidates/selection.tsv`, `datasets/er-candidates/split-assignment.tsv`, `datasets/er-candidates/fixture/fixture-release.xml` (untracked)
- `datasets/samples/er-candidates/**` (untracked: `PROVENANCE.tsv`, `FETCH-FAILURES.tsv`, and the dev/heldout `crossref-works`, `openalex-authors`, `openalex-works` JSON files)

### Keep out of this checkpoint

Everything outside `datasets/**`, in particular `modules/**` (entity-resolution and core work, with ER-P5-12 in progress), `tests/**`, and `docs/agent-runs/**`.

## Preflight commands (run in your Terminal, from the repository root)

```sh
# 1. Stage exactly the required files (add the optional ones only if you want them) and review
git add datasets/scripts/dsutil.py datasets/scripts/lib-datasets.sh datasets/scripts/fetch-dblp.sh \
        datasets/scripts/fetch-samples.sh datasets/scripts/fetch-er-candidates.sh datasets/dataset-manifest.yaml
git diff --cached --stat
git diff --cached -- datasets/scripts datasets/dataset-manifest.yaml   # review the bytes you commit
shasum -a 256 datasets/scripts/dsutil.py datasets/scripts/lib-datasets.sh datasets/scripts/fetch-dblp.sh datasets/dataset-manifest.yaml
git diff --cached --name-only | grep -v '^datasets/'                   # must print nothing

# 2. After committing: the script directory must match HEAD with nothing untracked
git status --porcelain -- datasets/scripts                            # must print nothing
test -x datasets/scripts/fetch-dblp.sh && echo executable || echo "not executable: prefix the command with bash"

# 3. Download area: absent or real directories, no links; no stray .part
ls -ld datasets/downloads datasets/downloads/dblp datasets/downloads/dblp/full 2>&1
ls -l datasets/downloads/dblp/full/ 2>&1                               # a .part, if present, must show link count 1

# 4. Environment that still affects curl (-q disables ~/.curlrc)
env | grep -i -E '^(https?_proxy|all_proxy|no_proxy|curl_ca_bundle|ssl_cert_file|ssl_cert_dir|sslkeylogfile)=' || echo "none set"

# 5. Tools and disk (about 1.1 GB free needed)
command -v python3 curl awk gzip && (command -v md5 || command -v md5sum)
df -h .
```

## The download (reviewed in RV-P5-09; you run it)

```sh
SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full
```

Then verify and report the observed size and sha256:

```sh
datasets/scripts/fetch-dblp.sh --verify
ls -l datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz
cat datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.sha256
```

Deferred (decisions-11 item 1): RV-P5-09 NIT-A (hard-linked `.part`), NIT-C (untested re-checks), NIT-E (manifest 416 wording). Agent `--verify` runs after your commit will pass the provenance gate only while `datasets/scripts/**` stays identical to HEAD.

Note: your commit and the download change git-visible state between agent Bash calls; the next agent Bash call may be refused once as UNATTRIBUTED (download outputs are git-ignored and are not audited). That refusal resets the baseline; it is the documented recovery procedure.
