# Phase 5 prerequisite (Unicode format characters): status and remaining limitations

> **PREREQUISITE WORK ONLY. THIS IS NOT COMPLETION OF PHASE 5.**
> This closes (pending human acceptance) only the first "Accepted deferral" in
> `docs/agent-runs/phase-04/human-gate.md`. None of the main Phase 5 work has started: no scoring policy, no
> thresholds, no match decisions, no LLM adjudication, no gold-set labels or evaluation. The other Phase 4
> optional deferrals and the "Required before any AWS execution" list are untouched.

## Status

| Item | State |
|---|---|
| Fix (ER-P5-00, `entity-resolution-engineer`) | done; uncommitted |
| Dependent test-literal and docs update (HD-P5-00a, `hadoop-engineer`) | done; uncommitted |
| Regression assertions (TE-P5-00, `test-engineer`) | done; uncommitted |
| `sbt check` | 443 passed, 0 failed (agent-run on a dirty tree; see `prereq-unicode-test-report.md`) |
| Focused review (RV-P5-00, `code-reviewer`) | **APPROVE**, no blockers, 2 non-blocking notes |
| Human gate (`human-gate.md`) | **not written**: human only |
| Commit (`commit.txt`) | **not made**: human only |
| Constitutional files, AWS, push | not touched |

## What changed in behaviour

- `TextFolding.fold` deletes every Unicode category Cf code point (zero-width space, ZWJ, ZWNJ, word joiner,
  soft hyphen, BOM, LRM/RLM/ALM, bidi embeddings, overrides and isolates, tag characters, ...) as the first
  per-code-point step, instead of turning it into a space. `TitleText.words` removes Cf before stripping a
  trailing editorial note.
- Raw `rawName`, `title` and profile names are unchanged; only derived keys and features change.
- `BlockingKeys.Version` `er-blocking.v1` -> `er-blocking.v2`; `MatchFeatures.Version` `er-features.v1` ->
  `er-features.v2`.

## Non-blocking review notes (kept open by human instruction; owner `entity-resolution-engineer`)

Not fixed and not routed. Full detail in `prereq-unicode-review-report.md`.

- **NIT-1. Lone surrogates around a Cf character.** `removeFormat` can fuse a lone high and a lone low
  surrogate separated by a Cf into one valid code point (`"\uD800​\uDC00"`: `fold` gives `""`,
  `TitleText.words` gives `"𐀀"`). Deterministic, but the Scaladoc equivalence claim is false for this input.
  Reachable only through JSON `\u` escapes in enrichment sources. The fuzz test does not cover it.
- **NIT-2. Cf as the only separator before a DBLP homonym number.** `"Christopher Frost"+U+200B+"0001"` now
  parses as family `frost0001` with no homonym suffix (block `frost0001|c` instead of `frost|c`). This is a
  recall loss for that input. It has no test and no explicit note.

## Open policy decisions for the human

1. **Cf as the only word separator joins words** (`Jun`+ZWSP+`Wang` -> `junwang`, key `junwang|-`). Accepted
   and documented by `entity-resolution-engineer` and pinned by tests. Whether a ZWSP should instead count as a
   separator is a policy choice. NIT-2 is one consequence.
2. **Hadoop `AuthorMention.normalizedName` still keeps Cf characters** (for example W3 `"jian wa<ZWJ>ng"` differs from
   W1). This follows the contract ("mechanical, no matching policy"), and no key reads it. Any future consumer
   that matched on `normalizedName` would still split these names. Owner if a change is wanted:
   `hadoop-engineer`.
3. **v1 and v2 outputs are not comparable.** Curated blocks and candidates produced under `er-blocking.v1` /
   `er-features.v1` must not be mixed with v2 outputs. No v1 outputs exist in any cloud environment (no AWS run
   has occurred).

## Remaining limitations of the evidence

- **No clean-tree test run.** Every `sbt check` ran on an uncommitted tree. The marker records
  `git_head=d16e8c9..., worktree=dirty`. A clean-tree `sbt check` on the accepted commit is still required for
  commit evidence (`docs/agent-runs/README.md`, `test-report.md` rule).
- **Test counts are agent-reported.** The orchestrator has no `sbt` allowlist row. It observed the git state
  and `verify-tests-ran.sh --check` OK, but no `sbt` output directly. The code-reviewer ran no commands.
- **Regression sensitivity was hand-traced, not executed.** No new test was run against the pre-fix code. Two
  agents and the code-reviewer traced it by hand.
- **The deleted set depends on the JDK's Unicode tables** (Temurin 17.0.17 observed). A JDK upgrade that changes
  category Cf would change keys and require a new version. U+180E's classification was not confirmed.
- **No repo-wide grep by implementation agents.** Neither `entity-resolution-engineer` nor `hadoop-engineer`
  could run `grep`/`git grep` (no allowlist row, no Grep tool). The code-reviewer's Grep over the repository
  (excluding `docs/agent-runs/**`) found `er-blocking.v1` / `er-features.v1` only in history comments.
- **`sbt check` output is ~27 MB**, mostly Hadoop DEBUG/INFO logging, and was not archived. Log configuration
  owner: `build-engineer` (not routed).
- **Gold-set evaluation was not run.** It is not part of this prerequisite, and the effect of v2 on match
  quality is unmeasured.
- **Routing beyond the prompt.** The human prompt named `entity-resolution-engineer` and `test-engineer`. The
  orchestrator also routed `hadoop-engineer` (test literals and docs in its territory) under the BLOCKED-ON
  rule. See `prereq-unicode-repair-01.md`.

## Suggested human next steps

1. Review the diff (13 paths) and this evidence set.
2. Decide on NIT-1, NIT-2 and policy decisions 1-3 (accept as documented, or route a follow-up work order).
3. Run `sbt check` on the tree you intend to commit, and record the result in your `human-gate.md`.
4. Commit and record the hash. Then give the orchestrator the main Phase 5 work order in a fresh session.
