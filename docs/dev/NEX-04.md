# NEX-04 — rapport de livraison partielle

Module NEXUS, dépôt `bubth720/JARVIS-NEXUS`.
Branche `work/nex-04-zim-readonly`, parent direct NEX-05
`3acec55616ff8e3b1666091780fca59261377e18`.
Le SHA final est celui du commit contenant ce rapport, consultable dans Git.
La branche utilise le préfixe `work/` pour ne pas déclencher le workflow existant
`feat/nex-*`, qui installe des outils/dépendances. Aucune fusion ni installation.

## Résultat et limites bloquantes

Catalogue SQLite persistant de références locales et adaptateur Linux utilisant
les API **réelles** de python-libzim. Recherche de titres dans l'index embarqué,
lecture d'un article à la demande, extraction de texte inerte, provenance,
cache LRU borné. Sans backend ou index compatible : `UNSUPPORTED`.
Le fichier `wikipedia_fr_all_maxi_2026-05 (3).zim` annoncé à 55,44 Go n'est pas
accessible dans cet environnement ; il n'a été ni ouvert, déplacé, modifié,
dupliqué ni téléchargé. La lecture native de cette archive **n'est pas validée**.
libzim n'est pas installé et aucun compilateur Kotlin/Gradle n'est disponible.
Le code Kotlin et ses six tests restent non compilés/non exécutés ici.
Android retourne `UNSUPPORTED` : [preuves et travail restant](ZIM-DEPENDENCIES.md).
La mission n'est donc pas déclarée entièrement qualifiée.

## Fichiers et intégration

- `adapters/zim/nexus_zim.py` : catalogue, lecteur libzim, extraction, cache,
  commandes `register/list/remove/search/article/wire`. Aucun réseau ni writer.
- `nexus-core/.../archive/ArchiveReader.kt` : port typé, adaptateur processus
  Linux et `SelectedArchiveContext : RagContextPort`.
- `nexus-core/.../ArchiveContextTest.kt` : six tests de contrat Kotlin à exécuter.
- `tests/zim/test_zim.py` : tests catalogue/erreurs/adaptateur et test réel opt-in.
- README, `.gitignore`, présent rapport, dépendances, validation JSON et log.

Le catalogue est un fichier privé choisi par l'hôte, séparé du ZIM. Il contient
au plus 32 archives et ne crée aucun index du corpus. La recherche retourne au
plus 20 titres ; requêtes <=256 caractères, titres/chemins <=4096 caractères,
article UTF-8 <=1 Mio. Il refuse les chemins relatifs d'entrée `..`, les chemins
absolus, séparateurs Windows et caractères de contrôle. Les clés d'article sont
traitées comme clés internes ; elles ne servent jamais à ouvrir un fichier.
`remove` ne touche qu'au catalogue et invalide le cache. L'hôte contrôle le
répertoire privé, les chemins du worker/interpréteur et sérialise ses appels.

## Réutilisation NEX-03 et citations

Après recherche, l'hôte construit au plus 20 `ArticleSelection(archiveId, path)`
sur choix explicite de l'utilisateur. Il injecte `ZimProcessReader` avec ses
chemins de confiance et un callback d'autorisation qui refuse par défaut, puis
`SelectedArchiveContext(reader, selections, collectionId) { authorized }`.
Il appelle les méthodes existantes `retrieve` et `resolve` de `RagContextPort`.
Le scope est figé et vérifié avant/après lecture ; il ne s'élargit jamais.
Aucun article n'est importé automatiquement dans la base NEX-03.

Les extraits (2048 unités UTF-16 maximum) réutilisent `ContextBudgeter`,
`ContextBundle` et son carrier JSON non fiable, sans nouveau LLM. Les citations
sont résolubles via **cette instance de port** tant que l'article et les droits
restent identiques ; elles ne sont pas des lignes de la base `LocalRagService`.
Les filtres de date d'import sont refusés, car ce port n'effectue pas d'import.
`sourceUri` est `zim://fingerprint/clé-encodée`, `sourceId` le fingerprint,
`sourceSha256` le SHA-256 du **texte extrait**, `rawSha256` celui du blob brut.
Le fingerprint SHA-256 couvre UUID/taille/header/checksum stocké ; ce n'est ni
le SHA-256 de l'archive entière ni une signature de l'éditeur. L'en-tête et le
checksum embarqué restent non vérifiés (`checksumVerified=false`).
Un changement de taille/header/checksum enregistré refuse `SOURCE_CHANGED`.
Les timestamps sont revérifiés durant la lecture et dans la clé de cache.
Un changement malveillant préservant ces métadonnées n'est pas une intégrité
complète : il faut une vérification native entière et une référence éditeur
fiable avant de revendiquer une provenance authentifiée.

## Tests et preuves

Commande exécutée : `python3 -m unittest discover -s tests/zim -v`.
18 réussis, 0 échec, 1 ignoré (archive réelle absente).
Voir [log exact](NEX-04-TESTS.txt) et [validation structurée](NEX-04-VALIDATION.json).
Les doubles `FakeReader` et les objets API injectés testent le contrat et les
limites ; ils ne simulent pas une réussite native dans le runtime livré.

Couverture réelle : persistance et nouveau processus CLI, erreurs de chemins,
corruption d'en-tête, checksum hors limites, archive absente/remplacée,
révocation d'accès injectée même après cache, symlink refusé, backend absent,
URI SAF explicitement refusée, cache octets/entrées, provenance du texte,
retrait sans modification de source et sockets Python interdites par injection.
Cela ne qualifie ni révocation SAF sur téléphone ni absence de trafic natif.

Un fichier **synthétique creux** de 55 440 000 000 octets utilise 4096 octets
physiques : l'enregistrement et le `pread` en fin de fichier exercent les
offsets 64 bits sans copie. Son temps exact est dans le log. Il ne constitue
pas une archive ZIM valide et ne mesure pas latence de recherche/décompression,
RSS, batterie, débit SD ou performance de Wikipédia réelle.

NEX-05 a été vérifiée par parent Git, identité des fichiers existants et rapport
versionné annonçant 69 tests passés. Ces 69 tests n'ont pas été réexécutés.
Une tentative initiale `bash gradlew --offline :nexus-core:test --no-daemon`
a échoué avant build, car le wrapper binaire n'avait pas encore été récupéré
par le connecteur. Le wrapper d'origine a ensuite été restauré et son SHA Git
vérifié. Aucune distribution Gradle ni compilation n'a été lancée après cela,
pour respecter l'interdiction d'installation ; aucun compilateur/cache Gradle
n'est présent. Aucun succès Kotlin ou non-régression JVM n'est revendiqué.

## Prochaine validation nécessaire

Sur un mini-PC déjà équipé d'un python-libzim compatible et avec le ZIM autorisé
à son emplacement actuel, exécuter le test réel en définissant
`NEXUS_ZIM_TEST_ARCHIVE='/chemin/wikipedia_fr_all_maxi_2026-05 (3).zim'`.
Exécuter également `:nexus-core:test` avec le toolchain déjà provisionné.
Mesurer temps froid/chaud, RSS, index, erreurs natives, redémarrage et trafic
réseau à l'aide du système. Ne pas lancer ces qualifications avec installation
implicite. Les blocages Android/SAF sont détaillés dans le document dépendances.
