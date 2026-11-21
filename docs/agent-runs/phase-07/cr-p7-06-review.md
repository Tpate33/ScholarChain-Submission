# CR-P7-06 (code-reviewer): re-review of BE-P7-04 (F-C fix) — orchestrator-condensed record

The full report is in the session transcript.

- VERDICT: APPROVE. STATUS: COMPLETE. Read-only (Read). Test evidence: human-observed `sbt check` exit 0, 129 suites, 1711 passed, 2026-11-20 14:24:19 CST; self-test tables counted in source: 27 + 29 = 56 broken, 12 + 8 = 20 allowed.
- **F-C closed** while the lexer is in sync: `scanText` runs `tokenHits(bare)` with rename and backtick-escape (RagSourceImports.scala:576-585). All three CR-P7-04 failure inputs traced to `path` at line 1 (input 1 via plain-string blanking :341/:354; input 2 via splice code kept in `bare` :350-353, :383; input 3 Java). Before the fix the raw and `code` streams paired literal backticks (:160-165), so the new self-tests fail without it.
- Splices and line numbers: `emit` (:253-262) blanks literal/comment characters, keeps `\n`; nested splices recurse up to MaxNesting (:351-353).
- No remaining evasion of the same class with the lexer in sync: char literals (:299-306), triple-quoted strings (:328-340), Java text blocks (:344), comments (:312-324, :370-373), raw interpolators all blanked in `bare`.
- Self-test cases at :1121-1139 assert `Hit(1, "path", <pkg>)`; doc comment (:43-49) matches the code; no new false-positive class found.
- Territory: only partly verified (untracked file; no diff available to the reviewer).

## Findings
- **m1 (minor, non-blocking; depends on deferred U-a), build-engineer:** a non-ASCII interpolator id (RagSourceImports.scala:268 with :160-165) desyncs the lexer, so the splice is blanked in `bare` while literal backticks pair in raw and `code`. Failure input: ``object A { val p = sé"`${org.apache.hadoop.fs.Path.SEPARATOR}`" }`` (needs a custom `sé` extension on StringContext in rag). Impact: a forbidden fully qualified reference passes; contrived. Suggested fixes (not applied): the U-a lexer problem for a byte ≥ 0x80 before `"`, or stop pairing backticks in the raw/`code` tokenizer.

## Unverified
- U-b with F-C: ``raw"`\${...}`"`` if scalac does not treat `\$` as an escape.
- Whether Scala 3.3.6 treats `\"` as non-terminating inside `s"..."`/`raw"..."` (lexer assumes so, :344).

## Residual risks
- Raw and `code` streams still pair backticks inside literals; F-C closed only while the lexer is in sync. Other CR-P7-04 residuals unchanged; rag scan vacuous (0 sources).

## Routing (orchestrator)
F-C is closed (APPROVE). m1 is in the class the human deferred (U-a: "Defer optional scanner refinements U-a/U-b unless necessary to resolve F-C"; "Resolve the current major F-C finding without expanding scanner scope"). It is recorded as a tracked residual for the human's decision at closeout, not fixed now. Practical exposure today is nil: rag has no sources, and any rag code is reviewed by code-reviewer (T5 rule extended to foreign-interpolator checks).
