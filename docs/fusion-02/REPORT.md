# FUSION-02 / NEXUS — rapport de qualification

Mission : [NEXUS issue3](https://github.com/bubth720/JARVIS-NEXUS/issues/3) et
[UNIFIED issue5](https://github.com/bubth720/JARVIS-UNIFIED/issues/5), lues intégralement,
corps conservés dans `evidence/`. Travaux du25 au26 octobre2026, données exclusivement
synthétiques. Verdict **PARTIAL**, **aucun GO RAG Android ni multi-module**.

## Branche, filiation et périmètre

Branche neuve `feat/fusion-02-nexus-android` depuis
`9d6928cd8651f83fa5091bdb474b33761d366412` (FUSION-01), conservé intact.
Son parent est FINAL `d903bf04f497ab0dc7f40e9708ed057e4f24c6ee`, lui-même descendant
de NEX04 `bbccac2ff2cc052f897c7f5015f234507e047b95` et NEX05
`3acec55616ff8e3b1666091780fca59261377e18`. Le port NEX06A
`374ea2e2fd640e78f2f5e12e60e75b493420e504` diverge depuis NEX05 : merge-base vérifié
`3acec55616ff8e3b1666091780fca59261377e18`. Aucun cherry-pick aveugle : FUSION-01 a
déjà réconcilié les interfaces et la bibliothèque v4, recompilées et testées ici.

HEAD de livraison = commit Git portant ce rapport ; SHA exact fourni dans la PR et
le bilan de publication, sans auto-référence cryptographique impossible dans le manifest.
PROVENANCE.json fixe base, branches, dépendances et hashes source/preuves.
Pas de commit ni checkout dans un dépôt frère. Main reste
`b27df0b84d8f0082d17ca6278549298aaaf97926`. Aucun AGENTS.md trouvé dans le workspace.

L'inventaire initial et celui après reprise sont enregistrés. Les têtes distantes
NEXUS et dépendances de référence sont inchangées à la reprise du26. Aucune branche
FUSION02 d'un autre propriétaire n'a été consommée. CORE, SERVICES, UNIFIED et les
lanes LLM/VOICE/INTERFACE/EVOLUTION restent propriétés de leurs dépôts.

## Fonctions et corrections livrées

1. **Sonde réelle préalable, sans fichier.** AndroidFts5Probe ouvre/ferme une base
   mémoire du SQLite fourni par le processus. Elle relève version/options informatives,
   crée réellement FTS5, vérifie MATCH d'expression, positions0/2 via
   fts5vocab(instance), puis score BM25 fini/négatif. Un module/fonction absent donne
   UNSUPPORTED ; un autre échec donne UNKNOWN. SUPPORTED ne qualifie que ces primitives,
   jamais SAF, RAG, ARM64 ou production. Aucun résultat de compile_options seul n'admet
   le stockage. Aucun moteur simulé ou FTS4 déguisé.
2. **Refus sans création de base.** L'ancien open créait nexus.db AVANT de sonder.
   Le nouveau contrôle lit uniquement les100octets de l’en-tête (sans connexion SQLite, même read-only, qui pourrait toucher le WAL), puis la sonde mémoire précède toute
   création persistante, migration ou lease. Les tests comparent liste et empreintes
   des fichiers avant/après refus et réouverture. L'incompatibilité de version reste
   distincte du défaut de capacité.
3. **Corruption sans suppression.** Le gestionnaire SQLite Android par défaut peut
   effacer une base corrompue. Un DatabaseErrorHandler explicite refuse et conserve
   l'original, après admission positive. Test réel de1024 octets invalides,
   sans reset, migration ou remplacement. Les symlinks et leases existants conservent
   leurs contrôles antérieurs ; pas de promesse de résistance à un processus hostile
   modifiant simultanément le dossier privé de l'hôte.
4. **Source sélectionnée relue à la demande.** SelectedDocumentSourceVerifier compare
   URI exacte, SHA256 et longueur d'un SourceRecord déjà autorisé aux octets relus,
   au maximum2MiB ; retourne CONFLICT, DENIED, INVALID_INPUT ou LIMIT_EXCEEDED selon cas.
   Les PFD sont fermés. `available()` du flux borné ne peut plus suggérer une allocation
   supérieure au quota. Pas de seconde copie/index, scan, droits déduits du contenu,
   conservation implicite de grant ou ouverture en écriture.
5. **Harness Android enrichi.** Tests de sonde/refus, fichiers préservés, corruption,
   transaction/rollback/réouverture, PFD/fermeture/oversize/denial, vérification du hash
   source et parcours content→RAG conditionnel. Le fournisseur synthétique est limité
   à l'APK de test, non exporté dans la livraison finale, sans chemin arbitraire ni
   contenu personnel. Il ne constitue PAS une qualification DocumentsUI/grants SAF.
6. **Livraison et contrôle.** AAR API24, compilation contre vrais stubs24, frontière
   Java8 sans JDBC/java.nio.file/java.time/JNI/permissions/composants exportés dans
   l'AAR. CI FUSION02 indépendante, preuves expurgées, reçus vérifiables hors réseau et
   tests de falsification. Les contrats publics existants n'ont pas été mutés.

L'autorité reste CORE ; la vérification de source ne remplace ni les scopes et
callbacks d'autorité de DocumentaryGateway, ni la validation complète de citation
par RagContextPort.resolve. Un fournisseur externe peut modifier/révoquer après
la lecture : aucune atomicité externe ou garantie de lease n'est annoncée.

## Tests exécutés et preuves

Les chiffres définitifs et campagnes sont détaillés dans VALIDATION.json et les
JUnit expurgés ; ce tableau distingue volontairement les niveaux de preuve.

| Domaine | Résultat / limite |
|---|---|
| Kotlin/JVM bibliothèque v4 + adaptateur SERVICES épinglé | 95 tests réussis, zéro échec/erreur/ignoré ; import réel1000 CSV, citations après réouverture, mises à jour incrémentales, droits/deadline, AES-GCM et restauration isolée v3→v4 |
| Robolectric | Matrice simulée API24/25/26/29/35 ; tests de stockage et source + nouveaux refus/corruption. Compte exact dans VALIDATION.json ; jamais une preuve Android physique |
| SQL, DDL réelle v4 | 10 tests réussis, interruption/reprise/corruption/extraction structurée |
| Graphe JavaScript | 7 tests réussis ; aucune nouvelle performance GPU/appareil revendiquée |
| ZIM | 18 réussis,1 ignoré pour archive réelle absente. Fixture sparse64 étiquetée, aucun benchmark Wikipédia réel |
| Android/API24/25 | Résultats finaux, empreintes APK et logs dans VALIDATION.json ; premier essai API24 sur compilation intermédiaire :7 tests réussis, SQLite3.9.2, FTS5 absent, aucune citation inventée |
| API24 compilation / AAR / Lint | Build et compilation stubs réels ; résultats expurgés conservés, pas d'inférence ABI ARM64 ni runtime à partir du build |
| Vérification des reçus | Hashes de sources et preuves, nombres comparés aux JUnit/runner ; rejette GO artificiel, activation, faux compte/hash/base et qualification physique inventée |
| Physique, rotation et force-stop, PDF Android | NOT_RUN ; aucune donnée/appareil personnel |

Les95 tests incluent80 historiques (17 import,12 RAG,18 stockage,8 FINAL,6 archive,
19 snapshots),10 gateway FUSION,1 migration snapshotv3,1 charge1000 documents et3
SERVICES. Ils ne s'additionnent pas aux anciennes campagnes comme s'il s'agissait
de tests différents. L'indexation1000 documents est synthétique JVM, pas Android.
Les mesures de FUSION-01 restent historiques ; pas de nouvelle promesse de latence
ou de consommation Wikipédia/ARM64 à partir d'une fixture.

N3 est confirmé sur copie : le test FusionSnapshotMigrationTest emploie la vraie DDLv3,
un snapshot ZIP chiffré AES-GCM et la restauration isolée versv4, puis résout une citation ;
basev3, snapshot original et base courante sont inchangés. Aucun schéma/backup CORE
ou document réel migré. Aucun schéma NEXUS ou format snapshot modifié par FUSION02.

## Environnement et méthode Android

Outillage officiel épinglé, hashes conservés : JDK17.0.16+8, Gradle8.10.2, SDK24/35,
build-tools35.0.0. Après redémarrage, `/tmp` a disparu ; reprovisionnement explicite
sous `/workspace/.fusion02-build` (sans changement HOME ni installation système).
Les tests initialement réussis du25 ont été conservés, puis les derniers changements
Android ont été revalidés. Le runtime n'a aucune dépendance réseau ajoutée.

Émulateur officiel37.2.12 build16428233, images AOSP x86 API24r08/API25r01,
archives et SHA256 en evidence/emulator*.json (SHA1 catalogue officiel aussi contrôlé).
Machine hôte Linux x86_64, quota2CPU,8GiB, aucun /dev/kvm ; accélération désactivée,
RAM invitée1536MiB, rendu SwiftShader,480×800. Ce n'est pas un téléphone ARM64.
Aucun appareil personnel connecté/visé.

ADB officiel37.0.1 ne pouvait pas créer son dossier sous le home en lecture seule ;
le home n'a pas été modifié. Le transport minimal de laboratoire en `lab/transport.py`
parle le protocole ADB à l'adbd de l'émulateur local dédié uniquement (ports5581/5583),
sans serveur hôte, clé ni login ; un challenge AUTH est refusé, jamais contourné.
Les installs concernent seulement l'APK de tests dans ces VM synthétiques. Les
sources exactes de provisionnement et configurations sont archivées en `lab/`.

Le premier broadcast airplane depuis shell a été refusé par Android (preuve gardée).
Le runbook final utilise les droits root prévus de l'image userdebug jetable pour
ce seul broadcast et désactive Wi-Fi/données avant les tests. Les logs indiquent les
commandes et résultats effectifs ; un simple flag settings n'est pas une preuve de
réseau désactivé. Pas de grant ni permission ajouté à l'AAR de production.

PSS : uniquement `android.os.Debug.getPss()` dans le processus instrumenté au moment
de la sonde, valeur KiB dans le log JSON. Inclut runner/instrumentation/GC ; ni pic
RAM d'import, ni consommation isolée du backend, ni mesure thermique ou physique.
Aucun budget de production dérivé de cette valeur.

## CI : cause historique, correction, état à SHA exact

Run NEX06A40115049468, SHA374ea2e2fd640e78f2f5e12e60e75b493420e504 : job JVM
120934494420 SUCCESS, job Android120934494656 FAILURE. Diagnostic expurgé conservé :
setup-android téléchargeait cmdline-tools16 puis lançait `sdkmanager tools` ;
**Failed to find package 'tools'**, exit1. Les validations produit Android suivantes
étaient SKIPPED. Ce n'était pas un test RAG échoué ni une preuve de FTS absent.

`.github/workflows/fusion-02.yml` remplace cette dépendance obsolète par les archives
SDK/build-tools épinglées du helper déjà exécuté au labo. Jobs JVM et Android-hôte
séparés, tests, Lint, compilation instrumentation et frontière conservés ; aucun
`continue-on-error`, test neutralisé ou activation native. Déclenché sur la branche
FUSION02 et manuellement. Les artefacts sont publiés même en échec.

L'ancienne branche n'est pas réécrite. Le reçu FUSION01 reste figé sur son SHA ;
son vérificateur historique n'est pas présenté comme un reçu de ce nouveau code.
CI du nouveau SHA : voir bilan/PR de publication et preuve de relecture après push ;
un build local réussi ne vaut pas CI distante réussie. L'instrumentation locale
n'est pas exécutée par ce workflow hôte et n'est pas assimilée à Robolectric.

Audit des dépendances au checkpoint : CORE36761cea CI40215604539 SUCCESS ;
SERVICES portf00091a CI40115305244 FAILURE ; SERVICES FINALd16dd84 CI40144003529
SUCCESS ; UNIFIED3caa57d CI40214722427 FAILURE ; INTERFACE1d0a89f CI40214158554
SUCCESS. LLM HEAD documentairee0b7814 sans run au SHA exact, succès du commit de code
antérieur378a1cf non transposé au HEAD. VOICEfe57452 garde des runs mixtes,
EVOLUTIONc018018 un run réussi. Aucun succès tiers n'est une qualification NEXUS.
Les autres workflows restent sous responsabilité de leur propriétaire.

## Blocages, risques et prochains gates

- **RAG Android bloque sur FTS5** : aucun backend externe inclus. Comparaison détaillée
  AndroidX2.5.2, Requery3.50.4, plateforme et fallback dégradé dans BACKENDS.md.
  Seul l'artefact AndroidX a été inspecté statiquement ; licence, taille et ABI
  observées ne démontrent ni runtime, ni sécurité complète, ni performances.
- **Accord spécifique requis avant SQLite externe**, selon issue NEXUS3 N2 et
  UNIFIED5 §4 N2 : architecture/provider, licence/notices, SHA, compilation ARM64,
  gestion16KiB, CVE, probe et budget mémoire/thermique à valider. Aucune demande
  implicite d'activation, aucun contournement de la sonde actuelle.
- **SAF persistant réel non qualifié** : le fournisseur de test content: n'est pas
  DocumentsUI. Choix utilisateur, grant persistant, révocation système et redémarrage
  de processus doivent être instrumentés dans le pilote hôte. Les tests de refus
  et relecture du port ne suffisent pas à cocher ce gate.
- **PDF Android SERVICES conditionnel** : l'extracteur JVM épinglé reste qualifié sur
  JVM ; ne pas l'embarquer comme PDF Android. Port SERVICES API24 à revoir/consommer
  après sa livraison, avec limites2MiB/100pages, deux pages synthétiques et provenance.
- **CORE et autres modules non intégrés** : conserver feature flags OFF, pas de service
  exporté/IPC/JNI, pas de seconde mémoire/catalogue. Le sample source compile dans
  le harness, pas dans AppGraph/SessionCoordinator de CORE.
- **API26/29/35 instrumentées et ARM64/physique non qualifiés** : seuls les niveaux
  effectivement consignés ont une preuve. Les autres restent NOT_RUN. Pas de GO par
  extrapolation d'un émulateur x86 vers un téléphone.
- **ZIM hors scope** : aucune ouverture/copie/modification de l'archive Wikipédia55Go,
  aucun reader Android ni performance réelle nouvelle.

Prochaine action proposée : revue de la PR brouillon et choix explicite d'un backend
SQLite si le RAG sur ces plateformes est souhaité ; ensuite adapter le même
NexusStoragePort dans une tranche autorisée, refaire sonde+TXT+citations+réouverture
API24/25 et parcours SAF réel, puis seulement soumettre le gate consommateur CORE.
Aucune fusion main, distribution, installation personnelle, migration réelle,
permission produit ou activation sensible effectuée.


## Dernière revue WAL et reprise du26

Le build final WAL a réussi (71 tests Robolectric, zéro échec/erreur/ignoré).
La lecture préalable de version utilise seulement l’en-tête SQLite, puis la version
réellement visible via WAL est recontrôlée après admission FTS5. Un en-tête invalide,
un fichier tronqué ou une erreur d’E/S donnent STORAGE_FAILURE. Le test de refus
compare aussi les octets de la base, du WAL et de la mémoire partagée pendant qu’une
autre connexion reste ouverte. Les campagnes à9 tests sont la revalidation de ce code ;
les campagnes précédentes à7/8 tests restent des preuves intermédiaires identifiées.
Les détails définitifs figurent dans VALIDATION.json, sans addition de répétitions.

Le changement de clé debug après disparition de l’ancien dossier temporaire a causé
INSTALL_FAILED_UPDATE_INCOMPATIBLE dans une tentative intermédiaire. Seule l’application
de test synthétique a été retirée/réinstallée dans la VM jetable ; aucune signature
produit, configuration CORE, installation personnelle ou donnée privée modifiée.
La relecture du fichier APK transféré par le protocole sync vérifie son SHA256 local,
car sha256sum n’est pas disponible dans ces images. Les tentatives et erreurs restent
conservées ; elles ne sont pas comptées comme succès de la dernière compilation.


## Résultat final exécuté

- JVM :95/95 ; Robolectric :71/71 ; zéro échec/erreur/ignoré.
- Émulateur API24 :9/9 et API25 :9/9 sur le même APK final SHA256
  `c3a0862171279cf76f2aef3d5f8b1fcdb7e1eed9a4798f043ca7158c09643280`,
  vérifié par relecture du transfert. SQLite3.9.2, sonde CREATE refusée sur les deux.
- Base/WAL/mémoire partagée préservés pendant connexion active :PASS sur les deux.
- Sources synthétiques content: et fermetures/PFD/hash/refus :PASS ; vrai SAF persistant :NOT_RUN.
- RAG Android :UNSUPPORTED sur les deux backends observés ; aucune citation fabriquée.
- Compilation stubs24 :1Java+23Kotlin ; frontière Java8 :131classes core+23classes
  Android ; AAR min24, zéro permission/composant productif/JNI ajouté.
- Lint :0erreur,2avertissements GradleDependency conservés ; aucune règle supprimée.
- Tests SQL10/10, JavaScript7/7, ZIM18réussis+1ignoré (vraie archive absente).

Dernière action de qualification :campagnes instrumentées finales à9cas par API,
puis vérification hors ligne des reçus et publication en PR brouillon.


La revue automatique de publication a refusé le log CI brut historique, en raison de
métadonnées de runner, chemins internes et informations d’authentification masquées.
Le fichier versionné est un diagnostic expurgé (cause, étape et résultats). Le log brut
reste uniquement dans le laboratoire local, sans tentative de contournement.


Les revues automatiques suivantes ont aussi refusé les métadonnées d’environnement
présentes dans les logs locaux (hostname, proxy et routes). Les journaux publiés sont
expurgés de ces champs ; les originaux sont conservés uniquement dans le laboratoire.
Les résultats, assertions, versions API/SQLite et empreintes d’artefacts restent présents.
Les hashes du reçu portent sur les fichiers expurgés effectivement publiés.
