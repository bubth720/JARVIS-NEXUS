pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "jarvis-nexus"
include(":nexus-core", ":nexus-cli")
