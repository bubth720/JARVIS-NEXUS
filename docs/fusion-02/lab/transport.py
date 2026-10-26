"""Minimal ADB transport for this disposable loopback emulator only.
No host server/auth key/personal home. AUTH explicitly rejected (never bypassed).
"""
import socket,struct,time,sys
from pathlib import Path
class Transport:
 def __init__(self,service,port=5581):
  if port not in [5581,5583]:raise ValueError('Only dedicated lab emulator ports')
  self.s=socket.create_connection(('127.0.0.1',port),timeout=60);self.s.settimeout(180);self.buffer=b'';self.remote=0;self.closed=False
  self.send(b'CNXN',0x01000000,4096,b'host::\0');c,a,b,p=self.receive()
  if c!=b'CNXN':raise RuntimeError('Lab handshake refused: '+repr(c))
  self.send(b'OPEN',1,0,service.encode()+b'\0');c,a,b,p=self.receive()
  if c!=b'OKAY':raise RuntimeError('Lab service refused: '+repr((c,p)))
  self.remote=a
 def exact(self,n):
  out=b''
  while len(out)<n:
   b=self.s.recv(n-len(out))
   if not b:raise EOFError()
   out+=b
  return out
 def send(self,c,a,b,p=b''):
  v=int.from_bytes(c,'little');self.s.sendall(struct.pack('<6I',v,a,b,len(p),sum(p)&0xffffffff,v^0xffffffff)+p)
 def receive(self):
  c,a,b,n,checksum,magic=struct.unpack('<6I',self.exact(24));assert magic==c^0xffffffff and n<=1024*1024
  p=self.exact(n);assert checksum==sum(p)&0xffffffff
  return c.to_bytes(4,'little'),a,b,p
 def message(self):
  c,a,b,p=self.receive()
  if c==b'WRTE':self.send(b'OKAY',1,self.remote);self.buffer+=p
  elif c==b'CLSE':self.send(b'CLSE',1,self.remote);self.closed=True
  elif c!=b'OKAY':raise RuntimeError('Unexpected '+repr(c))
  return c
 def write(self,data):
  for offset in range(0,len(data),4096):
   self.send(b'WRTE',1,self.remote,data[offset:offset+4096])
   while self.message()!=b'OKAY':
    if self.closed:raise EOFError()
 def read(self,n):
  while len(self.buffer)<n:
   if self.closed:raise EOFError()
   self.message()
  out=self.buffer[:n];self.buffer=self.buffer[n:];return out
 def output(self):
  while not self.closed:self.message()
  return self.buffer.decode(errors='replace')
 def close(self):self.s.close()
def shell(command,port=5581):
 t=Transport('shell:'+command,port)
 try:return t.output()
 finally:t.close()
def push(source,destination,port=5581):
 assert destination in ['/data/local/tmp/fusion02-test.apk']
 t=Transport('sync:',port)
 try:
  path=(destination+',33204').encode();t.write(b'SEND'+struct.pack('<I',len(path))+path)
  with Path(source).open('rb') as f:
   while chunk:=f.read(65536):t.write(b'DATA'+struct.pack('<I',len(chunk))+chunk)
  t.write(b'DONE'+struct.pack('<I',int(time.time())))
  response=t.read(8)
  if response!=b'OKAY\0\0\0\0':raise RuntimeError(repr(response))
  t.write(b'QUIT\0\0\0\0')
 finally:t.close()
def verify_apk(source,port=5581):
 import hashlib
 expected=hashlib.file_digest(Path(source).open('rb'),'sha256').hexdigest()
 t=Transport('sync:',port)
 try:
  path=b'/data/local/tmp/fusion02-test.apk';t.write(b'RECV'+struct.pack('<I',len(path))+path)
  digest=hashlib.sha256();total=0
  while True:
   kind,size=struct.unpack('<4sI',t.read(8))
   if kind==b'DONE':break
   if kind!=b'DATA' or size>65536:raise RuntimeError('Invalid sync reply')
   total+=size
   if total>10*1024*1024:raise RuntimeError('APK exceeds lab quota')
   digest.update(t.read(size))
  observed=digest.hexdigest()
  if observed!=expected:raise RuntimeError('APK transfer digest mismatch')
  print('APK transfer/re-read SHA256 verified: '+observed+' bytes='+str(total))
  t.write(b'QUIT\0\0\0\0')
 finally:t.close()
if __name__=='__main__':
 if sys.argv[1]=='shell':print(shell(sys.argv[2],int(sys.argv[3]) if len(sys.argv)>3 else 5581),end='')
 elif sys.argv[1]=='verify-apk':verify_apk(sys.argv[2],int(sys.argv[3]) if len(sys.argv)>3 else 5581)
 elif sys.argv[1]=='push':push(sys.argv[2],'/data/local/tmp/fusion02-test.apk',int(sys.argv[3]) if len(sys.argv)>3 else 5581)
