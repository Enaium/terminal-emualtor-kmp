import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "cn.enaium.terminal"
    version = "1.0.0"
}

// The library modules apply the Maven publish plugin, and the convention script
// in buildSrc configures their POMs. That script cannot reach the plugin's own
// Kotlin API (the plugin sits on *this* build's classpath, not buildSrc's), so
// the Central publishing is wired up here instead: `publishToMavenCentral`
// uploads every module's signed publications to the Sonatype Central Portal and
// releases the deployment. The examples apply no publish plugin and are skipped.
//
// `./gradlew publishToMavenCentral` needs the Portal token
// (`mavenCentralUsername` / `mavenCentralPassword`) and the signing key
// (`signing.*`) in ~/.gradle/gradle.properties.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure(MavenPublishBaseExtension::class.java) {
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()
        }
    }
}
