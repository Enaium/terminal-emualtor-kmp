package cn.enaium.terminal.example.sdl

import java.io.File

/** Picks a monospace TTF for the host OS. */
private fun jvmTerminalFont(): String? {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    val candidates = when {
        os.contains("mac") -> listOf(
            "/System/Library/Fonts/SFNSMono.ttf",
            "/System/Library/Fonts/Supplemental/Menlo.ttf",
            "/System/Library/Fonts/Monaco.ttf",
        )

        os.contains("win") -> listOf("C:\\Windows\\Fonts\\consola.ttf", "C:\\Windows\\Fonts\\lucon.ttf")
        else -> listOf(
            "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
        )
    }
    return candidates.firstOrNull { File(it).isFile }
}

/** Picks a CJK-capable TTF for the host OS (SDL_ttf resolves it per glyph). */
private fun jvmFallbackFont(): String? {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    val candidates = when {
        os.contains("mac") -> listOf(
            "/System/Library/Fonts/Hiragino Sans GB.ttc",
            "/System/Library/Fonts/PingFang.ttc",
        )

        os.contains("win") -> listOf("C:\\Windows\\Fonts\\msyh.ttc")
        else -> listOf(
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        )
    }
    return candidates.firstOrNull { File(it).isFile }
}

/** `--frames N` limits the run to N frames (headless CI); `--shell CMD` overrides the shell. */
fun main(args: Array<String>) {
    var frames = Int.MAX_VALUE
    var shell: List<String> = emptyList()
    var screenshot: String? = null
    var fallback: String? = jvmFallbackFont()
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
    println("terminal-emulator-kmp sdl example (frames=$frames)")
    runSdlTerminalExample(frames, shell, screenshot, jvmTerminalFont(), fallback)
}
