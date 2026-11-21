# Phase 07: human fixture copy (2026-11-19, human Terminal)

Recorded by the orchestrator from the output the human pasted into the session (with a screenshot of the Terminal). The human ran a strict `/bin/bash -euo pipefail` script in the Terminal. It was a reformatted version of the orchestrator's block, with an added `-L` symlink check on both destinations.

## What the script did
- Verified the archive `/Users/taufeeqpatel/ScholarChain-archive/phase-06/local-evidence/` against its saved `SHA256SUMS.txt`, before and after the copy, and checked that the checksum file's own sha256 did not change.
- Refused if either destination already existed or was a symlink.
- Copied `spark-submit-local-attempt-4/run-1-local/out/run=a65f3f38…/` and `load=a65f3f38…/` into `modules/storage/src/test/resources/fixtures/phase06-run-1-local/`. Wrote that directory's `SHA256SUMS.txt` from the archive's own lines, with the path prefix removed.
- Copied `spark-submit-local-attempt-4/run-2-local-cluster/out/run=a65f3f38…/_parts.json` into `modules/storage/src/test/resources/fixtures/phase06-run-2-local-cluster/run=a65f3f38…/_parts.json`. Its expected sha256 is the archive's own line for that file.
- Verified both copies with `shasum -a 256 -c`, matched file counts against the checksum list, refused non-regular files, refused `.DS_Store`, and refused git-ignored Parquet files.

## Observed output (verbatim)
```
OK archive verified before copy
OK run-1 fixture: 60 files verified
OK run-2 _parts.json verified; differs from run-1
OK archive verified after copy; checksum file unchanged
?? modules/storage/src/test/resources/
RESULT FIXTURES COPIED AND VERIFIED
```

## Facts
- Run-1 fixture: 60 files (56 run-directory files and 4 load manifests), each matching the archive's sha256.
- Run-2 `_parts.json`: matches the archive's sha256 and differs from run-1's `_parts.json`. It has the same run ID with different bytes (SE-P7-01 risk R1) and is used for the conflicting-publication test.
- The archive was verified unchanged after the copy.
- The fixtures are untracked and not ignored. The human commits them with the phase.

## Human instruction
Use these fixtures for the Phase 7 loaders and contract tests. Continue the approved local implementation, dependency verification, `sbt check` and independent review. Do not commit. Do not perform live AWS operations.
