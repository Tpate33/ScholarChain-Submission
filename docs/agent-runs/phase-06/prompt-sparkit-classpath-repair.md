# Phase 06 SparkIT classpath repair: work order (verbatim)

Received by the orchestrator on 2026-11-17 after sparkit-run-02.md (compile failure). The text is the human's own words.

```text
Delegate the SparkIT classpath repair to build-engineer. Confirm why
the existing Spark dependencies are missing from SparkIT's compile
classpath and make the smallest build change that supplies them to
both the compiler and the forked integration runner. Keep the existing
Spark and Scala versions and preserve the job jar's Provided setup.

Add archive-independent compilation of the actual src/it suite to
sbt check. Keep archive access and integration execution opt-in via
standalone sbt sparkIT. Preserve the existing preflight and route guards.

Run sbt check once and report explicit evidence that SparkIT compiled.
Get one focused review and return the exact commit scope and approval
wording for the next retry. Do not rerun sparkIT until I approve the
reviewed commit. Leave the archive and Phase 5 evidence intact.
```
