plugins { id("com.android.application") }
android {
    namespace = "fr.jarvis.nexus.prefusion.provider"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "fr.jarvis.nexus.prefusion.provider"; minSdk = 24; targetSdk = 35; versionCode = 1; versionName = "0.1-lab" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    lint { abortOnError = true }
}
