# NEXUS — Missions intensives (préparation uniquement)
Base vérifiée : `b27df0b84d8f0082d17ca6278549298aaaf97926`. Branche plan : `plan/intensive-2026-10-22`.
Source de vérité propriétaire : `bubth720/JARVIS-NEXUS`. Aucun moteur existant dans ce dépôt initial avant la campagne.
- [NEX-01 — Moteur local de connaissances et registre de sources](./NEX-01.md) — P0, dépend de : Aucune
- [NEX-02 — Chaîne d'import local, extraction et indexation incrémentale](./NEX-02.md) — P0, dépend de : NEX-01
- [NEX-03 — Recherche de connaissances citée et assemblage RAG local](./NEX-03.md) — P1, dépend de : NEX-02
- [NEX-04 — Bibliothèque encyclopédique hors ligne et catalogue ZIM](./NEX-04.md) — P2, dépend de : NEX-01,NEX-03
- [NEX-05 — Sauvegarde chiffrable, restauration isolée et format d'échange](./NEX-05.md) — P2, dépend de : NEX-01,NEX-02
Toutes les missions nécessitent une attribution Codex explicite. Aucune fusion `main`.
