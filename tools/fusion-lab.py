#!/usr/bin/env python3
"""Explicit reproducible Linux lab tooling. Never installs to the system/personal
home, accepts no model/APK/device operation, and downloads only with `provision`.
Build-time tools are not a NEXUS runtime network dependency.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tarfile
import urllib.request
from urllib.parse import urlparse
import zipfile

JDK='jdk-17.0.16+8'
ASSETS=[
 ('jdk.tar.gz','https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz','166774efcf0f722f2ee18eba0039de2d685b350ee14d7b69e6f83437dafd2af1',None),
 ('gradle.zip','https://downloads.gradle.org/distributions/gradle-8.10.2-bin.zip','31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26',None),
 ('platform-24_r02.zip','https://dl.google.com/android/repository/platform-24_r02.zip','f268f5945c6ece7ea95c1c252067280854d2a20da924e22ae4720287df8bdbc9','platforms/android-24'),
 ('platform-35_r02.zip','https://dl.google.com/android/repository/platform-35_r02.zip','0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0','platforms/android-35'),
 ('build-tools_r35_linux.zip','https://dl.google.com/android/repository/build-tools_r35_linux.zip','bd3a4966912eb8b30ed0d00b0cda6b6543b949d5ffe00bea54c04c81e1561d88','build-tools/35.0.0'),
]

def provision(root, android):
    receipts=[]
    for name,url,digest,destination in ASSETS:
        if destination and not android:continue
        archive=root/name
        if archive.is_symlink():raise ValueError('Lab archive symlink refused')
        if not archive.exists():
            with urllib.request.urlopen(url,timeout=60) as response,archive.open('xb') as out:
                count=0
                while chunk:=response.read(1024*1024):
                    count+=len(chunk)
                    if count>300*1024*1024:raise ValueError('Tool archive exceeds quota')
                    out.write(chunk)
        observed=hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()
        if observed!=digest:raise ValueError('Tool checksum mismatch: '+name)
        if name.endswith('.tar.gz'):
            with tarfile.open(archive) as package:package.extractall(root,filter='data')
        else:
            with zipfile.ZipFile(archive) as package:
                for entry in package.infolist():
                    parts=PurePosixPath(entry.filename).parts
                    if entry.filename.startswith('/') or '..' in parts:raise ValueError('Unsafe tool archive path')
                    parts=parts[1:] if destination else parts
                    if not parts:continue
                    base=root/'android-sdk'/destination if destination else root
                    target=base.joinpath(*parts)
                    if target.is_symlink():raise ValueError('Tool destination symlink refused')
                    if entry.is_dir():target.mkdir(parents=True,exist_ok=True)
                    else:
                        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(package.read(entry))
                        mode=(entry.external_attr>>16)&0o777
                        if mode:target.chmod(mode)
        receipts.append({'url':url,'sha256':observed,'bytes':archive.stat().st_size})
        print(name+': checksum verified')
    (root/'toolchain-receipt.json').write_text(json.dumps(receipts,indent=2)+'\n')

def environment(root):
    env=os.environ.copy()
    env.update(JAVA_HOME=str(root/JDK),GRADLE_USER_HOME=str(root/'gradle-home'),
               ANDROID_HOME=str(root/'android-sdk'),ANDROID_SDK_ROOT=str(root/'android-sdk'),
               ANDROID_USER_HOME=str(root/'android-user'))
    # A new lab preference folder, no read/write of a personal ~/.android.
    if env.get('ANDROID_PREFS_ROOT') and Path(env['ANDROID_PREFS_ROOT'])!=root/'android-user':
        raise ValueError('Conflicting ANDROID_PREFS_ROOT; use a separate clean lab invocation')
    (root/'android-user').mkdir(exist_ok=True)
    options=[]
    proxy=urlparse(env.get('HTTPS_PROXY',env.get('https_proxy','')))
    if proxy.hostname:
        for protocol in ('https','http'):
            options += [f'-D{protocol}.proxyHost={proxy.hostname}',f'-D{protocol}.proxyPort={proxy.port or 8080}']
    trust=Path('/etc/ssl/certs/java/cacerts')
    if trust.is_file():options.append('-Djavax.net.ssl.trustStore='+str(trust))
    env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' '+' '.join(options)
    return env

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path('/tmp/fusion-lab-tools'))
    parser.add_argument('--android',action='store_true')
    parser.add_argument('action',choices=['provision','gradle','api24'])
    args,remaining=parser.parse_known_args()
    if args.root.is_symlink():raise ValueError('Lab root symlink refused')
    root=args.root.absolute();root.mkdir(parents=True,exist_ok=True)
    if args.action=='provision':provision(root,args.android);return 0
    if remaining[:1]==['--']:remaining=remaining[1:]
    if args.action=='gradle':
        command=['bash',str(root/'gradle-8.10.2/bin/gradle'),*remaining]
    else:
        command=[sys.executable,str(Path(__file__).with_name('check-api24-compile.py')),
                 '--sdk',str(root/'android-sdk'),'--gradle-cache',str(root/'gradle-home'),'--jdk',str(root/JDK)]
    return subprocess.call(command,env=environment(root))

if __name__=='__main__':sys.exit(main())
