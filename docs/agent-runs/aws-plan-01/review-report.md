# Independent review — offline AWS preparation

STATUS: COMPLETE
OWNER: code-reviewer (`/root/offline_review`)
WORK ORDER: Independent review of offline preparation and CR-OFF-01 repair.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none
TEST RESULTS: Owner-reported census 3 passed; materializer 4 passed;
infrastructure/control 14 passed. Final sbt result recorded separately.
VALIDATION RESULTS: Current source snapshots supplied by the orchestrator and
infrastructure owner covered packaging, census, materialization, runtime
compute/IAM, admission, cleanup, controller and deadline monitor. No verified
critical or major finding remains within this reviewed scope.
ASSUMPTIONS: Materialization root is exclusively worker-owned. Final deployment
configuration and immutable images remain separately reviewed inputs.
RISKS: Snapshot review does not establish live AWS/image execution, complete EMR
orchestration, collector functionality, aggregate provider limits or public
release readiness.
MANUAL ACTION REQUIRED: Human checkpoint acceptance after final verification;
separate explicit approval for account access, cloud execution and paid calls.
BLOCKED-ON OWNER: none for reviewed offline code
BLOCKED-ON EVIDENCE: final sbt/image verification; cloud/publication gates
NEXT GATE: Archive final verification, then human checkpoint acceptance.
VERDICT: **APPROVE — offline checkpoint only.**

## Verified finding and repair

**CR-OFF-01 — major, RESOLVED; owner infra-engineer.** A successful manual
`stop-services` could previously be undone by active scaling or by the monitor
setting Graph's desired count back to one after its experiment deadline.
`scripts/runtime/control.py:46` now suspends scaling with minimum zero before
zeroing services. `scripts/runtime/deadline_monitor.py:31` suspends scaling with
bounds zero–one and no longer writes positive desired capacity. Two focused
regressions cover stop ordering and repeated monitor ticks preserving zero;
all 14 offline infrastructure checks passed. Monitor ZIP was regenerated and
verified against its reviewed source. No Scala source changed for this repair.

## Unverified concerns

Cloud execution remains **NO-GO** until the complete workload, bounded corpus,
provider/failure controls, account/cost prerequisites, immutable images, real
plans and explicit human approval are established. Public privacy review,
anonymous fresh-clone reproduction and Teams submission remain separate gates.

## Orchestrator closeout addendum — not a new reviewer verdict

The final existing sbt record confirms 2,889 passed, zero failed/errors, one
skipped, 243 suites. No candidate Scala/build source is newer than that record.
Container application checks remain BLOCKED by the zero-byte JVM library after
the approved targeted recovery. The human deferred further Docker repair and
new profiles/VMs, and requested closeout with that explicit limitation. The
independent verdict above remains approval of reviewed offline source only;
neither healthy container execution nor AWS readiness is asserted.
