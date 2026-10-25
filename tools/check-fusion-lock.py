#!/usr/bin/env python3
"""Offline source/evidence verifier; never fetches, migrates, installs or enables anything.
This is a NEXUS lab receipt, not the authoritative UNIFIED F0 composition lock.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re

ROOT=Path(__file__).resolve().parents[1]

def verify(manifest):
    data=json.loads(manifest.read_text())
    assert data['format']=='jarvis.nexus.fusion-lock/1'
    assert data['scope']=='NEXUS_LAB_ONLY_NOT_UNIFIED_F0'
    assert data['overall_status']=='PARTIAL'
    assert set(data['activation'])=={'core_android_connected','native_llm_enabled','zim_android_enabled','ipc_enabled','personal_device_installation','personal_data_migrated','merged_main'}
    assert all(value is False for value in data['activation'].values()), 'Sensitive/UNKNOWN activation is not authorized by a receipt'
    assert re.fullmatch('[0-9a-f]{40}',data['base_sha'])
    for reference in data['references']:
        assert re.fullmatch('[0-9a-f]{40}',reference['sha'])
    total=0
    for section in ('source_sha256','evidence_sha256','retained_sha256'):
        records=data[section];assert len(records)<=1000
        for name,expected in records.items():
            path=PurePosixPath(name)
            assert not path.is_absolute() and '..' not in path.parts
            assert re.fullmatch('[0-9a-f]{64}',expected)
            target=ROOT.joinpath(*path.parts)
            assert target.is_file() and not target.is_symlink() and target.stat().st_size<=2*1024*1024
            assert hashlib.sha256(target.read_bytes()).hexdigest()==expected, 'Digest mismatch: '+name
            total+=1
    for name,file in [('jvm','jvm-results.json'),('robolectric','android-results.json')]:
        rows=json.loads((ROOT/'docs/fusion-01/evidence'/file).read_text())
        measured={key:sum(int(row[key]) for row in rows) for key in ['tests','failures','errors','skipped']}
        assert data['tests'][name]==measured and measured['tests']>0
        assert all(measured[key]==0 for key in ['failures','errors','skipped'])
    assert data['tests']['instrumented']['executed']==0
    print(json.dumps({'verified_files':total,'receipt_status':'PARTIAL','network_used':False,
                      'physical_or_production_qualification':False}))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',type=Path,default=ROOT/'docs/fusion-01/integration-lock.json')
    verify(parser.parse_args().manifest)
