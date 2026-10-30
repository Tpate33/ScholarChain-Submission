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
set -euo pipefail
DS_NAME=fetch-samples
DS_DATASET=all
# shellcheck source=datasets/scripts/lib-datasets.sh
. "$(cd "$(dirname "$0")" && pwd)/lib-datasets.sh"

[ "$#" -eq 1 ] || ds_die "usage: fetch-samples.sh (--dry-run | --verify | --sample)" 2
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
