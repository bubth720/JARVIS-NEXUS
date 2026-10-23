# JARVIS NEXUS

Bibliothèque locale de connaissances. NEX-01 fournit un catalogue Kotlin/JVM,
SQLite transactionnel, provenance SHA-256, révisions, fragments et journal.
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
adaptateur natif desktop ; aucun APK ni JNI Android n'est créé. Pas encore de
PDF, FTS, RAG, ZIM, chiffrement ou raccordement inter-application. Ces fonctions
restent soumises aux missions suivantes et à leurs dépendances réellement livrées.
La base est en clair dans le stockage choisi par l'hôte ; aucun chiffrement
applicatif n'est revendiqué par NEX-01.
