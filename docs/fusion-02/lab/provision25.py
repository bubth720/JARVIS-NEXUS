import urllib.request,hashlib,zipfile,json
from pathlib import Path,PurePosixPath
r=Path('/workspace/.fusion02-lab');receipts=[]
assets=[('https://dl.google.com/android/repository/sys-img/android/x86-25_r01.zip','78ce7eb1387d598685633b9f7cbb300c3d3aeb5f','sdk/system-images/android-25/default')]
for url,sha,dest in assets:
 path=r/url.rsplit('/',1)[1]
 if not path.exists():
  with urllib.request.urlopen(url,timeout=60) as src,path.open('xb') as out:
   while chunk:=src.read(1024*1024):out.write(chunk)
 assert hashlib.file_digest(path.open('rb'),'sha1').hexdigest()==sha
 with zipfile.ZipFile(path) as z:
  for e in z.infolist():
   assert not e.filename.startswith('/') and '..' not in PurePosixPath(e.filename).parts
   t=r/dest/e.filename
   if e.is_dir():t.mkdir(parents=True,exist_ok=True)
   else:
    t.parent.mkdir(parents=True,exist_ok=True);t.write_bytes(z.read(e));mode=(e.external_attr>>16)&0o777
    if mode:t.chmod(mode)
 receipts.append({'url':url,'sha1':sha,'sha256':hashlib.file_digest(path.open('rb'),'sha256').hexdigest(),'bytes':path.stat().st_size})
 (r/'receipt-api25.json').write_text(json.dumps(receipts,indent=2)+'\n')
 print(url,flush=True)
