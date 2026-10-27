# NEX-PF-01 — lot 1, qualification interrompue avant Android

Date : 2026-10-27 UTC. Base mission vérifiée par `git rev-parse HEAD` avant création de la branche : `bdb51f77a5fe105dbff43d43032882fe1474a4ee` ; parent de code `8c778be1b302d10c8dcbf8444f6e6e051477c825`. Branche de travail : `codex/prefusion-20261026-lot1`. PR brouillon vers `mission/prefusion-gates-20261026`, jamais main. Le SHA publié est celui de la PR ; aucun SHA auto-référent n'est inventé.

## Diagnostic et correction

CI exacte de la mission : [40412740972](https://github.com/bubth720/JARVIS-NEXUS/actions/runs/40412740972), FAILURE. Les deux jobs échouent dans `check-nex07.py` sur le seul fichier additionnel `missions/PREFUSION-2026-10-26.md`, avant provisionnement/JVM/Android. Reproduit localement, ce n'est pas un échec de test FTS5.

Le nouveau `tools/check-prefusion.py` fige tous les fichiers de la mission (y compris ses instructions), refuse toute nouvelle source runtime, suppression, symlink et modification non déclarée. Les seuls ajouts permis sont ce garde, ses six tests et ce rapport. Seul le remplacement exact du garde CI et l'ajout des tests sont permis dans le workflow. Les attributs Git de checkout sont respectés, notamment le CRLF de gradlew.bat. Le garde NEX-07 original est exécuté sans changement dans un clone local temporaire à **son SHA historique**, séparément du contrôle de la nouvelle source. Aucun reçu FUSION-02 n'est réécrit.

## Résultats réellement exécutés

| Commande | Plateforme et résultat |
|---|---|
| `python3 tools/check-nex07.py` | Au SHA mission : échec de périmètre décrit ci-dessus ; reste historique et inchangé |
| `python3 tools/check-prefusion.py` | PASS gel de la source mission ; garde NEX-07 PASS uniquement à 8c778be… |
| `python3 -m unittest discover -s tests/prefusion -v` | 6/6 PASS, zéro échec/skip, Python 3.12 Linux |
| `python3 -m unittest discover -s tests/final -v` | 10/10 PASS SQL hôte ; aucune preuve SQLite Android |
| `python3 -m unittest discover -s tests/zim -v` | 18 PASS, 1 SKIP explicite : archive réelle absente |
| `node --test tests/final/test_graph.cjs` | 1 test runner PASS (ne pas réutiliser un ancien total de cas) |
| Gradle JVM/Robolectric/build/lint | NOT_RUN local : distribution/cache/SDK absents, accès proxy shell indisponible ; CI complète requise au nouveau SHA |
| Instrumentation API24/25/26/35, physique, SAF persistant | NOT_RUN dans ce lot |

Les tests ne sont pas désactivés ; les deux lanes CI JVM/Android-hôte restent complètes. Elles ne lancent pas une instrumentation Android, qui reste à réaliser distinctement. Les premiers gardes locaux ont révélé le CRLF de gradlew.bat ; corrigé dans le calcul des octets attendus sans changer le fichier.

## Sources et gates

Relus : `SelectedDocumentImporter.kt`, `AndroidFts5Probe.kt`, les tests du fournisseur synthétique, rapports FUSION-02/NEX-07 et `BACKENDS.md`. Le quota 2 Mio avant ouverture, la relecture bornée SHA/taille/URI et les refus de révocation sont préservés. La sonde en mémoire exige CREATE/MATCH/vocab/BM25 ; Robolectric observe le SQLite hôte. Le refus historique API24/25 n'est pas transformé en RAG opérationnel. Base/WAL/SHM et contrats restent byte-identiques ; aucun nouveau hash avant/après sur appareil n'est revendiqué.

Compatibilité : minSdk24, Kotlin2.0.21/AGP8.7.3, Gradle8.10.2, Java17, AAR sans nouveau JNI. Dépendances/licences restent celles du code gelé ; aucun SQLite externe ajouté ni licence de redistribution nouvellement approuvée. CORE RagContextPort demeure un consommateur inactif.

**BLOCKED** : backend FTS5 Android et vrai SAF persistant ; un backend portable nécessite décision spécifique ABI/licences/taille/budgets/migration. Aucun changement de backend, installation personnelle ou hausse minSdk. Prochaine étape : lire la CI exacte de cette PR, puis qualifier le fournisseur/Android autorisé ; soumettre le choix backend avant toute inclusion native.

Rollback : abandonner cette branche ou revert du lot CI/garde/rapport. Aucun changement runtime/persistant ni données à restaurer. Mission non terminée, aucun GO Android.
