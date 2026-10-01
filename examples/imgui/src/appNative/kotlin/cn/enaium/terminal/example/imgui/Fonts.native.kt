@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.terminal.example.imgui

import platform.posix.F_OK
import platform.posix.access

/**
 * Picks a monospace font for the host OS.
 *
 * No CJK fallback by default: ImGui bakes every glyph of a merged face into the
 * atlas up front, and the native binding copies that atlas pixel by pixel, so a
 * merged CJK face costs ~32 MiB and several seconds of startup here. Set
 * `TERMINAL_KMP_FALLBACK=/System/Library/Fonts/Hiragino Sans GB.ttc` (or pass
 * `--fallback` to the JVM entry point, where the copy is a memcpy) to opt in.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun nativeFontPaths(): TerminalFontPaths {
    val candidates = when (kotlin.native.Platform.osFamily) {
        kotlin.native.OsFamily.MACOSX -> TerminalFontPaths(
            regular = "/System/Library/Fonts/SFNSMono.ttf",
            italic = "/System/Library/Fonts/SFNSMonoItalic.ttf",
        )

        kotlin.native.OsFamily.WINDOWS -> TerminalFontPaths(
            regular = "C:\\Windows\\Fonts\\consola.ttf",
            bold = "C:\\Windows\\Fonts\\consolab.ttf",
            italic = "C:\\Windows\\Fonts\\consolai.ttf",
        )

        else -> TerminalFontPaths(
            regular = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
            bold = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
            italic = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Oblique.ttf",
        )
    }
    // ImGui silently produces an empty face for a path that does not exist
    // (text would simply not render), so verify them here.
    return TerminalFontPaths(
        regular = candidates.regular?.takeIf { exists(it) },
        bold = candidates.bold?.takeIf { exists(it) },
        italic = candidates.italic?.takeIf { exists(it) },
        fallback = candidates.fallback?.takeIf { exists(it) },
    )
}

private fun exists(path: String): Boolean = access(path, F_OK) == 0
