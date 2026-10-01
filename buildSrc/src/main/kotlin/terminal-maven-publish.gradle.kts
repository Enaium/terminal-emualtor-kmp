// ==================== terminal-maven-publish ====================
// The Maven publishing convention for every library module: the coordinates,
// the POM and the license/developer metadata live here, so a module only
// declares its description:
//
// ```kotlin
// plugins {
//     alias(libs.plugins.maven.publish)
//     id("terminal-maven-publish")
// }
//
// terminalPublishing {
//     description = "Terminal emulator core: screen buffers, scrollback, ..."
// }
// ```
//
// `./gradlew publishToMavenLocal` installs the module (all targets, with
// sources and javadoc) into ~/.m2/repository under the `cn.enaium.terminal`
// group. `./gradlew publishAndReleaseToMavenCentral` uploads the signed
// publications to the Sonatype Central Portal and releases them; that wiring
// lives in the root build (see below), together with the credentials and
// signing keys it reads from ~/.gradle/gradle.properties.
//
// The configuration goes through Gradle's own publishing API rather than the
// publish plugin's Kotlin API: the plugin is applied from the *root* build's
// classpath (so it can see the Kotlin plugin classes), while this script runs
// from buildSrc's, where the plugin's DSL - including `publishToMavenCentral` -
// is not on the classpath.

import cn.enaium.terminal.build.TerminalPublishingExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

val terminalPublishing = extensions.create("terminalPublishing", TerminalPublishingExtension::class.java)

extensions.configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(project.name)
            // Resolved lazily, so a module may set the description after
            // applying this convention (or leave it empty).
            description.set(terminalPublishing.description)
            url.set("https://github.com/Enaium/terminal-emulator-kmp")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://spdx.org/licenses/MIT.html")
                }
            }
            developers {
                developer {
                    id.set("Enaium")
                    name.set("Enaium")
                    url.set("https://github.com/Enaium")
                }
            }
            scm {
                url.set("https://github.com/Enaium/terminal-emulator-kmp")
                connection.set("scm:git:git@github.com:Enaium/terminal-emulator-kmp.git")
                developerConnection.set("scm:git:git@github.com:Enaium/terminal-emulator-kmp.git")
            }
        }
    }
}
