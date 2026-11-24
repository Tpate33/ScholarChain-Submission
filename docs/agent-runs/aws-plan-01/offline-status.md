# Offline AWS preparation — resumption record

Base checkpoint: `14d85c5` (accepted/pushed production-connections-01).
Deadline: November 25, 2026, 11 PM America/Chicago.

Authorization: offline implementation and targeted verification only. No AWS
account/service access, paid model calls, Terraform apply/destroy, deployment,
commit, push, guidance/hook changes, credential/state reads or private held-out
label reads are authorized. Public documentation is reference material only.

Closeout direction: the human explicitly deferred further Docker cache repair
and any separate Colima profile/VM. Close this local checkpoint with container
application verification **BLOCKED**, not passed. No additional Docker work or
new environment is authorized. The limitation does not prevent requesting
human acceptance of this documented offline checkpoint.

## Resumed assignments

- **offline_build / build-engineer:** portable relative-classpath loader/ER ZIP,
  four launchers, OCI contexts and relocation checks implemented. Four relocated
  refusal probes and 64 dependency fences passed; final coordinated `sbt check`
  passed (2,889 passed, 1 skipped, 243 suites; exit 0). No dependency cache or original class directories are needed at
  launch. Earlier OCI builds and non-root `/work` shell probes passed, but
  application probes remain blocked by a zero-byte JVM library in the public
  Java base. Colima's storage metadata recovered after one ordinary restart.
  The human subsequently approved replacement of exactly 32 image/cache records;
  those were removed and the base re-pulled, but Java still failed. The prior
  verification images were removed within that approved set. All 208 unrelated
  images, six existing containers, the volume and project files were preserved.
  The owner stopped before broader repair or new builds. The human
  explicitly authorized downloading the existing public Java base image and
  local container verification, with no AWS/ECR push.
- **offline_census / storage-engineer:** pure production-planner census implemented;
  three focused tests passed. Accepted-dev artifact counts DBLP 41, OpenAlex 43,
  Crossref 6; five indexes contain **176/51/8/9/5 = 249 planned vectors**.
  Embedding text totals 521,168 bytes; graph 459 vertices/799 edges; 1,302 analysis
  DynamoDB items, estimated 3,778 base + 3,429 GSI write units. These estimates
  exclude ledger/registry/runtime/retries and do not measure provider tokens.
  No coherent larger verified producer exports or 10K-work census exists.
- **offline_census / storage-engineer follow-up:** bounded exact-object S3
  materializer reuses the existing Java SDK reader. Four focused tests passed;
  default validation creates no client. Matching run ID is required for execute;
  paths, total/object byte bounds and hashes are checked, conflicting local files
  refused. Requires exclusively owned worker root and hard-link support. Existing
  reader requires S3 full-object SHA256 on staged objects. No AWS reads occurred.
- **offline_infra / infra-engineer:** private runtime stack and gated lifecycle,
  admission and deadline scripts implemented. Cached-provider Terraform validate,
  formatting, bash syntax, ShellCheck and 12 canned Python checks passed. No
  backend init/plan/apply occurred. Independent review identified that early
  manual service stop could be undone by the graph deadline monitor; owner repair
  completed: manual stop suspends scaling first; the monitor preserves zero.
  All 14 revised checks passed; the independent reviewer approved this offline
  checkpoint after re-review. Live behavior remains unverified.

The orchestrator checked for leftover sbt/Java processes before resumption; none
were present. Only one sbt process may run at a time. No completed prior tests or
reviews are restarted; final check/review follows the completed changes.

## Required release and submission gates

| Gate | Current status |
| --- | --- |
| Portable packaging and relocation | Host relocation/full gate passed; historical amd64 builds/shell probes passed; approved 32-record recovery failed JVM check; no current usable verified images |
| Deterministic input/vector census | Dev corpus verified: 249 total planned vectors; launch corpus missing |
| Runtime IaC and deployment/cleanup controls | 14 offline checks passed, major stop/monitor defect repaired; no live plan/apply |
| Independent review | APPROVE for reviewed offline source snapshots; CR-OFF-01 resolved; live readiness not approved |
| Human acceptance and Git checkpoint | Ready for human acceptance of offline scope with Docker deferral; explicit 35-file list verified; no staging/commit; public release gates remain separate |
| Public-repository security audit | Baseline and all 34 candidate files passed scoped token-pattern scans; explicit 35-file checkpoint list prepared; CR-REL-01 privacy/possible label-inference human review unresolved |
| Anonymous fresh clone and reproduction | Anonymous GitHub clone refused authentication (REST 404); separate LOCAL clone of accepted 14d85c5 passed 7 HTTP smoke checks; this does not establish public access or candidate reproducibility |
| Microsoft Teams assignment | Human submission pending; submit the public anonymously cloneable URL before the deadline and retain receipt |
| Two-scale evaluation | Unmet; a single bounded 10K run does not satisfy it |
| Live cloud run | Not authorized; no go until blockers, inventory, prior costs and exact run/cleanup approvals are resolved |

The release validator scanned 1,665 permitted historical blobs (24,169,333 bytes)
from 70 commits: no configured token/private-key/credential-URL patterns matched.
It excluded 266 protected blobs/269 paths without opening private label contents;
84 binary blobs were not text scanned. This is a scoped check, not certification
that every historical byte is safe. CR-REL-01 includes nonsecret account/resource
identifiers and potential label inference in Phase 5 human evidence/review queue;
human privacy review is still required. No visibility/history change is implied.
The existing F1–F11 finding list is
[`phase-10/release-scan-cr-rel-01.md`](../phase-10/release-scan-cr-rel-01.md);
the accepted Phase 10/11 records explicitly carried these decisions forward.
Anonymous clone testing must disable credentials/helpers. After publication and
the checkpoint push, repeat an actual anonymous fresh clone of that checkpoint.

## Cost and go/no-go requirements

See [bounded-plan.md](bounded-plan.md). The proposed additional envelope is $12;
the student's approximate $0.55 prior spend is unverified, not an exact baseline.
Before paid execution, reconcile cumulative AWS plus applicable external API
charges before credits and resource retention; present exact resources, immutable
artifacts, plan hashes, runtime/expiry controls and approved cleanup procedure.
The current go/no-go is **NO-GO for cloud execution** pending these gates.

## Concrete remaining launch blockers

1. Deferred container blocker: establish a healthy Java base. The specifically approved 32-record cache
   recovery was completed and failed the first base JVM check; no broader repair
   is authorized. The human deferred further repair and separate environments;
   do not resume either without a new explicit instruction. No additional
   disk-space minimum is proven.
   Then rebuild and finish container CLI probes, resolve usable immutable digests
   and verify real runtime configuration. Prior local image IDs are historical,
   removed verification artifacts; see test-report.md for exact results.
2. Prepare a coherent bounded producer corpus and census it; the 249-vector dev
   artifact is not a 10K-work experiment or evidence for two scales.
3. Complete the bounded single-cluster Hadoop → ER → Spark chain and real
   collector; the new admission controller does not submit EMR steps. Aggregate
   embedding/answer/request enforcement and application-failure propagation need
   verification; approval JSON assertions alone do not enforce them. ECS services
   may replace failed tasks until their stop deadline.
4. Reconcile prior gross AWS/external API spending and retained resources; verify
   account inventory, quotas, model access and monitor/notification permissions
   only after explicit account-inspection approval.
5. Bind exact network IDs, artifacts, config references, deadlines, real EMR ID
   and reviewed saved create/destroy plans before specific execution approval.

Cleanup design: archive evidence, stop admitted workloads, then human reviews
`scripts/cleanup.sh dev plan-destroy runtime` and applies the exact checksum-approved
destroy plan. Runtime teardown includes ephemeral Neptune/tables/vector indexes,
endpoints/ECR/runtime controls; base buckets are separate. Secrets use a seven-day
recovery window; reconcile retained costs. The minute stop monitor is not an
absolute billing cap and does not delete Neptune/endpoints. Broader base bucket
emptying remains a separate destructive approval. No cleanup was executed.

A useful live run before the deadline remains conditional on these unresolved
engineering and account gates. There is no verified duration estimate or promise
of completion. Local evidence must not be presented as successful AWS execution.

## Prepared human checkpoint steps — not executed

Request acceptance of this **offline** checkpoint with the recorded Docker
verification blocker and live AWS limitations. Public privacy review and
anonymous reproduction remain required before public release; they are not
claimed complete by accepting this local checkpoint. The human then stages only `commit-paths.txt`'s
explicit paths, reviews the staged summary and commits the implementation. The
human writes the two acceptance records below at that resulting implementation
SHA, reviews and commits those two files, then performs a normal push to
`origin/main`. Verify the remote SHA and repeat an anonymous clone/build/smoke
check of the published candidate. Do not add protected untracked `AGENTS.md` or
`skills/`, caches, credentials/state/plans, raw downloads or private labels.
Use a separate clean clone for future clean-tree cloud controls; do not delete
protected local guidance to satisfy their gate.

Prepared Git commands for the human after approval (not executed by agents):

```sh
git add --pathspec-from-file=docs/agent-runs/aws-plan-01/commit-paths.txt
git diff --cached --stat
git commit -m "Offline AWS preparation: portable workers, census and bounded runtime controls"
```

At the resulting implementation SHA, the human creates `human-gate.md` from the
unsigned draft and writes that SHA plus a newline to `commit.txt` in this folder.
Then, after reviewing those two acceptance files:

```sh
git add -- docs/agent-runs/aws-plan-01/human-gate.md docs/agent-runs/aws-plan-01/commit.txt
git commit -m "Record offline AWS preparation local checkpoint acceptance"
git push origin main
git fetch origin
git status -sb
```

These commands assume `main` remains at the reviewed baseline plus these two
new commits with no remote divergence. Recheck branch/HEAD/remote before use;
do not force-push, merge or include unrelated staged paths to bypass a mismatch.

No agent has created `human-gate.md` or `commit.txt`, signed acceptance, staged,
committed or pushed this checkpoint. No cloud permission is implied by accepting it.

Unsigned draft for human review:

<!-- acceptance-draft:start -->
```markdown
# Offline AWS preparation — human acceptance

Decision: PENDING
Accepted implementation commit: PENDING
Acceptance date (America/Chicago): PENDING

Scope: portable worker packaging, deterministic production-input census,
bounded exact-object materialization, private runtime infrastructure and gated
deployment/cleanup preparation, with the verification and review records here.

Review: independent source-snapshot review APPROVE for offline checkpoint;
major CR-OFF-01 repaired and re-reviewed. Existing sbt check passed 2,889 tests,
zero failures/errors, one skipped, 243 suites. The full suite was not rerun
for Docker recovery or closeout documentation.

Docker limitation: container application verification is BLOCKED by a zero-byte
libjvm.so in the Java base, persisting after the approved 32-record recovery.
Historical image builds/shell probes passed, but no healthy current verified
container image is established. Further cache repair and separate Colima
profiles/VMs are explicitly deferred.

Other limitations: no successful AWS run is established. Complete bounded producer
exports/census, EMR chain, collector, aggregate provider/failure controls, live
inventory/quotas/model access/costs/configuration and reviewed plans remain gates.
One 10K run does not meet two-scale evaluation requirements. Public privacy
review, anonymous fresh-clone reproduction and Teams receipt remain separately
tracked. See offline-status.md for exact status at acceptance.

Authority: this acceptance approves only the local checkpoint. It does not
authorize AWS inspection/deployment, paid calls, provider changes or cleanup.
```
<!-- acceptance-draft:end -->
