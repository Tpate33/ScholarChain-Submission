# Phase 06 work order ER-P6-05 (verbatim)

Received by the orchestrator on 2026-11-15, after CR-P6-06 (APPROVE). The acceptances and approvals in this text are the human's own words. The human records them in `human-gate.md`.

```text
I accept DE-P6-01 repair 02 and ER-P6-04 for bounded local use, with the remaining CR-P6-06 limitations recorded. I approve ER-P6-05 using the reviewed O1–O11 rules, with O9 as already decided: keep canonical authorship for all representative works and produce teams and graph metrics only for DBLP-backed, graph-eligible works.

Give entity-resolution-engineer one work order. Include the CR-P6-06 m1 regression that proves the integrity manifest reflects bytes read back from storage. Carry the other non-blocking findings forward. Use the verified decision reader and record both input run IDs. Validate expected author lists against every curated work, including non-representatives.

Preserve Phase 5 acceptance, matching policy, features, thresholds and evidence. Use only dev or committed sample inputs. Label grouping diagnostics as unevaluated. Do not regenerate old exports unless a needed input is actually missing. Use the authorized committed-sample route if available.

Before dispatch, identify any binding prerequisite that remains. If a human-only step is required now, cite the rule and provide only the exact action I need to take. Distinguish repository rules from prerequisites proposed in the draft.

Run sbt check once for the completed change and request one focused review. Return the results, reviewer verdict, and the grouping output available for Spark. Stop before starting Spark or AWS work.
```

## Orchestrator prerequisite check before dispatch (2026-11-15)

Repository rules (CLAUDE.md, docs/agents.md) that bind now: one owner per artifact; `sbt check` as the definition of done; the held-out fence; the entity-resolution lifecycle gate, which includes "match-policy review" (docs/agents.md, Lifecycle routing) and applies after implementation, not before. All are satisfied or built into the work order.

Prerequisites proposed in the draft (work-order-draft-er-p6-05.md section 6) that do not bind before dispatch:
- The D3(c) disclosure in `human-gate.md`. The rule's text (docs/agents.md, Human gates, Phase 5) covers policy, feature or threshold changes. ER-P6-05 changes none of them, so the disclosure is recorded at the phase gate.
- ER-P6-02 committed. Not a rule; a phase normally ends in one commit.
- A dev-run alias and allowlist row. Not needed; the committed-sample route through the test suite runs under `sbt check` (@SBT-ALL).

Prerequisites that were met: DE-P6-01 baseline accepted (with repairs 01 and 02); ER-P6-04 accepted.

No human-only step is required before dispatch.
