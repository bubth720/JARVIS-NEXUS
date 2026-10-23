pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "jarvis-nexus"
include(":nexus-core", ":nexus-cli")
// Optional build-time adapter, pinned to the delivered SERVICES extraction capability.
providers.gradleProperty("servicesSource").orNull?.let { source ->
    val validation = ProcessBuilder("python3", file("tools/prepare-services.py").absolutePath, source, "--verify").inheritIO().start().waitFor()
    check(validation == 0) { "SERVICES source provenance check failed" }
    includeBuild(source)
    include(":nexus-services-adapter")
}
