# terminal-emulator-kmp

![](https://img.cdn1.vip/i/6abe8100869fc_1790869760.webp)

A terminal emulator, written as a Kotlin Multiplatform library.

The core (screen, VT/ANSI parser, Unicode) is pure Kotlin and runs on every
platform Kotlin supports except WebAssembly. Platform code is limited to the
pseudo-terminal, and the two shipped frontends render with Dear ImGui
(`imgui-kmp`) or with SDL3 + SDL_ttf.

```
                 ┌────────────────┐   ┌──────────────┐
                 │ terminal-imgui │   │ terminal-sdl │      frontends
                 └───────┬────────┘   └──────┬───────┘
                         │                   │
                 ┌───────▼───────────────────▼───────┐
                 │          terminal-session         │      pty ⇄ parser ⇄ terminal
                 └───┬───────────┬───────────┬───────┘
                     │           │           │
        ┌────────────▼──┐  ┌─────▼───────┐  ┌▼──────────────┐
        │ terminal-pty  │  │terminal-    │  │ terminal-core │
        │ (ConPTY/posix)│  │parser (VT)  │  │ (screen, …)   │
        └───────────────┘  └─────────────┘  └───────┬───────┘
                                                   │
                                          ┌────────▼─────────┐
                                          │ terminal-unicode │
                                          └──────────────────┘
```

| module | contents | platforms |
| --- | --- | --- |
| `terminal-unicode` | UTF-8 codec, East-Asian/emoji cell widths, grapheme clustering | all |
| `terminal-core` | `Terminal` state machine, screen buffers, scrollback, selection, colors, modes, input encoding | all |
| `terminal-parser` | VT/ANSI state machine (CSI, SGR, OSC, DCS), device responses | all |
| `terminal-pty` | `PtyProcess`/`PtyFactory`: POSIX `openpty`+`fork`+`exec`, Windows ConPTY, pty4j on the JVM | desktop, mobile Apple/Android NDK¹ |
| `terminal-session` | pty reader thread → parser → terminal, input helpers | all |
| `terminal-imgui` | ImGui renderer (draw-list batching) + terminal widget | all |
| `terminal-sdl` | SDL3 renderer with an SDL_ttf glyph atlas + terminal widget | desktop, Apple mobile, Android |

¹ A pty needs `fork`/`exec`, which Android and iOS forbid for sandboxed apps.
`terminal-pty` reports `isSupported = false` there and `spawn` throws with a
clear message; the rest of the library works normally.

## Features

- **VT/ANSI**: CUU/CUD/CUF/CUB/CNL/CPL/CHA/CUP/HPA/VPA/HPR/VPR, ED/EL/ECH/ICH/DCH/IL/DL,
  SU/SD, DECSTBM scroll regions, DECSC/DECRC, DECALN, REP, TAB stops (HT/CHT/CBT/HTS/TBC),
  DECSCUSR cursor shapes, origin mode, insert mode, DECAWM.
- **SGR**: attributes (bold/dim/italic/underline + `4:x` variants/strikethrough/inverse/
  hidden/blink), 16/256 colors, true color (`38;2;r;g;b` and `38:2::r:g:b`), underline color.
- **OSC**: window title (0/1/2), palette (4), working directory (7), hyperlinks (8),
  default colors (10/11/12, 110-112), clipboard (52, set and query), shell integration (133).
- **Device responses**: DA1/DA2, DSR, DECRQM, DECRQSS, XTWINOPS, so applications detect
  capabilities instead of falling back to dumb-terminal mode.
- **Screens**: normal screen with scrollback, alternate screen (47/1047/1048/1049) and
  reflow on resize (logical lines are re-wrapped, the cursor keeps its place in the text).
- **Input**: keyboard encoding (application cursor keys, `Alt` as `ESC` prefix, Ctrl codes,
  function keys, modifiers), bracketed paste, focus reporting, mouse reporting
  (X10/VT200/button/any with X10/UTF-8/SGR/urxvt encodings).
- **Unicode**: UTF-8 with incremental decoding, wide characters, combining marks,
  variation selectors, ZWJ emoji, regional indicator flags, DEC special graphics.
- **Selection**: linear, block (Alt+drag), word (double click), line (triple click),
  copy across scrollback and soft-wrapped rows, `Ctrl+Shift+C`/`Ctrl+Shift+V`
  (`Ctrl+C` copies when there is a selection, otherwise it is SIGINT). A click
  without a drag selects nothing; the ends of a selection are inclusive, so a
  one-cell selection copies that character.
- **Performance**: cells live in parallel primitive arrays, scrolled lines are recycled,
  the renderer batches a row into one text run and one quad per background run, and the
  parser never runs on the UI thread.

## Quick start

```bash
# JVM: Dear ImGui + SDL3 renderer, runs the user's shell
./gradlew :examples:imgui:jvmRun

# JVM: SDL3 + SDL_ttf renderer
./gradlew :examples:sdl:jvmRun

# native (macOS/Linux/Windows): self-contained executables
./gradlew :examples:imgui:linkDebugExecutableMacosArm64
./examples/imgui/build/bin/macosArm64/debugExecutable/imgui.kexe

# run a bounded, headless session (CI) and dump the screen it rendered
./gradlew :examples:imgui:jvmRun --args="--frames 300 --shell /bin/sh --screenshot /tmp/term.bmp"
```

## Using the library

```kotlin
// 1. Spawn a shell behind a pty and wire it to a terminal.
val session = TerminalSession.spawn(PtyConfig(columns = 100, rows = 30))
// or: TerminalSession.spawn(PtyConfig(command = listOf("/bin/zsh", "-l")))

// 2. Once per frame (UI thread): drain the pty into the terminal state.
session.pump()
val terminal = session.terminal

// 3. Read the grid.
terminal.lineAt(0).text(0, terminal.columns)   // first visible row
terminal.cursorRow / terminal.cursorColumn     // 0-based
terminal.title / terminal.workingDirectory     // OSC 2 / OSC 7

// 4. Send input.
session.sendKey(TerminalKey.Enter)
session.sendText("ls -la\n")
session.sendPaste(clipboardText)
session.sendMouse(TerminalMouseButton.LEFT, TerminalMouseAction.PRESS, column, row)

// 5. Resize (also informs the child through TIOCSWINSZ / ResizePseudoConsole).
session.resize(120, 40)
```

Feeding bytes without a pty (tests, recordings, embedded shells):

```kotlin
val terminal = Terminal(columns = 80, rows = 24)
val parser = VTParser(terminal)
parser.feed(bytes)                 // bytes → escape sequences → terminal
parser.feed("\u001B[31mred\u001B[0m")
```

### Dear ImGui frontend

```kotlin
// Once, before the font atlas is built:
val fonts = installTerminalFonts(
    ImGui.getIO().fonts,
    TerminalFontSettings(regularPath = "/System/Library/Fonts/SFNSMono.ttf", fallbackPath = cjkFont),
)
// atlas.build(); upload the atlas texture; atlas.setTexID(...)

val terminal = ImGuiTerminal(session, fonts)
// every frame:
terminal.drawWindow("Terminal")            // or terminal.drawChild("term", size)
// forward the two events imgui-kmp does not expose through io:
terminal.textInput(sdlEvent.text)          // SDL_EVENT_TEXT_INPUT
terminal.handleWheel(sdlEvent.y)           // SDL_EVENT_MOUSE_WHEEL
// while terminal.wantsTextInput, call SDL_StartTextInput(windowId)
```

### SDL frontend

```kotlin
SDLTTF.init()
val font = SdlTerminalFont(
    "/System/Library/Fonts/SFNSMono.ttf",
    sizePx = 16f,
    density = window.pixelScale(),   // 2.0 on Retina: rasterize for the framebuffer
).also { it.bind(renderer) }
val terminal = SdlTerminal(session, window, renderer, font)
while (running) {
    while (true) { val event = SDL.pollEvent() ?: break; terminal.handleEvent(event) }
    session.pump()
    terminal.render()   // one SDL_RenderGeometry call for all glyphs
    renderer.present()
}
```

The SDL frontend lays the grid out in the renderer's *physical* pixel space (the
space SDL draws in) and scales the logical mouse coordinates up to match, which is
what keeps the terminal filling the window on a high-DPI display. Pass
`window.pixelScale()` as the font's `density` so glyphs are rasterized for the real
pixel grid instead of being upscaled.

## Design notes

**Threading.** The pty reader runs on a background dispatcher and only pushes byte
chunks into a channel; `TerminalSession.pump()` (called from the UI thread) is the
only place that mutates the terminal. Renderers therefore need no locks, and a slow
frame never blocks the child process.

**Closing the pty.** Quitting must never hang, and macOS makes that surprisingly
hard: `close()` of a pty master blocks until its slave side is gone *and* until a
concurrent `read()` on it returns. The POSIX implementation therefore

1. hangs up the *foreground process group* (`tcgetpgrp` + `killpg`) and then the
   shell, because a shell that started another shell (or a job) keeps the slave
   open long after the direct child is gone,
2. reaps with a bounded `WNOHANG` loop instead of a blocking `waitpid` — a child
   stuck in an uninterruptible kernel call ignores `SIGKILL` until that call
   returns, and the kernel reaps it later,
3. only then closes the master,

and the reader waits for output with a bounded `poll()` rather than parking in
`read()`. Data still wakes the reader immediately, so interactive latency is
unchanged; quitting takes at most a few hundred milliseconds.

**Reflow.** On resize the normal screen joins soft-wrapped rows into logical lines,
re-wraps them at the new width and maps the cursor back through the rewrap, so
`less`/`vim`/shell prompts survive a window resize. Blank rows below the content are
padding: they never push the re-wrapped text into the scrollback, and the window
follows the cursor when the screen gets shorter than the tail it would show. The
alternate screen keeps its top-left corner instead (full-screen applications redraw
themselves).

**Selection anchors.** A `TerminalPosition` holds the absolute `TerminalLine.id`,
which is never reused, so a selection keeps pointing at the same text while output
scrolls or the view moves; when the anchored line is trimmed away the selection
simply stops resolving.

**Grapheme clusters.** A cell stores a base code point plus the cluster's remaining
code points (combining marks, VS16, ZWJ sequences, skin tones, tag characters).
Emoji presentation (`VS16`) widens a cell from one to two columns; regional
indicators pair into a flag cell; DEC special graphics are translated on write.

**Grid alignment.** Text runs are only merged while every glyph in them advances by
exactly one cell, which a monospace face guarantees for ASCII. Anything else — a
symbol, an emoji, a glyph taken from the merged fallback face — is drawn on its own
cell, because its advance may differ and ImGui would shift the rest of the run with
it: the text then drifts away from the cursor, which is positioned by cell index
(and which is why a prompt with icons can look "one character off" in one shell and
fine in another). Pinning `ImFontConfig.glyphMinAdvanceX`/`glyphMaxAdvanceX` instead
makes ImGui drop those glyphs, so it is not an option.

**Rendering.** Both frontends resolve colors through the terminal
(`paletteRgb`/`defaultColor`) so `OSC 4`/`OSC 10-12` restyling works, apply inverse
and `DECSCNM`, draw one background quad per run of equal background, and one text
run per run of equal style. Wide glyphs are drawn individually so the rest of the
row stays aligned. The ImGui renderer additionally caches nothing but the font
metrics; the SDL renderer keeps a glyph atlas in a texture and emits all quads in a
single call.

**Fonts.** A terminal needs a monospaced face; the ImGui frontend measures the cell
from `"M"` and expects every glyph to advance by that width. The default glyph
ranges are `TerminalGlyphRanges.terminal` — Latin, punctuation, arrows, math, box
drawing, block elements, geometric shapes, symbols, kana and CJK — because a
narrower set (for example `chineseFull`, which leaves out box drawing) makes
full-screen applications draw their borders and markers as `?` fallbacks.

`TerminalFontSettings.fallbackPath` merges a second face for the glyphs the main one
lacks. Its ranges default to `TerminalGlyphRanges.fallback`: the symbol blocks, kana
and the 2500 most common Simplified Chinese characters (4 MiB atlas at 1x, 16 MiB at
2x). `fallbackWithIcons` adds emoji and the private use area (Nerd Font, Powerline),
and `terminalWithFallbacks` adds the full CJK and Hangul blocks — that one is the
difference between a few megabytes and tens of megabytes, because ImGui bakes every
requested glyph up front and silently *drops* what does not fit. The SDL frontend
resolves fallbacks per glyph through SDL_ttf and pays nothing up front.

`TerminalFontSettings.density` is the framebuffer scale: the faces are baked at
`sizePx * density` *physical* pixels, and the host sets `ImGuiIO.fontGlobalScale =
1f / density` so everything keeps its logical size (the examples do exactly that).
Do not use `ImFontConfig.rasterizerDensity` for this instead: in ImGui 1.92's
static atlas path it is multiplied by the font's own density, which bakes the atlas
with the density *squared* — a Retina display then needs a 256 MiB atlas that
overflows, and ImGui silently drops the glyphs that no longer fit (they render as
`?`, which is exactly how a missing box-drawing character looks).

**Shell exit.** The examples close when the shell behind the pty exits, once its
last output has been parsed; `TerminalSession.onExit` fires when the reader sees
end-of-stream.

**Escape-sequence coverage.** The parser implements Paul Williams' state machine
(Ground/Escape/CSI/DCS/OSC/SOS/PM/APC) and is fed byte-by-byte, so sequences split
across reads (or UTF-8 characters split across reads) work.

## Publishing

Every library module is published under the `cn.enaium.terminal` group
(`terminal-unicode`, `terminal-core`, `terminal-parser`, `terminal-pty`,
`terminal-session`, `terminal-imgui`, `terminal-sdl`) with the usual Maven
coordinates, Gradle module metadata, sources and javadoc artifacts.

The publishing setup lives in one place, the `terminal-maven-publish` convention
plugin in `buildSrc`: it carries the coordinates, the POM and the
license/developer/SCM metadata, so a module only declares its description.

```kotlin
plugins {
    alias(libs.plugins.maven.publish)   // root classpath: it needs the Kotlin plugin classes
    id("terminal-maven-publish")        // buildSrc: the shared POM
}

terminalPublishing {
    description = "Terminal emulator core: screen buffers, scrollback, ..."
}
```

```bash
./gradlew publishToMavenLocal                 # install every module (all targets) into ~/.m2
./gradlew publishAndReleaseToMavenCentral     # upload the signed publications and release them
```

### Depending on it

The artifacts live on Maven Central (and in `~/.m2` after `publishToMavenLocal`):

```kotlin
repositories {
    mavenCentral()
}
```

In a Kotlin Multiplatform project the root module of each library resolves the
variant for every target (jvm, android, ios/tvos/watchos, macos, linux, mingw).
Depend on it from `commonMain` - or from a single source set, e.g. `jvmMain`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("cn.enaium.terminal:terminal-session:1.0.0")  // pty + parser + core
            implementation("cn.enaium.terminal:terminal-imgui:1.0.0")    // + imgui-kmp widget
            implementation("cn.enaium.terminal:terminal-sdl:1.0.0")      // + sdl-kmp + sdl-ttf-kmp
        }
    }
}
```

A JVM-only project depends on the same coordinates; Gradle picks the jvm variant
from the module metadata:

```kotlin
dependencies {
    implementation("cn.enaium.terminal:terminal-imgui:1.0.0")
}
```

| dependency | brings in | use it for |
| --- | --- | --- |
| `terminal-unicode` | – | cell widths and grapheme clustering on their own |
| `terminal-core` | `terminal-unicode` | the screen state machine, without a pty or a renderer |
| `terminal-parser` | `terminal-core` | feeding bytes into a `Terminal` yourself |
| `terminal-pty` | pty4j on the JVM | a pty/ConPTY process |
| `terminal-session` | core, parser, pty | pty → parser → terminal, plus input helpers |
| `terminal-imgui` | session, imgui-kmp | a Dear ImGui terminal widget |
| `terminal-sdl` | session, sdl-kmp, sdl-ttf-kmp | an SDL3 terminal window |

Every module that applies the publish plugin is wired to the Sonatype Central
Portal: `publishAndReleaseToMavenCentral` uploads the signed publications of all
of them as one deployment and releases it. It reads the Portal token
(`mavenCentralUsername`/`mavenCentralPassword`) and the signing key
(`signing.keyId`/`signing.password`/`signing.secretKeyRingFile`, or the
`signingInMemoryKey*` properties) from `~/.gradle/gradle.properties`.

## Testing

```bash
./gradlew jvmTest                       # every module's tests on the JVM
./gradlew :terminal-session:jvmTest     # shell integration + compatibility suites
./gradlew :terminal-session:macosArm64Test  # the same suites on a native target
```

Tests live with the module they belong to: `terminal-unicode`, `terminal-core` and
`terminal-parser` carry the unit suites, `terminal-imgui` renders real frames headlessly
and inspects the produced vertices, and `terminal-session` spawns real shells and
programs through a real pty.

- `terminal-core`/`terminal-parser`/`terminal-unicode` cover the state machine,
  the escape sequences, reflow, scrollback, selection and input encoding.
- `terminal-imgui` renders real frames headlessly and inspects the produced vertices
  (backgrounds, text, cursor shapes, selection, reverse video).
- `terminal-session` spawns real shells and programs through a real pty and asserts on
  the resulting screen: prompts, `stty size` after a resize, SGR colors, alternate
  screen round trips, scrollback, closing a session whose shell (or its grandchild)
  is still running, and `vim`/`nano`/`less`/`top`/`fish`/`zsh`/`bash`
  (each skipped when the program is not installed).

## License

MIT
