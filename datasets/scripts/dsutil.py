"""dsutil.py -- ScholarChain dataset utilities. Owner: dataset-engineer.

Python 3.8+ standard library only (no PyYAML), so validation behaves the same on every machine.
Invoke with an isolated interpreter:  python3 -I datasets/scripts/dsutil.py <subcommand> ...

Subcommands
  validate <repo_root> [--dataset NAME]   validate datasets/dataset-manifest.yaml and datasets/samples
  get <repo_root> <dotted.key>            print one scalar from the manifest ("null" for None)
  check-xml <file>                        exit 0 if the file looks like an XML document (not an HTML page)
  check-openalex-manifest <file> <date>   check an OpenAlex manifest.json and print its totals
  er-fetch <repo_root> (dry-run|sample)   ER candidate sample set (work orders DS-P5-01, DS-P5-03): print the
                                          plan, or write the split assignment, fetch the OpenAlex/Crossref
                                          records and extract the dblp records OFFLINE from the pinned release
  er-verify <repo_root> [--with-release]  offline checks of the ER candidate sample set; --with-release also
                                          re-reads every recorded dblp byte range from the local pinned release
  er-fixture <repo_root>                  offline self-test of the dblp release extractor on the committed
                                          fixture (datasets/er-candidates/fixture/); writes only to a temp dir

The manifest is written in a strict YAML subset that this module parses itself:
block mappings ("key: value" / "key:" + deeper block), block lists ("- value", "- key: value"),
double-quoted strings (JSON escapes), integers, true/false/null, [] and {}, and plain scalars that
match [A-Za-z_][A-Za-z0-9_./-]*. Anything else (tabs, flow collections, anchors, multi-line
scalars, unquoted dates, trailing comments) is rejected with a line number. Every document that
parses here is also valid YAML 1.2.
"""

import contextlib
import datetime
import gzip
import hashlib
import html.entities
import io
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import xml.etree.ElementTree as ET
import zlib

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
        # The ER candidate set has its own provenance ledger, checksums and size bound (er-verify).
        if os.path.relpath(dp, root).replace(os.sep, '/') == SAMPLES_REL and 'er-candidates' in dns:
            dns.remove('er-candidates')
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
    if only is None:
        er = m.get('er_candidates')
        if rep.check(isinstance(er, dict), 'manifest: er_candidates section present',
                     'the ER candidate sample set (DS-P5-01) must be described'):
            for k in ('purpose', 'selection_file', 'split_file', 'samples_dir', 'ledger', 'failures_file',
                      'ranges_file', 'robots_compliance', 'byte_range_ledger', 'missing_record_policy',
                      'retrieval', 'split_rule', 'criteria', 'licenses', 'privacy', 'labeler_visibility',
                      'cross_split_check', 'limitations', 'validation_commands', 'unverified'):
                rep.check(k in er and not is_empty_value(er[k]) if k != 'unverified' else k in er,
                          'manifest: er_candidates.%s present' % k, 'missing or empty')
            ret = er.get('retrieval') or {}
            script = ret.get('script') if isinstance(ret, dict) else None
            rep.check(isinstance(script, str) and SCRIPT_RE.match(script) is not None
                      and os.path.isfile(os.path.join(root, script)),
                      'manifest: er_candidates.retrieval.script exists', 'must be an existing datasets/scripts/<name>.sh')
            rep.check(isinstance(ret, dict) and ret.get('full_download_by_default') is False,
                      'manifest: er_candidates.retrieval.full_download_by_default is false', 'must be false')
            rep.check(er.get('ranges_file') == ER_RANGES_REL, 'manifest: er_candidates.ranges_file is %s' % ER_RANGES_REL,
                      'must name the byte-range ledger written by the extractor')
        er_verify(root, rep)
    return rep


# --------------------------------------------------------------------------- ER candidate sample set
#
# Work order DS-P5-01. Real upstream records for entity-resolution review pairs. Every stored file is
# the unmodified body of one HTTPS response (one request = one file), written byte for byte; nothing
# is transcribed, re-serialized, or cleaned up. The record list is fixed in selection.tsv; the only
# derived requests are the OpenAlex author records of the authorships of a fetched OpenAlex work
# ("authors-of-work" rows, at most ER_MAX_AUTHORS_PER_WORK per work, in authorship order).

ER_DIR_REL = 'datasets/er-candidates'
ER_SELECTION_REL = ER_DIR_REL + '/selection.tsv'
ER_SPLIT_REL = ER_DIR_REL + '/split-assignment.tsv'
ER_SAMPLES_REL = SAMPLES_REL + '/er-candidates'
ER_LEDGER_REL = ER_SAMPLES_REL + '/PROVENANCE.tsv'
ER_FAILURES_REL = ER_SAMPLES_REL + '/FETCH-FAILURES.tsv'
ER_SPLIT_SALT = 'scholarchain-er-split.v1'
ER_MAX_TOTAL_BYTES = 8 * 1024 * 1024
ER_MAX_AUTHORS_PER_WORK = 6
ER_TIME_BUDGET_S = 500
ER_UA = 'ScholarChain-dataset-sampler/1.0 (CS441 course project; bounded sample fetch)'
ER_CRITERIA = ('homonym-rare', 'homonym-east-asian', 'homonym-western', 'name-variant', 'orcid-vs-none',
               'preprint-vs-vor', 'retraction', 'same-title', 'multi-doi', 'conf-vs-journal', 'ordinary')
ER_KINDS = {('dblp', 'rec'), ('openalex', 'work-by-doi'), ('openalex', 'authors-of-work'),
            ('crossref', 'work-by-doi')}
ER_LICENSES = {
    'dblp': 'CC0-1.0 (secondary ODC-BY-1.0) https://dblp.org/xml/README.txt',
    'openalex': 'CC0-1.0 https://openalex.org/about',
    'crossref': 'Crossref metadata reuse statement (no licence needed for facts; abstract not requested) '
                'https://www.crossref.org/documentation/retrieve-metadata/rest-api/',
}
ER_OA_WORK_SELECT = ('id,doi,title,display_name,publication_year,type,authorships,primary_location,biblio,ids,'
                     'primary_topic,is_retracted')
ER_OA_AUTHOR_SELECT = 'id,orcid,display_name,display_name_alternatives,last_known_institutions,ids'
ER_CR_SELECT = 'DOI,title,subtitle,author,container-title,ISSN,issued,type,volume,issue,page,update-to,relation'
# ER_CAPS['dblp'] bounds the record byte range of one release excerpt (no network request is involved).
ER_CAPS = {'dblp': 200000, 'openalex-work': 400000, 'openalex-author': 100000, 'crossref': 400000}
# Pacing applies to the only two network hosts of the ER set. dblp has no entry: dblp records are never
# requested over the network (dblp.org robots.txt: Disallow /rec/xml, /*.xml, /*.json).
ER_PACE_S = {'openalex': 0.2, 'crossref': 0.5}
ER_UNIT_RE = re.compile(r'^[a-z0-9][a-z0-9-]{2,79}$')
ER_DOI_RE = re.compile(r'^10\.[0-9]{4,9}/[\x21-\x7e]+$')
ER_DBLP_KEY_RE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9_.-]*(/[A-Za-z0-9_.-]+)+$')
ER_OA_AUTHOR_RE = re.compile(r'^https://openalex\.org/(A[0-9]{4,12})$')
ER_OA_WORK_RE = re.compile(r'^https://openalex\.org/(W[0-9]{4,12})$')
ER_LEDGER_COLS = ['file', 'unit_id', 'split', 'criterion', 'source', 'kind', 'record_id', 'request_url',
                  'retrieved_at_utc', 'http_status', 'bytes', 'sha256', 'license', 'note']
ER_FAILURE_COLS = ['unit_id', 'split', 'criterion', 'source', 'kind', 'identifier', 'url', 'reason', 'at_utc']


def er_read_tsv(path):
    out = []
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.startswith('#'):
                continue
            out.append((n, line.split('\t')))
    return out


def er_load_selection(root):
    """-> (units {unit: criterion}, rows [dict], errors [str])"""
    units, rows, errors = {}, [], []
    seen_rec = {}
    unit_ids = {}
    for n, cols in er_read_tsv(os.path.join(root, ER_SELECTION_REL)):
        if cols and cols[0] == 'unit_id':
            continue
        if len(cols) != 7:
            errors.append('line %d: expected 7 tab-separated columns, got %d' % (n, len(cols)))
            continue
        unit, crit, src, kind, ident, edoi, eauth = cols
        if not ER_UNIT_RE.match(unit):
            errors.append('line %d: bad unit id %r' % (n, unit))
        if crit not in ER_CRITERIA:
            errors.append('line %d: unknown criterion %r' % (n, crit))
        if (src, kind) not in ER_KINDS:
            errors.append('line %d: unknown source/kind %s/%s' % (n, src, kind))
        if units.setdefault(unit, crit) != crit:
            errors.append('line %d: unit %s appears under two criteria' % (n, unit))
        if src == 'dblp':
            if not ER_DBLP_KEY_RE.match(ident):
                errors.append('line %d: bad dblp key %r' % (n, ident))
            elif '__' in ident:
                # The file name replaces "/" by "__", so a key holding "__" could share a file name with
                # another key (a/b__c and a__b/c). Refused rather than renamed (RV-P5-02 nit 10).
                errors.append('line %d: dblp key %r contains "__", so its file name (each / replaced by __) '
                              'would not be unique' % (n, ident))
        elif not ER_DOI_RE.match(ident):
            errors.append('line %d: bad DOI %r' % (n, ident))
        if edoi != '-' and not ER_DOI_RE.match(edoi):
            errors.append('line %d: bad expected DOI %r' % (n, edoi))
        if src != 'dblp' and (edoi != '-' or eauth != '-'):
            errors.append('line %d: expect_doi/expect_author apply to dblp rows only' % n)
        sig = (src, kind, ident.lower())
        if sig in seen_rec:
            errors.append('line %d: %s %s %s is listed twice (also line %d)' % (n, src, kind, ident, seen_rec[sig]))
        seen_rec[sig] = n
        # A record identifier (DOI or dblp key) belongs to exactly one selection unit.
        for rid in {ident.lower()} | ({edoi.lower()} if edoi != '-' else set()):
            if unit_ids.setdefault(rid, unit) != unit:
                errors.append('line %d: identifier %s is in units %s and %s' % (n, rid, unit_ids[rid], unit))
        rows.append({'line': n, 'unit': unit, 'criterion': crit, 'source': src, 'kind': kind, 'ident': ident,
                     'expect_doi': None if edoi == '-' else edoi, 'expect_author': None if eauth == '-' else eauth})
    for r in rows:
        if r['kind'] == 'authors-of-work' and not any(
                o['unit'] == r['unit'] and o['source'] == 'openalex' and o['kind'] == 'work-by-doi'
                and o['ident'].lower() == r['ident'].lower() for o in rows):
            errors.append('line %d: authors-of-work needs an openalex work-by-doi row for %s in unit %s'
                          % (r['line'], r['ident'], r['unit']))
    if not rows:
        errors.append('selection is empty')
    return units, rows, errors


def er_split_key(unit):
    return hashlib.sha256(('%s|%s' % (ER_SPLIT_SALT, unit)).encode('utf-8')).hexdigest()


def er_compute_split(units):
    """{unit: (criterion, split, rank, key)}. Within each criterion, units ranked by key; the first
    ceil(3n/5) are heldout. Depends only on the unit IDs, never on any record content or outcome."""
    by = {}
    for u, c in units.items():
        by.setdefault(c, []).append(u)
    out = {}
    for c in sorted(by):
        ranked = sorted(by[c], key=lambda u: (er_split_key(u), u))
        k = (3 * len(ranked) + 4) // 5
        for i, u in enumerate(ranked):
            out[u] = (c, 'heldout' if i < k else 'dev', i + 1, er_split_key(u))
    return out


def er_split_text(split):
    lines = [
        '# ScholarChain ER candidate split assignment (work order DS-P5-01). Owner: dataset-engineer.',
        '# Written by datasets/scripts/fetch-er-candidates.sh --sample BEFORE any record is fetched, and',
        '# recomputed by --verify. Rule: key = sha256("%s|" + unit_id) as lowercase hex.' % ER_SPLIT_SALT,
        '# Within each criterion, units are ranked by key; the first ceil(3n/5) are heldout, the rest dev.',
        '# A selection unit (name group or work cluster) is never divided between the two sets.',
        '# Policy development may use only dev. Human gold labels for evaluation come only from heldout.',
        'unit_id\tcriterion\tsplit\trank_in_criterion\tsplit_key_sha256',
    ]
    for u in sorted(split, key=lambda x: (ER_CRITERIA.index(split[x][0]), split[x][2])):
        c, s, r, k = split[u]
        lines.append('\t'.join([u, c, s, str(r), k]))
    return '\n'.join(lines) + '\n'


def er_safe_name(doi):
    d = doi.lower()
    return re.sub(r'[^a-z0-9._-]', '_', d)[:100] + '-' + hashlib.sha256(d.encode('utf-8')).hexdigest()[:8]


def er_quote(doi):
    return urllib.parse.quote(doi, safe='/')


def er_item(row, split):
    src, kind, ident = row['source'], row['kind'], row['ident']
    base = '%s/%s/' % (ER_SAMPLES_REL, split)
    it = dict(row, split=split)
    if src == 'dblp':
        # Offline only: extracted from the local pinned release by er_dblp_step. No URL exists for it, so
        # run() (the only caller of er_curl) can never request a dblp record.
        it.update(urls=[], rel=base + 'dblp/' + ident.replace('/', '__') + '.xml', cap=ER_CAPS['dblp'],
                  accept=None, host='dblp-release')
    elif src == 'openalex' and kind == 'work-by-doi':
        it.update(urls=['https://api.openalex.org/works/doi:%s?select=%s' % (er_quote(ident), ER_OA_WORK_SELECT)],
                  rel=base + 'openalex-works/' + er_safe_name(ident) + '.json', cap=ER_CAPS['openalex-work'],
                  accept='application/json', host='openalex')
    elif src == 'openalex' and kind == 'author':
        it.update(urls=['https://api.openalex.org/authors/%s?select=%s' % (ident, ER_OA_AUTHOR_SELECT)],
                  rel=base + 'openalex-authors/' + ident + '.json', cap=ER_CAPS['openalex-author'],
                  accept='application/json', host='openalex')
    elif src == 'crossref':
        it.update(urls=['https://api.crossref.org/works?filter=doi:%s&rows=2&select=%s' % (er_quote(ident), ER_CR_SELECT)],
                  rel=base + 'crossref-works/' + er_safe_name(ident) + '.json', cap=ER_CAPS['crossref'],
                  accept='application/json', host='crossref')
    else:
        raise ValueError('no direct request for %s/%s' % (src, kind))
    return it


def er_check_content(item, data):
    """Checks one response body. Returns (record_id, note); raises ValueError(reason) when the body is
    not the requested record or a dblp selection fact does not hold. Never modifies the body."""
    src = item['source']
    if src == 'dblp':
        low = data[:2048].lower()
        if b'<html' in low or b'<!doctype html' in low:
            raise ValueError('html page instead of XML (bot protection?)')
        try:
            tree = ET.fromstring(_ENTITY_RE.sub(_entity_to_numeric, data))
        except ET.ParseError as e:
            raise ValueError('XML not well-formed: %s' % e)
        if tree.tag != 'dblp':
            raise ValueError('root element is %s, expected dblp' % tree.tag)
        recs = [el for el in tree if el.get('key')]
        if len(recs) != 1 or recs[0].get('key') != item['ident']:
            raise ValueError('expected exactly one record with key %s' % item['ident'])
        rec = recs[0]
        if item.get('expect_doi'):
            want = 'https://doi.org/' + item['expect_doi'].lower()
            if want not in [(e.text or '').strip().lower() for e in rec.iter('ee')]:
                raise ValueError('selection check failed: no ee %s' % want)
        if item.get('expect_author'):
            names = [''.join(a.itertext()).strip() for a in rec if a.tag in ('author', 'editor')]
            if item['expect_author'] not in names:
                raise ValueError('selection check failed: no author %r' % item['expect_author'])
        return item['ident'], ''
    try:
        obj = json.loads(data.decode('utf-8'))
    except (UnicodeDecodeError, ValueError) as e:
        raise ValueError('not JSON: %s' % e)
    if not isinstance(obj, dict):
        raise ValueError('JSON body is not an object')
    if src == 'openalex' and item['kind'] == 'author':
        m = ER_OA_AUTHOR_RE.match(str(obj.get('id', '')))
        if not m or m.group(1) != item['ident']:
            raise ValueError('not the OpenAlex author %s' % item['ident'])
        return m.group(1), ''
    if src == 'openalex':
        m = ER_OA_WORK_RE.match(str(obj.get('id', '')))
        if not m:
            raise ValueError('no OpenAlex work id in the body')
        if 'abstract_inverted_index' in obj:
            raise ValueError('abstract present (not requested)')
        got = str(obj.get('doi') or '').lower()
        note = '' if got == 'https://doi.org/' + item['ident'].lower() else 'openalex doi field: %s' % (got or 'null')
        return m.group(1), note
    msg = obj.get('message') if obj.get('status') == 'ok' else None
    items = msg.get('items') if isinstance(msg, dict) else None
    if not isinstance(items, list) or len(items) != 1 or not isinstance(items[0], dict):
        raise ValueError('expected exactly one Crossref item')
    if str(items[0].get('DOI', '')).lower() != item['ident'].lower():
        raise ValueError('Crossref item DOI %r differs' % items[0].get('DOI'))
    if 'abstract' in items[0]:
        raise ValueError('abstract present (not requested)')
    return items[0]['DOI'], ''


def er_author_ids(path):
    with open(path, 'rb') as f:
        obj = json.loads(f.read().decode('utf-8'))
    out = []
    for a in obj.get('authorships') or []:
        m = ER_OA_AUTHOR_RE.match(str(((a or {}).get('author') or {}).get('id') or ''))
        if m and m.group(1) not in out:
            out.append(m.group(1))
    return out[:ER_MAX_AUTHORS_PER_WORK]


def er_read_ledger(root):
    p = os.path.join(root, ER_LEDGER_REL)
    rows = {}
    if not os.path.isfile(p):
        return rows
    for n, cols in er_read_tsv(p):
        if cols[0] == 'file':
            continue
        if len(cols) != len(ER_LEDGER_COLS):
            raise ValueError('%s line %d: expected %d columns' % (ER_LEDGER_REL, n, len(ER_LEDGER_COLS)))
        rows[cols[0]] = dict(zip(ER_LEDGER_COLS, cols))
    return rows


def er_tsv_cell(v):
    return re.sub(r'[\t\r\n]+', ' ', str(v)) if v not in (None, '') else '-'


def er_now():
    return datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def er_curl(url, tmp, cap, accept):
    # -q must be the first argument: curl then ignores ~/.curlrc (RV-P5-07 NIT-5).
    cmd = ['curl', '-q', '--silent', '--show-error', '--location', '--proto', '=https', '--proto-redir', '=https',
           '--max-filesize', str(cap), '--max-time', '60', '-A', ER_UA, '-H', 'Accept: ' + accept,
           '-o', tmp, '--write-out', '%{http_code}', url]
    try:
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
    except subprocess.TimeoutExpired:
        return 124, '000', 'timeout'
    return r.returncode, r.stdout.decode('ascii', 'replace').strip(), r.stderr.decode('utf-8', 'replace').strip()


# dblp records: OFFLINE ONLY (work order DS-P5-03). dblp.org robots.txt (read 2026-11-05) disallows
# /rec/xml, /*.xml and /*.json, so no code path requests a per-record dblp URL. Each dblp record is
# extracted from the pinned release that the human downloads with the human-only
# "fetch-dblp.sh --full" (MD5 checked against the manifest pin, sha256 sidecar written). Construction
# rule of one excerpt file, all bytes of the DECOMPRESSED release stream, unmodified:
#   header = bytes [0, H): every line up to and including the first line whose stripped text is "<dblp>"
#   record = bytes [R, R+L): the record's lines, from the line that STARTS with "<type key=..." to the
#            line that ENDS with "</type>"
#   footer = bytes [F, F+N): the last non-blank line of the stream, which must be "</dblp>"
#   file   = header + record + footer
# Every range is written to DBLP-RELEASE-RANGES.tsv together with the release id, MD5 and the sha256 of
# the .gz, and the sha256 of the resulting file, so the excerpt can be rebuilt from the release alone.
# Failure modes (each makes no network request and writes no dblp file):
#   release-missing, release-sidecar-missing, release-checksum-mismatch, release-pin-mismatch,
#   release-layout-error (whole release refused); key-not-in-pinned-release, record-exceeds-cap,
#   excerpt-check-failed (that record only). A missing record is never substituted by any other source.
ER_DBLP_RELEASE_ID = 'dblp-2026-10-03'
ER_DBLP_RELEASE_DIR_REL = 'datasets/downloads/dblp/full'
ER_RANGES_REL = ER_SAMPLES_REL + '/DBLP-RELEASE-RANGES.tsv'
ER_RANGES_COLS = ['file', 'record_key', 'release_id', 'release_file', 'release_md5', 'release_gz_sha256',
                  'header_offset', 'header_length', 'record_offset', 'record_length', 'footer_offset',
                  'footer_length', 'excerpt_bytes', 'excerpt_sha256']
ER_RANGE_INT_COLS = ER_RANGES_COLS[6:13]
ER_DBLP_TYPES = rb'(article|inproceedings|proceedings|book|incollection|phdthesis|mastersthesis|www|data)'
ER_DBLP_START = re.compile(rb'^<' + ER_DBLP_TYPES + rb'\s[^>]*\bkey="([^"]+)"')
ER_DBLP_ANY_START = re.compile(rb'<' + ER_DBLP_TYPES + rb'\s[^>]*\bkey="([^"]+)"')
ER_HEADER_MAX = 65536
MD5_RE = re.compile(r'^[0-9a-f]{32}$')


class ErReleaseError(Exception):
    """The release as a whole cannot be used. The message starts with the failure-mode token."""


def er_dblp_pin(root):
    """Pinned release values from the manifest. -> dict(id, md5, file, rel). Raises ErReleaseError."""
    try:
        d = load_manifest(root)['datasets']['dblp']
        sid = d['snapshot']['id']
        md5 = d['integrity']['upstream_checksum']['value']
        url = d['official_source']['download_url']
    except (OSError, YamlError, KeyError, TypeError) as e:
        raise ErReleaseError('release-pin-mismatch: cannot read the dblp pin from the manifest (%s)' % e)
    if sid != ER_DBLP_RELEASE_ID:
        raise ErReleaseError('release-pin-mismatch: the manifest pins %s, but the ER excerpts are bound to %s. '
                             'Re-pinning needs a human decision and a dataset-engineer work order.'
                             % (sid, ER_DBLP_RELEASE_ID))
    if not (isinstance(md5, str) and MD5_RE.match(md5)):
        raise ErReleaseError('release-pin-mismatch: manifest MD5 pin %r is not 32 lowercase hex' % (md5,))
    name = str(url).rsplit('/', 1)[-1]
    if not name.startswith(sid) or not name.endswith('.xml.gz'):
        raise ErReleaseError('release-pin-mismatch: download_url file %r does not belong to %s' % (name, sid))
    return {'id': sid, 'md5': md5, 'file': name, 'rel': ER_DBLP_RELEASE_DIR_REL + '/' + name}


class _HashingReader:
    """Read-only wrapper of an open binary file that hashes (MD5 and SHA-256) every byte read through it."""

    def __init__(self, f):
        self._f = f
        self.md5 = hashlib.md5()
        self.sha = hashlib.sha256()

    def read(self, n=-1):
        b = self._f.read(n)
        self.md5.update(b)
        self.sha.update(b)
        return b

    def readinto(self, buf):
        b = self.read(len(buf))
        buf[:len(b)] = b
        return len(b)

    def readable(self):
        return True

    def drain(self):
        for _ in iter(lambda: self.read(1 << 20), b''):
            pass


def er_dblp_read_release(path, pin_md5, consume=None, shown=None):
    """Offline integrity gate and decompressed read in ONE pass over the .gz bytes (RV-P5-02 nit 11).
    Every compressed byte that consume() causes to be read is hashed on the way, and the rest of the file is
    hashed after it, so the bytes that were decompressed are exactly the bytes whose MD5 and SHA-256 are
    checked. consume(gz) receives the decompressed stream as a binary file object and may raise ErReleaseError.
    The checksum result takes precedence: a checksum mismatch is reported even when consume() also failed, and
    consume()'s result is returned only when both digests match. -> (gz_sha256, md5, result).
    shown: the path printed in messages (callers pass the repository-relative pin path, so reasons written to
    FETCH-FAILURES.tsv carry no absolute path; RV-P5-07 NIT-3). Raises ErReleaseError. Never touches the network."""
    hint = ('Nothing was written and no network request was made. The release comes only from the human-only '
            'download (datasets/scripts/fetch-dblp.sh --full), which checks the MD5 and writes the sidecar.')
    name = shown or os.path.basename(path)
    if not os.path.isfile(path):
        raise ErReleaseError('release-missing: %s does not exist. %s' % (name, hint))
    side = path + '.sha256'
    if not os.path.isfile(side):
        raise ErReleaseError('release-sidecar-missing: %s.sha256 does not exist. %s' % (name, hint))
    with open(side, encoding='ascii', errors='replace') as f:
        want = f.read().strip()
    result, read_err = None, None
    with open(path, 'rb') as raw:
        hr = _HashingReader(raw)
        if consume is not None:
            try:
                with gzip.GzipFile(filename='', mode='rb', fileobj=hr) as gz:
                    result = consume(gz)
            except ErReleaseError as e:
                read_err = e
            except (OSError, EOFError, zlib.error) as e:
                read_err = ErReleaseError('release-layout-error: cannot decompress the release (%s). '
                                          'No dblp record was written.' % e)
        hr.drain()
    got_md5, got_sha = hr.md5.hexdigest(), hr.sha.hexdigest()
    if got_md5 != pin_md5:
        raise ErReleaseError('release-checksum-mismatch: MD5 of %s is %s, the manifest pin is %s. %s'
                             % (name, got_md5, pin_md5, hint))
    if got_sha != want:
        raise ErReleaseError('release-checksum-mismatch: SHA-256 of %s is %s, its sidecar says %r. %s'
                             % (name, got_sha, want, hint))
    if read_err is not None:
        raise read_err
    return got_sha, got_md5, result


def er_dblp_scan(f, keys):
    """One streaming pass over the decompressed release stream f (a binary file object).
    -> (header_bytes, (footer_offset, footer_bytes), {key: (record_offset, record_bytes)}).
    Raises ErReleaseError (release-layout-error) when the layout rule above does not hold for the release
    or for a wanted key. Keys that are simply absent are not an error here."""
    def bad(msg):
        raise ErReleaseError('release-layout-error: %s. No dblp record was written.' % msg)
    header, found, cur, off, in_header, last = bytearray(), {}, None, 0, True, None
    try:
        for line in f:
            if in_header:
                header += line
                if line.strip() == b'<dblp>':
                    in_header = False
                elif len(header) > ER_HEADER_MAX:
                    bad('no "<dblp>" line in the first %d bytes' % ER_HEADER_MAX)
            elif cur is None:
                if b'key="' in line:
                    m = ER_DBLP_START.match(line)
                    if m:
                        k = m.group(2).decode('utf-8', 'replace')
                        if k in keys:
                            if k in found:
                                bad('key %s occurs twice (offsets %d and %d)' % (k, found[k][0], off))
                            cur = [m.group(1), k, off, [line]]
                    else:
                        for m2 in ER_DBLP_ANY_START.finditer(line):
                            k2 = m2.group(2).decode('utf-8', 'replace')
                            if k2 in keys:
                                bad('the record tag of key %s is not at the start of a line (line at offset %d)'
                                    % (k2, off))
            else:
                if ER_DBLP_ANY_START.search(line):
                    bad('record %s (offset %d) has no end tag before the next record' % (cur[1], cur[2]))
                cur[3].append(line)
            if cur is not None:
                end = b'</' + cur[0] + b'>'
                if end in line:
                    if not line.rstrip(b'\r\n').endswith(end):
                        bad('text follows %s on the end line of record %s' % (end.decode('ascii'), cur[1]))
                    found[cur[1]] = (cur[2], b''.join(cur[3]))
                    cur = None
            if line.strip():
                last = (off, line)
            off += len(line)
    except (OSError, EOFError, zlib.error) as e:
        bad('cannot decompress the release (%s)' % e)
    if in_header:
        bad('no "<dblp>" line')
    if cur is not None:
        bad('record %s (offset %d) is not closed before the end of the stream' % (cur[1], cur[2]))
    if last is None or last[1].strip() != b'</dblp>':
        bad('the last non-blank line is not "</dblp>"')
    return bytes(header), last, found


def er_range_url(rng):
    """The PROVENANCE.tsv request_url cell of a release excerpt (same facts as its ranges row)."""
    return 'release:%s sha256=%s header=%d+%d record=%d+%d footer=%d+%d' % (
        rng['release_file'], rng['release_gz_sha256'], rng['header_offset'], rng['header_length'],
        rng['record_offset'], rng['record_length'], rng['footer_offset'], rng['footer_length'])


def er_dblp_excerpts(scan, gz_sha, md5, pin, items):
    """scan = the result of er_dblp_scan over the verified release.
    -> [(item, (data, record_id, ranges_row) or None, failure_reason or None)]. Writes nothing."""
    header, (foot_off, footer), found = scan
    out = []
    for it in items:
        if it['ident'] not in found:
            out.append((it, None, 'key-not-in-pinned-release: %s is not a record key in %s'
                        % (it['ident'], pin['id'])))
            continue
        off, rec = found[it['ident']]
        if len(rec) > it.get('cap', ER_CAPS['dblp']):
            out.append((it, None, 'record-exceeds-cap: %d bytes > %d' % (len(rec), it.get('cap', ER_CAPS['dblp']))))
            continue
        data = header + rec + footer
        try:
            rid, _ = er_check_content(it, data)
        except ValueError as e:
            out.append((it, None, 'excerpt-check-failed: %s' % e))
            continue
        rng = {'file': it['rel'], 'record_key': rid, 'release_id': pin['id'], 'release_file': pin['file'],
               'release_md5': md5, 'release_gz_sha256': gz_sha, 'header_offset': 0, 'header_length': len(header),
               'record_offset': off, 'record_length': len(rec), 'footer_offset': foot_off,
               'footer_length': len(footer), 'excerpt_bytes': len(data),
               'excerpt_sha256': hashlib.sha256(data).hexdigest()}
        out.append((it, (data, rid, rng), None))
    return out


def er_append_row(path, header_lines, cols, row):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    new = not os.path.exists(path)
    with open(path, 'a', encoding='utf-8', newline='\n') as f:
        if new:
            for h in header_lines:
                f.write(h + '\n')
            f.write('\t'.join(cols) + '\n')
        f.write('\t'.join(er_tsv_cell(row[c]) for c in cols) + '\n')


ER_LEDGER_HEAD = ['# ScholarChain ER candidate provenance ledger (DS-P5-01). One row per stored response body.']
ER_RANGES_HEAD = ['# ScholarChain ER dblp release byte ranges (DS-P5-03). One row per dblp excerpt file.',
                  '# Offsets and lengths are bytes of the DECOMPRESSED release stream. file = header + record + footer.',
                  '# Rule: datasets/schemas/er-candidates.md, "dblp release excerpts".']


class ErExcerptWriteError(Exception):
    """One excerpt could not be written consistently. The message starts with excerpt-write-failed."""


def er_write_part(path, data):
    """Writes the temporary .part file of one excerpt (module-level so the fixture can inject a fault)."""
    with open(path, 'wb') as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())


def er_tail_complete(path):
    """True when the file is empty or ends with a newline (no row torn by an interrupted append)."""
    with open(path, 'rb') as f:
        f.seek(0, os.SEEK_END)
        if f.tell() == 0:
            return True
        f.seek(-1, os.SEEK_END)
        return f.read(1) == b'\n'


class ErTornRowError(Exception):
    """The last line has no trailing newline but is not provably torn; the run must stop for a manual repair."""


def er_repair_torn_tail(path, ncols, shown=None):
    """Drops an incomplete last line (no trailing newline) left by an interrupted append, so that it is
    never read as a row and the next append cannot join it. The dropped text is returned for printing.
    Only a line with FEWER tab-separated columns than a row (ncols) is provably torn, and only such a line is
    dropped. A last line with ncols or more columns is either a complete row whose final newline was lost (for
    example by a hand edit) or a row cut INSIDE its last column (an interrupted append can stop there too), and
    the two cannot be told apart from the bytes. It is never dropped: ErTornRowError is raised and the file is
    left unchanged, and the message asks the human to compare the last column with the source before restoring
    the newline (RV-P5-03 N1, RV-P5-07 NIT-4). Atomic: temporary file + os.replace."""
    if not os.path.isfile(path) or er_tail_complete(path):
        return None
    with open(path, 'rb') as f:
        b = f.read()
    cut = b.rfind(b'\n') + 1
    n = b[cut:].count(b'\t') + 1
    if n >= ncols:
        raise ErTornRowError(
            'the last line of %s has no final newline but has %d tab-separated columns (a row has %d), so it may '
            'be a complete row, or a row cut inside its LAST column by an interrupted write. It was NOT dropped, '
            'the file was not changed, and no record was fetched or extracted. Check that line by hand, '
            'including its last column (in PROVENANCE.tsv that is the free-text note, which --verify cannot '
            'check): only if every column is complete, add the missing final newline; if you cannot confirm '
            'that, delete the line (and, for PROVENANCE.tsv, the file it names) so the record is fetched or '
            'extracted again. Then re-run --sample.' % (shown or os.path.basename(path), n, ncols))
    tmp = path + '.tmp'
    with open(tmp, 'wb') as f:
        f.write(b[:cut])
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
    return b[cut:].decode('utf-8', 'replace')


def er_ranges_put(write_root, rng):
    """Writes the DBLP-RELEASE-RANGES.tsv row of one excerpt, replacing any uncommitted row for the same
    file left by an interrupted earlier write. Atomic: the whole file is rewritten to a temporary file
    and renamed, so a crash leaves either the old or the new file."""
    p = os.path.join(write_root, ER_RANGES_REL)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    if os.path.isfile(p):
        with open(p, encoding='utf-8') as f:
            lines = f.read().split('\n')
        if lines and lines[-1] == '':
            lines.pop()
    else:
        lines = ER_RANGES_HEAD + ['\t'.join(ER_RANGES_COLS)]
    kept = [ln for ln in lines if ln.startswith('#') or ln.split('\t', 1)[0] != rng['file']]
    kept.append('\t'.join(er_tsv_cell(rng[c]) for c in ER_RANGES_COLS))
    tmp = p + '.tmp'
    with open(tmp, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(kept) + '\n')
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, p)


def er_ledger_commit(write_root, row):
    """Appends the PROVENANCE.tsv row of one excerpt. This append is the commit point: an excerpt counts as
    present only when this row, its ranges row and the file all agree (module-level for fault injection)."""
    er_append_row(os.path.join(write_root, ER_LEDGER_REL), ER_LEDGER_HEAD, ER_LEDGER_COLS, row)


def er_write_excerpt(write_root, it, data, rid, rng, ledger):
    """Writes one excerpt so that, from the next run's point of view, "file + ranges row + PROVENANCE row"
    is all-or-nothing (RV-P5-02 finding 4):
      1. the bytes go to <file>.part and are re-hashed there; on a mismatch the .part is removed and
         ErExcerptWriteError is raised, so no file that looks valid is left behind;
      2. os.replace(<file>.part, <file>);
      3. the ranges row is written atomically (replacing any uncommitted row for the same file);
      4. the PROVENANCE row is appended LAST: it is the commit point.
    A crash after 2 or 3 leaves a file and possibly a ranges row but no PROVENANCE row. er_fetch treats
    that state as not present and re-extracts the record (deterministic bytes from the pinned release)."""
    path = os.path.join(write_root, it['rel'])
    part = path + '.part'
    os.makedirs(os.path.dirname(path), exist_ok=True)
    er_write_part(part, data)
    sha = sha256_file(part)
    if sha != rng['excerpt_sha256']:
        os.remove(part)
        raise ErExcerptWriteError('excerpt-write-failed: the written bytes of %s hash to %s, the excerpt is %s; '
                                  'the .part file was removed and no ledger row was written. Re-run --sample.'
                                  % (it['rel'], sha, rng['excerpt_sha256']))
    os.replace(part, path)
    row = {'file': it['rel'], 'unit_id': it['unit'], 'split': it['split'], 'criterion': it['criterion'],
           'source': 'dblp', 'kind': it['kind'], 'record_id': rid, 'request_url': er_range_url(rng),
           'retrieved_at_utc': er_now(), 'http_status': '-', 'bytes': str(len(data)), 'sha256': sha,
           'license': ER_LICENSES['dblp'],
           'note': 'offline excerpt of the pinned release; byte ranges in DBLP-RELEASE-RANGES.tsv'}
    er_ranges_put(write_root, rng)
    er_ledger_commit(write_root, row)
    ledger[it['rel']] = {c: er_tsv_cell(row[c]) for c in ER_LEDGER_COLS}


def er_dblp_step(write_root, release_path, pin, items, ledger, st=None):
    """Offline-only dblp extraction. -> (extracted_count, [(item, reason)]). Writes nothing unless the
    release passes the MD5, sidecar and layout checks; never makes a network request. The release is hashed
    and scanned in one pass, and nothing is written before both digests match (RV-P5-02 nit 11).
    st: the run state of er_fetch. When given, each excerpt is counted against the one ER_MAX_TOTAL_BYTES
    sample budget BEFORE it is written (st['total'] holds the bytes already in the ledger plus the bytes
    written in this run); an excerpt that would exceed it fails with byte-budget-reached (RV-P5-02 finding 3)."""
    if not items:
        return 0, []
    try:
        keys = {it['ident'] for it in items}
        gz_sha, md5, scan = er_dblp_read_release(release_path, pin['md5'], lambda gz: er_dblp_scan(gz, keys),
                                                 shown=pin.get('rel'))
        print('dblp release verified offline (hashed and scanned in one pass): %s md5 %s sha256 %s'
              % (pin['file'], md5, gz_sha))
        results = er_dblp_excerpts(scan, gz_sha, md5, pin, items)
    except ErReleaseError as e:
        print('ERROR dblp release: %s' % e)
        return 0, [(it, str(e)) for it in items]
    n, fails = 0, []
    for it, ok, reason in results:
        if ok is None:
            fails.append((it, reason))
            continue
        size = len(ok[0])
        if st is not None and st['total'] + size > ER_MAX_TOTAL_BYTES:
            fails.append((it, 'byte-budget-reached: writing this %d-byte excerpt would bring the ER sample to %d '
                              'bytes, over the %d-byte budget; nothing was written for it'
                          % (size, st['total'] + size, ER_MAX_TOTAL_BYTES)))
            continue
        try:
            er_write_excerpt(write_root, it, ok[0], ok[1], ok[2], ledger)
        except ErExcerptWriteError as e:
            print('ERROR dblp excerpt: %s' % e)
            fails.append((it, str(e)))
            continue
        if st is not None:
            st['total'] += size
        n += 1
        print('extracted %6d bytes %s' % (size, it['rel']))
    return n, fails


def er_read_ranges(root):
    p = os.path.join(root, ER_RANGES_REL)
    rows = {}
    if not os.path.isfile(p):
        return rows
    for n, cols in er_read_tsv(p):
        if cols[0] == 'file':
            continue
        if len(cols) != len(ER_RANGES_COLS):
            raise ValueError('%s line %d: expected %d columns' % (ER_RANGES_REL, n, len(ER_RANGES_COLS)))
        row = dict(zip(ER_RANGES_COLS, cols))
        for c in ER_RANGE_INT_COLS:
            if not re.match(r'^[0-9]+$', row[c]):
                raise ValueError('%s line %d: %s is not a byte count' % (ER_RANGES_REL, n, c))
            row[c] = int(row[c])
        if cols[0] in rows:
            raise ValueError('%s line %d: file %s listed twice' % (ER_RANGES_REL, n, cols[0]))
        rows[cols[0]] = row
    return rows


def er_check_ranges_offline(rep, root, ledger, ranges, pin):
    """Checks every dblp ledger row against its byte-range row and the stored file, without the release."""
    dblp_rows = {rel: r for rel, r in ledger.items() if r['source'] == 'dblp'}
    for rel in sorted(set(ranges) - set(dblp_rows)):
        rep.fail('er: %s' % ER_RANGES_REL, 'range row for %s has no dblp ledger row' % rel)
    for rel, row in sorted(dblp_rows.items()):
        what = 'er: %s byte ranges' % rel
        g = ranges.get(rel)
        if not rep.check(er_dblp_row_recorded(row, g), what + ' recorded',
                         'a dblp record must be a release excerpt with a row in ' + ER_RANGES_REL):
            continue
        problems = er_dblp_row_problems(root, rel, row, g, pin)
        rep.check(not problems, what + ' consistent (offline)', '; '.join(problems))


def er_dblp_row_recorded(row, g):
    return row['http_status'] == '-' and row['request_url'].startswith('release:') and g is not None


def er_dblp_state_problems(root, rel, row, g, pin):
    """Problems of one committed dblp excerpt (PROVENANCE row present). [] means present and consistent:
    ranges row, PROVENANCE row and file agree. Used by er_fetch before counting a record as present."""
    if not er_dblp_row_recorded(row, g):
        return ['no matching row in ' + ER_RANGES_REL if g is None else 'the PROVENANCE row is not a release excerpt']
    return er_dblp_row_problems(root, rel, row, g, pin)


def er_dblp_row_problems(root, rel, row, g, pin):
    """Offline consistency of one dblp PROVENANCE row, its ranges row g and the stored file. -> [problem]."""
    p = os.path.join(root, rel)
    data = open(p, 'rb').read() if os.path.isfile(p) else b''
    H, R, L, F, N = (g['header_length'], g['record_offset'], g['record_length'], g['footer_offset'],
                     g['footer_length'])
    problems = []
    if not os.path.isfile(p):
        problems.append('the excerpt file is missing')
    if g['release_id'] != pin['id'] or g['release_file'] != pin['file'] or g['release_md5'] != pin['md5']:
        problems.append('release id, file or MD5 differs from the pin %s/%s/%s' % (pin['id'], pin['file'], pin['md5']))
    if not SHA256_RE.match(g['release_gz_sha256']):
        problems.append('release_gz_sha256 is not a sha256')
    if g['header_offset'] != 0 or R < H or F < R + L:
        problems.append('ranges are not ordered header < record < footer')
    if H + L + N != g['excerpt_bytes'] or str(g['excerpt_bytes']) != row['bytes'] or len(data) != g['excerpt_bytes']:
        problems.append('lengths do not add up to the file size')
    if g['excerpt_sha256'] != row['sha256'] or hashlib.sha256(data).hexdigest() != g['excerpt_sha256']:
        problems.append('excerpt sha256 differs from the ledger or the file')
    if g['record_key'] != row['record_id']:
        problems.append('record key differs from the ledger')
    if row['request_url'] != er_range_url(g):
        problems.append('PROVENANCE request_url does not state the same ranges')
    if not problems:
        hl = data[:H].rstrip(b'\r\n').rsplit(b'\n', 1)[-1].strip()
        m = ER_DBLP_START.match(data[H:H + L])
        if hl != b'<dblp>' or not m or m.group(2).decode('utf-8', 'replace') != g['record_key'] \
                or data[H + L:].strip() != b'</dblp>':
            problems.append('file slices do not follow the header/record/footer rule')
    return problems


def er_check_ranges_release(rep, root, ranges, release_path, pin):
    """--with-release: re-reads every recorded range from the local release and compares the bytes. The
    release is hashed and read in one pass (RV-P5-02 nit 11). -> True when the release was read and passed
    the MD5 / sidecar gate, False otherwise (RV-P5-02 finding 8)."""
    want = {}
    for g in ranges.values():
        for part in ('header', 'record', 'footer'):
            want[(g[part + '_offset'], g[part + '_length'])] = bytearray()
    stop = max(s + n for s, n in want) if want else 0

    def consume(f):
        pos = 0
        while pos < stop:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            end = pos + len(chunk)
            for (s, n), buf in want.items():
                if s < end and s + n > pos:
                    buf += chunk[max(s, pos) - pos:min(s + n, end) - pos]
            pos = end
    try:
        gz_sha, md5, _ = er_dblp_read_release(release_path, pin['md5'], consume, shown=pin.get('rel'))
    except ErReleaseError as e:
        rep.fail('er: --with-release', str(e))
        return False
    rep.ok('er: --with-release: %s md5 matches the pin and sha256 matches its sidecar (%s)' % (pin['file'], gz_sha))
    if not ranges:
        print('INFO er: --with-release: no dblp byte ranges recorded yet (the release itself was read and verified)')
        return True
    for rel, g in sorted(ranges.items()):
        rep.check(g['release_gz_sha256'] == gz_sha, 'er: %s recorded release sha256' % rel,
                  'recorded %s, local release %s' % (g['release_gz_sha256'], gz_sha))
    for rel, g in sorted(ranges.items()):
        if g['release_gz_sha256'] != gz_sha:
            continue
        p = os.path.join(root, rel)
        data = open(p, 'rb').read() if os.path.isfile(p) else b''
        parts = [bytes(want[(g[k + '_offset'], g[k + '_length'])]) for k in ('header', 'record', 'footer')]
        ok = all(len(b) == g[k + '_length'] for b, k in zip(parts, ('header', 'record', 'footer'))) \
            and b''.join(parts) == data
        rep.check(ok, 'er: %s equals release bytes header %d+%d, record %d+%d, footer %d+%d' % (
            rel, g['header_offset'], g['header_length'], g['record_offset'], g['record_length'],
            g['footer_offset'], g['footer_length']), 'the release bytes at the recorded offsets differ from the file')
    return True


def er_plan(root):
    units, rows, errors = er_load_selection(root)
    if errors:
        return None, None, None, errors
    split = er_compute_split(units)
    direct = [er_item(r, split[r['unit']][1]) for r in rows if r['kind'] != 'authors-of-work']
    derived = [dict(r, split=split[r['unit']][1]) for r in rows if r['kind'] == 'authors-of-work']
    # Two planned records must never share a file (RV-P5-02 nit 10). Compared case-insensitively, because
    # the default macOS file system is case-insensitive.
    owner = {}
    for it in direct:
        o = owner.setdefault(it['rel'].lower(), it)
        if o is not it:
            errors.append('%s %s (unit %s) and %s %s (unit %s) map to the same file %s'
                          % (o['source'], o['ident'], o['unit'], it['source'], it['ident'], it['unit'], it['rel']))
    if errors:
        return None, None, None, errors
    return split, direct, derived, []


def er_fetch(root, mode):
    split, direct, derived, errors = er_plan(root)
    if errors:
        for e in errors:
            print('ERROR selection: %s' % e)
        return 2
    units = {}
    for u, (c, s, _, _) in split.items():
        units.setdefault((c, s), []).append(u)
    for c in ER_CRITERIA:
        print('criterion %-20s heldout %2d  dev %2d' % (c, len(units.get((c, 'heldout'), [])), len(units.get((c, 'dev'), []))))
    if mode == 'dry-run':
        try:
            pin = er_dblp_pin(root)
            rel_desc = 'release:%s (offline extraction from %s; no network)' % (pin['file'], pin['rel'])
        except ErReleaseError as e:
            rel_desc = 'release: UNUSABLE PIN (%s)' % e
        for it in direct:
            where = rel_desc if it['source'] == 'dblp' else it['urls'][0]
            print('\t'.join([it['unit'], it['split'], it['source'], it['kind'], it['ident'], where, it['rel']]))
        for r in derived:
            print('\t'.join([r['unit'], r['split'], 'openalex', 'authors-of-work', r['ident'],
                             'https://api.openalex.org/authors/<A-ID>?select=' + ER_OA_AUTHOR_SELECT,
                             '(first %d author IDs of the fetched work, authorship order)' % ER_MAX_AUTHORS_PER_WORK]))
        print('planned direct requests: %d; authors-of-work rows: %d (expanded after the work is fetched)'
              % (len(direct), len(derived)))
        return 0
    sp = os.path.join(root, ER_SPLIT_REL)
    text = er_split_text(split)
    if os.path.exists(sp):
        with open(sp, encoding='utf-8') as f:
            if f.read() != text:
                print('ERROR: %s differs from the deterministic rule; refusing to fetch' % ER_SPLIT_REL)
                return 2
        print('split assignment unchanged: %s' % ER_SPLIT_REL)
    else:
        os.makedirs(os.path.dirname(sp), exist_ok=True)
        with open(sp, 'w', encoding='utf-8', newline='\n') as f:
            f.write(text)
        print('wrote split assignment before any fetch: %s' % ER_SPLIT_REL)
    # A row torn by an interrupted append (no trailing newline) is never read as a row (finding 4). Only a
    # line with fewer columns than a row is dropped; anything else stops the run unchanged (RV-P5-03 N1).
    for rel_f, ncols in ((ER_LEDGER_REL, len(ER_LEDGER_COLS)), (ER_RANGES_REL, len(ER_RANGES_COLS))):
        try:
            torn = er_repair_torn_tail(os.path.join(root, rel_f), ncols, rel_f)
        except ErTornRowError as e:
            print('ERROR: %s' % e)
            return 2
        if torn is not None:
            print('REPAIRED %s: dropped an incomplete last line left by an interrupted write (the record it '
                  'described is not counted as present and is re-extracted or re-fetched): %r' % (rel_f, torn[:200]))
    try:
        ledger = er_read_ledger(root)
        ranges = er_read_ranges(root)
    except ValueError as e:
        print('ERROR: %s' % e)
        return 2
    lp = os.path.join(root, ER_LEDGER_REL)
    os.makedirs(os.path.dirname(lp), exist_ok=True)
    if not os.path.exists(lp):
        with open(lp, 'w', encoding='utf-8', newline='\n') as f:
            f.write('\n'.join(ER_LEDGER_HEAD) + '\n')
            f.write('\t'.join(ER_LEDGER_COLS) + '\n')
    st = {'total': sum(int(r['bytes']) for r in ledger.values()), 'fetched': 0, 'present': 0,
          'start': time.time(), 'stopped': {}, 'last': {}}
    failures = []

    def fail(it, url, reason):
        failures.append([it['unit'], it['split'], it['criterion'], it['source'], it['kind'], it['ident'], url,
                         reason, er_now()])
        print('NOT FETCHED %s %s %s: %s' % (it['unit'], it['source'], it['ident'], reason))

    def run(it):
        if it['source'] == 'dblp' or it['host'] not in ER_PACE_S:
            raise RuntimeError('refusing a network request for %s %s: dblp records are offline-only'
                               % (it['source'], it['ident']))
        rel, path = it['rel'], os.path.join(root, it['rel'])
        if rel in ledger:
            if os.path.isfile(path) and sha256_file(path) == ledger[rel]['sha256']:
                st['present'] += 1
                return
            fail(it, '-', 'ledger row exists but the file is missing or differs; resolve by hand')
            return
        if time.time() - st['start'] > ER_TIME_BUDGET_S:
            fail(it, '-', 'run time budget reached; re-run --sample to resume')
            return
        if it['host'] in st['stopped']:
            fail(it, '-', 'skipped: ' + st['stopped'][it['host']])
            return
        if st['total'] >= ER_MAX_TOTAL_BYTES:
            fail(it, '-', 'byte budget %d reached' % ER_MAX_TOTAL_BYTES)
            return
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + '.part'
        for i, url in enumerate(it['urls']):
            wait = ER_PACE_S[it['host']] - (time.time() - st['last'].get(it['host'], 0.0))
            if wait > 0:
                time.sleep(wait)
            if os.path.exists(tmp):
                os.remove(tmp)
            rc, code, err = er_curl(url, tmp, it['cap'], it['accept'])
            st['last'][it['host']] = time.time()
            size = os.path.getsize(tmp) if os.path.exists(tmp) else 0
            st['total'] += size
            last = i == len(it['urls']) - 1
            if rc != 0 or code != '200':
                if os.path.exists(tmp):
                    os.remove(tmp)
                if code == '429':
                    st['stopped'][it['host']] = 'HTTP 429 from %s earlier in this run' % it['host']
                    fail(it, url, 'HTTP 429 (rate limited); host skipped for the rest of this run')
                    return
                if code == '404' or last:
                    if last and code in ('202', '403'):
                        st['stopped'][it['host']] = 'HTTP %s (bot challenge) from %s earlier in this run' % (code, it['host'])
                    fail(it, url, 'curl exit %d, HTTP %s %s' % (rc, code, err[:160]))
                    return
                continue
            with open(tmp, 'rb') as f:
                data = f.read()
            try:
                rid, note = er_check_content(it, data)
            except ValueError as e:
                os.remove(tmp)
                if 'html page' in str(e) and not last:
                    continue
                fail(it, url, str(e))
                return
            if it['source'] != 'dblp' and b'\n' in data.rstrip(b'\n'):
                note = (note + '; ' if note else '') + 'body spans several lines'
            os.replace(tmp, path)
            row = {'file': rel, 'unit_id': it['unit'], 'split': it['split'], 'criterion': it['criterion'],
                   'source': it['source'], 'kind': it['kind'], 'record_id': rid, 'request_url': url,
                   'retrieved_at_utc': er_now(), 'http_status': code, 'bytes': str(size),
                   'sha256': sha256_file(path), 'license': ER_LICENSES[it['source']], 'note': note}
            with open(lp, 'a', encoding='utf-8', newline='\n') as f:
                f.write('\t'.join(er_tsv_cell(row[c]) for c in ER_LEDGER_COLS) + '\n')
            ledger[rel] = {c: er_tsv_cell(row[c]) for c in ER_LEDGER_COLS}
            st['fetched'] += 1
            print('fetched %7d bytes %s' % (size, rel))
            return

    # dblp: offline extraction from the pinned release only (no network path exists for dblp records).
    # An excerpt counts as present only when its PROVENANCE row (the commit point), its ranges row and the
    # file all agree (RV-P5-02 finding 4). No PROVENANCE row: any file, .part or ranges row is the leftover
    # of an interrupted write and is replaced by re-extraction. A PROVENANCE row that disagrees with the
    # ranges row or the file is reported for repair and never counted as present.
    dblp_items = [it for it in direct if it['source'] == 'dblp']
    dblp_pending = []
    pin, pin_err = None, None
    if dblp_items:
        try:
            pin = er_dblp_pin(root)
        except ErReleaseError as e:
            pin_err = str(e)
            print('ERROR dblp release: %s' % e)
    for it in dblp_items:
        rel = it['rel']
        p = os.path.join(root, rel)
        if pin is None:
            fail(it, '-', pin_err)
        elif rel in ledger:
            probs = er_dblp_state_problems(root, rel, ledger[rel], ranges.get(rel), pin)
            if not probs:
                st['present'] += 1
            else:
                fail(it, '-', 'excerpt-state-inconsistent: %s. Not counted as present. Repair by hand: delete the '
                     'PROVENANCE.tsv row and the DBLP-RELEASE-RANGES.tsv row (if any) for %s and the file %s, then '
                     're-run --sample; the excerpt is re-extracted from the pinned release'
                     % ('; '.join(probs), rel, rel))
        else:
            left = [w for w, x in (('file', os.path.exists(p)), ('.part file', os.path.exists(p + '.part')),
                                   ('ranges row', rel in ranges)) if x]
            if left:
                print('RECOVER %s: no PROVENANCE row (uncommitted), leftover %s from an interrupted write; '
                      'it is not counted as present and is replaced by re-extraction' % (rel, ', '.join(left)))
            dblp_pending.append(it)
    if dblp_pending:
        if pin is not None:
            print('extracting %d dblp records offline from %s; no dblp network request is ever made'
                  % (len(dblp_pending), pin['rel']))
            # st: dblp excerpt bytes count against the one 8 MiB budget while writing (RV-P5-02 finding 3).
            n, dfails = er_dblp_step(root, os.path.join(root, pin['rel']), pin, dblp_pending, ledger, st)
            st['fetched'] += n
            for it, reason in dfails:
                fail(it, 'release:' + pin['file'], reason)
    for it in direct:
        if it['source'] != 'dblp':
            run(it)
    works = {(it['unit'], it['ident'].lower()): it for it in direct
             if it['source'] == 'openalex' and it['kind'] == 'work-by-doi'}
    for r in derived:
        w = works[(r['unit'], r['ident'].lower())]
        wpath = os.path.join(root, w['rel'])
        if w['rel'] not in ledger or not os.path.isfile(wpath):
            fail(dict(r, source='openalex', kind='authors-of-work'), '-', 'OpenAlex work not fetched; authors not expanded')
            continue
        for aid in er_author_ids(wpath):
            run(er_item(dict(r, kind='author', ident=aid), r['split']))
    fp = os.path.join(root, ER_FAILURES_REL)
    with open(fp, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Requests of the last --sample run that did not produce a stored record (DS-P5-01).\n')
        f.write('\t'.join(ER_FAILURE_COLS) + '\n')
        for row in failures:
            f.write('\t'.join(er_tsv_cell(v) for v in row) + '\n')
    print('fetched %d, already present %d, not fetched %d, bytes in ledger %d'
          % (st['fetched'], st['present'], len(failures), sum(int(r['bytes']) for r in ledger.values())))
    return 1 if failures else 0


ER_ORCID_IN_RE = re.compile(r'^(?:https?://(?:www\.)?orcid\.org/)?([0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9Xx])$')


def er_entity_key(v):
    """Canonical identifier key of one entity identifier, so that the same iD written in two forms compares
    equal across sources: 'openalex-author:A..' or 'orcid:0000-0000-0000-000X'. Anything else is kept as
    'unparsed:<value>' (still compared exactly). Names are never keys: a matching name is not identity."""
    s = str(v or '').strip()
    if not s:
        return None
    m = ER_OA_AUTHOR_RE.match(s)
    if m:
        return 'openalex-author:' + m.group(1)
    m = ER_ORCID_IN_RE.match(s)
    if m:
        return 'orcid:' + m.group(1).upper()
    return 'unparsed:' + s


def er_asserted_orcid_key(v):
    """Key of one value that a source asserts as an ORCID iD (a dblp orcid attribute or a Crossref author ORCID),
    normalised exactly like er_entity_key; a value that does not parse is kept as 'unparsed-orcid:<value>'."""
    k = er_entity_key(v)
    if k and k.startswith('unparsed:'):
        return 'unparsed-orcid:' + k[len('unparsed:'):]
    return k


def er_dblp_entity_keys(data):
    """ORCID iDs that a dblp excerpt asserts on its author and editor elements (the orcid attribute).
    Author name strings, including dblp homonym suffixes, are deliberately not used."""
    tree = ET.fromstring(_ENTITY_RE.sub(_entity_to_numeric, data))
    out = set()
    for el in tree.iter():
        if el.tag in ('author', 'editor') and el.get('orcid'):
            k = er_asserted_orcid_key(el.get('orcid'))
            if k:
                out.add(k)
    return out


def er_crossref_entity_keys(data):
    """ORCID iDs that a stored Crossref response asserts: message.items[*].author[*].ORCID (RV-P5-07 MINOR-2).
    Identifiers only: given, family, name, sequence and affiliation are deliberately not read."""
    obj = json.loads(data.decode('utf-8'))
    msg = obj.get('message') if isinstance(obj, dict) else None
    items = msg.get('items') if isinstance(msg, dict) else None
    out = set()
    for w in items if isinstance(items, list) else []:
        authors = w.get('author') if isinstance(w, dict) else None
        for a in authors if isinstance(authors, list) else []:
            if isinstance(a, dict) and a.get('ORCID'):
                k = er_asserted_orcid_key(a.get('ORCID'))
                if k:
                    out.add(k)
    return out


def er_verify(root, rep, with_release=False):
    """Offline checks of the ER candidate set (also run by validate). with_release: also re-read every
    recorded dblp byte range from the local pinned release (still offline).
    -> True when with_release is set and the release was read and passed its gate, False when it was read
    and failed, None when it was not read (cmd_er_verify then fails; RV-P5-02 finding 8)."""
    if not rep.check(os.path.isfile(os.path.join(root, ER_SELECTION_REL)), 'er: %s exists' % ER_SELECTION_REL,
                     'missing'):
        return
    split, direct, derived, errors = er_plan(root)
    for e in errors:
        rep.fail('er: selection', e)
    if errors:
        return
    sel_units = {u for u in split}
    rep.ok('er: selection parses: %d units, %d direct requests, %d authors-of-work rows'
           % (len(sel_units), len(direct), len(derived)))
    # Digest of the selected rows only (every line that is not a comment, byte for byte, line ends included), so a
    # comment-only edit of selection.tsv can be shown to leave the selection unchanged (DS-P5-06).
    with open(os.path.join(root, ER_SELECTION_REL), 'rb') as f:
        sel_rows = [ln for ln in f.read().splitlines(True) if not ln.startswith(b'#')]
    print('INFO er: selection.tsv non-comment lines: %d, sha256 %s (comment lines excluded)'
          % (len(sel_rows), hashlib.sha256(b''.join(sel_rows)).hexdigest()))
    for c in ER_CRITERIA:
        n = sum(1 for v in split.values() if v[0] == c)
        h = sum(1 for v in split.values() if v[0] == c and v[1] == 'heldout')
        print('INFO er: criterion %s: %d units (heldout %d, dev %d)' % (c, n, h, n - h))
    sp = os.path.join(root, ER_SPLIT_REL)
    base = os.path.join(root, ER_SAMPLES_REL)
    if not os.path.isfile(sp):
        rep.check(not os.path.exists(base), 'er: not fetched yet (no split file, no records)',
                  'records exist without a split assignment')
        return
    with open(sp, encoding='utf-8') as f:
        rep.check(f.read() == er_split_text(split), 'er: %s matches the deterministic rule' % ER_SPLIT_REL,
                  'differs from the rule; never edit it by hand')
    for rel_f in (ER_LEDGER_REL, ER_RANGES_REL):
        pf = os.path.join(root, rel_f)
        if os.path.isfile(pf):
            rep.check(er_tail_complete(pf), 'er: %s ends with a complete row' % rel_f,
                      'the last line has no newline (torn by an interrupted write or a hand edit); the next '
                      '--sample drops it only if it has fewer columns than a row, otherwise it stops for a '
                      'manual repair')
    try:
        ledger = er_read_ledger(root)
        ranges = er_read_ranges(root)
    except ValueError as e:
        rep.fail('er: ledger', str(e))
        return
    try:
        pin = er_dblp_pin(root)
    except ErReleaseError as e:
        rep.fail('er: dblp release pin', str(e))
        return
    rep.ok('er: dblp records are bound to the pinned release %s (md5 %s)' % (pin['id'], pin['md5']))
    er_check_ranges_offline(rep, root, ledger, ranges, pin)
    released = None
    if with_release:
        released = er_check_ranges_release(rep, root, ranges, os.path.join(root, pin['rel']), pin)
    by_key ={(r['unit'], r['source'], r['kind'], r['ident'].lower()): r for r in direct}
    total = 0
    per = {}
    ids = {'dev': {}, 'heldout': {}}
    for rel, row in sorted(ledger.items()):
        p = os.path.join(root, rel)
        what = 'er: %s' % rel
        if not rep.check(os.path.isfile(p), what + ' exists', 'listed in the ledger but missing'):
            continue
        u = row['unit_id']
        ok = u in split and row['split'] == split[u][1] and row['criterion'] == split[u][0] \
            and rel.startswith('%s/%s/' % (ER_SAMPLES_REL, row['split']))
        rep.check(ok, what + ' is in its unit\'s split directory', 'unit, criterion or split disagrees with the rule')
        size = os.path.getsize(p)
        total += size
        rep.check(str(size) == row['bytes'] and sha256_file(p) == row['sha256'], what + ' sha256 and size',
                  'file differs from its provenance row')
        rep.check(row['license'] == er_tsv_cell(ER_LICENSES.get(row['source'], '?')), what + ' licence recorded',
                  'unexpected licence text')
        if row['kind'] == 'author':
            it = {'source': 'openalex', 'kind': 'author', 'ident': row['record_id']}
        else:
            ident = None
            for (uu, s, k, i), r in by_key.items():
                if uu == u and s == row['source'] and k == row['kind'] and r['rel'] == rel:
                    ident = r
            if not rep.check(ident is not None, what + ' is planned by selection.tsv', 'not in the selection'):
                continue
            it = ident
        with open(p, 'rb') as f:
            data = f.read()
        try:
            er_check_content(it, data)
            rep.ok(what + ' content is the requested record')
        except ValueError as e:
            rep.fail(what + ' content', str(e))
            continue
        key = (row['split'], row['source'] if row['kind'] != 'author' else 'openalex-author')
        per[key] = per.get(key, 0) + 1
        if row['source'] == 'openalex':
            obj = json.loads(data.decode('utf-8'))
            for a in (obj.get('authorships') or []) if row['kind'] != 'author' else [{'author': obj}]:
                au = (a or {}).get('author') or {}
                for v in (au.get('id'), au.get('orcid')):
                    k = er_entity_key(v)
                    if k:
                        ids[row['split']].setdefault(k, set()).add(u)
        elif row['source'] == 'dblp':
            # dblp excerpts join the cross-split check through the ORCID iDs they assert (RV-P5-02 finding 6).
            for k in er_dblp_entity_keys(data):
                ids[row['split']].setdefault(k, set()).add(u)
        elif row['source'] == 'crossref':
            # Crossref works join it through the ORCID iDs of their author entries (RV-P5-07 MINOR-2).
            for k in er_crossref_entity_keys(data):
                ids[row['split']].setdefault(k, set()).add(u)
    on_disk = set()
    for dp, dns, fns in os.walk(base):
        dns[:] = [d for d in dns if d not in OS_JUNK_DIRS]
        for fn in fns:
            rel = os.path.relpath(os.path.join(dp, fn), root).replace(os.sep, '/')
            if not is_os_junk(fn) and rel not in (ER_LEDGER_REL, ER_FAILURES_REL, ER_RANGES_REL):
                on_disk.add(rel)
    for rel in sorted(on_disk - set(ledger)):
        rep.fail('er: %s' % rel, 'file without a provenance ledger row (partial download?)')
    rep.check(total <= ER_MAX_TOTAL_BYTES, 'er: total %d bytes <= %d' % (total, ER_MAX_TOTAL_BYTES), 'too large')
    planned = {it['rel'] for it in direct}
    missing = sorted(planned - set(ledger))
    print('INFO er: stored files %d (bytes %d); planned direct requests without a stored record: %d'
          % (len(ledger), total, len(missing)))
    for rel in missing:
        print('INFO er: not stored: %s' % rel)
    for (s, src), n in sorted(per.items()):
        print('INFO er: %s %s: %d records' % (s, src, n))
    # RV-P5-02 finding 6 and RV-P5-07 MINOR-2: an entity identifier (OpenAlex author ID or ORCID iD, from OpenAlex
    # records, Crossref author ORCIDs and dblp excerpts) that occurs in both splits FAILS verification. The facts
    # are listed first; the remedy is a human decision on the selection, never an edit of the split assignment.
    both = sorted(set(ids['dev']) & set(ids['heldout']))
    for v in both:
        print('INFO er:   %s dev=%s heldout=%s' % (v, ','.join(sorted(ids['dev'][v])), ','.join(sorted(ids['heldout'][v]))))
    rep.check(not both, 'er: no OpenAlex author ID or ORCID iD (OpenAlex records, Crossref works and dblp excerpts) '
              'occurs in both dev and heldout (%d distinct IDs checked)' % len(set(ids['dev']) | set(ids['heldout'])),
              '%d ID(s) occur in both splits (listed above): the entity straddles the dev/heldout split. This needs '
              'a human decision on selection.tsv; never edit split-assignment.tsv by hand' % len(both))
    return released


def cmd_er_verify(root, with_release=False):
    rep = Report()
    released = er_verify(root, rep, with_release)
    if with_release and released is None:
        rep.fail('er: --with-release', 'the pinned release was not read, because verification stopped before the '
                 'release check (see above); --with-release never passes without reading the release')
    print('er-verify%s: %d checks, %d failures' % (' --with-release' if with_release else '', rep.checks, rep.failures))
    return 1 if rep.failures else 0


# --------------------------------------------------------------------------- dblp extractor fixture
#
# Offline self-test of the release extractor (DS-P5-03). The committed fixture-release.xml holds the four
# records of datasets/samples/dblp/dblp-sample.xml in the dblp release layout (header lines, one record
# element per line group, "</dblp>" last). It is gzipped into a temporary directory, so nothing is written
# to the repository. The expectations below are about EXTRACTION (bytes, offsets, refusals), not about
# entity resolution: no match label or outcome exists here.
ER_FIXTURE_XML_REL = ER_DIR_REL + '/fixture/fixture-release.xml'
ER_FIXTURE_ABSENT_KEY = 'journals/tjs/CamaraCB26'   # a real selection.tsv key that the fixture does not hold
ER_FIXTURE_ITEMS = [
    ('journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13', '10.1145/2491245', 'Jeffrey Dean'),
    ('journals/cacm/PopperMP21', '10.1145/3447741', 'Christina Pöpper'),
    ('journals/cacm/Varajao21', '10.1145/3453932', 'João Varajão'),
    ('conf/osdi/ChangDGHWBCFG06', None, 'Fay Chang'),
    (ER_FIXTURE_ABSENT_KEY, None, None),
]


def _fx_items():
    out = []
    for i, (k, doi, auth) in enumerate(ER_FIXTURE_ITEMS):
        out.append({'unit': 'fixture-%d' % (i + 1), 'criterion': 'ordinary', 'source': 'dblp', 'kind': 'rec',
                    'ident': k, 'expect_doi': doi, 'expect_author': auth, 'split': 'dev', 'cap': ER_CAPS['dblp'],
                    'rel': '%s/dev/dblp/%s.xml' % (ER_SAMPLES_REL, k.replace('/', '__'))})
    return out


def _fx_release(d, name, raw, sidecar=True, sidecar_value=None, truncate=None):
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, name)
    with open(p, 'wb') as f:
        with gzip.GzipFile(filename='', mode='wb', fileobj=f, mtime=0) as z:
            z.write(raw)
    if truncate is not None:
        with open(p, 'rb') as f:
            b = f.read()
        with open(p, 'wb') as f:
            f.write(b[:truncate])
    if sidecar:
        with open(p + '.sha256', 'w', encoding='ascii') as f:
            f.write((sidecar_value or sha256_file(p)) + '\n')
    with open(p, 'rb') as f:
        md5 = hashlib.md5(f.read()).hexdigest()
    return p, md5


def _fx_pin(md5):
    return {'id': 'fixture-release', 'md5': md5, 'file': 'fixture-release.xml.gz', 'rel': '(temporary directory)'}


def _fx_nothing_written(root_dir):
    return not os.path.exists(os.path.join(root_dir, ER_SAMPLES_REL))


def _fx_expect_refusal(rep, d, p, pin, name, token, needle):
    wr = os.path.join(d, 'out')
    n, fails = er_dblp_step(wr, p, pin, _fx_items(), {})
    reasons = sorted({r for _, r in fails})
    ok = n == 0 and len(fails) == len(ER_FIXTURE_ITEMS) and all(r.startswith(token + ':') for r in reasons) \
        and all(needle in r for r in reasons) and _fx_nothing_written(wr)
    rep.check(ok, 'fixture %s: fails closed with %s, %d of %d items refused, nothing written'
              % (name, token, len(fails), len(ER_FIXTURE_ITEMS)),
              'extracted %d, reasons %r' % (n, reasons))
    if reasons:
        print('INFO fixture %s: reason: %s' % (name, reasons[0]))


def _fx_line_span(raw, key):
    """Independent offset computation (not via er_dblp_scan): start of the line holding key="...", and
    the end of the line holding the record's end tag."""
    k = raw.index(b'key="' + key.encode('ascii') + b'"')
    s = raw.rindex(b'\n', 0, k) + 1
    tag = re.match(rb'<([a-z]+)', raw[s:]).group(1)
    e = raw.index(b'</' + tag + b'>', k)
    e = raw.index(b'\n', e) + 1
    return s, e


def _er_fixture_cases(rep, root, raw, tmp):
    items = _fx_items()
    _, sel_rows, sel_err = er_load_selection(root)
    rep.check(not sel_err and any(r['ident'] == ER_FIXTURE_ABSENT_KEY for r in sel_rows),
              'fixture: the absent-key probe %s is a real selection.tsv key' % ER_FIXTURE_ABSENT_KEY,
              'not in selection.tsv')
    rep.check(ER_FIXTURE_ABSENT_KEY.encode('ascii') not in raw, 'fixture: %s is absent from the fixture release'
              % ER_FIXTURE_ABSENT_KEY, 'present')
    print('INFO fixture: fixture-release.xml %d bytes sha256 %s' % (len(raw), hashlib.sha256(raw).hexdigest()))

    # 1. Good release: extraction, offsets, offline range check, --with-release check.
    good = os.path.join(tmp, 'good')
    p, md5 = _fx_release(os.path.join(good, 'release'), 'fixture-release.xml.gz', raw)
    pin = _fx_pin(md5)
    print('INFO fixture good: gz md5 %s sha256 %s' % (md5, sha256_file(p)))
    wr = os.path.join(good, 'out')
    ledger = {}
    n, fails = er_dblp_step(wr, p, pin, items, ledger)
    rep.check(n == 4, 'fixture good: 4 records extracted', 'extracted %d' % n)
    rep.check(len(fails) == 1 and fails[0][0]['ident'] == ER_FIXTURE_ABSENT_KEY
              and fails[0][1].startswith('key-not-in-pinned-release:'),
              'fixture good: absent key %s fails with key-not-in-pinned-release, no substitute' % ER_FIXTURE_ABSENT_KEY,
              'failures %r' % [(f[0]['ident'], f[1]) for f in fails])
    if fails:
        print('INFO fixture good: reason: %s' % fails[0][1])
    absent_rel = os.path.join(wr, items[-1]['rel'])
    rep.check(not os.path.exists(absent_rel), 'fixture good: no file written for the absent key', 'file exists')
    ranges = er_read_ranges(wr)
    rep.check(len(ranges) == 4, 'fixture good: 4 byte-range rows written', 'got %d' % len(ranges))
    h_end = raw.index(b'\n<dblp>\n') + len(b'\n<dblp>\n')
    f_off = raw.rindex(b'</dblp>')
    for it in items[:4]:
        g = ranges.get(it['rel'])
        if not rep.check(g is not None, 'fixture good: range row for %s' % it['ident'], 'missing'):
            continue
        s, e = _fx_line_span(raw, it['ident'])
        want = raw[:h_end] + raw[s:e] + raw[f_off:]
        with open(os.path.join(wr, it['rel']), 'rb') as f:
            data = f.read()
        rep.check((g['header_offset'], g['header_length'], g['record_offset'], g['record_length'],
                   g['footer_offset'], g['footer_length']) == (0, h_end, s, e - s, f_off, len(raw) - f_off)
                  and data == want and g['excerpt_sha256'] == hashlib.sha256(want).hexdigest()
                  and g['excerpt_bytes'] == len(want) and g['release_gz_sha256'] == sha256_file(p),
                  'fixture good: %s offsets header 0+%d record %d+%d footer %d+%d match an independent search, '
                  'file = header+record+footer, sha256 %s' % (it['ident'], g['header_length'], g['record_offset'],
                                                              g['record_length'], g['footer_offset'],
                                                              g['footer_length'], g['excerpt_sha256']),
                  'recorded %r, expected header 0+%d record %d+%d footer %d+%d'
                  % (g, h_end, s, e - s, f_off, len(raw) - f_off))
        try:
            er_check_content(it, data)
            rep.ok('fixture good: %s excerpt parses and carries its selection facts' % it['ident'])
        except ValueError as ex:
            rep.fail('fixture good: %s excerpt content' % it['ident'], str(ex))
    r_off = Report()
    er_check_ranges_offline(r_off, wr, er_read_ledger(wr), ranges, pin)
    # Two checks per dblp row: "recorded" and "consistent (offline)".
    rep.check(r_off.failures == 0 and r_off.checks == 8, 'fixture good: offline range check passes (%d checks)'
              % r_off.checks, '%d failures' % r_off.failures)
    r_rel = Report()
    er_check_ranges_release(r_rel, wr, ranges, p, pin)
    rep.check(r_rel.failures == 0 and r_rel.checks >= 9, 'fixture good: --with-release re-check passes (%d checks)'
              % r_rel.checks, '%d failures' % r_rel.failures)
    r_wrong = Report()
    p2, _ = _fx_release(os.path.join(good, 'other'), 'fixture-release.xml.gz', raw.replace(b'Spanner', b'SpanneR'))
    er_check_ranges_release(r_wrong, wr, ranges, p2, pin)
    rep.check(r_wrong.failures > 0, 'fixture good: --with-release refuses a different release (MD5 not the pin)',
              'no failure')

    # 2. Tampered range row: an offset shifted by one byte is caught (offline and --with-release).
    rp = os.path.join(wr, ER_RANGES_REL)
    with open(rp, encoding='utf-8') as f:
        lines = f.read().split('\n')
    for i, line in enumerate(lines):
        cols = line.split('\t')
        if len(cols) == len(ER_RANGES_COLS) and cols[1] == 'journals/cacm/Varajao21':
            cols[8] = str(int(cols[8]) + 1)
            lines[i] = '\t'.join(cols)
    with open(rp, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))
    tampered = er_read_ranges(wr)
    t_off, t_rel = Report(), Report()
    er_check_ranges_offline(t_off, wr, er_read_ledger(wr), tampered, pin)
    er_check_ranges_release(t_rel, wr, tampered, p, pin)
    rep.check(t_off.failures == 1, 'fixture tampered offset: offline check fails for the shifted row only',
              '%d failures' % t_off.failures)
    rep.check(t_rel.failures == 1, 'fixture tampered offset: --with-release fails for the shifted row only',
              '%d failures' % t_rel.failures)

    # 3. Release missing, sidecar missing, MD5 mismatch, sidecar mismatch.
    d = os.path.join(tmp, 'missing')
    _fx_expect_refusal(rep, d, os.path.join(d, 'release', 'fixture-release.xml.gz'), _fx_pin(md5),
                       'release-missing', 'release-missing', 'does not exist')
    d = os.path.join(tmp, 'nosidecar')
    p3, m3 = _fx_release(os.path.join(d, 'release'), 'fixture-release.xml.gz', raw, sidecar=False)
    _fx_expect_refusal(rep, d, p3, _fx_pin(m3), 'sidecar-missing', 'release-sidecar-missing', 'does not exist')
    d = os.path.join(tmp, 'md5')
    p4, _ = _fx_release(os.path.join(d, 'release'), 'fixture-release.xml.gz', raw)
    _fx_expect_refusal(rep, d, p4, _fx_pin('0' * 32), 'md5-mismatch', 'release-checksum-mismatch', 'MD5')
    d = os.path.join(tmp, 'sha')
    p5, m5 = _fx_release(os.path.join(d, 'release'), 'fixture-release.xml.gz', raw, sidecar_value='0' * 64)
    _fx_expect_refusal(rep, d, p5, _fx_pin(m5), 'sidecar-mismatch', 'release-checksum-mismatch', 'SHA-256')

    # 4. Malformed layouts. Each variant is pinned to its own MD5, so only the layout can fail.
    start_v = b'\n<article key="journals/cacm/Varajao21">'
    s_p, e_p = _fx_line_span(raw, 'journals/cacm/PopperMP21')
    s_v, e_v = _fx_line_span(raw, 'journals/cacm/Varajao21')
    pop_end = raw.rindex(b'</article>\n', s_p, e_p)
    variants = [
        ('tag-not-at-line-start', raw.replace(start_v, b'\n ' + start_v[1:], 1), 'not at the start of a line'),
        ('missing-dblp-end', raw[:raw.rindex(b'</dblp>')], '"</dblp>"'),
        ('missing-dblp-start', raw.replace(b'\n<dblp>\n', b'\n<dblp >\n', 1), 'no "<dblp>" line'),
        ('duplicate-key', raw[:e_v] + raw[s_v:e_v] + raw[e_v:], 'occurs twice'),
        ('missing-record-end', raw[:pop_end] + raw[pop_end + len(b'</article>\n'):], 'no end tag before the next record'),
        ('text-after-end-tag', raw[:e_v - 1] + b'<x/>' + raw[e_v - 1:], 'text follows'),
    ]
    for name, var, needle in variants:
        d = os.path.join(tmp, name)
        pv, mv = _fx_release(os.path.join(d, 'release'), 'fixture-release.xml.gz', var)
        _fx_expect_refusal(rep, d, pv, _fx_pin(mv), name, 'release-layout-error', needle)
    d = os.path.join(tmp, 'truncated-gzip')
    gz_len = os.path.getsize(p)
    pt, mt = _fx_release(os.path.join(d, 'release'), 'fixture-release.xml.gz', raw, truncate=gz_len // 2)
    _fx_expect_refusal(rep, d, pt, _fx_pin(mt), 'truncated-gzip', 'release-layout-error', 'cannot decompress')


class _FxCrash(Exception):
    """A simulated process kill inside the fixture (raised by an injected fault, never by production code)."""


def _fx_quiet(fn, *a):
    """Runs fn with its stdout captured. -> (result, captured text, _FxCrash or None)."""
    buf, res, exc = io.StringIO(), None, None
    with contextlib.redirect_stdout(buf):
        try:
            res = fn(*a)
        except _FxCrash as e:
            exc = e
    return res, buf.getvalue(), exc


def _fx_span_ok(raw, wr, items, ranges):
    """Every extracted record's ranges and bytes equal an independent search of raw."""
    h_end = raw.index(b'\n<dblp>\n') + len(b'\n<dblp>\n')
    f_off = raw.rindex(b'</dblp>')
    bad = []
    for it in items:
        g = ranges.get(it['rel'])
        if g is None:
            bad.append('%s: no ranges row' % it['ident'])
            continue
        s, e = _fx_line_span(raw, it['ident'])
        want = raw[:h_end] + raw[s:e] + raw[f_off:]
        with open(os.path.join(wr, it['rel']), 'rb') as f:
            data = f.read()
        if (g['header_length'], g['record_offset'], g['record_length'], g['footer_offset']) != (h_end, s, e - s, f_off) \
                or data != want:
            bad.append('%s: recorded %d+%d, independent %d+%d' % (it['ident'], g['record_offset'], g['record_length'],
                                                                    s, e - s))
    return bad


# Realistic release lines (RV-P5-02 finding 7). The attribute VALUES are synthetic; only their shape
# follows the dblp record syntax (mdate / publtype / cdate attributes before or after key="...").
ER_FX_ATTRS = [
    (b'<article key="journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13">',
     b'<article mdate="2020-11-02" key="journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13">'),
    (b'<article key="journals/cacm/PopperMP21">',
     b'<article mdate="2021-03-29" publtype="informal" key="journals/cacm/PopperMP21">'),
    (b'<article key="journals/cacm/Varajao21">',
     b'<article key="journals/cacm/Varajao21" mdate="2021-09-24" cdate="2021-09-24">'),
    (b'<inproceedings key="conf/osdi/ChangDGHWBCFG06">',
     b'<inproceedings mdate="2017-05-25" key="conf/osdi/ChangDGHWBCFG06">'),
]
# Unwanted records placed between wanted ones: a multi-line thesis right after <dblp>, a www person
# record, a record whose key is a PREFIX of a wanted key, a one-line record, and a proceedings record.
ER_FX_UNWANTED_AFTER_HEADER = (b'<phdthesis mdate="2019-01-01" key="phd/fixture/Unwanted01">\n'
                               b'<author>Fixture Unwanted Author</author>\n<title>Unwanted thesis.</title>\n'
                               b'<year>2019</year>\n</phdthesis>\n')
ER_FX_UNWANTED_BEFORE_POPPER = (b'<www mdate="2023-01-01" key="homepages/00/0000">\n'
                                b'<author>Fixture Person</author>\n<title>Home Page</title>\n</www>\n')
ER_FX_UNWANTED_BEFORE_CHANG = (b'<article mdate="2021-10-01" key="journals/cacm/Varajao2">\n'
                               b'<author>Fixture Prefix Author</author>\n<title>Prefix key record.</title>\n'
                               b'<year>2021</year>\n</article>\n'
                               b'<article mdate="2021-01-04" publtype="withdrawn" key="journals/corr/abs-2101-00001">'
                               b'<author>Fixture One Line</author><title>One-line record.</title><year>2021</year>'
                               b'</article>\n'
                               b'<proceedings mdate="2006-11-01" key="conf/osdi/2006">\n'
                               b'<editor>Fixture Editor</editor>\n<title>OSDI 2006.</title>\n</proceedings>\n')


def _fx_realistic(raw, decl, popper):
    v = raw.replace(b'<?xml version="1.0" encoding="US-ASCII"?>', decl, 1)
    for a, b in ER_FX_ATTRS:
        v = v.replace(a, b, 1)
    v = v.replace(b'P&ouml;pper', popper, 1)
    v = v.replace(b'\n<dblp>\n', b'\n<dblp>\n' + ER_FX_UNWANTED_AFTER_HEADER, 1)
    v = v.replace(b'<article mdate="2021-03-29"', ER_FX_UNWANTED_BEFORE_POPPER + b'<article mdate="2021-03-29"', 1)
    v = v.replace(b'<inproceedings mdate="2017-05-25"', ER_FX_UNWANTED_BEFORE_CHANG + b'<inproceedings mdate="2017-05-25"', 1)
    return v


def _er_fixture_layouts(rep, raw, tmp):
    """record-exceeds-cap, a large author team under the cap, and realistic release lines (finding 7)."""
    items = _fx_items()
    pos = raw.index(b'<title>Spanner')
    for name, n_auth, over in (('large-team-over-cap', 5100, True), ('large-team-under-cap', 4900, False)):
        var = raw[:pos] + b''.join(b'<author>Fixture Coauthor %05d</author>\n' % i for i in range(n_auth)) + raw[pos:]
        d = os.path.join(tmp, name)
        p, m = _fx_release(os.path.join(d, 'release'), 'fixture-release.xml.gz', var)
        wr = os.path.join(d, 'out')
        (n, fails), _, _ = _fx_quiet(er_dblp_step, wr, p, _fx_pin(m), items, {})
        reasons = {f[0]['ident']: f[1] for f in fails}
        corbett = items[0]
        rec_len = _fx_line_span(var, corbett['ident'])
        rec_len = rec_len[1] - rec_len[0]
        if over:
            ok = n == 3 and reasons.get(corbett['ident'], '').startswith('record-exceeds-cap:') \
                and len(reasons) == 2 and not os.path.exists(os.path.join(wr, corbett['rel'])) \
                and corbett['rel'] not in er_read_ranges(wr)
            rep.check(ok, 'fixture %s: a %d-byte record (%d authors) fails with record-exceeds-cap, that record only, '
                      'no file and no ranges row; the other 3 are extracted' % (name, rec_len, n_auth + 26),
                      'extracted %d, reasons %r' % (n, reasons))
            print('INFO fixture %s: reason: %s' % (name, reasons.get(corbett['ident'])))
        else:
            ok = n == 4 and not _fx_span_ok(var, wr, items[:4], er_read_ranges(wr))
            rep.check(ok, 'fixture %s: a %d-byte record (%d authors) is extracted whole, offsets match an independent '
                      'search' % (name, rec_len, n_auth + 26), 'extracted %d, reasons %r' % (n, reasons))
    # The encoding declaration of the real release is UNVERIFIED (manifest), so the plausible ones are tested,
    # each with the non-ASCII author name written the way that encoding allows.
    for name, decl, popper, good in (
            ('iso-8859-1', b'<?xml version="1.0" encoding="ISO-8859-1"?>', b'P\xf6pper', True),
            ('utf-8', b'<?xml version="1.0" encoding="UTF-8"?>', u'Pöpper'.encode('utf-8'), True),
            ('us-ascii', b'<?xml version="1.0" encoding="US-ASCII"?>', b'P&ouml;pper', True),
            ('utf-8-declared-latin-1-bytes', b'<?xml version="1.0" encoding="UTF-8"?>', b'P\xf6pper', False)):
        var = _fx_realistic(raw, decl, popper)
        d = os.path.join(tmp, 'realistic-' + name)
        p, m = _fx_release(os.path.join(d, 'release'), 'fixture-release.xml.gz', var)
        wr = os.path.join(d, 'out')
        (n, fails), _, _ = _fx_quiet(er_dblp_step, wr, p, _fx_pin(m), items, {})
        reasons = {f[0]['ident']: f[1] for f in fails}
        rng = er_read_ranges(wr)
        if good:
            bad = _fx_span_ok(var, wr, items[:4], rng)
            ok = n == 4 and list(reasons) == [ER_FIXTURE_ABSENT_KEY] and not bad \
                and not any(k in (g['record_key'] for g in rng.values()) for k in
                            ('journals/cacm/Varajao2', 'phd/fixture/Unwanted01', 'homepages/00/0000'))
            rep.check(ok, 'fixture realistic %s: declaration %s, mdate/publtype/cdate attributes, unwanted records '
                      '(incl. a prefix key) between wanted ones: 4 records extracted, offsets match an independent '
                      'search, non-ASCII author matched' % (name, decl.decode('ascii')),
                      'extracted %d, reasons %r, offsets %r' % (n, reasons, bad))
        else:
            pop = items[1]
            ok = n == 3 and reasons.get(pop['ident'], '').startswith('excerpt-check-failed:') \
                and not os.path.exists(os.path.join(wr, pop['rel'])) and pop['rel'] not in rng
            rep.check(ok, 'fixture realistic %s: bytes that contradict the declaration fail excerpt-check-failed for '
                      'that record only, nothing written for it' % name, 'extracted %d, reasons %r' % (n, reasons))
            print('INFO fixture realistic %s: reason: %s' % (name, reasons.get(pop['ident'])))


def _er_fixture_budget_and_names(rep, raw, tmp):
    """RV-P5-02 finding 3 (dblp excerpt bytes count against the 8 MiB budget while writing) and nit 10
    (two dblp keys must never share a file name)."""
    items = _fx_items()
    h_end = raw.index(b'\n<dblp>\n') + len(b'\n<dblp>\n')
    f_off = raw.rindex(b'</dblp>')
    s0, e0 = _fx_line_span(raw, items[0]['ident'])
    size0 = h_end + (e0 - s0) + (len(raw) - f_off)
    d = os.path.join(tmp, 'budget')
    p, m = _fx_release(os.path.join(d, 'release'), 'fixture-release.xml.gz', raw)
    wr = os.path.join(d, 'out')
    st = {'total': ER_MAX_TOTAL_BYTES - size0}
    (n, fails), _, _ = _fx_quiet(er_dblp_step, wr, p, _fx_pin(m), items, {}, st)
    reasons = {f[0]['ident']: f[1] for f in fails}
    written = sorted(it['ident'] for it in items if os.path.exists(os.path.join(wr, it['rel'])))
    ok = n == 1 and st['total'] == ER_MAX_TOTAL_BYTES and written == [items[0]['ident']] \
        and all(reasons.get(it['ident'], '').startswith('byte-budget-reached:') for it in items[1:4]) \
        and reasons.get(ER_FIXTURE_ABSENT_KEY, '').startswith('key-not-in-pinned-release:') \
        and sorted(er_read_ranges(wr)) == [items[0]['rel']]
    rep.check(ok, 'fixture byte budget: with %d bytes left, the %d-byte first excerpt is written (budget now exactly '
              'full) and the other 3 fail byte-budget-reached before any write, no file or ranges row for them'
              % (size0, size0), 'extracted %d, total %d, written %r, reasons %r' % (n, st['total'], written, reasons))
    if items[1]['ident'] in reasons:
        print('INFO fixture byte budget: reason: %s' % reasons[items[1]['ident']])
    # nit 10: a key holding "__" is refused by the selection parser (a/b__c and a__b/c would share a file).
    r = os.path.join(tmp, 'names')
    os.makedirs(os.path.join(r, ER_DIR_REL))
    with open(os.path.join(r, ER_SELECTION_REL), 'w', encoding='utf-8', newline='\n') as f:
        f.write('unit_id\tcriterion\tsource\tkind\tidentifier\texpect_doi\texpect_author\n')
        f.write('fx-name-1\tordinary\tdblp\trec\tjournals/x__y/Key1\t-\t-\n')
        f.write('fx-name-2\tordinary\tdblp\trec\tjournals__x/y/Key1\t-\t-\n')
    _, _, errs = er_load_selection(r)
    rep.check(len([e for e in errs if 'contains "__"' in e]) == 2,
              'fixture file names: dblp keys holding "__" are refused by the selection parser (no shared file name)',
              'errors %r' % errs)


def _fx_root(tmp, name, raw, sid=None, md5=None):
    """A throw-away repository root for er_fetch: dblp-only selection (so no network path exists), a minimal
    manifest pinning the fixture release, and the release with its sidecar at the pinned path."""
    r = os.path.join(tmp, name)
    os.makedirs(os.path.join(r, ER_DIR_REL))
    with open(os.path.join(r, ER_SELECTION_REL), 'w', encoding='utf-8', newline='\n') as f:
        f.write('# fixture selection (temporary; dblp rows only)\n')
        f.write('unit_id\tcriterion\tsource\tkind\tidentifier\texpect_doi\texpect_author\n')
        for i, (k, doi, auth) in enumerate(ER_FIXTURE_ITEMS):
            f.write('\t'.join(['fx-unit-%d' % (i + 1), 'ordinary', 'dblp', 'rec', k, doi or '-', auth or '-']) + '\n')
    _, m = _fx_release(os.path.join(r, ER_DBLP_RELEASE_DIR_REL), ER_DBLP_RELEASE_ID + '.xml.gz', raw)
    with open(os.path.join(r, MANIFEST_REL), 'w', encoding='utf-8', newline='\n') as f:
        f.write('datasets:\n  dblp:\n    snapshot:\n      id: %s\n    integrity:\n      upstream_checksum:\n'
                '        value: %s\n    official_source:\n      download_url: %s\n'
                % (json.dumps(sid or ER_DBLP_RELEASE_ID), json.dumps(md5 or m), json.dumps(_FX_PINNED_URL[0])))
    return r


_FX_PINNED_URL = ['']


def _fx_failures(r):
    p = os.path.join(r, ER_FAILURES_REL)
    if not os.path.isfile(p):
        return []
    return [dict(zip(ER_FAILURE_COLS, c)) for _, c in er_read_tsv(p) if c[0] != 'unit_id']


def _fx_verify(r):
    v = Report()
    _fx_quiet(er_verify, r, v)
    return v


def _fx_verify_out(r):
    v = Report()
    _, out, _ = _fx_quiet(er_verify, r, v)
    return v, out


def _fx_state(r):
    led = {k: v for k, v in er_read_ledger(r).items() if v['source'] == 'dblp'}
    return led, er_read_ranges(r), _fx_failures(r)


def _fx_rewrite_without(path, prefix):
    with open(path, encoding='utf-8') as f:
        lines = f.read().split('\n')
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(ln for ln in lines if not ln.startswith(prefix + '\t')))


def _er_fixture_orchestration(rep, root, raw, tmp):
    """er_fetch end to end on throw-away roots (findings 4 and 7): FETCH-FAILURES rows, exit codes, skipping
    of present records, crash recovery, torn rows, the post-write check, and release-pin-mismatch."""
    _FX_PINNED_URL[0] = load_manifest(root)['datasets']['dblp']['official_source']['download_url']
    g = globals()
    absent = ER_FIXTURE_ABSENT_KEY

    # O1. First run.
    r = _fx_root(tmp, 'orch', raw)
    rel = {it['ident']: it['rel'] for it in er_plan(r)[1]}
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    led, rng, fl = _fx_state(r)
    v, vout = _fx_verify_out(r)
    rep.check(rc == 1 and [(x['identifier'], x['reason'].split(':')[0]) for x in fl] == [(absent, 'key-not-in-pinned-release')]
              and len(led) == 4 and len(rng) == 4 and v.failures == 0 and os.path.isfile(os.path.join(r, ER_SPLIT_REL)),
              'fixture er_fetch first run: exit 1, one FETCH-FAILURES row (%s, key-not-in-pinned-release), 4 excerpts '
              'each with a PROVENANCE and a ranges row, er-verify passes (%d checks)' % (absent, v.checks),
              'rc %r, failures %r, ledger %d, ranges %d, verify failures %d' % (rc, fl, len(led), len(rng), v.failures))
    # Finding 6 positive control: the 4 distinct ORCID iDs of the excerpts are checked and none straddles.
    rep.check('PASS er: no OpenAlex author ID or ORCID iD (OpenAlex records, Crossref works and dblp excerpts) occurs '
              'in both dev and heldout (4 distinct IDs checked)' in vout,
              'fixture cross-split check: the 4 ORCID iDs asserted by the dblp excerpts are included and none is in '
              'both splits', 'output %r' % [ln for ln in vout.split('\n') if 'both' in ln])
    # Finding 8 positive control: --with-release reads the release and compares all 4 excerpts.
    rc_w, wout, _ = _fx_quiet(cmd_er_verify, r, True)
    rep.check(rc_w == 0 and wout.count('equals release bytes') == 4 and 'er-verify --with-release:' in wout,
              'fixture --verify --with-release after extraction: exit 0, all 4 excerpts compared with the release bytes',
              'rc %r, out %r' % (rc_w, wout[-400:]))

    # O2. Re-run: the 4 committed excerpts are present; nothing is rewritten.
    snap = [open(os.path.join(r, x), 'rb').read() for x in (ER_LEDGER_REL, ER_RANGES_REL)]
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    led, rng, fl = _fx_state(r)
    after = [open(os.path.join(r, x), 'rb').read() for x in (ER_LEDGER_REL, ER_RANGES_REL)]
    rep.check(rc == 1 and 'fetched 0, already present 4, not fetched 1' in out and snap == after and len(fl) == 1,
              'fixture er_fetch re-run: the 4 committed excerpts are skipped as already present (ledger and ranges '
              'unchanged), exit 1 for the absent key only', 'rc %r, out %r' % (rc, out[-300:]))

    # O3. Crash after the file and the ranges row, before the PROVENANCE commit.
    r = _fx_root(tmp, 'crash', raw)
    target = rel['journals/cacm/Varajao21']
    saved = g['er_ledger_commit']

    def crash_commit(wr, row):
        if row['file'] == target:
            raise _FxCrash('simulated kill before the PROVENANCE append')
        saved(wr, row)
    g['er_ledger_commit'] = crash_commit
    try:
        _, _, exc = _fx_quiet(er_fetch, r, 'sample')
    finally:
        g['er_ledger_commit'] = saved
    led, rng, _ = _fx_state(r)
    v = _fx_verify(r)
    rep.check(exc is not None and target not in led and target in rng and os.path.isfile(os.path.join(r, target))
              and v.failures > 0,
              'fixture crash before the PROVENANCE commit: file and ranges row exist without a PROVENANCE row, and '
              'er-verify detects it (%d failures)' % v.failures,
              'crash %r, in ledger %r, in ranges %r, verify failures %d' % (exc, target in led, target in rng, v.failures))
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    led, rng, fl = _fx_state(r)
    v = _fx_verify(r)
    rep.check(rc == 1 and ('RECOVER %s' % target) in out and 'fetched 2, already present 2, not fetched 1' in out
              and len(led) == 4 and len(rng) == 4 and v.failures == 0,
              'fixture crash recovery: the next run does not count the uncommitted record as present, re-extracts it '
              '(one ranges row, one PROVENANCE row), and er-verify passes',
              'rc %r, ledger %d, ranges %d, verify failures %d, out %r' % (rc, len(led), len(rng), v.failures, out[-400:]))

    # O4. A PROVENANCE row torn by an interrupted append.
    lp = os.path.join(r, ER_LEDGER_REL)
    with open(lp, 'rb') as f:
        body = f.read()[:-1]
    cut = body.rfind(b'\n') + 1
    torn_rel = body[cut:].split(b'\t', 1)[0].decode('utf-8')
    with open(lp, 'wb') as f:
        f.write(body[:cut] + body[cut:cut + (len(body) - cut) // 2])
    v = _fx_verify(r)
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    led, rng, fl = _fx_state(r)
    v2 = _fx_verify(r)
    rep.check(v.failures > 0 and ('REPAIRED %s' % ER_LEDGER_REL) in out and ('RECOVER %s' % torn_rel) in out
              and len(led) == 4 and len(rng) == 4 and v2.failures == 0 and er_tail_complete(lp),
              'fixture torn PROVENANCE row: er-verify detects it (%d failures); the next run drops the torn line, '
              're-extracts %s, and er-verify passes' % (v.failures, torn_rel),
              'verify before %d after %d, ledger %d, out %r' % (v.failures, v2.failures, len(led), out[-400:]))

    # O4b. RV-P5-03 N1: a COMPLETE last row that only lost its final newline is never dropped; the run stops.
    with open(lp, 'rb') as f:
        intact = f.read()
    with open(lp, 'wb') as f:
        f.write(intact[:-1])
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    with open(lp, 'rb') as f:
        after = f.read()
    v = _fx_verify(r)
    rep.check(rc == 2 and 'NOT dropped' in out and after == intact[:-1] and v.failures > 0 and 'REPAIRED' not in out
              and r not in out and 'cut inside its LAST column' in out and 'only if every column is complete' in out,
              'fixture complete PROVENANCE row without a final newline: not dropped, file unchanged byte for byte, '
              'exit 2 with the manual repair step, which warns that the last column may be cut (RV-P5-07 NIT-4), '
              'er-verify fails', 'rc %r, unchanged %r, out %r' % (rc, after == intact[:-1], out[-400:]))
    print('INFO fixture complete row without newline: %s' % [ln for ln in out.split('\n') if 'NOT dropped' in ln][:1])
    with open(lp, 'wb') as f:
        f.write(intact)
    rep.check(_fx_verify(r).failures == 0, 'fixture complete row without newline: after the manual repair (newline '
              'restored) er-verify passes', 'verify fails')

    # O5. The RV-P5-02 scenario: a PROVENANCE row without its ranges row (older write order or a hand edit).
    pop = rel['journals/cacm/PopperMP21']
    _fx_rewrite_without(os.path.join(r, ER_RANGES_REL), pop)
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    led, rng, fl = _fx_state(r)
    v = _fx_verify(r)
    hit = [x for x in fl if x['identifier'] == 'journals/cacm/PopperMP21']
    rep.check(rc == 1 and 'already present 3' in out and len(hit) == 1
              and hit[0]['reason'].startswith('excerpt-state-inconsistent:') and pop in hit[0]['reason']
              and r not in hit[0]['reason'] and v.failures > 0,
              'fixture PROVENANCE row without a ranges row: not counted as present, reported in FETCH-FAILURES as '
              'excerpt-state-inconsistent with the repair step (repository-relative paths only, RV-P5-03 N2), exit 1, '
              'er-verify fails',
              'rc %r, failures %r, verify failures %d' % (rc, fl, v.failures))
    print('INFO fixture excerpt-state-inconsistent: reason: %s' % (hit[0]['reason'] if hit else None))
    _fx_rewrite_without(lp, pop)
    os.remove(os.path.join(r, pop))
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    led, rng, fl = _fx_state(r)
    v = _fx_verify(r)
    rep.check(rc == 1 and len(led) == 4 and len(rng) == 4 and v.failures == 0 and len(fl) == 1,
              'fixture PROVENANCE row without a ranges row: after the documented manual repair the next run '
              're-extracts it and er-verify passes', 'rc %r, verify failures %d, failures %r' % (rc, v.failures, fl))

    # O6. The post-write sha check fails: nothing that looks valid is left behind.
    r = _fx_root(tmp, 'shafail', raw)
    target = rel['journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13']
    saved = g['er_write_part']

    def bad_write(path, data):
        if path == os.path.join(r, target) + '.part':
            data = data[:10] + bytes([data[10] ^ 1]) + data[11:]
        saved(path, data)
    g['er_write_part'] = bad_write
    try:
        rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    finally:
        g['er_write_part'] = saved
    led, rng, fl = _fx_state(r)
    v = _fx_verify(r)
    hit = [x for x in fl if x['reason'].startswith('excerpt-write-failed:')]
    p6 = os.path.join(r, target)
    rep.check(rc == 1 and len(hit) == 1 and not os.path.exists(p6) and not os.path.exists(p6 + '.part')
              and target not in led and target not in rng and len(led) == 3 and v.failures == 0,
              'fixture post-write sha check fails: excerpt-write-failed in FETCH-FAILURES, exit 1, no file, no .part, '
              'no PROVENANCE or ranges row; the other 3 are committed and er-verify passes',
              'rc %r, failures %r, exists %r, verify failures %d' % (rc, fl, os.path.exists(p6), v.failures))
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    led, rng, fl = _fx_state(r)
    rep.check(len(led) == 4 and 'fetched 1, already present 3' in out and _fx_verify(r).failures == 0,
              'fixture post-write sha check: the next run extracts the record', 'out %r' % out[-300:])

    # O7. release-pin-mismatch through er_fetch: every dblp item gets a FETCH-FAILURES row, nothing is written.
    for name, sid, md5 in (('pin-snapshot', 'dblp-2026-11-01', None), ('pin-md5', None, 'not-a-32-hex-md5')):
        r = _fx_root(tmp, name, raw, sid=sid, md5=md5)
        rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
        fl = _fx_failures(r)
        written = []
        for dp, _, fns in os.walk(os.path.join(r, ER_SAMPLES_REL)):
            written += [os.path.relpath(os.path.join(dp, fn), r).replace(os.sep, '/') for fn in fns]
        rep.check(rc == 1 and len(fl) == len(ER_FIXTURE_ITEMS)
                  and all(x['reason'].startswith('release-pin-mismatch:') for x in fl)
                  and sorted(written) == sorted([ER_LEDGER_REL, ER_FAILURES_REL]),
                  'fixture %s: release-pin-mismatch, %d of %d FETCH-FAILURES rows, exit 1, no dblp file or row written'
                  % (name, len(fl), len(ER_FIXTURE_ITEMS)), 'rc %r, failures %r, written %r' % (rc, fl, written))
        if fl:
            print('INFO fixture %s: reason: %s' % (name, fl[0]['reason']))

    # O8. RV-P5-02 finding 6: one ORCID iD asserted by dblp excerpts in BOTH splits fails er-verify. The iD is
    # injected in lower-case-x form, so the check must also normalise ORCID spellings.
    sp = er_compute_split({'fx-unit-%d' % (i + 1): 'ordinary' for i in range(len(ER_FIXTURE_ITEMS))})
    side = [sp['fx-unit-%d' % (i + 1)][1] for i in range(4)]
    inject = {0: (b'<author>James C. Corbett</author>', b'<author orcid="0000-0002-2814-962x">James C. Corbett</author>'),
              2: (b'<author orcid="0000-0002-4303-3908">', b'<author orcid="0000-0002-2814-962x">'),
              3: (b'<author>Fay Chang</author>', b'<author orcid="0000-0002-2814-962x">Fay Chang</author>')}
    tgt = [i for i in (0, 2, 3) if side[i] != side[1]][0]
    var = raw.replace(inject[tgt][0], inject[tgt][1], 1)
    r = _fx_root(tmp, 'straddle', var)
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    v, vout = _fx_verify_out(r)
    fails = [ln for ln in vout.split('\n') if ln.startswith('FAIL ')]
    rep.check(rc == 1 and v.failures == 1 and len(fails) == 1 and 'straddles the dev/heldout split' in fails[0]
              and 'orcid:0000-0002-2814-962X dev=' in vout,
              'fixture cross-split entity: an ORCID iD in dblp excerpts of a dev unit and a heldout unit (%s and %s) '
              'FAILS er-verify (1 failure), the iD and both units are listed' % (side[1], side[tgt]),
              'rc %r, failures %d, FAIL lines %r' % (rc, v.failures, fails))
    print('INFO fixture cross-split entity: %s' % [ln for ln in vout.split('\n') if 'orcid:0000-0002-2814-962X' in ln][:1])

    # O9. RV-P5-02 finding 8: --with-release must not pass when verification stops before reading the release
    # (no split file and no records yet).
    r = _fx_root(tmp, 'with-release-early', raw)
    rc, out, _ = _fx_quiet(cmd_er_verify, r, True)
    rep.check(rc == 1 and 'never passes without reading the release' in out,
              'fixture --verify --with-release before any extraction (verification stops early): exit 1, it never '
              'passes without reading the release', 'rc %r, out %r' % (rc, out[-400:]))

    # O10. RV-P5-02 finding 3 through er_fetch: bytes already in the ledger fill the budget, so every pending
    # dblp excerpt fails byte-budget-reached and nothing is written.
    r = _fx_root(tmp, 'budget-fetch', raw)
    fake = {'file': ER_SAMPLES_REL + '/dev/openalex-works/fixture-budget-filler.json', 'unit_id': 'fx-unit-1',
            'split': 'dev', 'criterion': 'ordinary', 'source': 'openalex', 'kind': 'work-by-doi', 'record_id': 'W0000',
            'request_url': 'https://fixture.invalid/', 'retrieved_at_utc': '2026-01-01T00:00:00Z', 'http_status': '200',
            'bytes': str(ER_MAX_TOTAL_BYTES), 'sha256': '0' * 64, 'license': 'fixture', 'note': 'fixture filler row'}
    er_append_row(os.path.join(r, ER_LEDGER_REL), ER_LEDGER_HEAD, ER_LEDGER_COLS, fake)
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    led, rng, fl = _fx_state(r)
    rs = sorted(x['reason'].split(':')[0] for x in fl)
    rep.check(rc == 1 and not led and not rng and rs == ['byte-budget-reached'] * 4 + ['key-not-in-pinned-release'],
              'fixture er_fetch with a full budget: the 4 dblp excerpts fail byte-budget-reached in FETCH-FAILURES, '
              'no excerpt, PROVENANCE or ranges row is written, exit 1', 'rc %r, reasons %r, ledger %d' % (rc, rs, len(led)))

    # O11. RV-P5-03 N2 / RV-P5-07 NIT-3: release-level reasons in FETCH-FAILURES.tsv carry no absolute path.
    r = _fx_root(tmp, 'release-missing-paths', raw)
    rp = os.path.join(r, ER_DBLP_RELEASE_DIR_REL, ER_DBLP_RELEASE_ID + '.xml.gz')
    os.remove(rp + '.sha256')
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    fl = _fx_failures(r)
    rs = [x['reason'] for x in fl]
    rep.check(rc == 1 and len(rs) == len(ER_FIXTURE_ITEMS) and all(x.startswith('release-sidecar-missing:') for x in rs)
              and all(r not in x and ER_DBLP_RELEASE_DIR_REL + '/' in x for x in rs),
              'fixture release-sidecar-missing: every FETCH-FAILURES reason names the repository-relative release path '
              'and no absolute path (RV-P5-07 NIT-3)', 'rc %r, reasons %r' % (rc, rs[:1]))
    os.remove(rp)
    rc, out, _ = _fx_quiet(er_fetch, r, 'sample')
    rs = [x['reason'] for x in _fx_failures(r)]
    rep.check(rc == 1 and len(rs) == len(ER_FIXTURE_ITEMS) and all(x.startswith('release-missing:') for x in rs)
              and all(r not in x and ER_DBLP_RELEASE_DIR_REL + '/' in x for x in rs),
              'fixture release-missing: every FETCH-FAILURES reason names the repository-relative release path and no '
              'absolute path (RV-P5-07 NIT-3)', 'rc %r, reasons %r' % (rc, rs[:1]))
    if rs:
        print('INFO fixture release-missing: reason: %s' % rs[0])


def _er_fixture_crossref_split(rep, root, raw, tmp):
    """RV-P5-07 MINOR-2: ORCID iDs asserted by Crossref works (message.items[].author[].ORCID) take part in the
    cross-split check, normalised like every other ORCID form. Identifiers only: in the control case both splits
    carry the same author NAME with different ORCID iDs, and verification passes."""
    _FX_PINNED_URL[0] = load_manifest(root)['datasets']['dblp']['official_source']['download_url']
    units = ['fx-cr-1', 'fx-cr-2', 'fx-cr-3']
    sp = er_compute_split({u: 'ordinary' for u in units})
    held = [u for u in units if sp[u][1] == 'heldout']
    dev = [u for u in units if sp[u][1] == 'dev']
    if not rep.check(len(held) == 2 and len(dev) == 1, 'fixture crossref cross-split: 3 units split into 2 heldout '
                     'and 1 dev', 'split %r' % sp):
        return
    h, d, o = held[0], dev[0], held[1]
    oa_doi, cr_doi, other_doi = '10.5555/fx-oa-dev', '10.5555/fx-cr-heldout', '10.5555/fx-oa-other'
    oa_body = json.dumps({'id': 'https://openalex.org/W0000000001', 'doi': 'https://doi.org/' + oa_doi,
                          'authorships': [{'author': {'id': 'https://openalex.org/A0000000001',
                                                      'display_name': 'Fixture Person',
                                                      'orcid': 'https://orcid.org/0000-0002-1825-0097'}}]},
                         separators=(',', ':')).encode('utf-8')
    for name, cr_orcid, straddles in (('straddle', 'http://orcid.org/0000-0002-1825-0097', True),
                                      ('control', 'https://orcid.org/0000-0000-0000-0002', False)):
        r = _fx_root(tmp, 'crossref-' + name, raw)
        with open(os.path.join(r, ER_SELECTION_REL), 'w', encoding='utf-8', newline='\n') as f:
            f.write('# fixture selection (temporary; RV-P5-07 MINOR-2)\n')
            f.write('unit_id\tcriterion\tsource\tkind\tidentifier\texpect_doi\texpect_author\n')
            for u, src, doi in ((h, 'crossref', cr_doi), (d, 'openalex', oa_doi), (o, 'openalex', other_doi)):
                f.write('\t'.join([u, 'ordinary', src, 'work-by-doi', doi, '-', '-']) + '\n')
        split, direct, _, errs = er_plan(r)
        if not rep.check(not errs, 'fixture crossref cross-split %s: selection parses' % name, 'errors %r' % errs):
            continue
        with open(os.path.join(r, ER_SPLIT_REL), 'w', encoding='utf-8', newline='\n') as f:
            f.write(er_split_text(split))
        cr_body = json.dumps({'status': 'ok', 'message-type': 'work-list', 'message': {'items': [
            {'DOI': cr_doi, 'author': [{'given': 'Fixture', 'family': 'Person', 'sequence': 'first',
                                        'ORCID': cr_orcid, 'authenticated-orcid': False}]}]}},
            separators=(',', ':')).encode('utf-8')
        bodies = {(h, 'crossref'): (cr_body, cr_doi), (d, 'openalex'): (oa_body, 'W0000000001')}
        for it in direct:
            if (it['unit'], it['source']) not in bodies:
                continue
            body, rid = bodies[(it['unit'], it['source'])]
            p = os.path.join(r, it['rel'])
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, 'wb') as f:
                f.write(body)
            er_append_row(os.path.join(r, ER_LEDGER_REL), ER_LEDGER_HEAD, ER_LEDGER_COLS, {
                'file': it['rel'], 'unit_id': it['unit'], 'split': it['split'], 'criterion': it['criterion'],
                'source': it['source'], 'kind': it['kind'], 'record_id': rid, 'request_url': 'https://fixture.invalid/',
                'retrieved_at_utc': '2026-01-01T00:00:00Z', 'http_status': '200', 'bytes': str(len(body)),
                'sha256': sha256_file(p), 'license': ER_LICENSES[it['source']], 'note': 'fixture record'})
        v, vout = _fx_verify_out(r)
        fails = [ln for ln in vout.split('\n') if ln.startswith('FAIL ')]
        if straddles:
            rep.check(v.failures == 1 and len(fails) == 1 and 'straddles the dev/heldout split' in fails[0]
                      and ('orcid:0000-0002-1825-0097 dev=%s heldout=%s' % (d, h)) in vout,
                      'fixture crossref cross-split: a Crossref author ORCID (http://orcid.org/ form) in heldout unit %s '
                      'equal to the ORCID of an OpenAlex authorship (https://orcid.org/ form) in dev unit %s FAILS '
                      'er-verify (1 failure), the iD and both units are listed' % (h, d),
                      'failures %d, FAIL lines %r' % (v.failures, fails))
            print('INFO fixture crossref cross-split: %s'
                  % [ln for ln in vout.split('\n') if 'orcid:0000-0002-1825-0097' in ln][:1])
        else:
            rep.check(v.failures == 0 and 'occurs in both dev and heldout (3 distinct IDs checked)' in vout
                      and ' dev=' not in vout,
                      'fixture crossref cross-split control: the same author name with different ORCID iDs in the two '
                      'splits passes (3 distinct IDs checked: the Crossref ORCID is counted, the name is never used)',
                      'failures %d, FAIL lines %r' % (v.failures, fails))


# Full-download transfer logic (findings 1 and 2), run against a curl STUB on PATH: no network.
ER_FX_URL = 'https://fixture.invalid/dblp-fixture.xml.gz'   # .invalid never resolves (RFC 2606)
ER_FX_CURL_STUB = r'''#!/bin/bash
printf '%s\n' "$@" > "$FX_CURL_LOG"
out=""; prev=""
for a in "$@"; do if [ "$prev" = "-o" ]; then out="$a"; fi; prev="$a"; done
case "$FX_CURL_MODE" in
  stall) printf 'partial-bytes' >> "$out"; printf '206'; echo 'curl: (28) Operation too slow.' >&2; exit 28 ;;
  range416) printf '416'; echo 'curl: (22) The requested URL returned error: 416' >&2; exit 22 ;;
  range416ok) printf '416'; exit 0 ;;
  noresume) printf '200'; echo 'curl: (33) HTTP server does not seem to support byte ranges. Cannot resume.' >&2; exit 33 ;;
  body) cat "$FX_CURL_BODY" >> "$out"; printf '200'; exit 0 ;;
  *) echo 'fixture curl stub: unexpected call' >&2; exit 99 ;;
esac
'''


def _er_fixture_full_download(rep, tmp, real_run):
    d = os.path.join(tmp, 'full-download')
    stub = os.path.join(d, 'stub')
    os.makedirs(stub)
    with open(os.path.join(stub, 'curl'), 'w', encoding='ascii', newline='\n') as f:
        f.write(ER_FX_CURL_STUB)
    os.chmod(os.path.join(stub, 'curl'), 0o700)
    good, wrong = os.path.join(d, 'good.gz'), os.path.join(d, 'wrong.gz')
    for p, txt in ((good, b'fixture release body\n'), (wrong, b'a different body\n')):
        with open(p, 'wb') as f:
            with gzip.GzipFile(filename='', mode='wb', fileobj=f, mtime=0) as z:
                z.write(txt)
    with open(good, 'rb') as f:
        good_bytes = f.read()
    pin = hashlib.md5(good_bytes).hexdigest()
    dest = os.path.join(d, 'full', 'dblp-fixture.xml.gz')
    part = dest + '.part'
    os.makedirs(os.path.dirname(dest))
    lib = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'lib-datasets.sh')
    log = os.path.join(d, 'curl-args.log')

    md5fail = os.path.join(d, 'stub-md5-fails')
    os.makedirs(md5fail)
    for tool in ('md5sum', 'md5'):
        with open(os.path.join(md5fail, tool), 'w', encoding='ascii', newline='\n') as f:
            f.write('#!/bin/bash\nexit 1\n')
        os.chmod(os.path.join(md5fail, tool), 0o700)
    fetched = {}

    def call(mode, body=None, to=None, pre='', base=None):
        if os.path.exists(log):
            os.remove(log)
        env = {'PATH': pre + stub + ':/usr/bin:/bin:/usr/sbin:/sbin', 'HOME': d, 'LC_ALL': 'C', 'FX_CURL_MODE': mode,
               'FX_CURL_LOG': log, 'FX_CURL_BODY': body or '/dev/null'}
        # DS_DOWNLOADS (the download root that ds_full_refuse_links requires) is set to the temporary directory
        # after the library is sourced; production code never takes it from the environment.
        script = ('. "$1" && DS_DOWNLOADS="$5" && ds_full_download "$2" "$3" "$4"; rc=$?; '
                  'printf "FX_FETCHED=%s\\n" "$DS_FETCHED_BYTES" >&2; exit "$rc"')
        r = real_run(['/bin/bash', '-c', script, 'fixture', lib, ER_FX_URL, to or dest, pin, base or d],
                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=60)
        args = open(log).read().split('\n') if os.path.exists(log) else None
        err = r.stderr.decode('utf-8', 'replace')
        m = re.search(r'FX_FETCHED=([0-9]+)', err)
        fetched['last'] = int(m.group(1)) if m else None
        return r.returncode, err, args

    def rm_target(err):
        """The path named by the printed "rm <path>" recovery step, parsed as a POSIX shell word (RV-P5-03 N3)."""
        m = re.search(r'Manual step: rm (.+?) and re-run', err) or re.search(r'repeats, rm (.+?) and re-run', err)
        if not m:
            return None
        try:
            toks = shlex.split(m.group(1))
        except ValueError:
            return None
        return toks[0] if len(toks) == 1 else None

    def has(args, flag, val):
        return bool(args) and any(args[i] == flag and args[i + 1] == val for i in range(len(args) - 1))

    def put(b):
        with open(part, 'wb') as f:
            f.write(b)

    def read(p):
        with open(p, 'rb') as f:
            return f.read()

    put(b'earlier-bytes')
    rc, err, args = call('stall')
    bounds = has(args, '--connect-timeout', '30') and has(args, '--speed-limit', '1024') \
        and has(args, '--speed-time', '120') and has(args, '-C', '-') and '--max-time' not in (args or [])
    rep.check(bounds, 'fixture full download: curl gets --connect-timeout 30, --speed-limit 1024, --speed-time 120, '
              '-C - and no --max-time', 'args %r' % args)
    rep.check(bool(args) and args[0] == '-q',
              'fixture full download: -q is curl\'s first argument, so ~/.curlrc is ignored (RV-P5-07 NIT-5)',
              'args %r' % (args or [])[:3])
    rep.check(rc == 28 and os.path.isfile(part) and read(part) == b'earlier-bytespartial-bytes' and part in err
              and 'Re-run the same command to resume' in err and not os.path.exists(dest)
              and fetched['last'] == len(b'partial-bytes'),
              'fixture full download stall: exit 28, .part kept with its bytes for resume, RECOVERY names the path, '
              'the 13 bytes of this run are counted', 'rc %r, fetched %r, err %r' % (rc, fetched['last'], err[-300:]))
    print('INFO fixture full download stall: %s' % err.strip().split('\n')[-2][:400])
    put(b'complete-but-corrupt')
    rc, err, args = call('range416')
    rep.check(rc == 22 and read(part) == b'complete-but-corrupt' and 'HTTP 416' in err and rm_target(err) == part,
              'fixture full download HTTP 416: non-zero exit, .part kept (not deleted silently), RECOVERY names the '
              'path and the manual rm step', 'rc %r, err %r' % (rc, err[-300:]))
    print('INFO fixture full download 416: %s' % err.strip().split('\n')[-2][:400])
    rc, err, args = call('range416ok')
    rep.check(rc == 22 and read(part) == b'complete-but-corrupt' and 'HTTP 416' in err and rm_target(err) == part
              and not os.path.exists(dest),
              'fixture full download HTTP 416 reported by curl as success (exit 0): treated as the 416 failure, exit 22, '
              '.part kept, manual rm step named', 'rc %r, err %r' % (rc, err[-300:]))
    # RV-P5-07 NIT-5 (b): when the MD5 of the .part could not be computed before the request, the 416 text must not
    # claim an MD5 mismatch or advise deleting a file that may be the complete release.
    for mode in ('range416', 'range416ok'):
        rc, err, args = call(mode, pre=md5fail + ':')
        rep.check(rc == 22 and read(part) == b'complete-but-corrupt' and 'could NOT be computed' in err
                  and 'its MD5 is not the pin' not in err and 'Do not delete it yet' in err,
                  'fixture full download HTTP 416 (%s) with a failing MD5 tool: exit 22, .part kept, the message says the '
                  'MD5 could not be computed and does not claim a mismatch' % mode, 'rc %r, err %r' % (rc, err[-300:]))
    print('INFO fixture full download 416 with failing MD5 tool: %s' % err.strip().split('\n')[-2][:400])
    rc, err, args = call('noresume')
    rep.check(rc == 33 and read(part) == b'complete-but-corrupt' and rm_target(err) == part,
              'fixture full download cannot resume (curl 33): non-zero exit, .part kept, RECOVERY names the manual step',
              'rc %r, err %r' % (rc, err[-300:]))
    # RV-P5-03 N3: a path with a quote and spaces is printed shell-escaped and parses back to the same path.
    odd_dest = os.path.join(d, "it's a dir; echo x", 'dblp-fixture.xml.gz')
    os.makedirs(os.path.dirname(odd_dest))
    with open(odd_dest + '.part', 'wb') as f:
        f.write(b'complete-but-corrupt')
    rc, err, args = call('range416', to=odd_dest)
    rep.check(rc == 22 and rm_target(err) == odd_dest + '.part' and os.path.isfile(odd_dest + '.part'),
              'fixture full download: the printed "rm <path>" step is shell-escaped (printf %q); a path holding a quote, '
              'spaces and ";" parses back to exactly that one path', 'rc %r, parsed %r, err %r'
              % (rc, rm_target(err), err[-300:]))
    os.remove(part)
    rc, err, args = call('body', wrong)
    rep.check(rc == 1 and not os.path.exists(part) and not os.path.exists(dest) and 'DELETED' in err and part in err,
              'fixture full download completed with the wrong MD5: exit 1, .part deleted and the message says so and why',
              'rc %r, err %r' % (rc, err[-300:]))
    # RV-P5-03 N4: an MD5 tool that fails never deletes a completed download.
    rc, err, args = call('body', good, pre=md5fail + ':')
    rep.check(rc == 1 and read(part) == good_bytes and not os.path.exists(dest) and 'cannot compute the MD5' in err
              and 'KEPT' in err,
              'fixture full download with a failing MD5 tool: exit 1, the completed .part is KEPT (not deleted, not '
              'renamed) and the message says why', 'rc %r, err %r' % (rc, err[-300:]))
    os.remove(part)
    rc, err, args = call('body', good)
    sha_ok = os.path.isfile(dest + '.sha256') and read(dest + '.sha256').decode('ascii').strip() == sha256_file(dest)
    rep.check(rc == 0 and read(dest) == good_bytes and sha_ok and not os.path.exists(part)
              and not os.path.exists(dest + '.sha256.tmp') and fetched['last'] == len(good_bytes),
              'fixture full download success: MD5 pin and gzip -t pass, file renamed, sha256 sidecar written, '
              '%d transferred bytes counted (RV-P5-03 N5)' % len(good_bytes),
              'rc %r, fetched %r, err %r' % (rc, fetched['last'], err[-300:]))
    # RV-P5-02 nit 12: a crash after the rename, before the sidecar: the next run re-checks and writes the sidecar.
    os.remove(dest + '.sha256')
    rc, err, args = call('unexpected')
    sha_ok = os.path.isfile(dest + '.sha256') and read(dest + '.sha256').decode('ascii').strip() == sha256_file(dest)
    rep.check(rc == 0 and args is None and sha_ok and fetched['last'] == 0,
              'fixture full download: a final file without its sidecar (crash between rename and sidecar) is re-checked '
              '(MD5 pin, gzip -t), the sidecar is written, nothing is downloaded again', 'rc %r, curl called %r, err %r'
              % (rc, args is not None, err[-300:]))
    os.remove(dest + '.sha256')
    with open(dest, 'wb') as f:
        f.write(read(wrong))
    rc, err, args = call('body', good)
    sha_ok = os.path.isfile(dest + '.sha256') and read(dest + '.sha256').decode('ascii').strip() == sha256_file(dest)
    rep.check(rc == 0 and args is not None and read(dest) == good_bytes and sha_ok,
              'fixture full download: a final file without a sidecar that fails the MD5 pin is NOT blessed; it is '
              'replaced only by a new download that passes both checks', 'rc %r, err %r' % (rc, err[-300:]))
    os.remove(dest)
    os.remove(dest + '.sha256')
    put(good_bytes)
    rc, err, args = call('unexpected')
    rep.check(rc == 0 and args is None and os.path.isfile(dest) and fetched['last'] == 0,
              'fixture full download: a .part that already matches the pin is not requested again (0 bytes counted)',
              'rc %r, curl called %r' % (rc, args is not None))
    with open(os.path.join(os.path.dirname(lib), 'fetch-dblp.sh'), encoding='utf-8') as f:
        src = f.read()
    full_branch = src.split('\n  full)\n', 1)[-1] if '\n  full)\n' in src else ''
    rep.check('ds_full_download "$URL" "$dest" "$PIN_MD5"' in full_branch and 'curl ' not in full_branch,
              'fixture: fetch-dblp.sh --full uses ds_full_download (the tested function) and no other curl call',
              'fetch-dblp.sh --full does not call ds_full_download')

    # RV-P5-07 MINOR-1: a symbolic link anywhere on the write path is refused before any network access; the link is
    # never followed, deleted or written through. Each case has its own download root (DS_DOWNLOADS) and an
    # "outside" directory holding a victim file; without the refusal each case would write outside the root or
    # into the victim (curl -C - appends to a linked .part, the sidecar write follows a linked .sha256.tmp).
    def link_case(name, make):
        base = os.path.join(d, 'links-' + name)
        outside = os.path.join(d, 'outside-' + name)
        os.makedirs(outside)
        victim = os.path.join(outside, 'victim')
        with open(victim, 'wb') as f:
            f.write(b'victim-bytes')
        dst = os.path.join(base, 'dblp', 'full', 'dblp-fixture.xml.gz')
        link = make(base, dst, outside, victim)
        rc, err, args = call('body', good, to=dst, base=base)
        ok = rc == 2 and args is None and read(victim) == b'victim-bytes' and sorted(os.listdir(outside)) == ['victim'] \
            and os.path.islink(link) and 'is a symbolic link' in err and link in err
        rep.check(ok, 'fixture full download symlink (%s): refused with exit 2 before any network access (curl not '
                  'called), the link is kept and nothing is written through it' % name,
                  'rc %r, curl called %r, victim %r, outside %r, link kept %r, err %r'
                  % (rc, args is not None, read(victim), sorted(os.listdir(outside)), os.path.islink(link), err[-300:]))
        return err

    def mk_root_link(base, dst, outside, victim):
        os.symlink(outside, base)
        return base

    def mk_dir_link(base, dst, outside, victim):
        os.makedirs(os.path.dirname(os.path.dirname(dst)))
        os.symlink(outside, os.path.dirname(dst))
        return os.path.dirname(dst)

    def mk_file_link(suffix):
        def mk(base, dst, outside, victim):
            os.makedirs(os.path.dirname(dst))
            os.symlink(victim, dst + suffix)
            return dst + suffix
        return mk
    err = link_case('download-root', mk_root_link)
    print('INFO fixture full download symlink refusal: %s' % [ln for ln in err.split('\n') if 'symbolic link' in ln][:1])
    link_case('directory-component', mk_dir_link)
    link_case('part-file', mk_file_link('.part'))
    link_case('sidecar-tmp', mk_file_link('.sha256.tmp'))
    link_case('sidecar', mk_file_link('.sha256'))
    link_case('final-file', mk_file_link(''))


def _er_fixture_full_wrapper(rep, tmp, real_run):
    """RV-P5-03 N6: EXECUTES fetch-dblp.sh --full (not only a text check). The three real scripts are copied
    byte for byte into a throw-away repository root whose minimal manifest pins a .invalid URL and the MD5 of a
    fixture body; curl is the stub first on PATH. Nothing is written to the repository and no network is used."""
    d = os.path.join(tmp, 'full-wrapper')
    repo = os.path.join(d, 'repo')
    sdir = os.path.join(repo, 'datasets', 'scripts')
    os.makedirs(sdir)
    here = os.path.dirname(os.path.abspath(__file__))
    for name in ('fetch-dblp.sh', 'lib-datasets.sh', 'dsutil.py'):
        shutil.copyfile(os.path.join(here, name), os.path.join(sdir, name))
    stub = os.path.join(d, 'stub')
    os.makedirs(stub)
    with open(os.path.join(stub, 'curl'), 'w', encoding='ascii', newline='\n') as f:
        f.write(ER_FX_CURL_STUB)
    with open(os.path.join(stub, 'python3'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('#!/bin/bash\nexec %s "$@"\n' % shlex.quote(sys.executable))
    for name in ('curl', 'python3'):
        os.chmod(os.path.join(stub, name), 0o700)
    good, wrong = os.path.join(d, 'good.gz'), os.path.join(d, 'wrong.gz')
    for p, txt in ((good, b'fixture wrapper release body\n'), (wrong, b'another body\n')):
        with open(p, 'wb') as f:
            with gzip.GzipFile(filename='', mode='wb', fileobj=f, mtime=0) as z:
                z.write(txt)
    with open(good, 'rb') as f:
        good_bytes = f.read()
    sid = ER_DBLP_RELEASE_ID
    with open(os.path.join(repo, MANIFEST_REL), 'w', encoding='utf-8', newline='\n') as f:
        f.write('datasets:\n  dblp:\n    snapshot:\n      id: %s\n    official_source:\n'
                '      download_url: "https://fixture.invalid/%s.xml.gz"\n'
                '      checksum_url: "https://fixture.invalid/%s.xml.gz.md5"\n'
                '      dtd_url: "https://fixture.invalid/dblp.dtd"\n'
                '    integrity:\n      upstream_checksum:\n        value: %s\n'
                % (json.dumps(sid), sid, sid, json.dumps(hashlib.md5(good_bytes).hexdigest())))
    dest = os.path.join(repo, 'datasets', 'downloads', 'dblp', 'full', sid + '.xml.gz')
    part = dest + '.part'
    log = os.path.join(d, 'curl-args.log')

    def run(confirm, mode, body=None):
        if os.path.exists(log):
            os.remove(log)
        env = {'PATH': stub + ':/usr/bin:/bin:/usr/sbin:/sbin', 'HOME': d, 'LC_ALL': 'C', 'FX_CURL_MODE': mode,
               'FX_CURL_LOG': log, 'FX_CURL_BODY': body or '/dev/null'}
        if confirm is not None:
            env['SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD'] = confirm
        r = real_run(['/bin/bash', os.path.join(sdir, 'fetch-dblp.sh'), '--full'], cwd=repo, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, env=env, timeout=60)
        return r.returncode, r.stderr.decode('utf-8', 'replace'), os.path.exists(log)

    for label, confirm in (('without the confirmation variable', None), ('with a wrong value', 'dblp-2026-11-01')):
        rc, err, called = run(confirm, 'unexpected')
        rep.check(rc == 3 and not called and not os.path.exists(os.path.join(repo, 'datasets', 'downloads')),
                  'fixture fetch-dblp.sh --full %s: exit 3, curl not called, nothing written' % label,
                  'rc %r, curl called %r, err %r' % (rc, called, err[-300:]))
    rc, err, called = run(sid, 'stall')
    rep.check(rc == 28 and called and os.path.isfile(part) and 'RECOVERY' in err and 'exit 28' in err
              and not os.path.exists(dest),
              'fixture fetch-dblp.sh --full stall: the wrapper exits 28 (curl exit passed through), .part kept',
              'rc %r, err %r' % (rc, err[-300:]))
    rc, err, called = run(sid, 'body', wrong)
    rep.check(rc == 1 and not os.path.exists(part) and not os.path.exists(dest) and 'DELETED' in err,
              'fixture fetch-dblp.sh --full wrong MD5: exit 1, corrupt .part deleted, no final file',
              'rc %r, err %r' % (rc, err[-300:]))
    rc, err, called = run(sid, 'body', good)
    sha_ok = os.path.isfile(dest + '.sha256') and open(dest + '.sha256').read().strip() == sha256_file(dest)
    rep.check(rc == 0 and sha_ok and ('done (mode --full, fetched %d bytes)' % len(good_bytes)) in err
              and 'size bytes: %d' % len(good_bytes) in err,
              'fixture fetch-dblp.sh --full success: exit 0, file and sidecar written, the final log line states the '
              '%d bytes fetched' % len(good_bytes), 'rc %r, err %r' % (rc, err[-400:]))
    rc, err, called = run(sid, 'unexpected')
    rep.check(rc == 0 and not called and 'fetched 0 bytes' in err,
              'fixture fetch-dblp.sh --full re-run: already verified, curl not called, fetched 0 bytes',
              'rc %r, curl called %r, err %r' % (rc, called, err[-300:]))
    # RV-P5-07 MINOR-1 through the real wrapper: datasets/downloads itself is a symbolic link to a directory outside
    # the repository root. The command exits 2 before curl, and nothing is written through the link.
    downloads = os.path.join(repo, 'datasets', 'downloads')
    os.rename(downloads, downloads + '-real')
    outside = os.path.join(d, 'outside-downloads')
    os.makedirs(outside)
    os.symlink(outside, downloads)
    rc, err, called = run(sid, 'body', good)
    rep.check(rc == 2 and not called and os.listdir(outside) == [] and os.path.islink(downloads)
              and 'datasets/downloads is a symbolic link' in err,
              'fixture fetch-dblp.sh --full with datasets/downloads a symbolic link: exit 2, curl not called, nothing '
              'written through the link, the link is kept', 'rc %r, curl called %r, outside %r, err %r'
              % (rc, called, os.listdir(outside), err[-300:]))


def cmd_er_fixture(root):
    rep = Report()
    src = os.path.join(root, ER_FIXTURE_XML_REL)
    if rep.check(os.path.isfile(src), 'fixture: %s exists' % ER_FIXTURE_XML_REL, 'missing'):
        with open(src, 'rb') as f:
            raw = f.read()
        calls = []

        def no_network(*a, **k):
            calls.append(a[:1])
            raise RuntimeError('network call attempted during the fixture')
        g = globals()
        saved = (g['er_curl'], subprocess.run)
        g['er_curl'] = no_network
        subprocess.run = no_network
        try:
            with tempfile.TemporaryDirectory(prefix='scholarchain-er-fixture-') as tmp:
                _er_fixture_cases(rep, root, raw, tmp)
                _er_fixture_layouts(rep, raw, tmp)
                _er_fixture_budget_and_names(rep, raw, tmp)
                _er_fixture_orchestration(rep, root, raw, tmp)
                _er_fixture_crossref_split(rep, root, raw, tmp)
                # The one sanctioned subprocess: bash with a curl STUB first on PATH and a .invalid URL.
                _er_fixture_full_download(rep, tmp, saved[1])
                _er_fixture_full_wrapper(rep, tmp, saved[1])
        finally:
            g['er_curl'], subprocess.run = saved
        rep.check(not calls, 'fixture: no network call was attempted (er_curl and subprocess.run trapped)',
                  '%d attempted' % len(calls))
    print('er-fixture: %d checks, %d failures (temporary directory removed; repository not written)'
          % (rep.checks, rep.failures))
    return 1 if rep.failures else 0


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
    if len(argv) == 3 and argv[0] == 'er-fetch' and argv[2] in ('dry-run', 'sample'):
        return er_fetch(argv[1], argv[2])
    if len(argv) == 2 and argv[0] == 'er-verify':
        return cmd_er_verify(argv[1])
    if len(argv) == 3 and argv[0] == 'er-verify' and argv[2] == '--with-release':
        return cmd_er_verify(argv[1], True)
    if len(argv) == 2 and argv[0] == 'er-fixture':
        return cmd_er_fixture(argv[1])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
