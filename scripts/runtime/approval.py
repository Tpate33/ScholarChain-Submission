#!/usr/bin/env python3
"""Offline approval-package validator; never talks to AWS or reads secrets/state/plans."""
import datetime as dt
import hashlib
import json
from pathlib import Path
import sys

UTC = dt.timezone.utc
GLOBAL_END = dt.datetime(2026, 11, 26, 5, tzinfo=UTC)


def stamp(s):
    value = dt.datetime.fromisoformat(s.replace('Z', '+00:00'))
    if value.utcoffset() != dt.timedelta(0):
        raise ValueError('UTC timestamp required')
    return value


def validate(data, now, allow_expired=False):
    required = {'schemaVersion', 'runId', 'start', 'runtimeDeadline', 'neptuneDeadline',
                'endpointDeadline', 'graphExperimentDeadline', 'emrDeadline', 'verifiedPriorSpendUsd',
                'incrementalEnvelopeUsd', 'totalVectorCount', 'totalEmbeddingCalls',
                'totalAnswerCalls', 'graphRequestCap', 'artifactSha256', 'account',
                'region', 'inventoryReviewed', 'quotasReviewed', 'modelsReviewed',
                'censusReviewed', 'cleanupReviewed', 'secretsPopulated', 'monitorTested'}
    if set(data) != required:
        raise ValueError('unexpected or missing approval fields')
    if data['schemaVersion'] != 1 or data['region'] != 'us-east-1':
        raise ValueError('unsupported schema/region')
    import re
    if not re.fullmatch(r'[0-9]{12}', data['account']) or not re.fullmatch(r'[a-z0-9-]{1,48}', data['runId']):
        raise ValueError('invalid account/run identifier')
    start = stamp(data['start'])
    for key, seconds in [('runtimeDeadline',7200),('neptuneDeadline',10800),('endpointDeadline',21600),('emrDeadline',7200)]:
        end = stamp(data[key])
        if not start < end <= GLOBAL_END or (end-start).total_seconds() > seconds:
            raise ValueError(key + ' exceeds window')
    if not allow_expired and not start <= now < stamp(data['runtimeDeadline']):
        raise ValueError('outside approved admission window')
    if not start < stamp(data['graphExperimentDeadline']) <= stamp(data['runtimeDeadline']):
        raise ValueError('graph experiment outside runtime window')
    for key in ['inventoryReviewed','quotasReviewed','modelsReviewed','censusReviewed',
                'cleanupReviewed','secretsPopulated','monitorTested']:
        if data[key] is not True:
            raise ValueError('incomplete launch gate: ' + key)
    for key, cap in [('totalVectorCount',10000),('totalEmbeddingCalls',10500),('totalAnswerCalls',100),('graphRequestCap',50000)]:
        if type(data[key]) is not int or not 0 <= data[key] <= cap:
            raise ValueError(key + ' exceeds cap')
    from decimal import Decimal
    prior = Decimal(str(data['verifiedPriorSpendUsd']))
    incremental = Decimal(str(data['incrementalEnvelopeUsd']))
    if not prior.is_finite() or not incremental.is_finite() or prior < 0 or incremental != 12 or prior+incremental > 25:
        raise ValueError('gross cumulative spending condition not met')
    if not isinstance(data['artifactSha256'],dict) or not data['artifactSha256']:
        raise ValueError('immutable artifacts missing')
    for name, digest in data['artifactSha256'].items():
        if not re.fullmatch(r'[a-z0-9.-]+',name) or not re.fullmatch(r'[0-9a-f]{64}',digest):
            raise ValueError('invalid artifact hash')
    return data


def main():
    if len(sys.argv) != 2:
        raise ValueError('usage: approval.py approved-run.json')
    path = Path(sys.argv[1])
    if path.is_symlink() or path.name.startswith('.') or path.suffix != '.json':
        raise ValueError('regular explicit nonsecret JSON file required')
    raw = path.read_bytes()
    validate(json.loads(raw),dt.datetime.now(UTC))
    print(hashlib.sha256(raw).hexdigest())

if __name__ == '__main__':
    try:
        main()
    except (ValueError,KeyError,TypeError,ArithmeticError) as error:
        sys.exit('REFUSED: '+str(error))
