# NEX-M50-01 — Choisir une recherche documentaire Android réellement disponible

Dépôt: bubth720/JARVIS-NEXUS
Branche de distribution: mission/m50-integrated-beta-20261029
Base figée de travail: c5dee7ab0344a23bfd80b13cd52d09e8b26241dd

**MISSION, PAS IMPLÉMENTATION.** Cible M50 = application unique avec coeur conversation, documents/CSV locaux et composants indépendants, seulement après qualifications de confiance. Cette mission est une tranche spécifique, distincte des PR lot3/lot4 existantes.

## Commande pour Codex
Ouvrir AGENTS.md si présent; vérifier les SHA, les PR existantes, provenance/CI et les lignes exactes du code. Créer une NOUVELLE branche `codex/m50-*` depuis cette branche de distribution, puis une PR brouillon vers elle. Ne pas modifier ni merger main, ni toucher aux données personnelles, ni relever de permission/SDK/ABI, ni activer connexion sensible sans validation distincte.

## Fichiers prioritaires à inspecter (confirmer leur présence)
- `docs/prefusion/lot3/REPORT.md`
- `docs/prefusion/lot3/BACKEND-DECISION.md`
- `nexus-android-store/src/main/kotlin/fr/jarvis/nexus/android/AndroidNexusStorage.kt`
- `nexus-android-store/src/main/kotlin/fr/jarvis/nexus/android/SelectedDocumentImporter.kt`
- `labs/prefusion_lot3/`

## Étapes détaillées

### 1. A — Diagnostic prouvé

Confirmer par instrumentation les primitives SQLite CREATE VIRTUAL TABLE/FTS5, MATCH, vocab, BM25 sur API24/25/26/35, minSdk24 et versions réelles du driver. Un test UNSUPPORTED réussi n'est pas une recherche qui fonctionne.

**Preuves d'acceptation de la phase :** diff ciblé et réversible, test nominal + contre-exemple, SHA40, commande exacte, sortie CI liée au HEAD, risques de régression et état PASS/BLOCKED/NOT_RUN.

### 2. B — Alternative sans migration

Comparer en laboratoire **sans changer le runtime existant** : 1) FTS5 embarqué correctement licencié/testé, 2) recherche littérale/FTS4 bornée avec limite et sémantique dégradée explicite. Mesurer RAM, taille APK, coût CPU/thermique, données indexées, source intacte et absence de réseau.

**Preuves d'acceptation de la phase :** diff ciblé et réversible, test nominal + contre-exemple, SHA40, commande exacte, sortie CI liée au HEAD, risques de régression et état PASS/BLOCKED/NOT_RUN.

### 3. C — Consultation TXT/PDF réelle

Sur l'émulateur, utiliser ACTION_OPEN_DOCUMENT, fournisseur distinct, grant lecture vérifié, digest de source, version/page/offset exact et citations revalidées, quota 2 MiB, URI révoquée, format malveillant, fermeture PFD et relecture après redémarrage. Ne jamais prétendre avoir un RAG si l'index réel est indisponible.

**Preuves d'acceptation de la phase :** diff ciblé et réversible, test nominal + contre-exemple, SHA40, commande exacte, sortie CI liée au HEAD, risques de régression et état PASS/BLOCKED/NOT_RUN.

### 4. D — Stratégie réversible

Produire un ADR séparé : branche optionnelle backend pour API24, compatibilité binaire, provenance et licences, fichier ancien inchangé, migration sur **copies synthétiques** et rollback validé avant toute migration réelle. **STOP avant import d'un nouveau driver/JNI ou migration sans approbation distincte**.

**Preuves d'acceptation de la phase :** diff ciblé et réversible, test nominal + contre-exemple, SHA40, commande exacte, sortie CI liée au HEAD, risques de régression et état PASS/BLOCKED/NOT_RUN.

### 5. E — Contrat CORE et CI

Publier port consultatif versionné et tests de consommateurs sur sources synthétiques, uniquement lorsque recherche réelle prouvée; campagnes JVM + 14 cas par API24/25/26/35 sur job existant, CI exact et rapport Physical NOT_RUN si non exécuté.

**Preuves d'acceptation de la phase :** diff ciblé et réversible, test nominal + contre-exemple, SHA40, commande exacte, sortie CI liée au HEAD, risques de régression et état PASS/BLOCKED/NOT_RUN.

## Règles de stop et critères finals
- D'abord prototypes OFF/testOnly. Le consentement utilisateur à l'architecture ou à des principes n'autorise PAS permissions, authentification de production, migration, import natif, export SAF ou installation physique.
- Préserver contrat historique, bases, voix personnelles, minSdk, permissions et anciennes variantes; voir le dossier central M50.
- Aucune réussite hôte ne qualifie Android réel; aucun résultat d'émulateur ne qualifie un appareil physique.
- Ne jamais masquer des échecs, affaiblir des tests, extrapoler READY, prétendre à un outil natif libéré ou créer de citation synthétique présentée comme réelle.
- Rendre : module, mission, branche, HEAD SHA + tree, tests succès/échecs/skips/non exécutés, CI et PR, blocages, dépendances, rollback, prochaine action.
- Ne jamais fusionner main sans confirmation explicite; un échec bloquant = rapport et STOP.
