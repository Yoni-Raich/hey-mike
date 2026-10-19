plugins { id("com.android.library"); kotlin("android"); kotlin("plugin.serialization") }
android {
    namespace = "dev.androidagent.connect"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { api(project(":core")); api(project(":connect-sdk")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"); testImplementation("junit:junit:4.13.2") }
