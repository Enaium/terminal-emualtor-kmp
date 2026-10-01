@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.terminal.example.sdl

import platform.posix.F_OK
import platform.posix.access

/** Picks a monospace TTF for the host OS. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun nativeTerminalFont(): String? {
    val candidates = when (kotlin.native.Platform.osFamily) {
        kotlin.native.OsFamily.MACOSX -> listOf(
            "/System/Library/Fonts/SFNSMono.ttf",
            "/System/Library/Fonts/Supplemental/Menlo.ttf",
            "/System/Library/Fonts/Monaco.ttf",
        )

        kotlin.native.OsFamily.WINDOWS -> listOf("C:\\Windows\\Fonts\\consola.ttf", "C:\\Windows\\Fonts\\lucon.ttf")
        else -> listOf(
            "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
        )
    }
    return candidates.firstOrNull { access(it, F_OK) == 0 }
}

/** Picks a CJK-capable TTF for the host OS (SDL_ttf resolves it per glyph). */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun nativeFallbackFont(): String? {
    val candidates = when (kotlin.native.Platform.osFamily) {
        kotlin.native.OsFamily.MACOSX -> listOf(
            "/System/Library/Fonts/Hiragino Sans GB.ttc",
            "/System/Library/Fonts/PingFang.ttc",
        )

        kotlin.native.OsFamily.WINDOWS -> listOf("C:\\Windows\\Fonts\\msyh.ttc")
        else -> listOf(
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        )
    }
    return candidates.firstOrNull { access(it, F_OK) == 0 }
}
