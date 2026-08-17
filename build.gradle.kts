import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "org.lolicode.moemusic"
version = "1.4.0"

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://jitpack.io")
        content { includeGroupByRegex("com\\.github\\.walkyst\\..*") }
    }
    maven {
        name = "Lolicode Releases"
        url = uri("https://maven.lolicode.org/releases")
        content { includeGroupByRegex("org\\.lolicode.*") }
    }
    maven {
        name = "Lolicode Snapshots"
        url = uri("https://maven.lolicode.org/snapshots")
        content { includeGroupByRegex("org\\.lolicode.*") }
    }
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/lolicode-org/MoeMusic")
        credentials {
            username = providers.gradleProperty("gpr.user")
                .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                .orElse("")
                .get()
            password = providers.gradleProperty("gpr.key")
                .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
                .orElse(providers.environmentVariable("PACKAGES_READ_TOKEN"))
                .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                .orElse("")
                .get()
        }
        content { includeGroupByRegex("org\\.lolicode.*") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("org.lolicode.moemusic.terminal.TerminalMainKt")
    applicationDefaultJvmArgs = listOf(
        // 256M is more than enough. By default it will eat ~2G ram.
        "-Xmx256M",
        // LavaPlayer loads native helpers during playback bootstrap. Java 24+ warns on stderr
        // unless native access is explicitly granted before application logging is active.
        "--enable-native-access=ALL-UNNAMED",
    )
}

dependencies {
    implementation("org.lolicode.moemusic:api:2.2.0")
    implementation("org.lolicode.moemusic:core:1.4.0")
    implementation("org.lolicode.moemusic:client-core:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.googlecode.lanterna:lanterna:3.1.5")
    implementation("org.jline:jline-terminal:4.3.1")
    implementation("org.jline:jline-terminal-jni:4.3.1")
    implementation("org.slf4j:slf4j-api:2.0.18")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register("printVersion") {
    doLast {
        println(project.version)
    }
}
