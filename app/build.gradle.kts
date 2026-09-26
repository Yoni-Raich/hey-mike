import java.util.Properties
plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose") }
val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
configurations.configureEach { exclude(group = "org.jetbrains", module = "annotations-java5") }
val versionCodeOverride = project.findProperty("versionCodeOverride")?.toString()?.toIntOrNull()
val versionNameOverride = project.findProperty("versionNameOverride")?.toString()
val nightlyKeystorePath = System.getenv("DEV_NIGHTLY_KEYSTORE")?.takeIf { it.isNotBlank() }
android {
    namespace = "dev.androidagent.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.androidagent.app"
        minSdk = 30
        targetSdk = 35
        versionCode = versionCodeOverride ?: appVersion.getProperty("versionCode").toInt()
        versionName = versionNameOverride ?: appVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Package native Codex binaries for both the ARM64 phone and the local
        // x86_64 emulator so emulator tests do not use ARM translation.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    flavorDimensions += "channel"
    productFlavors {
        create("prod") { dimension = "channel"; resValue("string", "app_name", "Hey Mike") }
        create("dev") { dimension = "channel"; applicationIdSuffix = ".dev"; resValue("string", "app_name", "Hey Mike Dev") }
    }
    signingConfigs {
        if (nightlyKeystorePath != null) {
            create("devNightly") {
                storeFile = file(nightlyKeystorePath)
                storePassword = System.getenv("DEV_NIGHTLY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DEV_NIGHTLY_KEY_ALIAS")
                keyPassword = System.getenv("DEV_NIGHTLY_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            if (nightlyKeystorePath != null) signingConfig = signingConfigs.getByName("devNightly")
        }
        release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main") {
        jniLibs.srcDir(layout.buildDirectory.dir("generated/runtime/jniLibs"))
        assets.srcDir(layout.buildDirectory.dir("generated/runtime/assets"))
    }
    packaging {
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" }
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/**", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES") }
    }
}
val prepareCodexRuntime by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    commandLine(python, rootProject.file("tools/prepare_runtime.py").absolutePath)
    inputs.file(rootProject.file("tools/prepare_runtime.py"))
    outputs.dir(layout.buildDirectory.dir("generated/runtime"))
}
tasks.named("preBuild") { dependsOn(prepareCodexRuntime) }
dependencies {
    implementation(project(":core")); implementation(project(":workspace")); implementation(project(":runtime"))
    implementation(project(":engine-codex")); implementation(project(":adb")); implementation(project(":device-tools")); implementation(project(":overlay")); implementation(project(":voice")); implementation(project(":a11y")); implementation(project(":automations"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2")
    annotationProcessor("io.noties:prism4j-bundler:2.0.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
