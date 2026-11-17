# Phase 06 work order: curated-archive compatibility repair (verbatim)

Received by the orchestrator on 2026-11-16 after sparkit-run-01.md (preflight refusal). The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Approve the curated-archive compatibility repair. First have
hadoop-engineer confirm the actual completion marker, directory layout,
and checksum sidecar rules from the producer, read-only.

Then route the matching fixes to build-engineer and spark-engineer, one
owner at a time. Apply the verified Hadoop contract only to the curated
input. Keep the existing decision and grouping checks, run-ID chain,
bounded traversal, and symlink refusals. Add focused fixtures for the
valid Hadoop layout and incomplete input. Do not alter the archive,
fabricate a marker, or rerun entity resolution.

Reuse test evidence where it applies. Run the required checks on the
combined repair and request one focused review. After APPROVE, return
the exact commit scope and draft human-gate wording that ratifies the
new reviewed build for the same standalone sbt sparkIT command. Use the
human-gate record to supersede the e8c6ecc approval if repo rules permit.
If the allowlist citation must also change, cite the binding rule and
have factory-engineer prepare the diff.

Wait for my approval of the reviewed build before retrying sparkIT.
Preserve Phase 5 acceptance and keep held-out data and AWS out of scope.
```

## Order (orchestrator)

1. HD-P6-01 (hadoop-engineer, read-only): producer contract for the curated run.
2. BE-P6-04 (build-engineer): curated-only preflight rule in project/SparkItRuns.scala with self-test fixtures.
3. SP-P6-02 step 1A repair 02 (spark-engineer): curated-only reader rule with fixtures.
4. One `sbt check` on the combined repair (last owner), one focused review (CR-P6-21).
5. Commit scope, draft human-gate wording, allowlist-citation analysis (factory-engineer diff only if required).
6. No `sbt sparkIT` until the human approves the reviewed build.
