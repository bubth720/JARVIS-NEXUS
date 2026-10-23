plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit(); testLogging { events("passed", "failed", "skipped") } }
