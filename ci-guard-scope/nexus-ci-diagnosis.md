# NEXUS-00 — diagnostic de portée CI « NEX-07 isolated quota preflight »

Base : `codex/reuse-first-r1plus-20261108` @ `b7d9d884ddd034e3580a2268fce69ca43030e8c5` (PR #14).

## Avant

Run [42523863724](https://github.com/bubth720/JARVIS-NEXUS/actions/runs/42523863724) : **failure** (jobs `jvm` et `android-host`) à l'étape « Check the exact reviewed source scope ».
`tools/check-prefusion.py` → `ValueError: Undeclared files: …` (39 fichiers : LOT3, M50-S1, M53, REUSE R1+).
Le workflow dédié de la branche, run [42523712059](https://github.com/bubth720/JARVIS-NEXUS/actions/runs/42523712059), est **success**.

## Cause (reproduite localement le 11/11/2026)

`nex-07.yml` se déclenche sur **toute** `pull_request` ; sa voie par défaut exécute la garde PRE-FUSION lot1, qui fige l'arbre lot1 (`bdb51f7`) et refuse tout successeur. Le code de quota n'est jamais atteint : l'échec précède Gradle.

| Garde historique (inchangée) | Commit de contexte | Résultat local |
|---|---|---|
| `tools/check-prefusion.py` | `201f0d2` | PASS |
| `tools/check-prefusion.py` | `b2b899c` | PASS |
| `tools/check-prefusion-lot3.py` | `c5dee7a` | PASS |
| `tools/check-m50-s1.py` | `be70faa` | PASS |
| `m53review/check.py` | `eae61fb` | PASS |
| `reuse-r1plus/check.py` | `b7d9d88` | PASS |
| `tools/check-prefusion.py` sur HEAD `b7d9d88` | — | FAIL (39 fichiers non déclarés) : reproduit |

Le quota NEX-07 (`SelectedDocumentSourceVerifier`, refus > 2 Mio avant ouverture du PFD, commits `cf9528a`/`000022a`) est inchangé.

## Correctif

- `tools/check-nexus00-scope.py` (successeur) : chaque garde historique doit être identique octet par octet à sa copie de référence et est exécutée **telle quelle** dans un worktree détaché de son commit ; puis tous les blobs/modes de `b7d9d88` sont figés sur HEAD, seules la voie par défaut de `nex-07.yml` (2 lignes, une par job) et 4 ajouts déclarés sont admis.
- `nex-07.yml` : voie par défaut → successeur + ses tests ; voies M50 et LOT3, `tests/prefusion`, Gradle JVM/Android, API24 et frontière Android **inchangées et bloquantes**.
- `tests/nexus00/test_scope.py` : 9 cas dont fichier non déclaré, fichier historique muté, suppression, dérive du workflow, ajout exécutable → tous refusés.
- `QuotaPreflightBoundaryTest.kt` (Robolectric, ajout) : flux borné sous/à/au-delà de la limite ; vérificateur : normal OK, 2 Mio + 1 refusé **sans descripteur**, exactement 2 Mio transmis à la vérification réelle.

## Après

Voir la section « Runs CI » ajoutée après exécution.
