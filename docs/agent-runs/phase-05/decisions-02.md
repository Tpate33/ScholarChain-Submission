# Phase 5 human instructions after ER-P5-03 (transcribed by the orchestrator)

Received 2026-11-05, in response to the ER-P5-03 identifier-policy table. This is a verbatim
transcription, not a human-gate record.

```text
Before implementation, resolve these two points:

1. P-2 says a DBLP-key disagreement blocks automatic acceptance, but your
   example accepts two different DBLP keys via a shared DOI. Specify the
   precedence explicitly, including which stronger evidence may override
   a source-record disagreement. Preserve the disagreement in reason codes.
   Apply the same clarity to OpenAlex author-ID disagreements.

2. Define the Phase 5 work-labeling target now: whether preprint, proceedings,
   and journal versions are distinct entities or one underlying work.
   Phase 6 aggregation may remain deferred, but human gold labels need an
   unambiguous definition before labeling starts.

I agree in principle with versioning the changed policy and describing
uncalibrated constants accurately. This is not blanket approval of P-1,
P-2, P-6, or the OpenAlex treatment.

For the proposed 1.03 GB download, provide the exact script/command,
source URL, expected download and extracted sizes, output paths, and
why a smaller bounded retrieval cannot provide the required real pairs.
Prepare this for review; do not download yet.

Keep the previously selected held-out groups fixed across policy-version
changes. Regenerate queue identifiers if necessary, without reselecting
examples based on policy outcomes.
```

## Status of decisions after this instruction

| Item | Status |
|---|---|
| Versioning the changed policy (P-3 in principle) and accurate uncalibrated wording (P-4 in principle) | agreed in principle |
| P-1, P-2, P-6, OpenAlex author treatment | NOT approved; pending precedence specification |
| P-7 / work-labeling target | pending; must be defined before labeling |
| dblp 1.03 GB download | NOT approved; review packet requested |
| Held-out groups (`datasets/er-candidates/split-assignment.tsv`) | fixed across policy versions; no reselection based on policy outcomes |

## Orchestrator routing

- ER-P5-04 `entity-resolution-engineer` (read-only): precedence specification and
  work-labeling target options.
- DS-P5-02 `dataset-engineer` (read-only): dblp download review packet.
- Both are instructed to make no Bash calls (parallel-safe under the per-session audit lock) and
  to write no files.
