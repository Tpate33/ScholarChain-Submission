# Phase 5 Unicode prerequisite human gate

Decider: Taufeeq Patel
Date: 2026-11-04
Scope: Unicode prerequisite only. Main Phase 5 is not accepted or completed.

## Decision
I accept the Unicode prerequisite implementation and its documented limitations.
Final closure remains pending a clean-tree test run on the committed prerequisite
and recording that commit and verification evidence.

## Evidence reviewed
- prereq-unicode-limitations.md
- prereq-unicode-test-report.md
- Recorded code-reviewer APPROVE, with NIT-1 and NIT-2 nonblocking.
- Working-tree file inventory, displayed diff portions, and new test inventory.
This was a focused acceptance check, not a fresh comprehensive code review.

## Personally observed verification
Command: sbt -no-colors check
Completed: 2026-11-04 21:59:29 America/Chicago
Exit status: 0
Passed: core 252, entityResolution 75, hadoop 67, tests 49; total 443.
Failed: 0. Errors: 0. Skipped: 0. Suites: 34.
HEAD: d16e8c9717f806f6f4018b56beb456382c5ed23a
Working tree: dirty.
Local full log: /tmp/scholarchain-unicode-check.zXN8Wt
The temporary log is not a durable archive or clean-commit evidence.

## Accepted limitations
- NIT-1: lone-surrogate fusion and the documented equivalence-claim limitation.
- NIT-2: Cf-only separation before a DBLP homonym number can lose recall.
- Cf-only word separators join words.
- Hadoop AuthorMention.normalizedName retains Cf; matching must use the
  intended entity-resolution keys and features.
- Blocking/features v1 outputs must not be mixed with v2 outputs.
- Cf classification depends on the running JDK's Unicode tables.
- Regression sensitivity against pre-fix code was hand-traced, not executed.
- Gold-set evaluation and the effect on match quality remain main Phase 5 work.
- Remaining evidence limitations are acknowledged as documented in
  prereq-unicode-limitations.md.

## Manual actions and boundaries
I ran verification in Terminal; generated build outputs and the test marker
were updated. This acceptance record was drafted with reviewer assistance.
No implementation was manually edited during this acceptance session.
Phase 4 AWS prerequisites and other deferred items remain outside this acceptance.

## Commit and clean-tree verification
Pending; to be recorded after the prerequisite commit.
