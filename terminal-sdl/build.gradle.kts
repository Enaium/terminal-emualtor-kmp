import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.maven.publish)
    id("terminal-maven-publish")
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// sdl-kmp / sdl-ttf-kmp publish for desktop, Apple mobile (iOS/tvOS) and
// Android (JVM + NDK) but not watchOS, so the SDL frontend covers exactly
// those targets.
kotlin {
    jvmToolchain(25)

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    android {
        namespace = "cn.enaium.terminal.sdl"
        compileSdk = 37
        minSdk = 24
        compilations.configureEach {
            compileTaskProvider.configure { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
        }
    }

    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()
    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":terminal-core"))
            api(project(":terminal-session"))
            api(libs.sdl.kmp)
            api(libs.sdl.ttf.kmp)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

// ==================== Publishing ====================
terminalPublishing {
    description = "SDL3 + SDL_ttf renderer and widget for the terminal emulator core"
}
