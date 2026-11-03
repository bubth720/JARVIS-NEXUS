# NEXUS A1 — CI M50-S1, source gelée

Branche : codex/m50-s1-nexus-ci-20261029.
Base mission : c02a00e8d6829de48a212cc03ac392e7ec73c82c,
tree 7648ce620381158a875059db9b028c9b71740052.
Baseline lot3 : c5dee7ab0344a23bfd80b13cd52d09e8b26241dd.

## Diagnostic et périmètre

Run historique [40852579751](https://github.com/bubth720/JARVIS-NEXUS/actions/runs/40852579751),
job 123375472874 : LOT3_REVIEW=false, échec du garde avant les tests produit.
Ce run ne constitue pas une exécution des suites JVM/Android-hôte.

Le nouveau garde vérifie ascendance, octets et modes de tous les fichiers lot3.
La mission publiée reste figée au SHA mission. Les seuls ajouts permis sont
le garde, ses tests, ce rapport et la mission. Dans nex-07, seuls les deux blocs
de routage changent : événement pull_request, base mission M50 exacte, head exact et dépôt source identique au dépôt cible. Le checkout choisit explicitement
le SHA HEAD de la PR uniquement dans cette route, et conserve github.ref pour les autres cas.
Tout autre événement conserve le routage lot3/historique. Les deux lanes et toutes
leurs commandes de test/build/lint restent inchangées.

Le garde lot3 et ses tests Python sont rejoués dans un clone jetable au SHA lot3.
Il y rejoue récursivement les gardes plus anciens à leurs SHA d'origine.
Aucun reçu, test historique, manifeste, permission, driver, dépendance, SDK,
ABI ou runtime n'est modifié.

## Vérification et limites

Commandes au nouveau HEAD :

    python3 tools/check-m50-s1.py
    python3 -m unittest discover -s tests/m50_s1 -v

Les 18 tests Python nouveaux couvrent scope nominal, changement de runtime,
manifeste/permission, suppression, symlink, ajout runtime, workflow non revu,
mission modifiée, preuves manquantes, checkout HEAD, fork homonyme, symlink parent,
modes index/worktree et conditions de routage.
Tests locaux : 18 PASS, 0 FAIL, 0 SKIP (fixture temporaire seulement).
Le garde Git complet et les suites JVM/Robolectric/build/lint/API24 sont confiés
aux deux lanes GitHub Actions sur le SHA publié ; leurs résultats doivent être
lus au run exact, jamais déduits de ce rapport. Le réseau Git local n'est pas
joignable (proxy port8080), aucun contournement tenté.
Aucun chiffre historique n'est transféré au HEAD.

Instrumentation API24/25/26/35 et physique : NOT_RUN dans A1.
Recherche réelle M50-G4 : BLOCKED, aucune nouvelle qualification FTS5.
G1/G7 : PARTIAL, en attente des runs exacts. Pas de M50 atteint.

Rollback : abandonner cette branche/PR ; aucune donnée ni application modifiée.
Prochaine action : lire les deux jobs au HEAD publié et traiter tout échec réel.


## Blocage de publication observé

Deux appels create_tree via connecteur ont été interrompus sans résultat après
attente prolongée. Aucune branche codex/m50-s1 ni PR de code n’est visible au
relevé lecture final. La publication et les CI distantes restent NOT_RUN. Les
18 tests locaux ne remplacent ni le garde Git complet ni les suites produit.
Un patch, un corps de PR et un commit local à parent mission vérifié sont fournis
comme artefacts, dans une base objets Git partielle (pas un clone produit complet).
La reprise doit appliquer le patch dans un vrai clone et lancer les deux lanes.
