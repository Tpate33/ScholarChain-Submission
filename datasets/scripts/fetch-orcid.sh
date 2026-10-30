#!/usr/bin/env bash
# fetch-orcid.sh -- bounded retrieval for the ORCID public data file enrichment. Owner: dataset-engineer.
#
#   --dry-run  print the pinned release record, the summaries file, and its expected size
#   --verify   offline: validate the manifest entry and committed samples
#   --sample   bounded: the figshare release metadata (<=1 MB), plus personal-details and single
#              work summaries of the seed iDs from the ORCID public API v3.0 (<=1 MB each) into
#              datasets/downloads/orcid/sample/ (git-ignored). Full works lists are NOT fetched
#              (they can be several MB).
#   --full     refused: the summaries archive alone is about 46 GB of personal data. Acquisition is
#              a human decision (privacy and storage review). Prints the release record and exits 3.
set -euo pipefail
DS_NAME=fetch-orcid
DS_DATASET=orcid
# shellcheck source=datasets/scripts/lib-datasets.sh
. "$(cd "$(dirname "$0")" && pwd)/lib-datasets.sh"
ds_parse_args "$@"
ds_need python3 curl

API="$(ds_manifest_get datasets.orcid.official_source.api_base)"
META="$(ds_manifest_get datasets.orcid.official_source.release_metadata_api)"

case "$DS_MODE" in
  dry-run)
    ds_log "snapshot:        $(ds_manifest_get datasets.orcid.snapshot.id)"
    ds_log "release record:  $(ds_manifest_get datasets.orcid.official_source.release_doi)"
    ds_log "release meta:    $META"
    ds_log "summaries file:  $(ds_manifest_get datasets.orcid.official_source.summaries_file) (size: see manifest)"
    ds_log "--sample writes: ${DS_OUT#"$DS_ROOT"/}/sample/"
    ;;

  verify)
    ds_verify_committed orcid
    ;;

  sample)
    ds_fetch "$META" "$DS_OUT/sample/figshare-release.json" 1000000 60 "application/json" || true
    for id in $(ds_seeds orcid personal-details); do
      ds_fetch "$API/$id/personal-details" "$DS_OUT/sample/api/$id/personal-details.xml" 1000000 60 "application/xml" || true
    done
    for ref in $(ds_seeds orcid work); do
      id="${ref%%/*}"
      put="${ref#*/}"
      ds_fetch "$API/$id/work/$put" "$DS_OUT/sample/api/$id/work-$put.xml" 1000000 60 "application/xml" || true
    done
    ;;

  full)
    ds_log "Refusing --full: the ORCID summaries archive is about 46 GB of personal data."
    ds_log "Acquisition needs a human privacy and storage decision (see the manifest)."
    ds_log "release record: $(ds_manifest_get datasets.orcid.official_source.release_doi)"
    exit 3
    ;;
esac
ds_finish
