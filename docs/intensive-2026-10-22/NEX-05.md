# NEX-05 — Sauvegarde chiffrable, restauration isolée et format d'échange

## 1. Identité et responsabilité
- **Identifiant officiel :** NEX-05
- **Module :** NEXUS · **Dépôt :** `bubth720/JARVIS-NEXUS`
- **Branche documentaire :** `plan/intensive-2026-10-22` · **Base vérifiée :** `b27df0b84d8f0082d17ca6278549298aaaf97926`
- **Branche Codex d'implémentation proposée :** `feat/nex-05-sauvegarde-chiffrable-restaura`
- **Priorité :** P2 · **Dépendances :** NEX-01,NEX-02 · **Autorisation :** préparation seule (aucune exécution engagée).

## 2. État initial vérifié
Dépôt main : README uniquement, sans runtime ni tests applicatifs. CORE possède déjà SQLite/PDF/RAG, SERVICES possède un module DOCUMENTS et FTS5 autonome ; NEXUS ne doit pas réimplémenter aveuglément ces moteurs.
Les chemins `nexus-core/`, `nexus-cli/` et `adapters/` ci-dessous sont **à créer** et non présentés comme existants. Ne pas confondre ce dépôt vide avec les fonctionnalités PDF/SQLite développées dans d'autres dépôts.

## 3. But fonctionnel et valeur livrée
Permettre de conserver et transférer volontairement la bibliothèque NEXUS avec provenance, versions et reprise après incident.
Cette mission doit aboutir à un composant exécutable et consommable depuis une API claire, pas uniquement à un diagramme, un protocole ou une interface vide.

## 4. Fonctionnalités à programmer (obligatoires)
1. Export versionné d'un snapshot de schéma, sources et index avec manifeste et SHA-256 par objet.
2. Restauration en base temporaire, vérification d'intégrité puis proposition d'adoption explicite, jamais écrasement implicite.
3. Port de chiffrement authenticated encryption avec clés détenues par la plateforme, mode refus si clé absente ; pas de clé dans Git.
4. Journal d'opérations rejouable et résolution de conflit par copies/version explicites.
5. Export/import SAF sans accès réseau ; préserver accès local téléphone et mini-PC.
6. Commandes de diagnostic integrity-check, dry-run restore et rollback en cas d'échec.

## 5. Architecture, composants et responsabilités
1. SnapshotExporter, SnapshotManifest, SnapshotImporter et RecoveryWorkspace.
2. IntegrityVerifier, CryptoPort, ConflictResolver et ImportPreview.
Appliquer injection de dépendances par constructeur, erreurs typées, limites explicites, ownership unique du stockage et séparation domaine/adaptateurs/hôte. Aucun autre dépôt modifié sans accord distinct.

## 6. Fichiers à créer/modifier (cibles proposées)
1. À créer : nexus-core/src/main/kotlin/.../backup/{SnapshotExporter,SnapshotImporter,IntegrityVerifier,CryptoPort}.kt
2. À créer : nexus-core/src/test/kotlin/.../SnapshotRecoveryTest.kt ; docs/dev/NEXUS-BACKUP-FORMAT.md
Avant d'écrire, relire l'arborescence du commit de base ; adapter seulement les nouveaux chemins pour éviter les doublons et documenter les changements exacts.

## 7. Plan de réalisation technique
1. Geler schema export v1 sans promettre compatibilité avec les backups CORE existants.
2. Construire snapshot vers un fichier fermé atomiquement et enregistrer hashes objets.
3. Implémenter restauration sur nouvelle base avec vérifications strictes, refus sans clé et aperçu des conflits.
4. Ajouter API dry-run et réactivation seulement après confirmation dans hôte consommateur.
5. Exécuter restauration de fixture, test de corruption et comparaison avant/après des données d'origine.

## 8. Technologies et dépendances
SQLite backup transactions, JSON canonique ou CBOR après revue, SHA-256, AES-GCM via CryptoPort Android Keystore/mini-PC.
Ne pas ajouter de dépendance, SDK, accès réseau ni licence restrictive sans revue et traçabilité. Toute dépendance exécutable doit être résolue dans l'environnement de build autorisé.

## 9. Connexions intermodules prévues
Export NEXUS distinct des données CORE et des packs VOICE ; aucune copie de souvenirs sans consentement.
Ne pas activer d'IPC de production, d'intégration CORE ou de migration de données sur cette mission. Préparer des ports locaux définis dans ce module et les cartographier avec UNIFIED sans modifier le contrat `contracts/v1`.

## 10. Sécurité, compatibilité et ressources
Aucune migration d'historiques privés CORE/SERVICES sans autorisation ; pas de synchronisation inter-appareils activée.
Exiger absence de télémétrie et accès réseau au runtime, traitement borné des entrées, chemins sûrs, gestion d'erreurs fatales, conservation des données, opérations atomiques et fermeture des ressources. La cible Android doit rester sobre ; le mini-PC pourra charger les traitements lourds après qualification.

## 11. Livrables principaux
- **Code concret** des composants indiqués, avec intégration dans un build réalisable et une commande d'usage local.
- Démonstration exécutable sur données synthétiques et ports publics réellement invoqués.
- Tests de non-régression indispensables couvrant persistance, erreurs, cas nominaux ; rapport **court** de résultats exacts.
- Branche de code dédiée et commit(s) complets ; aucun poids, document personnel, clé ou APK utilisateur.

## 12. Critères d'achèvement vérifiables
1. Restauration en sandbox donne un état identique au snapshot valide.
2. Archive corrompue ou clé manquante refuse sans modifier la base courante.
3. Export/version/provenance sont inspectables localement.
Les tests synthétiques ne doivent pas être présentés comme une qualification matérielle ou une compatibilité entre applications réelles.

## 13. Ordonnancement et parallélisme
Dépendances bloquantes : **NEX-01,NEX-02**. N'exécuter qu'après livraison vérifiable des ports nécessaires ; les autres modules peuvent évoluer en parallèle sur leurs propres branches.
Ne jamais exécuter deux missions touchant le même fichier dans le même checkout avant intégration consciente.

## 14. Périmètre des permissions
Cette fiche **n'est pas une autorisation d'exécution**. Aucun appareil personnel, aucune donnée privée, aucune installation, aucune action destructive, aucune fusion ou modification sensible d'un autre module. Les changements de contrats transversaux ou de stockage personnel exigent approbation spécifique.

## 15. Retour obligatoire à CENTRAL
Indiquer : **module, mission, dépôt, branche, base et commit final, fichiers créés/modifiés, démo exécutable, tests réellement passés/échoués/ignorés, limites, risques de régression, dépendances, prochaine action**. Ne pas écrire « terminé » si un composant clé reste fictif.
