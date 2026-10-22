# NEX-04 — Bibliothèque encyclopédique hors ligne et catalogue ZIM

## 1. Identité et responsabilité
- **Identifiant officiel :** NEX-04
- **Module :** NEXUS · **Dépôt :** `bubth720/JARVIS-NEXUS`
- **Branche documentaire :** `plan/intensive-2026-10-22` · **Base vérifiée :** `b27df0b84d8f0082d17ca6278549298aaaf97926`
- **Branche Codex d'implémentation proposée :** `feat/nex-04-bibliotheque-encyclopedique-ho`
- **Priorité :** P2 · **Dépendances :** NEX-01,NEX-03 · **Autorisation :** préparation seule (aucune exécution engagée).

## 2. État initial vérifié
Dépôt main : README uniquement, sans runtime ni tests applicatifs. CORE possède déjà SQLite/PDF/RAG, SERVICES possède un module DOCUMENTS et FTS5 autonome ; NEXUS ne doit pas réimplémenter aveuglément ces moteurs.
Les chemins `nexus-core/`, `nexus-cli/` et `adapters/` ci-dessous sont **à créer** et non présentés comme existants. Ne pas confondre ce dépôt vide avec les fonctionnalités PDF/SQLite développées dans d'autres dépôts.

## 3. But fonctionnel et valeur livrée
Ajouter un véritable catalogue d'archives hors ligne et la recherche encyclopédique locale, compatible téléphone économe et futur mini-PC.
Cette mission doit aboutir à un composant exécutable et consommable depuis une API claire, pas uniquement à un diagramme, un protocole ou une interface vide.

## 4. Fonctionnalités à programmer (obligatoires)
1. Indexer les fichiers ZIM sélectionnés localement sans copier automatiquement les archives géantes.
2. Lire en flux métadonnées et entrées par une interface ArchiveReader, avec limites et vérification de chemin.
3. Supporter un adaptateur libzim/Kiwix si dépendance et ABI disponibles ; sinon fournir un lecteur sur mini-PC et réponse UNSUPPORTED sur Android sans fausse simulation.
4. Gérer recherche par titre et fragments extraits à la demande, caches LRU plafonnés, provenance archive/article.
5. Permettre retrait de catalogue sans effacer l'archive originale.
6. Proposer import source encyclopédique vers RagContextPort uniquement sur choix utilisateur.

## 5. Architecture, composants et responsabilités
1. OfflineArchiveCatalog, ArchiveReader interface, ZimReaderAdapter.
2. ArchiveSearch, ArticleExtractor et BoundedCache.
3. ArchiveProvenance et UnsupportedFormatError.
Appliquer injection de dépendances par constructeur, erreurs typées, limites explicites, ownership unique du stockage et séparation domaine/adaptateurs/hôte. Aucun autre dépôt modifié sans accord distinct.

## 6. Fichiers à créer/modifier (cibles proposées)
1. À créer : nexus-core/src/main/kotlin/.../archive/{OfflineArchiveCatalog,ArchiveReader,ArchiveSearch}.kt
2. À créer : adapters/zim/src/main/.../ZimReaderAdapter.kt (proposé, dépendance conditionnelle), docs/dev/ZIM-DEPENDENCIES.md
3. À créer : tests d'archive fixture sous nexus-core/src/test/...
Avant d'écrire, relire l'arborescence du commit de base ; adapter seulement les nouveaux chemins pour éviter les doublons et documenter les changements exacts.

## 7. Plan de réalisation technique
1. Comparer versions/licences libzim et compatibilité de distribution hors ligne ; ne pas bloquer le noyau si non disponible.
2. Implémenter catalogue et lecteur abstrait réellement utilisé par un adaptateur de test sur archive locale autorisée.
3. Ajouter recherche et extraction avec quotas et nettoyage cache.
4. Créer CLI pour enregistrer archive choisie, consulter titres et exporter un contexte cité.
5. Tester chemin interdit, corruption, volume important simulé en streaming et déconnexion d'archive.

## 8. Technologies et dépendances
Kotlin/JVM, libzim uniquement après analyse licence/ABI ; cache local borné.
Ne pas ajouter de dépendance, SDK, accès réseau ni licence restrictive sans revue et traçabilité. Toute dépendance exécutable doit être résolue dans l'environnement de build autorisé.

## 9. Connexions intermodules prévues
NEXUS conserve archives et index ; INTERFACE pourra proposer un navigateur ; CORE utilisera RagContextPort.
Ne pas activer d'IPC de production, d'intégration CORE ou de migration de données sur cette mission. Préparer des ports locaux définis dans ce module et les cartographier avec UNIFIED sans modifier le contrat `contracts/v1`.

## 10. Sécurité, compatibilité et ressources
Ne pas télécharger Wikipédia ni créer de cache partagé sans accord explicite ; lecteur ZIM natif facultatif.
Exiger absence de télémétrie et accès réseau au runtime, traitement borné des entrées, chemins sûrs, gestion d'erreurs fatales, conservation des données, opérations atomiques et fermeture des ressources. La cible Android doit rester sobre ; le mini-PC pourra charger les traitements lourds après qualification.

## 11. Livrables principaux
- **Code concret** des composants indiqués, avec intégration dans un build réalisable et une commande d'usage local.
- Démonstration exécutable sur données synthétiques et ports publics réellement invoqués.
- Tests de non-régression indispensables couvrant persistance, erreurs, cas nominaux ; rapport **court** de résultats exacts.
- Branche de code dédiée et commit(s) complets ; aucun poids, document personnel, clé ou APK utilisateur.

## 12. Critères d'achèvement vérifiables
1. Le catalogue et la recherche fonctionnent sur un corpus d'archive local autorisé, ou l'absence de libzim est explicitement refusée sans fausse réussite.
2. Aucun téléchargement automatique ni modification du ZIM source.
3. La provenance article/archive accompagne chaque résultat.
Les tests synthétiques ne doivent pas être présentés comme une qualification matérielle ou une compatibilité entre applications réelles.

## 13. Ordonnancement et parallélisme
Dépendances bloquantes : **NEX-01,NEX-03**. N'exécuter qu'après livraison vérifiable des ports nécessaires ; les autres modules peuvent évoluer en parallèle sur leurs propres branches.
Ne jamais exécuter deux missions touchant le même fichier dans le même checkout avant intégration consciente.

## 14. Périmètre des permissions
Cette fiche **n'est pas une autorisation d'exécution**. Aucun appareil personnel, aucune donnée privée, aucune installation, aucune action destructive, aucune fusion ou modification sensible d'un autre module. Les changements de contrats transversaux ou de stockage personnel exigent approbation spécifique.

## 15. Retour obligatoire à CENTRAL
Indiquer : **module, mission, dépôt, branche, base et commit final, fichiers créés/modifiés, démo exécutable, tests réellement passés/échoués/ignorés, limites, risques de régression, dépendances, prochaine action**. Ne pas écrire « terminé » si un composant clé reste fictif.
