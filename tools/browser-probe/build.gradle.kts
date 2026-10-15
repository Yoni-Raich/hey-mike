plugins {
    id("com.android.application") version "8.7.3"
    id("com.android.library") version "8.7.3" apply false
    kotlin("android") version "2.1.0"
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
}
android {
    namespace = "dev.androidagent.browserprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.androidagent.browserprobe"
        minSdk = 30; targetSdk = 35
        versionCode = (findProperty("probeVersionCode") as? String)?.toInt() ?: 2
        versionName = "1.1"
    }
    buildTypes { release { isDebuggable = false; signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":web"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
