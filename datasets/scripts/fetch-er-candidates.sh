#!/usr/bin/env bash
# fetch-er-candidates.sh -- entity-resolution candidate sample set (work orders DS-P5-01, DS-P5-03).
# Owner: dataset-engineer.
#
#   --dry-run                 print the split counts and every planned request (no network, no writes)
#   --verify                  offline: selection, split rule, provenance ledger, checksums, record content,
#                             and every recorded dblp byte range against its stored file
#   --verify --with-release   as --verify, and also re-read every recorded dblp byte range from the local
#                             pinned release datasets/downloads/dblp/full/<release>.xml.gz (still offline;
#                             the release MD5 must equal the manifest pin and its sha256 sidecar must match)
#   --fixture                 offline self-test of the dblp release extractor on the committed fixture
#                             datasets/er-candidates/fixture/fixture-release.xml (gzipped into a temporary
#                             directory; writes nothing to the repository; no network)
#   --sample                  1. compute the dev/heldout split from the unit IDs alone and write
#                                datasets/er-candidates/split-assignment.tsv BEFORE any request (refuses to
#                                run when an existing file differs from the rule);
#                             2. dblp records: OFFLINE ONLY. Each listed key is extracted from the pinned
#                                release that the human downloaded with fetch-dblp.sh --full. A missing or
#                                mismatched release (MD5 pin, sha256 sidecar) or a malformed layout fails
#                                closed: no dblp file is written and NO network request is made. A key that
#                                is not in the pinned release is recorded as key-not-in-pinned-release and
#                                never substituted. Per-record dblp URLs are never requested (dblp.org
#                                robots.txt: Disallow /rec/xml, /*.xml, /*.json);
#                             3. network, only two hosts: api.openalex.org/works/doi:<doi>?select=... and
#                                api.openalex.org/authors/<A-ID>?select=...; api.crossref.org/works?filter=doi:...;
#                             4. expand "authors-of-work" rows to the OpenAlex author records of the
#                                fetched work (at most 6 per work, authorship order);
#                             5. store each body byte for byte under datasets/samples/er-candidates/<split>/
#                                with one PROVENANCE.tsv row, and one DBLP-RELEASE-RANGES.tsv row per dblp
#                                excerpt.
#                             Bounds: per-response byte caps, one 8 MiB budget, polite pacing per host, a
#                             500 s run budget, no retries after HTTP 429. Idempotent: a stored file that
#                             matches its ledger row is never fetched or extracted again.
#                             NOTE: --sample writes into datasets/samples/er-candidates/ (committed-ready),
#                             not into the git-ignored datasets/downloads/ area.
#   --full                    refused: there is no full mode for a review sample.
#
# Run from the repository root.
set -euo pipefail
DS_NAME=fetch-er-candidates
DS_DATASET=er-candidates
# shellcheck source=datasets/scripts/lib-datasets.sh
. "$(cd "$(dirname "$0")" && pwd)/lib-datasets.sh"

usage() {
  echo "usage: datasets/scripts/fetch-er-candidates.sh (--dry-run | --verify [--with-release] | --fixture | --sample)" >&2
}

if [ "$#" -eq 2 ] && [ "$1" = "--verify" ] && [ "$2" = "--with-release" ]; then
  ds_need python3
  exec python3 -I "$DS_PY" er-verify "$DS_ROOT" --with-release
fi
[ "$#" -eq 1 ] || { usage; ds_die "exactly one mode flag is required; nothing is fetched by default" 2; }
ds_need python3
case "$1" in
  --dry-run)
    python3 -I "$DS_PY" er-fetch "$DS_ROOT" dry-run
    ;;
  --verify)
    python3 -I "$DS_PY" er-verify "$DS_ROOT"
    ;;
  --fixture)
    python3 -I "$DS_PY" er-fixture "$DS_ROOT"
    ;;
  --sample)
    ds_need curl
    python3 -I "$DS_PY" er-fetch "$DS_ROOT" sample
    ;;
  --full)
    ds_die "--full is not defined for the ER candidate sample set" 3
    ;;
  -h|--help)
    usage
    ;;
  *)
    usage
    ds_die "unknown argument: $1" 2
    ;;
esac
