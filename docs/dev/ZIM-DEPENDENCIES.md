# Dépendances ZIM, licences et qualification Android

État inspecté le 2026-10-24. Aucun composant tiers installé, embarqué ou téléchargé
par le runtime de cette livraison. Les références ci-dessous sont des sources
consultées, pas des binaires qualifiés ou des autorisations de redistribution.

| Composant | Référence examinée | Licence et rôle |
| --- | --- | --- |
| libzim | [README/licence](https://github.com/openzim/libzim#license) | GPL-2.0-or-later ; lecture ZIM, décompression, index de titres intégré |
| python-libzim | [source e6af0b4b5768dc8795eb08fe52f7ef2714945de1](https://github.com/openzim/python-libzim/blob/e6af0b4b5768dc8795eb08fe52f7ef2714945de1/libzim/libzim.pyx) | GPL-3.0-or-later ; backend Linux optionnel effectivement appelé par `LibzimReader` |
| java-libkiwix | [379e3f067340ba27ed12c14af41d3106c2344a3e](https://github.com/kiwix/java-libkiwix/tree/379e3f067340ba27ed12c14af41d3106c2344a3e) | GPL-3.0-or-later ; piste Android, aucune dépendance ajoutée au build NEXUS |

La version inspectée de java-libkiwix déclare artefact `2.7.0`, libkiwix `14.2.1`,
libzim `9.8.2`, minSdk **24**, compile/targetSdk 34, NDK `27.3.13750724` et
`c++_shared`. Son build contient des tâches de téléchargement ; elles n'ont pas
été exécutées. Les quatre familles ABI prévues sont `arm64-v8a`, `armeabi-v7a`,
`x86_64`, `x86`. Cette liste n'est pas une preuve de compatibilité de leurs AAR.

Avant distribution, vérifier les licences des versions exactes, notices, code
source correspondant et obligations de redistribution GPL de l'ensemble lié.
Le découpage en processus n'est pas présenté comme une exemption de licence.
Les licences des articles/médias Wikipédia sont distinctes : conserver auteur,
source et licence du contenu, notamment CC BY-SA selon la notice de l'édition.
Cette livraison ne redistribue aucun contenu encyclopédique. Le nom du fichier
ne prouve ni son origine Wikimedia/Kiwix ni son authenticité.

## Lecteur Linux réellement programmé

Le backend appelle `libzim.reader.Archive(Path('/proc/self/fd/N'))`,
`SuggestionSearcher.suggest(...).get_results(0, limit)`, `get_entry_by_path`,
`get_item`, `size`, `mimetype`, `content`. Aucun faux contenu de secours.
Le descripteur original est ouvert `O_RDONLY|O_NOFOLLOW|O_NONBLOCK`, conservé
pendant l'opération ; `/proc/self/fd` évite de rouvrir un chemin remplacé.
Le fichier doit être régulier, monofichier, local et seekable. Une absence de
binding ou d'API requise produit `UNSUPPORTED`, code CLI 2. Un index de titres
absent est également refusé ; pas de scan de tous les articles.

La version binaire Python/libzim utilisable reste à sélectionner et épingler
dans un environnement déjà équipé, avec ses dépendances natives (dont Xapian
pour la recherche). Aucune version binaire n'a été testée ici. La taille du
blob est contrôlée avant `content` ; les exceptions natives sont refusées.
Le processus Kotlin a une durée maximale de 30 secondes et une sortie de
2 Mio maximum. Le cache d'articles Python est plafonné à 4 Mio/16 entrées,
le cache de clusters demandé à libzim à 8 Mio et celui de dirents à 1024 entrées.
La CLI termine après chaque commande. Ces caches ne plafonnent **pas** le RSS
ni la décompression d'un cluster hostile : l'hôte doit isoler et limiter
mémoire/CPU du processus natif. Pas de qualification corruption hostile native.

## Android : `UNSUPPORTED` dans cette livraison

API24 est une condition déclarée par l'amont, pas une incompatibilité universelle
d'Android. NEXUS ne possède actuellement ni module Android ni AAR/JNI livré,
ni stockage Android substituant SQLite JDBC, ni tests d'instrumentation.
`ZimProcessReader` refuse Android/Dalvik ; la CLI Linux refuse une URI `content:`.
Au niveau `RagContextPort`, le refus est adapté vers l'erreur existante
`KnowledgeError.UNSUPPORTED_FORMAT`, sans changer les contrats NEX-03.

Le raccordement Android devra respecter ce protocole hôte :

1. Lancer `ACTION_OPEN_DOCUMENT` avec `CATEGORY_OPENABLE`, demander uniquement
   `FLAG_GRANT_READ_URI_PERMISSION` et `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`.
   Ne jamais demander WRITE, ni permission globale de stockage.
2. Vérifier les flags réellement accordés puis appeler
   `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`.
   Persister l'URI opaque dans le stockage privé ; ne pas la convertir en chemin.
3. À chaque ouverture et après redémarrage, revérifier `persistedUriPermissions`,
   ouvrir via `ContentResolver.openFileDescriptor(uri, "r")`. Sur révocation,
   `SecurityException`, archive absente ou provider indisponible : erreur typée,
   invalidation du cache, nouvelle sélection explicite si nécessaire.
4. Vérifier `fstat`, longueur réelle et `lseek`/`pread` à un offset supérieur à
   4 Gio. Un provider pipe/cloud, taille inconnue ou non seekable est refusé
   `UNSUPPORTED` ; aucune matérialisation/copie de 55,44 Go comme repli.
5. L'API amont propose `Archive(FileDescriptor, long offset, long size)` et
   `FdInput` avec champs `long`. Vérifier aussi le JNI (`jlong`), `off_t` 64 bits,
   absence de conversion `int`, et les positions dans le ZIM jusqu'à EOF.
   Garder le `ParcelFileDescriptor` vivant jusqu'à la fermeture/dispose native.
6. Qualifier AAR/JNI et toutes les `.so` pour chaque ABI autorisée, API24 réel,
   chargement `libc++_shared`, symboles, taille de page 4/16 Kio, limites mmap
   et durée de vie des FD. Privilégier arm64 ; une ABI 32 bits avec accès fichier
   64 bits n'apporte pas un espace d'adressage 64 bits. Elle reste non qualifiée.

Les essais obligatoires futurs comprennent URI révoquée, réouverture après
arrêt réel du processus/téléphone, provider non seekable, archive de 55,44 Go,
mode avion et contrôle de l'absence de trafic natif. Aucun de ces essais
Android n'est remplacé par les injections Python du présent rapport.
