# NEX-07 / first bounded Android follow-up

**Base:** FUSION-02 NEXUS `06ccf98a7709364bf09cc1ecea1c35663d5787cf`.
**Scope:** Android selected-document integrity verifier only; no RAG enablement.

## Changed
- `SelectedDocumentSourceVerifier.verify` refuses a catalog record whose declared source length exceeds the 2 MiB selected-stream limit **before opening an Android provider**.
- The existing SHA256 and URI checks stay intact. The previous stream bound still applies when the record's declared length is smaller than the actual provider bytes.
- Robolectric regression explicitly checks the quota response and that the synthetic provider did not receive a new file-descriptor request.
- Reuses the established FUSION-02 JVM/Android-host CI on this new branch.

## Acceptance
- New test in `SelectedDocumentImporterTest` passes; all other Android tests continue to pass.
- No source bytes/URI are logged; no network, fresh permission, migration, native SQLite, or changed user database.
- No positive FTS5, real provider SAF, device, CORE/RAG, or multimodule claim. Current SQLite FTS5 platform refusal remains.

**Rollback:** revert the two Kotlin changes on the isolated branch; no persisted data is modified by this mission.

**Next decision:** a portable Android FTS5-capable backend would require separate architecture, security, licence, memory and migration approval.
