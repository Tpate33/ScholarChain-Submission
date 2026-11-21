# Human marker review (SE-P7-08) and multi-run decision — 2026-11-20 ~17:05 CST

## Human statement (verbatim)
> Marker reviewed: 2026-11-20T22:56:51.030852Z. I inspected the saved output: [success], exit code 0, completed at 4:56:51 PM CST; 148 suites, 1890 passed, storage 316, zero failures/errors/skips. No matching sbt process appeared. Disk space is 12 GiB.
>
> Record the review and follow the documented recovery procedure.
>
> Multi-run decision: choose (b) for the bounded local closeout—one analysis run per local store instance. Use fresh local stores for another run; preserve Spark's stable identifiers. Document this limitation and defer multi-run coexistence and active-run switching. This authorizes no deletion or cloud provisioning.
>
> Proceed with independent SE-P7-08 review and WP6 vector loading: five-index routing, long text in the object store, metadata filters, batching, bounded retries, rejected records, and explicitly labelled synthetic test vectors.
>
> Extend OpeningLoad and its end-to-end tests to include vector loading through real validation gates. Keep real embeddings and live AWS acceptance explicitly outstanding.
>
> Complete the remaining essential local closeout work, adversarial tests, final review, and documentation. Defer optional tooling refinements.
>
> Keep builds sequential and human review for future changed markers. No commits, cleanup, live AWS operations, hook changes, manual marker edits, or acceptance of unresolved vulnerabilities.

## Applied
- CR-P7-09 M1 closed for the local closeout as a recorded scope decision: one analysis run per local store instance; the fail-closed pre-write probe (SE-P7-08) refuses a second, different run with zero writes. Multi-run coexistence and an active-run pointer are DEFERRED and tracked outstanding (relevant before the EMR run replaces the dev run). Documentation must state the limitation.
- Next: CR-P7-10 (review of SE-P7-08) and SE-P7-09 (WP6 vectors + OpeningLoad extension); then WP8 adversarial tests (test-engineer), WP10 documentation (documentation-engineer), WP9 final whole-phase review; WP3 dependency fixes still pending.
