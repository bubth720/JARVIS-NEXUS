# M53 lecture seule — préparation isolée

Module NEXUS ; mission M53.01/03/04/10, préparation M53.02/05.
Dépôt `bubth720/JARVIS-NEXUS`, branche `codex/m53-readonly-preparation`.
Base exacte : `be70faa41d202d77eb8cf0cc5c831e10cfb020b5`, tree
`1045f29dc045ee739630c97f2c241a6f45561a42` (PR #12).

Ce lot ajoute seulement un init Gradle explicite, des tests, trois textes créés
pour ce laboratoire, des documents et une CI dédiée. Aucun source historique,
contrat partagé, permission, stockage utilisateur ou raccord runtime n'est changé.
Le contenu du corpus n'est pas une référence scientifique : ses faits sont fictifs.
Ses fichiers sont dédiés par leur auteur au domaine public sous CC0-1.0 ; aucune
licence du code historique n'est supposée ni remplacée (pas de LICENSE racine).

## Exécution

JDK17, Gradle8.10.2 du wrapper, Kotlin2.0.21 et SQLite JDBC3.46.1.0 existants :

```sh
python3 m53review/check.py
./gradlew -I m53review/init.gradle :nexus-jvm-store:test :nexus-core:test --no-daemon
python3 -m unittest discover -s tests/final -v
```

L'init ajoute les sources de revue au **test** de `nexus-jvm-store`. Sans `-I`,
les builds historiques sont identiques. Les tests importent volontairement trois
fichiers de ressources dans une nouvelle base temporaire, ferment ensuite les
droits d'écriture et appellent le vrai `DocumentaryGateway/1`, le vrai
`LocalRagService` et, via `resolve`, le vrai `CitationResolver` sur SQLite FTS5.
Ce sont des tests JVM du code existant, pas une imitation Python de son API.

La CI conserve JUnit, logs, identité commit/tree, garde de préservation et SHA256
des preuves 30 jours. Télécharger l'artefact avant expiration et vérifier son
manifest SHA256 ; les rapports versionnés ne remplacent pas les données brutes.
Les résultats définitifs sont liés au SHA du run et au rapport de livraison de PR.

## Périmètre atteint et limites

Les huit scénarios M53 portent sur recherche réelle, hashes/locators, Unicode,
injection comme donnée, absence d'indice probant, bornes, défaut d'autorité,
révocation logique, annulation/deadline et citation falsifiée. Chaque scénario
vérifie aussi l'absence de mutations après préparation et l'intégrité SQLite.
Les contrôles d'autorisation sont ceux d'un hôte de test local, jamais un mandat
CORE ni une preuve LAB_CLOUD d'admission LLM.

NOT_RUN : Android API29/35, SAF réel, rotation/redémarrage Android, documents
privés, OCR, modèle LLM, streaming natif, TTFT, consommation physique, SERVICES
runtime et CORE/INTERFACE. Aucun succès de fixture ne vaut PASS du gate M53 :
la lecture volontaire avec réouverture SAF après lifecycle reste à qualifier.
La réponse sourcée générative et les actions d'écriture sont des gates séparés.

Rollback : fermer la PR et retirer l'init `-I` de la campagne ; aucune DB ou
application historique n'est migrée. Ne pas supprimer des sources personnelles.
