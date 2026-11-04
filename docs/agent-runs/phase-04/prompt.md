# Phase 4 prompt (verbatim work order given to the orchestrator)

Use the hadoop-engineer agent to implement the ScholarChain Hadoop MapReduce pipeline.

Required jobs:

1. DBLP record extraction
- Read complete DBLP publication records without splitting an XML record across mappers.
- Support the selected DBLP record types present in the chosen snapshot.
- Extract DBLP key, title, author sequence, year, venue fields, ee links, URLs, and record type.
- Emit valid normalized WorkRecord and AuthorMention records.

2. External source normalization
- Implement one normalizer per selected dataset.
- Map source fields into shared contracts without losing source-specific IDs.
- Preserve author identifiers, affiliations, topic identifiers, abstracts, and work identifiers when available.
- Write malformed and unsupported records to versioned reject outputs.

3. Exact identifier extraction and join preparation
- Normalize DOI, arXiv, ORCID, OpenAlex, Semantic Scholar, and other selected identifiers.
- Emit exact-identifier keys and duplicate or conflict counters.

4. Researcher and work blocking
- Emit bounded candidate blocks using normalized names, initials, coauthor context, title, year, venue, affiliation, and source.
- Prevent pathological common-name or title blocks from overwhelming reducers.

5. Candidate feature construction
- Join records within blocks.
- Compute deterministic match features.
- Emit MatchCandidate records rather than final guesses hidden inside logs.

6. Publication team extraction
- Emit one publication-team record containing the ordered author mentions for every retained work.
- Emit author-paper records keyed by author mention and work ID.
- Emit candidate unordered coauthor pairs for every paper with at least two authors.
- A paper with n authors generates n * (n - 1) / 2 candidate pairs before duplicates are consolidated.
- Preserve work ID, year, author position, and source provenance on every pair record.
- Count and report unusually large author teams because they can create quadratic pair expansion.

7. Graph export preparation
- Emit partitioned work, author-mention, team, pair-candidate, topic, institution, and other selected enrichment files for Spark.

Required operational behavior:
- Hadoop counters for input, accepted, rejected, duplicate, conflict, oversize block, oversize author team, emitted pair, missing key, and schema failure records.
- Explicit partition strategy.
- Idempotent output paths containing run ID and dataset snapshot.
- No mutable shared state across map or reduce tasks.
- Compression for intermediate outputs.
- Deterministic outputs for deterministic inputs.
- Unit tests for parsers, pair generation, normalization, and key functions.
- Mini-cluster or local integration tests for representative jobs.
- A bounded EMR smoke-run script owned by the infrastructure workflow, not by this agent.

Run sbt check and the local bounded pipeline.
Report job graph, mapper and reducer key-value contracts, partition counts, counters, rejected records, output paths, maximum observed team size, largest pair expansion, and the largest observed blocking group.

Required Hadoop data flow:

```text
DBLP XML --------------------> DBLP parser --------------------------+
                                                                    |
OpenAlex/ORCID/etc. ---------> source normalizers ------------------+--> identity-link inputs
                                                                    |
publication teams -----------> author-paper and pair expansion -----+--> coauthor pair candidates
                                                                    |
all normalized records ------> blocking-key mappers ----------------+--> candidate identity groups
                                                                    |
all prepared records -----------------------------------------------+--> S3 curated MapReduce output
```

## Follow-up instruction 1 (after hadoop-engineer run 1, verbatim)

Yes. Have code-reviewer review the current Phase 4 implementation against the professor’s exact README requirements.

Distinguish implemented behavior, missing requirements, and dependencies owned by other agents. Check whether suppressing all pairs for oversized author teams satisfies the required n*(n-1)/2 pair generation and whether placeholder blocking keys leave acceptance requirements unmet.

Return findings with file/line references and the authorized owner. Do not implement repairs yet, expand into Phase 5, modify ownership rules, commit, push, or run AWS operations.

After the review, propose only the work orders necessary to complete Phase 4.

## Follow-up instruction 2 (after code-reviewer review 1, verbatim)

Yes, start work order 1 with domain-engineer. Keep it limited to the shared contracts and fields needed to complete Phase 4, with validation, schema-version handling, and contract tests. Report the result before starting the next work order.

For the proposed repairs:
- Remove pair suppression. Emit every required n*(n-1)/2 pair, while counting and flagging oversized teams.
- Keep entity-resolution work limited to Phase 4 blocking-key and deterministic feature functions. Do not start Phase 5 scoring, thresholds, decisions, or LLM review.
- Include the required adversarial tests.
- Have infra-engineer author the bounded EMR smoke-run script during Phase 4, with local syntax checks only. Do not execute it or access AWS.
- Building the job jars can verify packaging, but do not claim that this alone proves compatibility with an actual EMR runtime.

Do not choose the DBLP subset silently. Bring me a concrete recommendation based on README and the existing manifest before a run needs that choice.

Preserve agent ownership. Do not change constitutional rules, commit, push, deploy, or start Phase 5.

## Follow-up instruction 3 (after domain-engineer work order 1, verbatim)

Run one focused code-reviewer pass on the new and changed core contracts before work order 2.

Check Phase 4 coverage, schema versions, validation, fixed JSON examples, and compatibility with the planned Hadoop integration. Distinguish acceptance blockers from optional improvements.

Track these required integration tasks:
- hadoop-engineer adopts the positional pair builder and strict Json.decode.
- domain-engineer removes the temporary six-argument bridge after Hadoop migrates.
- No required coauthor pairs are suppressed.

Report findings and authorized owners. Do not start optional cleanup or another broad audit cycle. Do not commit, push, access AWS, or start Phase 5.

## Follow-up instruction 4 (after code-reviewer focused review 2, verbatim)

Proceed with work order 2: Phase 4 blocking-key functions and deterministic match features only. No Phase 5 scoring, thresholds, decisions, or LLM review.

Keep integration tasks a–i tracked for their authorized owners. Leave optional cleanup deferred.

For O3, preserve ORCID source-work-id with its submitting-source context. The professor explicitly requires preserving source-specific IDs; whether we currently join on it is a separate question. Route the smallest necessary contract change to domain-engineer.

Report results before the next work order. Do not commit, push, access AWS, or start Phase 5.

## Follow-up instruction 5 (after entity-resolution-engineer work order 2 and domain-engineer work order 1b, verbatim)

Yes, start work order 3 with hadoop-engineer, covering H1–H6 and integration tasks a and c–j as listed.

Finish the required Phase 4 jobs, preserve source-specific IDs and provenance, emit every required coauthor pair, and consolidate MatchCandidate output to one record per pair per run with all shared block keys.

Run sbt check and the bounded local pipeline. Report actual counters and output examples, and identify anything still incomplete.

Defer the entity-resolution and O3 review to the final Phase 4 review, which must explicitly cover both changes. After Hadoop migrates, route removal of the temporary bridge to domain-engineer.

Report back before the next work order. Do not commit, push, access AWS, or start Phase 5.

## Follow-up instruction 6 (after hadoop-engineer work order 3 and domain-engineer task b, verbatim)

Yes, start work order 4 with test-engineer.

Add the Phase 4 required adversarial tests under tests/** for broken XML, tricky Unicode, missing fields, duplicate IDs, common names, and giant teams. Exercise the actual pipeline and assert outputs, rejects, counters, and complete n*(n-1)/2 pair generation where applicable.

Run sbt check and report exact test counts, failures, and any uncovered requirements. Route implementation defects to their owning agents rather than changing their files.

Report back before work order 5. Do not commit, push, access AWS, or start Phase 5.

## Follow-up instruction 7 (after test-engineer work order 4 and hadoop-engineer repair 01, verbatim)

Yes, start work order 5 with infra-engineer: author the bounded EMR smoke-run script within its owned paths. Perform local syntax checks only. Do not execute the script, access AWS, create infrastructure, or deploy.

Keep the XML-reader limitation and zero-width-name concern tracked for the final review. Do not assume the chosen DBLP snapshot excludes the problematic XML constructs without evidence.

Report the script path, required parameters, enforced bounds, and actual validation results. Do not commit, push, or start Phase 5.

## Follow-up instruction 8 (after infra-engineer work order 5, verbatim)

Yes, start work order 6 with build-engineer.

Run sbt packageJobs and inspect both job jars for the expected application classes and Typesafe Config packaging. Report actual command results and any dependency or merge conflicts.

Successful packaging does not prove EMR runtime compatibility. Keep the smoke-script API assumptions, cost-cap limitations, and XML-reader limitation tracked for final review.

Do not execute the smoke script, access AWS, install tools, change ownership rules, commit, push, or start Phase 5. Report back before work order 7.

## Follow-up instruction 9 (after build-engineer work order 6, verbatim)

Yes, start work order 7 with code-reviewer against the professor’s exact Phase 4 requirements.

Cover the Hadoop pipeline, entity-resolution functions, changed core contracts, ORCID source-work-id preservation, bridge removal, adversarial tests, smoke script, and packaging evidence.

Specifically assess the XML split limitation, zero-width-name concern, complete pair generation, source provenance, and any stale documentation. Distinguish directly verified behavior from agent reports and packaging inferences. Identify genuine acceptance blockers separately from optional improvements and checks needed before future AWS execution.

Return APPROVE or REQUEST_CHANGES with file/line references and authorized owners. Do not implement repairs, modify permissions, commit, push, access AWS, or start Phase 5.

## Follow-up instruction 10 (after code-reviewer final review, verbatim)

Yes, route B1 to hadoop-engineer. Remove the unsupported claim about DBLP markup and accurately document the XML-reader limitation and required verification before processing the full snapshot. Include the stale bridge wording correction in the same document.

Keep this repair documentation-only. Then have code-reviewer re-check the changed sections and return APPROVE or REQUEST_CHANGES.

Defer the zero-width-character issue to a tracked prerequisite before Phase 5, owned by entity-resolution-engineer. Keep other optional items deferred.

Do not commit, push, access AWS, or start Phase 5. After approval, give me the remaining human validation and acceptance steps.
