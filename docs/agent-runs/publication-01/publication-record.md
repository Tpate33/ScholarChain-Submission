# Private publication candidate — evidence and limits

This is a sanitized derivative, not a new human ratification or a deployed cloud
system. Original source checkpoint: `e08aa0938bed6cb704b8cff990a6796aa7f2af50`.
The original ScholarChain repository remains private and unchanged. Publication,
GitHub repository creation, visibility changes and pushes are not authorized.

## Historical sanitization

The isolated derivative retains all 70 commits in the original main ancestry;
47 commit IDs changed. Sanitized-history base:
`621e34f6bb8972c52b7bd80cb4f196af005dcf9d`.
[commit-map.json](commit-map.json) maps original historical IDs to derivative IDs.
This mapping does not assert that the human accepted the rewritten IDs.

Only two paths changed in the historical transform:

- `gold/entity-resolution/heldout-v1/er-review-queue.jsonl` is omitted from every
  derivative tree. It was a null-label scaffold but carried pair-linked system
  predictions, which enabled reconstruction when joined with evaluation counts.
- The relevant version of `docs/agent-runs/phase-05/human-gate.md` is explicitly
  marked as a sanitized public derivative. It preserves the historical decision,
  methodology, sample sizes, policy/run/freeze provenance and non-sensitive test
  evidence. Label-derived class/confusion totals, constraining accuracy results
  and outcome-absence prose were withheld. Exact original evidence remains in the
  private original repository for human/instructor review.

No private held-out label file was read, copied, compared or modified. No actual
case-to-truth mapping was computed or published. Public membership/blind-view
metadata are not human labels. The publication decision depends on independent
review that no reconstructive truth constraints remain reachable with them.

For every historical commit, all other blob IDs—including application/build/test
code, fixture bytes and original producer manifests—were preserved. Authors,
committer identities, timestamps and messages were preserved. All four originally
signed commits were unchanged; no inherited signature was attached to a rewritten
commit. No new signing or acceptance is claimed.

Before the later human-approved lint amendment, a source-to-candidate completeness
check confirmed **1,100** required application/build/configuration/dataset/factory
artifacts retained their exact original blob IDs, including all **18 role
definitions** and **five project commands**. The lint amendment changes only two
factory hooks: **1,098** of those artifacts remain byte-identical, with the two
explicitly approved exceptions recorded below. All application code, build/test
code, configuration, datasets and producer manifests remain unchanged. The only
original tracked path omitted is the held-out review queue
named above; no other source, configuration or grading-document path was dropped.
The exact original human evaluation evidence remains private rather than being
represented by the redacted public derivative.

The derivative was constructed in a fresh Git object database: all 3,161 initial
objects were reachable from its sole main ref, with no original refs, reflogs,
alternates, queue payload or original constraining document blob. There are no
remotes and local push guards are enabled. Private source copies, original-tree
evidence and rewrite scratch data are outside the publication worktree/object
database and must never be uploaded or used as a public fork parent.

## Provenance and reproduction boundary

Original accepted commit references and historical output provenance remain
original references. They were not silently translated or resealed. Some original
IDs no longer exist as commits in the sanitized history; use the mapping to locate
their derivative. This does not make old operational run configurations executable.

In particular, historical ER dev manifests pin
`8c1bee042e7dca3119f5116df2150e0bee204598`, and the historical Spark cloud-run
configuration pins `148b4b91bc904a63680b94f91f275173f743c4b6`. Their ancestry and
unchanged-code guards must not be weakened. New executions require separately
prepared inputs appropriate to the code actually executed; a mapped ID alone does
not authorize or reproduce the identical historical run ID. No such runs occurred.

The existing local DBLP-derived API fixture, its hashes and source lineage remain
unchanged. Its stores are in-memory, its answer model is a template, and embeddings
are stand-ins. Production adapters/configuration exist and were tested offline;
real AWS processing/loading/models, elasticity and two-scale evaluation remain
unverified. Docker application verification remains blocked/deferred by the bad
cached JVM library; no new VM/profile/cache repair was attempted for publication.

## Allowlisted Parquet inspection

[parquet-audit.json](parquet-audit.json) records the exact 29 historical dev
blob/path versions, manifest digest, scan counters and limitations.
[parquet-audit.scala.txt](parquet-audit.scala.txt) archives the exact standalone
harness source (text evidence, not an added application/build source).

The harness used existing pinned Parquet readers and core contracts, with no
installation, AWS call, model call or private-label comparison. All 29 versions
equal the corresponding current dev files. It verified 2,809 rows, part hashes,
byte counts, layouts and expected source/snapshot/run/lineage identities; scanned
73,665 logical strings, 3,025 footer strings and 79,303 binary-metadata bytes.
There were zero configured credential, email or private-evaluation-indicator
findings and zero contract/origin mismatches. Pattern scanning and accepted dev
provenance do not prove absence of every possible secret or hidden payload.

## Candidate verification and publication gates

The original source checkpoint's existing gate passed 2,889 tests, zero
failures/errors, one skipped, 243 suites. That is not yet evidence of a clean clone
of this derivative. An independent fresh local clone of commit
`69d63538a57914f471eb199380e07570182a491f` then ran
`sbt -Dsbt.offline=true check pipelineDemoClasspath`: **exit 0, 2,886 passed,
zero failures/errors, four skipped, 243 suites**, 472 seconds. The legitimate
marker recorded that commit and a clean worktree. All 64 dependency checks and
four relocated worker CLI probes passed. The subsequent DBLP HTTP replay passed
all seven checks. No source, fixture or application configuration was changed.

The difference from the original 2,889/one-skip gate is three unchanged ER sample
probes requiring a completed generated Hadoop `curated-a` output under `target/`,
which is absent in a fresh clone: `DecisionExportSuite`, `GroupingExportSuite`,
and `SampleQueueProbeSuite`. Their source explicitly skips absent/incomplete
generated output. These are not missing private labels or withheld queue inputs.
The fourth skip is the existing semantic-neighbours smoke case.

During the initial Q run, the agent validator's **888 inert table probes and 51
fixture probes passed**, but the full validator **exited 1**: ShellCheck 0.11.0 flagged five unchanged hook
scripts. Factory-owner read-only review verified their imported globals are
initialized before use; no runtime initialization defect was established. The
validator lacks a script-relative ShellCheck source path, and two library globals
are intentionally consumed by callers. Diagnostic-only `-P SCRIPTDIR` resolved
caller warnings; standalone returned-global warnings remain. No hook was edited
or warning silently waived at that stage. Full agent validation was not yet green;
the candidate-only correction required the human ratification recorded next.

### Human-approved candidate-only lint follow-up

The human then explicitly approved: “I approve the minimal candidate-only
ShellCheck repair: the one search-path option and two scoped annotations
identified by the review.” This authorized the exact candidate-only amendment;
it did not authorize original-repository changes or publication. This quotation
archives the received instruction, not an agent-authored human-gate decision.

The factory owner applied only `-P SCRIPTDIR` to the validator's ShellCheck call
and two assignment-scoped SC2034 annotations for globals consumed by sourcing
hooks. The exact change is two files, five insertions, one deletion; no runtime
guard logic, matrix, tools, permissions or privacy fences changed. Independent
review confirmed the patch matches the approved proposal and weakens no floor.

**The affected validator now passes: exit 0, zero failures, 888 table probes and
51 fixture probes (939 total) passed.** All nine shell hooks pass syntax and
ShellCheck; settings/MCP JSON and registration/executable/frontmatter/collision/
orphan/ownership checks pass. [factory-lint-repair.json](factory-lint-repair.json)
records the exact validated hook hashes, patch/log digests and scope.

The earlier Q exit-1 record in `clean-checkout.json` is historical and was not
rewritten into a false pass. The application suite, HTTP replay and 29-file
Parquet scan were not repeated: this amendment affects lint tooling/comments,
not application behavior. The original repository and its active constitution
remain unchanged. Future agent work using the amended candidate must load that
candidate in a fresh session; no model-backed phase replay was performed here.

[clean-checkout.json](clean-checkout.json) records the actual counts, skip scope,
HTTP result, validator exit and log digests. Later attestation/documentation-only
commits must be checked for identical application/build/test/configuration blobs;
these results belong to the verified source commit, not a second full-suite run
or an anonymous GitHub clone. No full suite was repeated for evidence-only edits.

Independent release review of documentation/evidence commit
`69d63538a57914f471eb199380e07570182a491f` returned **PASS within the declared
scope**. It inspected 71 commit metadata objects and 1,768 permitted historical
blobs, keeping 189 protected blobs excluded. It found no verified credentials,
private keys, provider tokens, signed URLs or surviving reconstruction constraints.
The removed queue and original constraining document blob were absent from the
entire object database; all 3,177 objects were reachable from the sole main ref.
The four broad semantic matches were methodology, synthetic examples or explicitly
unevaluated reports. Accepted gzip and the completed Parquet inspections apply to
unchanged binary objects. This is an independent scoped review, not a universal
privacy guarantee and not a human publication decision.

Nonsecret historical AWS account/resource identifiers, local paths and required
student identity remain inherited disclosures; they are not credentials. Public
release requires an explicit human decision to retain them or a separately
reviewed additional redaction scope. No credential was established by the prior
scoped history audit; candidate verification must still check the final history.

The candidate is currently local and unpublished. Anonymous GitHub cloning cannot
be claimed before an explicitly approved independent public repository exists.
Final timely tag/SHA, video link, and Microsoft Teams submission receipt remain
outstanding. The professor's deadline is November 25, 2026, 11 PM America/Chicago.
Do not make the original repository public or force-push its history as a shortcut.

[publication-steps.md](publication-steps.md) contains exact future human commands
for an empty, independent public repository, a single approved main/tag push and
an environment-isolated anonymous clone. The proposed name is
`Tpate33/ScholarChain-Submission`, subject to availability and explicit human
approval. It must not be a fork or visibility change of the private original.
