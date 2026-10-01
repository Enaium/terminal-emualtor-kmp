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
        namespace = "cn.enaium.terminal.pty"
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
    mingwX64 {
        compilations.getByName("main").cinterops.create("conpty") {
            defFile(project.file("src/nativeInterop/cinterop/conpty.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
            compilerOpts("-D_WIN32_WINNT=0x0A00", "-DNTDDI_VERSION=0x0A000006", "-DUNICODE", "-D_UNICODE")
        }
    }
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
        // Apple mobile and Android NDK targets cannot fork/exec, so they only
        // carry an unsupported factory; Windows goes through ConPTY.
        val appleMobileMain by creating {
            dependsOn(nativeMain.get())
        }
        getByName("iosMain").dependsOn(appleMobileMain)
        getByName("tvosMain").dependsOn(appleMobileMain)
        getByName("watchosMain").dependsOn(appleMobileMain)

        // The POSIX implementation is compiled into the concrete desktop
        // targets instead of a shared intermediate source set: a shared set
        // also gets a *common metadata* compilation, which has no access to
        // platform.posix.
        getByName("macosMain").kotlin.srcDir("src/posixDesktopMain/kotlin")
        getByName("linuxMain").kotlin.srcDir("src/posixDesktopMain/kotlin")

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(libs.pty4j)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

// ==================== Publishing ====================
terminalPublishing {
    description = "Pseudo-terminal processes: POSIX openpty/fork/exec, Windows ConPTY and pty4j on the JVM"
}
