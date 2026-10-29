# Phase 2 work order (verbatim, as given to the orchestrator)

Use the build-engineer agent to create the ScholarChain sbt multi-project build and repository skeleton from scratch.

Required projects:
- core
- hadoop
- entityResolution
- sparkAnalytics
- storage
- rag
- services

Required principles:
- Scala 3 everywhere.
- Use Hadoop and Spark Java or DataFrame APIs where that avoids unsupported Scala binary assumptions.
- Pin every dependency and plugin version in a single version ledger.
- No floating or latest versions.
- Select one JSON library for the entire Scala codebase. Prefer uPickle unless a documented compatibility problem requires another choice.
- Select ScalaTest or MUnit and use it consistently.
- Keep Hadoop and Spark dependencies Provided when the runtime supplies them.
- Keep test-only libraries in Test scope.
- Keep logging implementation in Runtime scope.
- Use scalafmt with the Scala 3 dialect.
- Create a markTestRun task that updates .claude/.last-test-run only after tests pass.
- Define aliases: fmt, check, packageJobs, and dockerLocal.
- check must run format checking, compilation, tests, contract tests that do not need AWS, and markTestRun.
- Container images must use a pinned Java runtime, a non-root user, and percentage-based memory limits.
- Build behavior may depend only on explicitly documented environment variables.
- No secrets, cloud endpoints, or deployment procedures in the build definition.

Create:
- build.sbt
- project/build.properties
- project/plugins.sbt
- .scalafmt.conf
- .gitignore
- local compose file for emulators or test dependencies only when justified
- empty module source roots
- empty required top-level directories from the approved ownership map

Verify on a clean repository state with sbt Test/compile and then sbt check once the marker test exists.
Report the complete version ledger, module dependency graph, environment input surface, packaging choices, and any compatibility risk involving Scala 3, Hadoop, or Spark.
Additional context for Phase 2:
1. Phase 1 is fully completed, ratified, committed, and pushed to GitHub. The 18-agent system and security hooks are active. All 181 standard checks, 642 extended checks, and six mandatory live probes passed.
2. My local development environment:
   - macOS (Apple Silicon).
   - Java 17 installed and working.
   - Homebrew installed.
   - sbt launcher version 2.0.12 installed and working.
   - Standalone scalafmt is not installed. Configure scalafmt through the sbt plugin as required by the work order.
   - The repository does not yet have its Phase 2 build definition. Select and pin a compatible sbt version in project/build.properties.
3. The approved root .gitignore already exists and was committed during Phase 1. Do not recreate or overwrite it unnecessarily. Have build-engineer inspect it and make only justified changes within its ownership permissions, if required.
4. Use build-engineer for the build configuration and delegate other work only to the agents authorized by docs/agents.md. Follow all existing security hooks and ownership rules. Do not bypass safeguards to complete a task.
5. Prioritize the professor's explicit Phase 2 requirements and acceptance checks. Verify the Scala 3, Hadoop, Spark, and sbt compatibility choices using appropriate documentation before pinning versions. Do not introduce unnecessary features, additional modules, or unrelated infrastructure.
6. If sbt or scalafmt configuration requires additional local setup, clearly tell me what I need to install or configure. Do not assume AWS credentials, Docker, Terraform, or cloud infrastructure are already available.
7. Produce the required evidence and a clear PASS/FAIL report. If something cannot be completed, explain the actual blocker rather than claiming success.
8. Complete Phase 2 only. Do not start Phase 3, deploy cloud resources, commit, or push to GitHub without my explicit approval. Do not begin another open-ended security audit or unrelated repair cycle.
Please begin executing the professor's Phase 2 work order using the approved agent workflow.
