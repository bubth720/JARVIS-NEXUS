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

JVM/Linux qualifié pour le catalogue et les sauvegardes. NEX-06A fournit désormais une
bibliothèque Android min24 distincte, conditionnée par une sonde réelle FTS5/vocab/MATCH/BM25.
Le backend SQLite Robolectric disponible n'a pas FTS5 : refus explicite, aucune citation
Android qualifiée. Aucun moteur natif Android supplémentaire, APK installé, lecteur ZIM ou
raccordement CORE n'est livré. Voir [NEX-06A et ses preuves](docs/dev/NEX-06A.md).
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

## Adaptateur Android API24 (NEX-06A)

```sh
./gradlew -PwithAndroid=true :nexus-android-store:testDebugUnitTest \
  :nexus-android-store:assembleDebug :nexus-android-store:lintDebug
python3 tools/check-android-boundary.py
```

Un hôte Android dépend uniquement de `nexus-android-store` et `nexus-core`, jamais du
module JDBC `nexus-jvm-store`. Il construit une `SelectedDocument` depuis un résultat
SAF volontaire et injecte sa politique d'accès. Si `AndroidNexusStorage.open` renvoie
`CAPABILITY_UNAVAILABLE`, il garde cette capacité désactivée. Les dépendances JVM
historiques utilisent désormais `nexus-jvm-store`. Aucun scan ou import ZIM automatique.
