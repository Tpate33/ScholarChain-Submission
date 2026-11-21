# Phase 07: human decisions (2026-11-20), recorded by the orchestrator

The human's words, verbatim:

> Continue storage-engineer increment 2.
>
> After increment 2, prepare and test the proposed storage-only dependency fixes through the appropriate owners. Cite authoritative advisories, affected version ranges, and compatibility evidence. Do not describe any version as fully safe; state which identified advisories it fixes.
>
> I am not yet accepting unresolved vulnerabilities. Verify whether affected codecs are actually reachable, whether unused codecs can be excluded safely, and present any remaining risk requiring my acceptance separately.
>
> Address F-A/F-B after increment 2; restate those findings in the work order so their scope is clear.
>
> For the loader path, use local sbt execution during Phase 7 development and document exact invocation commands. Track deployed loader hosting and bounded live AWS integration as outstanding; this is not approval to omit assignment requirements.
>
> Keep builds sequential and obtain independent review. No live AWS operations, commits, cleanup, or hook changes.

## How the orchestrator applies them
1. Storage-engineer's increment 2 (SE-P7-03) continues. Nothing else runs sbt until it finishes.
2. Then, in this order:
   - (a) build-engineer: CR-P7-02 F-A and F-B, restated in full in the work order.
   - (b) the storage-only dependency fixes. The work order will cover:
     - storage-engineer establishes the codecs actually reachable, using the fixture Parquet metadata and Spark's writer settings;
     - build-engineer adds storage-only overrides or exclusions, with ledger keys;
     - a test that reads the fixture Parquet through the patched classpath.

     Wording rule: no version is described as "safe". Each report states which identified advisory a change fixes and which remain.
   - (c) the vulnerabilities that remain after (b) are presented to the human separately for acceptance. None is accepted yet.
3. Loader path:
   - Phase 7 runs loaders locally through sbt. The exact invocation commands will be documented.
   - Outstanding and tracked: hosting of the deployed loader, and the bounded live AWS integration (W1-W6).
   - This decision does not relax any assignment requirement.
4. Builds run one at a time, with an independent code-reviewer review of each change. The test marker is reviewed by the human after each increment.
