# Work order FE-P6-05: activate `sbt sparkIT` for spark-engineer

Human instruction (verbatim):

> I ratify FE-P6-04 in its inactive form. The reviewed build and inactive
> entry are committed. Read HEAD and have factory-engineer prepare the active
> allow entry for spark-engineer to run exactly standalone `sbt sparkIT`.
> Cite the reviewed build commit in the row, correct the output-scope
> wording, update the mirrored row, checksum and probe expectations, and run
> the required validators. Preserve the existing held-out protections.
>
> Return the exact activation diff and commit scope for my ratification.
> Provide draft wording for my human-gate record including the reviewed
> build SHA. Wait for ratification and restart before running sparkIT.

Orchestrator context:

- HEAD = `e8c6ecc400382307698e4308dc7f6bc3000890cd` ("Phase 6: add bounded Spark integration task and inactive permission"); working tree clean at session start.
- That commit contains the reviewed build (`build.sbt`, `project/SparkItRuns.scala`, `project/SparkItJUnitRunner.java`, `project/spark-it.properties`, `project/LocalRuns.scala`) and the inactive row `spark-engineer | inactive:FE-P6-04-ratification-pending | allow | - | sbt sparkIT`.
- Review evidence: `docs/agent-runs/phase-06/review-report.md` (CR-P6-16, CR-P6-17 APPROVE), `be-p6-03-report.md`, `repair-08.md`, `fe-p6-04-report.md`.
- sparkIT must NOT be run by anyone in this session.
