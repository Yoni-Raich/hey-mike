plugins { id("com.android.library"); kotlin("android"); id("maven-publish") }
group = "dev.heymike.connect"
version = "1.0.0"
android {
    namespace = "dev.heymike.connect.testing"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies { api(project(":connect-sdk")) }
afterEvaluate {
    publishing {
        publications { create<MavenPublication>("release") { from(components["release"]) } }
        repositories { maven { url = uri(rootProject.layout.projectDirectory.dir("artifacts/connect-maven")) } }
    }
}
