// Shared plugin classloader, as in NEX-06A. Android modules still require the
// explicit withAndroid flag; neither SDK use nor an APK is enabled by default.
plugins { kotlin("jvm") version "2.0.21" apply false; kotlin("android") version "2.0.21" apply false; id("com.android.library") version "8.7.3" apply false }
allprojects { group = "fr.jarvis.nexus"; version = "0.1.0" }
