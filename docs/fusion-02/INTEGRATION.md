# Consommation CORE — candidat source, pas GO Android

Base immuable : `9d6928cd8651f83fa5091bdb474b33761d366412` (FUSION-01).
Prendre le SHA de publication dans la PR de cette branche ; recompiler avec CORE,
aucune compatibilité binaire automatique promise. Les contrats RagContextPort,
Citation, CatalogResult, NexusStoragePort, le schéma4 et le format backup NEX05
restent inchangés. Nouvelles API additives Android : AndroidFts5Probe et
SelectedDocumentSourceVerifier. Pas de mutation des contrats UNIFIED v1.

Dépendances runtime : nexus-core (Kotlin/JVM8) et nexus-android-store (AAR min24),
Kotlin2.0.21. Pas de nexus-jvm-store/sqlite-jdbc/processus ZIM sur Android.
Build AGP8.7.3, Gradle8.10.2/JDK17, SDK35, build-tools35.0.0. Les parseurs SERVICES
consommés sur JVM sont exactement fd41d75015f77e947d22d6a60e619adc00bd70b7 ;
le port Android SERVICES f00091a n'est PAS ajouté à ce graphe Gradle. Il doit être
réconcilié/qualifié par son propriétaire avant import PDF Android (2MiB/100pages).
Aucun nouveau PDFBox, aucun parseur PDF copié, aucun OCR par défaut.

## Assemblage et refus

1. Hôte propriétaire crée un dossier privé NEXUS, indépendant de CORE. Appeler
   `AndroidFts5Probe.inspect()` pour diagnostic local, puis `AndroidNexusStorage.open`.
   Chaque open refait sa propre sonde, sans se fier à un ancien statut.
2. `Rejected(CAPABILITY_UNAVAILABLE)` signifie `UNSUPPORTED` pour ce backend,
   ce processus, cet appareil ; pas de collection, pas d'index ni d'extrait inventé.
   `STORAGE_FAILURE` reste une erreur/état inconnu, jamais une preuve d'absence FTS5.
   `INCOMPATIBLE_VERSION` interdit migration/réinitialisation. Aucun reset automatique
   après corruption ; originales conservées pour inspection/restauration isolée.
3. Seulement après Ready, construire KnowledgeCatalogService avec AccessPolicy
   de CORE et ImportCoordinator avec ParserRegistry qualifié. Exemple réellement
   compilé : `Fusion02DeviceTest.contentUriRagReopensOrReportsUnsupportedWithoutFakeCitation`.
   Son parseur TXT est une fixture synthétique de test, pas un PDF de production.
4. CORE reçoit un résultat ACTION_OPEN_DOCUMENT réellement choisi, jamais une URI
   issue d'un document/prompt. `SelectedDocument.fromUserSelection` n'est pas un
   système d'autorité : `confirmed` doit provenir de CORE. Imports foreground,
   2MiB maximum, worker unique, annulation sticky ; pas de copie de l'original.
5. Retenir la permission uniquement sur action propriétaire : `retainReadSelection`
   exige confirmation + drapeaux READ et PERSISTABLE retournés, ne prend que READ
   et vérifie le grant réel. Aucun WRITE, scan, conservation ou révocation implicite.
   Les tests de fournisseur synthétique ne qualifient PAS DocumentsUI/grants SAF.
6. Utiliser DocumentaryGateway v1 deny-by-default avec callbacks d'autorité CORE,
   scope, génération, annulation, deadline monotone et budget UTF8. Les callbacks
   ne doivent pas ouvrir une transaction NEXUS imbriquée. CORE reste seul maître
   des sessions, droits et reçus ; aucun IPC ou service exporté ajouté.
7. `RagContextPort.resolve` vérifie citation/hash/offsets/version dans l'index sous
   scope autorisé. Pour vérifier aussi les octets sélectionnés actuellement accessibles,
   `SelectedDocumentSourceVerifier.verify(selection, authorizedSourceRecord)` relit
   au plus2MiB et compare SHA256 + taille + URI. Cette API ne valide pas le scope,
   les pages ni les offsets ; elle complète resolve. Une révocation future ou une
   modification après la lecture reste possible chez un fournisseur externe.
8. Présenter les résultats comme résultats d'outil et preuves, sans réponse IA fictive.
   Fermer le storage au cycle de vie prévu ; réouvrir explicitement. N'importer aucune
   base personnelle pour essayer cette branche. Les chemins TXT/PDF SAF persistants,
   force-stop/rotation et composition CORE restent des gates séparés.

## Reproduire les tests hôtes

```sh
python3 tools/fusion-lab.py --root /workspace/.fusion02-build --android provision
python3 tools/prepare-services.py /workspace/.fusion02-services
python3 tools/fusion-lab.py --root /workspace/.fusion02-build gradle -- -PservicesSource=/workspace/.fusion02-services test --no-daemon --max-workers=2
python3 tools/fusion-lab.py --root /workspace/.fusion02-build gradle -- -PwithAndroid=true :nexus-android-store:testDebugUnitTest :nexus-android-store:assembleDebug :nexus-android-store:assembleDebugAndroidTest :nexus-android-store:lintDebug --no-daemon --max-workers=2
python3 tools/fusion-lab.py --root /workspace/.fusion02-build api24
python3 tools/check-android-boundary.py
python3 tools/check-fusion02.py
python3 -m unittest discover -s tests/fusion02 -v
```

Les archives/outils ne sont téléchargés qu'avec `provision` explicite. Aucun changement
HOME, aucune installation système/personnelle. Les dépendances de build peuvent
nécessiter réseau ; le produit n'en reçoit aucune permission réseau.
