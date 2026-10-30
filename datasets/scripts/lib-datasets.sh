# shellcheck shell=bash
# lib-datasets.sh -- shared helpers for datasets/scripts/fetch-*.sh and validate-manifest.sh.
# Owner: dataset-engineer. Sourced, never executed directly. Bash 3.2 compatible (macOS default).
#
# Safety contract (datasets/dataset-manifest.yaml, law 3):
#   * No mode flag -> usage + exit 2. Nothing is ever downloaded by default.
#   * --dry-run  : prints the plan. No network, no writes.
#   * --verify   : offline checks of committed samples and of any local downloads. No network.
#   * --sample   : bounded network fetch of the seed records in sample-seeds.tsv only. Every request
#                  has a byte cap and a time cap, and the whole run has one byte budget
#                  (SCHOLARCHAIN_SAMPLE_BUDGET_BYTES, default 20000000). A single fetch-<dataset>.sh
#                  run counts its own bytes. fetch-samples.sh creates a budget ledger file and exports
#                  SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER, so the four child scripts share one budget.
#                  Each request's byte cap is lowered to the remaining budget before it starts.
#                  Output goes to datasets/downloads/<dataset>/sample/ (git-ignored, human-confirmed
#                  2026-10-28; never into datasets/samples/).
#   * Logging    : query strings are never written to FETCH-LOG.tsv or to messages (they may carry
#                  the Crossref mailto address); the log shows "?<query-redacted>" instead.
#   * --full     : human-only. Requires SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=<snapshot id>. Only DBLP
#                  implements it; the other datasets print the official command and refuse.
#   * Idempotent : a file that exists and matches its .sha256 sidecar is never fetched again.

DS_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DS_ROOT="$(cd "$DS_SCRIPT_DIR/../.." && pwd)"
DS_DIR="$DS_ROOT/datasets"
DS_SEEDS="$DS_SCRIPT_DIR/sample-seeds.tsv"
DS_PY="$DS_SCRIPT_DIR/dsutil.py"
DS_UA="ScholarChain-dataset-sampler/1.0 (CS441 course project; bounded sample fetch)"
DS_SAMPLE_BUDGET_BYTES="${SCHOLARCHAIN_SAMPLE_BUDGET_BYTES:-20000000}"
DS_FETCHED_BYTES=0
DS_FAILURES=0
DS_MODE=""
DS_OUT=""

ds_log() { printf '[%s] %s\n' "${DS_NAME:-datasets}" "$*" >&2; }

ds_die() {
  printf '[%s] ERROR: %s\n' "${DS_NAME:-datasets}" "$1" >&2
  exit "${2:-1}"
}

ds_need() {
  local c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || ds_die "required command not found: $c" 2
  done
}

ds_usage() {
  cat >&2 <<EOF
usage: datasets/scripts/${DS_NAME}.sh (--dry-run | --verify | --sample | --full) [datasets/<output-dir>]

  --dry-run  print the retrieval plan; no network, no writes
  --verify   offline integrity checks (committed samples, local downloads)
  --sample   bounded fetch of the seed records in datasets/scripts/sample-seeds.tsv
  --full     HUMAN ONLY; requires SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=<snapshot id>
             (see datasets/dataset-manifest.yaml). Not implemented for every dataset.

Default output directory: datasets/downloads/${DS_NAME#fetch-}/ (git-ignored).
An output directory argument must be under datasets/downloads/.
Run from the repository root.
EOF
}

# ds_parse_args "$@" -> sets DS_MODE (dry-run|verify|sample|full) and DS_OUT (absolute path).
ds_parse_args() {
  local a
  for a in "$@"; do
    case "$a" in
      --dry-run|--verify|--sample|--full)
        [ -z "$DS_MODE" ] || ds_die "exactly one mode flag is allowed (got --$DS_MODE and $a)" 2
        DS_MODE="${a#--}"
        ;;
      -h|--help)
        ds_usage
        exit 0
        ;;
      -*)
        ds_usage
        ds_die "unknown option: $a" 2
        ;;
      *)
        [ -z "$DS_OUT" ] || ds_die "at most one output directory argument is allowed" 2
        DS_OUT="$a"
        ;;
    esac
  done
  if [ -z "$DS_MODE" ]; then
    ds_usage
    ds_die "no mode flag given; nothing is downloaded by default" 2
  fi
  ds_resolve_out
}

ds_resolve_out() {
  local rel="${DS_OUT:-datasets/downloads/${DS_DATASET:-misc}}"
  case "$rel" in
    /*) ds_die "output directory must be a relative path under datasets/downloads/ (got $rel)" 2 ;;
  esac
  case "/$rel/" in
    */../*|*/./*|*//*) ds_die "output directory must not contain empty, . or .. segments (got $rel)" 2 ;;
  esac
  # Only the git-ignored download area is writable (.gitignore /datasets/downloads/, human-confirmed
  # 2026-10-28; also pruned by project/BuildGuard.scala). Anything else would be git-visible.
  case "$rel" in
    datasets/downloads|datasets/downloads/*) ;;
    *) ds_die "output directory must be under datasets/downloads/ (git-ignored); got $rel" 2 ;;
  esac
  DS_OUT="$DS_ROOT/${rel%/}"
}

ds_sha256() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

ds_md5() {
  if command -v md5sum >/dev/null 2>&1; then
    md5sum "$1" | awk '{print $1}'
  else
    md5 -q "$1"
  fi
}

# ds_manifest_get dotted.key -> prints one scalar from datasets/dataset-manifest.yaml
ds_manifest_get() {
  python3 -I "$DS_PY" get "$DS_ROOT" "$1"
}

# ds_is_current FILE -> 0 when FILE exists and matches its FILE.sha256 sidecar
ds_is_current() {
  [ -s "$1" ] && [ -s "$1.sha256" ] && [ "$(ds_sha256 "$1")" = "$(cat "$1.sha256")" ]
}

# ds_redact_url URL -> URL without its query string or fragment. Query strings may carry personal
# or secret-like values (the Crossref mailto address), so they are never logged.
ds_redact_url() {
  local u="$1" base
  base="${u%%[?#]*}"
  if [ "$base" != "$u" ]; then
    printf '%s?<query-redacted>' "$base"
  else
    printf '%s' "$u"
  fi
}

# ds_urlencode STRING -> RFC 3986 percent-encoding (unreserved characters kept), byte by byte.
ds_urlencode() {
  local LC_ALL=C
  local s="$1" out="" c i n
  n=${#s}
  i=0
  while [ "$i" -lt "$n" ]; do
    c="${s:$i:1}"
    case "$c" in
      [A-Za-z0-9.~_-]) out="$out$c" ;;
      *) out="$out$(printf '%%%02X' "'$c")" ;;
    esac
    i=$((i + 1))
  done
  printf '%s' "$out"
}

ds_is_uint() {
  case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac
}

# ds_budget_used -> bytes already fetched in this run. Uses the shared ledger exported by
# fetch-samples.sh when present, otherwise this process's own counter.
ds_budget_used() {
  local v
  if [ -n "${SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER:-}" ]; then
    [ -f "$SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER" ] && [ ! -L "$SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER" ] \
      || ds_die "sample budget ledger is missing or not a regular file: $SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER"
    v="$(cat "$SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER")"
    ds_is_uint "$v" || ds_die "sample budget ledger does not hold a byte count: $SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER"
    printf '%s' "$v"
  else
    printf '%s' "$DS_FETCHED_BYTES"
  fi
}

# ds_budget_add BYTES -> adds to this process's counter and, when present, to the shared ledger.
ds_budget_add() {
  local used
  used="$(ds_budget_used)" || true
  ds_is_uint "$used" || ds_die "cannot read the sample byte budget; stopping"
  DS_FETCHED_BYTES=$((DS_FETCHED_BYTES + $1))
  if [ -n "${SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER:-}" ]; then
    printf '%s\n' "$((used + $1))" > "$SCHOLARCHAIN_SAMPLE_BUDGET_LEDGER" \
      || ds_die "cannot update the sample budget ledger; stopping"
  fi
}

# ds_fetch URL DEST MAX_BYTES [MAX_SECONDS] [ACCEPT_HEADER]
# Bounded, idempotent, atomic download. Returns 1 (and counts a failure) instead of exiting,
# so one unreachable seed does not hide the others. Exits when the run's byte budget is spent.
ds_fetch() {
  local url="$1" dest="$2" max="$3" secs="${4:-60}" accept="${5:-*/*}"
  local shown
  shown="$(ds_redact_url "$url")"
  case "$url" in
    https://*) ;;
    *) ds_die "refusing non-https URL: $shown" ;;
  esac
  if ds_is_current "$dest"; then
    ds_log "already present and verified, skipping: ${dest#"$DS_ROOT"/}"
    return 0
  fi
  ds_is_uint "$DS_SAMPLE_BUDGET_BYTES" || ds_die "SCHOLARCHAIN_SAMPLE_BUDGET_BYTES must be a byte count" 2
  local used remaining
  # ds_fetch often runs under "|| true", where errexit is off, so check the result explicitly.
  used="$(ds_budget_used)" || true
  ds_is_uint "$used" || ds_die "cannot read the sample byte budget; stopping"
  remaining=$((DS_SAMPLE_BUDGET_BYTES - used))
  if [ "$remaining" -le 0 ]; then
    ds_die "sample byte budget spent ($used of $DS_SAMPLE_BUDGET_BYTES bytes); stopping before $shown"
  fi
  if [ "$remaining" -lt "$max" ]; then
    max="$remaining"
  fi
  mkdir -p "$(dirname "$dest")"
  local tmp="$dest.part.$$"
  if ! curl --fail --silent --show-error --location \
      --proto '=https' --proto-redir '=https' \
      --max-filesize "$max" --max-time "$secs" --retry 2 --retry-delay 3 \
      -A "$DS_UA" -H "Accept: $accept" -o "$tmp" "$url"; then
    # Partial bytes of an aborted transfer also count against the run's budget.
    if [ -f "$tmp" ]; then
      ds_budget_add "$(wc -c < "$tmp" | tr -d ' ')"
    fi
    rm -f "$tmp"
    ds_log "FAILED fetch (cap $max bytes): $shown"
    DS_FAILURES=$((DS_FAILURES + 1))
    return 1
  fi
  local size
  size="$(wc -c < "$tmp" | tr -d ' ')"
  # Bytes that arrived count against the budget even when the response is then rejected.
  ds_budget_add "$size"
  if [ "$size" -gt "$max" ]; then
    rm -f "$tmp"
    ds_log "FAILED: response of $size bytes exceeds cap $max: $shown"
    DS_FAILURES=$((DS_FAILURES + 1))
    return 1
  fi
  mv -f "$tmp" "$dest"
  ds_sha256 "$dest" > "$dest.sha256"
  printf '%s\t%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$shown" "$size" "$(cat "$dest.sha256")" \
    >> "$(dirname "$dest")/FETCH-LOG.tsv"
  ds_log "fetched $size bytes: ${dest#"$DS_ROOT"/}"
}

# ds_seeds DATASET KIND -> prints matching identifiers from sample-seeds.tsv
ds_seeds() {
  local ds kind id
  while read -r ds kind id; do
    case "$ds" in ''|'#'*) continue ;; esac
    if [ "$ds" = "$1" ] && [ "$kind" = "$2" ]; then
      printf '%s\n' "$id"
    fi
  done < "$DS_SEEDS"
}

# ds_verify_committed DATASET -> offline validation of the manifest entry and committed samples
ds_verify_committed() {
  python3 -I "$DS_PY" validate "$DS_ROOT" --dataset "$1"
}

# ds_require_full_confirmation SNAPSHOT_ID
ds_require_full_confirmation() {
  if [ "${SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD:-}" != "$1" ]; then
    ds_die "--full downloads a complete corpus and is run by the human only. Re-run with SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=$1 after checking disk space and cost (see datasets/dataset-manifest.yaml)." 3
  fi
}

ds_finish() {
  if [ "$DS_FAILURES" -gt 0 ]; then
    ds_die "$DS_FAILURES step(s) failed (see messages above)"
  fi
  ds_log "done (mode --$DS_MODE, fetched $DS_FETCHED_BYTES bytes)"
}
