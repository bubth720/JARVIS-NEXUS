plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":nexus-jvm-store")) }
application { mainClass.set("fr.jarvis.nexus.cli.MainKt") }
