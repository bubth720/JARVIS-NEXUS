# NEX-01 — Moteur local de connaissances et registre de sources

## 1. Identité et responsabilité
- **Identifiant officiel :** NEX-01
- **Module :** NEXUS · **Dépôt :** `bubth720/JARVIS-NEXUS`
- **Branche documentaire :** `plan/intensive-2026-10-22` · **Base vérifiée :** `b27df0b84d8f0082d17ca6278549298aaaf97926`
- **Branche Codex d'implémentation proposée :** `feat/nex-01-moteur-local-de-connaissances-`
- **Priorité :** P0 · **Dépendances :** Aucune · **Autorisation :** préparation seule (aucune exécution engagée).

## 2. État initial vérifié
Dépôt main : README uniquement, sans runtime ni tests applicatifs. CORE possède déjà SQLite/PDF/RAG, SERVICES possède un module DOCUMENTS et FTS5 autonome ; NEXUS ne doit pas réimplémenter aveuglément ces moteurs.
Les chemins `nexus-core/`, `nexus-cli/` et `adapters/` ci-dessous sont **à créer** et non présentés comme existants. Ne pas confondre ce dépôt vide avec les fonctionnalités PDF/SQLite développées dans d'autres dépôts.

## 3. But fonctionnel et valeur livrée
Créer le premier runtime NEXUS réellement exécutable, base SQLite, documents, collections, sources immuables, révisions, index et transactions. Pas de mémoire conversationnelle implicite.
Cette mission doit aboutir à un composant exécutable et consommable depuis une API claire, pas uniquement à un diagramme, un protocole ou une interface vide.

## 4. Fonctionnalités à programmer (obligatoires)
1. Initialiser un module Kotlin/JVM testable, sans Android obligatoire ; proposer adaptateur Android seulement si bénéfice démontré.
2. Construire tables collection, source, document, revision, fragment, index_state et mutation_log avec clés et contraintes ; gérer migrations incrémentales.
3. Importer une source locale identifiée par URI opaque et SHA-256 : création idempotente, versions explicites, doublons et conflits retournés comme états typés.
4. Exposer CRUD transactionnel pour métadonnées et fragments bornés ; suppression logique et restauration avant purge explicite.
5. Sérialiser un catalogue de sources/provenances et un journal de mutations sans stocker automatiquement les souvenirs CORE.
6. Assurer contrôle d'intégrité, limites de taille, fermeture des connexions et reprise transactionnelle après erreur simulée.

## 5. Architecture, composants et responsabilités
1. NexusDatabase et SchemaMigrator : propriété exclusive des tables NEXUS.
2. SourceRepository et DocumentRepository : versionnement, requêtes paginées.
3. SourceFingerprint : hash streaming et identité stable.
4. KnowledgeCatalogService : opérations typées, réponses de refus explicites.
Appliquer injection de dépendances par constructeur, erreurs typées, limites explicites, ownership unique du stockage et séparation domaine/adaptateurs/hôte. Aucun autre dépôt modifié sans accord distinct.

## 6. Fichiers à créer/modifier (cibles proposées)
1. À créer : settings.gradle.kts, build.gradle.kts, nexus-core/src/main/kotlin/.../store/{NexusDatabase,SchemaMigrator,SourceRepository,DocumentRepository}.kt
2. À créer : nexus-core/src/main/kotlin/.../api/{KnowledgeCatalogService,SourceRecord,DocumentRecord,KnowledgeError}.kt
3. À créer : nexus-core/src/test/kotlin/.../NexusStorageTest.kt ; docs/dev/NEX-01.md
Avant d'écrire, relire l'arborescence du commit de base ; adapter seulement les nouveaux chemins pour éviter les doublons et documenter les changements exacts.

## 7. Plan de réalisation technique
1. Établir version JVM/SQLite et dépendances minimales après lecture des contrats UNIFIED ; fixer les contrats de stockage propres à NEXUS.
2. Définir la forme de chaque enregistrement, les index et contraintes d'unicité ; créer les migrations v1 et tests de reprise.
3. Implémenter opérations atomiques et contrôle d'accès local injecté, sans transport réseau ni accès libre au système de fichiers.
4. Écrire une démonstration CLI qui importe deux sources synthétiques, met à jour une révision puis rouvre la base et recherche les identifiants.
5. Construire et exécuter tests de migration, doublons, rollback et réouverture ; consigner commandes et limites.

## 8. Technologies et dépendances
Kotlin/JVM, SQLite via JDBC ou SQLDelight après revue de licence et compatibilité Android, JUnit ; pas de serveur ni Internet au runtime.
Ne pas ajouter de dépendance, SDK, accès réseau ni licence restrictive sans revue et traçabilité. Toute dépendance exécutable doit être résolue dans l'environnement de build autorisé.

## 9. Connexions intermodules prévues
Préparer un port de lecture local pour SERVICES/CORE sans IPC ni accès à la base interne ; aucun contrat UNIFIED modifié.
Ne pas activer d'IPC de production, d'intégration CORE ou de migration de données sur cette mission. Préparer des ports locaux définis dans ce module et les cartographier avec UNIFIED sans modifier le contrat `contracts/v1`.

## 10. Sécurité, compatibilité et ressources
Développement autonome autorisable, sans données privées.
Exiger absence de télémétrie et accès réseau au runtime, traitement borné des entrées, chemins sûrs, gestion d'erreurs fatales, conservation des données, opérations atomiques et fermeture des ressources. La cible Android doit rester sobre ; le mini-PC pourra charger les traitements lourds après qualification.

## 11. Livrables principaux
- **Code concret** des composants indiqués, avec intégration dans un build réalisable et une commande d'usage local.
- Démonstration exécutable sur données synthétiques et ports publics réellement invoqués.
- Tests de non-régression indispensables couvrant persistance, erreurs, cas nominaux ; rapport **court** de résultats exacts.
- Branche de code dédiée et commit(s) complets ; aucun poids, document personnel, clé ou APK utilisateur.

## 12. Critères d'achèvement vérifiables
1. Commande CLI réelle produit une base NEXUS puis la relit après redémarrage de processus.
2. Insertion dupliquée et migration interrompue ne corrompent aucun document.
3. Tests réels CRUD/versionning/transactions exécutés ; pas de simple mock stockage.
Les tests synthétiques ne doivent pas être présentés comme une qualification matérielle ou une compatibilité entre applications réelles.

## 13. Ordonnancement et parallélisme
Dépendances bloquantes : **Aucune**. Peut commencer indépendamment des autres dépôts.
Ne jamais exécuter deux missions touchant le même fichier dans le même checkout avant intégration consciente.

## 14. Périmètre des permissions
Cette fiche **n'est pas une autorisation d'exécution**. Aucun appareil personnel, aucune donnée privée, aucune installation, aucune action destructive, aucune fusion ou modification sensible d'un autre module. Les changements de contrats transversaux ou de stockage personnel exigent approbation spécifique.

## 15. Retour obligatoire à CENTRAL
Indiquer : **module, mission, dépôt, branche, base et commit final, fichiers créés/modifiés, démo exécutable, tests réellement passés/échoués/ignorés, limites, risques de régression, dépendances, prochaine action**. Ne pas écrire « terminé » si un composant clé reste fictif.
