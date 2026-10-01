plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
}

// No plugin dependencies: the conventions here configure plugins that the
// modules apply from the root build's classpath (a plugin on both classpaths
// makes Gradle refuse to resolve it).
