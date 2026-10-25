# Matrice NEXUS pour FUSION-01

Cette matrice appartient au dépôt NEXUS ; elle ne remplace pas le registre F0
ni un BOM approuvé par UNIFIED. `PASS` logiciel ne vaut pas activation produit.

| Fonction / lien | Contrat | Code | JVM réelle | Robolectric | Android instrumenté | Physique | Production / merge |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Catalogue, import, sources, révisions v4 | API locale conservée | PASS | PASS | Backend FTS bloqué | NOT_RUN | NOT_RUN | Non activé / aucun merge |
| Recherche, citations, contexte non fiable | RagContextPort NEX-03 conservé | PASS | PASS | Refus de capacité testé | NOT_RUN | NOT_RUN | Non connecté à CORE |
| DocumentaryGateway, révocation/délai/annulation | API locale /1, callbacks hôte | PASS | PASS | Compilation seulement | NOT_RUN | NOT_RUN | Opt-in hôte ; aucun transport |
| Snapshot chiffré v3→v4 en copie | NEX-05, format snapshot /1 | PASS | PASS | Hors AAR | NOT_RUN | NOT_RUN | Aucune adoption personnelle |
| SAF/PFD et persistance de lecture | Sélection propriétaire obligatoire | PASS | Sans objet | PFD et refus testés | NOT_RUN | NOT_RUN | Aucun grant réel créé |
| Atlas optionnel / JSON cité | jarvis.nexus.graph/1 | PASS | Export réel synthétique | Sans objet | NOT_RUN | NOT_RUN | Pas de condition de démarrage CORE |
| ZIM Linux, lecture seule | Backend libzim conditionnel | Conservé | Contrats seulement | Sans objet | NOT_RUN | NOT_RUN | Archive réelle absente |
| ZIM Android | Aucun backend natif qualifié | UNSUPPORTED | Sans objet | Sans objet | NOT_RUN | NOT_RUN | BLOCKED |
| CORE↔NEXUS Android | Adaptation dépendance/autorité requise | Non raccordé | Client CORE historique non rejoué ici | NOT_RUN | NOT_RUN | NOT_RUN | BLOCKED F0/F2 |
| Inférence, voix, IPC, EVOLUTION | Autres propriétaires | Aucun ajout | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | Désactivés / hors périmètre |

| Cible | Compilation de nos sources | Stockage/RAG natif | Verdict disponible |
| --- | --- | --- | --- |
| Android API24 | PASS contre vrais stubs SDK24, bytecode Java8 | Robolectric : FTS5 absent, refus exécuté ; aucun appareil | BLOCKED pour RAG natif, NOT_TESTED sur appareil |
| Android API25 | Couvert par surface API24 | Framework simulé : refus FTS | BLOCKED dans le backend simulé ; physique NOT_TESTED |
| Android API26 | Couvert par surface API24 | Framework simulé : refus FTS | BLOCKED dans le backend simulé ; physique NOT_TESTED |
| Android API29/ARM64 | Non exécuté spécifiquement | Aucun appareil/ABI qualifié | NOT_TESTED |
| Android API35 | AAR/APK tests compilées ; Lint | Framework simulé : refus FTS | BLOCKED dans le backend simulé ; physique NOT_TESTED |
| Linux de laboratoire | Java17, Gradle8.10.2, vrai SQLite JDBC/FTS5 | Bibliothèque et parseur SERVICES réellement exécutés | SUPPORTED pour la portée logicielle testée |
| Mini-PC matériel | Aucune machine distincte | Batterie/thermique/stockage matériel inconnus | NOT_TESTED |

L'absence de FTS5 dans le SQLite hôte Robolectric **ne prouve pas** son absence
sur chaque téléphone/OEM. La factory doit réussir sa sonde réelle sur chaque
cible ; aucune option de production ne permet de la contourner. Un éventuel
SQLite Android embarqué exige une qualification distincte version/ABI/licence.
