# NEX-02 — Import local et FTS5 incrémental

Module NEXUS ; branche `feat/nex-02-chaine-d-import-local-extracti`.
Base code directe : `c25d401dff6865657b75d320361ae81811363fa8` (NEX-01 livrée).
Plan préservé : `e4b2018383a35106cd326848fab597ef07a50157`.

## Livraison

- Schéma local v2 : jobs, provenance des pages et index FTS5 ; migration atomique
  depuis v1, sans modification des schémas UNIFIED ou bases CORE/SERVICES.
- `ImportCoordinator`, `ImportJob`, `ParserRegistry`, `ParserPort`, annulation
  coopérative, queue bornée à huit jobs ; source plafonnée à 2 MiB, 100 pages,
  250 000 caractères extraits. Aucun scan de répertoires ou import automatique.
- État QUEUED/PROCESSING/CANCELLING/CANCELLED/INTERRUPTED/FAILED/COMPLETED persisté.
  Reprise explicite par `recoverInterrupted` puis `process` avec les mêmes octets
  dont SHA-256 et taille sont revérifiés. Une annulation demandée avant la mort
  du worker reste une annulation, jamais une reprise implicite.
- Un verrou OS sidecar garde un seul parseur participant par base. Tant que le
  parseur travaille, une récupération est refusée ; CANCELLING n’affirme pas sa
  fin. Il s’agit d’un protocole de worker documentaire, **pas d’une attestation
  JNI/LLM ni d’une preuve absolue d’état mémoire Android**.
- Document, révision, fragments, pages, index et achèvement du job sont validés
  dans la même transaction. Échec/cancellation => rollback. L’ancien document
  reste intact, le job conserve son état/erreur. Aucun journal n’est effacé.
- Les fragments gardent le texte brut extrait ; offsets UTF-16 par page,
  page PDF réelle (1-based), null pour texte non paginé. SHA des pages conservé.
  Seul le texte indexé est normalisé NFC : aucun offset brut n’est inventé.
- Suppressions ou versions non indexées ne reviennent jamais comme résultats
  valides ; une restauration historique reste PENDING jusqu’à réindexation.

## Réutilisation du parseur propriétaire

Capacité `documents.extract.readonly.paged` SERVICES réellement livrée au commit
`fd41d75015f77e947d22d6a60e619adc00bd70b7` (le final documentaire ultérieur ne
change pas cette API). `DocumentTextExtractor` et `PdfBoxText.extractPages` sont
consommés par composite Gradle depuis un snapshot indépendant, pas copiés dans
une nouvelle implémentation PDF NEXUS. Aucun stockage SERVICES n’est ouvert.

Archive SHA-256 : `20efa456e6ee0f58363108aeec7ab77bc86911ea43c16743e16e3794dbb82fc7`.
Arbre source SHA-256 : `f0f39dde88c4a882925faaefcba2e92c9c8e0c39369261e294b221ab66b143f1`.
`tools/prepare-services.py` refuse altération, liens ou traversée d’archive.
Dépendances héritées : PDFBox 2.0.33 et Gson 2.11.0 Apache-2.0 ; SQLite JDBC déjà
épinglé par NEX-01. Le réseau sert seulement à préparer le build, jamais l’import.

## Limites et risques

- Android/SAF non implémenté ; JVM desktop seulement, sans promesse API/ABI Android.
- PDFBox est le parseur SERVICES existant en processus JVM. Les quotas d’entrée,
  pages et sortie ne prouvent pas une borne absolue de toutes ses allocations
  internes sur un PDF hostile. Pas de qualification d’épuisement mémoire Android.
- L’annulation de PDF est observée aux frontières coopératives : le worker garde
  son verrou jusqu’au retour effectif du parseur. Aucun arrêt forcé n’est promis.
- La reprise après interruption est testée par états/erreurs injectés et SQLite
  réel ; aucune coupure électrique ou expérience sur appareil n’est revendiquée.
- NEX-03/RAG/citations sémantiques, backups et ZIM restent des missions distinctes.
- Aucun modèle, APK, donnée personnelle, raccordement inter-APK, permission
  Android, changement JNI ou seuil matériel ajouté.

## Vérifications

Les commandes et résultats effectifs sont enregistrés après exécution dans
`NEX-02-VALIDATION.json` ; un résultat JVM ne qualifie pas un appareil Android.

Validation finale : **34 tests JUnit, 0 échec, 0 erreur, 0 ignoré** (31 noyau,
3 adaptateur). Migration d’une fixture SQL v1 historique non réécrite, rollback
réel d’index, annulation déclenchée au milieu de la transaction SQLite,
CANCELLING et verrou de worker, reprise, quotas, source modifiée, refus
non-autorisé, copie défensive et réindexation idempotente sont exercés.
Le test adaptateur extrait réellement TXT, Markdown et PDF synthétique **deux
pages**, vérifie les résultats FTS et leur page/offset puis rouvre la base.
Une erreur initiale de parenthèse Kotlin a été corrigée avant les builds réussis.

Commande du reçu : `gradle -PservicesSource=<snapshot-SERVICES-verifie> test
:nexus-services-adapter:installDist :nexus-cli:installDist --no-daemon
--console=plain` via les outils cloud existants. Le dernier passage est
incrémental : les trois tests adaptateur inchangés réutilisent leur XML de
réussite du passage complet précédent ; ce n’est pas un nouveau résultat inventé.
Deux processus CLI distincts ont réellement importé un Markdown synthétique puis
retrouvé « quantum » ; le SHA du fichier original était inchangé après essai.
