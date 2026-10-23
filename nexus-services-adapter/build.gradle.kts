plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":nexus-core"))
    implementation("fr.jarvis.services:desktop:1.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.pdfbox:pdfbox:2.0.33")
}
application { mainClass.set("fr.jarvis.nexus.adapters.ImportCliKt") }
tasks.test { useJUnit(); testLogging { events("passed", "failed", "skipped") } }
