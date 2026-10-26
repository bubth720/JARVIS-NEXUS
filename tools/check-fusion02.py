#!/usr/bin/env python3
"""Verify NEXUS FUSION-02 receipt offline, without install, migration or activation.
Hashes establish receipt consistency, not signed device provenance or a global GO.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def verify(root, validation=None, provenance=None):
    root=Path(root).resolve()
    v=validation if validation is not None else json.loads((root/'docs/fusion-02/VALIDATION.json').read_text())
    p=provenance if provenance is not None else json.loads((root/'docs/fusion-02/PROVENANCE.json').read_text())
    def require(condition,message):
        if not condition:raise ValueError(message)
    require(v['format']=='jarvis.nexus.fusion02.validation/1','Unexpected validation format')
    require(p['format']=='jarvis.nexus.fusion02.provenance/1','Unexpected provenance format')
    require(v['overall']=='PARTIAL' and v['androidRagGo'] is False,'Android/global GO is not qualified')
    require(set(v['activation'])=={'externalSqlite','personalInstallation','personalMigration','ipc','mainMerge'},'Activation keys differ')
    require(all(x is False for x in v['activation'].values()),'Sensitive activation is not authorized')
    require(p['base']=='9d6928cd8651f83fa5091bdb474b33761d366412','Unexpected integration base')
    require(p['branch']=='feat/fusion-02-nexus-android','Unexpected branch receipt')
    count=0
    for group in ['sources','evidence']:
        require(bool(p[group]),'Empty hash group')
        for name,digest in p[group].items():
            path=root/name
            require(not Path(name).is_absolute() and '..' not in Path(name).parts,'Unsafe receipt path')
            require(not path.is_symlink() and path.resolve().is_relative_to(root),'Receipt escaped repository')
            require(hashlib.sha256(path.read_bytes()).hexdigest()==digest,'Hash mismatch: '+name)
            count+=1
    for kind,folder in [('jvm','jvm'),('robolectric','robolectric')]:
        suites=[ET.parse(path).getroot() for path in (root/'docs/fusion-02/evidence'/folder).glob('TEST-*.xml')]
        require(bool(suites),'No JUnit evidence')
        actual={field:sum(int(s.attrib.get(field,0)) for s in suites) for field in ['tests','failures','errors','skipped']}
        require(actual==v[kind],'Invented '+kind+' count')
        require(actual['failures']==actual['errors']==actual['skipped']==0,'Suite not fully passed')
    require(v['jvm']['tests']>=95 and v['robolectric']['tests']>=71,'Regression suite removed')
    for api,row in v['android'].items():
        require(row['rag'] in ['UNSUPPORTED','NOT_RUN'],'Unqualified RAG promotion')
        require(row['physical']=='NOT_RUN','No physical device qualified')
        if row['instrumentation']=='PASS':
            require(api in ['24','25'],'Unexpected emulator campaign')
            raw=(root/row['raw']).read_text()
            require(re.search(r'OK \('+str(row['tests'])+r' tests\)',raw) is not None,'Missing Android runner success')
            require('INSTRUMENTATION_CODE: -1' in raw and 'FAILURES!!!' not in raw,'Android runner failed')
            log=(root/row['logcat']).read_text()
            probes=re.findall(r'FUSION02_PLATFORM_PROBE (\{[^\n]+\})',log)
            require(bool(probes),'No real process probe evidence')
            observed=json.loads(probes[-1])
            require(observed['api']==int(api) and observed['status']=='UNSUPPORTED','Wrong process/API capability')
            require(row['rag']=='UNSUPPORTED','Probe refusal cannot qualify RAG')
            require(row['abi']=='x86' and 'x86' in observed['abis'],'Wrong emulator ABI')
        else:
            require(row['instrumentation']=='NOT_RUN' and row['tests']==0,'Unknown instrumentation claim')
    require(v['android']['24']['rag']!='PASS' and v['android']['25']['rag']!='PASS','RAG gate requires positive API24+25 path')
    return {'verifiedFiles':count,'overall':v['overall'],'androidRagGo':False}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);args=parser.parse_args()
    try:print(json.dumps(verify(args.root),sort_keys=True))
    except (ValueError,KeyError,OSError,TypeError) as error:raise SystemExit(str(error))
