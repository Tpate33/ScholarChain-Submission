# Phase 11 repair 02 (service-engineer, SV-P11-01)

Findings routed: CR-P11-01-4 (minor, duplicate JSON keys, T2), CR-P11-01-5 (minor, unpaired surrogates, T3), U1 (unverified digest collision), CR-P11-01-6 (minor, null kind NPE, T9). Owner: service-engineer.

Change summary (orchestrator): `QueryRequest.parse` adds a strict scan rejecting duplicate member names at any depth and unpaired UTF-16 surrogates (raw or `\uXXXX`) with 400 invalid-request and fixed messages; `QueryRequest.digest` now hashes an ASCII-escaped rendering (U1 collision observed before the change, distinct after); `ServiceRuntime.build` checks `kind == null` first. tests/** untouched.

Rerun evidence: final `sbt check` exit 0, 999 s; markTestRun suites=207, passed=2388, skipped=1. tests project 270 total, 269 passed, 0 failed, 1 skipped (T2, T3, T9 pass, inferred from 0 failures). Audit flag: the background markTestRun write was attributed to a concurrent read-only `git branch --show-current` call; human review pending.

## Agent report (verbatim)

I made all four fixes, and the one full `sbt check` exited 0 with every test green (2388 passed, 1 skipped, 0 failed). I still have to return BLOCKED-ON human: the shell-write audit hook flagged a change to the test marker and told me to stop until you review it.

**What the hook flagged.** `sbt check` was running in the background, and I was making read-only `git` polling calls while waiting for it. Its `markTestRun` step rewrote `.claude/.last-test-run` (output line: `markTestRun: wrote .../.claude/.last-test-run (git_head=a49317a..., worktree=dirty, suites=207, passed=2388, skipped=1)`). The audit hook pinned that write on my concurrent `git branch --show-current` call and reported "possible test-marker forgery". I believe this is the legitimate `sbt check` write landing during a parallel call, which the hooks don't support. I did not touch or repair the marker. The orchestrator should have the human confirm it with `git status` and the marker contents.

**The fixes, all in `modules/services/**`:**
1. **Duplicate keys (CR-P11-01-4, T2).** After ujson parses a body, `QueryRequest.parse` now runs a linear scan, `strictProblem`, over the text. A member name repeated within one object, at any depth, is rejected with `400 invalid-request` and a fixed message. Names are compared after escape decoding, so `"question"` and `"question"` count as the same. Nothing from the body is echoed and the workflow is never called. `/v1/query` is the only route that reads a body.
2. **Unpaired surrogates (CR-P11-01-5, T3).** The same scan rejects any string, name or value, that holds an unpaired UTF-16 surrogate, including one sent as a JSON `\uXXXX` escape. It returns `400 invalid-request` with a fixed message. Format and bidi characters (U+202E and similar) are unchanged: they still reach the workflow, and C0 controls are still 400 "control characters".
3. **U1 digest collision.** Before my digest change, the new test failed: `QueryRequest("\udc00Ada…").digest` and `QueryRequest("?Ada…").digest` were both `9f7bea6da2da…`. So the reviewer's concern was real for directly built requests. I changed `digest` to escape non-ASCII before hashing (`render(escapeUnicode = true)`), and the two now differ. Over HTTP, with one `Idempotency-Key` and either order, the lone-surrogate body is always 400 and never replays the `?` body's answer, and the `?` body is always a fresh run. Side effect: the digest value changes for every non-ASCII question. That is harmless today because the idempotency store is in memory only.
4. **Null `kind` (CR-P11-01-6, T9).** `ServiceRuntime.build` checks `kind == null` before any call on it and returns `Left("kind: required (the ServiceKind to compose)")`.

**Evidence that each test fails without its fix:** only the U1 digest test was seen failing before its change (`sbt services/test` gave 108 total, 1 failed). I wrote the duplicate-key, surrogate, U1 HTTP and null-`kind` tests after the fixes were in place, so I did not watch them fail. The before-fix failures for T2, T3 and T9 are TE-P11-02's observations, not mine.

**Acceptance tests in `tests/**`, not edited:** the `tests` project ran 270 tests: 269 passed, 0 failed, 1 skipped (`SemanticRetrievalCorpusSmokeSuite`, "(b) neighbours: researcher ID -> community summaries naming it"). With no failures, these all passed:
- MalformedBodyAdversarialSuite T2
- MalformedBodyAdversarialSuite T3
- ConfigOmissionAdversarialSuite T9, both tests

I did not read their individual result lines in the log.

```text
STATUS: BLOCKED-ON
OWNER: service-engineer
WORK ORDER: SV-P11-01 (Phase 11 repair 02): CR-P11-01-4 (T2), CR-P11-01-5 (T3), U1, CR-P11-01-6 (T9)
FILES CREATED:
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/test/scala/scholarchain/services/RepairP11Suite.scala
FILES MODIFIED:
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/main/scala/scholarchain/services/QueryService.scala
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/main/scala/scholarchain/services/ServiceRuntime.scala
COMMANDS RUN:
  sbt services/test   (1st: Total 108, Failed 1, Passed 107; the failure was the U1 digest test, before the digest change)
  sbt services/test   (2nd: Total 108, Failed 0, Passed 108)
  sbt check           (backgrounded by the harness; exit code 0; Total time 999 s)
  read-only git polling calls (status, log, diff --stat, rev-parse, branch --show-current, ls-files, show --stat) while sbt check ran
  Refused by the guard, nothing executed: one `ls`; `sbt services/testOnly scholarchain.services.RepairP11Suite`
TEST RESULTS (sbt check, observed):
  core 621/621; entityResolution 464/464; hadoop 85/85; rag 141/141; root 0; services 108/108;
  sparkAnalytics 263/263; storage 437/437; tests 270 total, 269 passed, 0 failed, 1 skipped.
  markTestRun: suites=207, passed=2388, skipped=1.
  RepairP11Suite: 9/9 passed.
  tests/**: MalformedBodyAdversarialSuite T2 passed; MalformedBodyAdversarialSuite T3 passed; ConfigOmissionAdversarialSuite T9 (both tests) passed. Inferred from 0 failures in the tests project; I did not read the per-test lines.
VALIDATION RESULTS:
  Status mapping: duplicate member name -> 400 invalid-request; unpaired surrogate -> 400 invalid-request (both fixed messages, no echo, workflow not called); not UTF-8 JSON -> 400 invalid-json (unchanged); other codes unchanged.
  Route table, timeout policy, dependency graph (services -> core, rag, storage) and authentication (bearer API key, SHA-256 digest, constant-time compare, scopes) are unchanged.
  U1: digest collision confirmed before the change (both 9f7bea6da2da...), distinct after; over HTTP no replay across the two bodies under one Idempotency-Key.
ASSUMPTIONS:
  Rejecting unpaired surrogates in every body string (names and mode included), not only in the question, is in scope: it also stops unknown-field-name echo of ill-formed text.
  Changing the digest value for non-ASCII questions is acceptable because the idempotency store is in memory only.
RISKS:
  I did not watch the duplicate-key, surrogate, U1 HTTP and null-kind tests fail without their fixes; their before-fix failures rest on TE-P11-02's observations of T2, T3 and T9.
  The digest change would invalidate any persisted idempotency record if a durable store were added later.
MANUAL ACTION REQUIRED:
  Human: review the audit report on .claude/.last-test-run (git status and the marker contents). I believe the change is the legitimate markTestRun write from the background `sbt check`, pinned on my concurrent read-only git call. Decide whether to accept it or rerun `sbt check` with no concurrent calls.
BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE:
  audit-bash-writes.sh --post: ".claude/.last-test-run (owner: GENERATED; changed by 'git branch --show-current', which is not 'sbt check' or 'sbt clean check': possible test-marker forgery)".
  The same window's sbt check output: "markTestRun: wrote .../.claude/.last-test-run (git_head=a49317a803bb9b44e3ba9314b12254dca5dd39e4, worktree=dirty, suites=207, passed=2388, skipped=1)", then "[exited with code 0]".
NEXT GATE: The human resolves the marker report. Then test-engineer reruns T2, T3 and T9, code-reviewer rereads the repair-02 diff, and the Phase 11 gate is decided (CR-P11-01-1 sits with rag-engineer).
```

Full `sbt check` log (harness task output, outside the repository): /private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/603f947b-a302-4647-a1c7-50d71f450e42/tasks/b17f5o2ij.output
