# Atlas local NEXUS

Visualiseur autonome sans dépendance JavaScript, CDN, compte ou serveur documentaire.
Les quatre fichiers statiques utilisent Canvas 2D pour projeter une sphère 3D.
Le modèle borné (`graph.js`) est séparé du rendu (`app.js`).

Avec Python déjà disponible, lancer explicitement depuis la racine du dépôt :

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory viewer
```

Ouvrir `http://127.0.0.1:8765`, puis choisir une page JSON exportée par
`GraphService.page().toJson()` ou la CLI `library ... graph ...`. Ce serveur
ne sert que les fichiers du visualiseur ; il ne reçoit aucun document.
L'ouverture directe de `index.html` est une option statique, mais elle n'a pas
été qualifiée : le navigateur administré de l'environnement bloque `file://`.

« Explorer un exemple synthétique » crée uniquement des nœuds en mémoire,
explicitement marqués synthétiques. Le bouton « Vider la vue » n'efface aucun
document. L'import d'une autre collection exige de vider la vue.

Rotation : glisser ou flèches clavier ; zoom : molette, pincement, touches +/− ;
déplacement : Maj-glisser ou déplacement à deux doigts. La liste propose une
sélection au clavier indépendante du canvas. La sélection révèle une fiche,
les relations et leurs citations exportées. Le visualiseur ne prétend pas
résoudre cryptographiquement une citation : cela revient à `RagContextPort`.
Seuls les URI `file://` et `content://` donnent un lien d'ouverture volontaire.
Les liens suggérés restent distincts et peuvent être masqués.

Chaque page est limitée à **1 Mio**, 250 nœuds et 1000 liens. La vue cumule au
plus **500 nœuds / 2000 liens**, puis refuse la page suivante sans modifier son
état. Mobile : densité par défaut 100, curseur plafonné à 250. Le stockage peut
contenir davantage de nœuds : utiliser les curseurs `nextNode` et `nextEdge`,
vider la vue et consulter une autre fenêtre. Les relations vers des nœuds d'une
autre page restent dans les données bornées mais ne sont tracées qu'après
chargement des deux extrémités. Les changements de filtre ou de sélection
ne déclenchent aucun chargement automatique.

Le rendu est demandé seulement quand la vue change ; aucun moteur physique,
animation permanente, service worker ou modèle ne tourne en arrière-plan.
La profondeur 1–3 concerne seulement les données chargées. Ce n'est pas une
traversée complète du graphe en base. Pour des graphes plus grands, conserver
cette pagination avant d'ajouter un rendu WebGL ou une API hôte locale.
