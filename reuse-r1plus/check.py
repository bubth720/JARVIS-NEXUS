"""Additions only: preserve every M53 source, test, contract, fixture and mode."""
import json
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
BASE='eae61fb62a9ccd4073b96d71454f89e5a943eae9'


def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)


def main():
    subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],cwd=ROOT,check=True)
    frozen={}
    for entry in git('ls-tree','-rz',BASE).split(b'\0'):
        if not entry:continue
        meta,name=entry.split(b'\t');mode,kind,oid=meta.decode().split();name=name.decode()
        assert kind=='blob';frozen[name]=(mode,oid)
        p=ROOT/name
        assert p.is_file() and not p.is_symlink(),name
        assert git('hash-object','--',name).decode().strip()==oid,name
        assert bool(p.stat().st_mode & 0o111)==(mode=='100755'),name
    actual=set(git('ls-files','--cached','--others','--exclude-standard').decode().splitlines())
    assert set(frozen)<=actual
    added=sorted(actual-frozen.keys())
    assert all(p.startswith('reuse-r1plus/') or p=='.github/workflows/reuse-r1plus.yml' for p in added),added
    for name in added:
        p=ROOT/name
        assert p.is_file() and not p.is_symlink() and not (p.stat().st_mode & 0o111),name
        assert p.suffix in {'.py','.md','.kt','.gradle','.yml','.json','.txt'},name
        p.read_text(encoding='utf-8')
    result={'status':'PASS','base':BASE,'source_commit':git('rev-parse','HEAD').decode().strip(),
            'historical_files_preserved':len(frozen),'additions':added,
            'canonical_contracts_changed':False,'default_build_changed':False,
            'application_sources_changed':False,'database_migrated':False,'android':'NOT_RUN',
            'production_connected':False,'core_authority':'NOT_PROVIDED'}
    out=ROOT/'build/reuse-r1plus';out.mkdir(parents=True,exist_ok=True)
    (out/'preservation.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))


if __name__=='__main__':main()
