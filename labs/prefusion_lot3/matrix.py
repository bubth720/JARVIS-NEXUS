"""Offline review of FTS5 laboratory observations; never a backend selector.

A record is author-provided evidence, not an OS attestation. Runtime stays OFF.
No database, URI, content, process, dependency, model or network is opened here.
"""
import argparse
import json
from pathlib import Path
import re

SCHEMA = 'nexus.prefusion.fts5-observation/1'
STAGES = ('OPEN', 'VERSION', 'CREATE', 'MATCH', 'VOCAB_INSTANCE', 'BM25', 'COMPLETE')
SCOPES = {'HOST_SQLITE', 'ROBOLECTRIC', 'ANDROID_INSTRUMENTATION', 'PHYSICAL_ANDROID'}
FIELDS = {'schema', 'scope', 'build_commit', 'api', 'abis', 'sqlite_version',
          'last_stage', 'status', 'production_enabled', 'checks'}
CHECKS = {'create', 'match', 'vocab_instance', 'bm25', 'close_reopen', 'migration_rollback'}


class ObservationError(ValueError):
    pass


def strict_json(text):
    def pairs(items):
        out = {}
        for k,v in items:
            if k in out: raise ObservationError('Duplicate field')
            out[k] = v
        return out
    try:
        value = json.loads(text, object_pairs_hook=pairs,
                           parse_constant=lambda _: (_ for _ in ()).throw(ObservationError('Non-finite value')))
        json.dumps(value, ensure_ascii=False).encode('utf-8')
        return value
    except (ValueError, UnicodeError) as failure:
        raise ObservationError('Invalid JSON observation') from failure


def validate(value):
    if not isinstance(value, dict) or set(value) != FIELDS:
        raise ObservationError('Closed observation fields required')
    if value['schema'] != SCHEMA or not isinstance(value['scope'],str) or value['scope'] not in SCOPES:
        raise ObservationError('Unsupported observation version/scope')
    if not isinstance(value['build_commit'], str) or not re.fullmatch('[0-9a-f]{40}', value['build_commit']):
        raise ObservationError('Full immutable build SHA required')
    if type(value['api']) is not int or not 0 <= value['api'] <= 100:
        raise ObservationError('Invalid API')
    if value['scope'] in {'ANDROID_INSTRUMENTATION','PHYSICAL_ANDROID'} and value['api'] < 24:
        raise ObservationError('API24 minimum is preserved')
    if not isinstance(value['abis'],list) or len(value['abis']) > 4 or any(not isinstance(a,str) for a in value['abis']) or len(set(value['abis'])) != len(value['abis']) or any(
            a not in {'x86','x86_64','armeabi-v7a','arm64-v8a','host'} for a in value['abis']):
        raise ObservationError('Closed ABI names required')
    if value['scope'] in {'ANDROID_INSTRUMENTATION','PHYSICAL_ANDROID'} and (not value['abis'] or 'host' in value['abis']):
        raise ObservationError('Android process ABI observation required')
    version=value['sqlite_version']
    if version is not None and (not isinstance(version,str) or not re.fullmatch(r'\d{1,3}\.\d{1,3}\.\d{1,3}(?:\.\d{1,3})?',version)):
        raise ObservationError('Invalid SQLite version')
    if not isinstance(value['last_stage'],str) or not isinstance(value['status'],str) or value['last_stage'] not in STAGES or value['status'] not in {'SUPPORTED','UNSUPPORTED','UNKNOWN','OFF'}:
        raise ObservationError('Invalid stage/status')
    if value['production_enabled'] is not False:
        raise ObservationError('No production activation allowed')
    checks=value['checks']
    if not isinstance(checks,dict) or set(checks) != CHECKS or any(not isinstance(c,str) or c not in {'PASS','FAIL','NOT_RUN','UNSUPPORTED'} for c in checks.values()):
        raise ObservationError('Explicit outcomes for all primitives required')
    if value['status']=='SUPPORTED' and (value['last_stage']!='COMPLETE' or not version or any(c!='PASS' for c in checks.values())):
        raise ObservationError('SUPPORTED needs every actual SQL/cleanup/rollback check')
    if value['status']=='OFF' and any(c!='NOT_RUN' for c in checks.values()):
        raise ObservationError('OFF cannot claim an executed primitive')
    if value['status']=='UNSUPPORTED' and not any(c=='UNSUPPORTED' for c in checks.values()):
        raise ObservationError('Unsupported must name the absent primitive')
    return value


def summarize(records):
    for record in records: validate(record)
    identities=[(r['build_commit'],r['scope'],r['api'],tuple(r['abis'])) for r in records]
    if len(identities)!=len(set(identities)): raise ObservationError('Duplicate or conflicting observation')
    rows=[]
    for api in (24,25,26,35):
        evidence=[r for r in records if r['scope']=='ANDROID_INSTRUMENTATION' and r['api']==api]
        statuses={r['status'] for r in evidence}
        verdict='NOT_RUN' if not evidence else next(iter(statuses)) if len(statuses)==1 else 'UNKNOWN'
        rows.append({'api':api,'instrumented_sql_primitives':verdict,
                     'observation_count':len(evidence),'rag_saf_qualified':False,
                     'production_enabled':False})
    return {'schema':'nexus.prefusion.matrix/1','rows':rows,
            'host_observations':sum(r['scope'] in {'HOST_SQLITE','ROBOLECTRIC'} for r in records),
            'backend_decision':'PENDING_USER_APPROVAL','runtime_gate':'BLOCKED',
            'limitations':['Author records are not cryptographic platform attestation.',
                           'SQL primitives do not qualify SAF, RAG or another device/ABI.']}


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('observations',type=Path)
    args=parser.parse_args(argv)
    try:
        if args.observations.stat().st_size > 65536:raise ObservationError('Input limit')
        data=strict_json(args.observations.read_text(encoding='utf-8'))
        if not isinstance(data,list) or len(data)>32:raise ObservationError('Bounded list required')
        print(json.dumps(summarize(data),indent=2));return 0
    except (ObservationError,OSError,TypeError) as failure:
        print('Observation refused: '+type(failure).__name__);return 1

if __name__=='__main__':raise SystemExit(main())
