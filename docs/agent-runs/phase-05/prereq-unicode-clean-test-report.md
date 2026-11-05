# Unicode prerequisite: human-run clean-tree verification

Run by: Taufeeq Patel
Repository: /Users/taufeeqpatel/Desktop/ScholarChain
Completed: 2026-11-04 22:04:09 America/Chicago

Captured immediately before the run:
Command: git rev-parse HEAD
127819b6a6e8eefd5c9ce270e924759f485a4523

Command: git status --porcelain
Output: empty.

Test command: sbt -no-colors check
Exit status: 0
Duration: 121 seconds

Observed results:
- core: 252 passed
- entityResolution: 75 passed
- hadoop: 67 passed
- tests: 49 passed
- Remaining projects: no tests
- Total: 443 passed, 0 failed, 0 errors
- Marker: 34 suites, 0 skipped, worktree=clean
- Marker git_head: 127819b6a6e8eefd5c9ce270e924759f485a4523

This report transcribes the Terminal output personally observed by the human.
The full output remains in a temporary local log, not a durable archive.
The original agent test report is retained unchanged as historical evidence.
Only evidence records will change in the follow-up commit; the tested
implementation and tests remain those of the commit above.
