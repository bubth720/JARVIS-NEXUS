# NEX-03 — Recherche de connaissances citée et assemblage RAG local

## 1. Identité et responsabilité
- **Identifiant officiel :** NEX-03
- **Module :** NEXUS · **Dépôt :** `bubth720/JARVIS-NEXUS`
- **Branche documentaire :** `plan/intensive-2026-10-22` · **Base vérifiée :** `b27df0b84d8f0082d17ca6278549298aaaf97926`
- **Branche Codex d'implémentation proposée :** `feat/nex-03-recherche-de-connaissances-cit`
- **Priorité :** P1 · **Dépendances :** NEX-02 · **Autorisation :** préparation seule (aucune exécution engagée).

## 2. État initial vérifié
Dépôt main : README uniquement, sans runtime ni tests applicatifs. CORE possède déjà SQLite/PDF/RAG, SERVICES possède un module DOCUMENTS et FTS5 autonome ; NEXUS ne doit pas réimplémenter aveuglément ces moteurs.
Les chemins `nexus-core/`, `nexus-cli/` et `adapters/` ci-dessous sont **à créer** et non présentés comme existants. Ne pas confondre ce dépôt vide avec les fonctionnalités PDF/SQLite développées dans d'autres dépôts.

## 3. But fonctionnel et valeur livrée
Offrir à JARVIS des réponses documentairement fondées avec sources et extraits authentiques, hors ligne.
Cette mission doit aboutir à un composant exécutable et consommable depuis une API claire, pas uniquement à un diagramme, un protocole ou une interface vide.

## 4. Fonctionnalités à programmer (obligatoires)
1. Requêtes FTS5 classées lexicalement, avec tolérance limitée aux fautes et filtres date/collection/source.
2. Réduire les résultats redondants et regrouper des fragments proches avant passage au LLM.
3. Assembler ContextBundle avec citations stables, pages, intervalles, scores et budget maximal configurable.
4. Exposer une API RAG de récupération seule et une API de composition de prompt ; distinguer citation fiable et génération non vérifiée.
5. Port facultatif de reranking local interchangeable et désactivé sans modèle ; jamais un second moteur en résidence.
6. Rejeter prompt-injection dans documents en isolant leur contenu comme données non fiables.

## 5. Architecture, composants et responsabilités
1. SearchRepository : rangs FTS5 et paginations.
2. EvidenceReranker : filtrage/déduplication déterministe.
3. ContextBudgeter : bornes caractères/tokens estimés en attente du vrai tokenizer.
4. CitationResolver : ids source/page immuables.
5. RagContextPort : réponses structurées pour CORE.
Appliquer injection de dépendances par constructeur, erreurs typées, limites explicites, ownership unique du stockage et séparation domaine/adaptateurs/hôte. Aucun autre dépôt modifié sans accord distinct.

## 6. Fichiers à créer/modifier (cibles proposées)
1. À créer : nexus-core/src/main/kotlin/.../rag/{SearchRepository,EvidenceReranker,ContextBudgeter,CitationResolver}.kt
2. À créer : nexus-core/src/main/kotlin/.../api/RagContextPort.kt ; nexus-core/src/test/kotlin/.../RagRetrievalTest.kt
Avant d'écrire, relire l'arborescence du commit de base ; adapter seulement les nouveaux chemins pour éviter les doublons et documenter les changements exacts.

## 7. Plan de réalisation technique
1. Créer structures versionnées Query, SearchHit, Citation, ContextBundle avec erreurs distinctes.
2. Implémenter ranking lexical réel sur FTS5 et filtrage de sources révoquées.
3. Ajouter budget de contexte borné et déduplication ; les données externes ne deviennent jamais instructions système.
4. Construire démo qui renvoie trois citations localisées et le contexte correspondant sur corpus synthétique.
5. Mettre sous tests le mapping page/offset, l'absence de sources et les documents malveillants.

## 8. Technologies et dépendances
SQLite FTS5, Kotlin séquences/coroutines bornées ; interface optionnelle vers embeddings locaux sans dépendance obligatoire.
Ne pas ajouter de dépendance, SDK, accès réseau ni licence restrictive sans revue et traçabilité. Toute dépendance exécutable doit être résolue dans l'environnement de build autorisé.

## 9. Connexions intermodules prévues
CORE consomme ContextBundle via future couche service ; aucun modèle chargé par NEXUS.
Ne pas activer d'IPC de production, d'intégration CORE ou de migration de données sur cette mission. Préparer des ports locaux définis dans ce module et les cartographier avec UNIFIED sans modifier le contrat `contracts/v1`.

## 10. Sécurité, compatibilité et ressources
Le port LLM demeure passif jusqu'à qualification CORE ; ne pas appeler un moteur simulé.
Exiger absence de télémétrie et accès réseau au runtime, traitement borné des entrées, chemins sûrs, gestion d'erreurs fatales, conservation des données, opérations atomiques et fermeture des ressources. La cible Android doit rester sobre ; le mini-PC pourra charger les traitements lourds après qualification.

## 11. Livrables principaux
- **Code concret** des composants indiqués, avec intégration dans un build réalisable et une commande d'usage local.
- Démonstration exécutable sur données synthétiques et ports publics réellement invoqués.
- Tests de non-régression indispensables couvrant persistance, erreurs, cas nominaux ; rapport **court** de résultats exacts.
- Branche de code dédiée et commit(s) complets ; aucun poids, document personnel, clé ou APK utilisateur.

## 12. Critères d'achèvement vérifiables
1. Trois requêtes documentaires réelles renvoient fragments et citations résolubles.
2. Les références ne pointent jamais vers source supprimée ou non autorisée.
3. Le budget maximum est respecté et les textes sources sont clairement séparés du SYSTEM.
Les tests synthétiques ne doivent pas être présentés comme une qualification matérielle ou une compatibilité entre applications réelles.

## 13. Ordonnancement et parallélisme
Dépendances bloquantes : **NEX-02**. N'exécuter qu'après livraison vérifiable des ports nécessaires ; les autres modules peuvent évoluer en parallèle sur leurs propres branches.
Ne jamais exécuter deux missions touchant le même fichier dans le même checkout avant intégration consciente.

## 14. Périmètre des permissions
Cette fiche **n'est pas une autorisation d'exécution**. Aucun appareil personnel, aucune donnée privée, aucune installation, aucune action destructive, aucune fusion ou modification sensible d'un autre module. Les changements de contrats transversaux ou de stockage personnel exigent approbation spécifique.

## 15. Retour obligatoire à CENTRAL
Indiquer : **module, mission, dépôt, branche, base et commit final, fichiers créés/modifiés, démo exécutable, tests réellement passés/échoués/ignorés, limites, risques de régression, dépendances, prochaine action**. Ne pas écrire « terminé » si un composant clé reste fictif.
