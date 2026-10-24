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
