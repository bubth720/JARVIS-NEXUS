"""N01-A3 successor scope guard for the owner's one-file workflow exception.

Every file/mode from N01-A2 is frozen except the exact rendered NEX-07 routing
delta. Historical guards remain byte-identical and retain their original scope.
This is a source/CI check, never an execution or CORE authorization.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[2]
BASE='2dd4447acb592e9e63123c0377b6310d4dae2bf1'
WORKFLOW='.github/workflows/nex-07.yml'
REPOSITORY='bubth720/JARVIS-NEXUS'
PR_BASE='codex/m53-readonly-preparation'
PR_BASE_SHA='eae61fb62a9ccd4073b96d71454f89e5a943eae9'
PR_HEADS=('codex/reuse-first-r1plus-20261108','codex/nexus-n01-a3-routing-20261118')
ADDITIONS=frozenset({
    '.github/workflows/n01-a3-routing.yml',
    'reuse-r1plus/n01-a3/check_routing_scope.py',
    'reuse-r1plus/n01-a3/tests/test_routing_scope.py',
    'reuse-r1plus/n01-a3/README.md',
    'reuse-r1plus/n01-a3/AUTHORIZATION.json',
})

def git(root,*args):
    return subprocess.check_output(['git','-C',str(root),*args])

def expected_workflow(original):
    """Only the reviewed checkout and guard-selection substitutions are allowed."""
    text=original.decode('utf-8')
    old="${{ github.event_name == 'pull_request' && github.base_ref == 'mission/m50-integrated-beta-20261029' && github.head_ref == 'codex/m50-s1-nexus-ci-20261029' && github.event.pull_request.head.repo.full_name == github.repository && github.event.pull_request.head.sha || github.ref }}"
    reuse="github.base_ref == 'codex/m53-readonly-preparation' && (github.head_ref == 'codex/reuse-first-r1plus-20261108' || github.head_ref == 'codex/nexus-n01-a3-routing-20261118')"
    new="${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository && ((github.base_ref == 'mission/m50-integrated-beta-20261029' && github.head_ref == 'codex/m50-s1-nexus-ci-20261029') || ("+reuse+")) && github.event.pull_request.head.sha || github.ref }}"
    if text.count(old)!=2:raise ValueError('Unexpected historical checkout routing')
    text=text.replace(old,new)
    lot3="          LOT3_REVIEW: ${{ github.event_name == 'pull_request' && github.base_ref == 'mission/prefusion-lot3-20261027' && startsWith(github.head_ref, 'codex/prefusion-lot3-') }}\n"
    extra="          REUSE_REVIEW: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository && "+reuse+" }}\n"
    if text.count(lot3)!=2:raise ValueError('Unexpected historical LOT3 selection')
    text=text.replace(lot3,extra+lot3)
    old_branch='          elif [ "$LOT3_REVIEW" = "true" ]; then\n'
    new_branch='          elif [ "$REUSE_REVIEW" = "true" ]; then\n            python3 reuse-r1plus/n01-a3/check_routing_scope.py --ci-event\n            python3 -m unittest discover -s reuse-r1plus/n01-a3/tests -v\n'+old_branch
    if text.count(old_branch)!=2:raise ValueError('Unexpected historical guard selection')
    return text.replace(old_branch,new_branch).encode('utf-8')

def verify_ci_event(event,environment,head):
    if environment.get('GITHUB_EVENT_NAME')!='pull_request':raise ValueError('PR event required')
    if environment.get('GITHUB_REPOSITORY')!=REPOSITORY:raise ValueError('Repository mismatch')
    if type(event) is not dict:raise ValueError('Malformed PR event')
    def mapping(value):
        if type(value) is not dict:raise ValueError('Malformed PR metadata')
        return value
    pr=mapping(event.get('pull_request'));base=mapping(pr.get('base'));source=mapping(pr.get('head'))
    if mapping(event.get('repository')).get('full_name')!=REPOSITORY:raise ValueError('Event repository mismatch')
    if base.get('ref')!=PR_BASE or base.get('sha')!=PR_BASE_SHA or mapping(base.get('repo')).get('full_name')!=REPOSITORY:raise ValueError('Unreviewed PR base')
    if source.get('ref') not in PR_HEADS:raise ValueError('Unreviewed PR head')
    if mapping(source.get('repo')).get('full_name')!=REPOSITORY:raise ValueError('Fork is not reviewed')
    if source.get('sha')!=head:raise ValueError('Checkout is not exact PR head')
    if environment.get('GITHUB_BASE_REF')!=base['ref'] or environment.get('GITHUB_HEAD_REF')!=source['ref']:raise ValueError('PR environment mismatch')
    return {'event':'pull_request','base':base['ref'],'head':source['ref'],'source_commit':head,'same_repository':True}

def check(root=ROOT):
    root=Path(root).resolve()
    subprocess.run(['git','-C',str(root),'merge-base','--is-ancestor',BASE,'HEAD'],check=True,capture_output=True)
    frozen={}
    for record in git(root,'ls-tree','-rz',BASE).split(b'\0'):
        if not record:continue
        meta,name=record.split(b'\t',1);mode,kind,oid=meta.decode().split();name=name.decode()
        if kind!='blob':raise ValueError('Unsupported historical object: '+name)
        frozen[name]=mode
        path=root/name
        if path.is_symlink() or not path.is_file():raise ValueError('Missing/non-regular historical file: '+name)
        original=git(root,'cat-file','--filters',BASE+':'+name)
        expected=expected_workflow(original) if name==WORKFLOW else original
        if path.read_bytes()!=expected:raise ValueError('Unauthorized bytes: '+name)
        if bool(path.stat().st_mode&0o111)!=(mode=='100755'):raise ValueError('Unauthorized mode: '+name)
    actual=set(git(root,'ls-files','--cached','--others','--exclude-standard','-z').decode().split('\0'))-{''}
    if not set(frozen)<=actual:raise ValueError('Historical path removed')
    additions=actual-set(frozen)
    if additions!=ADDITIONS:raise ValueError('Unexpected additions: missing='+repr(sorted(ADDITIONS-additions))+', extra='+repr(sorted(additions-ADDITIONS)))
    for name in sorted(additions):
        path=root/name
        if path.is_symlink() or not path.is_file() or path.stat().st_mode&0o111:raise ValueError('Unsafe addition: '+name)
        if not path.read_text(encoding='utf-8').strip():raise ValueError('Empty addition: '+name)
    return {'result':'PASS','base':BASE,'source_commit':git(root,'rev-parse','HEAD').decode().strip(),
      'historical_files':len(frozen),'historical_files_unchanged':len(frozen)-1,
      'sole_exception':WORKFLOW,'exception_sha256':hashlib.sha256((root/WORKFLOW).read_bytes()).hexdigest(),
      'additions':sorted(additions),'historical_guards_changed':False,'contracts_changed':False,
      'runtime_authorized':False,'network_collection':False}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ci-event',action='store_true')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args();result=check()
    if args.ci_event:
        file=os.environ.get('GITHUB_EVENT_PATH')
        if not file:raise ValueError('Missing GitHub event file')
        result['ci_context']=verify_ci_event(json.loads(Path(file).read_text()),os.environ,result['source_commit'])
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))

if __name__=='__main__':main()
