package cn.enaium.terminal.sdl

import cn.enaium.sdl.SDLWindow

/**
 * The framebuffer scale of this window: physical pixels per logical point.
 *
 * It is 2.0 on a Retina display and 1.0 on a standard one. Pass it as
 * [SdlTerminalFont.density] so glyphs are rasterized for the real pixel grid
 * instead of being upscaled (and blurry); [SdlTerminal] derives the same factor
 * from the renderer it draws with.
 */
fun SDLWindow.pixelScale(): Float {
    val logical = size
    if (logical.x <= 0 || logical.y <= 0) return 1f
    return (sizeInPixels.x.toFloat() / logical.x).coerceAtLeast(0.01f)
}
