"""dsutil.py -- ScholarChain dataset utilities. Owner: dataset-engineer.

Python 3.8+ standard library only (no PyYAML), so validation behaves the same on every machine.
Invoke with an isolated interpreter:  python3 -I datasets/scripts/dsutil.py <subcommand> ...

Subcommands
  validate <repo_root> [--dataset NAME]   validate datasets/dataset-manifest.yaml and datasets/samples
  get <repo_root> <dotted.key>            print one scalar from the manifest ("null" for None)
  check-xml <file>                        exit 0 if the file looks like an XML document (not an HTML page)
  check-openalex-manifest <file> <date>   check an OpenAlex manifest.json and print its totals

The manifest is written in a strict YAML subset that this module parses itself:
block mappings ("key: value" / "key:" + deeper block), block lists ("- value", "- key: value"),
double-quoted strings (JSON escapes), integers, true/false/null, [] and {}, and plain scalars that
match [A-Za-z_][A-Za-z0-9_./-]*. Anything else (tabs, flow collections, anchors, multi-line
scalars, unquoted dates, trailing comments) is rejected with a line number. Every document that
parses here is also valid YAML 1.2.
"""

import hashlib
import html.entities
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

MANIFEST_REL = 'datasets/dataset-manifest.yaml'
SAMPLES_REL = 'datasets/samples'
SUMS_NAME = 'SHA256SUMS'


# --------------------------------------------------------------------------- strict YAML subset

class YamlError(Exception):
    pass


KEY_RE = re.compile(r'^([A-Za-z0-9_][A-Za-z0-9_.-]*):(?: (.*))?$')
INT_RE = re.compile(r'^-?[0-9]+$')
PLAIN_RE = re.compile(r'^[A-Za-z_][A-Za-z0-9_./-]*$')
YAML11_BOOLISH = {'yes', 'no', 'on', 'off', 'y', 'n', 'True', 'False', 'TRUE', 'FALSE', 'Null', 'NULL'}


def _is_item(text):
    return text == '-' or text.startswith('- ')


def _tokens(text):
    toks = []
    for n, raw in enumerate(text.split('\n'), 1):
        if '\t' in raw:
            raise YamlError('line %d: tab characters are not allowed' % n)
        line = raw.rstrip(' \r')
        stripped = line.lstrip(' ')
        if not stripped or stripped.startswith('#'):
            continue
        toks.append([len(line) - len(stripped), stripped, n])
    return toks


def _scalar(s, n):
    s = s.strip()
    if s.startswith('"'):
        try:
            val, end = json.JSONDecoder().raw_decode(s)
        except ValueError as e:
            raise YamlError('line %d: bad double-quoted string (%s)' % (n, e))
        if not isinstance(val, str):
            raise YamlError('line %d: expected a string' % n)
        if s[end:].strip():
            raise YamlError('line %d: unexpected text after a quoted string' % n)
        return val
    if s in ('null', '~'):
        return None
    if s == 'true':
        return True
    if s == 'false':
        return False
    if s == '[]':
        return []
    if s == '{}':
        return {}
    if INT_RE.match(s):
        return int(s)
    if s in YAML11_BOOLISH or not PLAIN_RE.match(s):
        raise YamlError('line %d: value %r must be written as a double-quoted string' % (n, s))
    return s


def _parse_block(toks, i, indent):
    if _is_item(toks[i][1]):
        return _parse_list(toks, i, indent)
    return _parse_map(toks, i, indent)


def _parse_map(toks, i, indent):
    out = {}
    while i < len(toks):
        ind, text, n = toks[i]
        if ind < indent:
            break
        if ind > indent:
            raise YamlError('line %d: unexpected indentation' % n)
        if _is_item(text):
            raise YamlError('line %d: list item where a mapping key was expected' % n)
        m = KEY_RE.match(text)
        if not m:
            raise YamlError('line %d: expected "key: value" or "key:"' % n)
        key, rest = m.group(1), m.group(2)
        if key in out:
            raise YamlError('line %d: duplicate key %r' % (n, key))
        i += 1
        if rest is None or not rest.strip():
            if i < len(toks) and toks[i][0] > ind:
                out[key], i = _parse_block(toks, i, toks[i][0])
            else:
                out[key] = None
        else:
            out[key] = _scalar(rest, n)
    return out, i


def _parse_list(toks, i, indent):
    out = []
    while i < len(toks):
        ind, text, n = toks[i]
        if ind < indent:
            break
        if ind > indent:
            raise YamlError('line %d: unexpected indentation' % n)
        if not _is_item(text):
            raise YamlError('line %d: mapping key where a list item was expected' % n)
        item = text[2:].strip() if text != '-' else ''
        if item == '':
            i += 1
            if i < len(toks) and toks[i][0] > ind:
                val, i = _parse_block(toks, i, toks[i][0])
            else:
                val = None
            out.append(val)
        elif KEY_RE.match(item):
            toks[i] = [ind + 2, item, n]
            val, i = _parse_map(toks, i, ind + 2)
            out.append(val)
        else:
            out.append(_scalar(item, n))
            i += 1
    return out, i


def parse_yaml(text):
    toks = _tokens(text)
    if not toks:
        raise YamlError('empty document')
    if toks[0][0] != 0:
        raise YamlError('line %d: document must start at column 0' % toks[0][2])
    val, i = _parse_block(toks, 0, 0)
    if i != len(toks):
        raise YamlError('line %d: unexpected content' % toks[i][2])
    return val


def load_manifest(root):
    with open(os.path.join(root, MANIFEST_REL), encoding='utf-8') as f:
        return parse_yaml(f.read())


# --------------------------------------------------------------------------- helpers

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
            h.update(chunk)
    return h.hexdigest()


_XML_PREDEFINED = {'amp', 'lt', 'gt', 'quot', 'apos'}
_ENTITY_RE = re.compile(rb'&([A-Za-z][A-Za-z0-9]*);')


def _entity_to_numeric(m):
    name = m.group(1).decode('ascii')
    if name in _XML_PREDEFINED or name not in html.entities.name2codepoint:
        return m.group(0)
    return ('&#%d;' % html.entities.name2codepoint[name]).encode('ascii')


def parse_xml_file(path):
    """Well-formedness parse. Named entities that dblp.dtd declares (ISO Latin-1 set) are mapped to
    numeric references first, because the external DTD is not loaded."""
    with open(path, 'rb') as f:
        data = f.read()
    return ET.fromstring(_ENTITY_RE.sub(_entity_to_numeric, data))


def is_str(v):
    return isinstance(v, str) and v.strip() != ''


# --------------------------------------------------------------------------- validation

REQUIRED_TOP = ['manifest_version', 'owning_agent', 'last_reviewed', 'raw_prefix_template', 'raw_bucket_note',
                'identity_policy', 'selection', 'samples', 'datasets']

REQUIRED_DATASET = ['name', 'required', 'purpose', 'official_source', 'license', 'snapshot', 'retrieval', 'format',
                    'size', 'raw_prefix', 'integrity', 'schema_version', 'upstream_schema_version', 'schema_notes',
                    'subset_rule', 'update_strategy', 'linkage_fields', 'source_identifiers', 'personal_data',
                    'limitations', 'validation_commands', 'owning_agent', 'samples', 'unverified']

REQUIRED_NESTED = {
    'official_source': ['landing_page', 'documentation'],
    'license': ['name', 'url', 'verified_from', 'verified_on', 'redistribution', 'attribution'],
    'snapshot': ['id', 'date', 'date_meaning', 'retrieval_date', 'status'],
    'retrieval': ['script', 'dry_run_command', 'sample_command', 'verify_command', 'full_command',
                  'full_download_by_default'],
    'format': ['file_format', 'compression'],
    'size': ['expected', 'observed'],
    'integrity': ['method', 'upstream_checksum', 'our_sha256', 'etag_policy'],
    'personal_data': ['contains_personal_data', 'fields', 'handling'],
}

# Nested fields that may legitimately be null. Each has its own specific check below
# (snapshot.retrieval_date: null while the human download is pending, otherwise YYYY-MM-DD).
NULLABLE_NESTED = {('snapshot', 'retrieval_date')}


def is_empty_value(v):
    """True for null, a blank string, an empty list or mapping, or a list holding any such item.
    Booleans and integers (including false and 0) are values, not empty."""
    if v is None:
        return True
    if isinstance(v, str):
        return v.strip() == ''
    if isinstance(v, dict):
        return len(v) == 0
    if isinstance(v, list):
        return len(v) == 0 or any(is_empty_value(x) for x in v)
    return False


RAW_PREFIX_RE = re.compile(r'^s3://\{raw_bucket\}/raw/[a-z0-9-]+/snapshot_date=[0-9]{4}-[0-9]{2}-[0-9]{2}/$')
SCRIPT_RE = re.compile(r'^datasets/scripts/[a-z0-9][a-z0-9-]*\.sh$')
DATE_RE = re.compile(r'^[0-9]{4}-[0-9]{2}-[0-9]{2}$')
SHA256_RE = re.compile(r'^[0-9a-f]{64}$')
# Bucket names allowed in s3:// locations: the {raw_bucket} template and the two official public
# source buckets. Compared by exact bucket name (the text between "s3://" and the next "/"), so
# s3://openalex-anything or s3://{raw_bucket}x are rejected.
ALLOWED_S3_BUCKETS = frozenset(['{raw_bucket}', 'openalex', 'api-snapshots-reqpays-crossref'])
S3_TOKEN_RE = re.compile(r's3://[^\s"]*')


def s3_location_allowed(tok):
    if not tok.startswith('s3://'):
        return False
    return tok[len('s3://'):].split('/', 1)[0] in ALLOWED_S3_BUCKETS


# Operating-system metadata that may appear in a working copy but is never a sample. .DS_Store is
# also git-ignored at the repository root. Anything else on disk must be listed in SHA256SUMS.
OS_JUNK_FILES = frozenset(['.DS_Store', 'Thumbs.db', 'ehthumbs.db', 'desktop.ini', '.directory', 'Icon\r'])
OS_JUNK_DIRS = frozenset(['__MACOSX', '.Spotlight-V100', '.Trashes', '.fseventsd', '.TemporaryItems'])


def is_os_junk(name):
    return name in OS_JUNK_FILES or name.startswith('._')


# ORCID data minimisation (manifest datasets.orcid.subset_rule and personal_data). Samples may keep
# only the iD, public names, other-names and work summaries. Any element in one of these ORCID
# namespaces, or with one of these local names in any namespace, fails validation.
ORCID_NS_BASE = 'http://www.orcid.org/ns/'
ORCID_BANNED_NAMESPACES = frozenset([
    'email', 'address', 'keyword', 'researcher-url', 'external-identifier',
    'employment', 'education', 'distinction', 'invited-position', 'membership', 'qualification',
    'service', 'funding', 'peer-review', 'research-resource',
])
ORCID_BANNED_LOCAL_NAMES = frozenset([
    'biography',
    'emails', 'email', 'addresses', 'address', 'keywords', 'keyword',
    'researcher-urls', 'researcher-url', 'external-identifiers', 'external-identifier',
    'employments', 'employment-summary', 'educations', 'education-summary',
    'distinctions', 'distinction-summary', 'invited-positions', 'invited-position-summary',
    'memberships', 'membership-summary', 'qualifications', 'qualification-summary',
    'services', 'service-summary', 'fundings', 'funding-summary', 'peer-reviews',
    'peer-review-summary', 'research-resources', 'research-resource-summary',
])


def orcid_banned_tags(tree):
    hits = set()
    for el in tree.iter():
        tag = el.tag if isinstance(el.tag, str) else ''
        if tag.startswith('{'):
            ns, local = tag[1:].split('}', 1)
        else:
            ns, local = '', tag
        if local in ORCID_BANNED_LOCAL_NAMES:
            hits.add(tag)
        elif ns.startswith(ORCID_NS_BASE) and ns[len(ORCID_NS_BASE):].rstrip('/') in ORCID_BANNED_NAMESPACES:
            hits.add(tag)
    return sorted(hits)


class Report:
    def __init__(self):
        self.failures = 0
        self.checks = 0

    def ok(self, what):
        self.checks += 1
        print('PASS %s' % what)

    def fail(self, what, why):
        self.checks += 1
        self.failures += 1
        print('FAIL %s: %s' % (what, why))

    def check(self, cond, what, why):
        if cond:
            self.ok(what)
        else:
            self.fail(what, why)
        return cond


def _walk_strings(v):
    if isinstance(v, str):
        yield v
    elif isinstance(v, dict):
        for x in v.values():
            for s in _walk_strings(x):
                yield s
    elif isinstance(v, list):
        for x in v:
            for s in _walk_strings(x):
                yield s


def read_sums(root, rep):
    path = os.path.join(root, SAMPLES_REL, SUMS_NAME)
    sums = {}
    if not rep.check(os.path.isfile(path), 'samples: %s exists' % SUMS_NAME, 'missing ' + path):
        return sums
    with open(path, encoding='ascii') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip():
                continue
            m = re.match(r'^([0-9a-f]{64})  (\S.*)$', line)
            if not m:
                rep.fail('samples: %s line %d' % (SUMS_NAME, n), 'expected "<sha256>  <path>" (shasum -a 256 format)')
                continue
            rel = m.group(2)
            if not rel.startswith(SAMPLES_REL + '/'):
                rep.fail('samples: %s line %d' % (SUMS_NAME, n), 'path must start with %s/' % SAMPLES_REL)
                continue
            sums[rel] = m.group(1)
    return sums


def validate_samples(root, rep, manifest_samples):
    sums = read_sums(root, rep)
    base = os.path.join(root, SAMPLES_REL)
    on_disk = set()
    ignored = []
    for dp, dns, fns in os.walk(base):
        for d in [d for d in dns if d in OS_JUNK_DIRS]:
            ignored.append(os.path.relpath(os.path.join(dp, d), root).replace(os.sep, '/') + '/')
            dns.remove(d)
        for fn in fns:
            rel = os.path.relpath(os.path.join(dp, fn), root).replace(os.sep, '/')
            if is_os_junk(fn):
                ignored.append(rel)
            elif rel != SAMPLES_REL + '/' + SUMS_NAME:
                on_disk.add(rel)
    if ignored:
        print('INFO samples: ignored %d OS metadata entr%s: %s' % (
            len(ignored), 'y' if len(ignored) == 1 else 'ies', ', '.join(sorted(ignored))))
    for rel in sorted(on_disk):
        rep.check(rel in sums, 'samples: %s is listed in %s' % (rel, SUMS_NAME),
                  'every committed sample file must have a checksum')
    total = 0
    for rel, want in sorted(sums.items()):
        p = os.path.join(root, rel)
        if not rep.check(os.path.isfile(p), 'samples: %s exists' % rel, 'listed in %s but missing' % SUMS_NAME):
            continue
        total += os.path.getsize(p)
        got = sha256_file(p)
        rep.check(got == want, 'samples: sha256 %s' % rel, 'expected %s, got %s' % (want, got))
    rep.check(total <= 300 * 1024, 'samples: total size %d bytes <= 300 KiB' % total, 'samples must stay small')
    for rel in manifest_samples:
        rep.check(rel in sums, 'samples: manifest sample %s has a checksum' % rel, 'not in %s' % SUMS_NAME)
    return sums


def validate_sample_content(root, rep, rel, dataset):
    p = os.path.join(root, rel)
    if not os.path.isfile(p):
        return
    if rel.endswith('.jsonl'):
        good, bad = 0, 0
        with open(p, encoding='utf-8') as f:
            for n, line in enumerate(f, 1):
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                except ValueError as e:
                    rep.fail('content: %s line %d' % (rel, n), 'invalid JSON (%s)' % e)
                    bad += 1
                    continue
                if not isinstance(obj, dict):
                    rep.fail('content: %s line %d' % (rel, n), 'each line must be a JSON object')
                    bad += 1
                    continue
                if dataset == 'crossref' and 'abstract' in obj:
                    rep.fail('content: %s line %d' % (rel, n), 'Crossref abstracts may be copyrighted; remove them')
                    bad += 1
                    continue
                good += 1
        if bad == 0:
            rep.check(good > 0, 'content: %s has %d valid JSON records' % (rel, good), 'no records')
    elif rel.endswith('.xml'):
        try:
            tree = parse_xml_file(p)
        except ET.ParseError as e:
            rep.fail('content: %s' % rel, 'XML is not well-formed (%s)' % e)
            return
        rep.ok('content: %s is well-formed XML (root %s)' % (rel, tree.tag))
        if dataset == 'orcid':
            hits = orcid_banned_tags(tree)
            rep.check(not hits,
                      'privacy: %s has no email, address, keyword, researcher-url, person-level '
                      'external-identifier, biography, affiliation, funding, peer-review or '
                      'research-resource elements' % rel,
                      'minimise personal data; found %s' % ', '.join(hits))
        if dataset == 'dblp':
            n_pub = len([el for el in tree if el.tag in ('article', 'inproceedings', 'proceedings', 'book',
                                                          'incollection', 'phdthesis', 'mastersthesis')])
            rep.check(n_pub > 0 and all(el.get('key') for el in tree),
                      'content: %s has %d publication records, all keyed' % (rel, n_pub),
                      'every dblp record needs a key attribute')


def validate_dataset(root, rep, dsname, ds):
    where = 'datasets.%s' % dsname
    if not rep.check(isinstance(ds, dict), where, 'must be a mapping'):
        return []
    missing = [k for k in REQUIRED_DATASET if k not in ds]
    rep.check(not missing, '%s: all required fields present' % where, 'missing ' + ', '.join(missing))
    for k in ('name', 'purpose', 'raw_prefix', 'schema_version', 'upstream_schema_version', 'schema_notes',
              'subset_rule', 'update_strategy'):
        if k in ds:
            rep.check(is_str(ds[k]), '%s.%s is a non-empty string' % (where, k), 'empty or not a string')
    if 'required' in ds:
        rep.check(isinstance(ds['required'], bool), '%s.required is a boolean' % where, 'use true or false')
    for k, subkeys in sorted(REQUIRED_NESTED.items()):
        v = ds.get(k)
        if not rep.check(isinstance(v, dict), '%s.%s is a mapping' % (where, k), 'must be a mapping'):
            continue
        miss = [s for s in subkeys if s not in v]
        rep.check(not miss, '%s.%s: required sub-fields present' % (where, k), 'missing ' + ', '.join(miss))
        empty = [s for s in subkeys if s in v and (k, s) not in NULLABLE_NESTED and is_empty_value(v[s])]
        rep.check(not empty, '%s.%s: required sub-fields are non-empty' % (where, k),
                  'null, blank, or empty value in ' + ', '.join(empty))

    rep.check(ds.get('owning_agent') == 'dataset-engineer', '%s.owning_agent' % where, 'must be dataset-engineer')
    rp = ds.get('raw_prefix', '')
    rep.check(isinstance(rp, str) and RAW_PREFIX_RE.match(rp) is not None, '%s.raw_prefix is templated' % where,
              'must match s3://{raw_bucket}/raw/<dataset>/snapshot_date=YYYY-MM-DD/ (no real bucket names)')
    snap = ds.get('snapshot') or {}
    if isinstance(snap, dict):
        rep.check(isinstance(snap.get('date'), str) and DATE_RE.match(snap.get('date') or '') is not None,
                  '%s.snapshot.date is YYYY-MM-DD' % where, 'quote the date as a string')
        if isinstance(rp, str) and isinstance(snap.get('date'), str):
            rep.check(rp.endswith('snapshot_date=%s/' % snap['date']), '%s.raw_prefix matches snapshot.date' % where,
                      'raw_prefix and snapshot.date disagree')
        rd = snap.get('retrieval_date')
        rep.check(rd is None or (isinstance(rd, str) and DATE_RE.match(rd) is not None),
                  '%s.snapshot.retrieval_date is null (pending) or YYYY-MM-DD' % where, 'bad value %r' % (rd,))
    lic = ds.get('license') or {}
    if isinstance(lic, dict):
        vf = lic.get('verified_from')
        rep.check(isinstance(vf, list) and vf and all(isinstance(u, str) and u.startswith('https://') for u in vf),
                  '%s.license.verified_from lists https sources' % where, 'cite the official pages that state the terms')
        rep.check(isinstance(lic.get('verified_on'), str) and DATE_RE.match(lic.get('verified_on') or '') is not None,
                  '%s.license.verified_on is a date' % where, 'quote the date as a string')
    ret = ds.get('retrieval') or {}
    if isinstance(ret, dict):
        script = ret.get('script')
        ok = isinstance(script, str) and SCRIPT_RE.match(script) is not None
        rep.check(ok and os.path.isfile(os.path.join(root, script)), '%s.retrieval.script exists' % where,
                  'must be an existing datasets/scripts/<name>.sh')
        for k, flag in (('dry_run_command', '--dry-run'), ('sample_command', '--sample'),
                        ('verify_command', '--verify'), ('full_command', '--full')):
            cmd = ret.get(k)
            rep.check(isinstance(cmd, str) and isinstance(script, str) and script in cmd and flag in cmd.split(),
                      '%s.retrieval.%s uses %s %s' % (where, k, script, flag), 'command does not match the script and flag')
        rep.check(ret.get('full_download_by_default') is False, '%s.retrieval.full_download_by_default is false' % where,
                  'a full corpus must never be downloaded by default')
    size = ds.get('size') or {}
    if isinstance(size, dict):
        rep.check(isinstance(size.get('expected'), dict) and bool(size.get('expected')),
                  '%s.size.expected is recorded' % where, 'record the expected size and its source')
        obs = size.get('observed')
        if rep.check(isinstance(obs, dict) and 'bytes' in obs and 'status' in obs,
                     '%s.size.observed has bytes and status' % where, 'needs bytes and status'):
            if obs['bytes'] is None:
                rep.check(isinstance(obs['status'], str) and obs['status'].startswith('pending'),
                          '%s.size.observed is honestly pending' % where, 'null bytes requires status pending-...')
            else:
                rep.check(isinstance(obs['bytes'], int) and obs['bytes'] > 0, '%s.size.observed.bytes' % where,
                          'must be a positive integer')
    integ = ds.get('integrity') or {}
    if isinstance(integ, dict):
        ours = integ.get('our_sha256')
        if isinstance(ours, dict):
            vals = [v for v in ours.values() if isinstance(v, str) and SHA256_RE.match(v)]
            pending = [v for v in ours.values() if isinstance(v, str) and v.startswith('pending')]
            rep.check(bool(vals) or bool(pending), '%s.integrity.our_sha256 recorded or pending' % where,
                      'record sha256 values or an explicit pending status')
        else:
            rep.check(isinstance(ours, str) and (SHA256_RE.match(ours) is not None or ours.startswith('pending')),
                      '%s.integrity.our_sha256 recorded or pending' % where, 'sha256 hex or pending-...')
    for k in ('linkage_fields', 'limitations', 'validation_commands'):
        v = ds.get(k)
        rep.check(isinstance(v, list) and v and all(is_str(x) for x in v), '%s.%s is a non-empty list' % (where, k),
                  'list of non-empty strings required')
    sids = ds.get('source_identifiers')
    rep.check(isinstance(sids, list) and sids and all(isinstance(x, dict) and is_str(x.get('name'))
                                                       and is_str(x.get('format')) and is_str(x.get('example'))
                                                       for x in sids),
              '%s.source_identifiers have name, format, example' % where, 'each needs name, format, example')
    pd = ds.get('personal_data') or {}
    if isinstance(pd, dict):
        rep.check(isinstance(pd.get('contains_personal_data'), bool), '%s.personal_data.contains_personal_data' % where,
                  'must be true or false')
        if pd.get('contains_personal_data'):
            rep.check(isinstance(pd.get('handling'), list) and bool(pd.get('handling')),
                      '%s.personal_data.handling documented' % where, 'document minimisation and handling')
    sn = ds.get('schema_notes')
    rep.check(isinstance(sn, str) and sn.startswith('datasets/schemas/') and os.path.isfile(os.path.join(root, sn)),
              '%s.schema_notes file exists' % where, 'must point to an existing datasets/schemas/ file')
    unv = ds.get('unverified')
    rep.check(isinstance(unv, list) and all(isinstance(x, dict) and is_str(x.get('item')) and is_str(x.get('reason'))
                                            for x in unv),
              '%s.unverified entries have item and reason' % where, 'list of {item, reason}')
    mentions = any('UNVERIFIED' in s for s in _walk_strings(ds))
    if mentions:
        rep.check(isinstance(unv, list) and len(unv) > 0, '%s: UNVERIFIED values are listed under unverified' % where,
                  'every UNVERIFIED value needs an entry with a reason')
    samples = ds.get('samples')
    ok = isinstance(samples, list) and samples and all(isinstance(s, str) and s.startswith(SAMPLES_REL + '/')
                                                       for s in samples)
    rep.check(ok, '%s.samples lists files under %s/' % (where, SAMPLES_REL), 'list sample paths')
    return samples if ok else []


def validate(root, only=None):
    rep = Report()
    try:
        m = load_manifest(root)
    except (OSError, YamlError) as e:
        rep.fail('manifest: parse %s' % MANIFEST_REL, str(e))
        return rep
    rep.ok('manifest: parse %s' % MANIFEST_REL)
    if not rep.check(isinstance(m, dict), 'manifest: top level is a mapping', 'bad top level'):
        return rep
    missing = [k for k in REQUIRED_TOP if k not in m]
    rep.check(not missing, 'manifest: top-level fields present', 'missing ' + ', '.join(missing))
    rep.check(m.get('owning_agent') == 'dataset-engineer', 'manifest: owning_agent', 'must be dataset-engineer')
    ip = m.get('identity_policy') or ''
    rep.check(isinstance(ip, str) and 'never evidence of identity' in ip, 'manifest: identity_policy states the name rule',
              'must state that a matching author name is never evidence of identity')
    for s in _walk_strings(m):
        for tok in S3_TOKEN_RE.findall(s):
            rep.check(s3_location_allowed(tok), 'manifest: s3 location %s is a template or an official public source' % tok,
                      'real ScholarChain bucket names are external configuration')
        if 'arn:aws:' in s:
            rep.fail('manifest: no ARNs', 'found an ARN; account-bound identifiers are external configuration')
    dsets = m.get('datasets')
    if not rep.check(isinstance(dsets, dict) and dsets, 'manifest: datasets is a non-empty mapping', 'no datasets'):
        return rep
    sel = m.get('selection') or {}
    enrich = sel.get('enrichment') if isinstance(sel, dict) else None
    if only is None:
        rep.check(isinstance(dsets.get('dblp'), dict) and dsets['dblp'].get('required') is True,
                  'manifest: dblp is the required seed',
                  'DBLP is mandatory')
        rep.check(isinstance(enrich, list) and len(enrich) >= 3 and all(e in dsets for e in enrich),
                  'manifest: at least three enrichment datasets are selected and described',
                  'the spec requires at least three enrichment datasets')
    else:
        rep.check(only in dsets, 'manifest: dataset %s present' % only, 'unknown dataset')
    all_samples = []
    by_sample = {}
    for name in sorted(dsets):
        if only is not None and name != only:
            continue
        s = validate_dataset(root, rep, name, dsets[name])
        all_samples.extend(s)
        for rel in s:
            by_sample[rel] = name
    validate_samples(root, rep, all_samples)
    for rel in sorted(by_sample):
        validate_sample_content(root, rep, rel, by_sample[rel])
    return rep


# --------------------------------------------------------------------------- other subcommands

def cmd_get(root, dotted):
    v = load_manifest(root)
    for part in dotted.split('.'):
        if not isinstance(v, dict) or part not in v:
            print('dsutil get: no such key: %s' % dotted, file=sys.stderr)
            return 2
        v = v[part]
    if isinstance(v, (dict, list)):
        print('dsutil get: %s is not a scalar' % dotted, file=sys.stderr)
        return 2
    print('null' if v is None else (str(v).lower() if isinstance(v, bool) else v))
    return 0


def cmd_check_xml(path):
    with open(path, 'rb') as f:
        head = f.read(2048)
    low = head.lower()
    if b'<html' in low or b'<!doctype html' in low:
        print('not XML data: %s looks like an HTML page (bot protection or error page)' % path, file=sys.stderr)
        return 1
    if not head.lstrip(b'\xef\xbb\xbf \t\r\n').startswith(b'<'):
        print('not XML data: %s' % path, file=sys.stderr)
        return 1
    return 0


def cmd_check_openalex_manifest(path, date):
    with open(path, encoding='utf-8') as f:
        m = json.load(f)
    files = m.get('files') or []
    print('entity=%s date=%s record_count=%s content_length=%s files=%d' % (
        m.get('entity'), m.get('date'), m.get('record_count'), m.get('content_length'), len(files)))
    if m.get('date') != date:
        print('snapshot date mismatch: manifest %s, pinned %s' % (m.get('date'), date), file=sys.stderr)
        return 1
    return 0


def main(argv):
    if len(argv) >= 2 and argv[0] == 'validate':
        only = None
        rest = argv[2:]
        if rest:
            if len(rest) != 2 or rest[0] != '--dataset':
                print('usage: dsutil.py validate <repo_root> [--dataset NAME]', file=sys.stderr)
                return 2
            only = rest[1]
        rep = validate(argv[1], only)
        print('manifest validation: %d checks, %d failures' % (rep.checks, rep.failures))
        return 1 if rep.failures else 0
    if len(argv) == 3 and argv[0] == 'get':
        return cmd_get(argv[1], argv[2])
    if len(argv) == 2 and argv[0] == 'check-xml':
        return cmd_check_xml(argv[1])
    if len(argv) == 3 and argv[0] == 'check-openalex-manifest':
        return cmd_check_openalex_manifest(argv[1], argv[2])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
