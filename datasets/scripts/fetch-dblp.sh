#!/usr/bin/env bash
# fetch-dblp.sh -- bounded retrieval for the DBLP XML seed dataset. Owner: dataset-engineer.
#
#   --dry-run  print the pinned release, URLs, sizes and target paths (no network, no writes)
#   --verify   offline: validate the manifest entry and committed sample; check any local
#              release download against the pinned upstream MD5 and gzip integrity
#   --sample   bounded: dblp.dtd (<=100 KB, https://dblp.org/xml/dblp.dtd) and the release .md5 file
#              (<=1 KB, drops.dagstuhl.de) into datasets/downloads/dblp/sample/. Per-record dblp XML
#              (https://dblp.org/rec/<key>.xml) is NOT requested (DS-P5-03): dblp.org robots.txt says
#              "Disallow: /rec/xml", "Disallow: /*.xml" and "Disallow: /*.json" (read 2026-11-05). dblp
#              records come only from the pinned release (--full) or the committed sample.
#   --full     HUMAN ONLY: the pinned dated release (about 1.03 GB) into datasets/downloads/dblp/full/
#              (git-ignored); requires SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=<datasets.dblp.snapshot.id>.
#              Resumable. Connect timeout 30 s; a transfer below 1024 bytes/s for 120 s is a stall
#              (curl exit 28); no total time limit. A partial file that already matches the pinned
#              MD5 is not re-requested. On any curl failure, and on HTTP 416 even when curl exits 0,
#              the .part file is KEPT and a RECOVERY line names it and the shell-escaped manual step
#              (HTTP 416 / cannot resume: rm the .part by hand). Only a transfer that curl reports
#              complete but that fails the MD5 or gzip check is deleted; if the MD5 cannot be computed
#              the .part is kept. A final file left without its sidecar by an interrupted run is
#              re-checked (MD5 pin, gzip -t) and gets its sidecar without a new download.
#              Symbolic links are refused (exit 2, before any network access, and again before the
#              transfer and the rename): datasets/downloads itself, every path component below it, the
#              release file, its .part, .sha256 and .sha256.tmp. A link is never followed, deleted or
#              written through (RV-P5-07 MINOR-1). curl runs with -q first, so ~/.curlrc is ignored.
#
# All pinned values (URLs, snapshot id, upstream MD5) are read from datasets/dataset-manifest.yaml.
set -euo pipefail
DS_NAME=fetch-dblp
DS_DATASET=dblp
# shellcheck source=datasets/scripts/lib-datasets.sh
. "$(cd "$(dirname "$0")" && pwd)/lib-datasets.sh"
ds_parse_args "$@"
ds_need python3 curl awk

SNAP_ID="$(ds_manifest_get datasets.dblp.snapshot.id)"
URL="$(ds_manifest_get datasets.dblp.official_source.download_url)"
MD5_URL="$(ds_manifest_get datasets.dblp.official_source.checksum_url)"
DTD_URL="$(ds_manifest_get datasets.dblp.official_source.dtd_url)"
PIN_MD5="$(ds_manifest_get datasets.dblp.integrity.upstream_checksum.value)"
FILE="$(basename "$URL")"

case "$DS_MODE" in
  dry-run)
    ds_log "snapshot:        $SNAP_ID"
    ds_log "release file:    $URL (expected about 1.03 GB, see manifest size.expected)"
    ds_log "upstream md5:    $PIN_MD5 (from $MD5_URL)"
    ds_log "dtd:             $DTD_URL"
    ds_log "--sample writes: ${DS_OUT#"$DS_ROOT"/}/sample/{dblp.dtd,$FILE.md5}"
    ds_log "--full writes:   ${DS_OUT#"$DS_ROOT"/}/full/$FILE (human only)"
    ds_log "--full bounds:   connect timeout ${DS_FULL_CONNECT_TIMEOUT_S} s; stall below ${DS_FULL_STALL_BYTES_PER_S} bytes/s for ${DS_FULL_STALL_TIME_S} s; cap ${DS_FULL_MAX_BYTES} bytes; no total time limit"
    ds_log "per-record dblp XML is never requested (dblp.org robots.txt disallows /rec/xml and /*.xml)"
    ;;

  verify)
    ds_verify_committed dblp
    if [ -s "$DS_OUT/sample/$FILE.md5" ]; then
      got="$(awk '{print $1}' "$DS_OUT/sample/$FILE.md5")"
      [ "$got" = "$PIN_MD5" ] || ds_die "upstream .md5 file ($got) differs from the pinned manifest value ($PIN_MD5)"
      ds_log "upstream .md5 file matches the pinned value"
    fi
    if [ -e "$DS_OUT/full/$FILE" ]; then
      ds_need gzip
      ds_need_md5
      ds_is_current "$DS_OUT/full/$FILE" || ds_die "$FILE does not match its .sha256 sidecar"
      got="$(ds_md5 "$DS_OUT/full/$FILE")" || ds_die "cannot compute the MD5 of $FILE (md5sum or md5 failed)"
      [ "$got" = "$PIN_MD5" ] || ds_die "$FILE does not match the pinned upstream MD5"
      gzip -t "$DS_OUT/full/$FILE" || ds_die "$FILE fails gzip -t"
      ds_log "full release verified: md5, sha256 sidecar, gzip -t"
    fi
    ;;

  sample)
    ds_fetch "$DTD_URL" "$DS_OUT/sample/dblp.dtd" 100000 || true
    if ds_fetch "$MD5_URL" "$DS_OUT/sample/$FILE.md5" 1024; then
      got="$(awk '{print $1}' "$DS_OUT/sample/$FILE.md5")"
      if [ "$got" != "$PIN_MD5" ]; then
        ds_log "WARNING: upstream .md5 ($got) differs from the pinned manifest value ($PIN_MD5)"
        DS_FAILURES=$((DS_FAILURES + 1))
      fi
    fi
    # No per-record request: dblp.org robots.txt disallows /rec/xml, /*.xml and /*.json (DS-P5-03).
    ds_log "per-record dblp XML not requested (robots.txt); seed records stay the committed sample"
    ;;

  full)
    ds_require_full_confirmation "$SNAP_ID"
    ds_need gzip
    ds_need_md5
    dest="$DS_OUT/full/$FILE"
    # Bounded, resumable transfer plus MD5 / gzip -t / sidecar (lib-datasets.sh ds_full_download).
    # Every failure prints a RECOVERY line naming the .part path; see RV-P5-02 findings 1 and 2.
    rc=0
    ds_full_download "$URL" "$dest" "$PIN_MD5" || rc=$?
    [ "$rc" -eq 0 ] || ds_die "the full download did not complete (exit $rc); follow the RECOVERY or ERROR line above" "$rc"
    ds_log "size bytes: $(wc -c < "$dest" | tr -d ' ')"
    ds_log "sha256:     $(cat "$dest.sha256")"
    ds_log "Record these as size.observed.bytes and integrity.our_sha256 in the manifest (dataset-engineer work order)."
    ;;
esac
ds_finish
