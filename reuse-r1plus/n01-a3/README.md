# N01-A3 — routage REUSE autorisé, garde successeur

Accord utilisateur explicite du 18 novembre 2026 sur le seul delta de routage
NEX-07 et un garde successeur. Base A2 `2dd4447a`. Aucun autre ancien fichier
ou mode ne peut changer ; tous les scripts de garde restent identiques.

Le garde dérive les octets attendus du workflow depuis sa version Git figée.
Il accepte exclusivement deux substitutions de checkout, deux déclarations de
contexte REUSE et deux branches de sélection de garde/tests. Les routes M50,
LOT3 et PRE-FUSION ainsi que toutes les étapes métier suivantes sont conservées.
La liste des nouveaux fichiers A3 est exacte, sans préfixe permissif.

Contexte PR : base exacte `codex/m53-readonly-preparation`, tête REUSE historique
ou tête A3 nommée, même dépôt. Le checkout doit être le SHA de tête de la PR,
jamais son merge artificiel. Le garde revérifie l'événement GitHub, le dépôt,
les branches et le SHA. Il ne donne aucun droit CORE ou runtime.

Les anciens gardes conservent leur périmètre : le garde A2 REUSE refuse toujours
le workflow modifié à HEAD. Ses 11 tests et son succès sont rejoués au SHA A2
original dans un clone temporaire. Aucune preuve rouge historique n'est réécrite.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 reuse-r1plus/n01-a3/check_routing_scope.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s reuse-r1plus/n01-a3/tests -v
```

La CI dédiée ajoute les régressions JVM REUSE sur vraies bases SQLite et les tests
Python historiques. NEX-07 conserve ses deux jobs JVM et Android-host. Ces tests
ne qualifient ni un téléphone, ni l'autorité CORE, ni une collecte RESEARCH.
