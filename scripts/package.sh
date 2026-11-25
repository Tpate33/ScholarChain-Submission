#!/usr/bin/env bash
# =====================================================================================================
# scripts/package.sh -- build the Hadoop and Spark job jars, the portable worker bundle and the two
# local OCI images, tagged by the full Git commit SHA. Owner: infra-engineer (IE-P13-01).
#
# USAGE   scripts/package.sh          (no arguments; run from the repository root on a clean tree)
#
# Steps: clean tree; `sbt packageJobs imageBuildContexts` (the build-engineer aliases: hadoop and
# sparkAnalytics assemblies with their content checks, the worker bundle and both OCI build contexts;
# sbt builds no image); clean tree again (the build must not change tracked files); locate exactly one
# of each artifact; `docker build --network=none` of the services and worker contexts as LOCAL images
#   scholarchain-services:<sha40>   scholarchain-workers:<sha40>   (platform linux/amd64, ECS X86_64)
# then a JVM probe in each image (`java -version`, no network). Writes a create-once record
#   target/package/<sha40>/attempt-<n>/package-manifest.txt   (artifact sha256, bytes, image tags/IDs)
# A failed image build or probe (for example the known zero-byte libjvm.so in the Java base, see
# docs/agent-runs/aws-plan-01/offline-status.md) is recorded as IMAGE-FAIL and the script exits 6: the
# jar checksums are still recorded, nothing is masked.
#
# NOT DONE HERE: no registry push, no ECR login, no AWS call. Pushing to ECR is a separate deploy-time,
# human-gated step (not implemented); any argument, including --push, is refused.
#
# MARKERS  [package] STEP / CHECK ok / ARTIFACT <name> sha256=<hex> bytes=<n> path=<p> /
#          IMAGE-OK|IMAGE-FAIL <tag> ... / RESULT PACKAGE-OK|PACKAGE-INCOMPLETE; FAIL on stderr.
# EXIT     0 every artifact and both images verified, 2 usage, 3 refused (dirty tree, sbt failure,
#          missing or ambiguous artifact), 6 an image failed to build or verify
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=package
# shellcheck source=scripts/lib/common.sh disable=SC1091
. "$(dirname "$0")/lib/common.sh"
readonly IMAGE_PLATFORM=linux/amd64
readonly SERVICES_IMAGE=scholarchain-services
readonly WORKERS_IMAGE=scholarchain-workers

if (($# != 0)); then
  [[ $1 == --push ]] && die "$EX_USAGE" "--push is not implemented: pushing to ECR is a separate deploy-time, human-gated step"
  die "$EX_USAGE" "usage: scripts/package.sh (no arguments)"
fi
need_tools git sbt shasum awk
step "start at=$(now_utc)"
require_repo_root
require_clean_tree
SHA=$HEAD_SHA

step "sbt packageJobs imageBuildContexts"
sbt packageJobs imageBuildContexts || die "$EX_REFUSED" "sbt packageJobs imageBuildContexts failed (output above)"
ok "sbt packaging"
step "tree after build"
require_clean_tree

# one_file <label> <glob...>: prints the single regular file matching, else refuses.
one_file() {
  local label=$1 f found=()
  shift
  for f in "$@"; do
    [[ -f $f && ! -L $f ]] && found+=("$f")
  done
  ((${#found[@]} == 1)) || die "$EX_REFUSED" "$label: expected exactly one file, found ${#found[@]} ($*)"
  printf '%s' "${found[0]}"
}
HADOOP_JAR=$(one_file "hadoop job jar" modules/hadoop/target/scala-*/scholarchain-hadoop-jobs-*.jar)
SPARK_JAR=$(one_file "spark job jar" modules/spark/target/scala-*/scholarchain-spark-jobs-*.jar)
WORKER_ZIP=$(one_file "worker archive" target/workers/scholarchain-workers.zip)
WORKER_SUMS=$(one_file "worker SHA256SUMS" target/workers/stage/SHA256SUMS)
SERVICES_CTX=modules/services/target/docker/stage
WORKERS_CTX=target/workers/stage
for ctx in "$SERVICES_CTX" "$WORKERS_CTX"; do
  [[ -f $ctx/Dockerfile && ! -L $ctx/Dockerfile ]] || die "$EX_REFUSED" "OCI context $ctx has no Dockerfile"
done

OUT_PARENT="target/package/$SHA"
mkdir -p "$OUT_PARENT"
n=1
while ! mkdir "$OUT_PARENT/attempt-$n" 2>/dev/null; do
  n=$((n + 1))
  ((n <= 999)) || die "$EX_REFUSED" "no free attempt directory under $OUT_PARENT"
done
OUT="$OUT_PARENT/attempt-$n"
MANIFEST="$OUT/package-manifest.txt"
{
  printf 'schema=scholarchain.package-manifest.v1\ncommit=%s\ncreated_at=%s\n' "$SHA" "$(now_utc)"
} >"$MANIFEST"
ok "record $MANIFEST (create-once)"

artifact() { # artifact <name> <path>
  local sum bytes
  sum=$(sha256_file "$2")
  bytes=$(file_bytes "$2")
  say "ARTIFACT $1 sha256=$sum bytes=$bytes path=$2"
  printf 'artifact.%s.path=%s\nartifact.%s.sha256=%s\nartifact.%s.bytes=%s\n' "$1" "$2" "$1" "$sum" "$1" "$bytes" >>"$MANIFEST"
}
step "artifacts"
artifact hadoop-job-jar "$HADOOP_JAR"
artifact spark-job-jar "$SPARK_JAR"
artifact worker-archive "$WORKER_ZIP"
artifact worker-sha256sums "$WORKER_SUMS"
artifact services-dockerfile "$SERVICES_CTX/Dockerfile"
artifact workers-dockerfile "$WORKERS_CTX/Dockerfile"

# ---- local images ----------------------------------------------------------------------------------
IMAGE_FAILS=0
image() { # image <name> <context>
  local tag="$1:$SHA" id rc=0 ver
  step "docker build $tag from $2"
  # --network=none (CR-P13-01 n2): RUN steps get no network; the generated Dockerfiles' RUN steps are
  # the local JVM probe, mkdir and chown only. Resolving the FROM base image is not affected by it.
  docker build --network=none --platform "$IMAGE_PLATFORM" --label "org.opencontainers.image.revision=$SHA" \
    --tag "$tag" "$2" || rc=$?
  if ((rc != 0)); then
    say "IMAGE-FAIL $tag stage=build exit=$rc"
    printf 'image.%s.tag=%s\nimage.%s.status=build-failed exit=%s\n' "$1" "$tag" "$1" "$rc" >>"$MANIFEST"
    IMAGE_FAILS=$((IMAGE_FAILS + 1))
    return 0
  fi
  id=$(docker image inspect --format '{{.Id}}' "$tag") || id=unknown
  step "jvm probe $tag (no network)"
  if ver=$(docker run --rm --network none --platform "$IMAGE_PLATFORM" --entrypoint java "$tag" -version 2>&1); then
    say "IMAGE-OK $tag id=$id"
    printf 'image.%s.tag=%s\nimage.%s.id=%s\nimage.%s.status=verified\n' "$1" "$tag" "$1" "$id" "$1" >>"$MANIFEST"
  else
    rc=$?
    printf '%s\n' "$ver" | head -n 20 >&2 || true
    say "IMAGE-FAIL $tag stage=jvm-probe exit=$rc id=$id (java -version output above)"
    printf 'image.%s.tag=%s\nimage.%s.id=%s\nimage.%s.status=jvm-probe-failed exit=%s\n' "$1" "$tag" "$1" "$id" "$1" "$rc" >>"$MANIFEST"
    IMAGE_FAILS=$((IMAGE_FAILS + 1))
  fi
}
if command -v docker >/dev/null 2>&1; then
  image "$SERVICES_IMAGE" "$SERVICES_CTX"
  image "$WORKERS_IMAGE" "$WORKERS_CTX"
else
  say "IMAGE-FAIL all stage=tooling docker is not installed"
  printf 'image.all.status=docker-not-installed\n' >>"$MANIFEST"
  IMAGE_FAILS=2
fi
printf 'image.registry_push=not-performed (deploy-time, human-gated)\n' >>"$MANIFEST"
MANIFEST_SHA=$(sha256_file "$MANIFEST")
chmod a-w "$MANIFEST"
say "MANIFEST $MANIFEST sha256=$MANIFEST_SHA"
if ((IMAGE_FAILS > 0)); then
  say "RESULT PACKAGE-INCOMPLETE commit=$SHA image_failures=$IMAGE_FAILS"
  die "$EX_VERIFY" "$IMAGE_FAILS image(s) failed to build or verify (IMAGE-FAIL lines above); jar artifacts are recorded in $MANIFEST"
fi
say "RESULT PACKAGE-OK commit=$SHA"
