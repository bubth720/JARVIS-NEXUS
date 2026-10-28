# NEX-PF-02 — décision backend proposée, non approuvée

Source revue : `docs/fusion-02/BACKENDS.md`, inchangée, et les sources
AndroidFts5Probe/AndroidNexusStorage/SelectedDocumentImporter à
`b2b899c8cce73ee0763bf6896fb9ceffe62c2bc6`. Cette tranche n'ajoute aucun driver.
Les mesures historiques d'artefacts restent historiques, pas de mesures appareil nouvelles.

| Option | Gain / contraintes | ABI, taille, licences | Décision proposée |
|---|---|---|---|
| SQLite plateforme | Aucun second backend; FTS5 et vocab/BM25 dépendent de la version OEM. Refus propre si absents. | ABI OS, zéro `.so` ajouté, SQLite domaine public / wrappers AOSP Apache-2.0. | Garder la voie actuelle OFF pour RAG tant que non qualifiée. |
| AndroidX sqlite-bundled 2.5.2 candidat | FTS5 portable à vérifier; adaptation du stockage, Kotlin transitif 2.1.10 vs compilateur 2.0.21, revue 16 KiB/CVE/reproductibilité. | Inspection historique : AAR 3 365 857 octets; ARM64 `.so` 1 932 216 octets; quatre ABI, min21 annoncé. Apache-2.0 et notices transitives à vérifier. | Préférence d'étude isolée après autorisation native spécifique; aucune dépendance ajoutée ici. |
| Requery sqlite-android candidat | Namespace Android proche, supply chain JitPack, qualification SQL et fichier requises. | ABI et taille d'artefact non vérifiées ici; licence/notice finale à contrôler. | Alternative, pas de téléchargement ni substitution. |
| FTS4 ou recherche dégradée | Ne satisfait pas vocab(instance)/BM25 ni la sémantique actuelle de citations. | Pas de nouveau natif, mais index/migration et contrat de capacité distincts nécessaires. | Ne jamais annoncer documents.search ou RAG équivalent par alias. |

RAM/PSS, temps de chauffe, latence, taille APK finale et consommation : **NOT_RUN**.
Avant choix : mesurer PSS repos/import 2 MiB/recherche, p50/p95, température et batterie
sur matériel autorisé; plafonds existants inchangés. Vérifier ARM64 et page 16 KiB,
licences, hash binaire/source et chaîne de construction. Une API35 verte ne qualifie
ni API24 ni un OEM particulier. Le prototype SQL temporaire n'utilise pas la base réelle.

Migration : aucune ici. Si autorisée ultérieurement, export/sauvegarde vérifiée,
restauration isolée, compatibilité de version, ancien binaire et bases WAL/SHM conservés;
pas de rétrogradation ou migration en place implicite. Rollback présent : enlever
`-PwithPrefusionLab=true` (par défaut absent) ou abandonner la branche; aucune donnée
utilisateur créée ni modifiée.

**Décisions nécessaires** : sélection du backend et ajout natif éventuel; plan de migration
physique; qualification sur appareil personnel. Le grant SAF de démonstration n'est pas
une autorité CORE ni un consentement durable. L'ajout du fournisseur test protégé
MANAGE_DOCUMENTS ne modifie aucune permission ni aucun manifeste de production.
RAG reste **UNSUPPORTED/BLOCKED**, indépendamment des primitives SQL observées.
