# FUSION-01 / NEXUS — qualification et réconciliation en laboratoire

Mission : [NEXUS #2](https://github.com/bubth720/JARVIS-NEXUS/issues/2),
directrice [UNIFIED #4](https://github.com/bubth720/JARVIS-UNIFIED/issues/4),
port existant [NEX-06A #1](https://github.com/bubth720/JARVIS-NEXUS/issues/1).
Branche neuve : `feat/fusion-01-nexus-integration`.
Base : `d903bf04f497ab0dc7f40e9708ed057e4f24c6ee`.
HEAD de livraison : commit portant ce rapport (SHA complet dans Git et la PR).
**État global : PARTIAL. Aucun GO de production Android ou de fusion.**

## Ce qui fonctionne et ce qui a été développé

La bibliothèque finale est maintenant compilée et testée sur JVM. Ses sources,
révisions, imports, citations, bibliothèque/atlas v4 et sauvegardes sont conservés.
Le même métier utilise `NexusStoragePort` sur JVM et dans l'adaptateur Android.
Il n'existe pas de seconde base documentaire, de second moteur d'inférence,
de nouvelle autorité de permissions ou de transport ajouté.

- **N0 : audit réalisé.** Branches/SHA, parents, rapports, AGENTS présents,
  CI et surfaces de build des huit dépôts consultés en lecture seule.
  `remote-inventory.json` et `reference-builds.json` sont des observations,
  jamais un BOM F0 approuvé. Les échecs CI LLM/VOICE et les checks absents ne
  sont pas convertis en succès.
- **N1 : PASS logiciel JVM.** Les 80 tests de la base finale et les trois tests
  SERVICES passent avant le refactoring. La livraison ajoute des tests du
  gateway, d'un vrai snapshot v3 chiffré et de 1000 imports réels synthétiques.
- **N2 : code portable réconcilié, Android RAG BLOCKED.** Le port NEX-06A est
  repris sans perdre v4. Les fontes JDBC/snapshots/processus ZIM et les tests JVM
  se trouvent dans `nexus-jvm-store`. Le noyau `nexus-core` et l'AAR ne référencent
  ni `java.sql`, ni `java.nio.file`, ni `java.time`, ni `org.sqlite`.
- **N3 : PASS JVM du point d'entrée documentaire.** `DocumentaryGateway` /1
  appelle le vrai `LocalRagService`, signale `INDEX_MISSING` ou `UNSUPPORTED`,
  vérifie les callbacks hôte d'autorisation/annulation, le délai monotone et le
  carrier UTF-8 borné. Index, récupération et résolution de toutes les citations
  partagent une transaction SQLite cohérente. Une deuxième connexion ne peut
  réécrire la provenance au milieu de cette lecture dans le test dédié.
- **N4 : atlas et ZIM conservés.** L'export réel du graphe Kotlin est lu par
  le visualiseur ; ses citations gardent tous leurs champs. L'archive Wikipédia
  réelle n'est pas disponible et n'a été ni copiée ni réindexée. ZIM Android
  conserve `UNSUPPORTED` ; aucun libzim/JNI/poids nouveau n'est embarqué.

## Filiation, divergence et décision de base

```text
3acec55616ff8e3b1666091780fca59261377e18  NEX-05, schéma v3
├─ 374ea2e2fd640e78f2f5e12e60e75b493420e504  NEX-06A : port API24, v3
└─ bbccac2ff2cc052f897c7f5015f234507e047b95  NEX-04 Linux
   └─ d903bf04f497ab0dc7f40e9708ed057e4f24c6ee  bibliothèque/atlas v4
      └─ feat/fusion-01-nexus-integration  réconciliation manuelle documentée
```

Le parent commun a été vérifié avec `git merge-base`. La branche API24 exclut
les travaux finaux v4 et NEX-04 : la prendre comme remplacement aurait perdu
ces fonctionnalités. La base finale est donc conservée ; les adaptations de
`374ea2e...` sont portées consciemment, sans merge/cherry-pick aveugle.
Les migrations SQL v1–v4 restent celles de la base finale, centralisées dans
`NexusSchema`. La transaction et la syntaxe UPDATE + INSERT OR IGNORE remplacent
les UPSERT exigeant SQLite≥3.24, sans reconstruire FTS à l'ouverture.

Les méthodes et champs `RagContextPort`/`Citation` NEX-03 sont conservés : URI,
source SHA256, document/version, page, ordinal, offsets et hash d'extrait.
Les DTO ne perdent aucun champ pendant l'export ou la résolution.
La compatibilité **binaire** d'anciens JAR n'est pas promise : recompiler les
consommateurs, et remplacer leur dépendance JVM `nexus-core` par
`nexus-jvm-store`. Le client CORE-03 historique consomme encore l'ancienne
coordonnée ; son adaptation appartient à une PR CORE distincte. Aucun fichier
CORE ou contrat UNIFIED n'a été modifié.

Le port futur de plages NEX-06A, incompatible par son nom avec le lecteur
d'articles NEX-04, est nommé `ArchiveRangeSource`. Le véritable `ArchiveReader`
d'articles et `SelectedArchiveContext` sont conservés ; `ZimProcessReader` est
strictement dans le module JVM. Aucun faux décodeur Android n'est ajouté.

## Fichiers et architecture

- `nexus-core/.../store/{NexusStoragePort,NexusSchema,NexusRow}` : API de stockage
  et schéma unique ; horloge portable, sessions préparées et lease d'import.
- Métier `api/rag/library/graph/importing/search` : mêmes sources et algorithmes
  derrière le port. Les quotas vérifient l'espace fourni par le backend ; espace
  inconnu = refus d'import. Pas de chemin JVM dans la couche portable.
- `nexus-jvm-store` : adaptateur JDBC de NEX-06A, backup v4 et lecteur processus
  Linux. Les six suites historiques/finales, leur fixture et les quatre fichiers
  de backup sont déplacés sans modification ; leurs empreintes sont conservées.
- `nexus-android-store` : bibliothèque minSdk24, sonde réelle temp FTS5/vocab,
  MATCH/BM25 avant migration, PFD en lecture seule. La rétention SAF est une
  action propriétaire facultative, vérifie flags READ+PERSISTABLE et ne demande
  jamais WRITE. Révocation réelle/reboot/provider physique restent NOT_RUN.
- `DocumentaryGateway`, `FusionDocumentaryTest`, `FusionSnapshotMigrationTest`,
  `FusionScaleTest` : lecture bornée, refus négatifs et preuves réelles sur JVM.
- `tools/fusion-lab.py` : outils Linux épinglés et répertoires de laboratoire,
  aucun provisionnement sans la commande explicite `provision`.
- `tools/check-fusion-lock.py`, `integration-lock.json` : vérification hors ligne
  des sources/preuves, sans interpréter cette attestation comme un GO global.
- Workflow `fusion-01.yml` : **manuel seulement**, JVM et Android hôte séparés,
  sans installation d'APK. CI de cette nouvelle branche : NOT_RUN au rapport.

`DocumentaryCall` contient seulement des callbacks de l'hôte de confiance.
CORE reste propriétaire des session/request/generation et fournit la fermeture
correspondante ; aucun identifiant ou droit n'est reconstruit depuis un document.
Le gateway ne mémorise pas de consentement et n'expose aucune mutation. L'hôte
doit encore rejeter une réponse devenue tardive après son retour, ne pas appeler
le gateway depuis une transaction NEXUS déjà ouverte et conserver ses contrôles
de corrélation. L'annulation logique n'est jamais une preuve de fin JNI/unload.

## Tests effectivement exécutés

Environnement : Linux de laboratoire x86_64, Temurin17.0.16+8, Gradle8.10.2,
Kotlin2.0.21, SQLite JDBC3.46.1.0, AGP8.7.3, SDK24/35 et build-tools35.
Outils téléchargés et vérifiés en `/tmp/fusion-lab-tools`, aucun outil système
ou personnel installé. Aucune connexion/appairage à un appareil.

| Exécution finale | Résultat | Preuve |
| --- | --- | --- |
| Vraie bibliothèque JVM + adaptateur SERVICES | **95 tests, 0 échec/erreur/saut** | `evidence/jvm-verification.log`, `jvm-junit/`, `jvm-results.json` |
| Android hôte Robolectric, API24/25/26/35 simulés | **37 tests, 0 échec/erreur/saut** | `evidence/android-verification.log`, `android-junit/` |
| Compilation SDK24, sans JDK comme plateforme | **1 Java +22 Kotlin PASS** | `evidence/api24-source-final.log` |
| Frontière bytecode core+AAR | **PASS**, min24, Java8, aucune permission/JNI | `evidence/boundary-source-final.log` |
| AAR et APK d'instrumentation | **Compilées** ; deux scénarios instrumentés **0 exécuté** | hashes du lock ; Android build log |
| Lint Android | **0 erreur, 2 avertissements** de versions de dépendances de tests | `evidence/android-lint.xml` |
| SQLite DDL/migrations et modèle JS du graphe | **10 +7 tests passés** | `sql-regression.log`, `graph-regression.log` |
| Tests de contrat ZIM | **18 passés, 1 ignoré** (archive réelle absente) | `zim-regression.log` |
| Chromium desktop et tactile émulé | **PASS**, export JVM réel de 24 nœuds/12 liens, citations intactes | `viewer-jvm-export.log` |
| Démonstrations JVM RAG/backup/atlas | **PASS**, RAG 3 citations relues, sauvegarde authentifiée/corruption refusée | `*-demo.log`, `demo-results.json` |

Les 95 tests incluent les 83 de la base/adaptateur, dix tests de gateway, un de
snapshot v3 et un de volume. Les exécutions baseline et finales se recouvrent :
elles ne sont pas additionnées comme de nouveaux tests distincts.

Les tests hôte Android utilisent du vrai SQLite hôte pour les refus de FTS5,
les transactions brutes et les PFD, dans un framework simulé. La factory de
production n'a **aucun** paramètre pour contourner sa sonde. La fixture brute
par réflexion reste seulement dans les tests. Aucun succès RAG Android n'est
déduit de ces 37 succès. Les deux avertissements Lint concernent uniquement
`androidx.test:runner:1.6.2` et `androidx.test.ext:junit:1.2.1` ; ils ne sont pas
supprimés ni « corrigés » par une mise à jour de dépendance non qualifiée.

Le test de snapshot fabrique une base avec le DDL NEX-05 v3 exact, crée une vraie
archive AES-GCM et vérifie migration/résolution après réouverture de sa seule
copie v4. Les octets de l'ancien fichier, de l'archive et de la base courante
restent identiques. La fixture SQL est liée au SHA NEX-05, pas à une base privée.

`FusionScaleTest` passe **1000 CSV synthétiques** par le vrai ImportCoordinator,
le parseur et FTS, puis réimporte un contenu inchangé sans parsing et révise un
document. Il vérifie 1000 documents, 1001 révisions, 1000 entrées FTS et une
citation v2 après fermeture/réouverture. Mesures brutes dans `jvm-scale.json` :
temps réel, taille base, heap instantanée et pic RSS du processus de tests.
Ce pic englobe aussi les autres tests du worker ; ce n'est ni une mesure Android
ni un pic mémoire isolé de l'importeur, et aucun seuil/SLA n'en est déduit.

Les trois CLI ont été tracées avec `strace -f -e trace=network` : aucune tentative
de connexion IPv4/IPv6 observée. Le JDK crée des sockets de sonde et tente une
socket Unix `nscd` absente ; cela est conservé dans les traces, pas caché derrière
une affirmation « aucun socket ». Ce n'est ni une sandbox réseau ni le mode
avion d'un téléphone. Le navigateur n'observe aucune requête externe ; seuls
les quatre fichiers statiques loopback sont servis. Les corpus sont FIXTURE.

## Commandes reproductibles

```sh
python3 tools/fusion-lab.py provision
python3 tools/prepare-services.py /tmp/fusion-lab-tools/services-fd41d75015f77e947d22d6a60e619adc00bd70b7
python3 tools/fusion-lab.py gradle -- -PservicesSource=/tmp/fusion-lab-tools/services-fd41d75015f77e947d22d6a60e619adc00bd70b7 test :nexus-cli:installDist :nexus-services-adapter:installDist --rerun-tasks --no-daemon --console=plain --max-workers=2
python3 tools/fusion-lab.py --android provision
python3 tools/fusion-lab.py gradle -- -PwithAndroid=true :nexus-android-store:testDebugUnitTest :nexus-android-store:assembleDebug :nexus-android-store:assembleDebugAndroidTest :nexus-android-store:lintDebug --no-daemon --console=plain --max-workers=2
python3 tools/fusion-lab.py api24
python3 tools/check-android-boundary.py
python3 tools/check-fusion-lock.py
NEXUS_GRAPH_PAGE=docs/fusion-01/evidence/atlas-demo.json node tests/final/browser.cjs
```

Le navigateur exige Node/Playwright/Chromium déjà provisionnés, aucun paquet
supplémentaire installé par son test. Les tâches Android et SERVICES sont
séparées : le composite SERVICES historique peut activer son propre build Android
avec `withAndroid`, ce qui réclame une autre version Gradle ; ne pas le forcer.
Les téléchargements de développement respectent proxy/CA et leurs SHA ; aucune
connexion internet n'est nécessaire dans la bibliothèque runtime.

Les premiers échecs sont conservés : constantes XML JAXP absentes des stubs24
(remplacées par les mêmes URI de propriétés, sans retirer les protections),
préférences Android/Robolectric pointant initialement sur un home non inscriptible,
classloader AGP/Kotlin non partagé et build-tools34 demandé par défaut. Les outils
ont été isolés, le root plugin NEX-06A repris et build-tools35 épinglé. Aucune modification des permissions du home, aucun
contournement de sandbox, acceptation automatique de licence34, test
désactivé, faux FTS ou seuil de sécurité abaissé pour obtenir un résultat vert.
Les factories XML non compatibles avec les propriétés sécurisées restent refusées.

Les journaux Gradle bruts conservent leurs espaces terminaux. Le contrôle Git
de whitespace est propre sur les sources/documentation ; le contrôle non filtré
signale ces espaces dans les preuves. Aucun test ou contrôle Lint n’est supprimé.

## Dépendances, licences, risques et portes restantes

La dépendance effective d'extraction reste le snapshot immuable SERVICES
`fd41d75015f77e947d22d6a60e619adc00bd70b7`, vérifié avant et après build avec son
empreinte d'arbre ; aucun parseur PDFBox n'est copié. Les références distantes
CORE `aea5ac859ac551d54fe16187c1883a487dbee9d6`, NEX-06A `374ea2e...` et les
six autres modules sont inventoriées, pas assemblées dans un produit candidat.
Kotlin/Gradle/AGP/PDFBox utilisent leurs licences amont, SQLite JDBC ses notices
et SQLite le domaine public ; JUnit/Robolectric sont dépendances de tests.
Temurin et SDK conservent leurs notices dans le répertoire de laboratoire.
Les licences globales de tous les dépôts/modèles/assets ne sont pas déclarées
qualifiées par cet audit : la composition de distribution appartient à F0.

**Blocages :** FTS5 absent dans le backend Android hôte testé ; aucune instrumentation
API24/25/26/29/35 ou ARM64 exécutée ; aucun mini-PC physique ; ZIM réel et son
backend absents ; aucune composition F0 approuvée consommée ni raccordement CORE.
OCR/XLSX universel/vectoriel neuronal, JNI LLM, voix/avatar et EVOLUTION restent
hors activation. Ce rapport ne débloque aucune de ces portes.

Risques : changement de module/classpath et de signatures binaires ⇒ recompiler
les consommateurs ; la v4 ne doit pas être ouverte avec un ancien binaire v3.
Préserver les archives v3, ne migrer que des copies isolées pour qualification,
et garder le binaire + la base d'origine ensemble pour retour arrière. La base
publique `main` reste inchangée, aucune donnée personnelle n'a été ouverte.

**Prochaine action :** revue de cette PR brouillon et de son lock NEXUS ; porter
la dépendance côté CORE dans sa propre branche après BOM F0, puis exécuter le
harness instrumenté sur un laboratoire autorisé. Si FTS5 manque sur la cible,
conserver `CAPABILITY_UNAVAILABLE` et soumettre séparément une proposition
SQLite/FTS5 embarquée versionnée avec ABI/licences/coût mémoire, sans activation
ni migration personnelle implicite.
