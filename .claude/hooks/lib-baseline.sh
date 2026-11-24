#!/usr/bin/env bash
# lib-baseline.sh -- shared library, SOURCED (not executed) by
#   .claude/hooks/audit-bash-writes.sh   (Bash PreToolUse/PostToolUse audit)
#   .claude/hooks/post-write.sh          (PostToolUse Write|Edit|MultiEdit|NotebookEdit)
#   .claude/hooks/verify-tests-ran.sh    (Stop/SubagentStop; read-only marker check, R1-b)
#
# Session baseline of content hashes for every git-visible path (tracked plus untracked, non-ignored),
# the git control files that change what git shows or runs, and the test marker. The test marker is
# added explicitly (not through git ls-files), so it is recorded even when gitignored, and its entry
# also carries its mtime (ns) and size, because verify-tests-ran.sh compares mtimes. State lives in a
# private per-OS-user directory: ${TMPDIR:-/tmp}/scholarchain-bash-audit-<uid>/
#   session-<key>.baseline.v2.json   the baseline; replaced atomically (temp file + rename in one dir)
#   session-<key>.started            written when the session's first baseline is created; a missing
#                                    baseline AFTER this marker exists fails closed
#   session-<key>.lock/              mkdir lock; bounded wait (5 s); a timeout fails closed
#   session-<key>.incidents.log      inter-call changes and session-start notes for the human
# <key> = sha256(session_id | physical project root)[:32].
# Parallel Bash calls (or concurrent writes) in one session are UNSUPPORTED: the lock serializes the
# hooks, and a lock held longer than the wait fails closed. A stale lock (left by a killed hook) is
# removed by the human after confirming no tool call is running: rmdir <state dir>/session-<key>.lock
# These files can be modified by any process of the same OS user (no OS sandbox; B11 is off).

SCHOLARCHAIN_BASELINE_PY='
import hashlib, json, os, stat, subprocess, sys
G = ["git", "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false"]
MARKER = ".claude/.last-test-run"   # hashed even when gitignored (test-marker rule)

def entry(p):
    try:
        st = os.lstat(p)
    except OSError:
        return "missing", 0
    if stat.S_ISLNK(st.st_mode):
        return "link:" + hashlib.sha256(os.fsencode(os.readlink(p))).hexdigest(), 0
    if stat.S_ISDIR(st.st_mode):
        return "dir", 0
    if not stat.S_ISREG(st.st_mode):
        return "special", 0
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    if p == MARKER:
        # R1: the test gate (verify-tests-ran.sh) compares MTIMES, so a touch that leaves the bytes
        # unchanged is a forgery too. Only the marker records mtime (ns) and size; every other path
        # stays content-only, so same-size rewrites with a restored mtime are still seen.
        return "file:%o:%s:mtime_ns=%d:size=%d" % (st.st_mode & 0o111, h.hexdigest(), st.st_mtime_ns, st.st_size), st.st_size
    return "file:%o:%s" % (st.st_mode & 0o111, h.hexdigest()), st.st_size

def lsfiles(extra):
    r = subprocess.run(G + ["--literal-pathspecs", "ls-files", "-z", "-c", "-o", "--exclude-standard"] + extra, capture_output=True)
    if r.returncode:
        sys.exit(3)
    return [p for p in r.stdout.decode("utf-8", "surrogateescape").split("\0") if p]

mode = sys.argv[1]
if mode == "snap":
    gd = subprocess.run(G + ["rev-parse", "--git-dir"], capture_output=True)
    if gd.returncode:
        sys.exit(3)
    paths = {p: p for p in lsfiles([])}
    gitdir = gd.stdout.decode().strip()
    for n in ("config", "info/exclude", "info/attributes"):
        paths[".git/" + n] = os.path.join(gitdir, n)
    hd = os.path.join(gitdir, "hooks")
    for n in (sorted(os.listdir(hd)) if os.path.isdir(hd) else []):
        paths[".git/hooks/" + n] = os.path.join(hd, n)
    paths[MARKER] = MARKER
    total, out = 0, {}
    for name, p in sorted(paths.items()):
        out[name], size = entry(p)
        total += size
        if total > (1 << 30):
            sys.exit(4)
    json.dump({"v": 2, "files": out}, sys.stdout)
elif mode == "update":
    # update BASE OUT REL...: re-hash exactly the named repo-relative paths into a copy of BASE.
    # A named path that is git-visible (or the marker) gets its current entry; any other named path is
    # removed from the copy, exactly as a fresh snapshot would treat it. No other key is touched.
    base, out, rels = sys.argv[2], sys.argv[3], sys.argv[4:]
    try:
        b = json.load(open(base))
        assert b.get("v") == 2 and isinstance(b.get("files"), dict)
    except Exception:
        sys.exit(5)
    vis = set(lsfiles(["--"] + rels)) if rels else set()
    for r in rels:
        if r in vis or r == MARKER:
            b["files"][r] = entry(r)[0]
        else:
            b["files"].pop(r, None)
    with open(out, "w") as f:
        json.dump(b, f)
elif mode == "marker":
    # marker BASE (R1-b, read-only): exit 0 if the test marker entry equals the baseline entry, 1 if it
    # differs (changed since the last audited Bash call or authorized write), 5 if BASE is corrupt.
    try:
        b = json.load(open(sys.argv[2]))
        assert b.get("v") == 2 and isinstance(b.get("files"), dict)
    except Exception:
        sys.exit(5)
    sys.exit(0 if b["files"].get(MARKER) == entry(MARKER)[0] else 1)
'

# baseline_init <session_id>: sets state_dir skey base started lockdir incidents (cwd = project root)
baseline_init() {
  state_dir="${TMPDIR:-/tmp}"
  state_dir="${state_dir%/}/scholarchain-bash-audit-$(id -u)"
  { mkdir -p "$state_dir" && chmod 700 "$state_dir"; } 2>/dev/null || return 1
  skey="$(python3 -I -c 'import hashlib, sys; print(hashlib.sha256(sys.argv[1].encode()).hexdigest()[:32])' "$1|$(pwd -P)")" || return 1
  base="$state_dir/session-$skey.baseline.v2.json"
  # Returned global: sourcing hooks read this after baseline_init succeeds.
  # shellcheck disable=SC2034
  started="$state_dir/session-$skey.started"
  lockdir="$state_dir/session-$skey.lock"
  # Returned global: audit-bash-writes.sh appends to this session log.
  # shellcheck disable=SC2034
  incidents="$state_dir/session-$skey.incidents.log"
}

SCHOLARCHAIN_LOCK_WAIT_TENTHS=50   # 5 s
# R4: SCHOLARCHAIN_LOCK_HELD is this process's ownership token. baseline_unlock removes the lock
# directory only if this process acquired it (and only once), so a timed-out or repeated unlock can
# never remove a lock held by another hook.
SCHOLARCHAIN_LOCK_HELD=0
baseline_lock() {
  local i=0
  [ "$SCHOLARCHAIN_LOCK_HELD" = 1 ] && return 0
  while ! mkdir "$lockdir" 2>/dev/null; do
    i=$((i + 1))
    [ "$i" -ge "$SCHOLARCHAIN_LOCK_WAIT_TENTHS" ] && return 1
    sleep 0.1
  done
  SCHOLARCHAIN_LOCK_HELD=1
  trap 'rmdir "$lockdir" 2>/dev/null' EXIT
}
baseline_unlock() {
  [ "$SCHOLARCHAIN_LOCK_HELD" = 1 ] || return 0
  rmdir "$lockdir" 2>/dev/null; SCHOLARCHAIN_LOCK_HELD=0; trap - EXIT
}

# baseline_replace <file>: atomic replace of the baseline (caller holds the lock)
baseline_replace() {
  cp "$1" "$base.tmp.$$" && mv -f "$base.tmp.$$" "$base" || { rm -f "$base.tmp.$$"; return 1; }
}

baseline_snapshot() { python3 -I -c "$SCHOLARCHAIN_BASELINE_PY" snap; }

# baseline_marker_check: read-only; 0 = marker matches the baseline, 1 = changed, 5 = corrupt baseline
baseline_marker_check() { python3 -I -c "$SCHOLARCHAIN_BASELINE_PY" marker "$base"; }

# baseline_update <rel>...: re-hash exactly these paths into the baseline, atomically (caller holds lock)
baseline_update() {
  python3 -I -c "$SCHOLARCHAIN_BASELINE_PY" update "$base" "$base.tmp.$$" "$@" && mv -f "$base.tmp.$$" "$base" \
    || { rm -f "$base.tmp.$$"; return 1; }
}

baseline_lock_msg() {
  echo "the session baseline lock $lockdir was held for more than $((SCHOLARCHAIN_LOCK_WAIT_TENTHS / 10)) s. Parallel Bash calls in one session are unsupported. If no tool call is running, the lock is stale: the human removes it with 'rmdir $lockdir' after reviewing git status."
}
