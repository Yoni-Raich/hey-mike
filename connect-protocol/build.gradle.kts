plugins { kotlin("jvm"); kotlin("plugin.serialization"); `maven-publish` }
group = "dev.heymike.connect"
version = "1.0.0"
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; withSourcesJar() }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
publishing {
    publications { create<MavenPublication>("maven") { from(components["java"]); pom { name.set("Mike Connect protocol"); licenses { license { name.set("Apache License 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } } } } }
    repositories { maven { url = uri(rootProject.layout.projectDirectory.dir("artifacts/connect-maven")) } }
}
