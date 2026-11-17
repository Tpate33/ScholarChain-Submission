# Phase 06 CR-P6-25: code-reviewer consolidated review of the SP-P6-03 milestone (2026-11-17)

- Scope: uncommitted tree on HEAD c2d9a82: modules/core (DE-P6-03), project/SparkItRuns.scala (BE-P6-07), modules/spark (steps 2A, 2B, 2C). Evidence read: step reports, sparkit-run-06.md, sparkit-run-07.md, attempt-5 result files.
- Reviewer tools: Read, Grep, Glob. No commands run.

## Verdict: APPROVE

No critical or major findings.

## Checked, no defect (reviewer summary)

Hash-min components (GraphStructureJob.scala:384-412; anchor = ComponentId, StableIds.scala:799-813); label propagation determinism, tie rule, fixed points, iterations at the bound (:415-463; StructureValidation.scala:111-122), hand derivations w1/w3/w5 recomputed; bridge counts = checkBridges (:524-537; AnalysisValidation.scala:761-790), w5 z recomputed; k-core peeling (:466-515), w4 recomputed; CanonicalGraphJob rule-for-rule equal to CanonicalGraph.expected (CanonicalGraph.scala:302-504); stored shortest paths exact vs selectShortestPaths (AnalysisValidation.scala:990-1031), prefix pruning proved; sampled-BFS sources and distance counts (StableIds.scala:946-969); temporal records = checkTemporal (StructureValidation.scala:394-480); summary facts from the vocabularies citing the subject's records; load-manifest row totals reconcile with run 07 (1258, 1302, 2809, 249), create-once beside the run; DE-P6-03 digest unchanged with keys unset, schemas registered, StableIds consistent; CR-P6-21 m1/n1/n2 closed (HadoopCuratedRun.scala:353-416 and SparkItRuns.scala:856-911 match Pipeline.scala:418-423, :516-521, :646-666; pin behind `private[input]` VerifiedArchiveReader.scala:36-55; CRC checker CuratedParts.scala:21-65; n2 tests VerifiedArchiveReaderSuite.scala:1000-1060); driver receives counts and small sets only, checkpoints released in `finally`; isolation clean (no AWS client, s3://, ARN, gold/ or held-out path).

## Findings

- f1 (minor; domain-engineer; AnalysisValidation.scala:1141-1195 `checkSummaryInputs`): a fact's evidence is not bound to the input's subject. Failure input: a researcher-profile for r1 whose `publicationCount = "5"` cites ResearcherMetrics of r2 (same window, also 5) passes; likewise componentSize citing an unrelated component in path or community summaries. Impact: the executable contract for course item 10 accepts misattributed facts (RAG grounding); inconsistent with checkEntitySummaries (StructureValidation.scala:612-637), which binds evidence. Spark producer unaffected (SummaryJob.scala:93-129, :182-184, :221-226 cite the subject's own records). Fix: per document type require the cited record to be the subject, its component/community, or a hop edge of the subject path; counter-tests.
- f2 (minor; orchestrator plan, spark-engineer code; emr-plan-draft.md:9-19): the plan omits that the reader and publishers use the local java.nio file system (RunTree.scala:6, :143, :214; VerifiedArchiveReader.scala:3; RunPublisher.scala:5, :332, :377; LoadManifests.scala:5, :146-147). An EMR step with s3:// roots fails; with file:// scratch on a multi-node cluster, the driver lists only its own disk. Fix: add a prerequisite "Hadoop-FileSystem reader and publisher (S3 or HDFS) with create-once semantics on S3" (spark-engineer); atomic rename does not carry over to S3.
- n1 (nit; spark-engineer; PathJob.scala:585): `PathLengthStats.supersteps = d - 1` reports -1 when no component reaches minComponentSize (summary value only).
- n2 (nit; build-engineer; project/SparkItRuns.scala:857): `i.toInt` on an unbounded round name throws (fails closed with an exception, not a refusal message); the reader uses toIntOption. Already recorded in BE-P6-07.

## Carried findings

CR-P6-21 m1, n1, n2: closed. CR-P6-24 m1 (nullability claim, LogicalReproducibility.scala:13-15, :85-86): open. CR-P6-24 n1 (:57-60): open. CR-P6-24 n2 (AnalysisRun.scala:232 `outputs` spark-only): open. CR-P6-24 n3: not re-inspected. CR-P6-24 n4: evidence only. CR-P6-22 N1, N2 and CR-P6-23 F1, F2 (build-engineer): open, unaffected.

## Course coverage (reviewer)

Met locally: items 1-10, O1, O3, O4, G1 and G2 as reported. Partial: item 5 sampled branch exercised by unit tests only (all 37 dev estimates exact); item 11 "partitioned" as part-file directories; O2 holds for Spark jobs but the reader decodes all inputs on the driver; O5 logical only; R1 no DAGs or shuffle metrics. Not met: G3 (AWS gate, plus f2). Noted: the run manifest's inputSnapshots name dblp, openalex, crossref (two enrichment sources in this dev chain).

## Unverified concerns (reviewer)

Peeling depth at DBLP scale (about four jobs per round); one String per CSV partition (CanonicalGraphJob.scala:621-660) at full scale; driver-side decoding and CanonicalGraph.expected in VerifiedArchiveReader.scala:242-279 (fail-closed above maxDriverValidation*, so dev/sample scale only); CSV row counts writer-reported (RunPublisher.scala:374-376); non-InvariantFailure exceptions in GraphStructureJob.compute leave persisted RDDs and the checkpoint directory; ATOMIC_MOVE onto an empty directory is POSIX-local only.
