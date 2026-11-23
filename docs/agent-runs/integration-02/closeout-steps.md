# Integration Round 02 — exact human approval and Git steps

Run these only after approving the local scope in `human-gate.md` and the final PASS
in `test-report.md`. The acceptance file is a proposal until then. Codex has not
staged, committed or pushed anything. This checkpoint is not deployment approval.

## Approval to give

> I accept Integration Round 02 as a local checkpoint, including the recorded
> deferrals and residual risks. I accept the independent local reviews and archived
> Codex-observed test evidence without claiming my own full test run or line-by-line
> source audit. I approve the implementation commit, the two-file human-acceptance
> commit and a normal push of both to origin/main. No AWS, paid calls, force push,
> tag or protected guidance/hook change is authorized.

## 1. Review, verify and commit the implementation checkpoint

Paste this into your regular Terminal after approving. It stops on unexpected
branch/base/remote/index state or changed reviewed implementation bytes. It does
not discard work, merge, rebase, force-push or rerun tests. A changed remote base
requires reviewing that difference rather than bypassing the check.

```bash
bash <<'BASH'
set -euo pipefail
cd /Users/taufeeqpatel/Desktop/ScholarChain
test "$(git rev-parse --show-toplevel)" = /Users/taufeeqpatel/Desktop/ScholarChain
test "$(git branch --show-current)" = main
test "$(git rev-parse HEAD)" = bdbf5df02133a0cb5f7493fcd1fd08b98469db60
test "$(git remote get-url origin)" = https://github.com/Tpate33/ScholarChain-Project.git
test -z "$(git diff --cached --name-only)"
git fetch origin
test "$(git rev-parse origin/main)" = bdbf5df02133a0cb5f7493fcd1fd08b98469db60
python3 - <<'PY'
import hashlib, json
from pathlib import Path
manifest = json.loads(Path('docs/agent-runs/integration-02/reviewed-files.json').read_text())
for name, expected in manifest['sha256'].items():
    if hashlib.sha256(Path(name).read_bytes()).hexdigest() != expected:
        raise SystemExit('STOP: reviewed source changed: ' + name)
for name, expected in manifest['executable'].items():
    if bool(Path(name).stat().st_mode & 0o111) != expected:
        raise SystemExit('STOP: executable mode changed: ' + name)
print('Reviewed implementation bytes and executable modes match.')
PY
git status --short
git add --pathspec-from-file=docs/agent-runs/integration-02/commit-paths.txt
diff -u docs/agent-runs/integration-02/commit-paths.txt <(git diff --cached --name-only)
git diff --cached --check
git diff --cached --stat
git commit -m "Integration round 02: verified local DBLP query, launchers and lifecycle cleanup"
git rev-parse HEAD
BASH
```

`commit-paths.txt` contains every intended implementation/evidence/documentation
file, including the path list itself. It excludes `human-gate.md` and `commit.txt`,
which belong in the following human-authored closeout commit. Do not use `git add .`.
The exact paths and their modified/new status are in `file-inventory.md`.

## 2. Record your acceptance against the actual implementation SHA

Run only after step 1 succeeds and if you accept the scope described above. This
records your Terminal action, not an agent's signature or a claim you reran tests.

```bash
bash <<'BASH'
set -euo pipefail
cd /Users/taufeeqpatel/Desktop/ScholarChain
test "$(git branch --show-current)" = main
test -z "$(git diff --cached --name-only)"
test "$(git rev-parse HEAD^)" = bdbf5df02133a0cb5f7493fcd1fd08b98469db60
test "$(git log -1 --format=%s)" = "Integration round 02: verified local DBLP query, launchers and lifecycle cleanup"
python3 - <<'PY'
import datetime, subprocess
from pathlib import Path
from zoneinfo import ZoneInfo
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
p = Path('docs/agent-runs/integration-02/human-gate.md')
s = p.read_text()
replacements = {
    'Decision: PENDING': 'Decision: ACCEPT LOCAL CHECKPOINT',
    'Accepted implementation commit: PENDING': 'Accepted implementation commit: ' + sha,
    'Acceptance date (America/Chicago): PENDING': 'Acceptance date (America/Chicago): ' + datetime.datetime.now(ZoneInfo('America/Chicago')).isoformat(timespec='seconds'),
}
for old, new in replacements.items():
    if s.count(old) != 1:
        raise SystemExit('STOP: acceptance draft changed; inspect it before recording approval.')
    s = s.replace(old, new)
p.write_text(s)
Path('docs/agent-runs/integration-02/commit.txt').write_text(sha + '\n')
PY
git add -- docs/agent-runs/integration-02/human-gate.md docs/agent-runs/integration-02/commit.txt
diff -u <(printf '%s\n' docs/agent-runs/integration-02/commit.txt docs/agent-runs/integration-02/human-gate.md) <(git diff --cached --name-only)
git diff --cached --check
git diff --cached
git commit -m "Record integration round 02 local checkpoint acceptance"
git --no-pager log -2 --oneline
BASH
```

## 3. Push the approved checkpoint and verify synchronization

```bash
bash <<'BASH'
set -euo pipefail
cd /Users/taufeeqpatel/Desktop/ScholarChain
test "$(git branch --show-current)" = main
test "$(git remote get-url origin)" = https://github.com/Tpate33/ScholarChain-Project.git
test "$(git log -1 --format=%s)" = "Record integration round 02 local checkpoint acceptance"
git push origin main
git fetch origin
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)"
git status -sb
git status --short
BASH
```

Expected final state: main matches origin/main; only `?? AGENTS.md` and `?? skills/`
remain intentionally untracked. If other files appear, inspect them; do not delete,
reset, amend or force-push to manufacture a clean state. No final submission tag is
created by this checkpoint sequence. No AWS operation is part of these commands.
