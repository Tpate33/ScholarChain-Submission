#!/usr/bin/env bash
# fetch-samples.sh -- run one mode of every fetch-<dataset>.sh in turn. Owner: dataset-engineer.
#
#   datasets/scripts/fetch-samples.sh --dry-run | --verify | --sample
#
# --full is deliberately not forwarded. Each dataset's --full is a separate human decision.
# Output goes to datasets/downloads/<dataset>/ (git-ignored; one directory per dataset). An
# output-directory argument is not accepted here because the four datasets would share it.
#
# Byte budget: in --sample mode one budget (SCHOLARCHAIN_SAMPLE_BUDGET_BYTES, default 20000000)
# covers the whole run, not each child script. This script writes a ledger file under
# datasets/downloads/ and exports SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER; every child ds_fetch reads the
# bytes already spent from it, lowers its per-request cap to the remaining budget, and adds the
# bytes it received. The ledger is removed when this script exits.
#
# ER candidate sample set (work orders DS-P5-01, DS-P5-03):
#   datasets/scripts/fetch-samples.sh (--dry-run | --verify | --sample) datasets/er-candidates
#       runs datasets/scripts/fetch-er-candidates.sh with the same mode (see that script);
#   datasets/scripts/fetch-samples.sh --verify datasets/er-candidates datasets/downloads/dblp/full/<release>.xml.gz
#       runs fetch-er-candidates.sh --verify --with-release (the path must be the manifest-pinned release);
#   datasets/scripts/fetch-samples.sh --dry-run datasets/er-candidates datasets/downloads/dblp/full/<release>.xml.gz
#       runs fetch-er-candidates.sh --dry-run --with-release: READ-ONLY layout inspection of the pinned release
#       (escaped bytes, bounded output) and the extraction scanner's verdict per key; writes nothing (DS-P5-09);
#   datasets/scripts/fetch-samples.sh --verify datasets/er-candidates/fixture
#       runs fetch-er-candidates.sh --fixture (offline extractor self-test; writes only to a temp dir).
# This entry point exists because a newly created script has no executable bit until the human sets
# it; this committed script invokes it through bash. Only path arguments are used, so every ER mode
# has a plain "<script> <mode> <datasets/ paths>" shape.
set -euo pipefail
DS_NAME=fetch-samples
DS_DATASET=all
# shellcheck source=datasets/scripts/lib-datasets.sh
. "$(cd "$(dirname "$0")" && pwd)/lib-datasets.sh"

ER_USAGE="usage: fetch-samples.sh (--dry-run | --verify | --sample) datasets/er-candidates | (--verify | --dry-run) datasets/er-candidates <pinned release path> | --verify datasets/er-candidates/fixture"
if [ "$#" -eq 2 ] && [ "$2" = "datasets/er-candidates/fixture" ]; then
  [ "$1" = "--verify" ] || ds_die "$ER_USAGE" 2
  exec bash "$DS_SCRIPT_DIR/fetch-er-candidates.sh" --fixture
fi
if [ "$#" -eq 3 ] && [ "$2" = "datasets/er-candidates" ]; then
  case "$1" in
    --verify|--dry-run) ;;
    *) ds_die "$ER_USAGE" 2 ;;
  esac
  pinned="datasets/downloads/dblp/full/$(basename "$(ds_manifest_get datasets.dblp.official_source.download_url)")"
  [ "$3" = "$pinned" ] || ds_die "the release path must be the manifest-pinned $pinned (got $3)" 2
  # --verify: re-read every recorded byte range from the release. --dry-run: read-only layout inspection of the
  # release and the extraction scanner's verdict per selected key (DS-P5-09); writes nothing.
  exec bash "$DS_SCRIPT_DIR/fetch-er-candidates.sh" "$1" --with-release
fi
if [ "$#" -eq 2 ] && [ "$2" = "datasets/er-candidates" ]; then
  case "$1" in
    --dry-run|--verify|--sample) exec bash "$DS_SCRIPT_DIR/fetch-er-candidates.sh" "$1" ;;
    *) ds_die "$ER_USAGE" 2 ;;
  esac
fi
[ "$#" -eq 1 ] || ds_die "usage: fetch-samples.sh (--dry-run | --verify | --sample) [datasets/er-candidates ...]" 2
case "$1" in
  --dry-run|--verify|--sample) ;;
  --full) ds_die "--full is never forwarded; run the single dataset script after a human decision" 3 ;;
  *) ds_die "usage: fetch-samples.sh (--dry-run | --verify | --sample)" 2 ;;
esac

if [ "$1" = "--sample" ]; then
  ds_is_uint "$DS_SAMPLE_BUDGET_BYTES" || ds_die "SCHOLARCHAIN_SAMPLE_BUDGET_BYTES must be a byte count" 2
  mkdir -p "$DS_DIR/downloads"
  ledger="$DS_DIR/downloads/.sample-budget.$$"
  printf '0\n' > "$ledger"
  trap 'rm -f "$ledger"' EXIT
  export SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER="$ledger"
  export SCHOLARCHAIN_SAMPLE_BUDGET_BYTES="$DS_SAMPLE_BUDGET_BYTES"
  ds_log "one byte budget for the whole run: $DS_SAMPLE_BUDGET_BYTES bytes"
fi

rc=0
for ds in dblp openalex crossref orcid; do
  ds_log "== $ds $1"
  bash "$DS_SCRIPT_DIR/fetch-$ds.sh" "$1" || rc=1
done
if [ "$1" = "--sample" ]; then
  ds_log "bytes fetched in this run: $(cat "$ledger") of $DS_SAMPLE_BUDGET_BYTES"
fi
[ "$rc" -eq 0 ] || ds_die "at least one dataset reported a failure (see above)"
ds_log "all datasets completed $1"
