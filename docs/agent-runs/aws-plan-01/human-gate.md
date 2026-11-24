# Offline AWS preparation — human acceptance

Decision: ACCEPT OFFLINE CHECKPOINT
Accepted implementation commit: 09c3d4cda6b0935aa607dcddecfdbefab84b6ddb
Acceptance date (America/Chicago): 2026-11-23T22:33:26-06:00

Scope: portable worker packaging, deterministic production-input census,
bounded exact-object materialization, private runtime infrastructure and gated
deployment/cleanup preparation, with the verification and review records here.

Review: independent source-snapshot review APPROVE for offline checkpoint;
major CR-OFF-01 repaired and re-reviewed. Existing sbt check passed 2,889 tests,
zero failures/errors, one skipped, 243 suites. The full suite was not rerun
for Docker recovery or closeout documentation.

Docker limitation: container application verification is BLOCKED by a zero-byte
libjvm.so in the Java base, persisting after the approved 32-record recovery.
Historical image builds/shell probes passed, but no healthy current verified
container image is established. Further cache repair and separate Colima
profiles/VMs are explicitly deferred.

Other limitations: no successful AWS run is established. Complete bounded producer
exports/census, EMR chain, collector, aggregate provider/failure controls, live
inventory/quotas/model access/costs/configuration and reviewed plans remain gates.
One 10K run does not meet two-scale evaluation requirements. Public privacy
review, anonymous fresh-clone reproduction and Teams receipt remain separately
tracked. See offline-status.md for exact status at acceptance.

Authority: this acceptance approves only the local checkpoint. It does not
authorize AWS inspection/deployment, paid calls, provider changes or cleanup.
