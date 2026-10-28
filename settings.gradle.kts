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

// Explicit synthetic test packages only. No production dependency or default activation.
if (providers.gradleProperty("withPrefusionLab").orNull == "true") {
    check(providers.gradleProperty("withAndroid").orNull == "true")
    include(":nexus-prefusion-provider", ":nexus-prefusion-lab")
    project(":nexus-prefusion-provider").projectDir = file("labs/saf-provider")
    project(":nexus-prefusion-lab").projectDir = file("labs/android-lab")
}
