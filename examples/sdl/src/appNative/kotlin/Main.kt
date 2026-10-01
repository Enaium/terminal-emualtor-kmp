@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cn.enaium.terminal.example.sdl.nativeFallbackFont
import cn.enaium.terminal.example.sdl.nativeTerminalFont
import cn.enaium.terminal.example.sdl.runSdlTerminalExample
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** Native entry point; `TERMINAL_KMP_FRAMES` limits the run for headless checks. */
fun main() {
    val frames = getenv("TERMINAL_KMP_FRAMES")?.toKString()?.toIntOrNull() ?: Int.MAX_VALUE
    val fallback = getenv("TERMINAL_KMP_FALLBACK")?.toKString()?.takeIf { it.isNotBlank() }
        ?: nativeFallbackFont()
    val shell = getenv("TERMINAL_KMP_SHELL")?.toKString()?.takeIf { it.isNotBlank() }?.let { listOf(it) }
    println("terminal-emulator-kmp sdl example (frames=$frames)")
    runSdlTerminalExample(
        frames = frames,
        shellCommand = shell ?: emptyList(),
        fontPath = nativeTerminalFont(),
        fallbackFontPath = fallback,
    )
}
