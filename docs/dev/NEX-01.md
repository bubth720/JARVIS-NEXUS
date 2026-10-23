# NEX-01 — Stockage local livré

## Références et périmètre

- Module NEXUS, dépôt `bubth720/JARVIS-NEXUS`.
- Branche `feat/nex-01-moteur-local-de-connaissances-`.
- Main préservé : `b27df0b84d8f0082d17ca6278549298aaaf97926`.
- Départ documentaire vérifié : `e4b2018383a35106cd326848fab597ef07a50157`.
- Lecture des contrats UNIFIED v1 et de leurs règles de permissions : aucun
  schéma ni transport modifié, API locale NEXUS version 1.
- Le nouvel ordre global utilisateur autorise cette implémentation ordinaire ;
  les fiches historiques restent préservées avec leur statut de préparation.

## Code et propriété

Modules `nexus-core` et `nexus-cli` : données typées, policy injectée, repositories
internes, transactions et fermeture sérialisées par une unique connexion.
Sept tables : collection, source, document, revision, fragment, index_state,
mutation_log. Source = URI opaque + SHA-256 ; chaque source est immuable.
Document = identité par collection/URI ; une révision pointe vers une source.
Les fragments sont fournis explicitement par l’hôte ; NEX-01 ne prétend pas
avoir extrait ni vérifié sémantiquement leur correspondance au document.
Les noms, textes, tailles, pagination et offsets sont bornés. Le hash est calculé
en streaming et lit au plus la limite + 1 octet en cas de dépassement.

Une répétition exacte (contenu, titre, fragments) est idempotente. Un changement
exige la version attendue. Renommage et suppression/restauration créent une
nouvelle version ; un appel obsolète est refusé. Une source supprimée logiquement
n'expose plus ses fragments via le port normal. La purge est séparée, confirmée,
limitée à un document supprimé ; le journal survit et le fichier original n'est
jamais ouvert ni effacé. Les exports JSON paginés décrivent sources, documents,
provenance et mutations sans exporter les textes des fragments.

Les migrations et leur `user_version` sont atomiques ; une version supérieure
inconnue est refusée. Les échecs d'import annulent sources, révisions, fragments et
journal de la transaction. Pas de migration de bases CORE/SERVICES ou privées.

## Dépendances et licences

- Java 17, Kotlin 2.0.21 (Apache-2.0), Gradle 8.10.2.
- `org.xerial:sqlite-jdbc:3.46.1.0` : Apache-2.0/BSD-2-Clause, SQLite domaine public.
  Même version que le module desktop SERVICES inspecté pour cette campagne.
- SLF4J API transitif : MIT ; aucun fournisseur de logs ajouté.
- JUnit 4.13.2 (EPL-1.0) : tests seulement.
- Pas de réseau au runtime. Maven/Gradle servent uniquement au build initial ;
  le kit `installDist` contient les dépendances nécessaires à l'exécution locale.
- Adaptateur Android futur séparé : aucune promesse ABI/API Android issue de JDBC.

## Validation

Les commandes et résultats réellement obtenus sont consignés ci-dessous après
leur exécution. Les fixtures sont synthétiques, pas des données utilisateur.

## Gates suivants

NEX-02 exige le port d'extraction PDF SERVICES concret et épinglé ; pas de
réimplémentation concurrente. NEX-03 attend l'index réel de NEX-02. NEX-05 attend
NEX-01/NEX-02 et restaure seulement vers une nouvelle base autorisée. NEX-04
requiert NEX-03, une licence/ABI de lecteur ZIM qualifiée ou un refus explicite.
Aucun de ces gates n'est satisfait par la seule existence d'une classe vide.

Validation exécutée le 22 octobre 2026, environnement Linux Java 17 :

```sh
gradle test :nexus-cli:installDist wrapper --gradle-version 8.10.2 --distribution-type bin --gradle-distribution-sha256-sum 31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26 --no-daemon --console=plain
nexus-cli/build/install/nexus-cli/bin/nexus-cli seed-demo <base-temporaire-synthetique>
nexus-cli/build/install/nexus-cli/bin/nexus-cli inspect <meme-base>
```

Résultat : build réussi ; **18 tests JUnit, 0 échec, 0 erreur, 0 ignoré**.
Couverture : sept tables réelles, duplication, versions et provenance,
renommage/suppression/restauration/purge, autorisation et révocation pendant hash,
limites et Unicode, rollback après échec SQLite injecté, concurrence, pagination
JSON, fermeture/réouverture, migration interrompue et refus d'une version future,
SHA streaming, titres historiques et copie défensive des fragments.

La CLI a été lancée dans **deux processus indépendants** sur une base temporaire :
identifiants et sorties identiques après réouverture, deux documents (versions 2
et 1), trois sources immuables, quatre mutations ; JSON décodé réellement et
intégrité vérifiée. Avertissement SLF4J : fournisseur de logs absent, logger NOP,
sans échec de test ni dépendance réseau au runtime.

Les tests Android, intégrations intermodules, extraction PDF, FTS, RAG, ZIM et
chiffrement ne sont pas revendiqués. Pas d'installation, migration de données
personnelles, modification d'un autre dépôt ni fusion. Le SHA de livraison est
celui de la branche ; voir le compte rendu de campagne et `git rev-parse HEAD`.
