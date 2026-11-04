# Phase 4 repair 02: final-review blocker B1 (documentation only)

- **Findings routed:** B1 (unsupported claim in `modules/hadoop/docs/job-contracts.md:180-181` that the real DBLP dump has no comments, CDATA or PIs) and the stale bridge wording nit (`job-contracts.md:299-301`). Source: code-reviewer final review (work order 7), `review-report.md`.
- **Routing basis:** human follow-up instruction 10.
- **Owner:** hadoop-engineer. Documentation only; only `modules/hadoop/docs/job-contracts.md` changed (per agent; `git status` cannot isolate it because `modules/hadoop/docs/` is untracked).
- **Rerun evidence:** no `sbt check` (only a `.md` file changed). Re-check by code-reviewer follows.

## Tracked items deferred by the human (instruction 10)

| Item | Owner | Status |
|---|---|---|
| O-ZW: Unicode format characters (Cf: ZWJ, ZWNJ, ZWSP, word joiner, soft hyphen, BOM, bidi marks) inside names are folded to spaces and split family keys (`modules/entity-resolution/src/main/scala/scholarchain/entityresolution/TextFolding.scala:70-81`) | entity-resolution-engineer | **Prerequisite before Phase 5**; deferred from Phase 4 |
| UnicodeSuite:161-165 prints but does not assert ZWJ/ZWNJ keys | test-engineer | Deferred, pair with O-ZW |
| Other optional items from the final review (stale v2 test name and `v2Fields`; script trap window and lock-before-trap; malformed non-publication records counted as skipped; unbounded block-key length) | as listed in `review-report.md` | Deferred |
| Required checks before future AWS execution (full-snapshot markup scan; EMR MainClass vs manifest Main-Class; operator IAM for head-object; AWS CLI/API assumptions; shellcheck and executable bit; cost-bound semantics; Typesafe Config; Scala libraries in the Spark jar; giant-team scale) | as listed in `review-report.md` | Open; before any AWS run |
| DOCTYPE-only input not refused by `refuse-if-present` (`MARKUP_TOTAL` excludes `doctype_declarations`, per hadoop-engineer repair 02) | infra-engineer | Open; before any AWS run |

## hadoop-engineer, repair 02 (final report, verbatim)

I made the documentation-only repair to `modules/hadoop/docs/job-contracts.md`. No other file was edited and no Scala source or test was touched. `git status --short` cannot prove that by itself: the file sits in the untracked `modules/hadoop/docs/` directory, so it shows only `?? modules/hadoop/docs/`. The output I saw is identical, line for line, to the status at the start of the session.

### Edits (line numbers are in the edited file)

**E1, B1, required edit 1.** The unsupported sentence is deleted. The true statement about the DTD is kept as its own sentence.
- Before (old 178-183):
  - "**Residual limitation.** If `s - (W + 16)` itself falls inside a construct whose remaining bytes contain a markup-opener look-alike (...) whose closer is within W bytes, a split read and a whole-file read can disagree. The real DBLP dump has no comments, CDATA, or PIs in its body; the external DTD is never read."
  - Then the "Compressed files are not split ... External entities and DTDs are never loaded." bullet.
- After (new 181-183): the Compressed bullet is unchanged, followed by the new sentence "The external DTD (`dblp.dtd`) is never read."
- The residual-limitation bullet moved into the new section below as item 1.

**E2, required edit 2: new section "XML-reader limitations (the single authoritative statement; not resolved in Phase 4)" (new 185-210).** It has three items:
1. **Split versus whole-file divergence (187-198).** The lookback start can fall inside a comment, CDATA, PI or DOCTYPE whose tail holds an opener look-alike with a closer within W. Possible effects: a lost record (no work, no reject), a ghost work, or an `UnclosedMarkup` report that appears or disappears. The output then depends on split size, which breaks determinism. This case is NOT covered by any test.
2. **Markup longer than W (199-204).** It is treated as unclosed. Records inside it become works in both whole-file and split reads. The only signal is one `malformed-xml` report on the opener.
3. **Real input not characterized (205-210).** Whether the `dblp-2026-10-03` body contains comments, CDATA, PIs or DOCTYPE/internal subset is UNVERIFIED. The full dump has not been downloaded (`pending-human-download`, `format.encoding` UNVERIFIED in the manifest), and the committed sample begins with a comment. Limitations 1 and 2 cannot be ruled out for real data.

**E3, required edit 3: new paragraph "Required verification before processing the full snapshot (or any non-smoke input)" (new 212-230).**
- Before a run, a markup scan of the exact input bytes (identified by sha256, decompressed if gzip) must report counts of comments, CDATA, PIs (excluding the XML declaration), and DOCTYPE and ENTITY declarations. Any non-zero count needs a recorded human decision first.
- It points to `scripts/run-bounded-pipeline.sh --preflight-scan` and `dblp_markup_policy=refuse-if-present` (and `accept-reported`) for smoke runs.
- It states that no equivalent scan exists for the full snapshot: the script is smoke-only, refuses functional runs, and caps input at 64 MiB. One must be provided and run first. That work is outside `modules/hadoop` (infra workflow and human), owner not decided.
- I checked the script source (lines 677-760) and added two facts it shows:
  - **DOCTYPE is not gated.** `MARKUP_TOTAL` leaves out `doctype_declarations`, so `refuse-if-present` does not refuse when DOCTYPE is the only construct present. The doc says a non-zero DOCTYPE count still needs the human decision.
  - **The scan is heuristic.** The script prints "heuristic line scanner".

**E4, bridge nit, required edit 4 (old 298-302, new 344-348).**
- Before: "Pair expansion uses the positional builder `CoauthorPairCandidate.of(x, posX, y, posY, ...)`: O(1) hashing per pair (plus core validation, 2 SHA-256 per pair). The former 6-argument bridge (O(teamSize) hashes per pair, about n^3/3 per team) is no longer called by this module, so full runs no longer carry that cost. A 1000-author team expands to 499,500 pairs in about 3 s in the unit test."
- After: "Pair expansion uses only the 8-argument positional core builder `CoauthorPairCandidate.of(mention_i, i, mention_j, j, workId, year, n, provenance)` (`jobs/TeamJob.scala:52-61`): O(1) hashing per pair (plus core validation, 2 SHA-256 per pair). It is the only `CoauthorPairCandidate.of` overload in core. A 1000-author team expands to 499,500 pairs in about 3 s in the unit test."
- I confirmed in `modules/core/.../Collaboration.scala:93-102` that only the 8-argument `of` exists, and in `TeamJob.scala:52-61` that this call is the one used.

**Required edit 5: other unevidenced claims about the real dump, and two consistency qualifiers.**
- **E5, parameter table (line 85).**
  - Before: "`UTF-8`, `ISO-8859-1`, or `US-ASCII` (the dump's XML declaration)"
  - After: "`UTF-8`, `ISO-8859-1`, or `US-ASCII`; must match the XML declaration of the input (for the pinned snapshot this is UNVERIFIED, manifest `format.encoding`)"
- **E6, Lexical model (old 158-159, new 159-162).**
  - Before: "...outside markup; records are direct children of `<dblp>` and no field element is named like a record type."
  - After: "...outside markup. The model assumes, from `dblp.dtd` (manifest `format.file_format`), that records are direct children of `<dblp>` and that no field element is named like a record type; this is not verified against the bytes of the pinned snapshot."
- **E7, qualifier (old 152, new 152-153).**
  - Before: "...the same for every split size (README 1166, 2243):"
  - After: "...the same for every split size (README 1166, 2243), except in the untested case described under "XML-reader limitations" below:"
- **E8, qualifier (old 175-177, new 178-181).**
  - Before: "...opener inside the lookback window and `s` is classified exactly as a whole-file read classifies it."
  - After: "...opener inside the lookback window and, provided the lookback start itself is outside markup (limitation 1 below), `s` is classified exactly as a whole-file read classifies it."
- E7 and E8 do not make claims about the dump. Without them, the absolute determinism claims would contradict the new limitation section.
- No other statement about the real dump was found.

```text
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 4, repair 02: documentation-only fix of final-review blocker B1 plus the stale bridge-wording nit, in modules/hadoop/docs/job-contracts.md only.
FILES CREATED: none
FILES MODIFIED: modules/hadoop/docs/job-contracts.md
COMMANDS RUN: git ls-files --others --exclude-standard -- modules/hadoop/src/main (listed hadoop sources, to locate TeamJob.scala); git status --short (ran successfully; output identical to the session-start status).
TEST RESULTS: none. sbt check was not run because only a .md file changed, which the work order allows. No Scala source or test changed.
VALIDATION RESULTS: I re-read the edited passages and they say what was intended. Evidence checked: datasets/dataset-manifest.yaml lines 69-86 (snapshot dblp-2026-10-03, pending-human-download, encoding UNVERIFIED); datasets/samples/dblp/dblp-sample.xml lines 1-2 (XML declaration, then a comment); scripts/run-bounded-pipeline.sh lines 16, 22, 45-46, 58-59, 118-120, 221, 429, 673-761 (preflight scan, smoke-only, heuristic scanner, MARKUP_TOTAL leaves out DOCTYPE); Collaboration.scala:93-102 and TeamJob.scala:52-61 (only the 8-argument of). git status cannot isolate this file because modules/hadoop/docs/ is untracked.
ASSUMPTIONS: The effects listed for limitation 1 (lost record, ghost work, UnclosedMarkup report appearing or disappearing) come from the lexer design. No test shows them. Reporting the script's DOCTYPE gap is a factual reference to scripts/**, not a change to it.
RISKS: The residual D1 divergence and the treatment of markup longer than W remain in code and are untested. No full-snapshot markup scan exists. refuse-if-present does not refuse when DOCTYPE is the only construct found. The scan is heuristic.
MANUAL ACTION REQUIRED: Before any full-snapshot or non-smoke run, a human must decide who provides the full-snapshot markup scan (infra workflow), run it, and record a decision for any non-zero count. The human may also want infra-engineer to consider gating doctype_declarations in run-bounded-pipeline.sh.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of B1 and the bridge nit against job-contracts.md, then human Phase 4 acceptance.
```
