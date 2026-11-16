# Phase 06 work orders: archive dev run 01, then DE-P6-02 C1-C7 (verbatim)

Received by the orchestrator on 2026-11-16, after dev run 01. The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Archive the exact Hadoop curated dev input, decision run, and grouping run
listed in phase-06/dev-run-01.md. Copy them to a fresh location outside the
repo and build output directories. Preserve the run directory names and
manifests, and verify the copies against the source bytes and available
integrity manifests. Record the archive paths and run IDs in the Phase 6
evidence. Use an authorized agent if permitted. If the copy is blocked by
the repository rules, cite the rule and return one exact copy command for me.

Use the archived, verified bytes for the first Spark integration run.
Do not rerun or re-pin entity resolution unless input compatibility or
integrity actually requires it.

Once the archive is verified, delegate DE-P6-02 C1–C7 together to
domain-engineer, limited to modules/core. Reuse existing contracts and
implement only the shared schemas, validation, and tests. Keep unknown
identity confidence explicit. For Parquet exports, use byte counts and
checksums for file integrity and obtain row counts from Parquet metadata or
the writer. Do not apply JSONL newline validation to binary files.

Preserve prior graph-scope and metric decisions and keep Phase 5 accepted.
Use dev or committed samples only. Run sbt check once and get one focused
code review. Return the verdict and the first Spark implementation work
order. Stop before starting Spark implementation or AWS work.
```

## Orchestrator finding: the copy is blocked for every agent (2026-11-16)

- docs/agents.md, Agent catalog, global floor: every identity, the orchestrator included, is refused "any Bash command that is not an active row of the Bash allowlist (files are written with Write/Edit under the same ownership map; shell syntax, inline interpreter code, and unlisted programs are refused by default)". No row names `cp`, `rsync`, `mkdir`, `tar` or `shasum` for these paths (dataset-engineer's `shasum -a 256` row is limited to `<PATH:datasets/>`).
- docs/agents.md, Enforcement layers, Bash enforcement: "Path arguments must resolve inside the repository" and "no allowlist row is itself a file-writing shell command". An archive outside the repository is therefore unreachable by Bash, and Write/Edit cannot make byte-exact copies of the run files.
- Result: the human runs the copy and the verification in a terminal. The orchestrator records the archive path and the verification output here once the human pastes it.

## Planned archive (to be confirmed by the human's verification output)

- Archive root: `~/ScholarChain-archive/phase-06/dev-run-01/` (outside the repository and every build output directory; created fresh, the copy refuses if it already exists).
- Copied with repository-relative paths preserved (`rsync -a --relative`):
  - `modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa` (curated input `run:2881724b…8baa`)
  - `target/local-runs/dev/er-decisions/run=736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a` (decision run)
  - `target/local-runs/dev/er-assignments/run=84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2` (grouping run)
- DE-P6-02 is held until the verification output is recorded.

## Archive verified by the human (2026-11-16, 12:00 local; from the human's terminal screenshot)

The human ran, in a regular Terminal (zsh), a subshell that: set `archive="$HOME/ScholarChain-archive/phase-06/dev-run-01"`; ran `mkdir -p "${archive:h}" && mkdir "$archive"` (fresh leaf); ran `rsync -a --relative` over the three run paths into `"$archive/"`; then for each path ran `diff -rq "$run_path" "$archive/$run_path" || exit 1` and printed `Identical:`. Observed output, verbatim:

```
Identical: modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa
Identical: target/local-runs/dev/er-decisions/run=736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a
Identical: target/local-runs/dev/er-assignments/run=84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2
Archive verified: /Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01
```

Recorded archive:

| Dataset | Run ID | Archive path |
|---|---|---|
| Hadoop curated dev input | `run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa` | `/Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01/modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa` |
| ER decision run | `run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a` | `/Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01/target/local-runs/dev/er-decisions/run=736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a` |
| ER grouping run | `run:84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2` | `/Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01/target/local-runs/dev/er-assignments/run=84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2` |

Verification scope:
- All three copies are byte-identical to their sources (`diff -rq`, recursive, every file).
- The per-part checksum listing (`shasum -a 256` over the archive) was not run. The integrity-manifest check rests on transitivity: the source decision and grouping runs were verified against their `_parts.json` by the task's verified readers in dev run 01 (`DecisionExport.readPublished`, `GroupingExport.readValidated`), and the archive equals those bytes. The Spark integration run must still verify the archived parts against `_parts.json` before consuming them.
- Human decision: the first Spark integration run uses these archived, verified bytes; entity resolution is not re-run or re-pinned unless input compatibility or integrity requires it.
