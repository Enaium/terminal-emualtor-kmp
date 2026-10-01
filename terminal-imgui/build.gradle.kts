import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.maven.publish)
    id("terminal-maven-publish")
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(25)

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    android {
        namespace = "cn.enaium.terminal.imgui"
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
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()
    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":terminal-core"))
            api(project(":terminal-parser"))
            api(project(":terminal-session"))
            api(libs.imgui.kmp)
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
    description = "Dear ImGui renderer and widget for the terminal emulator core"
}
