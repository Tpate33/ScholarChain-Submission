# Phase 5 audit recovery 02: DS-P5-01 fetch moved to background

## Incident (agent-reported)

- `dataset-engineer` ran `datasets/scripts/fetch-samples.sh --sample datasets/er-candidates`.
  The human approved it through the script-provenance `ask` prompt.
- The call ran for more than 180 s, and the harness moved it to the background; the agent did not
  request that. Its later writes landed between audited calls.
- The final line of the fetch, as reported: `fetched 171, already present 0, not fetched 121, bytes in ledger 623126`, exit 1.
- On the agent's next call (`fetch-samples.sh --verify datasets/er-candidates`),
  `audit-bash-writes.sh --pre` reported an UNATTRIBUTED inter-call change. The change covered the
  ledger, the failures file and the new record files, all under `datasets/**`. The hook refused the
  call and reset the baseline. The agent stopped as instructed.
- Incident log (temporary directory, not modified by the orchestrator):
  `/var/folders/zt/pbpscx0s72b8jch7_wr7gc9m0000gn/T/scholarchain-bash-audit-501/session-31ac882037fa1279f7fc3022ee76cd91.incidents.log`

## Orchestrator observation after hand-back

- A first attempt, `git status --porcelain -- datasets`, was refused by `guard-dangerous.sh`
  (no-allowlist-entry: path filters are not part of the `git status` row). Nothing ran.
- `git status --porcelain`: ran normally, with no UNATTRIBUTED report.
  - New or modified under `datasets/**`: `dataset-manifest.yaml`, `scripts/dsutil.py`,
    `scripts/fetch-samples.sh` (modified); `er-candidates/`, `samples/er-candidates/`,
    `schemas/er-candidates.md`, `scripts/fetch-er-candidates.sh` (untracked).
  - Otherwise only the previously known Phase 5 paths. No change outside `datasets/**` from this
    work order.
- `.claude/hooks/verify-tests-ran.sh --check`: OK.

## Status

- The fetched records, `--verify`, `validate-manifest.sh --verify` and `sbt check` for the
  DS-P5-01 changes are **unverified**. No test count exists for this work order.
- The background-written files have not been checked by any audited command. The human should
  review `git diff -- datasets` and the untracked files before any further datasets step.

## Process note for future work orders

Any Bash call longer than 180 s is moved to the background by the harness. That produces the same
UNATTRIBUTED pattern as recovery-01, so long fetches and downloads should run in a human terminal.
`sbt check` (about 2 minutes) is close to that limit; this is a residual risk.
