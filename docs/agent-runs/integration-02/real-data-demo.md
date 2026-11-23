# DBLP-derived local API connection — November 23, 2026

User authorization: connect existing real DBLP-derived pipeline output to the local
query demo, keep changes minimal, verify HTTP behavior, update the report, preserve
uncommitted work, and make no AWS/model calls, commits or pushes. AGENTS.md, skills
and hooks remain unchanged. No human acceptance or independent review is claimed.

## Data and implementation

Reused the human-copied Phase 6 artifacts already committed under storage test
resources as data. No test implementation is linked into services. `PipelineDemo`
loads the real curated/grouping/analysis/settings/benchmark chain through existing
`LocalRunSource` and `OpeningLoad`, requiring the accepted part-manifest SHA-256
`749763adb05af272a3c0c5473ed3d90d071a3e762d67ef70ebef84295cc94907`.
The accepted analysis run is `a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40`.

The curated work records count 41 DBLP, 43 OpenAlex and six Crossref. This does not
establish three enrichment datasets or course corpus-scale compliance. Original
provenance and the grouping evaluation limitation are retained.

A separate `pipelineDemoClasspath` task supplies optional storage reader libraries
to the local process. The normal services package and module graph keep their
existing dependency boundaries. The original synthetic launcher remains available.
`LocalDemo.workflowConfig` now also accepts the actual analysis window, so both
profiles share model/budget settings without reminting facts.

## Observed behavior

The standalone replay returned VERIFIED claims for Ailing Zeng, resolved evidence
and explain, returned three graph neighbors, preserved the answer/query on replay,
and refused a missing API key. Each coauthor edge has one shared publication and
cites DBLP source `conf/aaai/ZengCZ023`. The actual loader completed all four local
targets: graph 1,258 writes, key-value 1,459, objects 31, vectors 249. These are
in-memory target writes, not AWS operations. Embeddings and answer synthesis are
explicitly non-production stand-ins.

Final `sbt check services/stage`: 2,850 passed, zero failures/errors, one skipped,
233 suites; service staging also passed. Focused tests: three passed, zero failures/errors. They include missing-artifact
and wrong-manifest refusals; no fallback to the synthetic seed occurs. See
[verification](../../verification.md) for full-gate status and archived evidence.
An initial compile caught a configuration result-type mismatch; it was corrected
before the successful focused run. No failing command is represented as passing.

## Limits and next gate

No new dataset acquisition or pipeline computation was run. No live store load,
production model, cloud deployment, scaling result, final tag or independent final
review is claimed. Student review, remaining required cloud work, video and release
approval remain outstanding. No commits or pushes were made.
