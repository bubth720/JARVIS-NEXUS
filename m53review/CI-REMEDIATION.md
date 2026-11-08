# Première exécution CI conservée

Source `7b0591a9e615bbac69cb3c775637e5e212ba91a7`, tree
`21843863eedd75c8efd410b32f32e631ed2d5d9f` :
[run42456495284](https://github.com/bubth720/JARVIS-NEXUS/actions/runs/42456495284)
FAIL avec zéro job. Les huit nouveaux tests JVM sont alors NOT_RUN.

La revue du YAML identifie une expression `runner.temp` dans `jobs.review.env`,
où ce contexte n'est pas disponible. Le correctif utilise un répertoire relatif
de build pour les mêmes preuves. Aucun test, source métier, garde historique ou
contrat n'est changé. La nouvelle CI doit être examinée avant de conclure PASS.

La CI historique NEX-07 run42456497929 reste FAIL : le garde de périmètre exact
refuse les additions M53 non incluses dans son périmètre approuvé. Ses jobs JVM
et Android s'arrêtent avant leurs tests. Ce garde n'est ni modifié ni contourné.
La CI dédiée distingue son contrôle de préservation au nouveau HEAD du rejeu du
garde M50 sur sa base historique exacte. Ce dernier ne certifie pas le HEAD M53.

Le rollback est l'abandon de la branche additive ; aucune application, base
utilisateur, signature ou permission n'a changé.
