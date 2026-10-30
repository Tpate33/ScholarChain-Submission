#!/usr/bin/env bash
# fetch-openalex.sh -- bounded retrieval for the OpenAlex enrichment dataset. Owner: dataset-engineer.
#
#   --dry-run  print the pinned release, manifest URLs, expected sizes and the official copy command
#   --verify   offline: validate the manifest entry and committed samples; check any downloaded
#              per-entity manifest.json against the pinned snapshot date
#   --sample   bounded: the per-entity manifest.json files for works/authors/institutions
#              (<=5 MB each) and the seed records from api.openalex.org (<=2 MB each)
#              into datasets/downloads/openalex/sample/ (git-ignored)
#   --full     refused: the JSONL snapshot is about 755 GB. Copying it into the S3 raw zone is a
#              human-approved pipeline action; this prints the documented command and exits 3.
set -euo pipefail
DS_NAME=fetch-openalex
DS_DATASET=openalex
# shellcheck source=datasets/scripts/lib-datasets.sh
. "$(cd "$(dirname "$0")" && pwd)/lib-datasets.sh"
ds_parse_args "$@"
ds_need python3 curl

SNAP_DATE="$(ds_manifest_get datasets.openalex.snapshot.date)"
BASE="$(ds_manifest_get datasets.openalex.official_source.https_mirror)"
API="$(ds_manifest_get datasets.openalex.official_source.api_base)"
ENTITIES="works authors institutions"

case "$DS_MODE" in
  dry-run)
    ds_log "snapshot date:   $SNAP_DATE"
    for e in $ENTITIES; do
      ds_log "manifest:        $BASE/data/jsonl/$e/manifest.json"
    done
    ds_log "expected sizes:  see datasets.openalex.size.expected in the manifest"
    ds_log "--sample writes: ${DS_OUT#"$DS_ROOT"/}/sample/"
    ds_log "full copy (human, not run by this script): $(ds_manifest_get datasets.openalex.retrieval.official_bulk_command)"
    ;;

  verify)
    ds_verify_committed openalex
    for e in $ENTITIES; do
      f="$DS_OUT/sample/manifests/$e-manifest.json"
      if [ -s "$f" ]; then
        python3 -I "$DS_PY" check-openalex-manifest "$f" "$SNAP_DATE"
      fi
    done
    ;;

  sample)
    for e in $ENTITIES; do
      f="$DS_OUT/sample/manifests/$e-manifest.json"
      if ds_fetch "$BASE/data/jsonl/$e/manifest.json" "$f" 5000000 120 "application/json"; then
        if ! python3 -I "$DS_PY" check-openalex-manifest "$f" "$SNAP_DATE"; then
          ds_log "WARNING: OpenAlex has published a newer release than the pinned $SNAP_DATE; update the manifest before any copy."
          DS_FAILURES=$((DS_FAILURES + 1))
        fi
      fi
    done
    for id in $(ds_seeds openalex work); do
      ds_fetch "$API/works/$id" "$DS_OUT/sample/api/works/$id.json" 2000000 60 "application/json" || true
    done
    for id in $(ds_seeds openalex author); do
      ds_fetch "$API/authors/$id" "$DS_OUT/sample/api/authors/$id.json" 2000000 60 "application/json" || true
    done
    for id in $(ds_seeds openalex institution); do
      ds_fetch "$API/institutions/$id" "$DS_OUT/sample/api/institutions/$id.json" 2000000 60 "application/json" || true
    done
    ;;

  full)
    ds_log "Refusing --full: the OpenAlex JSONL snapshot is about 755 GB (manifest size.expected)."
    ds_log "The raw-zone copy is a human-approved pipeline action (cost and destination are open questions in the manifest)."
    ds_log "Documented upstream command: $(ds_manifest_get datasets.openalex.retrieval.official_bulk_command)"
    exit 3
    ;;
esac
ds_finish
