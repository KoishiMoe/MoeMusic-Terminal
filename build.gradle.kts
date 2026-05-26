import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    application
}

group = "org.lolicode.moemusic"
version = "1.1.0"

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
        // LavaPlayer loads native helpers during playback bootstrap. Java 24+ warns on stderr
        // unless native access is explicitly granted before application logging is active.
        "--enable-native-access=ALL-UNNAMED",
    )
}

dependencies {
    implementation("org.lolicode.moemusic:api:1.0.0")
    implementation("org.lolicode.moemusic:core:1.2.0")
    implementation("org.lolicode.moemusic:client-core:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.googlecode.lanterna:lanterna:3.1.5")
    implementation("org.jline:jline-terminal:4.1.2")
    implementation("org.jline:jline-terminal-jni:4.1.2")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
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
