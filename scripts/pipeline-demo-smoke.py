#!/usr/bin/env python3
"""Verify the separate DBLP-derived local profile. No AWS or paid model calls."""
import argparse
import json
import pathlib
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUN = 'run:a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40'
PROFILE = 'pipeline-demo; DBLP-DERIVED; NON-PRODUCTION'
KEY = 'scholarchain-local-demo-key-NOT-A-SECRET'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, default=ROOT / 'target/pipeline-demo-evidence')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'summary.json').unlink(missing_ok=True)
    with tempfile.TemporaryFile(mode='w+') as log:
        process = subprocess.Popen(['bash', str(ROOT / 'scripts/pipeline-demo.sh'), '--port', '0'],
                                   cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 90
            while True:
                log.seek(0)
                banner = log.read()
                match = re.search(r'profile=pipeline-demo listening=(http://127\.0\.0\.1:\d+)', banner)
                if match:
                    break
                require(process.poll() is None and time.monotonic() < deadline,
                        'Pipeline demo did not start: ' + banner[-12000:])
                time.sleep(0.1)
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            results = []

            def call(name, path, body=None, authenticated=True, expected=200, idem=None):
                headers = {'Authorization': 'Bearer ' + KEY} if authenticated else {}
                if body is not None:
                    headers['Content-Type'] = 'application/json'
                if idem:
                    headers['Idempotency-Key'] = idem
                request = urllib.request.Request(match.group(1) + path,
                                                data=json.dumps(body).encode() if body else None,
                                                headers=headers)
                try:
                    response = opener.open(request, timeout=30)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    status, profile, raw = response.code, response.headers.get('X-ScholarChain-Profile'), response.read()
                require(status == expected, f'{name}: HTTP {status}: {raw.decode()}')
                require(profile == PROFILE, f'{name}: missing profile disclosure')
                value = json.loads(raw)
                (args.output / (name + '.json')).write_text(json.dumps(value, indent=2) + '\n')
                results.append({'check': name, 'httpStatus': status})
                return value

            call('health', '/healthz', authenticated=False)
            body = {'question': 'Who are the coauthors of Ailing Zeng?', 'mode': 'sync'}
            answer = call('query', '/v1/query', body, idem='real-data-smoke-1')
            require(answer['verification']['overall'] == 'VERIFIED', json.dumps(answer))
            require(answer['nonProductionModel'] is True, 'template model disclosure is missing')
            require(RUN in json.dumps(answer), 'actual analysis lineage is missing')
            claims = answer['answer']['claims']
            require(bool(claims) and all(c['evidenceIds'] for c in claims), 'claims have no citations')
            edges = [json.loads(i['text']) for i in answer['answer']['evidenceBundle']['items']
                     if i['evidenceType'] == 'coauthor-edge']
            require(len(edges) == 3 and all(e['jointPublicationCount'] == 1 and
                    e['supportingWorkIds'] == ['work:dblp:conf/aaai/ZengCZ023'] for e in edges),
                    'coauthor evidence differs from the accepted DBLP paper')
            graph = call('graph', '/v1/graph/nodes/researcher:orcid:0000-0002-3783-0679/neighbors')
            require(len(graph['items']) == 3, 'expected three collaborators in the graph route')
            evidence_id = claims[0]['evidenceIds'][0]
            evidence = call('evidence', '/v1/evidence/' + evidence_id)
            require(evidence['evidenceId'] == evidence_id, 'citation does not resolve')
            call('explain', '/v1/explain/' + answer['queryId'])
            replay = call('replay', '/v1/query', body, idem='real-data-smoke-1')
            require(replay['queryId'] == answer['queryId'] and replay['answer'] == answer['answer'], 'replay changed')
            call('unauthorized', '/v1/query', body, authenticated=False, expected=401)
            (args.output / 'summary.json').write_text(json.dumps({
                'result': 'PASS', 'profile': PROFILE, 'analysisRunId': RUN,
                'limitations': ['in-memory stores', 'template answer model', 'hashed-token stand-in embeddings',
                                'bounded dev sample, not full corpus or cloud deployment'],
                'checks': results, 'queryId': answer['queryId']}, indent=2) + '\n')
            print('DBLP PIPELINE DEMO PASS: real-data query, source paper, graph, citation, explain, replay, authentication')
            print('Evidence: ' + str(args.output.resolve()))
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            log.seek(0)
            (args.output / 'startup.log').write_text(log.read())


if __name__ == '__main__':
    main()
