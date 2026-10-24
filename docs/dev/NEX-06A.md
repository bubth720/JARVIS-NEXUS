# NEX-06A — Adaptateur SQLite Android API24

Date : 2026-10-24 UTC. Issue : https://github.com/bubth720/JARVIS-NEXUS/issues/1
Branche : `feat/nex-06a-android-api24` ; base : `3acec55616ff8e3b1666091780fca59261377e18`.
Le SHA de livraison est celui du commit portant ce rapport, à lire avec `git rev-parse HEAD`.

## Livraison réelle et limite centrale

Le noyau métier existant est désormais partagé derrière `NexusStoragePort` ; la bibliothèque
`nexus-android-store` compile avec **minSdk24**, sans JDBC, permission Internet, LLM ni nouveau
moteur SQLite natif. Elle ouvre une base privée NEXUS, vérifie la version puis sonde réellement
FTS5, `fts5vocab(instance)`, MATCH et BM25 avant toute migration persistante. Le refus est
`StorageOpenResult.Rejected(CAPABILITY_UNAVAILABLE)` lorsque ces capacités manquent.
`compile_options` est un diagnostic, jamais une preuve suffisante.

**Le SQLite natif de Robolectric 4.14.1 n'a pas FTS5** (`no such module: fts5`). Le refus est
exécuté et vérifié sur les frameworks API24/25/26/35 simulés. Aucun résultat de citation
Android n'est annoncé. L'issue prévoit explicitement ce cas de réception avec refus et plan
compatible. L'adaptateur est livré avec une capacité RAG conditionnelle, **non qualifiée sur
Android réel**. CORE-03A demeure en attente ; cette livraison ne vaut pas autorisation de raccordement.

## Code, ownership et préservation

- `nexus-core` : DTO/contrats RAG NEX-03 et algorithmes existants ; nouveau
  `store/NexusStoragePort.kt`, `NexusRow.java`, `NexusSchema.kt`, horloge `NexusClock` portable.
  Sessions SQL préparées, transaction et lease d'import explicites. Aucun cache/catalogue concurrent.
- `nexus-jvm-store` : JDBC, fichiers JVM, snapshots NEX-05 et compatibilité `java.time.Clock`.
  Les quatre suites historiques et leur fixture ont été déplacées **à l'identique** ; les quatre
  fichiers de sauvegarde sont également identiques à la base.
- `nexus-android-store` : connexion SQLite système, paramètres liés, cursors/statements fermés,
  transactions sérialisées avec rollback, lease OS inter-connexions du parseur documentaire.
  Ce lease n'est pas une admission LLM ou un nouvel arbitre natif.
- `SelectedDocumentImporter` : seules URI `content:` expressément sélectionnées ; lectures PFD
  en mode `r`, fermées par `AutoCloseInputStream`, plafond de 2 MiB, source/digest revérifiés par
  l'importeur partagé. Aucun scan, grant persistant ajouté, accès aux bases CORE/SERVICES ou copie ZIM.
- `SyntheticNexusDemo` : opt-in explicite, texte synthétique uniquement, index/recherche/résolution
  puis réouverture si la plateforme accepte FTS5. Désactivée, elle ne crée aucun fichier.
- `archive/ArchiveReader.kt` : port de lecture de plages à offsets 64 bits ; **pas de lecteur ZIM livré**.
- `tools/check-android-boundary.py` : vérification du bytecode propre core+adaptateur, Java8,
  absence de références `java.sql`, `java.nio.file`, `java.time`, JDBC et payload JNI dans l'AAR ;
  manifeste min24 sans permissions. Ce contrôle ne qualifie pas un APK hôte arbitraire.
- Workflow CI : régressions JVM historiques et job Android séparé, AAR/rapports/APK de tests en
  artefact. L'APK de tests est compilée, jamais installée par cette mission.

La syntaxe UPSERT exigeant SQLite >=3.24 a été remplacée par UPDATE + INSERT OR IGNORE dans
la même transaction. Les paramètres et les invariants de versions/provenance ne changent pas.
JDBC utilise `Statement.execute` pour DDL/PRAGMA : `executeUpdate` refusait certains DDL FTS.

Compatibilité : contrats de données et méthodes RAG NEX-03 conservés ; les consommateurs JVM
utilisant `NexusDatabase`/snapshots doivent maintenant dépendre de `nexus-jvm-store`, qui expose
le core. La façade Clock préserve les appels Kotlin historiques. Les constructeurs acceptent
le port ; **pas de compatibilité binaire promise pour des JAR déjà compilés** : recompiler le
consommateur. Android ne doit jamais dépendre de `nexus-jvm-store`.

Les travaux NEX-04 interrompus sont préservés sur l'ancienne branche au checkpoint local
`83aed5ce50477c590b7b9e996a4b0251e7b8b9fa`, sans publication ni déclaration de livraison.
Une future reprise devra réconcilier son port ArchiveReader et son schéma v4 ; aucune fusion
implicite de ce chantier n'a été faite ici. `main` distant reste
`b27df0b84d8f0082d17ca6278549298aaaf97926`.

## Références réellement revérifiées

- NEX-03 : https://github.com/bubth720/JARVIS-NEXUS/commit/777e6afa75ecb99b85e52d68837cb0fefcf161cd
- NEX-05/base : https://github.com/bubth720/JARVIS-NEXUS/commit/3acec55616ff8e3b1666091780fca59261377e18
- `git ls-remote` confirme ces SHA ; `git merge-base --is-ancestor` confirme NEX-03 → NEX-05.
- CORE JVM laboratoire : https://github.com/bubth720/JAVIS-Local-v2/commit/aea5ac859ac551d54fe16187c1883a487dbee9d6
- Parseur SERVICES composite inchangé : `fd41d75015f77e947d22d6a60e619adc00bd70b7`,
  provenance vérifiée par `tools/prepare-services.py --verify`, aucun fichier source externe modifié.

## Vérifications exécutées

Environnement Linux, Java17, Gradle8.10.2, SDK35. Les commandes `./gradlew` équivalentes ci-dessous
ont été exécutées via le helper cloud qui configure JDK/SDK/cache/proxy/CA ; il n'ajoute aucune
capacité au produit.

```sh
./gradlew -PservicesSource=/chemin/sources-services-fd41d750 test \
  :nexus-cli:installDist :nexus-services-adapter:installDist --no-daemon --max-workers=2
./gradlew -PwithAndroid=true :nexus-android-store:testDebugUnitTest \
  :nexus-android-store:assembleDebug :nexus-android-store:assembleDebugAndroidTest \
  :nexus-android-store:lintDebug --no-daemon --max-workers=2
python3 tools/check-android-boundary.py
python3 tools/check-api24-compile.py --sdk /workspace/.jarvis-core-tools/android-sdk \
  --gradle-cache /workspace/.jarvis-core-tools/gradle-home \
  --jdk /workspace/.jarvis-core-tools/jdk-17.0.16+8
nexus-cli/build/install/nexus-cli/bin/nexus-cli rag-demo /nouvelle/fixture.db
nexus-cli/build/install/nexus-cli/bin/nexus-cli backup-demo /repertoire/de/laboratoire
```

- **69 tests JVM réussis**, zéro échec/saut : 18 stockage +17 import +12 RAG +19 sauvegarde
  +3 adaptateur parseur SERVICES. Les assertions historiques sont inchangées.
- **33 tests Robolectric réussis**, zéro échec/saut : 24 stockage/refus/migration/leases/permissions
  sur API24/25/26/35 ; 8 ContentResolver/PFD borné, digest, fermeture et refus ; 1 backend legacy
  vérifiant lui aussi l'absence de FTS et la conservation d'une table sentinelle.
- CRUD/transaction/close/reopen Android sont testés sur une **fixture de stockage brute sans FTS**,
  construite par réflexion dans le test uniquement. Aucun constructeur/factory public ne contourne
  la sonde ; ces tests ne sont pas des preuves de catalogue RAG fonctionnel.
- Migration : interruption réellement injectée après plusieurs DDL, rollback tables/version ;
  nouvelle tentative échouant sur FTS absent et conservant à nouveau tables/version/sentinelle.
- Citation JVM réelle : `RAG citations=3 resolved=3`.
- Sauvegarde JVM réelle : `exported=true authentication=verified restored_documents=1 conflicts=1
  corruption_refused=true originals_preserved=true`.
- Compilation supplémentaire des **1 source Java +16 sources Kotlin** propres contre le véritable
  `platforms/android-24/android.jar` Google : Kotlin `-no-jdk`, Java8, sans APIs du JDK17 utilisées
  comme plateforme. Réussie via `tools/check-api24-compile.py` ; les stubs lambda sont uniquement
  du support de compilation, jamais une dépendance produit. Ce n'est pas un essai sur appareil.
- AAR debug, APK d'instrumentation et Lint compilés (**0 erreur, 0 avertissement**) ; résultats détaillés et empreintes dans
  `NEX-06A-VALIDATION.json`. Aucun test instrumenté n'a été exécuté : pas de laboratoire Android
  ou appareil autorisé disponible. Les deux scénarios instrumentés distinguent explicitement
  refus de capacité et vraie citation si FTS est disponible.

Les échecs intermédiaires ne sont pas masqués : le port JDBC utilisait `executeUpdate` pour du
DDL/PRAGMA ; les tests Android WIP présumaient à tort FTS5 dans le backend natif Robolectric ;
le provider de fixture initial n'avait pas d'autorité déclarée. Les implémentations/tests ont été
corrigés et les suites finales réexécutées. Aucun test désactivé ni compteur de succès simulé.
Le composite SERVICES reçoit aussi la propriété `withAndroid` : avec l'ancien Gradle8.10.2,
son APK historique AGP8.9.2 réclame Gradle>=8.11.1. Les validations JVM et Android sont séparées,
sans modifier cette dépendance externe ou relever le minSdk de CORE.

## Blocage et plan de compatibilité à valider séparément

1. Conserver le refus de capacité ; ne pas remplacer FTS5 par une recherche approximative sous
   le même contrat. Pas de faux RAG et pas de catalogue parallèle.
2. Proposer un adaptateur versionné `nexus-android-store-fts5` réutilisant exactement
   `NexusStoragePort` et les mêmes repositories/schema. Avant implémentation : qualifier version
   SQLite/FTS5/vocab/BM25, minSdk24 réel, ABI arm64-v8a et émulateur, licences, provenance,
   reproductibilité et coût mémoire. Aucun moteur ou binaire supplémentaire n'a été ajouté ici.
3. Vérifier migrations/transactions/citations inter-plateformes, crash/close/annulation et
   permissions sur laboratoire API24/25/26/35. Une éventuelle bibliothèque native embarquée doit
   être autorisée séparément ; aucune diminution des protections ni migration personnelle.
4. Après livraison SERV-04A et qualification FTS nécessaire, choisir explicitement la base
   CORE-03A et ses dépendances. Aucun raccordement, installation, activation d'Athéna,
   modification JNI/contrats UNIFIED ou merge main n'est autorisé par ce rapport.
