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
of this derivative. Candidate clean-clone validation and independent final privacy
review are pending and will be recorded separately before publication approval.

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
