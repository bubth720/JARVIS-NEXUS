# Laboratoire jetable uniquement

Scripts conservés tels qu'exécutés dans ce workspace. Aucun usage sur appareil
personnel. Les chemins fixes `/workspace/.fusion02-lab` et `/workspace/JARVIS-NEXUS`
désignent uniquement ce laboratoire. Ne pas substituer une URI ou un appareil réel.

`provision.py` puis `provision25.py` vérifient les sommes du catalogue officiel et
extraient émulateur37.2.12, outils et images AOSP24/25 dans le dossier dédié.
Copier api24-config.ini dans avd/api24.avd/config.ini et créer l'ini AVD comme dans
les chemins de ce fichier ; API25 remplace api24/API24/android-24 par25.

Lancer chaque VM avec ANDROID_AVD_HOME, ANDROID_USER_HOME, ANDROID_EMULATOR_HOME et
ANDROID_SDK_ROOT sous ce dossier, options `-avd api24 -port 5580 -no-window -no-audio
-no-boot-anim -no-snapshot -accel off -gpu swiftshader_indirect` (API25/5582).
Après le build instrumenté, `python3 final-run.py` attend le boot, transfère et relit
l'APK (SHA256), installe le seul package synthétique, active le mode avion sur cette
image userdebug, désactive Wi-Fi/données et exécute le runner et les logs.

Le transport refuse AUTH et tous ports hors5581/5583 ; aucune clé, HOME, compte,
ADB personnel ni service exporté de production. Les commandes shell restent celles
du laboratoire. Les fichiers source du runner sont sous androidTest, le fournisseur
non exporté se trouve seulement dans l'APK de test. L'AAR n'en contient aucun.

Sur un autre laboratoire autorisé où ADB dispose d'un dossier de configuration
isolé, les mêmes opérations peuvent être exécutées via ADB standard en ciblant
explicitement les numéros emulator-5580/emulator-5582, jamais un device personnel.
