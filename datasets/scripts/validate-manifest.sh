#!/usr/bin/env bash
# validate-manifest.sh -- offline validation of datasets/dataset-manifest.yaml and datasets/samples/.
# Owner: dataset-engineer. No network access and no writes.
#
#   datasets/scripts/validate-manifest.sh --verify    run every check (exit 0 only if all pass)
#   datasets/scripts/validate-manifest.sh --dry-run   list what would be checked
#   no mode flag                                       usage, exit 2 (lib-datasets.sh contract)
#
# Checks (implemented in dsutil.py): every manifest field required by the course specification
# for every dataset, with nested required values non-null and non-empty; templated S3 raw
# prefixes (no real bucket names or ARNs; official public buckets matched by exact bucket name);
# license sources cited; observed sizes either recorded or explicitly pending; UNVERIFIED values
# listed with a reason; retrieval scripts exist and never download a full corpus by default; schema
# notes exist; every sample file is listed in datasets/samples/SHA256SUMS and matches (OS metadata
# such as .DS_Store is ignored); JSONL and XML samples parse; ORCID samples carry only the iD,
# names, other-names and work summaries (no email, address, keyword, researcher URL, person-level
# external identifier, biography, or affiliation, funding, peer-review or research-resource
# section); Crossref samples carry no abstracts.
set -euo pipefail
DS_NAME=validate-manifest
DS_DATASET=manifest
# shellcheck source=datasets/scripts/lib-datasets.sh
. "$(cd "$(dirname "$0")" && pwd)/lib-datasets.sh"

vm_usage() {
  cat >&2 <<EOF
usage: datasets/scripts/validate-manifest.sh (--verify | --dry-run) [datasets/dataset-manifest.yaml]

  --verify   run every offline manifest and sample check (exit 0 only if all pass)
  --dry-run  list what would be checked; no checks, no writes
EOF
}

mode=""
for a in "$@"; do
  case "$a" in
    --verify|--dry-run)
      [ -z "$mode" ] || ds_die "exactly one mode flag is allowed" 2
      mode="${a#--}"
      ;;
    -h|--help)
      vm_usage
      exit 0
      ;;
    --sample|--full)
      ds_die "$a is not meaningful for validation; use --verify or --dry-run" 2
      ;;
    datasets/dataset-manifest.yaml)
      ;;
    *)
      vm_usage
      ds_die "unknown argument: $a" 2
      ;;
  esac
done
if [ -z "$mode" ]; then
  vm_usage
  ds_die "no mode flag given" 2
fi
ds_need python3

if [ "$mode" = "dry-run" ]; then
  ds_log "would validate $DS_ROOT/datasets/dataset-manifest.yaml"
  ds_log "would verify checksums in $DS_ROOT/datasets/samples/SHA256SUMS"
  ds_log "would parse every JSONL and XML sample listed in the manifest"
  exit 0
fi

python3 -I "$DS_PY" validate "$DS_ROOT"
