#!/usr/bin/env bash
# fetch-dblp.sh -- bounded retrieval for the DBLP XML seed dataset. Owner: dataset-engineer.
#
#   --dry-run  print the pinned release, URLs, sizes and target paths (no network, no writes)
#   --verify   offline: validate the manifest entry and committed sample; check any local
#              release download against the pinned upstream MD5 and gzip integrity
#   --sample   bounded: dblp.dtd (<=100 KB), the release .md5 file (<=1 KB) and the seed records
#              https://dblp.org/rec/<key>.xml (<=200 KB each) into datasets/downloads/dblp/sample/
#   --full     HUMAN ONLY: the pinned dated release (about 1.03 GB) into datasets/downloads/dblp/full/
#              (git-ignored); requires SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=<datasets.dblp.snapshot.id>.
#              Resumable. A partial file that already matches the pinned MD5 is not re-requested. A
#              file that fails the MD5 or gzip check is deleted, so the next run starts clean.
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
    ds_log "--sample writes: ${DS_OUT#"$DS_ROOT"/}/sample/{dblp.dtd,$FILE.md5,rec/*.xml}"
    ds_log "--full writes:   ${DS_OUT#"$DS_ROOT"/}/full/$FILE (human only)"
    ds_log "seed records:"
    ds_seeds dblp rec | sed 's/^/  /' >&2
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
      ds_is_current "$DS_OUT/full/$FILE" || ds_die "$FILE does not match its .sha256 sidecar"
      [ "$(ds_md5 "$DS_OUT/full/$FILE")" = "$PIN_MD5" ] || ds_die "$FILE does not match the pinned upstream MD5"
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
    for key in $(ds_seeds dblp rec); do
      dest="$DS_OUT/sample/rec/$(printf '%s' "$key" | tr '/' '_').xml"
      if ds_fetch "https://dblp.org/rec/$key.xml" "$dest" 200000 60 "application/xml"; then
        if ! python3 -I "$DS_PY" check-xml "$dest"; then
          rm -f "$dest" "$dest.sha256"
          ds_log "dblp returned a non-XML page for $key (bot protection?). Open https://dblp.org/rec/$key.xml in a browser instead."
          DS_FAILURES=$((DS_FAILURES + 1))
        fi
      fi
    done
    ;;

  full)
    ds_require_full_confirmation "$SNAP_ID"
    ds_need gzip
    dest="$DS_OUT/full/$FILE"
    if ds_is_current "$dest"; then
      ds_log "already present and verified: ${dest#"$DS_ROOT"/}"
    else
      mkdir -p "$DS_OUT/full"
      # A complete .part left by an interrupted earlier run (killed after curl, before the checks)
      # is checked first; resuming it would request a range past the end of the file.
      if [ -s "$dest.part" ] && [ "$(ds_md5 "$dest.part")" = "$PIN_MD5" ]; then
        ds_log "partial file already matches the pinned MD5; not downloading again"
      else
        ds_log "downloading $URL (about 1.03 GB; resumable)"
        curl --fail --location --proto '=https' --proto-redir '=https' --max-filesize 2000000000 \
          -A "$DS_UA" -C - -o "$dest.part" "$URL"
      fi
      got="$(ds_md5 "$dest.part")"
      if [ "$got" != "$PIN_MD5" ]; then
        rm -f "$dest.part"
        ds_die "MD5 mismatch: got $got, pinned $PIN_MD5. The corrupt file $dest.part was deleted; re-run the same command to download it again from the start."
      fi
      if ! gzip -t "$dest.part"; then
        rm -f "$dest.part"
        ds_die "gzip -t failed although the MD5 matched. $dest.part was deleted; re-run to download again, and report this to dataset-engineer if it repeats."
      fi
      mv -f "$dest.part" "$dest"
      ds_sha256 "$dest" > "$dest.sha256"
    fi
    ds_log "size bytes: $(wc -c < "$dest" | tr -d ' ')"
    ds_log "sha256:     $(cat "$dest.sha256")"
    ds_log "Record these as size.observed.bytes and integrity.our_sha256 in the manifest (dataset-engineer work order)."
    ;;
esac
ds_finish
