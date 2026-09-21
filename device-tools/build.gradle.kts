plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "dev.androidagent.devicetools"
    compileSdk = 35
    defaultConfig { minSdk = 30; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core"))
    implementation(project(":adb"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    // Android provides XmlPullParser at runtime; kxml supplies it to local JVM tests.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
