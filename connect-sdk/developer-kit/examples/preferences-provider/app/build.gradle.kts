// SPDX-License-Identifier: Apache-2.0
plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.heymike.connect.example"
    compileSdk = 35
    defaultConfig { applicationId = "dev.heymike.connect.example.preferences"; minSdk = 24; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("dev.heymike.connect:connect-sdk:1.0.0") }
