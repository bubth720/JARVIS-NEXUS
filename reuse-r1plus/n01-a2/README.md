# N01-A2 — diagnostic du contexte CI, protections inchangées

Base REUSE R1plus : b7d9d884ddd034e3580a2268fce69ca43030e8c5.

Le refus du run 42523863724 est reproduit : le workflow NEX-07 choisit
`tools/check-prefusion.py`, dont les ajouts autorisés ne comprennent ni M53 ni
REUSE. Ce garde est correct dans son propre contexte ; il refuse ces ajouts.
`reuse-r1plus/check.py` passe et préserve les 329 fichiers et modes M53.

Ajouts autorisés : régressions hors ligne sous `reuse-r1plus/n01-a2/`, et
déclenchement manuel/sur cette branche du workflow REUSE déjà autorisé par son
garde. Les gardes, contrats, workflows M53 et sources métier restent identiques.
Les mutations négatives sont effectuées dans des clones locaux temporaires.

Le routage de `.github/workflows/nex-07.yml` appartient aux fichiers gelés M53.
Un test prouve que son édition est refusée par le garde REUSE. Aucun correctif
n'y est appliqué et aucun ancien résultat rouge n'est remplacé. Le patch de
routage proposé et la demande d'accord sont préparés dans UNIFIED N01-A2.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 reuse-r1plus/check.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s reuse-r1plus/n01-a2/tests -v
```

Ces tests ne qualifient ni Android, ni réseau RESEARCH, ni CORE, ni export ou
migration. Ils ne suppriment aucun contrôle historique et n'accordent aucun GO.
