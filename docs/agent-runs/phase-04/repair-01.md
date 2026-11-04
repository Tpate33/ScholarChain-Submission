# Phase 4 repair 01: DBLP reader defects D1, D2, D3

- **Findings routed:** D1 (record inside an XML comment becomes a work at small split sizes), D2 (unclosed record swallows following valid records), D3 (normalize output depends on split size). Source: test-engineer, work order 4 (`test-report.md`), failing tests in `tests/src/test/scala/scholarchain/tests/adversarial/AdversarialXmlSuite.scala`.
- **Routing basis:** human follow-up instruction 6: "Route implementation defects to their owning agents rather than changing their files."
- **Owner:** hadoop-engineer (`modules/hadoop/**`). `tests/**` was not changed.
- **Rerun evidence:** `sbt check` reported by hadoop-engineer as passing with 407 tests (core 252, entityResolution 56, hadoop 67, tests 32), markTestRun wrote the marker. Not re-run by the orchestrator.

## hadoop-engineer, repair 01 (final report, verbatim)

D1, D2 and D3 are fixed. `sbt check` passed in full: 407 tests passed, 0 failed, and markTestRun wrote the marker. The 3 test-engineer tests that were failing now pass, and nothing under tests/** was changed. All edits are under modules/hadoop/**.

### Results from `sbt check`
| Project | Passed | Failed / errors |
|---|---|---|
| core | 252 | 0 / 0 |
| entityResolution | 56 | 0 / 0 |
| hadoop | 67 (59 existing + 8 new) | 0 / 0 |
| tests | 32 | 0 / 0 |
| rag, root, services, sparkAnalytics, storage | 0 (no tests) | — |

markTestRun line:
`[info] markTestRun: wrote .claude/.last-test-run (git_head=8c679adfac2fd5d2d62697973d2d4c326f0e813e, worktree=dirty, suites=32, passed=407, skipped=0)`

In the 64-byte-split adversarial run the DBLP counters reconcile: INPUT.dblp=22, REJECTED=6, REJECTED.malformed-xml=4, REJECTED.missing-field=1, REJECTED.unterminated-record=1, SCHEMA_FAILURE=6. The whole-file run used to show INPUT.dblp=20; the two neighbour records it lost are now read.

### Root cause
- **D1:** a reader that started mid-file did not know whether it was inside a comment, CDATA section, processing instruction or DOCTYPE. It took the first `<article` it saw as a record start (old `DblpScanner.next`, lines 84-111). The old comment at lines 47-52 assumed this could not happen.
- **D2:** `readRecord` (old lines 162-178) read up to the first matching end tag. It ran straight over the start tag of the next top-level record, so an unclosed record swallowed the valid records after it.
- **D3:** follows from D1 and D2.

### The fix (`modules/hadoop/src/main/scala/scholarchain/hadoop/dblp/DblpInputFormat.scala`)
- **New reader.** `DblpScanner` is now a byte-level lexer (lines ~266-451) that knows about comments, CDATA, processing instructions and `<!` declarations. Declarations include the DOCTYPE with quotes, the bracketed internal subset, and comments or processing instructions inside it. Nothing inside these constructs counts as a tag.
- **Length limit on constructs.** An opener only opens a construct if its closer is within W bytes, where W is the existing `scholarchain.dblp.maxRecordBytes`.
  - Otherwise the opener is read as plain text.
  - The split that holds the opener reports it once as a new `UnclosedMarkup` raw record, rejected `malformed-xml`.
  - So an unclosed comment cannot swallow the records after it either.
- **How a split starts reading (D1).** `DblpScanner.streamStart` (lines 85-90) puts the start of reading W + 16 bytes before the split start.
  - `DblpRecordReader.initialize` seeks there; the reader reads from there and emits nothing before the split start.
  - Every construct is at most W + 9 bytes long, so any construct containing the split start opens inside that window. The split start is then classified the same way a whole-file read classifies it.
  - Cost: at most W + 16 extra bytes per split.
- **Recovery after a bad record (D2).** In `readRecord`, if another record start tag comes before the end tag (lines 489-495), the record stops just before it. It gets status `Interrupted`, with `nextOffset` set to that tag. The reader carries on from that tag, so only the bad record is rejected.
  - The reason is `malformed-xml`, not `unterminated-record`. The adversarial suite expects exactly one `REJECTED.unterminated-record`, from the truncated file.
- **Raw record format.** `DblpRawRecord` now carries `status` (Complete, Unterminated, Interrupted, UnclosedMarkup) and `nextOffset`, and both are serialized. `complete` is still available.
- **Mapper.** The classification moved into a new pure function, `DblpRawOutcome.of`, in `dblp/DblpMapping.scala`. `DblpMapper.normalize` in `jobs/NormalizeJob.scala` now calls it.
- **Contract doc.** The DBLP reader section of `modules/hadoop/docs/job-contracts.md` now gives the lexical model, the W limit, record extent, the ownership rule, the read-start rule with its bound and cost, and the residual limitation. The reject conditions, the parameter row and the fixture lists were updated too.

### New tests (`DblpSynchronizationSuite`, 8 tests)
The fixture has "ghost" records inside a comment longer than many splits, inside processing instructions, inside CDATA, inside record content (CDATA and comments), and inside the DOCTYPE internal subset. It also has two unclosed records followed by valid ones, and a never-closed comment followed by valid records. The tests check:
- The expected records and statuses of a whole-file read, at W = 512 and W = 1 MiB.
- A two-way split at every byte offset, at both W values.
- Every split size from 1 to 64, plus sizes 97, 128, 200, 333, 511, 512, 513, 1000 and 2048, at both W values.
- Splits that start inside each construct.
- How records are classified: interrupted records and unclosed markup are `malformed-xml`, a cut-off record is still `unterminated-record`, and the neighbouring records are accepted.
- That the raw record survives serialization.
- The real `DblpInputFormat` record reader at six split sizes plus the whole file, with identical classified outcomes.

`DblpScannerSuite` and `DblpMappingSuite` only had their helpers updated to the new scanner signature, which now takes the read-start offset. All their existing assertions pass unchanged.

### Remaining limitation
A split read and a whole-file read can still disagree in one narrow case. That is when the point where reading starts (split start minus W + 16) falls inside a construct whose remaining bytes contain text that looks like an opener, such as `<?` or `<![CDATA[` inside a comment, and that fake opener has a matching closer within W bytes. The real DBLP dump has no comments, CDATA or processing instructions in its body. This is documented in the code and in job-contracts.md.

```text
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 4, repair of defects D1, D2, D3 found by test-engineer (work order 4)
FILES CREATED:
- modules/hadoop/src/test/scala/scholarchain/hadoop/dblp/DblpSynchronizationSuite.scala
FILES MODIFIED:
- modules/hadoop/src/main/scala/scholarchain/hadoop/dblp/DblpInputFormat.scala (scanner rewritten; DblpRawRecord status/nextOffset; reader seeks to streamStart)
- modules/hadoop/src/main/scala/scholarchain/hadoop/dblp/DblpMapping.scala (new DblpRawOutcome)
- modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/NormalizeJob.scala (DblpMapper delegates to DblpRawOutcome)
- modules/hadoop/src/test/scala/scholarchain/hadoop/dblp/DblpScannerSuite.scala (scan helper uses streamStart)
- modules/hadoop/src/test/scala/scholarchain/hadoop/dblp/DblpMappingSuite.scala (new constructor signature)
- modules/hadoop/docs/job-contracts.md (DBLP reader contract, reject conditions, parameter row, fixtures)
COMMANDS RUN:
- git status --short (twice), git diff --stat
- sbt hadoop/test: success, Passed: Total 67, Failed 0, Errors 0
- sbt check: success (107 s); build-source, version-ledger and scalafmt checks OK; all test projects passed; markTestRun wrote the marker
- refused by guard, nothing ran: cat of fixtures, git grep (2 shapes); used Read instead
TEST RESULTS (sbt check):
- core: Passed 252, Failed 0, Errors 0
- entityResolution: Passed 56, Failed 0, Errors 0
- hadoop: Passed 67, Failed 0, Errors 0 (59 existing + 8 new in DblpSynchronizationSuite)
- tests: Passed 32, Failed 0, Errors 0 (the 3 AdversarialXmlSuite D1/D2/D3 tests now pass; tests/** not changed)
- rag, root, services, sparkAnalytics, storage: 0 tests
- markTestRun: wrote .claude/.last-test-run (git_head=8c679adfac2fd5d2d62697973d2d4c326f0e813e, worktree=dirty, suites=32, passed=407, skipped=0)
VALIDATION RESULTS:
- Every 2-way split offset and every split size 1..64 plus 9 larger sizes give records/rejects identical to a whole-file read, at W=512 and W=1 MiB
- Real DblpInputFormat at split sizes 16/64/100/257/1000/1500 matches the whole file: 29 accepted, 3 malformed-xml (2 interrupted records, 1 unclosed comment), no ghost key
- Adversarial 64-byte run: INPUT.dblp=22, REJECTED=6 (malformed-xml 4, missing-field 1, unterminated-record 1), SCHEMA_FAILURE=6; Ghost is not a work; Good4/Good5 accepted; normalize output identical between split sizes
- Local bounded pipeline (LocalPipelineSuite inside sbt check) passed; normalize INPUT.dblp=4, ACCEPTED.dblp=4, REJECTED=0
- No dedicated `sbt localPipeline` run: that allowlist row is inactive (B10-alias-pending)
ASSUMPTIONS:
- scholarchain.dblp.maxRecordBytes doubles as the markup limit W, so no new manifest parameter is needed (the harnesses' manifests would refuse an unknown or missing one)
- An unclosed record cut at the next record start is rejected malformed-xml (README 2243 resynchronization; the adversarial suite needs exactly one unterminated-record)
- A comment/CDATA/PI/declaration not closed within W is malformed: it is reported and its opener read as text, so records after it are recovered
RISKS:
- Residual limitation above (fake opener inside a construct that straddles the read-start point); documented, not reachable with the real DBLP body
- Each split reads W + 16 extra bytes (1 MiB at the test value); not measured on EMR
- INPUT.dblp now also counts unclosed-markup reports; they reconcile as REJECTED
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer reviews the modules/hadoop diff (DblpInputFormat.scala, DblpMapping.scala, NormalizeJob.scala, DblpSynchronizationSuite.scala, job-contracts.md) and test-engineer's tests/**; human phase acceptance
```
