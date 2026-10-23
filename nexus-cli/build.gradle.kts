plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":nexus-core")) }
application { mainClass.set("fr.jarvis.nexus.cli.MainKt") }
