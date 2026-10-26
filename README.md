# JARVIS NEXUS

Bibliothèque locale de connaissances. NEX-01 fournit un catalogue Kotlin/JVM,
SQLite transactionnel, provenance SHA-256, révisions, fragments et journal.
NEX-02 ajoute les imports bornés/reprenables et SQLite FTS5 réel ; l’adaptateur
optionnel réutilise le parseur SERVICES livré pour TXT, Markdown et PDF textuel.
Aucun moteur LLM, transport réseau, accès direct aux bases CORE/SERVICES ou
mémorisation conversationnelle automatique.

## Construire et démontrer

Java 17 ; Gradle Wrapper 8.10.2 avec SHA-256 de distribution vérifiée.

```sh
./gradlew test :nexus-cli:installDist
nexus-cli/build/install/nexus-cli/bin/nexus-cli seed-demo /tmp/nexus-demo.db
nexus-cli/build/install/nexus-cli/bin/nexus-cli inspect /tmp/nexus-demo.db
```

Choisir un nouveau chemin pour la démonstration synthétique. `seed-demo` importe
deux sources artificielles puis révise la première. `inspect`, dans un nouveau
processus, retrouve leurs identifiants et versions ; il ne modifie pas les
sources. Le fichier SQLite de démonstration reste hors Git.

## Consommer

L'hôte ouvre un `NexusDatabase` à un chemin privé qu'il contrôle et injecte une
`AccessPolicy` à `KnowledgeCatalogService`. Le catalogue reçoit un `InputStream`
explicitement choisi par l'hôte : aucune URI de document n'est ouverte par le
moteur. L'hôte conserve la propriété du stream et ferme la base via `use`.
Toutes les opérations de catalogue, y compris les lectures, vérifient la
politique. L'import vérifie de nouveau l'autorisation après le hash.

`KnowledgeReadPort` prépare une consultation locale typée. Les API refusent les
conflits et erreurs connues par `CatalogResult.Rejected`. Les versions attendues
protègent les mises à jour, renommages, suppressions logiques et restaurations.
La purge exige une permission distincte et une confirmation explicite ; elle
préserve provenance et journal. Aucun fichier source n'est supprimé.

Le schéma et l'API sont locaux à NEXUS, sans modification de UNIFIED v1.
Voir [détails et validation NEX-01](docs/dev/NEX-01.md).

## Limites

JVM/Linux qualifié par les tests, pas Android. SQLite JDBC embarque son propre
adaptateur natif desktop ; aucun APK ni JNI Android n'est créé.
NEX-04 ajoute un adaptateur ZIM conditionnel à python-libzim sur Linux.
La lecture native et le raccordement inter-application restent à qualifier.
La base est en clair dans le stockage choisi par l'hôte ; aucun chiffrement
applicatif n'est revendiqué par NEX-01.

## Importer via SERVICES sans le dupliquer

```sh
python3 tools/prepare-services.py /tmp/nexus-services-pinned
./gradlew -PservicesSource=/tmp/nexus-services-pinned test :nexus-services-adapter:installDist
nexus-services-adapter/build/install/nexus-services-adapter/bin/nexus-services-adapter import /tmp/nexus-import.db /chemin/document-choisi.pdf
nexus-services-adapter/build/install/nexus-services-adapter/bin/nexus-services-adapter search /tmp/nexus-import.db physique
```

Le téléchargement est celui de **sources de build publiques épinglées**, aucun
poids ni contenu documentaire. Le kit installé s’exécute sans ce téléchargement
et sans réseau. Les sources SERVICES sont vérifiées avant le composite build et
ne sont pas modifiées. Sans cette dépendance optionnelle, le noyau et la CLI
NEX-01 restent disponibles ; aucun faux parseur PDF n’est substitué.

Voir [NEX-02](docs/dev/NEX-02.md) pour la reprise explicite, la progression et les
limites de qualification.

## Récupération citée sans LLM

```sh
nexus-cli/build/install/nexus-cli/bin/nexus-cli rag-demo /tmp/nexus-rag-demo.db
```

NEX-03 expose `RagContextPort` : recherche FTS5 autorisée, citations résolubles,
regroupement et contexte JSON non fiable borné, sans appel d’inférence.
`LocalRagService` exige un scope d’autorité hôte explicite ; son défaut refuse.
Voir [NEX-03](docs/dev/NEX-03.md). Le budget du carrier ne remplace pas les tokens
exacts ni les protections d’admission CORE/LLM.

## Archives chiffrées et restauration isolée

```sh
nexus-cli/build/install/nexus-cli/bin/nexus-cli backup-demo /tmp
```

NEX-05 expose `SnapshotExporter` et `SnapshotImporter` avec permissions et
confirmation explicites, AES-GCM via `CryptoPort`, vérification SHA/SQLite/FTS
et conflits, sans remplacer les données courantes. La démonstration ne crée
que des données synthétiques temporaires et une clé éphémère. La base de travail
reste en clair dans le stockage privé de l'hôte ; seules les archives sont chiffrées.
Voir [format et limites](docs/dev/NEXUS-BACKUP-FORMAT.md) et [validation](docs/dev/NEX-05.md).

## Catalogue ZIM en lecture seule (NEX-04, qualification partielle)

```sh
python3 adapters/zim/nexus_zim.py --catalog /tmp/nexus-zim-catalog.db register '/chemin/wikipedia_fr_all_maxi_2026-05 (3).zim'
python3 adapters/zim/nexus_zim.py --catalog /tmp/nexus-zim-catalog.db list
python3 adapters/zim/nexus_zim.py --catalog /tmp/nexus-zim-catalog.db search ID_ARCHIVE Paris 5
python3 adapters/zim/nexus_zim.py --catalog /tmp/nexus-zim-catalog.db article ID_ARCHIVE CHEMIN_ENTREE
python3 -m unittest discover -s tests/zim -v
```

`ID_ARCHIVE` est retourné par `register`, qui enregistre seulement une référence
et un fingerprint de métadonnées non vérifiées. `search`/`article` appellent
les vraies API libzim ; elles refusent `UNSUPPORTED` si la dépendance manque.
Aucune installation automatique, copie du ZIM, génération d'index global ou
requête réseau. Le retrait `remove ID_ARCHIVE` supprime seulement la référence.
`SelectedArchiveContext` réutilise `RagContextPort` pour les articles explicitement
choisis par l'hôte ; résolution des citations par le même port.
Voir [rapport NEX-04](docs/dev/NEX-04.md) et [dépendances et SAF](docs/dev/ZIM-DEPENDENCIES.md).

## Bibliothèque et atlas — NEXUS-FINAL-01 (qualification partielle)

La branche ajoute métadonnées/tags, formats structurés bornés, import incrémental,
recherche par phrase/type et graphe de relations citées dans le même stockage.
Le [visualiseur autonome](viewer/README.md) fonctionne avec des pages JSON locales,
sans CDN, modèle ou serveur documentaire. La [qualification détaillée](docs/dev/NEXUS-FINAL-01.md)
sépare les tests SQLite/JavaScript exécutés du code Kotlin et d'Android non qualifiés.
Le schéma passe à v4 ; conserver une sauvegarde v3 avant ouverture par un nouveau
binaire. Les snapshots NEX-05 v3 se restaurent uniquement en copie isolée migrée.

Avec un toolchain déjà disponible (aucune installation automatique autorisée ici) :

```sh
./gradlew :nexus-core:test :nexus-cli:installDist --offline
nexus-cli/build/install/nexus-cli/bin/nexus-cli library /chemin/prive/nexus.db create MaCollection
nexus-cli/build/install/nexus-cli/bin/nexus-cli library /chemin/prive/nexus.db import ID_COLLECTION /chemin/table.csv
nexus-cli/build/install/nexus-cli/bin/nexus-cli library /chemin/prive/nexus.db search ID_COLLECTION 'mécanique quantique' csv phrase
nexus-cli/build/install/nexus-cli/bin/nexus-cli library /chemin/prive/nexus.db graph ID_COLLECTION > /chemin/choisi/page.json
nexus-cli/build/install/nexus-cli/bin/nexus-cli atlas-demo /tmp/nexus-atlas-nouveau.db > /tmp/nexus-atlas-page.json
```

Ces commandes JVM sont fournies pour la prochaine validation, **non exécutées ici**.
La CLI noyau importe CSV/TSV/DOCX/XML et ZIP tabulaire ; PDF/TXT/MD et ZIP textuels
réutilisent l'adaptateur SERVICES optionnel. Les formats non qualifiés sont refusés,
sans OCR ni téléchargement. Les reprises utilisent `ImportCoordinator` et le
source à nouveau choisi ; `ImportMetadata` porte catégorie/tags/date fichier.

Vérifications sans installation :

```sh
python3 tools/validate-final.py --browser --benchmark
python3 -m http.server 8765 --bind 127.0.0.1 --directory viewer
```

Les options navigateur exigent Node/Playwright/Chromium déjà présents. Le serveur
statique est un lancement volontaire local ; il n'ouvre ni ne sert la base.

## FUSION-01 — noyau portable et qualification réelle

La [livraison FUSION-01](docs/fusion-01/REPORT.md) réconcilie NEX-06A avec la
bibliothèque v4. `nexus-core` contient le métier partagé sans JDBC ; les
consommateurs JVM doivent dépendre de **`nexus-jvm-store`** et être recompilés.
Les snapshots et le lecteur processus ZIM restent JVM. Android s'inclut seulement
avec `-PwithAndroid=true`, minSdk24, et refuse si FTS5/vocab/MATCH/BM25 manquent.
Le contexte et les citations ne deviennent jamais une autorisation ou un SYSTEM.

Cette tranche a exécuté les tests Kotlin/JVM auparavant non faits. Les preuves
historiques restent inchangées ; consulter le nouveau rapport et sa
[matrice de compatibilité](docs/fusion-01/unified-integration-matrix.md).
Robolectric et compilation API24 ne prouvent ni appareil physique ni RAG Android.
Aucun raccordement CORE, IPC, JNI LLM ou migration personnelle n'est activé.

```sh
python3 tools/fusion-lab.py provision
python3 tools/fusion-lab.py gradle -- test :nexus-cli:installDist --no-daemon
python3 tools/check-fusion-lock.py
```

`provision` télécharge uniquement des outils de laboratoire épinglés dans `/tmp`,
sur demande explicite ; `gradle`/`api24` ne les installent pas automatiquement.
Voir le rapport pour l'adaptateur SERVICES et les contrôles Android séparés.

### FUSION-02 — qualification Android et refus sans destruction

Branche `feat/fusion-02-nexus-android`, base FUSION-01 `9d6928c`.
Sonde FTS5/MATCH/vocab(instance)/BM25 en mémoire avant création de base,
gestion de corruption sans suppression automatique et revalidation bornée des
sources `content:`. Les preuves émulateur et les limites restent distinctes des
tests JVM/Robolectric ; aucun backend SQLite externe ajouté.
Voir [rapport](docs/fusion-02/REPORT.md), [intégration CORE](docs/fusion-02/INTEGRATION.md)
et [options backend soumises à décision](docs/fusion-02/BACKENDS.md).
