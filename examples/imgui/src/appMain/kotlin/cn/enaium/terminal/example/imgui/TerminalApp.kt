package cn.enaium.terminal.example.imgui

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLKeymod
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags
import cn.enaium.terminal.imgui.ImGuiTerminal
import cn.enaium.terminal.imgui.TerminalFontSettings
import cn.enaium.terminal.imgui.installTerminalFonts
import cn.enaium.terminal.pty.PtyConfig
import cn.enaium.terminal.session.TerminalSession

/**
 * The font files the demo loads for the terminal (null keeps ImGui's built-in font).
 *
 * [fallback] is merged into the regular face for the glyphs it lacks (CJK,
 * symbols, emoji). It is deliberately left unset by the examples: merging a CJK
 * font makes ImGui bake every CJK glyph into the atlas (a 4096x4096 texture
 * with a 14px face), which is slow to build and to upload - pass one here when
 * CJK text matters, ideally at a smaller size.
 */
data class TerminalFontPaths(
    val regular: String?,
    val bold: String? = null,
    val italic: String? = null,
    val fallback: String? = null,
    /**
     * Symbol faces merged into the regular face after [fallback]. One face
     * rarely covers everything a shell prints (a CJK face has no braille, a
     * symbol face no ideographs), and ImGui draws every code point none of the
     * merged faces provides with its `?` glyph.
     */
    val extras: List<String> = emptyList(),
)

/**
 * Runs the Dear ImGui terminal demo: an SDL3 window, the imgui backends and a
 * shell attached to a pty, rendered by [ImGuiTerminal].
 *
 * Pass [frames] to exit after a number of frames (useful for headless CI runs),
 * [shellCommand] to run something other than the user's shell, and [fontPaths]
 * to point the terminal at the host's monospace font.
 */
fun runTerminalExample(
    frames: Int = Int.MAX_VALUE,
    shellCommand: List<String> = emptyList(),
    screenshotPath: String? = null,
    fontPaths: TerminalFontPaths = TerminalFontPaths(regular = null),
) {
    SDL.setMainReady()
    if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
        // Headless CI (or a plain SSH session) has no display: fall back to the
        // dummy driver so the example still exercises the whole pipeline.
        SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
        if (SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            println("video init fell back to the dummy driver - running headless")
        } else {
            error("SDL_Init failed: ${SDL.error()}")
        }
    }

    SDL.createWindow(
        title = "terminal-emulator-kmp - imgui",
        width = 1100,
        height = 700,
        flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
    ).use { window ->
        SDL.createRenderer(window).use { renderer ->
            val context = ImGui.createContext()
            try {
                val backend = ImGuiSdlBackend(window)
                val rendererBackend = ImGuiSdlRendererBackend(renderer)
                backend.init()
                ImGui.styleColorsDark()

                val density = maxOf(backend.framebufferScale.x, backend.framebufferScale.y, 1f)
                // The faces are baked at `sizePx * density` physical pixels, so
                // the UI is scaled down by the same factor: glyphs stay crisp on
                // a Retina display while everything keeps its logical size.
                ImGui.getIO().fontGlobalScale = 1f / density
                val atlas = ImGui.getIO().fonts
                val fonts = installTerminalFonts(
                    atlas,
                    TerminalFontSettings(
                        regularPath = fontPaths.regular,
                        boldPath = fontPaths.bold,
                        italicPath = fontPaths.italic,
                        fallbackPath = fontPaths.fallback,
                        extraFallbackPaths = fontPaths.extras,
                        sizePx = 14f,
                        density = density,
                        // The default ranges cover box drawing, block elements,
                        // arrows, shapes and CJK; narrowing them here is what
                        // made full-screen applications render as `?`.
                    ),
                )
                // ImGui's own widgets need a face as well, baked for the same
                // physical pixel grid.
                atlas.addFontDefault(ImFontConfig(sizePixels = 13f * density))
                check(atlas.build()) { "font atlas build failed" }
                val texData = atlas.getTexDataAsRGBA32()
                atlas.setTexID(rendererBackend.uploadFontTexture(texData.pixels, texData.width, texData.height))

                val session = TerminalSession.spawn(
                    PtyConfig(columns = 100, rows = 30, command = shellCommand),
                )
                val terminal = ImGuiTerminal(session, fonts)

                var running = true
                var frame = 0
                var textInputEnabled = false
                while (running && frame < frames) {
                    while (true) {
                        val event = SDL.pollEvent() ?: break
                        when (event) {
                            is SDLEvent.Quit -> running = false
                            is SDLEvent.Window -> {
                                if (event.type == SDLWindowEventType.CLOSE_REQUESTED) running = false
                                backend.processEvent(event)
                            }

                            // The terminal consumes text and wheel events itself:
                            // imgui-kmp does not expose io.InputQueueCharacters /
                            // io.MouseWheel, so they never reach the widget through
                            // ImGui's own queues.
                            is SDLEvent.Key -> {
                                // Cmd+Q quits the demo even when the platform
                                // does not translate it into a quit event.
                                if (event.down && event.modifiers and SDLKeymod.GUI != 0 &&
                                    event.keycode == SDLKeycode.Q
                                ) {
                                    running = false
                                }
                                backend.processEvent(event)
                            }

                            is SDLEvent.TextInput -> terminal.textInput(event.text)
                            is SDLEvent.MouseWheel -> {
                                terminal.handleWheel(event.y)
                                backend.processEvent(event)
                            }

                            else -> backend.processEvent(event)
                        }
                    }

                    // Everything the pty produced since the last frame.
                    val parsedOutput = session.pump()

                    // A terminal closes when its shell exits: once the process
                    // is gone *and* its last output has been parsed, stop.
                    if (session.pty != null && !parsedOutput && !session.isRunning) {
                        println("shell exited")
                        running = false
                    }

                    // SDL only delivers text events while text input is active;
                    // the terminal wants them whenever it has the focus.
                    val wantsText = terminal.wantsTextInput
                    if (wantsText != textInputEnabled) {
                        if (wantsText) SDL.startTextInput(window.id) else SDL.stopTextInput(window.id)
                        textInputEnabled = wantsText
                    }

                    backend.newFrame()
                    drawTerminal(terminal)
                    ImGui.render()

                    renderer.drawColor = SDLColor(16, 16, 20, 255)
                    renderer.clear()
                    rendererBackend.renderDrawData(ImGui.getDrawData())
                    renderer.present()
                    frame++
                }

                session.close()
                rendererBackend.close()

                // A bounded run is what CI uses; printing the screen proves the
                // whole pty -> parser -> terminal -> renderer pipeline ran.
                if (screenshotPath != null) {
                    renderer.renderReadPixels(null)?.use { surface ->
                        println("screenshot ${surface.width}x${surface.height} -> $screenshotPath")
                        surface.saveBMP(screenshotPath)
                    }
                }
                if (frames != Int.MAX_VALUE) dumpTerminal(session)
            } finally {
                ImGui.destroyContext(context)
            }
        }
    }
    SDL.quit()
}

/** Draws the terminal as a borderless window covering the whole SDL window. */
private fun drawTerminal(terminal: ImGuiTerminal) {
    val display = ImGui.getIO().displaySize
    ImGui.setNextWindowPos(ImVec2(0f, 0f))
    ImGui.setNextWindowSize(display)
    ImGui.setNextWindowBgAlpha(1f)
    terminal.drawWindow(
        title = "terminal",
        flags = ImGuiWindowFlags.NO_TITLE_BAR or ImGuiWindowFlags.NO_RESIZE or ImGuiWindowFlags.NO_MOVE or
            ImGuiWindowFlags.NO_SCROLLBAR or ImGuiWindowFlags.NO_SAVED_SETTINGS or
            ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS or ImGuiWindowFlags.NO_NAV_FOCUS,
    )
}

/** Prints the terminal's visible screen (used by the bounded, headless runs). */
private fun dumpTerminal(session: TerminalSession) {
    val terminal = session.terminal
    println("--- terminal screen ${terminal.columns}x${terminal.rows} ---")
    for (row in 0 until terminal.rows) {
        val text = terminal.lineAt(row).text(0, terminal.columns)
        if (text.isNotBlank()) println(text)
    }
    println(
        "--- end (scrollback=${terminal.scrollbackSize}, title=${terminal.title}, " +
            "cursor=${terminal.cursorRow},${terminal.cursorColumn} visible=${terminal.modes.cursorVisible} " +
            "style=${terminal.cursorStyle} scrolledBack=${terminal.isScrolledBack}, " +
            "time=${ImGui.getTime()} phase=${ImGui.getTime() % 1.06}) ---",
    )
}
