pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "terminal-emulator"

include(":terminal-unicode")
include(":terminal-core")
include(":terminal-parser")
include(":terminal-pty")
include(":terminal-session")
include(":terminal-imgui")
include(":terminal-sdl")

include(":examples:imgui")
include(":examples:sdl")
