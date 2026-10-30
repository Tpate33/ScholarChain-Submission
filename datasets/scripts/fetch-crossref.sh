#!/usr/bin/env bash
# fetch-crossref.sh -- bounded retrieval for the Crossref enrichment dataset. Owner: dataset-engineer.
#
#   --dry-run  print the pinned release, distribution channels and expected sizes
#   --verify   offline: validate the manifest entry and committed sample
#   --sample   bounded: the seed DOIs from api.crossref.org (<=1 MB each) into
#              datasets/downloads/crossref/sample/. Optional SCHOLARCHAIN_CROSSREF_MAILTO adds the
#              documented "polite pool" mailto parameter (percent-encoded). The address is never
#              stored: not in the repository, and not in FETCH-LOG.tsv or log messages (lib-datasets.sh
#              redacts every query string before logging).
#   --full     refused: the public data file is about 208 GB (blog) and is distributed by torrent
#              or a requester-pays S3 bucket (cost to the requester). Prints the channels, exits 3.
set -euo pipefail
DS_NAME=fetch-crossref
DS_DATASET=crossref
# shellcheck source=datasets/scripts/lib-datasets.sh
. "$(cd "$(dirname "$0")" && pwd)/lib-datasets.sh"
ds_parse_args "$@"
ds_need python3 curl

API="$(ds_manifest_get datasets.crossref.official_source.api_base)"
MAILTO="${SCHOLARCHAIN_CROSSREF_MAILTO:-}"

case "$DS_MODE" in
  dry-run)
    ds_log "snapshot:        $(ds_manifest_get datasets.crossref.snapshot.id)"
    ds_log "torrent:         $(ds_manifest_get datasets.crossref.official_source.torrent_page)"
    ds_log "requester-pays:  $(ds_manifest_get datasets.crossref.official_source.requester_pays_bucket) (2026 object key UNVERIFIED)"
    ds_log "expected size:   see datasets.crossref.size.expected in the manifest"
    ds_log "--sample writes: ${DS_OUT#"$DS_ROOT"/}/sample/api/"
    ds_log "seed DOIs:"
    ds_seeds crossref doi | sed 's/^/  /' >&2
    ;;

  verify)
    ds_verify_committed crossref
    ;;

  sample)
    for doi in $(ds_seeds crossref doi); do
      url="$API/works/$doi"
      if [ -n "$MAILTO" ]; then
        url="$url?mailto=$(ds_urlencode "$MAILTO")"
      fi
      ds_fetch "$url" "$DS_OUT/sample/api/$(printf '%s' "$doi" | tr '/' '_').json" 1000000 60 "application/json" || true
    done
    ;;

  full)
    ds_log "Refusing --full: the Crossref public data file is about 208 GB and is distributed by torrent"
    ds_log "or a requester-pays S3 bucket. Acquisition is a human decision (see the manifest's unverified and open items)."
    ds_log "torrent: $(ds_manifest_get datasets.crossref.official_source.torrent_page)"
    exit 3
    ;;
esac
ds_finish
