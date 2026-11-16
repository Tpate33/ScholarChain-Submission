# Phase 06 work order ER-P6-01 (verbatim)

Received by the orchestrator on 2026-11-15, after SP-P6-01 returned BLOCKED-ON.

```text
Delegate a read-only assessment to entity-resolution-engineer as the next step after SP-P6-01. Use the existing Spark blocker report, accepted Phase 5 contracts, and relevant source files. Use only dev or committed sample inputs. Do not access held-out labels or evaluation output.

Verify what Phase 5 already produces and identify the smallest handoff Spark needs to build canonical researcher and work IDs. Separate these two decisions:
1. How to persist existing accepted, rejected, unresolved, and conflict decisions without changing them.
2. How to group records under stable canonical IDs, including what happens when accepted links imply a conflicting merge, an author mention remains unresolved, or multiple sources describe the same work.

Identify the minimum shared contracts domain-engineer would need to add, who owns each proposed artifact, and the smallest implementation work order to run next. If any proposed rule changes which records count as the same entity, show a concrete example and explain the D3(c) implications and approval needed.

Phase 5 remains accepted. Do not reopen it or change its matching policy, features, thresholds, or accepted evidence. This assessment is proposal only. Do not edit files, run builds or tests, or repeat Phase 5 verification. Return the proposal, the evidence supporting it, and any specific decision needed from me before implementation.
```
