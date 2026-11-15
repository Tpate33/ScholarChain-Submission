#!/usr/bin/env bash
# guard-dangerous.sh -- PreToolUse hook for Bash | Read | Grep | Glob | mcp__aws-api__*.
#
# Bash: DEFAULT DENY. A command runs only if (1) it passes a byte-level character gate that admits no
# shell syntax (so bash, sh, and zsh all split it on single spaces and expand nothing), and (2) its
# space-separated tokens match an ACTIVE row of .claude/hooks/bash-allowlist.tsv for the acting
# identity (agent_type, or ORCHESTRATOR when absent; unknown identities and code-reviewer get nothing).
# Matched argv then passes token-level tripwires (terraform apply/destroy/state, git push/force, AWS
# destructive verbs, EMR scale/termination, cleanup.sh, test-marker forgery, secrets, dangerous
# programs), path resolution (no escape from the repo through symlinks), and, for rows that execute a
# repository script, a provenance check: the script must be tracked and unmodified versus HEAD.
# Factory hook scripts (PROV=hook) are exempt from git provenance: they already run with full
# authority as hooks on every tool call, so a commit check on the Bash invocation adds no protection;
# their integrity rests on enforce-ownership.sh (factory-only writes) and human ratification.
# Precondition (NEW-2, a narrowing): every sbt row denies while the repository root has no regular
# .gitignore file (owner build-engineer, decision B6), naming build-engineer and B6.
# Read/Grep/Glob: secret-read check on the raw fields and their resolved (symlink-free) paths, and the
# held-out gold-label fence (G9-c, FE-P5-03): no Read of gold/**/heldout*/er-gold-labels*, no Grep under
# gold/, no repository-wide Grep whose glob names gold/heldout/label. Read/Grep: the held-out evaluation
# output sbt may keep under target/ is fenced (R6, rule heldout-eval-output-read, FE-P5-04).
# Bash: git diff/show may not name a gold/ path other than the exact non-label files, and a content-printing
# git diff/show whose scope includes gold/ is refused when git lists a held-out label file in that scope.
# Bash: sbt goldSetEvalHeldout (D15(c), FE-P5-04) and sbt localPipelineHeldout (decisions-21, FE-P5-07) are human-only:
# refused for every identity before allowlist matching (rules heldout-eval-human-only and heldout-pipeline-human-only),
# and a tripwire so --validate-allowlist fails any active row naming either.
# Bash: every sbt command with a clean token is refused while the held-out pipeline output exists (D10(ii), rule heldout-run-protected).
# mcp__aws-api__*: destructive-verb and secret-dump token check on cli_command.
#
# Never emits "allow": the result is a deny (exit 2, actionable message on stderr), an "ask"
# (permissionDecision JSON on stdout), or no decision (exit 0), so settings.json ask rules still apply.
# Fails closed: python3 missing, unreadable allowlist, bad payload, or any internal error -> deny.
# Does not source lib-bash-analysis.sh (no shell parsing anywhere).
#   --validate-allowlist   check the data file and print PASS/FAIL lines (used by validate-agent-files.sh)
set -u
HOOK_DIR="$(cd "$(dirname "$0")" && pwd)"
failclosed() {
  printf 'BLOCKED [guard-unavailable]: %s\nIdentity: unknown. Allowed shapes for you: none until this is fixed.\nDo instead: %s\n' "$1" "$2" >&2
  exit 2
}
command -v python3 >/dev/null 2>&1 || failclosed "python3 is required to check tool calls." \
  "The human installs python3 (macOS: xcode-select --install). Every checked call is refused until then."
IFS= read -r -d '' PY <<'PYEOF' || true
import json, os, re, subprocess, sys, time
HOOK_DIR, MODE = sys.argv[1], sys.argv[2]
TABLE = os.path.join(HOOK_DIR, 'bash-allowlist.tsv')
AGENTS = ('factory-engineer build-engineer domain-engineer dataset-engineer hadoop-engineer entity-resolution-engineer '
          'spark-engineer storage-engineer rag-engineer service-engineer test-engineer code-reviewer infra-engineer '
          'deploy-engineer experiment-engineer documentation-engineer incident-responder dependency-updater').split()
TAIL = ('Nothing was executed. If this is genuinely required, return STATUS: BLOCKED-ON with the exact command as '
        'evidence (a new Bash shape: BLOCKED-ON factory-engineer); see docs/agents.md, Escalation rules.')
DO_GATE = ('Run one allowed command per call with plain arguments. Inspect files with Read/Grep/Glob (not cat, grep, '
           'head, ls); change files you own with Write/Edit (not redirection, sed -i, tee, or interpreters).')
END = r'([^a-z0-9_-]|$)'
SECRET_TEXT = re.compile(r'(^|[ /=<>(:@])\.env([._-][a-z0-9_.-]*|rc)?' + END + r'|(^|[ /=:@])\.?secrets?/|(^|[ /=:@~])\.ssh('
    + END + r'|/)|(^|[ /=:@~])\.gnupg(' + END + r'|/)|(id_rsa|id_ed25519|id_ecdsa|id_dsa)|\.(pem|key|p12|pfx|jks|keystore)'
    + END + r'|\.aws/(credentials|config|sso|cli)|\.tfstate|(^|[ /])\.netrc|\.docker/config\.json|(^|[ /])credentials(\.json)?' + END)
SECRET_PATH = re.compile(r'(^|/)(\.env([._-][^/]*|rc)?|\.ssh|\.gnupg|\.secrets?|secrets?|\.netrc|credentials(\.json)?'
    r'|id_(rsa|ed25519|ecdsa|dsa)[^/]*|[^/]*\.(pem|key|p12|pfx|jks|keystore)|[^/]*\.tfstate[^/]*)(/|$)'
    r'|(^|/)\.aws/(credentials|config|sso|cli)(/|$)|(^|/)\.docker/config\.json$', re.I)
SEG_BAD = re.compile(r'(^|/)(\.\.|\.git)(/|$)', re.I)
# G9-c (FE-P5-03): held-out human gold labels reach no agent tool; only the held-out evaluation, which the
# human runs in a Terminal (sbt goldSetEvalHeldout, D15(c)), reads them. Patterns apply to lower-cased repo-relative paths.
HELDOUT_LABELS = re.compile(r'^gold/(.+/)?heldout[^/]*/(.+/)?er-gold-labels[^/]*$')
GOLD_OK = re.compile(r'^gold/(.+/)?(er-gold-membership\.jsonl(\.sha256)?|er-blind-label-view\.jsonl|er-review-queue\.jsonl|er-config\.conf)$')
GIT_VIEW = ('--stat', '--name-only', '--name-status')
HL_DO = ('Held-out labels are read only by the held-out evaluation, which the human runs in a Terminal (sbt goldSetEvalHeldout, D15(c)). '
         'Membership, freeze, config, blind-view and queue files under gold/ may be Read by exact path; give Grep a '
         'path inside your own module; use git diff/show with pathspecs outside gold/. Otherwise return STATUS: BLOCKED-ON human.')
# D15(c) R6 (FE-P5-04): held-out evaluation output that sbt may keep under target/ is for the human only.
HELDOUT_OUT_READ = re.compile(r'^target/(streams/(.+/)?goldsetevalheldout|local-runs/heldout)(/|$)')
HELDOUT_OUT_GREP = re.compile(r'^target(/streams(/.*)?|/local-runs(/heldout(/.*)?)?)?$')
HO_DO = ('Held-out evaluation output is for the human only (decision D15(c)). Read or Grep dev output (target/local-runs/dev/, '
         'the goldSetEvalDev streams) or your own module instead; if held-out results are needed, return STATUS: BLOCKED-ON human.')
HE_DO = ("Held-out gold-set evaluation is human-only (decision D15(c)): the human runs 'sbt goldSetEvalHeldout' in a separate Terminal, "
         "outside every Claude Code session. Do not run, request, or retry it; entity-resolution-engineer may run 'sbt goldSetEvalDev'. "
         "Name the held-out run as the human's NEXT GATE, or return STATUS: BLOCKED-ON human.")
HP_DO = ("The held-out local pipeline is human-only (decisions-21): the human runs 'sbt localPipelineHeldout' as plain batch sbt in a regular "
         "Terminal, outside every Claude Code session. Do not run, request, or retry it; hadoop-engineer may run 'sbt localPipelineDev'. "
         "Name the held-out run as the human's NEXT GATE, or return STATUS: BLOCKED-ON human.")
GATE_CH = re.compile(r'[A-Za-z0-9._/:=@,+ -]\Z')
DESTRUCT = re.compile(r'(delete|terminate|remove|purge|deregister|disable|batch-delete|batch-write|modify|put|create|update|reset|'
    r'schedule|stop|start|run|execute|failover|restore|revoke|retire|set|add|attach|detach|register|cancel|invoke|send|publish|'
    r'import|copy|replace|associate|disassociate|enable|tag|untag|rotate|apply|reboot|promote|upload|restart)-[a-z0-9-]*\Z')
SECRET_OPS = {'get-secret-value', 'get-login-password', 'get-login', 'export-credentials', 'get-session-token', 'configure',
              'get-federation-token', 'get-authorization-token', 'decrypt', '--with-decryption', 'assume-role',
              'get-role-credentials', 'get-credentials-for-identity', 'generate-data-key'}
DANGER = set('rm rmdir dd shred wipefs diskutil find xargs chmod chown mv cp ln tee truncate sudo su env printenv eval exec '
             'curl wget nc scp rsync ssh perl ruby node osascript'.split())
RX = {'INT': r'[0-9]{1,4}', 'SHA40': r'[0-9a-f]{40}', 'SHA256': r'[0-9a-f]{64}', 'REGION': r'[a-z]{2}-[a-z]+-[0-9]',
      'AWSID': r'[A-Za-z0-9][A-Za-z0-9:/._-]{0,255}', 'IMGID': r'image(Tag|Digest)=[A-Za-z0-9:._-]{1,128}',
      'OUT': r'json|text', 'RO': r'scholarchain-readonly'}
CLASSES = set(RX) | {'PATH', 'REV', 'REVX', 'SBT', 'SBTTEST', 'DSSCRIPT', 'ENV', 'ENVNP', 'TFCHDIR', 'TFPLAN'}
PATHLIKE = ('PATH', 'DSSCRIPT', 'TFPLAN')
SBT_TASKS = ('compile', 'Test/compile', 'test', 'scalafmt', 'Test/scalafmt', 'scalafmtCheck')
IDENT, SHAPES, RAW, ROOT, CWD, DIRS = 'unknown', 'none', '', '', '', {}

def norm(s):
    return ' ' + ' '.join(re.sub(r'["\'\\`]', '', s).replace('\n', ' ; ').lower().split()) + ' '

def secretish(t):
    return bool(SECRET_PATH.search(t) or SECRET_TEXT.search(norm(t)))

FLOOR = [(r'(terraform|tofu|terragrunt).*(apply|destroy)', 'terraform-apply-destroy', 'never run from an agent tool call'),
         (r'(terraform|tofu).*(state (rm|mv|push)|force-unlock|import|taint)', 'terraform-state-mutation', 'human-only'),
         (r'git.*push.*(--force|-f |-f$|--mirror|--delete|\+)', 'git-force-push', 'never permitted'),
         (r'git.*push', 'git-push-human-only', 'the human commits and pushes'),
         (r'rm .*(-[a-z]*r|--recursive)', 'rm-recursive-force', 'recursive deletion is never run by agents'),
         (r'mkfs|shred|wipefs|diskutil|dd .*of=', 'destructive-filesystem', 'never permitted'),
         (r's3 .*(rm|rb|mv) |s3api.*delete', 's3-destructive', 'cleanup is human-run'),
         (r'dynamodb.*(delete|batch-write)', 'dynamodb-destructive', 'human-gated data repair'),
         (r'neptune.*(delete|reset|failover)', 'neptune-destructive', 'human-gated'),
         (r's3vectors.*delete', 's3vectors-destructive', 'human-gated'), (r'ecr.*delete', 'ecr-destructive', 'human-gated'),
         (r'kms.*(delete|disable|schedule|revoke|retire)', 'kms-destructive', 'human-gated'),
         (r'emr.*(terminate|modify|add-instance|put-|remove-|set-termination|create-cluster|run-job-flow)',
          'emr-unreviewed-scale-or-termination', 'use the approved scripts and Terraform'),
         (r'aws.*(delete|terminate|remove|purge|deregister|disable)-', 'aws-destructive-verb', 'Terraform plus approved scripts only'),
         (r'get-secret-value|with-decryption|get-login-password|printenv|(^| )env( |$)', 'secret-dump', 'never print secrets'),
         (r'cleanup\.sh', 'cleanup-human-only', 'the human runs it'), (r'marktestrun|last-test-run', 'test-marker-forgery', 'run sbt check'),
         (r'drop (table|database|schema|graph)|truncate|detach delete', 'database-destruction', 'human-gated'),
         (r'claude\.md|agents\.md|\.mcp\.json|\.claude/', 'constitution-write', 'only factory-engineer, via Write/Edit, with ratification')]

def classify(raw):
    t, out = norm(raw), []
    for rx, rule, why in FLOOR:
        if re.search(rx, t) and not (rule == 'constitution-write' and IDENT == 'factory-engineer'):
            out.append((rule, why))
    if secretish(raw):
        out.append(('secret-read', 'secret material is never read'))
    return out

def deny(rule, why, do):
    lines = ['BLOCKED [%s]: %s' % (rule, why), 'Identity: %s. Allowed shapes for you: %s.' % (IDENT, SHAPES), 'Do instead: %s' % do]
    fl = [f for f in classify(RAW) if f[0] != rule] if RAW else []
    if fl:
        lines.append('Floor rule also matched: ' + '; '.join('%s (%s)' % f for f in fl[:3]))
    print('\n'.join(lines + [TAIL]))
    sys.exit(2)

def ask(reason):
    print(json.dumps({'hookSpecificOutput': {'hookEventName': 'PreToolUse', 'permissionDecision': 'ask',
                                             'permissionDecisionReason': reason}}))
    sys.exit(0)

def is_path(t):
    return (re.fullmatch(r'[A-Za-z0-9_.][A-Za-z0-9._/-]*', t) is not None and not SEG_BAD.search(t)
            and '//' not in t and not secretish(t))

def is_rev(t):
    parts = t.split('..')
    return len(parts) <= 2 and all(re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9._/-]*', p) and not SEG_BAD.search(p) for p in parts)

def cls_ok(c, arg, t):
    if c == 'AWSID' and (re.match(r'(?i)(file|fileb|https?):', t) or '//' in t):
        return False    # AWS CLI would load file:// / fileb:// / http(s):// parameter values
    if c in RX:
        return re.fullmatch(RX[c], t) is not None
    if c == 'PATH':
        return is_path(t) and t.startswith(arg or '')
    if c == 'REV':
        return is_rev(t)
    if c == 'REVX':
        a, sep, b = t.partition(':')
        return is_rev(a) and (not sep or is_path(b))
    if c == 'SBT':
        return arg in DIRS.get('@projects', ()) and t in ['%s/%s' % (arg, k) for k in SBT_TASKS]
    if c == 'SBTTEST':
        return t in ['%s/test' % p for p in DIRS.get('@projects', ())]
    if c == 'DSSCRIPT':
        return is_path(t) and re.fullmatch(r'datasets/scripts/[a-z0-9][a-z0-9-]*\.sh', t) is not None
    if c in ('ENV', 'ENVNP'):
        return t in DIRS.get('@env' if c == 'ENV' else '@env-nonprod', ())
    env = DIRS.get('@env', ())
    if c == 'TFCHDIR':
        return t.startswith('-chdir=infra/terraform/environments/') and t.split('/')[-1] in env and t.count('/') == 3
    if c == 'TFPLAN':
        m = re.fullmatch(r'infra/terraform/environments/([a-z0-9-]+)/tfplan', t)
        return m is not None and m.group(1) in env
    raise ValueError('unknown class ' + c)

def parse_spec(spec):
    items = []
    for w in spec.split(' '):
        if w.startswith('{') and w.endswith('}'):
            alts = []
            for a in w[1:-1].split(';'):
                m = re.fullmatch(r'(!?)(-[A-Za-z0-9-]+)(?:([=:])<([A-Z0-9]+)(?::([^>]*))?>(\+?))?', a)
                if not m or (m.group(4) and m.group(4) not in CLASSES):
                    raise ValueError('bad option %r' % a)
                alts.append((m.group(2), m.group(3) or '', m.group(4), m.group(5), m.group(6) == '+', m.group(1) == '!'))
            items.append(('opts', alts))
        elif w.startswith('<'):
            m = re.fullmatch(r'<([A-Z0-9]+)(?::([^>]*))?>([?*+]?)', w)
            if not m or m.group(1) not in CLASSES:
                raise ValueError('bad class item %r' % w)
            items.append(('cls', m.group(1), m.group(2), m.group(3)))
        elif re.fullmatch(r'\??[A-Za-z0-9._/:=@,+-]+', w):
            items.append(('opt', w[1:]) if w.startswith('?') else ('lit', w))
        else:
            raise ValueError('bad spec item %r' % w)
    return items

def match(items, toks):
    i, n, caps = 0, len(toks), []
    for it in items:
        if it[0] in ('lit', 'opt'):
            if i < n and toks[i] == it[1]:
                i += 1
            elif it[0] == 'lit':
                return None
        elif it[0] == 'cls':
            _, c, arg, q = it
            cnt = 0
            while i < n and (cnt == 0 or q in ('*', '+')) and cls_ok(c, arg, toks[i]):
                caps.append((c, toks[i])); i += 1; cnt += 1
            if cnt == 0 and q in ('', '+'):
                return None
        else:
            used = set()
            while i < n:
                for j, (flag, sep, c, arg, multi, _req) in enumerate(it[1]):
                    t = toks[i]
                    if j in used:
                        continue
                    if not sep and t == flag:
                        i += 1
                    elif sep == '=' and t.startswith(flag + '=') and cls_ok(c, arg, t[len(flag) + 1:]):
                        caps.append((c, t[len(flag) + 1:])); i += 1
                    elif sep == ':' and t == flag and i + 1 < n and cls_ok(c, arg, toks[i + 1]):
                        caps.append((c, toks[i + 1])); i += 2
                        while multi and i < n and not toks[i].startswith('-') and cls_ok(c, arg, toks[i]):
                            caps.append((c, toks[i])); i += 1
                    else:
                        continue
                    used.add(j)
                    break
                else:
                    break
            if any(a[5] and j not in used for j, a in enumerate(it[1])):
                return None
    return caps if i == n else None

def load():
    rows, groups = [], {}
    with open(TABLE, encoding='ascii') as f:
        for ln, line in enumerate(f, 1):
            c = line.rstrip('\n').split('\t')
            if not c[0] or c[0].startswith('#'):
                continue
            if len(c) <= 2 and c[0] in ('@projects', '@env', '@env-nonprod'):
                DIRS[c[0]] = tuple((c + [''])[1].split())
                continue
            if len(c) != 6 or not re.fullmatch(r'active|inactive:[A-Za-z0-9+_-]+', c[1]) or c[2] not in ('allow', 'ask') \
                    or c[3] not in ('-', 'hook', 'git', 'git-ask', 'git+args'):
                raise ValueError('line %d: malformed row' % ln)
            r = {'ln': ln, 'id': c[0], 'active': c[1] == 'active', 'status': c[1], 'mode': c[2], 'prov': c[3], 'spec': c[4],
                 'why': c[5], 'items': None if c[4].startswith('@') else parse_spec(c[4])}
            (groups.setdefault(c[0], []) if c[0].startswith('@') else rows).append(r)
    return rows, groups

def rows_for(ident, rows, groups):
    out = []
    for r in rows:
        if r['id'] != ident:
            continue
        if r['items'] is not None:
            out.append(r)
            continue
        if r['spec'] not in groups:
            raise ValueError('line %d: unknown group %s' % (r['ln'], r['spec']))
        for g in groups[r['spec']]:
            out.append(dict(g, active=g['active'] and r['active'], mode='ask' if 'ask' in (g['mode'], r['mode']) else 'allow',
                            group=r['spec']))
    return out

def render(rows):
    seen, out = set(), []
    for r in rows:
        if r['active']:
            s = r.get('group') or r['spec']
            if s not in seen:
                seen.add(s); out.append(s + (' (ask)' if r['mode'] == 'ask' and not r.get('group') else ''))
    s = '; '.join(out) or 'none'
    return (s if len(s) < 700 else s[:700] + ' ...') + ' (full table: .claude/hooks/bash-allowlist.tsv)'

def heldout_human_only_bash(toks):
    low = [t.lower() for t in toks]
    if os.path.basename(low[0]) != 'sbt':
        return None
    if any('goldsetevalheldout' in t for t in low[1:]):
        return ('heldout-eval-human-only', 'sbt goldSetEvalHeldout prints held-out metrics and false-positive and false-negative '
                'listings, so no agent and not the orchestrator runs it (decision D15(c)); no identity has an allowlist row for it.', HE_DO)
    if any('localpipelineheldout' in t for t in low[1:]):
        return ('heldout-pipeline-human-only', "sbt localPipelineHeldout runs only as plain batch sbt in the human's own Terminal "
                '(decisions-21), so no agent and not the orchestrator runs it; no identity has an allowlist row for it.', HP_DO)
    return None

def tripwire(toks):
    low = [t.lower() for t in toks]
    s, prog = set(low), os.path.basename(low[0])
    he = heldout_human_only_bash(toks)
    if he:
        return he
    if s & {'apply', 'destroy', '-destroy'}:
        return 'terraform-apply-destroy', 'apply/destroy never runs from an agent tool call', 'infra-engineer presents a plan; apply runs only through the human-approved scripts/deploy.sh path.'
    if s & {'state', 'import', 'taint', 'untaint', 'force-unlock', 'console'}:
        return 'terraform-state-mutation', 'state mutation bypasses plan review', 'Describe the change in a BLOCKED-ON report; the human performs it.'
    if 'push' in s or any(t.startswith('--force') or t in ('-f', '--mirror', '--delete') for t in low):
        return 'git-force-push', 'agents never push; force pushes are never permitted', 'The human commits and pushes each ratified phase.'
    if any('cleanup.sh' in t for t in low):
        return 'cleanup-human-only', 'scripts/cleanup.sh deletes cloud resources', 'Report the cleanup need; the human runs it after review.'
    if any('marktestrun' in t or 'last-test-run' in t for t in low):
        return 'test-marker-forgery', 'only sbt check may refresh .claude/.last-test-run, after tests pass', "Run 'sbt check'."
    if prog == 'aws' and s & SECRET_OPS:
        return 'secret-dump', 'the call can print credentials or secret values', 'Reference secrets by name or ARN only.'
    if prog == 'aws' and any(DESTRUCT.match(t) or t in ('rb', 'rm', 'mv', 'cp', 'sync', 'mb') for t in low):
        return ('emr-unreviewed-scale-or-termination' if 'emr' in s else 'aws-destructive-verb'), \
            'destructive or mutating AWS operations are never run directly by agents', 'All AWS changes go through reviewed Terraform plus approved scripts.'
    if prog in DANGER or prog.startswith('mkfs') or (prog in ('bash', 'sh', 'zsh') and (len(low) != 3 or low[1] != '-n')) or \
            (prog.startswith('python') and low[1:2] != ['--version'] and low[1:4] != ['-i', '-m', 'json.tool']):
        return 'destructive-program', '%s is never run directly (file mutation, deletion, interpreters, or network)' % prog, DO_GATE
    for t in toks:
        if secretish(t):
            return 'secret-read', 'token %r names secret material' % t, 'Use Secrets Manager ARNs or ratified placeholder names.'
    return None

def git_rels(p):
    # git reads REV:path from the repository root, and pathspecs and REV:./path from the shell cwd
    return {os.path.relpath(os.path.normpath(os.path.join(b, p)), ROOT).lower() for b in (ROOT, CWD)}

def heldout_label_bash(toks):
    low = [t.lower() for t in toks]
    if os.path.basename(low[0]) != 'git' or len(low) < 2 or low[1] not in ('diff', 'show'):
        return None
    revs, paths, dd = [], [], False
    for t in toks[2:]:
        if t == '--':
            dd = True
        elif dd:
            paths.append(t)
        elif not t.startswith('-'):
            revs.append(t)
    blobs = [r.split(':', 1)[1] for r in revs if ':' in r]
    view = any(t in GIT_VIEW for t in low[2:])
    for p in blobs + ([] if view else paths):
        if p and any((r == 'gold' or r.startswith('gold/')) and not GOLD_OK.match(r) for r in git_rels(p)):
            return ('heldout-label-read', 'git %s naming %r could print human gold-set labels (G9-c).' % (low[1], p), HL_DO)
    if view or blobs or (paths and not any('.' in git_rels(p) for p in paths)):
        return None    # names only; one blob or tree checked above; or every pathspec lies outside gold/
    args = (['show', '--name-only', '--format=', '--no-ext-diff'] if low[1] == 'show' else
            ['diff', '--name-only', '--no-ext-diff'] + [t for t in toks[2:] if t in ('--cached', '--staged')])
    try:
        out = git_out(args + revs + ['--', 'gold'], time.monotonic() + GIT_BUDGET)
    except subprocess.TimeoutExpired:
        return ('heldout-label-read', 'git %s could not be checked for held-out gold labels within %d s (G9-c).' % (low[1], GIT_BUDGET), HL_DO)
    except RuntimeError:
        return None    # git rejects these arguments, so the real call prints no content either
    hit = [n.strip() for n in out.decode('utf-8', 'replace').split('\n') if HELDOUT_LABELS.search(n.strip().lower())]
    if hit:
        return ('heldout-label-read', 'git %s would print the held-out human gold labels %s (G9-c).' % (low[1], hit[0]), HL_DO)
    return None

def repo_rels(v):
    # lower-cased repo-relative readings of a tool path (as given and symlink-resolved, from the shell cwd);
    # '.' when it is the repository root or one of its ancestors; paths elsewhere are omitted
    out, lr = set(), ROOT.lower().rstrip(os.sep)
    ab = os.path.normpath(os.path.join(CWD, v))
    for p in (ab, os.path.realpath(ab)):
        r = p.lower().rstrip(os.sep) or os.sep
        if r == lr or r.startswith(lr + os.sep):
            out.add(os.path.relpath(r, lr))
        elif lr.startswith(r.rstrip(os.sep) + os.sep):
            out.add('.')
    return out

def gold_check(tool, ti):
    if tool == 'Glob':
        return    # Glob lists names, never content
    v = ti.get('file_path' if tool == 'Read' else 'path')
    rels = repo_rels(v) if isinstance(v, str) and v else {'.'}
    for rel in rels:
        if tool == 'Read' and HELDOUT_LABELS.search(rel):
            deny('heldout-label-read', 'Read %r: held-out human gold labels are read only by the held-out evaluation the human runs, never by an agent tool (G9-c).' % v, HL_DO)
        if tool == 'Grep' and (rel == 'gold' or rel.startswith('gold/')):
            deny('heldout-label-read', 'Grep under gold/ (%r) could print held-out human gold labels (G9-c).' % v, HL_DO)
        if tool == 'Read' and HELDOUT_OUT_READ.search(rel):
            deny('heldout-eval-output-read', 'Read %r: sbt keeps held-out evaluation output (metrics, false-positive and false-negative listings) there; it is for the human only (D15(c)).' % v, HO_DO)
        if tool == 'Grep' and (HELDOUT_OUT_GREP.search(rel) or HELDOUT_OUT_READ.search(rel)):
            deny('heldout-eval-output-read', 'Grep %r could search held-out evaluation output kept under target/ (D15(c)).' % v, HO_DO)
    g = ti.get('glob')
    if tool == 'Grep' and '.' in rels and isinstance(g, str) and re.search(r'gold|heldout|label', g, re.I):
        deny('heldout-label-read', 'Grep over the repository with glob %r could reach held-out gold-label files (G9-c).' % g, HL_DO)

GIT_BUDGET = 10.0    # seconds for ALL provenance git calls of one hook call (hook timeout is 15 s)

def git_out(args, deadline, inp=None):
    left = deadline - time.monotonic()
    if left <= 0.2:
        raise subprocess.TimeoutExpired('git', GIT_BUDGET)
    r = subprocess.run(['git', '-C', ROOT, '-c', 'core.fsmonitor=false', '-c', 'core.untrackedCache=false'] + args,
                       input=inp if inp is not None else b'', stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=left,
                       env=dict(os.environ, GIT_OPTIONAL_LOCKS='0'))
    if r.returncode:
        raise RuntimeError('git %s exited %d' % (args[0], r.returncode))
    return r.stdout

def tree_clean(paths, dirs, deadline):
    # Every file in `paths` and EVERYTHING under each dir in `dirs` must be byte-identical to HEAD
    # (raw bytes via hash-object --no-filters, so clean/smudge filters cannot mask changes), with the
    # same executable bit; no untracked, ignored, deleted, or symlinked entries.
    want = {}
    for line in git_out(['ls-tree', '-r', '-z', 'HEAD', '--'] + list(paths) + [d + '/' for d in dirs], deadline).split(b'\0'):
        if line:
            meta, name = line.split(b'\t', 1)
            mode, _typ, oid = meta.decode().split(' ')
            want[name.decode('utf-8', 'surrogateescape')] = (mode, oid)
    have = set(paths)
    for d in dirs:
        base = os.path.join(ROOT, d)
        if os.path.islink(base) or (os.path.exists(base) and os.path.realpath(base) != os.path.normpath(base)):
            return '%s is reached through a symlink' % d
        for dp, dns, fns in os.walk(base):
            for n in dns + fns:
                fp = os.path.join(dp, n)
                if os.path.islink(fp):
                    return '%s is a symlink inside a script directory' % os.path.relpath(fp, ROOT)
                if n in fns:
                    have.add(os.path.relpath(fp, ROOT))
    for p in sorted(have):
        if p not in want:
            return '%s is not tracked at HEAD (uncommitted, untracked, or ignored)' % p
        if '\n' in p:
            return '%s has a newline in its name' % p.replace('\n', '?')
    for p in sorted(set(want) - have):
        return '%s is deleted in the working tree' % p
    names = sorted(have)
    oids = git_out(['hash-object', '--no-filters', '--stdin-paths'], deadline, ('\n'.join(names) + '\n').encode()).decode().split()
    if len(oids) != len(names):
        return 'git hash-object returned %d ids for %d files' % (len(oids), len(names))
    for n, o in zip(names, oids):
        mode, oid = want[n]
        if mode not in ('100644', '100755'):
            return '%s has mode %s at HEAD (not a regular file)' % (n, mode)
        if o != oid:
            return '%s is modified relative to HEAD' % n
        if bool(os.stat(os.path.join(ROOT, n)).st_mode & 0o111) != (mode == '100755'):
            return '%s executable bit differs from HEAD' % n
    return None

def provenance(rel, dirs, deadline):
    ab = os.path.join(ROOT, rel)
    if os.path.realpath(ab) != os.path.normpath(ab) or not os.path.isfile(ab):
        return '%s is not a regular file at that path (missing, or reached through a symlink)' % rel
    if rel.startswith('.claude/hooks/'):
        return None
    try:
        return tree_clean([rel], dirs, deadline)
    except subprocess.TimeoutExpired:
        return 'provenance of %s is not verifiable: git did not finish within the %d s budget' % (rel, GIT_BUDGET)
    except Exception as e:
        return 'provenance of %s is not verifiable (%s: %s)' % (rel, type(e).__name__, e)

def decide(r, toks, caps):
    if r['prov'] != '-' and CWD != ROOT:
        return 'deny', ('cwd-not-repo-root', 'repository scripts run only from the repository root (cwd is %s)' % CWD, 'Start the call from the repository root.')
    targets = [toks[0]] if r['prov'] != '-' else []
    targets += [t for c, t in caps if c == 'PATH'] if r['prov'] == 'git+args' else []
    deadline = time.monotonic() + GIT_BUDGET
    for i, t in enumerate(targets):
        dirs = [d for d in ('datasets/scripts', 'scripts') if i == 0 and t.startswith(d + '/')][:1]
        p = provenance(t, dirs, deadline)
        if p and r['prov'] == 'git-ask':
            return 'ask', '%s; the human must approve running it (dataset-engineer own script).' % p
        if p:
            return 'deny', ('script-provenance', '%s; a repository script and every file in its script directory must be committed and identical to HEAD before an agent runs it' % p,
                            'The human reviews and commits the scripts first; then retry the same command.')
    return ('ask', 'allowlist row %d (%s): %s' % (r['ln'], r['spec'], r['why'])) if r['mode'] == 'ask' else ('none', '')

def bash_check(cmd):
    global SHAPES
    if IDENT == 'code-reviewer':
        deny('reviewer-read-only', 'code-reviewer is read-only and has no Bash authority.', 'Use Read, Grep, and Glob. Ask the orchestrator to place the diff under docs/agent-runs/phase-NN/ for you to Read.')
    if IDENT != 'ORCHESTRATOR' and IDENT not in AGENTS:
        deny('unknown-identity', "agent_type '%s' is not one of the 18 ratified agents, so it has no Bash entries." % IDENT, 'Use a ratified agent from docs/agents.md (Agent catalog).')
    try:
        rows = rows_for(IDENT, *load())
    except Exception as e:
        deny('allowlist-unreadable', '.claude/hooks/bash-allowlist.tsv could not be loaded (%s), so nothing can be allowed.' % e, 'Report BLOCKED-ON factory-engineer with this message.')
    SHAPES = render(rows)
    if not isinstance(cmd, str) or not cmd:
        deny('shell-syntax-gate', 'empty or non-text command.', DO_GATE)
    if len(cmd.encode('utf-8', 'surrogatepass')) > 400:
        deny('shell-syntax-gate', 'the command is longer than 400 bytes.', DO_GATE)
    bad = next((ch for ch in cmd if not GATE_CH.match(ch)), None)
    if bad is not None:
        deny('shell-syntax-gate', 'character %r (U+%04X) is outside the accepted alphabet [A-Za-z0-9 . _ / : = @ , + -] plus single spaces; '
             'shell operators, quotes, expansions, redirection, globs, comments, tabs, and newlines are never accepted.' % (bad, ord(bad)), DO_GATE)
    toks = cmd.split(' ')
    if '' in toks or len(toks) > 24:
        deny('shell-syntax-gate', 'tokens must be separated by exactly one space, with no leading or trailing space, and at most 24 tokens.', DO_GATE)
    for t in toks:
        if t.startswith('=') or '==' in t:
            deny('shell-syntax-gate', 'token %r starts with = or contains == (zsh = expansion).' % t, DO_GATE)
    if not (CWD == ROOT or CWD.startswith(ROOT + os.sep)):
        deny('cwd-outside-repo', 'the shell working directory %s is outside the repository.' % CWD, 'Run commands from the repository root.')
    he = heldout_human_only_bash(toks)
    if he:
        deny(*he)
    hits = [(r, c) for r in rows if r['active'] for c in [match(r['items'], toks)] if c is not None]
    if not hits:
        lead = lambda r: [it[1] for it in (r['items'] or [])[:2] if it[0] == 'lit']
        dormant = [r for r in rows if not r['active'] and lead(r) and (len(lead(r)) == 2 or '/' in lead(r)[0])
                   and toks[:len(lead(r))] == lead(r)]
        if dormant:
            deny('entry-inactive', 'this shape is listed but INACTIVE (%s, row %d): %s.' % (dormant[0]['status'], dormant[0]['ln'], dormant[0]['why']),
                 'Report BLOCKED-ON human: the row activates only after the named decision is ratified.')
        deny('no-allowlist-entry', 'the command does not match any active allowlist entry for %s.' % IDENT,
             'Use one of the shapes above exactly, or an equivalent tool (Read/Grep/Glob, Write/Edit). For a new shape report BLOCKED-ON factory-engineer with the command, workflow citation, what it writes, and why no entry suffices.')
    tw = tripwire(toks)
    if tw:
        deny(*tw)
    hl = heldout_label_bash(toks)
    if hl:
        deny(*hl)
    gi = os.path.join(ROOT, '.gitignore')
    if toks[0] == 'sbt' and (os.path.islink(gi) or not os.path.isfile(gi)):
        deny('gitignore-missing', 'sbt writes target/, .bsp/ and other build output; without the repository .gitignore those outputs '
             'are git-visible and the Bash audit would report them (and the test marker rules depend on it). Decision B6 is pending.',
             'Report BLOCKED-ON build-engineer: the ratified .gitignore (decision B6) must be installed before sbt runs.')
    if toks[0] == 'sbt' and 'clean' in [t.lower() for t in toks[1:]] and os.path.isdir(os.path.join(ROOT, 'modules/hadoop/target/local-pipeline/er-candidates/heldout')):
        deny('heldout-run-protected', 'sbt clean would delete modules/hadoop/target/local-pipeline/er-candidates/heldout/, the held-out pipeline output the frozen gold membership was drawn from (decision D10).',
             "Run 'sbt check' instead (no clean). If a clean build is really required, return STATUS: BLOCKED-ON human: the human archives that directory first.")
    for _, caps in hits:
        for c, t in caps:
            if c in PATHLIKE:
                rp = os.path.realpath(os.path.join(CWD, t))
                rel = os.path.relpath(rp, ROOT)
                if not (rp == ROOT or rp.startswith(ROOT + os.sep)) or SEG_BAD.search(rel) or secretish(rel):
                    deny('path-escape-or-secret', '%s resolves to %s (outside the repository, inside .git, or secret material).' % (t, rp), 'Name the real repository file; symlinks out of the repository are refused.')
    results = [decide(r, toks, caps) for r, caps in hits]
    for kind, val in results:
        if kind == 'deny':
            deny(*val)
    for kind, val in results:
        if kind == 'ask':
            ask(val)

def read_check(tool, ti):
    keys = ('file_path',) if tool == 'Read' else ('path', 'pattern', 'glob')
    for k in keys:
        v = ti.get(k)
        if isinstance(v, str) and v and secretish(v):
            deny('secret-read', '%s %s=%r references a secret location (.env*, secrets/, ~/.ssh, ~/.gnupg, ~/.aws, keys, credentials, tfstate).' % (tool, k, v),
                 'Use Secrets Manager references (ARNs) or ratified placeholder names. Never read, print, or copy secret material.')
    home = os.path.realpath(os.environ.get('HOME', '/nonexistent'))
    for k in ('file_path', 'path'):
        v = ti.get(k)
        if not isinstance(v, str) or not v:
            continue
        v = re.sub(r'^(~|\$HOME|\$\{HOME\})(?=/|$)', home, v)
        ab = os.path.join(CWD, v)
        rp = os.path.realpath(ab)
        if secretish(rp) or secretish(os.path.normpath(ab)):
            deny('secret-read', '%s resolves to secret material (%s), e.g. through a symlink.' % (v, rp), 'Name the exact non-secret file you need.')
        if tool != 'Read' and rp in ('/', '/Users', '/home', '/root', '/private', '/var', '/private/var', home, os.path.dirname(home)):
            deny('secret-read', '%s on %s would search a home or system root that contains keys and credentials.' % (tool, rp), 'Search inside the repository or a named non-secret directory.')
    gold_check(tool, ti)

def mcp_check(tool, ti):
    blob = json.dumps(ti)
    if secretish(blob):
        deny('secret-read', 'the MCP call references secret material.', 'Reference secrets by name or ARN only.')
    cli = ti.get('cli_command')
    if not tool.startswith('mcp__aws-api__') or cli is None:
        return
    if not isinstance(cli, str):
        deny('aws-unverifiable', 'cli_command is not a string.', "Issue one literal 'aws <service> <describe|list|get>-...' call.")
    low = norm(cli).split()
    if set(low) & SECRET_OPS:
        deny('secret-dump', 'the AWS call can return credentials or secret values.', 'Reference secrets by name or ARN only.')
    if any(DESTRUCT.match(t) or t in ('rb', 'rm', 'mv', 'cp', 'sync', 'mb') for t in low):
        deny('emr-unreviewed-scale-or-termination' if 'emr' in low else 'aws-destructive-verb', 'mutating or destructive AWS operations are not run by agents (the MCP server is also READ_OPERATIONS_ONLY).',
             'All AWS changes go through reviewed Terraform plus approved scripts (CLAUDE.md, Cloud change path).')

def validate():
    fails = []
    try:
        rows, groups = load()
        ids = {r['id'] for r in rows}
        for a in AGENTS + ['ORCHESTRATOR']:
            if (a == 'code-reviewer') == (a in ids):
                fails.append('%s: %s' % (a, 'code-reviewer must have no rows' if a == 'code-reviewer' else 'no rows'))
        for a in ids - set(AGENTS) - {'ORCHESTRATOR'}:
            fails.append('row identity %s is not a ratified agent' % a)
        for a in ids:
            for r in rows_for(a, rows, groups):
                first = r['items'][0]
                if first[0] != 'lit' and first[1:2] != ('DSSCRIPT',):
                    fails.append('line %d: program token must be a fixed word' % r['ln'])
                for it in r['items']:
                    if it[0] == 'cls' and it[1] == 'SBT' and it[2] not in DIRS.get('@projects', ()):
                        fails.append('line %d: unknown sbt project %s' % (r['ln'], it[2]))
                lits = [it[1] if it[0] in ('lit', 'opt') else 'x' for it in r['items'] if it[0] != 'opts' and it[-1] not in ('?', '*')]
                if r['active'] and tripwire(lits):
                    fails.append('line %d: active row trips %s' % (r['ln'], tripwire(lits)[0]))
                if r['prov'] == 'hook' and not r['spec'].startswith('.claude/hooks/'):
                    fails.append('line %d: PROV=hook outside .claude/hooks/' % r['ln'])
        print('PASS  bash-allowlist.tsv parses (%d identity rows, %d groups)' % (len(rows), len(groups)))
    except Exception as e:
        fails.append('bash-allowlist.tsv: %s' % e)
    for f in fails:
        print('FAIL  allowlist ' + f)
    sys.exit(1 if fails else 0)

def main():
    global IDENT, RAW, ROOT, CWD
    if MODE == '--validate-allowlist':
        validate()
    try:
        p = json.load(sys.stdin)
        assert isinstance(p, dict)
    except Exception:
        deny('payload-unparseable', 'the hook payload is not a JSON object, so the call cannot be checked.', 'Retry; if it persists, report BLOCKED-ON factory-engineer.')
    tool = p.get('tool_name') if isinstance(p.get('tool_name'), str) else ''
    ti = p.get('tool_input') if isinstance(p.get('tool_input'), dict) else {}
    # Identity: agent_type ABSENT -> ORCHESTRATOR. Present but not a non-empty string, the string
    # "null", or not one of the 18 ratified agents -> deny every checked tool (fail closed).
    if 'agent_type' not in p:
        IDENT = 'ORCHESTRATOR'
    else:
        at = p['agent_type']
        IDENT = at if isinstance(at, str) else 'INVALID(%s)' % type(at).__name__
        if not isinstance(at, str) or at in ('', 'null'):
            deny('invalid-identity', 'the payload carries agent_type=%s, which is not a valid agent name, so the call cannot be attributed.' % json.dumps(at),
                 'Run the work through a ratified agent from docs/agents.md (Agent catalog), or from the orchestrator.')
        if at not in AGENTS:
            deny('unknown-identity', "agent_type '%s' is not one of the 18 ratified agents, so it has no tool authority here." % at,
                 'Use a ratified agent from docs/agents.md (Agent catalog).')
    ROOT = os.path.realpath(os.environ.get('CLAUDE_PROJECT_DIR') or os.getcwd())
    cwd = p.get('cwd')
    CWD = os.path.realpath(cwd) if isinstance(cwd, str) and os.path.isdir(cwd) else ROOT
    if tool == 'Bash':
        RAW = ti.get('command') if isinstance(ti.get('command'), str) else ''
        bash_check(ti.get('command'))
    elif tool in ('Read', 'Grep', 'Glob'):
        read_check(tool, ti)
    elif tool.startswith('mcp__'):
        mcp_check(tool, ti)
    sys.exit(0)

try:
    main()
except SystemExit:
    raise
except BaseException as e:
    print('BLOCKED [guard-internal-error]: %s: %s\nIdentity: %s.\nDo instead: report BLOCKED-ON factory-engineer with this message.\n%s' % (type(e).__name__, e, IDENT, TAIL))
    sys.exit(2)
PYEOF
if [ "${1:-}" = "--validate-allowlist" ]; then
  python3 -I -c "$PY" "$HOOK_DIR" --validate-allowlist </dev/null
  exit $?
fi
out="$(python3 -I -c "$PY" "$HOOK_DIR" hook)"; rc=$?
case "$rc" in
  0) [ -n "$out" ] && printf '%s\n' "$out"; exit 0 ;;
  2) printf '%s\n' "$out" >&2; exit 2 ;;
  *) failclosed "the guard's decision core exited with status $rc, so the call could not be checked." \
       "Report BLOCKED-ON factory-engineer with this message." ;;
esac
