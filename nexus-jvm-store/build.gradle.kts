plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies { api(project(":nexus-core")); implementation("org.xerial:sqlite-jdbc:3.46.1.0"); testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit(); testLogging { events("failed", "skipped") } }
// JVM compatibility/backup implementation and historical white-box tests are trusted friends.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(":nexus-core:jar")
    compilerOptions.freeCompilerArgs.add("-Xfriend-paths=${project(":nexus-core").layout.buildDirectory.get().asFile}/libs/nexus-core-${project.version}.jar")
}
