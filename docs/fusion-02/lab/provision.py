import urllib.request,hashlib,zipfile,json
from pathlib import Path,PurePosixPath
r=Path('/workspace/.fusion02-lab');receipts=[]
assets=[('https://dl.google.com/android/repository/emulator-linux_x64-16428233.zip','cd7362ea55dfb86a418958138dc396e74165dd01','sdk'),('https://dl.google.com/android/repository/platform-tools_r37.0.1-linux.zip','477254aa5f903c15cf51001717bdf347fb6b53e0','sdk'),('https://dl.google.com/android/repository/sys-img/android/x86-24_r08.zip','c1cae7634b0216c0b5990f2c144eb8ca948e3511','sdk/system-images/android-24/default')]
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
 (r/'receipt.json').write_text(json.dumps(receipts,indent=2)+'\n')
 print(url,flush=True)
