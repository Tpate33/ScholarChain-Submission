# Integration Round 02: human acceptance — local checkpoint

Decision: ACCEPT LOCAL CHECKPOINT
Accepted implementation commit: 1079eacb0260d9d57f4184a55c9d1ee64ef3093c
Acceptance date (America/Chicago): 2026-11-23T15:36:35-06:00
Human: Taufeeq Patel (tpate33@uic.edu)

Prepared for human review. While Decision is PENDING, this document is unsigned and grants no permission.
Only Taufeeq can accept it. The proposed Git sequence fills these three pending
fields only when run by the human after approval; Codex has not accepted on their behalf.

## Proposed accepted scope

Accept the working local integration checkpoint: production launcher/read-policy
wiring that fails closed for absent dependencies; the separate synthetic demo;
the verified DBLP-derived local query, evidence/explain/graph routes and replay;
local launchers; offline codec submission correction; and supporting report/evidence.
The verified major throwing-factory cleanup finding is repaired by service-engineer
and independently re-reviewed. Both scoped reviews approve the local checkpoint.

This is acceptance of a narrowed local milestone, not a declaration that every
original Integration 02 A–E criterion or course requirement was completed:
- Original B (Secrets Manager key source) is deferred.
- Original D2 ServiceLoader registration is replaced by explicit selection of
  ProductionLauncher by the production mains. No service registration or silent
  synthetic fallback is added. This is a scope/design decision to accept explicitly.
- Codex's direct implementation takeover and observed full checks are disclosed.
  Human acceptance relies on archived evidence and reviews and does not claim the
  human personally ran the final full check or reviewed every source line.

## Evidence reviewed for acceptance

`review-report.md`, `repair-01.md`, `test-report.md`, `reviewed-files.json`,
`file-inventory.md`, `real-data-demo.md`, `verification/` and the report in
`reports/final-report.md`. Code review was independent; no reviewer changed code
or ran tests. The final post-repair gate passed 2,851 tests, zero failures/errors,
one skipped, across 233 suites; both launcher preparations passed. Exact command,
466-second check duration and attribution are in test-report.md.

## Retained limitations and residuals

The dev sample contains 41 DBLP, 43 OpenAlex and six Crossref normalized work records.
The local Ailing Zeng answer's three edges cite the actual DBLP paper
`conf/aaai/ZengCZ023`; grouping evaluation remains unevaluated. This does not prove
full-corpus coverage, three additional ingested datasets, real LLM RAG or model quality.
In-memory stores, template synthesis and hashed-token embeddings are explicit stand-ins.

Keep Integration 01 residuals open. Defer the two minor synthetic smoke issues
(assertions under optimized Python; stale PASS artifact after a later failed run),
and the unverified interrupted-drain shutdown concern. These are not fixed or waived
for deployment. Use normal Python and a fresh synthetic replay output directory.

No live storage/model, Secrets Manager, runtime infrastructure/deployment, successful
EMR output, measured elasticity, independent test execution, final video or instructor
waiver is established. Existing cloud script hashes/plans are historical and must
not be used to deploy the changed wrapper. No AWS operation is authorized here.

## Proposed Git authorization

After explicit human approval, the human may stage only the paths listed in `commit-paths.txt`, create
the implementation checkpoint commit, record its actual SHA in `commit.txt` and this
acceptance file, create the two-file acceptance commit, then push both to origin/main.
This proposed sequence does not itself grant approval. No tag, force push, history
rewrite, AWS operation, paid call, deletion or change to AGENTS.md, skills or hooks
is included. The two protected untracked guidance paths remain excluded.
