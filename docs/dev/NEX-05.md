# NEX-05 — sauvegarde chiffrable et restauration isolée

Base directe : `777e6afa75ecb99b85e52d68837cb0fefcf161cd` (NEX-03).
Branche : `feat/nex-05-sauvegarde-chiffrable-restaura`. Aucune fusion.

Implémentation : `backup/{CryptoPort,SnapshotTypes,IntegrityVerifier,Snapshots}.kt`,
permissions EXPORT/RESTORE, CLI `BackupDemo`, tests SQLite/JCE réels et smoke CI.
Format détaillé dans [NEXUS-BACKUP-FORMAT.md](NEXUS-BACKUP-FORMAT.md).

Résultat : snapshot natif SQLite cohérent, manifeste versionné et SHA-256,
AES-GCM clé fournie par l'hôte, refus des archives corrompues/incompatibles,
prévisualisation et copie séparée sans adoption destructive. Le journal reste
consultable, sans prétendre reconstruire les données depuis ses anciens événements.

Validation exécutée : commande exacte et XML dans `NEX-05-VALIDATION.json`.
**69 tests JUnit, 0 échec, 0 erreur, 0 ignoré** ; distributions CLI et adaptateur
construites. Démo réelle :

```
BACKUP exported=true authentication=verified restored_documents=1 conflicts=1 corruption_refused=true originals_preserved=true
```

Les tests couvrent aussi vrai FTS/citations après restauration, conflit de forks
à version égale, quotas, mauvais tag/clé, absence de permission/confirmation,
publication sans écrasement, archive authentifiée à chemins interdits et
schéma mensonger. Une revue indépendante a identifié deux courses réelles :
destination alias réorientée et SHA vérifié avant réouverture. Corrigées par
ancrage canonique et digest des octets déchiffrés ; tests déterministes inclus.
Le nettoyage refuse les identités de workspace remplacées et préserve les
sentinelles tierces. ZIP à 1 000 membres refusé avec énumération bornée.

Échecs de développement corrigés : compilation d'une lambda de constructeur,
et erreur JCE sur entrée tronquée maintenant refusée par contrôle minimal du tag.
Aucun test désactivé. Aucun fichier de données, secret ou clé ajouté au dépôt.

Limites : JVM17/Linux, pas d'APK/SAF/Keystore ni essai physique/panne matérielle.
Parent privé de confiance requis ; les contrôles inode ne garantissent pas
l'absence absolue de courses contre un attaquant contrôlant le parent lui-même.
Pas d'écrasement de la base actuelle, migration personnelle ou accès à CORE.
Prochaine étape : NEX-04, lecteur ZIM soumis à disponibilité/licence/ABI qualifiées ;
refus UNSUPPORTED obligatoire si aucun lecteur réel n'est disponible.
