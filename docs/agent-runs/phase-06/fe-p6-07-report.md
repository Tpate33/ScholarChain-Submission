# FE-P6-07: post-restart probe validation (factory-engineer final report, verbatim)

```
STATUS: BLOCKED-ON
OWNER: factory-engineer
WORK ORDER: FE-P6-07 (Phase 6, validation only, no file changes): run `.claude/hooks/validate-agent-files.sh --probes` once, report every NOTE, FAIL and RESULT line verbatim, and compare with the human's Terminal run (888 table probes, 51 fixture probes, RESULT: FAIL (5 failures), only the five shellcheck FAIL lines).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
1. `.claude/hooks/validate-agent-files.sh --probes`, run once from /Users/taufeeqpatel/Desktop/ScholarChain. Exit code 1. I ran no other command.

TEST RESULTS: none (no sbt or application tests are in scope)

VALIDATION RESULTS:
The tool kept the start and end of the output and cut about 20,011 characters from the middle ("... [20011 characters truncated] ..."). So I can only report the lines I saw.

FAIL lines I saw, verbatim (all five are in the "== Hook scripts ==" section):
FAIL  shellcheck .claude/hooks/audit-bash-writes.sh
FAIL  shellcheck .claude/hooks/enforce-ownership.sh
FAIL  shellcheck .claude/hooks/lib-baseline.sh
FAIL  shellcheck .claude/hooks/post-write.sh
FAIL  shellcheck .claude/hooks/verify-tests-ran.sh

NOTE lines I saw: none. The lines `NOTE ... table probes run` and `NOTE ... fixture probes run` did not appear in the part of the output I could see.
RESULT line I saw: none. The visible output ends with `PASS  probe [cases] cleanup.sh by deploy-engineer -> BLOCK` and has no RESULT line after it.

Other results I saw:
- JSON: settings.json and .mcp.json both parse.
- `bash -n`: passes for all 9 hook scripts.
- shellcheck: passes for format-scala.sh, guard-dangerous.sh, lib-ownership.sh and validate-agent-files.sh.
- Hook registration: no duplicate registrations; every referenced hook exists and is executable; format-scala.sh, lib-baseline.sh and lib-ownership.sh are each called or sourced by a registered hook.
- Agent files: frontmatter and tool fences pass for every agent shown before the cut; the code-reviewer fence passes.
- Probes: every probe line shown after the cut ([misc] and [cases]) is PASS.

Comparison with the human's Terminal run:
- Table probe count (888): not confirmed, because the NOTE line was not visible.
- Fixture probe count (51): not confirmed, because the NOTE line was not visible.
- Failures: the five FAIL lines I saw are exactly the five shellcheck lines the human recorded, and the visible output has no extra failure. Exit code 1 is what a FAIL result should give. But I cannot rule out more FAIL lines in the ~20 KB that was cut, and I did not see a "RESULT: FAIL (5 failures)" line. So I can neither confirm nor deny that this run matches the human's run.

ASSUMPTIONS: The validator writes its summary at the end of its output (going by the human's report). Missing NOTE and RESULT lines mean they were lost from the captured output, not that the validator skipped them. I have not verified this.
RISKS: There may be more FAIL lines I did not see. I cannot capture the full output in a file because the Bash character gate refuses redirection, and the work order allows the command only once.
MANUAL ACTION REQUIRED: The orchestrator or human decides how to get the full output. Option 1: allow one more run, and in that run I read only the summary part. Option 2: the human compares the Terminal record they already have and pastes the NOTE, FAIL and RESULT lines into the next work order.
BLOCKED-ON OWNER: orchestrator (to route to the human)
BLOCKED-ON EVIDENCE: The tool output was cut by "[20011 characters truncated]"; no NOTE or RESULT line was visible; exit code 1; the five shellcheck FAIL lines quoted above were seen.
NEXT GATE: The human approves a second run of the validator or supplies the summary lines, then compares them with the record in docs/agent-runs/phase-06/human-probes-and-quota-2026-11-18.md.
MATRIX BASIS: factory-engineer allowlist row `.claude/hooks/validate-agent-files.sh ?--probes` (active, provenance hook). No amendment is proposed.
PRIVILEGE DELTA: none (unchanged for all agents)
FLOOR INVARIANTS: I made no changes, so every floor invariant is unchanged. Every visible floor probe (terraform apply/destroy/state, force push, rm -rf, secret reads, destructive AWS, EMR scale or termination, constitution writes, test-marker forgery, cleanup.sh) gives BLOCK as expected.
AUDIT RESULTS: Collision: no duplicate hook registrations (PASS seen); agent names unique in the part I saw. Orphans: every referenced hook exists and is executable, and every hook or library on disk is referenced (PASS seen). One-writer: the validator's ownership-mirror check may sit in the cut section and was not seen; nothing changed since the ratified commit 103d05d.
COMPLETE DIFF: none (I modified no controlled file. The tree is as at the start of the session: the only untracked file is docs/agent-runs/phase-06/prompt-resume-after-fe-p6-06.md, which is outside my territory. I did not re-check this with git, to stay within the single-command limit.)
OUT-OF-SCOPE ITEMS: The five shellcheck findings (CR-P6-33 m1) were deferred by the human and were not touched.
RATIFICATION REQUIRED: No constitutional change was proposed. Only the human can accept this validation evidence.
```

Orchestrator note: no additional FAIL line was visible, but the run is **not confirmed** to match the human's Terminal run (NOTE counts and RESULT line truncated from the agent's view). Routed to the human; sparkSubmitLocal not started.
