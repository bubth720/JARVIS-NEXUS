#!/usr/bin/env python3
"""Inspect our two delivered bytecode surfaces, not an assertion about arbitrary host APKs."""
import io
import json
import struct
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
core = root / 'nexus-core/build/libs/nexus-core-0.1.0.jar'
aar = root / 'nexus-android-store/build/outputs/aar/nexus-android-store-debug.aar'
forbidden = [b'java/sql/', b'java/nio/file/', b'java/time/', b'org/sqlite/']
counts = {}
with zipfile.ZipFile(aar) as archive:
    assert not any(name.startswith('jni/') for name in archive.namelist()), 'Unexpected native payload'
    manifest = archive.read('AndroidManifest.xml').decode()
    assert 'android:minSdkVersion="24"' in manifest, manifest
    assert 'uses-permission' not in manifest, manifest
    android_jar = archive.read('classes.jar')
for label, jar in [('core', core.read_bytes()), ('android', android_jar)]:
    count = 0
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        for name in archive.namelist():
            if not name.endswith('.class'):
                continue
            value = archive.read(name)
            assert struct.unpack('>H', value[6:8])[0] <= 52, ('Java >8 bytecode', label, name)
            for dependency in forbidden:
                assert dependency not in value, ('Forbidden Android linkage', label, name, dependency)
            count += 1
    assert count > 0
    counts[label] = count
print(json.dumps({'own_class_boundary': 'verified', 'classes': counts,
                  'min_sdk': 24, 'aar_permissions': [], 'aar_native_payload': False,
                  'scope': 'own core+adapter bytecode; not physical qualification'}))
