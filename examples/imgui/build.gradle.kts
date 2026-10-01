import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Runnable Dear ImGui terminal demo. The JVM entry point is the fastest way to
// try the library (`./gradlew :examples:imgui:jvmRun`); the native targets
// produce self-contained executables.
//
// The app sources are compiled into the concrete targets rather than a shared
// common source set: imgui-kmp's SDL backend lives in its per-target artifacts,
// so it is invisible to a common metadata compilation.
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
        mainRun { mainClass = "cn.enaium.terminal.example.imgui.Main_jvmKt" }
    }

    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    // Executables are only linked for the host OS: cross-linking (e.g. a Linux
    // binary from macOS) needs a full cross toolchain and sysroot.
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
                implementation(libs.imgui.kmp)
                implementation(project(":terminal-session"))
                implementation(project(":terminal-imgui"))
            }
        }
        nativeTargets.forEach { target ->
            getByName(target) {
                dependencies {
                    implementation(libs.sdl.kmp)
                    implementation(libs.imgui.kmp)
                    implementation(project(":terminal-session"))
                    implementation(project(":terminal-imgui"))
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
