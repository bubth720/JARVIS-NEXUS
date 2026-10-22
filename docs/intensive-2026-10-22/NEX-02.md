# NEX-02 — Chaîne d'import local, extraction et indexation incrémentale

## 1. Identité et responsabilité
- **Identifiant officiel :** NEX-02
- **Module :** NEXUS · **Dépôt :** `bubth720/JARVIS-NEXUS`
- **Branche documentaire :** `plan/intensive-2026-10-22` · **Base vérifiée :** `b27df0b84d8f0082d17ca6278549298aaaf97926`
- **Branche Codex d'implémentation proposée :** `feat/nex-02-chaine-d-import-local-extracti`
- **Priorité :** P0 · **Dépendances :** NEX-01 · **Autorisation :** préparation seule (aucune exécution engagée).

## 2. État initial vérifié
Dépôt main : README uniquement, sans runtime ni tests applicatifs. CORE possède déjà SQLite/PDF/RAG, SERVICES possède un module DOCUMENTS et FTS5 autonome ; NEXUS ne doit pas réimplémenter aveuglément ces moteurs.
Les chemins `nexus-core/`, `nexus-cli/` et `adapters/` ci-dessous sont **à créer** et non présentés comme existants. Ne pas confondre ce dépôt vide avec les fonctionnalités PDF/SQLite développées dans d'autres dépôts.

## 3. But fonctionnel et valeur livrée
Transformer des documents locaux en fragments recherchables avec provenance précise, sans créer une seconde implémentation PDFBox concurrente de SERVICES.
Cette mission doit aboutir à un composant exécutable et consommable depuis une API claire, pas uniquement à un diagramme, un protocole ou une interface vide.

## 4. Fonctionnalités à programmer (obligatoires)
1. Créer une API de sources ImportJob, ImportResult, Cancellation et ParserPort.
2. Brancher adaptateurs texte brut, Markdown et PDF textuel local ; pour PDF réutiliser adapter SERVICES seulement via port ou bibliothèque commune approuvée, sans couplage au stockage SERVICES.
3. Normaliser Unicode, dédupliquer par SHA256 et découper par taille/paragraphes avec offsets et pages préservés.
4. Indexer incrémentalement les fragments dans SQLite FTS5 et rendre la réindexation annulable et reprenable.
5. Rejeter archives piégées, documents chiffrés non pris en charge, dépassements mémoire et texte invalide, sans effet partiel.
6. Créer file locale bornée de tâches et un écran/CLI de progression avec états explicites.

## 5. Architecture, composants et responsabilités
1. ImportCoordinator : transaction métier et reprise.
2. ParserRegistry : détection par type et extracteurs autorisés.
3. ChunkIndexer : fragments et provenance (page, offset, source id).
4. ImportProgressStore : tâche persistée, interruption et rejet typé.
Appliquer injection de dépendances par constructeur, erreurs typées, limites explicites, ownership unique du stockage et séparation domaine/adaptateurs/hôte. Aucun autre dépôt modifié sans accord distinct.

## 6. Fichiers à créer/modifier (cibles proposées)
1. À créer : nexus-core/src/main/kotlin/.../import/{ImportCoordinator,ParserRegistry,ChunkIndexer,ImportJob}.kt
2. À créer : nexus-core/src/main/kotlin/.../search/FtsIndex.kt ; nexus-cli/src/main/kotlin/.../ImportCli.kt
3. À créer : nexus-core/src/test/kotlin/.../{ImportPipelineTest,FtsIndexTest}.kt
Avant d'écrire, relire l'arborescence du commit de base ; adapter seulement les nouveaux chemins pour éviter les doublons et documenter les changements exacts.

## 7. Plan de réalisation technique
1. Réutiliser schéma NEX-01 et vérifier API de documents SERVICES existante.
2. Créer ports stream/chunk pour exclure chargement intégral en mémoire et distinguer extraction de stockage.
3. Implémenter import normal et abort atomique, idempotence SHA, progression et quota.
4. Ajouter FTS5 locale réelle et mise à jour incrémentale lors des révisions/suppressions.
5. Fournir démo avec PDF synthétique/Markdown/texte et vérifier extraits/pagination/reprise.

## 8. Technologies et dépendances
Kotlin/JVM + SQLite FTS5, SAF Android dans adaptateur optionnel ; PDFBox déjà présent dans SERVICES si intégration autorisée.
Ne pas ajouter de dépendance, SDK, accès réseau ni licence restrictive sans revue et traçabilité. Toute dépendance exécutable doit être résolue dans l'environnement de build autorisé.

## 9. Connexions intermodules prévues
SERVICES reste propriétaire extraction riche ; NEXUS propriétaire archivage et index ; CORE passe seulement des documents explicitement choisis.
Ne pas activer d'IPC de production, d'intégration CORE ou de migration de données sur cette mission. Préparer des ports locaux définis dans ce module et les cartographier avec UNIFIED sans modifier le contrat `contracts/v1`.

## 10. Sécurité, compatibilité et ressources
Conserver NEX-01 ; nouveaux importeurs seulement locaux et bornés.
Exiger absence de télémétrie et accès réseau au runtime, traitement borné des entrées, chemins sûrs, gestion d'erreurs fatales, conservation des données, opérations atomiques et fermeture des ressources. La cible Android doit rester sobre ; le mini-PC pourra charger les traitements lourds après qualification.

## 11. Livrables principaux
- **Code concret** des composants indiqués, avec intégration dans un build réalisable et une commande d'usage local.
- Démonstration exécutable sur données synthétiques et ports publics réellement invoqués.
- Tests de non-régression indispensables couvrant persistance, erreurs, cas nominaux ; rapport **court** de résultats exacts.
- Branche de code dédiée et commit(s) complets ; aucun poids, document personnel, clé ou APK utilisateur.

## 12. Critères d'achèvement vérifiables
1. Des fichiers de trois formats passent une extraction et un index réels.
2. Une interruption conserve un état cohérent et permet reprise sans doublons.
3. La recherche FTS retourne offsets et provenance vérifiables.
Les tests synthétiques ne doivent pas être présentés comme une qualification matérielle ou une compatibilité entre applications réelles.

## 13. Ordonnancement et parallélisme
Dépendances bloquantes : **NEX-01**. N'exécuter qu'après livraison vérifiable des ports nécessaires ; les autres modules peuvent évoluer en parallèle sur leurs propres branches.
Ne jamais exécuter deux missions touchant le même fichier dans le même checkout avant intégration consciente.

## 14. Périmètre des permissions
Cette fiche **n'est pas une autorisation d'exécution**. Aucun appareil personnel, aucune donnée privée, aucune installation, aucune action destructive, aucune fusion ou modification sensible d'un autre module. Les changements de contrats transversaux ou de stockage personnel exigent approbation spécifique.

## 15. Retour obligatoire à CENTRAL
Indiquer : **module, mission, dépôt, branche, base et commit final, fichiers créés/modifiés, démo exécutable, tests réellement passés/échoués/ignorés, limites, risques de régression, dépendances, prochaine action**. Ne pas écrire « terminé » si un composant clé reste fictif.
