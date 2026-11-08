# SERVICES — audit documentaire, sans modification

Référence inspectée en lecture seule : `bubth720/JARVIS-SERVICES`
`8d80d09d6aabf6eac00a7bc6bb4930862bd72812`. Fichiers effectivement lus :
`contracts/v1/request.schema.json`, `docs/CONTRACT_AUDIT.md`,
`docs/fusion-02/COMMIT-CONTRACT.md` et
`services-portable-api/src/main/java/fr/jarvis/services/modules/guard/AccessGuard.java`.

Le schéma local v1 exige un transport trusted_in_process et un module fermé
documents/knowledge/data/actions/memory/analyst/guard/bridge. Ce champ sérialisé
n'est pas une preuve d'autorité ; seul l'hôte construit `AccessGuard.Context`.
`confirm` exige les grants confirm + opération ; `consume` retire le jeton à
usage unique et vérifie principal/opération/hash/délai (60s monotones). Un texte
retrouvé ou un LLM ne peut fournir ces grants ni transformer une lecture en action.

Le contrat fusion-02 commit-proposal/1 est explicitement une **proposition**, sans
raccord runtime. Il exige aperçu exact (session/requête/génération, source SHA256,
contenu, destination, deadline), consentement opaque après confirmation UI, point
de linéarisation CORE et journal COMMIT_STARTED avant effet. Close + relecture +
SHA256 + original préservé précèdent COMMITTED. Exception après engagement ou ACK
perdu => UNKNOWN, aucune répétition automatique. Annulation logique ne prouve pas
fin native/ContentResolver. Les exports SAF actuels restent UNQUALIFIED hors
leurs fixtures ; les tests de crash simulé ne prouvent pas la durabilité physique.

| Risque M53 | Préparation/point d'arrêt |
|---|---|
| Lecture documentaire autorisée | scope/document/grant minimaux ; banc NEXUS seul dans cette PR |
| Création/export réversible | aperçu cible exacte + consentement unique, contrat CORE à valider |
| Modification/suppression | accord distinct, original préservé, refus par défaut |
| Action externe/BRIDGE | aucune connexion ni transport autorisé par ce lot |
| Mémoire personnelle | opt-in et store/provenance séparés, aucun enregistrement implicite |
| Révocation après commit | état explicitement potentiellement partiel, jamais rollback inventé |

Prochaine décision : valider une proposition d'adaptation versionnée et sa
matrice d'autorité avant toute branche sensible. Aucun source SERVICES changé,
aucune action SERVICES exécutée et aucun gate d'écriture accepté ici.
