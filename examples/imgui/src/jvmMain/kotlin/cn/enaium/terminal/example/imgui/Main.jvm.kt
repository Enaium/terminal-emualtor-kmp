package cn.enaium.terminal.example.imgui

import java.io.File

/**
 * Picks a monospace font for the host OS, plus a CJK fallback.
 *
 * The JVM atlas upload is a memcpy, so merging a CJK face (which bakes every
 * CJK glyph into the atlas: 32 MiB and ~0.4s here) is affordable; the native
 * build copies the atlas pixel by pixel and skips it by default.
 */
private fun jvmFontPaths(): TerminalFontPaths {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    val candidates = when {
        os.contains("mac") -> TerminalFontPaths(
            regular = "/System/Library/Fonts/SFNSMono.ttf",
            italic = "/System/Library/Fonts/SFNSMonoItalic.ttf",
            fallback = "/System/Library/Fonts/Hiragino Sans GB.ttc",
        )

        os.contains("win") -> TerminalFontPaths(
            regular = "C:\\Windows\\Fonts\\consola.ttf",
            bold = "C:\\Windows\\Fonts\\consolab.ttf",
            italic = "C:\\Windows\\Fonts\\consolai.ttf",
            fallback = "C:\\Windows\\Fonts\\msyh.ttc",
        )

        else -> TerminalFontPaths(
            regular = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
            bold = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
            italic = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Oblique.ttf",
            fallback = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        )
    }
    // ImGui silently produces an empty face for a path that does not exist
    // (text would simply not render), so verify them here.
    return TerminalFontPaths(
        regular = candidates.regular?.takeIf { File(it).isFile },
        bold = candidates.bold?.takeIf { File(it).isFile },
        italic = candidates.italic?.takeIf { File(it).isFile },
        fallback = candidates.fallback?.takeIf { File(it).isFile },
    )
}

/** `--frames N` limits the run to N frames (headless CI); `--shell CMD` overrides the shell. */
fun main(args: Array<String>) {
    var frames = Int.MAX_VALUE
    var shell: List<String> = emptyList()
    var screenshot: String? = null
    var fallback: String? = null
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--frames" -> {
                frames = args.getOrNull(index + 1)?.toIntOrNull() ?: Int.MAX_VALUE
                index++
            }

            "--shell" -> {
                shell = args.getOrNull(index + 1)?.let { listOf(it) } ?: emptyList()
                index++
            }

            "--screenshot" -> {
                screenshot = args.getOrNull(index + 1)
                index++
            }

            "--fallback" -> {
                fallback = args.getOrNull(index + 1)
                index++
            }
        }
        index++
    }
    println("terminal-emulator-kmp imgui example (frames=$frames)")
    val paths = jvmFontPaths().let { if (fallback != null) it.copy(fallback = fallback) else it }
    runTerminalExample(frames, shell, screenshot, fontPaths = paths)
}
