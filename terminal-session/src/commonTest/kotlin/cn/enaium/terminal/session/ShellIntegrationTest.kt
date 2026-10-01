package cn.enaium.terminal.session

import cn.enaium.terminal.core.TerminalAttributes
import cn.enaium.terminal.core.TerminalColor
import cn.enaium.terminal.core.TerminalLine
import cn.enaium.terminal.pty.PtyConfig
import cn.enaium.terminal.pty.defaultShellCommand
import cn.enaium.terminal.pty.ptyFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests: a real shell runs behind a real pty, its bytes go through
 * `VTParser` into a `Terminal`, and the assertions look at the resulting
 * screen - the same path the frontends take.
 *
 * They need a working pty, so they are skipped on platforms where
 * `PtyFactory.isSupported` is false (Android, iOS, tvOS, watchOS).
 */
class ShellIntegrationTest {

    @Test
    fun shellPrintsPromptAndRunsCommands() {
        if (!ptyFactory().isSupported) return
        withSession { session ->
            assertTrue(pumpUntil(session) { screenContains(session, "$") }, "the shell printed a prompt")
            // The command line is echoed, so the marker appears twice only once
            // the command actually ran and produced output.
            session.sendText("echo terminal-kmp-integration\n")
            assertTrue(
                pumpUntil(session) { countOccurrences(session, "terminal-kmp-integration") >= 2 },
                "the shell printed the command output:\n${screen(session)}",
            )
        }
    }

    @Test
    fun resizeIsPropagatedToTheApplication() {
        if (!ptyFactory().isSupported) return
        withSession { session ->
            assertTrue(pumpUntil(session) { screenContains(session, "$") }, "prompt")
            session.resize(90, 25)
            // stty reports the pty's size, which proves the resize reached the
            // kernel side (TIOCSWINSZ / ResizePseudoConsole).
            session.sendText("stty size\n")
            assertTrue(
                pumpUntil(session) { screenContains(session, "25 90") },
                "the child saw the new window size:\n${screen(session)}",
            )
            assertEquals(90, session.terminal.columns)
            assertEquals(25, session.terminal.rows)
        }
    }

    @Test
    fun resizingWhileAtAPromptKeepsTheShellInSync() {
        if (!ptyFactory().isSupported) return
        withSession { session ->
            assertTrue(pumpUntil(session) { atPrompt(session) }, "prompt")
            // A screenful of output, so the re-wrap has real content to move.
            session.sendText("seq 1 60\n")
            // The echoed command line also contains "60", so wait for the
            // output's own last line.
            assertTrue(
                pumpUntil(session) { screen(session).lines().any { it.trim() == "60" } },
                "the output arrived:\n${screen(session)}",
            )
            assertTrue(pumpUntil(session) { atPrompt(session) }, "back at a prompt")

            // A window drag: the widget resizes on every cell it gains or loses.
            for (width in intArrayOf(80, 120, 60, 100, 40, 100)) {
                session.resize(width, 30)
                session.pump()
            }

            // The numbers are still one contiguous, in-order run: no row was
            // duplicated or dropped by the re-wrap.
            val numbers = screen(session).lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                .map { it.toInt() }
            assertTrue(numbers.isNotEmpty(), "the output is still on screen:\n${screen(session)}")
            assertTrue(
                numbers == numbers.sorted().distinct(),
                "the output is in order and not duplicated:\n${screen(session)}",
            )

            // The cursor sits on the prompt line, so the shell's next output
            // starts where the shell thinks it does.
            val terminal = session.terminal
            val cursorLine = terminal.lineAt(terminal.cursorRow).text(0, terminal.columns)
            assertTrue(cursorLine.contains("$"), "the cursor is on the prompt line, not '${cursorLine.trim()}'")
            assertEquals(1, countOccurrences(session, "$ "), "one prompt line, not a wall of redrawn ones:\n${screen(session)}")

            session.sendText("echo RESIZE-OK\n")
            assertTrue(
                pumpUntil(session) { countOccurrences(session, "RESIZE-OK") >= 2 },
                "the shell still runs commands after the resizes:\n${screen(session)}",
            )
        }
    }

    @Test
    fun narrowingTheWindowKeepsTheStartOfALongLine() {
        if (!ptyFactory().isSupported) return
        withSession { session ->
            assertTrue(pumpUntil(session) { atPrompt(session) }, "prompt")
            // A long line that soft-wraps, with blank rows below it. Re-wrapping
            // it into more rows must not push its own beginning off screen. The
            // marker is spelled with an escape so only the output prints it.
            session.sendText("printf 'B\\105GIN'; printf 'x%.0s' \$(seq 1 300); echo\n")
            assertTrue(
                pumpUntil(session) { screen(session).contains("BEGIN") },
                "the output arrived:\n${screen(session)}",
            )
            assertTrue(pumpUntil(session) { atPrompt(session) }, "back at a prompt")

            session.resize(40, 30)
            session.pump()
            assertTrue(
                screen(session).contains("BEGIN"),
                "the start of the long line is still on screen:\n${screen(session)}",
            )
        }
    }

    @Test
    fun colorsAndAttributesReachTheScreen() {
        if (!ptyFactory().isSupported) return
        withSession { session ->
            assertTrue(pumpUntil(session) { screenContains(session, "$") }, "prompt")
            session.sendText("printf '\\033[1;31mRED-TEXT\\033[0m\\n'\n")
            // The echoed command line is plain text, so a bold red cell can only
            // come from the escape sequences the command emitted.
            assertTrue(
                pumpUntil(session) { hasBoldRedCell(session) },
                "the screen has a bold, red cell:\n${screen(session)}",
            )
        }
    }

    @Test
    fun alternateScreenIsEnteredAndLeft() {
        if (!ptyFactory().isSupported) return
        withSession { session ->
            assertTrue(pumpUntil(session) { screenContains(session, "$") }, "prompt")
            session.sendText("echo NORMAL-MARKER\n")
            assertTrue(pumpUntil(session) { countOccurrences(session, "NORMAL-MARKER") >= 2 }, "normal screen content")

            session.sendText("printf '\\033[?1049h\\033[2J\\033[HALT-SCREEN'\n")
            assertTrue(pumpUntil(session) { session.terminal.isAlternateScreen }, "alternate screen")
            assertTrue(
                pumpUntil(session) { screenContains(session, "ALT-SCREEN") },
                "the alternate screen shows its own content",
            )
            assertTrue(
                !screenContains(session, "NORMAL-MARKER"),
                "the alternate screen is a separate buffer:\n${screen(session)}",
            )

            session.sendText("printf '\\033[?1049l'\n")
            assertTrue(pumpUntil(session) { !session.terminal.isAlternateScreen }, "back on the normal screen")
            assertTrue(
                pumpUntil(session) { screenContains(session, "NORMAL-MARKER") },
                "the normal screen still has its old content:\n${screen(session)}",
            )
        }
    }

    @Test
    fun scrollbackKeepsTheOutputOfAFullScreenOfLines() {
        if (!ptyFactory().isSupported) return
        withSession { session ->
            assertTrue(pumpUntil(session) { screenContains(session, "$") }, "prompt")
            session.sendText("seq 1 200\n")
            assertTrue(
                pumpUntil(session, timeoutMillis = 15_000) { session.terminal.scrollbackSize > 0 },
                "lines scrolled into the scrollback:\n${screen(session)}",
            )
            assertTrue(pumpUntil(session, timeoutMillis = 15_000) { screenContains(session, "200") }, "seq finished")
            val scrolled = session.terminal.scrollbackSize
            assertTrue(scrolled > 100, "most of the 200 lines scrolled off, was $scrolled")

            session.terminal.scrollView(Int.MAX_VALUE)
            assertTrue(session.terminal.isScrolledBack)
            assertTrue(screenContains(session, "1"), "the oldest lines are reachable:\n${screen(session)}")
        }
    }

    @Test
    fun vimStartsOnTheAlternateScreenAndQuits() {
        if (!ptyFactory().isSupported) return
        if (!commandExists("vim")) return
        withSession(command = listOf("sh", "-c", "vim -u NONE -i NONE -n")) { session ->
            assertTrue(
                pumpUntil(session, timeoutMillis = 20_000) { session.terminal.isAlternateScreen },
                "vim took over the screen",
            )
            session.sendText(":q!\r")
            assertTrue(
                pumpUntil(session, timeoutMillis = 20_000) { !session.terminal.isAlternateScreen },
                "vim left the alternate screen:\n${screen(session)}",
            )
        }
    }

    // ==================== helpers ====================

    private fun withSession(
        command: List<String> = emptyList(),
        body: (TerminalSession) -> Unit,
    ) {
        val session = TerminalSession.spawn(
            PtyConfig(command = command.ifEmpty { defaultShellCommand() }, columns = 100, rows = 30),
        )
        try {
            body(session)
        } finally {
            session.close()
        }
    }

    /** True while the shell waits at its prompt: the cursor sits on it. */
    private fun atPrompt(session: TerminalSession): Boolean {
        val terminal = session.terminal
        return terminal.lineAt(terminal.cursorRow).text(0, terminal.columns).contains("$")
    }

    private fun screen(session: TerminalSession): String {
        val terminal = session.terminal
        val sb = StringBuilder()
        for (row in 0 until terminal.rows) {
            sb.append(terminal.lineAt(row).text(0, terminal.columns)).append('\n')
        }
        return sb.toString()
    }

    private fun screenContains(session: TerminalSession, needle: String): Boolean = screen(session).contains(needle)

    private fun countOccurrences(session: TerminalSession, needle: String): Int {
        val text = screen(session)
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun hasBoldRedCell(session: TerminalSession): Boolean {
        val terminal = session.terminal
        for (row in 0 until terminal.rows) {
            val line: TerminalLine = terminal.lineAt(row)
            for (column in 0 until terminal.columns) {
                if (line.codepointAt(column) == 0) continue
                val attributes = line.attributesAt(column)
                val foreground: TerminalColor = line.foregroundAt(column)
                if (attributes.bits and TerminalAttributes.BOLD != 0 && foreground.isIndexed && foreground.index == 1) {
                    return true
                }
            }
        }
        return false
    }

    /** Runs the shell's `command -v` to find out whether a program exists. */
    private fun commandExists(name: String): Boolean {
        val session = TerminalSession.spawn(
            PtyConfig(command = listOf("sh", "-c", "command -v $name || true"), columns = 80, rows = 10),
        )
        return try {
            pumpUntil(session, timeoutMillis = 5_000) { screenContains(session, "/$name") }
        } finally {
            session.close()
        }
    }

    /** Pumps the session until [condition] holds or the timeout expires. */
    private fun pumpUntil(
        session: TerminalSession,
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean,
    ): Boolean {
        var waited = 0L
        while (waited <= timeoutMillis) {
            session.pump()
            if (condition()) return true
            runBlocking { delay(POLL_INTERVAL_MILLIS) }
            waited += POLL_INTERVAL_MILLIS
        }
        session.pump()
        return condition()
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 20L
    }
}
