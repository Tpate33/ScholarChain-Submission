# Human decisions, 2026-11-20 ~15:07 CST: SE-P7-05 marker review, fixtures, guard question

## Human statement (verbatim)

> Marker reviewed for the run completed November 20 at 3:01:05 PM CST (21:01:05Z). Terminal confirms [success] and exit code 0: 135 suites, 1762 passed, storage 188, zero failures/errors/skips. Git status shows the expected paths. Disk space remains 13 GiB available.
>
> Record this review and follow the documented recovery procedure.
>
> Decisions:
> 1. Fixtures: choose option (a). Have storage-engineer identify the exact archived grouping/curated files needed for WP5 and explain their purpose and size. Give me one complete macOS Terminal copy block that reads expected checksums from the saved archive checksum files, verifies sources and copies, stops on failure, refuses existing destinations, and preserves the archive unchanged. Do not fabricate replacement evidence.
>
> 2. Command guard: authorize factory-engineer to investigate the reported discrepancy in read-only mode and report whether the allowed command actually violates the current rules. No hook, permission, or constitutional changes are approved. Keep this investigation from delaying independent loader work.
>
> 3. Proceed with independent WP4 review and WP5 implementation under the approved local closeout scope. Continue work that does not depend on the additional fixtures while I handle the copy.
>
> Do not treat tests that manually supply pending validation results as proof of a complete load. Final local acceptance must exercise the real checks end to end.
>
> Keep builds sequential and per-increment human marker review. No commits, cleanup, live AWS operations, manual marker edits, or acceptance of unresolved vulnerabilities.

## How the orchestrator applies them
- Recovery procedure run after this record.
- SE-P7-06a (storage-engineer, read-only, no build): identify the archived grouping/curated files, purpose, size, and their lines in the archive checksum files. The orchestrator then gives the human one copy block.
- FE-P7-01 (factory-engineer, read-only): investigate the compound `cd … && sbt storage/compile` report. No changes.
- CR-P7-07 (code-reviewer): review SE-P7-05 (WP4).
- SE-P7-06 (storage-engineer, the only builder): WP5 work not dependent on the new fixtures.
- Acceptance rule recorded: in-package tests that mark pending checks as passed are not evidence of a complete load; final local acceptance (WP7) must run the production gate with every check really performed, end to end on the fixtures.
