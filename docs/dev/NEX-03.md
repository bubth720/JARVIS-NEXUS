# NEX-03 — Récupération documentaire citée

NEXUS · `feat/nex-03-recherche-de-connaissances-cit`.
Base directe NEX-02 : `bfa46e1fe08d4943fe864ac53d0c0ba56109ea9c` ; plan
`e4b2018383a35106cd326848fab597ef07a50157`. Aucun autre dépôt ou contrat UNIFIED
v1 modifié ; aucun raccordement CORE ni moteur LLM ajouté.

## Code livré et port

`fr.jarvis.nexus.rag.RagContextPort` :

```kotlin
fun retrieve(query: KnowledgeQuery, scope: SearchScope,
             limits: ContextLimits = ContextLimits()): CatalogResult<ContextBundle>
fun resolve(citation: Citation, scope: SearchScope): CatalogResult<String>
```

`LocalRagService(database, catalog, hostScope)` implémente ce port. L’autorité de
lecture de l’hôte est **refusée par défaut** et intersectée avec la sélection du
requérant ; la sélection n’est pas une permission. Le contenu source ne peut
modifier cette autorité, qui appartiendra à CORE dans une intégration future.
Lecture globale, scopes et citations sont revérifiés avant restitution. Aucun IPC
ou service exporté n’est créé. Adaptateur Android non fourni : ne pas lier le
driver JDBC desktop directement au CORE API24.

- Vrai FTS5/bm25, filtres collection/source et date **d’import NEXUS**.
  Le schéma local v3 conserve NULL pour les dates historiques inconnues : aucune
  date de création du fichier n’est inventée.
- Recherche NFC ; fallback lexical borné à une seule édition sur mot ASCII
  de 4–32 caractères, première lettre identique, vocabulaire autorisé limité
  à 256 candidats. Le résultat expose `FUZZY_ONE_EDIT`, pas une correction IA.
- Déduplication de texte avec provenances distinctes ; regroupement contigu
  uniquement même document/version/page, sans inventer des passages manquants.
- Citation stable : document, révision, ordinal, URI et SHA de source, page,
  offsets UTF-16 du texte brut, SHA d’extrait et identifiant dérivé. Résolution
  refuse source hors scope, modification, suppression et version non courante.
- Budget vérifié sur le **JSON effectivement sérialisé**, citations et métadonnées
  comprises ; pas de troncature d’extrait non traçable. Limites de sources et
  d’éléments, refus explicite sans preuve ou si le carrier ne tient pas.
- `asUntrustedJson()` produit seulement du contexte documentaire structuré et
  échappé, `trusted:false`, sans rôle SYSTEM, permission ou instruction exécutable.
  Cela ne prouve pas une résistance absolue d’un futur modèle aux injections.
  Le moteur n’est pas appelé ; CORE/LLM garderont assemblage et admission réels.
- `estimatedTokens` est un indicateur éditorial des **extraits seulement**,
  UTF-8/4, ni borne haute ni borne basse. Il ne remplace jamais le tokenizer
  final, les budgets SYSTEM ou une réservation d’inférence.

## Corrections de régression issues de revue

L’API FtsIndex NEX-02 ne normalisait pas les requêtes décomposées avant découpage.
Correction NFC + test « préparation », sans réécriture du commit historique.
La lecture bornée observe maintenant l’annulation entre lectures et à leur
retour ; elle ne prétend pas interrompre une lecture bloquée.
Une exception du parseur ou du stream après CANCELLING ne peut plus transformer
le job en FAILED réessayable : CANCELLED prévaut. Un journal append-only conserve
l’erreur **typée observée** et l’état, sans message d’exception privé. Reprise
explicite et refus tant que le worker vit restent préservés. Aucun reset natif.

## Validation réellement exécutée

**50 JUnit réussis, 0 échec/erreur/ignoré**, Linux Java17 : stockage et imports
réels, PDF synthétique deux pages, FTS, citations, scopes/révocation, falsification,
dates, faute bornée, regroupement, budget JSON complet, injection documentaire et
régressions d’annulation (CountDownLatch puis exception parser/IO, audit conservé).

```sh
gradle -PservicesSource=<snapshot-SERVICES-verifie> test :nexus-cli:installDist :nexus-services-adapter:installDist --no-daemon --console=plain
nexus-cli/build/install/nexus-cli/bin/nexus-cli rag-demo <nouvelle-base-synthetique>
```

La CLI a réellement importé trois sources synthétiques, interrogé FTS et résolu
**trois citations** ; son JSON a été décodé et son carrier vérifié < 4000 unités
UTF-16. Les tests ne sont pas une preuve de génération LLM ni d’essai Android.
Commandes et provenance des XML : `NEX-03-VALIDATION.json`.

Prochaines missions possibles : NEX-05 (snapshot/restauration isolée), NEX-04
(catalogue ZIM, lecteur conditionnel), puis consommateur CORE sur port autorisé,
avec adaptateur Android/stockage à qualifier séparément.
