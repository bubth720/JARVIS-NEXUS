# NEX-PF-02 — lot 3

Module NEXUS; branche `codex/prefusion-lot3-nexus`; base mission
`b2b899c8cce73ee0763bf6896fb9ceffe62c2bc6`, descendante de
`201f0d2b03209de104157595a557143fe87eb94e` (PR historique #8).
Date de reprise : 28 octobre 2026 UTC. Le SHA final est fourni par `git rev-parse HEAD`
et par la branche publiée, sans auto-référence inventée dans le commit.

## Incrément exécutable

- `labs/prefusion_lot3/matrix.py` : lecteur standard Python borné et versionné,
  JSON fermé, refus des doublons/valeurs malformées/activation/affirmation SUPPORTED
  sans toutes les primitives. Sépare hôte, Robolectric, instrumentation et physique;
  une observation hôte ne qualifie jamais Android. Les reçus restent déclaratifs,
  pas une attestation cryptographique.
- `labs/android-lab` : cible de test min24, explicitement incluse seulement avec
  `-PwithAndroid=true -PwithPrefusionLab=true`. Activity OFF par défaut, picker
  ACTION_OPEN_DOCUMENT, URI synthétique confirmée uniquement, aucune persistance.
- `labs/saf-provider` : APK testOnly distinct, fournisseur DocumentsProvider protégé
  MANAGE_DOCUMENTS, documents fixes synthétiques, lecture seule, cas quota 2 MiB
  et révocation. Aucun provider ni permission ajouté au runtime NEXUS.
- Cinq tests Android : OFF initial, primitives FTS5/MATCH/vocab/BM25 et migration
  temporaire/rollback/réouverture, picker réel + digest/original/PFD clos, quota,
  révocation et refus du second accès. Ce corpus de cinq cas utilise aussi la classe publique
  de production SelectedDocumentSourceVerifier : digest valide/invalide, refus de
  quota avant ouverture, et DENIED après révocation; aucun stockage SQLite requis
  pour ces contrôles. Une plateforme sans FTS5 est explicitement
  journalisée UNSUPPORTED; un test de refus réussi ne prouve pas le support FTS5.
- CI distincte : régressions JVM/Robolectric/build/Lint et campagnes émulateur
  API24/25/26/35 (9 cas existants + 5 nouveaux par API), chaque résultat lié au SHA exécuté; pas d'installation personnelle.

## Périmètre et preuves

`tools/check-prefusion-lot3.py` fige tous les fichiers de la base mission, sauf
l'ajout exact du bloc Gradle opt-in des deux cibles synthétiques et le routage CI borné décrit ci-dessous. Ajouts fermés.
Le garde historique n'est ni désactivé ni élargi : exécuté dans clone isolé à sa
référence historique, puis garde lot3 sur les sources actuelles. Dans nex-07.yml,
les deux étapes de garde sont aiguillées vers le garde lot3 uniquement pour un
événement pull_request, base exacte mission/prefusion-lot3-20261027 et branche
source préfixée codex/prefusion-lot3-. Les autres cas exécutent strictement
l'ancien garde. Les deux lanes et toutes les commandes de tests restent inchangées.
Trois tests vérifient le remplacement exact des seules deux étapes, le refus de
modification des permissions/tests et les trois conditions de routage. Le nouveau
garde exige les octets exacts de ce changement; aucun ancien reçu n'est réécrit.

Les preuves API24/25 de `docs/fusion-02/evidence/` et le rapport backend historique
ont été relus et conservés. Les résultats historiques ne sont pas imputés au SHA
lot3. Les tests source/digest/version/citations/page/offset de JVM et Robolectric
restent ceux livrés; le picker de laboratoire ne revendique pas une intégration
complète ImportCoordinator/RAG ni la qualification physique de ces garanties.

## Résultats locaux

| Vérification | Résultat |
|---|---|
| Python lot3 | 15 PASS : 12 matrice +3 routage CI, 0 échec, 0 saut |
| Python garde historique tests | 6 PASS, 0 échec, 0 saut |
| Python SQL hôte | 10 PASS, 0 échec, 0 saut |
| Python ZIM | 18 PASS, 1 SKIP explicite archive réelle absente |
| Node graph | 7 PASS, 0 échec, 0 saut |
| Garde lot3 + gardes historiques isolés | PASS |
| JVM stockage/adaptateur | Réexécution complète 28 octobre (`--rerun-tasks`) : 92 + 3 PASS, 0 échec/erreur/saut; BUILD SUCCESSFUL en 6m7s |
| Robolectric | 71 PASS, 0 échec/erreur/saut; hôte uniquement |
| AAR/API24 | AAR assemblée; frontière 131 classes CORE +23 Android, aucune permission/JNI; compilation API24 de 1 Java +23 Kotlin PASS |
| APKs/Lint | BUILD SUCCESSFUL en 11m38s : AAR, APK laboratoire, APK provider, Lint des 3 modules; 0 erreur/fatale. 4 warnings de laboratoire (2 SetTextI18n, DataExtractionRules, MissingApplicationIcon) conservés explicitement |
| Émulateurs API24/25/26/35 | NOT_RUN local : `/dev/kvm` absent; workflow préparé, pas de résultat attribué par anticipation |
| Appareil physique / PSS / chauffe / batterie / ARM64 | NOT_RUN, aucune installation autorisée ni faite |

Correction réellement faite : première configuration Gradle refusait une version
`com.android.application` déjà présente dans le classloader AGP. Le module test
réutilise désormais ce plugin sans demander une seconde version. Aucune dépendance
native ni source historique n'a changé.

Commandes reproductibles :

```sh
python3 tools/check-prefusion-lot3.py
python3 -m unittest discover -s tests/prefusion_lot3 -v
python3 -m unittest discover -s tests/prefusion -v
python3 -m unittest discover -s tests/final -v
python3 -m unittest discover -s tests/zim -v
node --test tests/final/test_graph.cjs
python3 tools/prepare-services.py /tmp/nexus-services-source
bash gradlew -PservicesSource=/tmp/nexus-services-source test :nexus-cli:installDist :nexus-services-adapter:installDist --no-daemon --max-workers=2
bash gradlew -PwithAndroid=true -PwithPrefusionLab=true :nexus-android-store:testDebugUnitTest :nexus-android-store:assembleDebug :nexus-android-store:assembleDebugAndroidTest :nexus-android-store:lintDebug :nexus-prefusion-lab:assembleDebugAndroidTest :nexus-prefusion-lab:lintDebug :nexus-prefusion-provider:assembleDebug :nexus-prefusion-provider:lintDebug --no-daemon --max-workers=2
python3 tools/check-android-boundary.py
```

L'environnement local utilise le JDK17/Gradle8.10.2/SDK24+35 vérifiés partagés,
via `/workspace/.lot3-tools/env.sh nexus`; chemins de cache non publiés comme preuves.
Les tâches Gradle de configuration SKIPPED ne sont pas des tests sautés.
Complément final : APK instrumentation existante (9 cas) et APK laboratoire
(5 cas, rollback SQL amélioré) recompilées : BUILD SUCCESSFUL en 1m29s,
87 tâches dont 28 exécutées. Compiler ces APK ne signifie pas exécuter leurs tests.
Démonstration CLI réelle : un fichier JSON `[]` passé à
`python3 labs/prefusion_lot3/matrix.py /tmp/nexus-lot3-observations.json`
renvoie quatre API NOT_RUN, `runtime_gate=BLOCKED`, `production_enabled=false`;
aucune observation n'est fabriquée.

## Gates, retour arrière et suite

RAG reste UNSUPPORTED/BLOCKED; aucun driver choisi, aucun grant CORE créé,
aucun IPC activé, aucune migration. Décision backend : voir `BACKEND-DECISION.md`.
La CI doit encore fournir les observations exactes sur plateformes si elle n'est
pas accessible. Un appareil personnel reste soumis à accord distinct.

Rollback : ne pas fournir la propriété opt-in, ou abandonner cette branche; aucune
base originale/WAL/SHM/document utilisateur ni profil n'a été touché. Prochaine
action : examiner les artefacts émulateur au SHA publié, puis décider si une étude
native AndroidX isolée est autorisée. Aucun raccordement RAG réel avant qualification.

PR demandée : brouillon vers `mission/prefusion-lot3-20261027`, jamais main.
L'API GitHub est refusée par le proxy (Forbidden, diagnostic central); pas de
contournement ou nouvelle tentative de création API. Utiliser le lien de comparaison
fourni avec le bilan pour créer manuellement le brouillon.

## Première observation GitHub, non attribuée à un autre SHA

Au commit b5f89c0db8fa5ad96ed43697b5ffdb4d33ca757a :
[run 40666775515](https://github.com/bubth720/JARVIS-NEXUS/actions/runs/40666775515),
FAILURE (observé 28 octobre 2026, 09:52:57 UTC; durée 6m20s).
Job host SUCCESS (4m27s); quatre jobs Android API24/25/26/35 FAILURE avec
annotation publique identique : `The process '/usr/bin/sh' failed with exit code 1`.
Cinq artefacts annoncés. La vue publique exige une connexion pour les logs :
la cause exacte et les comptes instrumentés de ces jobs sont **UNKNOWN**, pas
présumés être cinq tests en échec ni un problème de backend particulier.

Correctif diagnostique : le script émulateur conserve le premier code d'erreur,
annote la phase fixe (build/install/instrumentation), résume uniquement les vrais
comptes JUnit disponibles et refuse un succès de commande sans les 9+5 cas
attendus. Aucun test ni assertion n'est supprimé; aucun contenu documentaire ou
log système général n'est publié. Les contrôles SourceVerifier publics ont été
ajoutés aux cinq cas et leur APK recompilée : BUILD SUCCESSFUL 26s, 4 tâches
exécutées (62 tâches au total). Cela reste une compilation, pas une exécution Android.
Le nouveau SHA et son run doivent être vérifiés séparément; aucun résultat du run
ci-dessus ne lui est transféré.
