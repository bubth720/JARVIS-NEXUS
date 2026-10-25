pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "jarvis-nexus"
include(":nexus-core", ":nexus-jvm-store", ":nexus-cli")
// Optional build-time adapter, pinned to the delivered SERVICES extraction capability.
providers.gradleProperty("servicesSource").orNull?.let { source ->
    val validation = ProcessBuilder("python3", file("tools/prepare-services.py").absolutePath, source, "--verify").inheritIO().start().waitFor()
    check(validation == 0) { "SERVICES source provenance check failed" }
    includeBuild(source)
    include(":nexus-services-adapter")
}

if (providers.gradleProperty("withAndroid").orNull == "true") include(":nexus-android-store")
