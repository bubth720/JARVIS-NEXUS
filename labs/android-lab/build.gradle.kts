plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "fr.jarvis.nexus.prefusion.lab"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    // Only the opt-in test APK: exercise modern platform behavior without the
    // obsolete-target UI warning. Android API24 remains the supported minimum.
    defaultConfig { minSdk = 24; targetSdk = 35; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":nexus-android-store"))
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
