plugins { id("com.android.library"); kotlin("android"); kotlin("plugin.serialization"); id("maven-publish") }
group = "dev.heymike.connect"
version = "1.0.0"
android {
    namespace = "dev.heymike.connect"
    compileSdk = 35
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    api(project(":connect-protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
afterEvaluate {
    publishing {
        publications { create<MavenPublication>("release") { from(components["release"]); pom { name.set("Mike Connect Android SDK"); licenses { license { name.set("Apache License 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } } } } }
        repositories { maven { url = uri(rootProject.layout.projectDirectory.dir("artifacts/connect-maven")) } }
    }
}
