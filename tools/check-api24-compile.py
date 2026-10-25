#!/usr/bin/env python3
"""Compile own portable+Android sources against real SDK24 stubs, never JDK APIs as platform."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', required=True)
parser.add_argument('--gradle-cache', required=True)
parser.add_argument('--jdk', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
cache = Path(args.gradle_cache) / 'caches/modules-2/files-2.1'

def jar(group, name, version):
    files = list((cache / group / name / version).glob('*/*.jar'))
    if len(files) != 1:
        raise SystemExit(f'Expected one cached {group}:{name}:{version}, found {len(files)}; build dependencies normally first')
    return str(files[0])

compiler_cp = [jar('org.jetbrains.kotlin', name, '2.0.21') for name in
               ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-daemon-embeddable']]
compiler_cp += [jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.6.4'),
                jar('org.jetbrains', 'annotations', '13.0')]
platform = Path(args.sdk) / 'platforms/android-24/android.jar'
if not platform.is_file():
    raise SystemExit('SDK Android24 not installed; no download is performed by this verifier')
# Lambda stub is compiler support only, never a library runtime dependency.
boot = str(platform) + os.pathsep + str(Path(args.sdk) / 'build-tools/35.0.0/core-lambda-stubs.jar')
java_sources = sorted((root / 'nexus-core/src/main/java').rglob('*.java'))
kotlin_sources = sorted((root / 'nexus-core/src/main/kotlin').rglob('*.kt')) + sorted((root / 'nexus-android-store/src/main/kotlin').rglob('*.kt'))
with tempfile.TemporaryDirectory(prefix='nex06a-api24-') as folder:
    rows = str(Path(folder) / 'java')
    subprocess.run([str(Path(args.jdk)/'bin/javac'), '-source', '8', '-target', '8', '-bootclasspath', boot,
                    '-d', rows, *map(str, java_sources)], check=True)
    cp = os.pathsep.join([str(platform), jar('org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),
                          jar('org.jetbrains','annotations','13.0'), rows])
    subprocess.run([str(Path(args.jdk)/'bin/java'), '-cp', os.pathsep.join(compiler_cp),
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-jdk', '-no-stdlib', '-no-reflect',
                    '-jvm-target', '1.8', '-classpath', cp, '-d', str(Path(folder)/'kotlin'),
                    *map(str,kotlin_sources)], check=True)
print(f'API24 stub compilation passed: {len(java_sources)} Java + {len(kotlin_sources)} Kotlin own sources; no device/runtime qualification')
