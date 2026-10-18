import java.util.Properties
plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "dev.androidagent.workspace"
    compileSdk = 35
    defaultConfig { minSdk = 30; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/file-delta/jniLibs"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/file-delta/assets"))
}
val prepareFileDelta by tasks.registering(Exec::class) {
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: Properties().apply { val local = rootProject.file("local.properties"); if (local.exists()) local.inputStream().use(::load) }.getProperty("sdk.dir")
        ?: error("Set ANDROID_HOME for the native delta build")
    workingDir(rootProject.projectDir)
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        rootProject.file("tools/prepare_file_delta.py").absolutePath, "--sdk", sdk,
        "--output", layout.buildDirectory.dir("generated/file-delta").get().asFile.absolutePath)
    inputs.file(rootProject.file("tools/prepare_file_delta.py"))
    inputs.file("src/main/cpp/delta_jni.c")
    outputs.dir(layout.buildDirectory.dir("generated/file-delta/jniLibs"))
    outputs.dir(layout.buildDirectory.dir("generated/file-delta/assets"))
}
tasks.named("preBuild") { dependsOn(prepareFileDelta) }
tasks.withType<Test>().configureEach {
    project.findProperty("deltaNativeLibraryPath")?.let { jvmArgs("-Djava.library.path=$it") }
    project.findProperty("deltaFixtureDir")?.let { systemProperty("mike.delta.fixtureDir", it.toString()) }
}
dependencies {
    api(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    // Real SQLite for the session schema migration tests.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
