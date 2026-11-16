# Phase 06 work order ER-P6-02 repair 01 (verbatim)

Received by the orchestrator on 2026-11-15, after CR-P6-01 returned APPROVE with findings m1, m2, n1-n5.

```text
Proceed with ER-P6-02 repair 01. Delegate it to entity-resolution-engineer.

Fix m1 and m2. Refuse incomplete published runs and manifests for the wrong stage, while allowing a present decision file with zero rows. Add a test with a non-default threshold and a candidate whose outcome would differ if the configuration were ignored. Include n1 and n4 if they are small changes in the same code. Leave n2 and n3 documented as limitations pending acceptance.

Keep Phase 5 accepted. Preserve its matching policy, features, thresholds, and evidence. Use only dev or committed samples. Leave canonical grouping and shared contracts deferred.

Add focused regression coverage and request a review of the repair diff only. Before the final sbt check, return the exact cleanup needed for n5. Once I confirm cleanup, have the owning agent run sbt check once and report the result and reviewer verdict. Do not repeat Phase 5 checks or start the next work order.
```
