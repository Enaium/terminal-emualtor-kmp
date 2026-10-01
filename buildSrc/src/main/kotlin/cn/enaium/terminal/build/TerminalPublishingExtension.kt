package cn.enaium.terminal.build

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Per-module input for the `terminal-maven-publish` convention.
 *
 * ```kotlin
 * terminalPublishing {
 *     description = "Terminal emulator core: screen buffers, scrollback, ..."
 * }
 * ```
 */
open class TerminalPublishingExtension @Inject constructor(objects: ObjectFactory) {

    /** Human readable module description used in the published POM. */
    val description: Property<String> = objects.property(String::class.java)
}
