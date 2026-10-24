# NEXUS-FINAL-01 — livraison et qualification partielle

Dépôt `bubth720/JARVIS-NEXUS`, branche `feat/nexus-final-01`, parent direct
`bbccac2ff2cc052f897c7f5015f234507e047b95` (NEX-04), qui conserve NEX-05
`3acec55616ff8e3b1666091780fca59261377e18`. Le SHA final est celui du commit
contenant ce rapport. Aucun changement des autres dépôts, aucune fusion,
installation ou donnée personnelle transférée.

Le noyau documentaire, les ports cités, les sauvegardes et le lecteur ZIM sont
conservés. Les extensions Kotlin sont écrites mais **non compilées ici** :
Gradle/Kotlin et le toolchain Java 17 ne sont pas provisionnés. L'interdiction
d'installation conservée de la session empêche de les ajouter. Les vérifications
effectives portent sur le SQL de production, le visualiseur JavaScript et les
tests Python ZIM. Elles ne remplacent pas les tests JVM.

## État des lots

| Lot | Code livré | Qualification / limites |
| --- | --- | --- |
| A — bibliothèque | Métadonnées, catégories/tags, imports structurés et CLI ; SERVICES reste le parseur PDF/TXT/MD | Nouveaux extracteurs et raccordement JVM non exécutés ; huit tests JUnit ajoutés |
| B — incrémental | Hash inchangé + index READY : saut du parsing/FTS ; `forceReindex` volontaire ; emplacements, quotas et reprise persistante | Schéma/transactions SQLite testés ; reprise/cancellation JVM non requalifiées |
| C — recherche | Mot-clé/phrase, type/date/source/collection, BM25, citations NEX-03, signaux d'insuffisance | SQL FTS réel testé ; reranking sémantique optionnel sans modèle fourni ; pas de rappel vectoriel complet |
| D — ZIM | Catalogue et backend Linux NEX-04 conservés, sans copie/réindexation globale | 18 tests de contrat passent, test réel ignoré ; Android `UNSUPPORTED` ; archive réelle et libzim absents |
| E — graphe | Nœuds typés, liens cités explicites/suggérés, édition/rejet versionnés, suggestions de mentions littérales | Contraintes et pagination SQLite testées ; API Kotlin non exécutée |
| F — sphère | Canvas 3D projeté, catégories, tactile/clavier, zoom/déplacement, fiches, profondeur/densité, pages bornées | Chromium desktop et tactile émulé passent ; aucun téléphone physique qualifié |
| G — contrôle | Sauvegardes NEX-05 étendues, aperçu graphe, restauration isolée v3→v4, suppression logique confirmée | Isolation SQLite testée ; régression AES/JVM et migration de snapshot chiffré non exécutées |

## Architecture et fichiers

- `store/NexusDatabase.kt` : migration atomique **v4** ; aucune reconstruction
  FTS pendant la migration. Tables nouvelles : `document_metadata`,
  `import_metadata`, `fragment_location`, `graph_node`, `graph_edge`.
- `importing/StructuredParser.kt` : DOCX, CSV, TSV, XML et ZIP textuel borné.
  `ServicesParser.registry()` compose les nouveaux formats avec l'extracteur
  SERVICES existant ; aucun parseur PDF/TXT/MD copié depuis SERVICES.
- `ImportCoordinator` : métadonnées de job persistées et adoption atomique avec
  l'index, gestion du stockage libre et du quota, chemin incrémental sans parsing.
- `library/LibraryService.kt` : catalogue filtré par autorité hôte, résultats
  localisés et signaux documentaires ; `LIBRARY_API_VERSION = 1`.
- `rag/` : types de recherche étendus par des valeurs par défaut compatibles ;
  résolution et carrier non fiable NEX-03 réutilisés. Contrats UNIFIED inchangés.
- `graph/GraphService.kt`, `MentionSuggestions.kt` : stockage et API de graphe
  `jarvis.nexus.graph/1`, suggestions locales déterministes sans LLM.
- `backup/` : snapshots v4 et lecture des snapshots v3 compatibles ; la copie
  isolée est vérifiée avant migration puis revérifiée après migration.
- `nexus-cli/.../LibraryCli.kt` : consultation, import structuré, export de page
  de graphe et démonstration synthétique intégrée (non exécutée ici).
- `viewer/` : visualiseur local autonome, sans paquet externe ; voir son README.
- `tests/final/`, `FinalLibraryTest.kt`, `tools/validate-final.py` : preuves,
  tests exécutables et mesures explicitement délimitées.

## Bibliothèque, extraction et limites

Le catalogue conserve titre, nom, extension qualifiant le type, taille, date
fichier lorsqu'elle est fournie, date d'import initial, provenance, catégorie,
32 tags maximum, empreinte du source et état d'index. Les documents historiques
sans nouvelles métadonnées restent consultables et leur type vaut inconnu.
Un filtre de type les exclut tant qu'ils n'ont pas été enrichis par un import.

L'hôte choisit les flux et garde leur propriété. Les CLI ouvrent uniquement le
chemin explicitement sélectionné, sans suivre un symlink final ; l'empreinte
vérifie de nouveau le contenu entre enqueue et process. Aucun watcher ne scanne
le disque. Les originaux ne sont jamais modifiés. Les URI distinctes ayant le
même contenu conservent leur provenance ; le regroupement NEX-03 évite de
multiplier les extraits identiques. Une même URI inchangée ne crée pas de nouvelle
révision. Une modification exige la version attendue afin de refuser les conflits.

Limites par défaut : source ≤2 Mio, texte extrait ≤250 000 unités UTF-16,
100 pages maximum, fragments de 2048 unités avec coupures sur fins de paragraphes
et sans couper une paire de surrogates ; huit jobs en attente ; base ≤64 Mio,
réserve disque ≥16 Mio. Les entrées/fragments et FTS sont bornés ; les quotas
ne constituent pas un plafond universel de RSS d'un parseur natif PDF.
La réserve d'enqueue est conservatrice (8× la taille source) et peut refuser
un import avant saturation. Les journaux temporaires SQLite réclament aussi
de l'espace. Les limites de snapshot NEX-05 restent distinctes (16 Mio par défaut,
configurables jusqu'à 64 Mio) et peuvent refuser un export devenu trop grand.

DOCX lit seulement `word/document.xml` et conserve les paragraphes. Aucun rendu
de mise en page ni numéro de page physique n'est inventé ; les offsets UTF-16
dans cette extraction et l'emplacement du composant XML sont conservés.
CSV/TSV conservent le texte d'origine, les guillemets et les sauts de ligne,
avec un contrôle de syntaxe (10 000 lignes / 256 colonnes au maximum).
XML refuse DTD et entités externes, sans XInclude ou chargement réseau.
DOCX/ZIP : ≤128 entrées, ≤2 Mio par membre, ≤4 Mio décompressés au total,
ratio >100 refusé au-delà de 4096 octets, noms dangereux/dupliqués refusés.
Un ZIP documentaire accepte au plus 32 fichiers TXT/MD/CSV/TSV et rend un batch
unique avec ses en-têtes de membres conservés ; aucun fichier n'est extrait
sur disque et les archives imbriquées sont refusées. TXT/MD exigent SERVICES.
Il ne s'agit pas d'un importeur universel de toutes archives ou feuilles Excel.
PDF scanné/OCR, DOC binaire, XLS/XLSX et formats non qualifiés restent refusés.

Les jobs persistants se reprennent avec le source choisi et vérifié de nouveau.
Une extraction interrompue est reparsée ; il n'existe pas de reprise à un offset
interne PDF/DOCX. `recoverInterrupted()` distingue arrêt et annulation. Le chemin
inchangé fait confiance au même extracteur déjà utilisé ; après changement de
version/configuration de parseur, l'hôte doit demander `forceReindex=true` sur
un nouveau job. Aucun cache d'embeddings n'est créé automatiquement.

## Recherche et preuves

`KnowledgeQuery` ajoute `mediaTypes` (extensions, pas MIME) et `phrase`. La phrase
est encodée comme une phrase FTS fermée ; le texte de l'utilisateur ne devient
jamais une expression SQL/FTS brute. Les scopes sont intersectés avec l'autorité
hôte et revérifiés ; les défauts refusent l'accès. Dates = dates d'import initial,
pas dates de modification ni dates de dernière réindexation.

`LibraryService.search` retourne les `SearchHit` et `Citation` existants avec
un emplacement complémentaire. `LocalRagService` reste le port du contexte
borné et de la résolution. Les liens vers l'original sont des URI de provenance,
jamais des ouvertures automatiques. Le visualiseur n'ouvre que les URI locales
file/content après un geste utilisateur et affiche le texte par `textContent`.

`insufficient` est un signal conservateur (zéro preuve ou moins de deux empreintes
sources distinctes), pas une mesure de vérité ou d'exhaustivité. Le signal de
contradiction détecte seulement un lien `CONTRADICTS` explicitement enregistré,
non rejeté et concernant les preuves trouvées. Aucune contradiction sémantique
n'est inférée automatiquement. Les synthèses d'un futur LLM devront séparer
preuves retrouvées, interprétations et informations manquantes ; NEXUS ne génère
pas de synthèse et ne développe pas un second LLM.

`EmbeddingPort` est injecté et désactivé par défaut. `SemanticReranker` rerange
au plus 100 résultats lexicaux déjà autorisés par cosinus, avec vérification des
dimensions/valeurs finies. Sans modèle : `UNSUPPORTED_FORMAT`. Aucun modèle,
runtime d'inférence ou index vectoriel n'est livré/qualifié. La recherche purement
sémantique à rappel indépendant de FTS demeure partielle.

## Graphe et visualisation

Nœuds document/concept/personne/entité/catégorie/événement/source. Chaque relation
stocke son origine explicite/suggérée et une preuve de fragment résoluble.
Les contraintes refusent les collections croisées. Les liens rejetés et leur
version sont conservés, avec journal de mutation. L'édition attend une version
et ne réactive pas silencieusement un rejet. Une preuve devenue ancienne après
révision ou suppression documentaire masque le lien dans la vue courante.
Les suggestions `literal-mention/1` ne font que repérer au plus 32 termes fournis
par l'hôte dans 100 preuves déjà autorisées (200 suggestions au maximum).
Elles ne prouvent ni identité d'entité ni causalité ; l'hôte décide de leur adoption.

Le graphe exige une autorité hôte sur la collection entière ; les scopes limités
à certaines sources sont refusés pour éviter de révéler des étiquettes d'autres
sources. Quotas par collection : 100 000 nœuds / 200 000 relations. Pages keyset
de 250 nœuds et 1000 relations maximum, défaut 200/20. Le visualiseur charge
seulement des pages JSON ≤1 Mio, au plus 500 nœuds / 2000 relations cumulés,
avec refus atomique au-delà. La profondeur 1–3 est locale à la fenêtre chargée.
Pas de chargement complet automatique des milliers de nœuds, ni d'adaptateur
HTTP documentaire. Les améliorations WebGL/traversée serveur restent possibles
en conservant ce contrat borné. Ce n'est pas une synchronisation paginée avec
snapshot cohérent pendant des éditions concurrentes : recharger la vue si nécessaire.

## Sauvegarde et contrôle

AES-GCM, `CryptoPort` contrôlé par l'hôte, confirmation et restauration isolée
NEX-05 restent en place. Les nouvelles tables font partie de la même base et
de la sauvegarde. L'aperçu compte les nœuds/liens et signale une différence du
graphe ; les conflits documentaires incluent métadonnées et emplacements.
Une archive v3 authentifiée est vérifiée dans son schéma initial, puis seule sa
copie de récupération migre en v4. Le manifeste décrit le fichier source v3,
pas le hash du fichier migré ; `restoredSchemaVersion` expose la version restaurée.
Aucune adoption/écrasement automatique de la base courante. Ne pas rouvrir une
base v4 avec un ancien binaire NEX-05 ; les anciennes sauvegardes restent intactes.

`previewRemoval` donne compte et empreinte des IDs/versions de la collection.
`removeCollection` exige cette prévisualisation inchangée, confirmation et
permissions WRITE+PURGE ; elle crée des révisions supprimées logiquement,
retire FTS, et conserve fichiers, sources et historique. Aucun effacement physique
de collection n'est exposé par la nouvelle CLI. Les erreurs d'import et les
mutations réutilisent les journaux existants ; les refus de lecture ne créent
pas d'écritures non autorisées.

## Vérifications et mesures exécutées

Commande : `python3 tools/validate-final.py --browser --benchmark`.
Les outils étaient déjà présents ; le script n'installe rien.

- SQLite réel, DDL extrait des migrations Kotlin : **10 tests passés**.
  Migration v3→v4 conservant sources/FTS, phrases/scopes/type, offsets/hash,
  contraintes intercollections, pagination, rollback/restart et copie isolée.
- Modèle JavaScript du visualiseur : **7 tests passés** (plafonds, refus atomique,
  filtres, profondeur, positions, preuve requise, texte HTML inerte).
- Suite ZIM existante : **18 passés, 1 ignoré** faute d'archive réelle.
- Chromium `154.0.8037.57` : desktop 1440×1000 et tactile émulé 390×844,
  sélection, recherche, rotation, pincement via CDP, preuve HTML inerte,
  URI locale et refus d'une page >1 Mio ; aucune erreur JavaScript ou requête
  externe observée. Le transport testé est un serveur statique loopback explicite.
  `file://` est bloqué par la politique du navigateur administré et n'est pas qualifié.

Mesure de stockage : **5 000 documents synthétiques / 5 000 nœuds / 24 985 liens**,
base **11 268 096 octets**, peuplement SQL environ **0,83 s**, requête FTS p50
**7,3 ms**, p95 **8,1 ms**, RSS processus Python **16,1 Mio** observés.
Ce sont des insertions SQL et requêtes réelles sur le schéma de production,
**pas un benchmark de l'import Kotlin ou de l'extraction**. `tracemalloc` mesure
seulement les allocations Python, pas toute la mémoire native SQLite.

Rendu de stress : 500 nœuds / 2000 liens synthétiques, appels Canvas synchrones
p50 environ 1,6 ms desktop, heap JS environ 27 Mio. Le script pousse aussi ce
stress dans le viewport mobile au-delà de sa densité normale, volontairement.
Il ne mesure ni FPS du compositeur, ni consommation, ni mémoire Android physique.
Les chiffres exacts et conditions sont dans les journaux JSON/textes, pas des SLA.

Preuves : `NEXUS-FINAL-01-VALIDATION.json`, journaux `*-SQLITE.txt`, `*-GRAPH.txt`,
`*-ZIM.txt`, `*-BROWSER.txt`, `*-BENCHMARK.txt`. Une capture synthétique locale
est produite par le test navigateur dans `/tmp/nexus-final-viewer.png`.

## Qualification restant à exécuter

Huit nouveaux tests `FinalLibraryTest`, les suites JVM historiques et la démo
`atlas-demo` n'ont pas été exécutés ; aucun succès de compilation n'est revendiqué.
Ils doivent passer avant de déclarer la bibliothèque JVM livrable en production.
Réexécuter aussi l'adaptateur SERVICES et les snapshots chiffrés avec un toolchain
déjà provisionné, puis mesurer les imports réels et la mémoire du processus JVM.

L'archive Wikipédia autorisée n'est pas montée et libzim est absent. Aucun
résultat Wikipédia réel ni benchmark ZIM n'est revendiqué à partir du fichier
creux synthétique NEX-04. Android conserve `UNSUPPORTED` ; API24, ABI, SAF
persistant/révocation/redémarrage et accès aléatoire 64 bits restent à qualifier
séparément, conformément à `ZIM-DEPENDENCIES.md`. Aucun mini-PC matériel distinct
ou téléphone n'a été connecté. L'OCR et les adaptateurs externes restent désactivés.
