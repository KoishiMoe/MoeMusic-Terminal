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
            name = "Lolicode on Codeberg"
            url = uri("https://codeberg.org/api/packages/lolicode/maven")
            content { includeGroupByRegex("org\\.lolicode.*") }
        }
    }
}

rootProject.name = "moemusic-terminal"

val sharedBuildDir = file("../shared")
if (sharedBuildDir.resolve("settings.gradle.kts").isFile) {
    includeBuild(sharedBuildDir)
}
