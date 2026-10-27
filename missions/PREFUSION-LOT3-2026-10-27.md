# NEX-PF-02 — Choix backend FTS5/SAF Android avant raccordement CORE

**État : MISSION CODEX À EXÉCUTER (aucune implémentation revendiquée par ce document).**
Dépôt : bubth720/JARVIS-NEXUS
Branche source : mission/prefusion-lot3-20261027
SHA de base du lot vérifié : 201f0d2b03209de104157595a557143fe87eb94e

## Règles d'exécution obligatoires
1. Lire AGENTS.md s'il existe, les rapports du lot précédent, vérifier HEAD et CI.
2. Créer une nouvelle branche codex/prefusion-lot3-* issue de la branche de mission. Ne pas développer dans main.
3. Tranches courtes avec tests négatifs, commits, CI exacte, PR brouillon vers la branche mission.
4. Hors ligne au runtime, compatibilité Android et données personnelles préservées. Aucun merge, migration, extension de permissions, import architectural ou activation sensible sans décision explicite.
5. Tout effet d'écriture, nouveau backend SQLite natif ou transport UID/certificat non approuvé reste OFF et limité à tests synthétiques.

## Fichiers à examiner
- `nexus-android-store/src/main/kotlin/fr/jarvis/nexus/android/AndroidFts5Probe.kt`
- `nexus-android-store/src/main/kotlin/fr/jarvis/nexus/android/AndroidNexusStorage.kt`
- `nexus-android-store/src/main/kotlin/fr/jarvis/nexus/android/SelectedDocumentImporter.kt`
- `docs/prefusion/ASSESSMENT.md`

## Étapes

### Phase 1

Lire compte rendu lot1, README de backend et preuve réelle API24/25; comparer SQLite plateforme et SQLite embarqué candidat sans substituer une dépendance native ni exiger un SDK plus haut.

### Phase 2

Établir prototype OFF sur sources synthétiques pour matrice FTS5 CREATE, MATCH, vocab, BM25, migration/réouverture; distinguer tests hôte SQLite de vrai émulateur Android.

### Phase 3

Tester SAF ACTION_OPEN_DOCUMENT : grants de lecture révocables, fournisseur distinct, sélection confirmée, PFD clos, digest/version/page/offset, limites 2 MiB et original intact. Aucun stockage URI persistant hors consentement explicite.

### Phase 4

Livrer rapport de décision backend FTS5 avec alternatives, coûts mémoire/thermique, ABI, taille APK, licences, risques de migrations et rollback. **STOP avant ajout driver natif ou migration sans accord utilisateur**. L’absence backend qualifié laisse RAG UNSUPPORTED.

### Phase 5

Run CI JVM et Android-hôte au SHA exact, conserver hash historiques; appareil personnel NOT_RUN sauf validation. PR brouillon avec matrice de capacité et blocages.

## Critères de réception
- Aucune régression, ancienne fonctionnalité, contrat gelé ou preuve historique supprimés.
- Tests au SHA de la PR et à la granularité JVM / Robolectric / Android émulateur / appareil physique, sans inventer les non exécutés.
- Distinguer fiche d'architecture, prototype et connexion de production; le statut d'intégration reste BLOCKED si manque autorité, backend ou appareil.
- Rapport : module, mission, branche, commit, tests avec totaux/échecs/sauts, blocages, fichier d'impact, rollback, prochaine action.
- PR brouillon uniquement. STOP et demande de décision pour toute opération sensible.
