import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Runnable SDL_ttf terminal demo (`./gradlew :examples:sdl:jvmRun`). The app
// sources are compiled into the concrete targets because sdl-kmp publishes its
// SDL3 bindings per target (they are not part of a common metadata compilation).
val desktopTargets = listOf(
    "jvmMain",
    "macosArm64Main",
    "macosX64Main",
    "linuxX64Main",
    "linuxArm64Main",
    "mingwX64Main",
)

val nativeTargets = listOf(
    "macosArm64Main",
    "macosX64Main",
    "linuxX64Main",
    "linuxArm64Main",
    "mingwX64Main",
)

kotlin {
    jvmToolchain(25)

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        mainRun { mainClass = "cn.enaium.terminal.example.sdl.Main_jvmKt" }
    }

    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    // Executables are only linked for the host OS: cross-linking needs a full
    // cross toolchain and sysroot.
    val hostOs = org.gradle.internal.os.OperatingSystem.current()
    if (hostOs.isMacOsX) {
        macosArm64 { binaries.executable() }
        macosX64 { binaries.executable() }
    }
    if (hostOs.isLinux) {
        linuxX64 { binaries.executable() }
        linuxArm64 { binaries.executable() }
    }
    if (hostOs.isWindows) {
        mingwX64 { binaries.executable() }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        desktopTargets.forEach { target ->
            getByName(target).kotlin.srcDir("src/appMain/kotlin")
        }
        nativeTargets.forEach { target ->
            getByName(target).kotlin.srcDir("src/appNative/kotlin")
        }
        getByName("jvmMain") {
            dependencies {
                implementation(libs.sdl.kmp)
                implementation(libs.sdl.ttf.kmp)
                implementation(project(":terminal-session"))
                implementation(project(":terminal-sdl"))
            }
        }
        nativeTargets.forEach { target ->
            getByName(target) {
                dependencies {
                    implementation(libs.sdl.kmp)
                    implementation(libs.sdl.ttf.kmp)
                    implementation(project(":terminal-session"))
                    implementation(project(":terminal-sdl"))
                }
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

tasks.withType(JavaExec::class.java).configureEach {
    if (OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}
