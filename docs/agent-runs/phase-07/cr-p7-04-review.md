# CR-P7-04 (code-reviewer): re-review of BE-P7-03 (F-A and F-B fixes) — orchestrator-condensed record

The full report is in the session transcript.

- VERDICT: REQUEST_CHANGES.
- STATUS: COMPLETE. Read-only (Read and Grep); the run results were not observed by the reviewer.
- **F-A closed** for every traced form: `as` and `=>` renames, `_root_`, chained packages, `export`, and a rename inside a splice.
- **F-B closed** for the original input, which is now caught by both `path` and `comment-at-dot`.

## Finding F-C (major, build-engineer)
- **Defect:** the tokenizer pairs backticks that sit inside string literals (RagSourceImports.scala:156-162). The path, selector, wildcard and package rules run only on the raw and `code` streams, and both keep literal text (lines 573-577). So two literal backticks swallow a fully qualified forbidden reference that sits between them.
- **Failure inputs** (all PASS today):
  1. `object A { val a = "`"; val b = scholarchain.storage.aws.s3.S3ObjectStore; val c = "`" }`
  2. `object A { val p = s"Call `${org.apache.hadoop.fs.Path.SEPARATOR}` now" }`. This is a Markdown code span in an interpolated string, a form common in prompt strings.
  3. Java: `String a = "`"; org.apache.hadoop.fs.Path p = null; String b = "`";`
- **Impact:** the main path rule is defeated by ordinary-looking code. The doc comment's claim to cover fully qualified references is untrue.
- **Fix:** also run `tokenHits(tokens(lexed.bare))`, or take backticked identifiers only from lexer code state. Add three self-test cases, each expecting `path` at line 1.

## Unverified concerns (build-engineer, optional)
- **U-a:** a non-ASCII interpolator such as `sé"..."` desyncs the lexer, so a rename hidden inside its splice is missed. Fix: raise a lexer problem when the character before `"` is 0x80 or above.
- **U-b:** `\$` is treated as an escape inside interpolated strings. If scalac does not do the same, `raw"\${...}"` hides a splice.
- **U-c:** XML literals could desync the lexer. scala-xml is not on rag's classpath, so this does not compile today.
- **U-d:** whether Scala 3.3.6 supports the `$"` escape. If it does not, the model built for U3 is itself a desync.

## Confirmed without a finding
- Fail-closed behaviour: every lexer problem fails the file, and files cannot be skipped.
- False positives: the listed ones are acceptable; normal port and model imports, lambdas and interpolations pass.
- suspicious-char: the category set and the UTF-8 validation are correct, and the BOM exception applies only at offset 0.
- File-system safety: the R2 refusal is present, links are not followed, deletion stays inside target/rag-source-imports.
- Self-test asserts rule, package and line for each case.
- Territory: project/** only.

## Residual risks
- The rename rule runs only on the lexer's output, and the split `storage // c` followed by `.aws` on the next line is still uncovered (disclosed).
- R1 (awsAligned) is open.
- R4 (storage port re-exports) belongs to storage-engineer.
- The rag scan is vacuous until rag has sources.
