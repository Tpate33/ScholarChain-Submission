# Phase 5 open findings, grouped by owner (orchestrator assembly)

Assembled 2026-11-11 per decisions-08.md. Sources: review-report.md (RV-P5-02, RV-P5-03, RV-P5-04, RV-P5-05, RV-P5-06) and test-report.md (TE-P5-01). Status is as last checked in code by RV-P5-05/RV-P5-06; nothing here is new analysis or an observed test result. "Gold-set eval" = affects the required gold-set evaluation (labeling, membership, metrics, reporting). "Acquisition" = affects dataset acquisition (dblp download/extraction, samples).

Legend for impact: **G** gold-set evaluation, **A** dataset acquisition, **—** neither directly.

## entity-resolution-engineer

| ID | Sev. | Finding | Location (as reported) | Impact |
|---|---|---|---|---|
| RV-P5-04 m1 | minor | Threshold sensitivity counts every probabilistic-path row as eligible for score-based acceptance, including rows the stage rule (and other blocks) can never accept; worse since v4, because stage-blocked rows keep the probabilistic path | `GoldSetEvaluation.scala:400-402` | **G**: the required threshold-sensitivity output overstates what a threshold change would accept |
| RV-P5-04 m6 | minor | Evaluation output prints no thresholds, model or prompt version; `withConfig` has no caller (`ReviewQueueMain` uses `loadDefault`), so external config is not applied | `GoldSetEvalMain.scala:98`, `ReviewQueueMain.scala:248, 264` | **G**: the order requires reporting versions and thresholds; config externalization invariant |
| RV-P5-04 m7 | minor | arXiv agreements use exact equality while the policy text says "versions vN are compared as one value" (now also in v5) | `MatchFeatures.scala:219-222`, `er-policy.v5.json:142` | **G**: per-path recall/accuracy of the exact-id arXiv path; policy text inaccurate |
| RV-P5-04 m8 | minor | Title-markup normalization written but not wired (needs er-features.v3; hadoop-engineer input for features) | `TitleMarkup.scala:8` | **G**: feature quality on titles with markup |
| RV-P5-04 N1 | nit | `LeakageStructureSuite` has no `java.nio` ban | `LeakageStructureSuite` | **G** (indirect): guard that keeps labels out of tuning |
| RV-P5-04 N2 | nit | Adjudication response check bypassable with Cf characters | `AdjudicationProtocol.scala:25-26` | — (LLM is interface-only, OA-1) |
| RV-P5-04 N3 | nit | FN examples drawn only from REJECTED/match | `GoldSetEvaluation.scala:527` | **G**: the order requires ≥10 FN examples; UNRESOLVED/CONFLICT misses are not shown |
| RV-P5-04 N4 | nit | Membership file write can leave a partial file | `GoldMembership.scala:438-450` | **G**: create-once membership manifest integrity |
| RV-P5-05 #2 | minor | An arXiv ID and a `10.48550/` DOI of a different submission are never related; the pair is same-stage-preprint and can be inferred-ACCEPTED | `Corroboration.scala:181` | **G**: possible false positives on preprint pairs |
| RV-P5-05 #3 | nit | `Adjudication.eligible` trusts the stored stage verdict | `Adjudication.scala:66` | — |
| RV-P5-05 #4 | nit | `evaluate` does not refuse duplicate member IDs built in code; `coverage()` maps non-canonical labels to `""` | `GoldSetEvaluation.scala:248, 516` | **G** (code path only; CLI ingest prevents it) |
| RV-P5-05 #5 | nit | Manifest hashed, then re-read (TOCTOU); the SHA requirement is procedural | `GoldSetEvalMain.scala:157-168` | **G**: held-out freeze guarantee |
| RV-P5-06 nit 1 | nit | ACCEPTED outcome can list a rule ID that says "unresolved" | `Resolver.scala:425-436, 563-572` | — (provenance clarity) — in scope of ER-P5-10 |
| RV-P5-06 nit 2 | nit | Preprint-prefix validation accepts non-canonical prefixes; an upper-case prefix silently disables the decisions-07 block | `MatchPolicy.scala:558-561` | **G** (fails open on a mis-edited policy file) |
| TE-P5-01 | characterization | Comma-form DBLP homonym ("Name, 0001") behaviour pinned as-is | `DblpMasking` | **G**: blind-view masking for labelers |

## dataset-engineer

| ID | Sev. | Finding | Location (as reported) | Impact |
|---|---|---|---|---|
| RV-P5-02 #3 | minor | dblp excerpt bytes not counted against the 8 MiB sample budget | `dsutil.py:1565` (RV-P5-05) | **A** |
| RV-P5-02 #5 | minor | `selection.tsv` columns (`criterion`, `expect_*`, unit IDs) carry dblp identity claims and must be documented as withheld from labelers; ER blind view already excludes criterion and unit IDs (`BlindView.scala:141-143, 237-268`) | `selection.tsv`, schema/manifest | **G**: label independence (schema-side documentation still open) |
| RV-P5-02 #6 | minor | Cross-split entity overlap check only prints INFO; dblp excerpts not included | `dsutil.py:1705` (RV-P5-05) | **G**: dev/held-out separation; **A** |
| RV-P5-02 #8 | minor | `--verify --with-release` can pass without reading the release (not reachable today) | `dsutil.py:1612-1615` | **A** |
| RV-P5-02 #9 | nit | `selection.tsv` comment still names `dblp.org/rec` URLs (comment-only edit needs human agreement) | `selection.tsv:10` | **A** (documentation) |
| RV-P5-02 #10 | nit | Two dblp keys can map to one file name (caught later as "listed twice") | `dsutil.py:809` | **A** |
| RV-P5-02 #11 | nit | Release hashed and scanned in two passes | `dsutil.py:1147-1149` | **A** |
| RV-P5-02 #12 | nit | Crash between `mv` and sidecar write forces a re-download | `fetch-dblp.sh:99-100` | **A** |
| RV-P5-03 N1 | nit | Torn-tail repair unchanged | `dsutil.py:1145-1160` | **A** |
| RV-P5-03 N2 | nit | Writes an absolute path | `dsutil.py:1549-1552` | **A** |
| RV-P5-03 N3 | nit | Prints `rm '$part'` unescaped | `lib-datasets.sh:297, 299, 303` | **A** |
| RV-P5-03 N4 | nit | `ds_md5` result unchecked | `lib-datasets.sh:332` | **A** |
| RV-P5-03 N5 | nit | Log line unchanged | `lib-datasets.sh:361` | **A** |
| RV-P5-03 N6 | nit | `--full` wrapper only text-checked | download wrapper | **A** |

Details for RV-P5-03 N1-N6 are in the RV-P5-03 section of review-report.md; the descriptions above are the RV-P5-05 one-line status summaries.

## build-engineer

| ID | Finding | Impact |
|---|---|---|
| process | `sbt check` takes 120-128 s, near the 180 s tool limit that auto-backgrounds calls (cause of recovery-01 and recovery-04); ~27 MB of Hadoop DEBUG/INFO log output truncates the console summary | **G** and **A** indirectly: every agent-run verification; not yet routed |
| FE-P5-01 follow-up | `sbt goldSetEval` alias (allowlist row inactive `B10-alias-pending`); only after FE-P5-01 ratification | **G**: the required gold-set evaluation command |

## factory-engineer / human

| ID | Item | Impact |
|---|---|---|
| FE-P5-01 | amendment draft (G9-a `gold/**` → HUMAN, G9-b `sbt goldSetEval` row); ratification and session restart pending | **G**: where human labels may live and how the evaluation is run |
| gate | ≥200 human-reviewed pairs (none exist); then the held-out gold-set evaluation with the recorded manifest SHA-256 | **G** |
| gate | full dblp download (not authorized); then offline extraction and the held-out membership run | **A**, then **G** |

## In flight

| Work order | Scope |
|---|---|
| ER-P5-10 (entity-resolution-engineer) | COMPLETE (agent-report.md): er-policy.v6; `sbt entityResolution/test` 324/0 (agent-reported); no full `sbt check` yet. RV-P5-06 nit 1 fixed for the preprint-DOI rule only; still open for the arXiv rule (pinned on an ACCEPTED outcome at `PolicyV4Suite:217`). |

## Added by ER-P5-10

| ID | Owner | Item | Impact |
|---|---|---|---|
| ER-P5-10 Q1 | human decision | Should a shared dblp-key / scopus-eid also be blocked when the trigger is the arXiv cross-stage block (bare arXiv ID shared, no preprint DOI)? Today such pairs ACCEPT at 0.99; a characterization test pins it | **G**: possible preprint/published merges |
| ER-P5-10 pins | test-engineer | 7 tests/** tests pin er-policy.v5 (MatchContractCrossModuleSuite ×4, ClusterCorroborationAdversarialSuite ×3); predicted, not observed | blocks the next full `sbt check` |
