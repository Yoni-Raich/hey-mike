plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "dev.androidagent.remote"
    compileSdk = 35
    defaultConfig { minSdk = 30; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core"))
    api(project(":engine-codex"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Pure-Java SSH client: Android networking and DNS, no native binary to stage.
    implementation("com.github.mwiede:jsch:2.28.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // An in-process SSH server, so the client is tested against real SSH.
    testImplementation("org.apache.sshd:sshd-core:2.15.0")
}
