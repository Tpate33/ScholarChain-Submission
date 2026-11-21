# CR-P7-02 (code-reviewer): re-review of BE-P7-02 (F1, F3 and F4) — orchestrator-condensed record

The full report is in the session transcript.

- VERDICT: APPROVE.
- STATUS: COMPLETE. Read-only: no files changed and no commands run. The test results are build-engineer's evidence plus the human marker review; the reviewer did not observe them.
- Scope: project/RagSourceImports.scala, project/DependencyScopes.scala, and build.sbt lines 127-132, 712-723 and 752-770.

## Findings (all owned by build-engineer)
- **F-A (minor).** A Scala 3 unbraced rename of an ancestor package gets past every rule. Example: `import scholarchain.storage as st` followed by `st.aws.s3.X`; likewise `import org.apache as a`. The doc comment at RagSourceImports.scala:41-47 claims this form is covered. Suggested fix: add a rule for an ancestor run followed by `as` or `=>`, with self-test cases.
- **F-B (minor).** The comment stripper loses sync inside string-interpolation splices. Example: `s"${'"'}" + scholarchain.storage./**/aws...` gets past the scan (RagSourceImports.scala:169-184). Suggested fix: track `${` nesting, or make the rules independent of the stripper.
- Both evasions are about the future: the adapter packages and rag sources do not exist yet.

## Residual risks
- R1 (nit): `awsAligned` still filters by org only; unmanaged rag jars and Parquet's non-Apache transitives are not checked.
- R2 (nit): the self-test scratch parent accepts a pre-planted symlink.
- R3 (nit): false positives such as `{port, model as _}`. Ordinary port/model imports were verified not to trigger the scan.
- R4 (storage-engineer): anything that storage's port packages re-export, or the main-scope `fake` package, is visible to rag.
- R5 (nit): a mislabelled self-test case in DependencyScopes.scala:431-436.

## Verified without a finding
- Import and reference forms covered: plain, wildcard, braced, multi-line, `=>` renames, fully qualified terms and types, export, `_root_`, backticks, relative references, chained package clauses, Java imports and static imports, Java unicode escapes.
- Encoding and lexing: ISO-8859-1 decoding is safe; nested comments and triple-quoted strings are handled.
- Coverage: the cross-check catches `sourcesInBase`, extra source directories and managed sources.
- File-system safety and deterministic output.
- F4 mapping in both files: each self-test input maps to its intended check.
- F3: `com.amazonaws` is now checked on the rag classpaths.
- Placement: the check runs before markTestRun, and a FAIL stops `check`.
- Territory: all changes are within build-engineer's paths.

## Real negative run
Not needed for acceptance, in the reviewer's judgement. The file fixture exercises the same scan and the same throw path. Recommendation: let rag's first real source change serve as the live test, or have rag-engineer run a temporary-file run under its own work order.

## Unverified
- U1: whether scalac accepts non-ASCII whitespace between `.` and a segment.
- U2: whether unicode escapes are processed inside backticked identifiers.
- U3: whether the `$"` quote escape can also break the stripper's sync.
- U4: whether rag code could compile against an adapter whose SDK types are absent from rag's classpath.

## Next
The human accepts the verdict, and either routes F-A and F-B to build-engineer now or defers them explicitly. Re-review once rag has real sources.
