
## DE-P6-02 contract acceptance (2026-11-16)

I accept DE-P6-02 repair 01 following CR-P6-14. A counted work has exactly one canonical team if it has at least one author mention in the grouping run. A counted work without authors has no team. Each benchmark pair list is bound to the grouping run that issued its researcher IDs. Phase 6 remains open.

## FE-P6-05 ratification (2026-11-16)

I ratify the standalone `sbt sparkIT` command in allow mode for
`spark-engineer` only, against the reviewed build at `e8c6ecc`.
Allowlist SHA-256: `71034e28f6b00faecfc09be9ab13e3204b207b80b78c034a7cc760fcf040d707`.
This permission takes effect after this commit and a fresh Claude Code session.
Integration attempts are cleanable. Preserve the run evidence before cleanup.

## Curated archive repair and Spark integration retry (2026-11-17)

I accept BE-P6-04 and SP-P6-02 step 1A repair 02 following CR-P6-21
(APPROVE). Option A applies only to the pinned curated dev run. The
archive's stage markers establish stage completion. Attempt-level success
comes from the original attempt and the recorded dev-run evidence.
The read-only archive check found no .DS_Store files.

I approve one standalone `sbt sparkIT` retry by spark-engineer using
the reviewed build in the commit containing this entry. This supersedes
the e8c6ecc build approval for this retry. The allowlist scope is unchanged.
Carry CR-P6-21 m1, n1, and n2 forward before any step reads curated data
parts. Phase 5 remains accepted.

## SparkIT classpath repair and run 03 approval (2026-11-17)

I accept BE-P6-05 following CR-P6-22 (APPROVE) and defer N1 and N2.
Run 02 passed archive preflight but failed to compile. The repaired
integration suite now compiles under sbt check without archive access.

I approve one standalone `sbt sparkIT` run by spark-engineer using the
reviewed build in the commit containing this entry. This supersedes
the retry approval at 7244398. The allowlist scope is unchanged.
The archive and Phase 5 acceptance are preserved.

## SparkIT runner repair and run 04 approval (2026-11-17)

I accept BE-P6-06 following CR-P6-23 (APPROVE). Run 03 remains failed
evidence. The corrected JUnit lookup and result handling passed the
archive-independent runner checks. I defer F1 and F2. I accept the
JUnit ledger entry as a validation value for the already-resolved
dependency. G5 remains open and ownership permissions are unchanged.

I approve one standalone `sbt sparkIT` run by spark-engineer using the
reviewed build in the commit containing this entry. This supersedes the
run 03 approval at 982eecf. Phase 5 remains accepted.
