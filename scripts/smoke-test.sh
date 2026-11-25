#!/usr/bin/env bash
# shellcheck disable=SC2154 # S_* are assigned by parse_kv through printf -v (lib/common.sh)
# =====================================================================================================
# scripts/smoke-test.sh -- bounded smoke test of the deployed ScholarChain REST services.
# Owner: infra-engineer (IE-P13-01). Read-only against the service: one query plus its replay.
#
# USAGE   scripts/smoke-test.sh <environment>
#
# Configuration (no endpoint is hardcoded): the committed, unmodified
#   infra/terraform/environments/<environment>/smoke.conf      (PROPOSED contract, see SMOKE_KEYS)
#     config_version=scholarchain.smoke-config.v1
#     environment=<environment>
#     query_base_url=http[s]://<host>[:<port>]        query-jobs service (POST /v1/query)
#     evidence_base_url=http[s]://<host>[:<port>]     evidence-explain service (GET /v1/evidence/{id})
#     query_body_path=<committed JSON file>           the bounded researcher query (mode "sync")
#     connect_timeout_seconds=<1..30>   request_timeout_seconds=<1..120>   max_total_seconds=<10..600>
#   The base URLs must be reachable from where this runs (the runtime stack's API load balancer,
#   restricted to the human's ingress CIDRs, or a reviewed port forward); which one is a human decision.
#   TRANSPORT (CR-P13-01 m4): a non-loopback host must use https://. http:// is accepted without
#   condition only for localhost and 127.0.0.0/8. http:// to any other host is refused unless
#   SCHOLARCHAIN_SMOKE_ALLOW_PLAINTEXT=1 is set explicitly; then a prominent WARNING block is printed
#   (never the key), and the operator should rotate the API key after the run.
#   API key: environment variable SCHOLARCHAIN_SMOKE_API_KEY, set by the operator from Secrets Manager
#   by their own means; 16..512 characters of [A-Za-z0-9._~+/=-], as the server requires. It is written only to a 0600 temporary header file passed to curl with
#   `-H @file` (never on a command line) and is never printed.
#
# Checks (each one HTTP request, bounded by --connect-timeout/--max-time and the overall deadline):
#   health-query / ready-query / health-evidence / ready-evidence   GET /healthz, /readyz -> 200
#   query          POST /v1/query (Idempotency-Key) -> 200, queryId, >= 1 claim, every claim has
#                  >= 1 evidence ID
#   evidence       GET /v1/evidence/<first evidence ID> -> 200, evidenceId equals the requested ID
#   replay         same body and Idempotency-Key -> 200, same queryId and same answer
#   unauthorized   POST /v1/query without Authorization -> 401 (expected error path)
# Prints a result table; response bodies stay in a temporary directory removed on exit.
#
# MARKERS  [smoke-test] STEP / CHECK ok / SMOKE <check> PASS|FAIL <detail> / TABLE / RESULT
#          SMOKE-OK|SMOKE-FAILED; FAIL on stderr.
# EXIT     0 every check passed, 2 usage, 3 refused (config, key), 6 at least one check failed
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=smoke-test
# shellcheck source=scripts/lib/common.sh disable=SC1091
. "$(dirname "$0")/lib/common.sh"
readonly SMOKE_SCHEMA=scholarchain.smoke-config.v1
readonly SMOKE_KEYS="config_version environment query_base_url evidence_base_url query_body_path \
connect_timeout_seconds request_timeout_seconds max_total_seconds"
readonly MAX_BODY_BYTES=65536

(($# == 1)) || die "$EX_USAGE" "usage: scripts/smoke-test.sh <environment>"
ARG_ENV=$1
is_env_name "$ARG_ENV" || die "$EX_USAGE" "environment '$ARG_ENV' is not a short lowercase name"
need_tools git curl jq awk shasum
step "start env=$ARG_ENV at=$(now_utc)"
require_repo_root
# no-local: the smoke test needs no account ID, admin ARN or API ingress value, so it does not read
# the human-local env.local.conf (CR-P13-02 nit); env.conf is still required committed and unexpired.
load_env_conf "$ARG_ENV" no-local
CONF="infra/terraform/environments/$ARG_ENV/smoke.conf"
[[ -f $CONF ]] || die "$EX_REFUSED" "$CONF does not exist: the smoke-test endpoint contract is not committed yet (see the header of this script)"
require_tracked "smoke config" "$CONF"
parse_kv "$CONF" "$SMOKE_KEYS" S_ ":"
[[ $S_config_version == "$SMOKE_SCHEMA" ]] || die "$EX_USAGE" "$CONF: config_version must be $SMOKE_SCHEMA"
[[ $S_environment == "$ARG_ENV" ]] || die "$EX_REFUSED" "$CONF: environment '$S_environment' differs from '$ARG_ENV'"
PLAINTEXT_HOSTS=""
for k in query_base_url evidence_base_url; do
  v="S_$k"
  [[ ${!v} =~ ^https?://[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:[0-9]{1,5})?$ ]] ||
    die "$EX_USAGE" "$CONF: $k must be http[s]://<host>[:<port>] with no path, query or credentials"
  if [[ ${!v} == http://* ]]; then
    h=${!v#http://}
    h=${h%%:*}
    # Loopback (a reviewed port forward on this machine) may use http: the key never leaves the host.
    if [[ $h != localhost && ! $h =~ ^127\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; then
      [[ ${SCHOLARCHAIN_SMOKE_ALLOW_PLAINTEXT:-} == 1 ]] ||
        die "$EX_REFUSED" "$CONF: $k uses http:// to non-loopback host $h; the API key would be sent in cleartext. Use https://, or set SCHOLARCHAIN_SMOKE_ALLOW_PLAINTEXT=1 explicitly to accept that risk"
      PLAINTEXT_HOSTS="$PLAINTEXT_HOSTS $h"
    fi
  fi
done
if [[ -n $PLAINTEXT_HOSTS ]]; then
  say "WARNING ================================================================================="
  say "WARNING PLAINTEXT: SCHOLARCHAIN_SMOKE_ALLOW_PLAINTEXT=1 -- the Bearer API key will be sent"
  say "WARNING unencrypted over http:// to:$PLAINTEXT_HOSTS"
  say "WARNING Anyone on the network path can read it. ROTATE THE API KEY in Secrets Manager after"
  say "WARNING this smoke test. (The key value is never printed by this script.)"
  say "WARNING ================================================================================="
fi
need_int_range connect_timeout_seconds "$S_connect_timeout_seconds" 1 30
need_int_range request_timeout_seconds "$S_request_timeout_seconds" 1 120
need_int_range max_total_seconds "$S_max_total_seconds" 10 600
is_repo_path "$S_query_body_path" || die "$EX_USAGE" "$CONF: query_body_path is not a plain repository path"
require_tracked "query body" "$S_query_body_path"
(($(file_bytes "$S_query_body_path") <= MAX_BODY_BYTES)) || die "$EX_REFUSED" "query body exceeds $MAX_BODY_BYTES bytes"
jq -e 'type == "object" and (.question | type) == "string" and .mode == "sync"' "$S_query_body_path" >/dev/null ||
  die "$EX_USAGE" "$S_query_body_path must be a JSON object with a string question and mode \"sync\""
ok "smoke config $CONF (query=$S_query_base_url evidence=$S_evidence_base_url)"
[[ -n ${SCHOLARCHAIN_SMOKE_API_KEY:-} ]] || die "$EX_REFUSED" "SCHOLARCHAIN_SMOKE_API_KEY is not set (the key is never read from the repository)"
# Length 16..512 matches the server's API-key bounds (modules/services Auth).
[[ $SCHOLARCHAIN_SMOKE_API_KEY =~ ^[A-Za-z0-9._~+/=-]{16,512}$ ]] || die "$EX_REFUSED" "SCHOLARCHAIN_SMOKE_API_KEY has unexpected characters or a length outside 16..512 (value not printed)"

TMP=$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-smoke.XXXXXXXX")
# shellcheck disable=SC2329 # invoked by the EXIT trap below
cleanup() {
  find "$TMP" -type f -exec rm -f {} + 2>/dev/null || true
  rmdir "$TMP" 2>/dev/null || true
}
trap cleanup EXIT
(
  umask 077
  printf 'Authorization: Bearer %s\n' "$SCHOLARCHAIN_SMOKE_API_KEY" >"$TMP/auth.h"
)
unset SCHOLARCHAIN_SMOKE_API_KEY
printf 'Content-Type: application/json\n' >"$TMP/json.h"
IDEM="smoke-${HEAD_SHA:0:12}-$(date -u +%Y%m%dT%H%M%SZ)-$$"
printf 'Idempotency-Key: %s\n' "$IDEM" >"$TMP/idem.h"
START=$SECONDS

RESULTS=""
FAILED=0
result() { # result <check> <PASS|FAIL> <detail>
  say "SMOKE $1 $2 $3"
  RESULTS="$RESULTS$1|$2|$3"$'\n'
  if [[ $2 == FAIL ]]; then FAILED=$((FAILED + 1)); fi
}
# http <out file> <method> <url> [curl header/data options...]: prints the HTTP status ("000" on a
# transport error or timeout). Never follows redirects; ignores proxy variables; http/https only.
http() {
  local out=$1 method=$2 url=$3 status
  shift 3
  if ((SECONDS - START >= S_max_total_seconds)); then
    say "NOTE overall deadline of $S_max_total_seconds s reached before $method $url" >&2
    printf '000'
    return 0
  fi
  status=$(curl -sS --proto '=http,https' --noproxy '*' --max-redirs 0 \
    --connect-timeout "$S_connect_timeout_seconds" --max-time "$S_request_timeout_seconds" \
    --max-filesize 4194304 -o "$out" -w '%{http_code}' -X "$method" "$@" "$url" 2>"$out.err") || true
  [[ $status =~ ^[0-9]{3}$ ]] || status=000
  # Transport evidence (curl's own message; it never contains header values) on stderr.
  if [[ $status == 000 && -s $out.err ]]; then head -n 3 "$out.err" >&2 || true; fi
  printf '%s' "$status"
}
json_ok() { jq -e "$2" "$1" >/dev/null 2>&1; }

step "health and readiness"
for svc in query evidence; do
  v="S_${svc}_base_url"
  base=${!v}
  for p in healthz readyz; do
    name="$([[ $p == healthz ]] && printf health || printf ready)-$svc"
    st=$(http "$TMP/$name.json" GET "$base/$p")
    if [[ $st == 200 ]]; then result "$name" PASS "GET /$p status=200"; else result "$name" FAIL "GET /$p status=$st"; fi
  done
done

step "bounded query"
QUERY_ID=""
EVIDENCE_ID=""
st=$(http "$TMP/query.json" POST "$S_query_base_url/v1/query" -H "@$TMP/auth.h" -H "@$TMP/json.h" -H "@$TMP/idem.h" \
  --data-binary "@$S_query_body_path")
if [[ $st == 200 ]] && json_ok "$TMP/query.json" '(.queryId | type) == "string" and (.answer.claims | type) == "array"
    and (.answer.claims | length) >= 1 and all(.answer.claims[]; (.evidenceIds | type) == "array" and (.evidenceIds | length) >= 1)'; then
  QUERY_ID=$(jq -r .queryId "$TMP/query.json")
  EVIDENCE_ID=$(jq -r '.answer.claims[0].evidenceIds[0]' "$TMP/query.json")
  CLAIMS=$(jq -r '.answer.claims | length' "$TMP/query.json")
  result query PASS "status=200 queryId=$QUERY_ID claims=$CLAIMS"
else
  result query FAIL "status=$st (needs 200, queryId and >= 1 claim with evidence IDs)"
fi

step "evidence fetch"
if [[ $EVIDENCE_ID =~ ^[A-Za-z0-9._:-]{1,256}$ ]]; then
  st=$(http "$TMP/evidence.json" GET "$S_evidence_base_url/v1/evidence/$EVIDENCE_ID" -H "@$TMP/auth.h")
  if [[ $st == 200 ]] && [[ $(jq -r '.evidenceId // ""' "$TMP/evidence.json" 2>/dev/null) == "$EVIDENCE_ID" ]]; then
    result evidence PASS "status=200 evidenceId=$EVIDENCE_ID"
  else
    result evidence FAIL "status=$st for evidenceId=$EVIDENCE_ID (needs 200 and the same evidenceId)"
  fi
else
  result evidence FAIL "no usable evidence ID from the query"
fi

step "idempotency replay"
if [[ -n $QUERY_ID ]]; then
  st=$(http "$TMP/replay.json" POST "$S_query_base_url/v1/query" -H "@$TMP/auth.h" -H "@$TMP/json.h" -H "@$TMP/idem.h" \
    --data-binary "@$S_query_body_path")
  if [[ $st == 200 ]] && jq -e --slurpfile a "$TMP/query.json" '.queryId == $a[0].queryId and .answer == $a[0].answer' \
    "$TMP/replay.json" >/dev/null 2>&1; then
    result replay PASS "status=200 same queryId and answer"
  else
    result replay FAIL "status=$st (needs 200, the same queryId and the same answer)"
  fi
else
  result replay FAIL "skipped: the query check did not return a queryId"
fi

step "expected error path"
st=$(http "$TMP/unauthorized.json" POST "$S_query_base_url/v1/query" -H "@$TMP/json.h" --data-binary "@$S_query_body_path")
if [[ $st == 401 ]]; then result unauthorized PASS "status=401 without Authorization"; else result unauthorized FAIL "status=$st (needs 401)"; fi

step "summary"
say "TABLE check            result detail"
printf '%s' "$RESULTS" | awk -F'|' -v s="$SCRIPT_NAME" 'NF >= 2 { printf "[%s] TABLE %-16s %-6s %s\n", s, $1, $2, $3 }'
say "elapsed_seconds=$((SECONDS - START)) max_total_seconds=$S_max_total_seconds idempotency_key=$IDEM"
if ((FAILED > 0)); then
  say "RESULT SMOKE-FAILED failed_checks=$FAILED"
  die "$EX_VERIFY" "$FAILED smoke check(s) failed (SMOKE ... FAIL lines above)"
fi
say "RESULT SMOKE-OK"
