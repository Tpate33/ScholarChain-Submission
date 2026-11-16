# Phase 5 human decisions after ER-P5-01 (transcribed by the orchestrator)

Received 2026-11-04 in the orchestrator session, in response to the ER-P5-01 report
(`agent-report.md`). This is a verbatim transcription of the human's instruction, not a
human-gate record; `human-gate.md` remains human-authored.

```text
Proceed with the proposed owner routing, subject to these decisions:

1. Conflict policy: differing identifiers alone do not establish that two
   unrelated records represent a conflicted identity. Define when a pair
   represents a plausible identity match. For such pairs, authoritative
   identifier disagreements must prevent automatic acceptance, including
   acceptance through another matching identifier. Test these cases.

2. OpenAlex IDs: do not blanket-downgrade or omit the README's required exact
   identifier rules. Distinguish author IDs from work IDs and document their
   scope, reliability, and conflict behavior. Any supporting-only treatment
   needs an explicit rationale and review against the professor's requirements.

3. Prompt location: keep the adjudication prompt with the entity-resolution
   module if the ownership map permits it; preserve prompt/schema versioning.

4. Blind labeling: yes. Hide system scores, decisions, and match paths from
   the labeling view. Show source evidence and allow uncertain labels.
   Do not generate gold labels or treat synthetic examples as real evidence.
   Keep evaluation labels out of policy development and threshold tuning.

Route domain-engineer and dataset-engineer first. Then prepare the narrowly
scoped factory/build changes for the human-owned label path and evaluation
command. Present constitutional changes for my ratification; do not activate
them or continue under changed permissions before the required session restart.

After contract integration, run independent testing and review. Provide a
traceable queue of at least 200 real candidate pairs for my manual review.

Track the interface-only LLM adjudication as an unresolved acceptance item:
explain how actual adjudication and its evaluation will be exercised.
Do not make paid or external model calls without my approval.

Preserve the Unicode prerequisite acceptance records. Do not commit, push,
deploy, or run AWS operations. Do not claim Phase 5 complete before its
evaluation requirements and gates are satisfied.
```

## Orchestrator routing plan derived from these decisions

1. DE-P5-01 `domain-engineer`: MatchDecision / config / identifier-scheme contract extensions.
2. DS-P5-01 `dataset-engineer`: additional real, licensed sample records to yield >= 200 real
   candidate pairs covering reject, conflict, and band strata.
3. FE-P5-01 `factory-engineer` (proposal only, presented for ratification, not activated) and
   BE-P5-01 `build-engineer` (alias): human-owned gold-label path and `goldSetEval` command.
4. ER-P5-02 `entity-resolution-engineer`: contract integration, decisions 1-4, blind labeling
   view, real review queue.
5. TE-P5-01 `test-engineer`, RV-P5-01 `code-reviewer`.
6. Human labeling, then gold-set evaluation under ratified permissions in a fresh session.

Open acceptance item OA-1: LLM adjudication is interface-only. No paid or external model call
without human approval.

Sequential execution: agents run one at a time because the audit hooks serialize Bash calls
on a per-session lock and fail closed on concurrent use.
