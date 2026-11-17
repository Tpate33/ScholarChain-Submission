# Phase 06 sparkIT run 01: SP-P6-02 step 1A, `sbt sparkIT` (spark-engineer, 2026-11-16)

- Work order: run standalone `sbt sparkIT` exactly once after CR-P6-20 APPROVE (human text in prompt-sp-p6-02-step-1a-repair-01.md).
- Gates observed by the orchestrator before dispatch: `git diff --stat e8c6ecc -- build.sbt project`, `git diff --stat --cached e8c6ecc -- build.sbt project` and `git ls-files --others --exclude-standard -- build.sbt project` all printed nothing. HEAD 5e176eb.
- Result: **REFUSED at preflight (NOT a pass).** No test ran, no attempt directory was created, no edge counts, JUnit result or output-integrity evidence exist.

## Agent report, verbatim (contract block and refusal text)

Refusal (sbt exit code 1, total time 0 s, Nov 16 2026 11:23:12 PM). The task printed `sparkIT: configuration and archive checks before compiling`, then:

```
[error] sparkIT: refused: archive /Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01 does not hold exactly the three pinned run directories of project/spark-it.properties: modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa (Hadoop curated dev input) has no regular _SUCCESS; <run>/blocking/round-0/blocks/work/.part-r-00000.gz.crc is more than 4 levels below the run directory (bound); <run>/blocking/round-0/blocks/work/part-r-00000.gz is more than 4 levels below the run directory (bound); ... (the same for blocks/researcher, members/work and members/researcher, each part-r-00000.gz and its .crc)
```

(As reported: `<run>` is the full curated run path; eight depth violations in total; the same message printed a second time as the `(sparkIT)` error line. The refusal names only the curated run.)

```text
STATUS: BLOCKED-ON
OWNER: spark-engineer
WORK ORDER: SP-P6-02 step 1A run 01 (Phase 6): run standalone `sbt sparkIT` exactly once and record the evidence; change no files.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- `git rev-parse HEAD` -> 5e176eb7b5641ea79b9b610933184bd85052be49
- `git status --porcelain` -> same as the session snapshot (the step-1A modules/spark changes and phase-06 evidence files)
- `git diff --stat e8c6ecc -- build.sbt project` -> no output (build still matches e8c6ecc)
- `sbt sparkIT` (once, foreground, timeout 600000 ms; no permission prompt was refused) -> exit code 1, refused during preflight (text above)
- `git ls-files -- project` -> 10 tracked build files, used only to find the check's source
TEST RESULTS: No tests ran; the refusal came before the SparkIT compile. No preflight-passed line, no PASS/FAIL verdict line and no per-class results. sbt total time 0 s.
VALIDATION RESULTS: No attempt evidence exists. Reading modules/spark/target/spark-it/attempt-1/result.txt returned "File does not exist", consistent with the design (attempt-<n>/ is created only after preflight and compile). No listing tool, so other attempt-* or .staging-*/.spark-* leftovers were not checked; nothing ran that could create them. No archive, gold/**, held-out, target/streams or target/local-runs/heldout read. No AWS.
ASSUMPTIONS: The refusal reflects the real archive contents as walked by project/SparkItRuns.scala layoutProblems.
RISKS: Integration evidence for step 1A is still missing. Changing the depth bound or the _SUCCESS rule weakens a reviewed guard (CR-P6-16 F5) and needs review again.
MANUAL ACTION REQUIRED: confirm the real layout of the Hadoop curated dev run (root _SUCCESS? parts 5 levels deep?) and decide whether the archive copy or the sparkIT layout check is wrong.
BLOCKED-ON OWNER: build-engineer (project/SparkItRuns.scala: curated-run _SUCCESS rule and MaxRunDepth = 4), with the human to confirm the archive is faithful (hadoop-engineer if the curated layout itself should carry a root _SUCCESS)
BLOCKED-ON EVIDENCE: the refusal above; project/SparkItRuns.scala line 106 `val MaxRunDepth: Int = 4`; project/spark-it.properties line 16.
NEXT GATE: owner repair, re-review, sbt check, build confirmed against the repaired commit, then one new `sbt sparkIT` under a new work order.
```

## Orchestrator analysis (2026-11-16)

- The archive is not the likely fault: prompt-archive-and-de-p6-02.md records the human's `diff -rq` showing all three archived runs byte-identical to their sources. So the source Hadoop curated run itself has no top-level `_SUCCESS` and has parts five levels below the run directory.
- The mismatch is between the actual Hadoop curated layout and two assumptions:
  1. build-engineer's sparkIT preflight (`project/SparkItRuns.scala`): every pinned run has a regular `_SUCCESS`; walk depth at most 4. BE-P6-03 recorded "The archived curated run contains `_SUCCESS`, as the work order states. I did not read the archive." (be-p6-03-report.md, ASSUMPTIONS).
  2. spark-engineer's reader (`VerifiedArchiveReader`): stage 1 also requires `_SUCCESS` for all three runs and a bounded tree walk (`maxTreeDepth=4` in the IT limits; CR-P6-19 unverified concern). Even with the preflight relaxed, the reader would likely refuse the same run.
- The authoritative definition of the Hadoop curated run layout and its completion marker belongs to hadoop-engineer (modules/hadoop, ErSplitPipeline / localPipelineDev). erDecisionGroupingDev accepted this run as "the one complete dev attempt", so completion may be signalled at another level (for example the attempt or per-job output directories). Not verified by the orchestrator.
- No change made. `sbt sparkIT` not retried.
