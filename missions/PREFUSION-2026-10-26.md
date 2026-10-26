# Codex mission NEX-PF-01 — Android documentary qualification

Repository: bubth720/JARVIS-NEXUS
Working reference: branch mission/prefusion-gates-20261026
Parent code baseline: 8c778be1b302d10c8dcbf8444f6e6e051477c825

## Instructions
Inspect current code, branch SHAs, tests and existing AGENTS.md if present. Create a fresh development branch from this mission branch. Submit a draft PR back to this branch. Do not merge into main, install apps, modify personal documents, enable writes or migrate databases without human approval.

### 1. Verify real FTS5 Android support
Read docs/fusion-02/REPORT.md and docs/nex-07/REPORT.md and Kotlin AndroidFts5Probe.kt and AndroidNexusStorage.kt. Run FTS5/MATCH/vocab/BM25 on actual Android API24/25/26/35 with explicit emulator vs Robolectric reporting; do not label missing SQLite feature as a successful RAG path.

### 2. Strengthen safe source handling
Use SelectedDocumentImporter.kt and Android tests. Check over-quota declared lengths, provider supplying more bytes than advertised, changed URI/content, revoked grants, corruption, active WAL and no db deletion. Preserve original bytes and historical FUSION-02 hashes.

### 3. Review database solution, no unapproved replacement
Propose optional portable SQLite FTS5 backend only after examining ABI, APK size, dependency licences, minSdk24, thermal/memory costs and reversible migration. If approval is required, STOP before adding native libraries. Compare a safe degraded literal search mode without claiming feature parity.

### 4. Acceptance
Run python3 tools/check-nex07.py, Gradle Kotlin/JVM and Android host tests and instrumentation where supported. Report exact tested SHA, all results, skips, failures, database before/after hash, blocked gates, rollback and compatibility with CORE. Never fake physical device or Android RAG evidence.

No merge main; draft PR only.
