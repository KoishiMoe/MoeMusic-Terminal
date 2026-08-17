pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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
    }
}

rootProject.name = "moemusic-terminal"

val sharedBuildDir = file("../shared")
if (sharedBuildDir.resolve("settings.gradle.kts").isFile) {
    includeBuild(sharedBuildDir)
}
