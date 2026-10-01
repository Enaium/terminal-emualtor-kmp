package cn.enaium.terminal.example.sdl

import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLKeycode
import cn.enaium.sdl.SDLKeymod
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags
import cn.enaium.sdl.ttf.SDLTTF
import cn.enaium.terminal.pty.PtyConfig
import cn.enaium.terminal.sdl.SdlTerminal
import cn.enaium.terminal.sdl.SdlTerminalFont
import cn.enaium.terminal.sdl.pixelScale
import cn.enaium.terminal.session.TerminalSession

/**
 * Runs the SDL_ttf terminal demo: an SDL3 window with a shell attached to a
 * pty, rendered by [SdlTerminal] (glyph atlas + one `SDL_RenderGeometry` call
 * per frame).
 */
fun runSdlTerminalExample(
    frames: Int = Int.MAX_VALUE,
    shellCommand: List<String> = emptyList(),
    screenshotPath: String? = null,
    fontPath: String? = null,
    fallbackFontPath: String? = null,
) {
    SDL.setMainReady()
    if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
        SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
        if (SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            println("video init fell back to the dummy driver - running headless")
        } else {
            error("SDL_Init failed: ${SDL.error()}")
        }
    }
    check(SDLTTF.init()) { "SDL_ttf init failed: ${SDLTTF.error()}" }

    SDL.createWindow(
        title = "terminal-emulator-kmp - sdl",
        width = 1100,
        height = 700,
        flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
    ).use { window ->
        SDL.createRenderer(window).use { renderer ->
            val resolvedFontPath = fontPath ?: error("no monospace TTF found for this platform")
            // Rasterize at the framebuffer scale, otherwise glyphs are upscaled
            // (and blurry) on a Retina display. SDL_ttf resolves the fallback
            // per glyph, so a CJK face costs nothing up front here.
            val font = SdlTerminalFont(
                resolvedFontPath,
                sizePx = 16f,
                density = window.pixelScale(),
                fallbackPath = fallbackFontPath,
            )
            font.bind(renderer)

            val session = TerminalSession.spawn(
                PtyConfig(columns = 100, rows = 30, command = shellCommand),
            )
            val terminal = SdlTerminal(session, window, renderer, font)
            // Without this SDL never delivers SDL_EVENT_TEXT_INPUT, and typed
            // characters would never reach the application.
            SDL.startTextInput(window.id)

            var running = true
            var frame = 0
            while (running && frame < frames) {
                while (true) {
                    val event = SDL.pollEvent() ?: break
                    when (event) {
                        is SDLEvent.Quit -> running = false
                        is SDLEvent.Key ->
                            // Cmd+Q quits the demo even when the platform does
                            // not translate it into a quit event.
                            if (event.down && event.modifiers and SDLKeymod.GUI != 0 &&
                                event.keycode == SDLKeycode.Q
                            ) {
                                running = false
                            } else {
                                terminal.handleEvent(event)
                            }

                        is SDLEvent.Window ->
                            if (event.type == SDLWindowEventType.CLOSE_REQUESTED) running = false
                            else terminal.handleEvent(event)

                        else -> terminal.handleEvent(event)
                    }
                }
                val parsedOutput = session.pump()
                // A terminal closes when its shell exits.
                if (session.pty != null && !parsedOutput && !session.isRunning) {
                    println("shell exited")
                    running = false
                }
                terminal.render()
                renderer.present()
                frame++
            }

            SDL.stopTextInput(window.id)
            if (screenshotPath != null) {
                renderer.renderReadPixels(null)?.use { surface ->
                    println("screenshot ${surface.width}x${surface.height} -> $screenshotPath")
                    surface.saveBMP(screenshotPath)
                }
            }
            if (frames != Int.MAX_VALUE) {
                println("--- terminal screen ${terminal.columns}x${terminal.rows} ---")
                for (row in 0 until terminal.terminal.rows) {
                    val text = terminal.terminal.lineAt(row).text(0, terminal.terminal.columns)
                    if (text.isNotBlank()) println(text)
                }
                println("--- end ---")
            }
            session.close()
            terminal.close()
            SDLTTF.quit()
        }
    }
    SDL.quit()
}
