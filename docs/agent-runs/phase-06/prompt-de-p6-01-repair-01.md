# Phase 06 work order DE-P6-01 repair 01 (verbatim)

Received by the orchestrator on 2026-11-15, after CR-P6-03 (APPROVE with F1-F5, N1-N6) and the ER-P6-05 draft (work-order-draft-er-p6-05.md). The approvals in this text are the human's own words. The human records them in `human-gate.md`.

```text
Approve the focused DE-P6-01 repair. Delegate it to domain-engineer, limited to modules/core.

- F1/M2: Reject assignments that put the endpoints of a REJECTED or CONFLICT decision in the same researcher or work group. This approves that contract invariant only.
- F2: A singleton with a pending, vetoed, or conflicting decision may receive a researcher ID only with its own non-colliding ORCID or DBLP person ID. Enforce what core can validate from the supplied data and identify any check the producer still needs to perform.
- F3: Validate assignment coverage against the representative source work's expected author positions or mention IDs. Include a regression for a missing final author as well as a gap in the middle. A complete-looking sequence derived from the assignments alone cannot detect a missing final author.
- F4: Keep grouping-only settings out of the upstream decision run identity and preserve existing config and manifest compatibility. Prefer a separate grouping config scope. If this requires changes outside core, report the exact change and its owner.
- F5: Fix the simple quadratic scans where a small change suffices. Document any remaining bounded cost.
- O9: Keep canonical authorship records for all representative works. Produce graph teams and compute graph metrics only for DBLP-backed, graph-eligible works. Spark must use the canonical teams from the grouping run.

Add focused regression coverage, run sbt check once, and send only the repair diff to code-reviewer. Return the observed result and verdict. Keep the existing Phase 5 policy and evidence intact, and do not access held-out labels or evaluation output. Hold ER-P6-04 and ER-P6-05 until this repair is reviewed. Do not treat this as blanket approval of O1–O11.
```
