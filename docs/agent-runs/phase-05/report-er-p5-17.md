# ER-P5-17 final report (entity-resolution-engineer; verbatim)

Archived by the orchestrator, 2026-11-14. Work order: `labels file=... sha256=...` print line in GoldSetEvalMain (D5 L1; decisions-19, section 8 step 2).

---

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-17 (Phase 5; amendment-fe-p5-06, D5 option L1, D15(c), routing in decisions-19). GoldSetEvalMain now prints a labels-evidence line naming the labels file and the sha256 of its raw bytes.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/evaluation/LabelsEvidenceSuite.scala (9 feature tests, synthetic fixtures only)

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/evaluation/GoldSetEvalMain.scala
  - New `def labelsLine(path: Path, sha256: String): String`, which returns `labels file=<path.toAbsolutePath.normalize> sha256=<hex>`.
  - New `def readLabelsWithSha256(p: String): Either[String, (Path, String, Vector[String])]`. It reads the file once with `Files.readAllBytes`. The lowercase hex SHA-256 and the decoded lines both come from those same bytes. Decoding is strict UTF-8 (malformed or unmappable input is refused). Lines split on `\n`, `\r\n` or `\r`, and the empty element after a final terminator is dropped. This gives exactly what `Files.readAllLines(UTF_8)` gives, so label parsing does not change. The error message is still `cannot read <p>: <msg>`.
  - In `runWithConfig`, the labels read now uses this helper instead of `readLines` (the queue still uses `readLines`). A local `labelsEvidence` is set only after a successful read.
    - Success path: printed exactly once, right after the `manifest sha256=... freeze=...` line. That is before the settings, coverage, metric, sensitivity and report-json lines.
    - Refusal path: printed after the config evidence, but only if the labels file was actually read.
  - The scaladoc gained a "Labels evidence (ER-P5-17, D5 L1)" bullet.
  - Unchanged: metrics, thresholds, policy, label parsing (`GoldLabels.ingest`), the split check and the freeze/hash check.

COMMANDS RUN:
- git ls-files -- modules/entity-resolution
- git ls-files --others --exclude-standard -- modules/entity-resolution
- git ls-files --others -- modules/entity-resolution/src/test/scala/scholarchain/entityresolution/evaluation
- grep / git grep: three attempts, all refused by guard-dangerous.sh (no-allowlist-entry or shell-syntax-gate). Nothing ran. I used Read instead.
- sbt check (foreground)
- sbt entityResolution/testOnly scholarchain.entityresolution.evaluation.LabelsEvidenceSuite: refused by the guard (no allowlist entry). Nothing ran.
- sbt entityResolution/test (foreground, after sbt check, no source change between them)
- git status --porcelain

TEST RESULTS:
- `sbt check` succeeded: `[success] Total time: 135 s (0:02:15.0)`. Observed per-project counts:
  - core: Total 299, Failed 0, Errors 0, Passed 299
  - entityResolution: Total 393, Failed 0, Errors 0, Passed 393
  - hadoop: Total 84, Failed 0, Errors 0, Passed 84
  - tests: Total 145, Failed 0, Errors 0, Passed 145
  - rag, root, services, sparkAnalytics, storage: 0 tests
  - markTestRun: `wrote .../.claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=76, passed=921, skipped=0)`
- `sbt entityResolution/test`: Total 393, Failed 0, Errors 0, Passed 393. All 9 LabelsEvidenceSuite tests are listed as passed:
  1. held-out run: exactly one labels line with the absolute path and the raw-byte sha256
  2. the labels line appears before the coverage and every metric line
  3. a relative labels argument is printed as an absolute, normalized path
  4. the hash is of the raw bytes: CRLF labels hash differently and parse identically
  5. dev run: the labels line carries the DEV marking and the split suffix
  6. a missing or unreadable labels file is refused and prints no labels line (missing file, a directory, non-UTF-8 bytes)
  7. labels not reached (manifest refused first) print no labels line
  8. labels read but refused at ingest: the refusal still names the file and hash once
  9. readLabelsWithSha256: lines equal Files.readAllLines for the same bytes (12 byte patterns: CR/LF/CRLF, trailing blanks, empty, BOM, non-ASCII)
- Existing suites still pass, including the FreezeSplitConfigSuite rule that every dev line starts with `DEV ` and ends with ` split=dev`.
- Tests 1-3, 5 and 8 assert the new line exists, so they fail without the change. Test 9 calls the new helper, so it does not compile without it.

VALIDATION RESULTS:
- I did not run a gold-set evaluation (the work order forbids `goldSetEvalHeldout` and `goldSetEvalDev`). The hash's correctness is checked only against synthetic fixtures. In the tests the sha256 is computed independently with `MessageDigest` plus `String.format`, not through any module helper.
- Metrics, thresholds and policy are unchanged, so policy version, features, thresholds, model and prompt versions, confusion matrix, unresolved rate and threshold sensitivity have nothing new to report.
- I did not read any real gold-label file or any path covered by rule R6.

ASSUMPTIONS:
- On the success path the line gets the report's usual split marking, as the `config file=... sha256=...` evidence line already does. Held-out output is `labels file=<abs> sha256=<hex> split=heldout`; dev output is `DEV labels file=<abs> sha256=<hex> split=dev`. The text before the marking is exactly the format in the work order. I chose this because the module's documented rule is that every report line ends with `split=`, and an existing test enforces it for dev. On the refusal path, where no split is established, the line is unmarked: `labels file=<abs> sha256=<hex>`. If the orchestrator wants the bare format on every path, that is a one-line change plus updating the existing dev-marking test.
- The path is `toAbsolutePath.normalize` without resolving symlinks, matching how the config and freeze evidence print paths.
- No line is printed when the labels are refused as not UTF-8, even though the bytes were read. That is the existing refusal ("cannot read"). Hashing a file whose read was refused would make it look like evidence for an evaluation that never happened.
- A labels file that was read but then refused at ingest still prints the line once in the refusal output. That makes refusals traceable to exact bytes.

RISKS:
- The labels file is now held fully in memory as bytes and then as decoded text, as the manifest already was. Label files are small, so the impact is negligible.
- Any downstream parser that expects the bare format on held-out success lines must allow the ` split=heldout` suffix and the `DEV ` prefix (see ASSUMPTIONS).
- `sbt check` output is very large (27.4 MB, mostly Hadoop DEBUG logging in the tests project), so counts must be read from the end of the output.

MANUAL ACTION REQUIRED: Review the diff and decide whether the labels line should carry the split marking (current behaviour) or stay bare on every path. Commit is human-only.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of GoldSetEvalMain.scala and LabelsEvidenceSuite.scala, then the human gold-set evaluation under D15(c) (`sbt goldSetEvalHeldout`, human-only), whose output should now include the labels line with the file's sha256.
```
