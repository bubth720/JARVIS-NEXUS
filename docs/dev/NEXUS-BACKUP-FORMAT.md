# Sauvegarde NEXUS expérimentale — version 1

Ce format JVM ne remplace ni les archives CORE/UNIFIED existantes ni leurs contrats.
L'export produit une copie SQLite cohérente via l'API de backup SQLite ; la base
source et ses journaux ne sont pas modifiés pour créer cette copie.

Enveloppe : ASCII `JARVIS-NEXUS-01\n`, nonce aléatoire de 12 octets, puis AES-GCM
(tag de 128 bits, en-tête authentifié comme AAD). La clé AES appartient à l'hôte,
ne figure ni dans l'archive, ni dans Git. Les essais utilisent des clés éphémères.
La portabilité du conteneur ne constitue pas un transfert de clé : enveloppement,
rotation, récupération et Android Keystore restent à qualifier séparément.

Charge utile authentifiée : ZIP contenant exactement `manifest.json` et
`knowledge.sqlite`. Le manifeste JSON canonique fermé déclare
`format=jarvis.nexus.snapshot/1`, le schéma SQLite exact, `engine=sqlite`, taille
et SHA-256 de la base. Champs inconnus, doublons, chemins supplémentaires,
versions incompatibles et dépassements sont refusés. Le digest facultatif de
l'archive porte sur les octets réellement lus par le déchiffrement, comparé
avant parsing ZIP. L'intégrité GCM est vérifiée avant toute lecture du ZIP.

Limites par défaut : base 16 Mio, archive 20 Mio, prévisualisation de 1 000
documents. Les lectures sont bornées ; le fournisseur JCE et ZipFile peuvent
allouer dans cette borne. L'énumération des membres s'arrête au troisième.
Vérifications SQLite : en-tête/version avant ouverture, integrity_check,
foreign_key_check, tables requises, état des révisions et index FTS réel.
Aucune réparation ou migration silencieuse.

Publication par hardlink atomique sans écrasement, sur le même système de
fichiers. Sans support, refus explicite ; aucune substitution destructive.
La destination utilise le parent canonique capturé au départ. Les workspaces
privés contrôlent leur identité inode avant utilisation et nettoyage ; une
identité changée refuse le nettoyage plutôt que supprimer le répertoire tiers.
**Hypothèse : parent privé contrôlé par l'hôte.** Ces contrôles ne prouvent pas
une résistance absolue à un attaquant remplaçant simultanément le parent
canonique ou ses descendants. Aucun mécanisme SAF Android n'est qualifié.

Restauration : confirmation RESTORE explicite, vérification complète, puis copie
séparée. Prévisualisation des conflits de version et de contenu/métadonnées même
à version égale. Aucun remplacement de base courante, aucune adoption automatique.
Fermer le workspace annule la copie ; `retainSeparateCopy` conserve explicitement
la copie distincte. Une erreur conserve originaux et archives. Une identité de
workspace modifiée peut laisser un répertoire déplacé pour examen de l'hôte.

Le journal restauré est consultable par curseur ; il ne permet pas de reconstruire
à lui seul tout l'état historique (les événements anciens n'ont pas tout le
payload). Ce livrable ne revendique pas un event sourcing complet, une migration
personnelle, un essai physique Android ou une résistance à une coupure matérielle.
