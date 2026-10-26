# FUSION-02 — décision backend Android (25 octobre 2026)

Aucun backend externe ajouté, chargé ou déclaré qualifié. Inspection statique hors Gradle
uniquement dans `/workspace/.fusion02-lab`. Les mesures d'AAR ne sont ni une taille APK
finale ni une mesure RAM. Les sources et empreintes observées sont dans
`evidence/backend-inspection.json`. Une présence de chaîne `fts5` n'est pas une sonde.

| Option | Provenance / licence | API24 / ARM64 | Taille | Sécurité / sémantique | Mémoire / thermique | Rollback et preuve requise |
|---|---|---|---|---|---|---|
| SQLite plateforme, retenu | OS Android / AOSP ; SQLite domaine public, wrappers Apache-2.0 | API24 de compilation, ABI fournie par OS ; capacités par processus | zéro bibliothèque native ajoutée | Versions/correctifs OEM variables. Probe en mémoire FTS5 + phrase MATCH + positions vocab(instance) + score BM25 ; refus sans création de fichier. Pas d'équivalence présumée selon API | Non mesuré sur ARM64 ; mémoire sonde bornée à 2 lignes, fermeture systématique | Aucun changement natif, aucun reset. Exiger instrumentation propre API24/25, données originales intactes. RAG reste bloqué si probe négative |
| AndroidX `sqlite-bundled-android:2.5.2`, candidat inspecté | Google Maven ; POM Apache-2.0 ; SQLite embarqué domaine public ; compléter notices transitives avant distribution | Manifest min21 ; ELF AArch64 et 4 ABI présents. Kotlin stdlib 2.1.10 déclaré, écart avec compilateur NEXUS 2.0.21 à qualifier | AAR 3 365 857 octets ; ARM64 `.so` 1 932 216 octets, ZIP 833 624 octets ; voir SHA256 | Nouveau driver à adapter à NexusStoragePort, revue native/CVE et compatibilité fichier/SQL nécessaires. Chaînes FTS5 observées, aucune garantie runtime | NOT_RUN. Proposition à arbitrer : mesurer PSS avant/après, import 2MiB/100 pages, latence p50/p95 et température sur téléphone ; aucun budget chiffré vendu comme mesure | Approbation spécifique AVANT dépendance/JNI. Tester même probe et migration isolée, crash/reprise, coexistence JVM ; conserver ancien binaire et sauvegarde isolée, jamais rétrograder la base en place |
| Requery `com.github.requery:sqlite-android:3.50.4`, candidat documentaire | README/LICENSE figés au commit `0bbaa7a8b4c485c0d4b385425113fe33ead6c3c0` ; JitPack annoncé ; Apache-2.0 et SQLite domaine public à contrôler dans l'artefact final | README annonce min21, ARM64/ARMv7/x86/x64 ; ABI/artefact exacts NON inspectés | README donne environ 1,7MB ARM64 ; estimation éditeur, pas mesure ici. Les anciennes coordonnées `io.requery` Maven Central ont renvoyé404 | API proche d'Android sous autre namespace. Supply-chain JitPack, reproductibilité, symboles/page16K, CVE, `fts5vocab(instance)` et BM25 à vérifier. Pas de téléchargement/chargement du natif | NOT_RUN ; mêmes mesures et plafonds à arbitrer | Approbation spécifique puis artefact immuable + SHA + notices + probe/régression ; aucun simple remplacement d'import global |
| FTS4 plateforme / lexique dégradé, NON implémenté | OS actuel, pas de nouveau natif | Dépend aussi de la plateforme, ARM64 à instrumenter | Pas de bibliothèque ajoutée ; index supplémentaire NON créé | Ne fournit pas le contrat FTS5/vocab(instance)/BM25. Exiger autre identifiant de capacité et autre contrat versionné, revue CORE/UNIFIED. Aucun alias trompeur documents.search | NOT_RUN ; scoring/performance/qualité différents | Autorisation architecturale avant toute migration/index concurrent ; branche indépendante, nouvelles citations/positions testées |

Préférence proposée, sans décision d'activation : conserver le refus plateforme dans
cette tranche ; évaluer AndroidX avec un adaptateur séparé seulement après validation.
L'ajout de SQLite externe est expressément soumis à accord par NEXUS issue3 N2 et
UNIFIED issue5 §4 N2. Ni la publication de cette étude ni une CI verte ne vaut cet accord.

API24/25/26/29/35 : compilation min24 commune ; observations Robolectric étiquetées
HOST_ONLY, jamais transférées à un appareil. ARM64 et appareils physiques demeurent
non qualifiés tant qu'aucune preuve correspondante n'est produite. ZIM exclu.
