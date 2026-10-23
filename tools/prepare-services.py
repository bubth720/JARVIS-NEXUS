#!/usr/bin/env python3
"""Build-time dependency only. No runtime network, model, repository mutation or PDF fork."""
import argparse, hashlib, io, pathlib, tarfile, urllib.request
COMMIT = 'fd41d75015f77e947d22d6a60e619adc00bd70b7'
ARCHIVE_SHA256 = '20efa456e6ee0f58363108aeec7ab77bc86911ea43c16743e16e3794dbb82fc7'
TREE_SHA256 = 'f0f39dde88c4a882925faaefcba2e92c9c8e0c39369261e294b221ab66b143f1'

def verify(root):
    rows = []
    for p in root.rglob('*'):
        relative = p.relative_to(root)
        parts = relative.parts
        if parts[0] in {'.gradle', '.kotlin', 'build'} or len(parts) > 1 and parts[1] == 'build':
            continue
        if p.is_symlink(): raise ValueError('Source symlink refused')
        if p.is_file(): rows.append((relative.as_posix(), hashlib.sha256(p.read_bytes()).hexdigest()))
    listing = ''.join(f'{digest}  {name}\n' for name, digest in sorted(rows))
    if hashlib.sha256(listing.encode()).hexdigest() != TREE_SHA256:
        raise ValueError('SERVICES source tree differs from pinned commit')
    print(f'SERVICES source verified: {COMMIT}')

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('directory', type=pathlib.Path); parser.add_argument('--verify', action='store_true'); args = parser.parse_args()
    if args.verify or args.directory.exists(): verify(args.directory); return
    with urllib.request.urlopen(f'https://codeload.github.com/bubth720/JARVIS-SERVICES/tar.gz/{COMMIT}', timeout=60) as response:
        archive = response.read(20 * 1024 * 1024 + 1)
    if len(archive) > 20 * 1024 * 1024 or hashlib.sha256(archive).hexdigest() != ARCHIVE_SHA256: raise ValueError('SERVICES archive integrity mismatch')
    entries = []
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as tar:
        for member in tar.getmembers():
            parts = pathlib.PurePosixPath(member.name).parts[1:]
            if not parts or '..' in parts or member.name.startswith('/') or member.issym() or member.islnk():
                if member.isdir() and not parts: continue
                raise ValueError('Unsafe dependency member')
            if member.isfile(): entries.append((parts, tar.extractfile(member).read()))
    if len(entries) > 1000 or sum(len(data) for _, data in entries) > 20 * 1024 * 1024: raise ValueError('Dependency quota exceeded')
    args.directory.mkdir(parents=True, exist_ok=False)
    for parts, data in entries:
        target = args.directory.joinpath(*parts); target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
    verify(args.directory)
if __name__ == '__main__': main()
