plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "fr.jarvis.nexus.android"
    compileSdk = 35
    defaultConfig { minSdk = 24; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}
dependencies {
    api(project(":nexus-core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    maxHeapSize = "1g"
}
