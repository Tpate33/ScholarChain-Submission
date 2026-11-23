# SV-INT-04 — failed-start cleanup repair

Owner: service-engineer. Finding: CR-INT02-A-1 (major).

The usage interruption left the regression test written but the production call
unrepaired. Resume inspection found no Java/sbt process. The owner reused the
already-recorded failing run instead of repeating it and completed only the
factory-execution guard.

Changed files:
- `modules/services/src/main/scala/scholarchain/services/ProductionComposition.scala`
- `modules/services/src/test/scala/scholarchain/services/ProductionLauncherSuite.scala`

Before: the new throwing-factory regression failed (one failed, zero passed), with
an escaping IllegalStateException. After: source/test formatting and focused
ProductionLauncherSuite/WorkflowStoreBoundsSuite passed 14 tests, zero failures or
errors. Logs: `verification/workflow-factory-before.txt` and
`verification/workflow-factory-after.txt`. Dummy exception text is test data, not a secret.

The minimal guard sends NonFatal exceptions through existing bounds/client/key
cleanup and returns a sanitized refusal. The regression also verifies that later
calls through captured stores cannot reach the underlying client. No other behavior
was intentionally changed. The reviewer re-inspected the fix and approved scope A.

Owner report: STATUS COMPLETE; files created none; the two files above modified;
focused commands only; 14 passed; no AWS/model calls or commits/pushes; no remaining
owner blocker; next gate coordinator's single post-repair full check, then human
local acceptance. No unrelated refactoring or deferred-minor repairs were performed.
