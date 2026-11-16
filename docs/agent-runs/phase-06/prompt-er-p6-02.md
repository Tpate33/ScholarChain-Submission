# Phase 06 work order ER-P6-02 (verbatim)

Received by the orchestrator on 2026-11-15, after ER-P6-01 returned PROPOSAL. Human decision: H9 approved; H1-H8 deferred.

```text
I approve H9 only. Delegate ER-P6-02 to entity-resolution-engineer. Use the run manifest's decision timestamp and publish the completed decision dataset only if every candidate succeeds. A failed attempt must not leave a partial dataset that downstream jobs can consume.

First, check the relevant source for an existing writer of the shared MatchDecision format. Reuse it if present. Otherwise, add the smallest bounded exporter within your ownership. Use the existing resolver and unchanged er-policy.v8 settings. Emit one decision per candidate in the existing format, with run provenance. Use only dev or committed sample inputs.

Keep Phase 5 accepted. Do not change its matching policy, features, thresholds, or accepted evidence. Do not access held-out labels or evaluation output. Defer canonical grouping, the shared grouping contracts, and H1-H8. If work outside the agent's ownership is required, report the exact blocker and its owner.

Add focused tests for decision parity, reproducibility with the same manifest and inputs, and failure without partial publication. Run sbt check and route the change to code-reviewer once it is ready. Return the implementation report, observed verification results, and reviewer verdict. Do not repeat Phase 5 checks.
```
