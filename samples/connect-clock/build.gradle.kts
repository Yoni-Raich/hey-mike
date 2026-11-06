plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.heymike.connect.sample"
    compileSdk = 35
    defaultConfig { applicationId = "dev.heymike.connect.sample"; minSdk = 30; targetSdk = 35; versionCode = 1; versionName = "1.0.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":connect-sdk"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
