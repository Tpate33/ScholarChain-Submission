# Offline AWS preparation — verification record

Base: `14d85c5d14b32cb191e06330208819538d0bf93e`.
Only offline implementation/testing is authorized. No AWS account/service call,
paid model call, Terraform plan/apply, deployment, commit or push was executed.

| Check | Observed result |
| --- | --- |
| Production-planner census tests | 3 passed, reported by storage owner |
| Exact-object materializer tests | 4 passed, reported by storage owner; injected reader only |
| Worker ZIP relocation | 4 refusal probes passed; checksums, relative classpaths and loader/ER isolation passed |
| Dependency fences | 64 passed in coordinated gate |
| Final `sbt -Dsbt.offline=true scalafmtSbt imageBuildContexts check` | PASS, exit 0: 2,889 passed, 0 failed/errors, 1 skipped, 243 suites; 529 seconds |
| Terraform formatting / cached-provider validate | Passed, reported by infra owner; provider 6.72.0, no backend or account access |
| Python fixture/contract/monitor ZIP checks | 14 passed after review repair, including two stop regressions and source/ZIP equality |
| Bash syntax / ShellCheck | Passed, reported by infra owner |
| Independent review | APPROVE for offline checkpoint; CR-OFF-01 repaired and re-reviewed |
| OCI image build/execution | Prior local amd64 builds/shell probes passed; Java/application probes remain BLOCKED after approved targeted cache recovery; prior verification images removed within approved scope |

The first coordinated sbt invocation was cancelled before tests to correct a
verified multi-stage Dockerfile packaging defect: `/work` initially existed only
in the intermediate image stage. The build owner corrected both stages and
restarted the single final gate. No completed full suite was repeated for
reassurance. Python-only review repairs do not require another Scala full gate.

The completed gate wrote its legitimate `markTestRun` record at accepted HEAD
`14d85c5` with the expected dirty working tree. Final log:
`/private/tmp/scholarchain-offline-aws-check.log`. This record covers the source
candidate before a human commit; it is not a claim of a clean committed gate.

Portable ZIP: 104,048,860 bytes, SHA256
`527a89063b23b87311d4b56d406d39b76076655f9d9070e8edbfcc36bff1f8ff`.
Generated build-context content inventories (not OCI image digests): runtime
81 files / 43,351,549 bytes, manifest SHA256
`716de0191081dd5aa6b490ae9f2866bd0f3ef63b0908bb7654b0f5bf17212df7`;
worker 101 files / 115,203,217 bytes, manifest SHA256
`ade8315bd967ccb951914922bb199dd2aacd4ccb67404fb8660eb85ec1cf29ee`.
Generated target artifacts are intentionally excluded from the Git checkpoint.

Approved public base pull reported digest
`sha256:3421d7f073ded5491600707946dd7593502bdc87a18b3f234e7069574fb80401`;
subsequent inspect failed, so actual local image architecture was not verified.
Worker build used `--platform linux/amd64 --pull=false --network=none` and failed
at `WORKDIR` commit: containerd overlayfs `metadata.db: input/output error`.
Read-only base inspection then failed with a content-blob I/O error. Host Data
volume showed only **116 MiB available, 100% capacity**. No VM reset, pruning,
unrelated deletion, image push, runtime build or paid call was attempted. Restore
local disk capacity/engine health, then resume container verification; the passing
Scala gate does not need repeating merely because the local engine is repaired.

### Disk recovery and resumed container checks

After the human freed disk space, read-only checks showed approximately 3.97 GiB
host free, 17.12 GiB Colima root free and 86.66 GiB Docker-volume free. Base
inspection still failed with storage I/O errors. With no active containers/builds,
one ordinary Colima stop/start preserved the existing profile and all images,
volumes and project files. Storage metadata became readable; the cached base was
verified as linux/amd64 (93,775,732 bytes), platform image ID
`sha256:61ee3bb704f7c75f5c7538cd94832d85278bb5f7c266716ed2e54fb85ff6b302`.
No additional public download was required to resume builds.

Both existing contexts built with `--platform linux/amd64 --pull=false
--network=none`. Actual network-disabled shell probes passed UID/GID 10001:10001,
`uname` x86_64 and writes to `/work` in both images:

| Local image | Image ID | Bytes |
| --- | --- | ---: |
| Worker | `sha256:3befd38b186b4e17c18d4987888c541454481e4fe9c96b7fbe7105f22de891db` | 197,828,362 |
| Runtime | `sha256:3b4701c31861976557fa8c20fa8c4fc52626834c736891d09f15cf905cc4611a` | 133,349,557 |

**Application verification remains blocked:** the cached base's
`/opt/java/openjdk/lib/server/libjvm.so` is **zero bytes**; base `java -version`
exits 6 with “file too short.” The derived images inherit the failure. One ordinary
approved base re-pull reported up to date, downloaded no layers and did not repair
the library. No application or AWS workflow was started. These built image IDs
must not be treated as usable deployment artifacts.

Host free space after builds was 3,537,040 KiB (about 3.37 GiB). Additional disk
space is not the established current blocker; an exact additional-space requirement
cannot be inferred from damaged cached image content. Targeted recovery requires
an explicit exception to the human's no-existing-image-deletion restriction.
No images/volumes/project files were deleted, no pruning/reset performed, and no
source/full-test change occurred. The 2,889-test gate and independent source review
remain valid; container CLI probes still require a healthy base and rebuilt images.

Read-only cache-recovery planning isolated 32 image records: the damaged Java
base, the two tagged verification outputs and 29 untagged build intermediates.
The other 208 inspected image records are outside the proposed removal set; none
of six existing containers references that set. Exact leaf-first IDs are in
`/private/tmp/scholarchain-targeted-image-removal-order.txt`, SHA256
`f088d52d0ab76ddb65a32e8abd0c1cb1f306d3cd978d362cafcf1006a1d785f0`.
**Plan only, not authorized/executed:** recheck aliases/container references,
then remove only those reviewed IDs using `docker --context colima image rm
--no-prune <ID>`, without force and stopping on any conflict or changed reference.
Re-pull the same approved amd64 base and test base Java with network disabled
before rebuilding. No unrelated image, volume, project file or VM reset is in
scope. Docker may retain shared cached content; this plan does not guarantee
repair, and persistent JVM failure must stop further recovery.

### Approved 32-record recovery — stopped at persistent JVM failure

The human explicitly approved removing only the 32 reviewed disposable Docker
image/cache records. Before mutation, the build owner confirmed the inventory
hash, 32 unique exact IDs, unchanged aliases/parents/layers and no container
references to the set or base platform child. All six existing containers were
stopped. The affected set contained only images/intermediates; no container state,
volume or ScholarChain path was selected.

Exactly **32/32** reviewed records were removed in leaf-first order with
`image rm --no-prune`, without force. The same approved linux/amd64 public Java
base was re-pulled at the recorded digest. The first network-disabled base check
still reported **zero-byte `libjvm.so`, Java exit 6**. The owner stopped before
rebuilds/application probes; no broader deletion or general prune/reset occurred.

Post-repair comparison: **208/208 unrelated image records preserved; all six
existing container identities/image references/states unchanged; one existing
volume unchanged**. Current inventory contains those 208 records plus the
re-pulled base. Earlier verification-image IDs above are historical and were
removed under the approved exception; no healthy current built image is claimed.
The cause of persistent bad content (including possible retained snapshot reuse)
is not proven, and no upstream library hash was asserted.

Evidence: `/private/tmp/scholarchain-targeted-recovery.log`,
`/private/tmp/scholarchain-base-recovery-pull.log`,
`/private/tmp/scholarchain-repair-before.json` and
`/private/tmp/scholarchain-repair-after.json`.
The approved recovery scope is exhausted. The human explicitly deferred all
further cache repair and separate verification environments. No additional
Docker work will be attempted for this closeout. The 35-file checkpoint is
prepared for human acceptance with this blocker; acceptance has not been signed
and container application verification is **not complete**.

## Reproducible census artifact

`modules/storage/offline-census/accepted-dev.json` SHA256:
`6026078dd4d8e8ac00a062f335df753fd9df990059f1f23ca76c9adfc3292b0a`.
DBLP 41 / OpenAlex 43 / Crossref 6; five vector indexes 176/51/8/9/5 = 249;
521,168 embedding-text bytes; 459 graph vertices / 799 edges; 1,302 analysis
DynamoDB items. Source/run/part/work hashes are recorded by the artifact.
This is a planned-input census, not real embeddings or successful live writes.
The available larger raw DBLP archive has no corresponding verified bounded
producer exports; a 10K-work cloud-run census is still missing.

## Release checks and limits

Release validator reported a baseline permitted-history token-pattern scan with
zero matches; see `offline-status.md` for its protected exclusions and unresolved
CR-REL-01 human privacy review. Incremental candidate scan covered all 34 intended
files then present (33 text files plus the verified single-source ZIP), with zero
credential-pattern matches and no missing/extra Git paths. The subsequently
prepared `commit-paths.txt` adds only those explicit filenames plus itself,
bringing the implementation checkpoint to 35 files. It excludes protected local
guidance, caches, state/plans and private labels. No files were staged.

An anonymous GitHub clone failed authentication; unauthenticated REST returned
404. A separate **local** clone of committed `14d85c5` built its demo classpath and
passed seven HTTP smoke checks (query/graph/evidence/explain/replay/health 200;
unauthorized 401). That validates the prior checkpoint locally, not anonymous
GitHub access or this uncommitted candidate. Candidate anonymous fresh-clone
verification and Microsoft Teams assignment submission remain release gates.

No check in this record proves cloud quotas/model access, live IAM/network
correctness, cleanup reliability, actual provider charges, integrated EMR or
elasticity behavior. Checkov/tfsec were unavailable and were not run.

Closeout recheck: all 35 intended paths match the current Git working tree,
with no missing/extra intended files, symlinks or prohibited secret/state/held-out
path names. Intentionally untracked `AGENTS.md` and `skills/` are excluded.
The prior scoped content scans remain the recorded credential check; no private
label contents were opened. No candidate Scala/build source is newer than the
legitimate passing marker. Existing review APPROVE and 2,889 passing tests were
confirmed from their records without rerunning tests or review. Staging is empty;
no human acceptance files, commits or pushes were created by agents.
