# REUSE FIRST R1+ — consultation documentaire expérimentale

Base M53 eae61fb62a9ccd4073b96d71454f89e5a943eae9, 100 tests JVM historiques
à conserver. Aucun source métier, build par défaut, donnée, contrat canonique ou
preuve historique modifié. Le nouveau garde fige tous les octets et modes de M53.

ReadOnlyConsultationAdapter réutilise DocumentaryGateway/1 → LocalRagService →
SQLite FTS5 et CitationResolver. Il conserve les citations, les hashes, le JSON
non fiable et la corrélation session/requête/génération. Désactivé par défaut,
sans callback de session courante et autorisation fraîche du DocumentaryCall il
refuse. Une consultation réévalue la révocation et la deadline ; elle ne crée pas
de cache de résultats, d'autorisation, de connexion, de modèle ou de capacité d'écriture.

Il s'agit de code de laboratoire : l'init explicite ajoute l'adaptateur et ses
tests au source set **test uniquement**. Aucun JAR productif ou APK ne le reçoit.
Ce carrier candidat n'est pas un nouveau contrat wire approuvé, ni une identité
Binder, un consentement utilisateur ou un mandat CORE. Aucun raccordement actif.

```sh
python3 reuse-r1plus/check.py
REUSE_BENCHMARK_OUTPUT="$PWD/build/reuse-r1plus/benchmark.json" ./gradlew -I reuse-r1plus/init.gradle :nexus-jvm-store:test :nexus-core:test --no-daemon
python3 -m unittest discover -s tests/final -v
```

Le corpus CC0 de M53 est réutilisé tel quel dans des bases temporaires synthétiques.
Tous les tests vérifient l'absence de mutation du catalogue et l'intégrité SQLite.
Le benchmark compare des appels réels alternés après échauffement : le consommateur
de référence reproduit la résolution publique redondante déjà utilisée dans le test
M53, le candidat consomme le bundle que Gateway a déjà validé dans son snapshot.
Aucune vérification interne de Gateway n'est retirée. Cette référence est un
**consommateur de test, pas un parcours productif historique**. Les payloads doivent
être identiques. Les timings bruts et quantiles sont enregistrés sans assertion de
gain ; un p95 dégradé n'est pas caché. Aucun gain Android, LLM ou batterie n'en découle.

Android ARM64/API29/35, SAF, UI sourcée, mémoire personnelle, Kiwix natif et
autorité CORE restent BLOCKED/NON QUALIFIÉS. Pas de migration, réseau, renderer,
import privé ou action SERVICES. Retirer l'option `-I` suffit au rollback.
Les gardes historiques aux périmètres exacts restent inchangés et sont rapportés
séparément ; leur refus des nouveaux ajouts n'est pas transformé en succès.
