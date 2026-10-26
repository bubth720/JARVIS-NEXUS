from transport import shell,push,verify_apk
from pathlib import Path
import time,concurrent.futures,contextlib
r=Path('/workspace/JARVIS-NEXUS');e=r/'docs/fusion-02/evidence'
apk=r/'nexus-android-store/build/outputs/apk/androidTest/debug/nexus-android-store-debug-androidTest.apk'
def run(api,port):
 for attempt in range(60):
  try:
   if shell('getprop sys.boot_completed',port).strip()=='1':break
  except Exception:pass
  time.sleep(10)
 else:raise RuntimeError('Boot timeout '+str(api))
 push(apk,'/data/local/tmp/fusion02-test.apk',port)
 with (e/f'wal-api{api}-apk-transfer.txt').open('w') as out:
  with contextlib.redirect_stdout(out):verify_apk(apk,port)
 info=shell('pm install -r /data/local/tmp/fusion02-test.apk; getprop ro.build.fingerprint; getprop ro.build.version.sdk; getprop ro.product.cpu.abi',port)
 (e/f'wal-api{api}-install.txt').write_text(info)
 if 'Success' not in info:raise RuntimeError('Install failed '+info)
 offline=shell('settings put global airplane_mode_on 1; su 0 am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true; svc wifi disable; svc data disable; settings get global airplane_mode_on; ip route; logcat -c',port)
 (e/f'wal-api{api}-offline.txt').write_text(offline)
 (e/f'wal-api{api}-instrumentation.txt').write_text(shell('am instrument -w -r fr.jarvis.nexus.android.test/androidx.test.runner.AndroidJUnitRunner',port))
 (e/f'wal-api{api}-logcat.txt').write_text(shell('logcat -d -s System.out:I TestRunner:I AndroidRuntime:E',port))
 return api
with concurrent.futures.ThreadPoolExecutor(2) as pool:
 for result in pool.map(lambda pair:run(*pair),[(24,5581),(25,5583)]):print('Completed',result,flush=True)
