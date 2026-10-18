plugins { id("com.android.application") version "8.7.3" }
android {
    namespace = "dev.androidagent.qa.fixture"
    compileSdk = 35
    defaultConfig { applicationId = "dev.androidagent.qa.fixture"; minSdk = 30; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
