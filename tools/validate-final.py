#!/usr/bin/env python3
"""Reproducible checks using already-provisioned tools only. Installs nothing."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--browser',action='store_true');parser.add_argument('--benchmark',action='store_true');args=parser.parse_args()
    output=ROOT/'docs/dev';output.mkdir(exist_ok=True)
    checks=[]
    def run(name,command,json_result=False):
        result=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=120)
        (output/f'NEXUS-FINAL-01-{name}.txt').write_text(result.stdout)
        record={'name':name,'command':command,'exit_code':result.returncode,'log':f'NEXUS-FINAL-01-{name}.txt'}
        if json_result and result.returncode==0:record['result']=json.loads(result.stdout)
        checks.append(record);print(f'{name}: exit={result.returncode}')
    run('SQLITE',[sys.executable,'-m','unittest','discover','-s','tests/final','-v'])
    run('ZIM',[sys.executable,'-m','unittest','discover','-s','tests/zim','-v'])
    if shutil.which('node'):
        run('GRAPH',['node','--test','tests/final/test_graph.cjs'])
        if args.browser:run('BROWSER',['node','tests/final/browser.cjs'],True)
    else:checks.append({'name':'GRAPH','not_executed':'Node absent; no installation attempted'})
    if args.benchmark:run('BENCHMARK',[sys.executable,'tests/final/sqlite_lab.py'],True)
    run('DIFF',['git','diff','--check'])
    tracked=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines()
    relevant=[p for p in tracked if p.endswith(('.kt','.py','.js','.cjs','.html','.css'))]
    report={'format':'jarvis.campaign-validation/1','mission':'NEXUS-FINAL-01','module':'NEXUS','branch':'feat/nexus-final-01','base_commit':'bbccac2ff2cc052f897c7f5015f234507e047b95','recorded_utc':datetime.now(timezone.utc).isoformat(),'qualification':'partial_SQLite_schema_and_viewer_tested_JVM_and_Android_unqualified','checks':checks,'source_sha256':{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in relevant},'not_executed':['Kotlin compilation and JUnit suites: Gradle/Kotlin/JDK17 toolchain absent, no installation allowed','Actual SERVICES PDF extractor / DOCX / CSV runtime tests in new JVM pipeline','Native libzim on authorized real Wikipedia archive: archive and binding unavailable','Android ABI/API24/SAF or physical mini-PC measurement','Local embedding model quality and full semantic recall','Encrypted v3/v4 snapshot integration regression (JUnit not run)'],'preservation':{'merged':False,'other_repositories_modified':False,'personal_data_transferred':False,'wikipedia_copied_or_reindexed':False,'installations':False},'scope_notes':['SQLite timings measure the actual production DDL and FTS using Python, not Kotlin import throughput','Chromium touch viewport is emulation, not Android qualification','Canvas timings measure synchronous draw calls, not compositor frame rate','Old ZIM tests include one real-archive test skipped; synthetic sparse-file results do not qualify Wikipedia','No cloud adapter, HTTP document API, OCR, model or background watcher enabled']}
    (output/'NEXUS-FINAL-01-VALIDATION.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    return int(any(c.get('exit_code',0)!=0 for c in checks))

if __name__=='__main__':sys.exit(main())
