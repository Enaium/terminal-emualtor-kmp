@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cn.enaium.terminal.example.imgui.nativeFontPaths
import cn.enaium.terminal.example.imgui.runTerminalExample
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Native entry point (Kotlin/Native requires the executable entry in the
 * default package). `TERMINAL_KMP_FRAMES` limits the run for headless checks,
 * `TERMINAL_KMP_SHELL` runs another program instead of the user's shell.
 */
fun main() {
    val frames = getenv("TERMINAL_KMP_FRAMES")?.toKString()?.toIntOrNull() ?: Int.MAX_VALUE
    val fallback = getenv("TERMINAL_KMP_FALLBACK")?.toKString()?.takeIf { it.isNotBlank() }
    val shell = getenv("TERMINAL_KMP_SHELL")?.toKString()?.takeIf { it.isNotBlank() }?.let { listOf(it) }
    println("terminal-emulator-kmp imgui example (frames=$frames)")
    val paths = nativeFontPaths().let { if (fallback != null) it.copy(fallback = fallback) else it }
    runTerminalExample(frames = frames, shellCommand = shell ?: emptyList(), fontPaths = paths)
}
