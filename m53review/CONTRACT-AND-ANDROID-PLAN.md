# Proposition documentaire `jarvis.m53.readonly-proposal/1`

Statut PROPOSED, sans code d'adaptation connecté. Aucun changement UNIFIED v1,
CORE ou INTERFACE. Autorité conservée par CORE ; NEXUS ne fabrique aucun mandat,
bail LLM, confirmation SERVICES ou accès global.

| Champ proposé | API réelle et limite conservée |
|---|---|
| scope autorisé | `SearchScope` collections/sources, intersection avec le scope hôte, vide refusé |
| query + filters | `KnowledgeQuery`, 1..256 caractères, 1..12 mots, phrase/type/dates |
| maxResults | `ContextLimits.maxItems` 1..20, valeur laboratoire 5 |
| deadline + annulation | `DocumentaryCall`, horloge monotone du même domaine, callbacks de l'hôte |
| verifiedText | `ContextItem.text`, résolu de nouveau dans le même snapshot |
| segment/locator | citation document/version/ordinal/page/start/end ; offsets **UTF-16 du texte extrait**, pas octets PDF |
| hash | source SHA256 réel + texte SHA256 + ID de citation intégrant la provenance |
| bounds | carrier JSON <=8000 caractères et <=8192 octets laboratoire ; aucune troncature sans citation |

Le score FTS/BM25 est un rang de recherche, pas une probabilité de vérité.
`estimatedTokens` n'est pas une preuve de tokenisation ni une admission LLM.
L'index actuel lie version de document et version indexée READY ; sa date n'est
pas exposée comme date de création du fichier. Avant intégration UI, ajouter
une proposition explicite pour date d'index si nécessaire ; ne pas l'inventer.
Le résultat interne contient des URI : l'UI future affiche titre/type/locator,
et ouvre via un résolveur hôte autorisé, sans afficher/exporter l'URI privée.
Une citation inventée, altérée, révoquée ou périmée ne devient pas cliquable par
simple texte LLM. Le JSON `trusted:false` reste de la donnée documentaire.

| État réel | Affichage futur proposé |
|---|---|
| READY | passages vérifiés, source ouvrable uniquement après recontrôle du grant |
| NO_EVIDENCE | « Aucun passage pertinent vérifié » |
| DENIED/CANCELLED/DEADLINE_EXCEEDED | refus explicite sans passage tardif |
| INDEX_MISSING | index indisponible, proposer une réindexation volontaire |
| BUDGET_EXCEEDED/BYTE_BUDGET | résultat refusé, pas de faux extrait tronqué |
| citation NOT_FOUND/CONFLICT | indisponible, conserver seulement le dernier identifiant connu |

## Matrice de formats de départ

Réutiliser `ParserRegistry`, `ImportCoordinator`, les parsers SERVICES et les
lecteurs existants ; pas de parseur PDF/DOCX improvisé dans cette revue.
TXT de ce lot : UTF-8 strict contrôlé par corpus, page synthétique1, source <=4Kio,
fragment <=1024 caractères. Limites historiques par défaut : source16Mio,
2048 fragments, 8192 caractères/fragment, total2M caractères ; ce ne sont pas
des budgets physiques Android qualifiés. PDF/TXT/MD/CSV/TSV/XML/DOCX/ZIP dépendent
du parser explicitement fourni ; inconnu => UNSUPPORTED_FORMAT. PDF scanné : OCR
offline NOT_QUALIFIED. ZIM : lecteur existant distinct, pas d'index massif ni de
copie de la bibliothèque Wikipedia ; qualification dédiée requise.

## Campagne Android préparée — entièrement NOT_RUN dans ce lot

1. Variante testOnly indépendante, écrans Documents existants INTERFACE, aucun
   accès stockage global. Fichiers de provider synthétique distinct par UID.
2. Choix SAF volontaire ACTION_OPEN_DOCUMENT ; grant exact et minimum, persistance
   seulement si proposée/acceptée ; aucun index automatique du téléphone.
3. Import, recherche locale avec LLM OFF, extrait et ouverture source réelle.
4. Rotation, redémarrage, URI temporaire/persistante, révocation OS et refus provider,
   source déplacée/supprimée, index interrompu, doublon de nom avec hash différent.
5. PDF corrompu, ZIP déguisé, quota, UTF-8 invalide, liens forgés, injection, échéance
   et annulation ; original inchangé et zéro texte privé dans les logs.
6. API29/35 sur runner compatible seulement. L'erreur historique HV_UNSUPPORTED
   ne justifie aucune boucle de tentatives ou modification de l'hyperviseur.
7. Gate lecture séparé du gate LLM sourcé et de toute action SERVICES ; aucun
   modèle ou appareil personnel requis pour les tests préparatoires.

CORE/INTERFACE doivent valider identité/version, closures d'autorité, fin de
session et droits de réouverture avant branche d'intégration distincte. Ce lot
n'ouvre aucun transport, aucun nouvel appel et aucun worker de génération.
