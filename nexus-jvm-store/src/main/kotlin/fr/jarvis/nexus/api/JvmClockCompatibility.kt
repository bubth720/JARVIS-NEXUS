package fr.jarvis.nexus.api
import fr.jarvis.nexus.store.*
/** Source-compatible JVM convenience; Android consumers use NexusClock, no java.time dependency. */
@Suppress("FunctionName")
fun KnowledgeCatalogService(database: NexusStoragePort, access: AccessPolicy, limits: CatalogLimits = CatalogLimits(), clock: java.time.Clock): KnowledgeCatalogService =
    KnowledgeCatalogService(database, access, limits, NexusClock { clock.millis() })
