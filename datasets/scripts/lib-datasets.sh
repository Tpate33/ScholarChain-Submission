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
# The only area any script writes downloads to (git-ignored). Assigned here, never read from the environment.
DS_DOWNLOADS="$DS_DIR/downloads"

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

# ds_md5 FILE -> prints the lowercase hex MD5 of FILE. Returns 1 and prints nothing when no MD5 tool works or
# its output is not 32 lowercase hex digits (RV-P5-03 N4). The file is read on stdin, so its name can never
# change the tool's output format.
ds_md5() {
  local out
  if command -v md5sum >/dev/null 2>&1; then
    out="$(md5sum < "$1")" || return 1
    out="${out%% *}"
  elif command -v md5 >/dev/null 2>&1; then
    out="$(md5 -q < "$1")" || return 1
  else
    return 1
  fi
  case "$out" in ''|*[!0-9a-f]*) return 1 ;; esac
  [ "${#out}" -eq 32 ] || return 1
  printf '%s\n' "$out"
}

ds_need_md5() {
  command -v md5sum >/dev/null 2>&1 || command -v md5 >/dev/null 2>&1 \
    || ds_die "required command not found: md5sum or md5 (needed for the MD5 pin check)" 2
}

# ds_write_sidecar FILE -> writes FILE.sha256 through a temporary file and mv, so a crash leaves either no
# sidecar or a complete one (RV-P5-02 nit 12). Never writes through a symbolic link (RV-P5-07 MINOR-1): a linked
# temporary file or sidecar is refused, and the temporary file is created with noclobber (O_EXCL), so a link
# planted after the check makes the write fail instead of following it.
ds_write_sidecar() {
  local sha tmp="$1.sha256.tmp"
  sha="$(ds_sha256 "$1")" || return 1
  case "$sha" in ''|*[!0-9a-f]*) return 1 ;; esac
  [ "${#sha}" -eq 64 ] || return 1
  if [ -L "$tmp" ] || [ -L "$1.sha256" ]; then
    ds_log "ERROR: $tmp or $1.sha256 is a symbolic link; the sidecar was not written (links are never followed)"
    return 1
  fi
  if [ -e "$tmp" ]; then
    rm -f "$tmp" || return 1
  fi
  ( set -C; printf '%s\n' "$sha" > "$tmp" ) || return 1
  mv -f "$tmp" "$1.sha256" || return 1
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
  # -q must be the first argument: curl then ignores ~/.curlrc (RV-P5-07 NIT-5).
  if ! curl -q --fail --silent --show-error --location \
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

# Transfer bounds of the human-only full download (RV-P5-02 finding 1). There is deliberately NO total
# --max-time: a slow but healthy 1 GB transfer must not be cut off. A connection that cannot be opened
# within DS_FULL_CONNECT_TIMEOUT_S seconds, or a transfer slower than DS_FULL_STALL_BYTES_PER_S bytes/s
# for DS_FULL_STALL_TIME_S consecutive seconds, makes curl exit 28. Recorded in the manifest under
# datasets.dblp.retrieval.full_transfer_bounds.
DS_FULL_CONNECT_TIMEOUT_S=30
DS_FULL_STALL_BYTES_PER_S=1024
DS_FULL_STALL_TIME_S=120
DS_FULL_MAX_BYTES=2000000000

ds_bytes_of() {
  if [ -f "$1" ]; then wc -c < "$1" | tr -d ' '; else printf '0'; fi
}

# ds_full_recovery CURL_EXIT HTTP_CODE PART [PRE_MD5] -> prints the recovery step for a failed full transfer.
# It never deletes PART: the expected byte size of the release is not published (UNVERIFIED), so a
# script cannot prove that PART is complete. The human decides (RV-P5-02 finding 2).
# PRE_MD5 is what the MD5 check of PART before the request found: mismatch, failed (no MD5 could be computed)
# or unchecked (no non-empty PART). The 416 text claims an MD5 mismatch only for "mismatch" (RV-P5-07 NIT-5).
ds_full_recovery() {
  local rc="$1" code="$2" part="$3" pre="${4:-unchecked}" n q
  n="$(ds_bytes_of "$part")"
  # The path in a printed rm command is shell-escaped, so copy-pasting it removes exactly that file even when
  # the path holds quotes, spaces or shell syntax (RV-P5-03 N3).
  q="$(printf '%q' "$part")"
  case "$rc:$code" in
    28:*)
      ds_log "RECOVERY: curl exit 28: no connection within ${DS_FULL_CONNECT_TIMEOUT_S} s, or the transfer stalled (below ${DS_FULL_STALL_BYTES_PER_S} bytes/s for ${DS_FULL_STALL_TIME_S} s). The partial file $part ($n bytes) was KEPT. Re-run the same command to resume it." ;;
    *:416)
      case "$pre" in
        mismatch)
          ds_log "RECOVERY: the server refused to resume (HTTP 416, range not satisfiable). The partial file $part ($n bytes) is already at least as long as the release, but its MD5 is not the pin, so it is corrupt and cannot be resumed. It was KEPT (not deleted automatically, because the exact release size is not published). Manual step: rm $q and re-run the same command (downloads about 1.03 GB again)." ;;
        failed)
          ds_log "RECOVERY: the server refused to resume (HTTP 416, range not satisfiable). The partial file $part ($n bytes) may already be complete, but its MD5 could NOT be computed before the request (md5sum or md5 missing or failed), so it is not known whether it is the release or corrupt. It was KEPT. Do not delete it yet: fix the MD5 tool and re-run the same command; a .part that matches the pin is then used without downloading again. Only if the same command then reports an MD5 that is not the pin: rm $q and re-run." ;;
        *)
          ds_log "RECOVERY: the server refused to resume (HTTP 416, range not satisfiable). The partial file $part ($n bytes) was KEPT; its MD5 was not checked before the request. Re-run the same command first (a .part that matches the pin is not downloaded again). If the same error repeats, rm $q and re-run (downloads about 1.03 GB again)." ;;
      esac ;;
    33:*)
      ds_log "RECOVERY: curl exit 33: the server does not accept byte ranges, so $part ($n bytes) cannot be resumed. It was KEPT. Manual step: rm $q and re-run the same command to download from the start." ;;
    63:*)
      ds_log "RECOVERY: curl exit 63: the response exceeds the ${DS_FULL_MAX_BYTES}-byte cap. $part ($n bytes) was KEPT for inspection. Do not re-run; report this to dataset-engineer (the pinned URL may have changed)." ;;
    *)
      ds_log "RECOVERY: curl exit $rc (HTTP ${code:-none}). The partial file $part ($n bytes) was KEPT. Re-run the same command to resume. If the same error repeats, rm $q and re-run to start over." ;;
  esac
}

# ds_full_link_refused PATH -> the refusal message of ds_full_refuse_links.
ds_full_link_refused() {
  ds_log "ERROR: refusing the full download: ${1#"$DS_ROOT"/} is a symbolic link. The full download writes only real directories and files under ${DS_DOWNLOADS#"$DS_ROOT"/}/ and never follows, deletes or writes through a link; nothing was written or deleted through it, and no request was made after this check. Inspect it by hand (ls -ld $(printf '%q' "$1")), replace it with a real directory or remove it, then re-run the same command."
}

# ds_full_refuse_links DEST -> 0 when no path that the full download reads, creates or writes is a symbolic link.
# Otherwise logs the link and returns 2 (RV-P5-07 MINOR-1). Checked with -L (which never follows the link):
# DS_DOWNLOADS (datasets/downloads) itself, every path component below it down to DEST, DEST, DEST.part,
# DEST.sha256 and DEST.sha256.tmp. DEST must lie under DS_DOWNLOADS. ds_full_download calls it before any network
# access and again before the transfer and before the rename.
ds_full_refuse_links() {
  local dest="$1" rest seg p
  case "$dest" in
    "$DS_DOWNLOADS"/*) ;;
    *) ds_log "ERROR: refusing the full download: $dest is not under ${DS_DOWNLOADS#"$DS_ROOT"/}/"; return 2 ;;
  esac
  p="$DS_DOWNLOADS"
  if [ -L "$p" ]; then ds_full_link_refused "$p"; return 2; fi
  rest="${dest#"$DS_DOWNLOADS"/}"
  while [ -n "$rest" ]; do
    seg="${rest%%/*}"
    if [ "$seg" = "$rest" ]; then rest=""; else rest="${rest#*/}"; fi
    p="$p/$seg"
    if [ -L "$p" ]; then ds_full_link_refused "$p"; return 2; fi
  done
  for p in "$dest.part" "$dest.sha256" "$dest.sha256.tmp"; do
    if [ -L "$p" ]; then ds_full_link_refused "$p"; return 2; fi
  done
  return 0
}

# ds_full_download URL DEST PIN_MD5 -> 0 when DEST exists with a matching .sha256 sidecar, after the
# MD5 pin and gzip -t checks. Never relies on errexit; every failure returns non-zero with a message.
# Refuses (exit status 2, before any network access) when a path it would use is a symbolic link.
ds_full_download() {
  local url="$1" dest="$2" pin="$3" part="$2.part" rc code got before after pre
  ds_full_refuse_links "$dest" || return 2
  if ds_is_current "$dest"; then
    ds_log "already present and verified: $dest"
    return 0
  fi
  # RV-P5-02 nit 12: a crash between the rename and the sidecar write leaves the final file without a
  # sidecar. It is re-checked (MD5 pin and gzip -t) before the sidecar is written, so a file that does not
  # pass is never blessed; it stays in place and is replaced only by a new download that passes both checks.
  if [ -s "$dest" ] && [ ! -e "$dest.sha256" ]; then
    got="$(ds_md5 "$dest")" || got=""
    if [ "$got" = "$pin" ] && gzip -t "$dest"; then
      ds_write_sidecar "$dest" || { ds_log "ERROR: cannot write the sha256 sidecar of $dest"; return 1; }
      ds_log "$dest had no sha256 sidecar (an earlier run was interrupted after the rename); MD5 pin and gzip -t pass, sidecar written; not downloading again"
      return 0
    fi
    ds_log "$dest has no sidecar and does not pass the MD5 pin and gzip -t checks (MD5 ${got:-not computed}); it is left in place and replaced only if a new download passes both checks"
  fi
  mkdir -p "$(dirname "$dest")" || return 1
  ds_full_refuse_links "$dest" || return 2
  # A complete .part left by an earlier run (killed after curl, before the checks) is checked first;
  # resuming it would request a range past the end of the file. pre records what that check found, so the
  # HTTP 416 message never claims an MD5 mismatch when no MD5 could be computed (RV-P5-07 NIT-5).
  pre=unchecked
  if [ -s "$part" ]; then
    if got="$(ds_md5 "$part")"; then
      if [ "$got" = "$pin" ]; then pre=match; else pre=mismatch; fi
    else
      pre=failed
    fi
  fi
  if [ "$pre" = match ]; then
    ds_log "partial file already matches the pinned MD5; not downloading again"
  else
    ds_log "downloading $url (about 1.03 GB; resumable; connect timeout ${DS_FULL_CONNECT_TIMEOUT_S} s; stall bound ${DS_FULL_STALL_BYTES_PER_S} bytes/s for ${DS_FULL_STALL_TIME_S} s; no total time limit)"
    before="$(ds_bytes_of "$part")"
    rc=0
    # -q must be the first argument: curl then ignores ~/.curlrc (RV-P5-07 NIT-5).
    code="$(curl -q --fail --location --proto '=https' --proto-redir '=https' --max-filesize "$DS_FULL_MAX_BYTES" \
      --connect-timeout "$DS_FULL_CONNECT_TIMEOUT_S" --speed-limit "$DS_FULL_STALL_BYTES_PER_S" \
      --speed-time "$DS_FULL_STALL_TIME_S" --write-out '%{http_code}' \
      -A "$DS_UA" -C - -o "$part" "$url")" || rc=$?
    # Bytes received in this run, for the final log line (RV-P5-03 N5).
    after="$(ds_bytes_of "$part")"
    if ds_is_uint "$before" && ds_is_uint "$after" && [ "$after" -ge "$before" ]; then
      DS_FETCHED_BYTES=$((DS_FETCHED_BYTES + after - before))
    fi
    if [ "$rc" -ne 0 ]; then
      ds_full_recovery "$rc" "$code" "$part" "$pre"
      return "$rc"
    fi
    # curl may report a refused resume (HTTP 416) as success (RV-P5-03 unverified concern). Treat it as the
    # 416 failure: the .part is kept and the manual step is printed; it is never checked, deleted or renamed.
    if [ "$code" = "416" ]; then
      ds_full_recovery "$rc" "$code" "$part" "$pre"
      return 22
    fi
  fi
  # A link planted while curl ran is refused before the .part is read, renamed or deleted.
  ds_full_refuse_links "$dest" || return 2
  if ! got="$(ds_md5 "$part")"; then
    ds_log "ERROR: cannot compute the MD5 of $part (md5sum or md5 missing or failed). The file was KEPT (not checked, not deleted, not renamed). Fix the MD5 tool and re-run the same command; a .part that matches the pin is not downloaded again."
    return 1
  fi
  if [ "$got" != "$pin" ]; then
    # curl reported a completed transfer, so these bytes are the whole response and they are wrong;
    # resuming cannot repair them. Deleting is the documented exception (manifest full_transfer_bounds).
    rm -f "$part"
    ds_log "ERROR: MD5 mismatch after a completed transfer: got $got, pinned $pin. The corrupt file $part was DELETED (curl reported the transfer complete, so resuming cannot repair it). Re-run the same command to download it again from the start."
    return 1
  fi
  if ! gzip -t "$part"; then
    rm -f "$part"
    ds_log "ERROR: gzip -t failed although the MD5 matched. $part was DELETED; re-run to download again, and report this to dataset-engineer if it repeats."
    return 1
  fi
  mv -f "$part" "$dest" || return 1
  ds_write_sidecar "$dest" || return 1
  return 0
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
