package cn.enaium.terminal.sdl

import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLMouseButton
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLPoint
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.SDLWindowFlags
import cn.enaium.sdl.ttf.SDLTTF
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.pty.PtyProcess
import cn.enaium.terminal.session.TerminalSession
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * High-DPI behaviour, verified headlessly.
 *
 * A Retina window is a framebuffer twice the size of the window, and a render
 * target of double size reproduces exactly that: the renderer's output is twice
 * the logical window while SDL's mouse coordinates stay logical. Drawing in
 * logical units then fills only the top-left quarter of the output - the bug
 * these tests pin down.
 */
class SdlTerminalHighDpiTest {

    private var initialized = false

    @BeforeTest
    fun setUp() {
        SDL.setMainReady()
        initialized = SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
        if (!initialized) {
            // Headless CI: the dummy driver still provides a window, a software
            // renderer and render targets.
            SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
            initialized = SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)
        }
        check(initialized) { "SDL init failed: ${SDL.error()}" }
        check(SDLTTF.init()) { "SDL_ttf init failed: ${SDLTTF.error()}" }
    }

    @AfterTest
    fun tearDown() {
        if (initialized) {
            SDLTTF.quit()
            SDL.quit()
        }
    }

    @Test
    fun laysOutInPhysicalPixels() {
        withTerminal { fixture ->
            fixture.widget.render()
            // Twice the logical window, twice the cells.
            assertEquals(
                (fixture.target.x / fixture.font.cellWidth).toInt(),
                fixture.widget.columns,
                "columns cover the whole framebuffer",
            )
            assertEquals(
                (fixture.target.y / fixture.font.cellHeight).toInt(),
                fixture.widget.rows,
                "rows cover the whole framebuffer",
            )
        }
    }

    @Test
    fun drawsIntoTheWholeFramebuffer() {
        withTerminal { fixture ->
            fixture.widget.terminal.write("X")
            fixture.widget.render()

            val drawnWidth = (fixture.widget.columns * fixture.font.cellWidth).toInt()
            val drawnHeight = (fixture.widget.rows * fixture.font.cellHeight).toInt()
            assertTrue(
                drawnWidth > fixture.logical.x,
                "the grid must extend past the logical window ($drawnWidth > ${fixture.logical.x})",
            )

            val surface = fixture.widget.renderer.renderReadPixels(null) ?: error("read pixels failed")
            surface.use {
                assertEquals(fixture.target.x, it.width)
                assertEquals(fixture.target.y, it.height)

                // A pixel inside the grid but *beyond* the logical window: it is
                // only painted when the layout uses physical pixels.
                val x = drawnWidth - 4
                val y = drawnHeight / 2
                val actual = readPixel(it.pixels, it.pitch, x, y)
                val expected = SDL.mapRGBA(it.format, 0x10, 0x10, 0x14, 0xFF)
                assertEquals(
                    expected.toUInt().toString(16),
                    actual.toUInt().toString(16),
                    "pixel ($x, $y) is the terminal background",
                )
            }
        }
    }

    @Test
    fun mouseCoordinatesAreScaledToPhysicalPixels() {
        withTerminal { fixture ->
            fixture.widget.render()
            val pty = fixture.widget.session.pty as RecordingPty
            // The application asks for SGR mouse reports.
            fixture.widget.terminal.setPrivateMode(1000, true)
            fixture.widget.terminal.setPrivateMode(1006, true)

            // A click at the logical point (100, 50) is at physical (200, 100).
            fixture.widget.handleEvent(
                SDLEvent.MouseButton(
                    timestamp = 0uL,
                    windowId = 0,
                    down = true,
                    button = SDLMouseButton.LEFT,
                    clicks = 1,
                    x = 100f,
                    y = 50f,
                ),
            )

            val column = (200f / fixture.font.cellWidth).toInt()
            val row = (100f / fixture.font.cellHeight).toInt()
            assertEquals(
                "\u001B[<0;${column + 1};${row + 1}M",
                pty.writtenText(),
                "the click reports the cell under the physical pixel",
            )
        }
    }

    // ==================== helpers ====================

    private class Fixture(
        val widget: SdlTerminal,
        val font: SdlTerminalFont,
        val logical: SDLPoint,
        val target: SDLPoint,
    )

    /**
     * Runs [body] with a logical window, a render target twice its size (the
     * framebuffer of a 2x display) and a font rasterized for that scale.
     */
    private fun withTerminal(logicalSize: SDLPoint = SDLPoint(400, 200), body: (Fixture) -> Unit) {
        SDL.createWindow("terminal-sdl test", logicalSize.x, logicalSize.y, SDLWindowFlags.HIDDEN).use { window ->
            SDL.createRenderer(window).use { renderer ->
                val target = SDLPoint(logicalSize.x * 2, logicalSize.y * 2)
                renderer.createTexture(
                    format = SDLPixelFormat.RGBA32,
                    access = SDLTextureAccess.TARGET,
                    width = target.x,
                    height = target.y,
                ).use { texture ->
                    renderer.target = texture
                    val font = SdlTerminalFont(testFontPath(), sizePx = 10f, density = 2f)
                    font.bind(renderer)
                    val session = TerminalSession(Terminal(80, 24), RecordingPty())
                    val widget = SdlTerminal(session, window, renderer, font)
                    try {
                        body(Fixture(widget, font, logicalSize, target))
                    } finally {
                        session.close()
                        widget.close()
                    }
                }
            }
        }
    }

    /** Reads one pixel as the raw 32-bit value the surface format stores. */
    private fun readPixel(pixels: ByteArray, pitch: Int, x: Int, y: Int): Int {
        val offset = y * pitch + x * 4
        return (pixels[offset].toInt() and 0xFF) or
            ((pixels[offset + 1].toInt() and 0xFF) shl 8) or
            ((pixels[offset + 2].toInt() and 0xFF) shl 16) or
            ((pixels[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun testFontPath(): String {
        val candidates = listOf(
            "/System/Library/Fonts/SFNSMono.ttf",
            "/System/Library/Fonts/Supplemental/Menlo.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
            "C:\\Windows\\Fonts\\consola.ttf",
        )
        return candidates.firstOrNull { File(it).isFile }
            ?: error("no monospace TTF found for this platform")
    }

    /** A pty that records what the widget sends and reports end-of-stream. */
    private class RecordingPty : PtyProcess {
        private val buffer = ArrayList<Byte>()
        override val pid: Int get() = 0
        override val devicePath: String get() = ""
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

        override fun write(data: ByteArray, offset: Int, length: Int) {
            for (i in offset until offset + length) buffer.add(data[i])
        }

        override fun resize(columns: Int, rows: Int) = Unit
        override fun isAlive(): Boolean = true
        override fun terminate(force: Boolean) = Unit
        override fun close() = Unit

        fun writtenText(): String = buffer.toByteArray().decodeToString()
    }
}
