package cn.enaium.terminal.session

import cn.enaium.terminal.core.TerminalKey
import cn.enaium.terminal.pty.PtyConfig
import cn.enaium.terminal.pty.ptyFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compatibility checks against the programs that expose terminal bugs:
 * full-screen editors and pagers (alternate screen, scroll regions, cursor
 * addressing, SGR, Ctrl keys) and interactive shells.
 *
 * Every case is skipped when the program is not installed, so the suite runs
 * anywhere without failing for environmental reasons.
 */
class CompatibilityTest {

    @Test
    fun fishRunsAndPrints() = runIfInstalled("fish", listOf("fish", "-c", "echo FISH-OK")) { session ->
        assertTrue(pumpUntil(session) { screenContains(session, "FISH-OK") }, "fish output:\n${screen(session)}")
    }

    @Test
    fun zshRunsAndPrints() = runIfInstalled("zsh", listOf("zsh", "-c", "echo ZSH-OK")) { session ->
        assertTrue(pumpUntil(session) { screenContains(session, "ZSH-OK") }, "zsh output:\n${screen(session)}")
    }

    @Test
    fun bashRunsAndPrints() = runIfInstalled("bash", listOf("bash", "-c", "echo BASH-OK")) { session ->
        assertTrue(pumpUntil(session) { screenContains(session, "BASH-OK") }, "bash output:\n${screen(session)}")
    }

    @Test
    fun lessPagesAndQuits() = runIfInstalled(
        name = "less",
        command = listOf("sh", "-c", "printf 'PAGER-LINE-1\\nPAGER-LINE-2\\n' > /tmp/terminal-kmp-pager.txt && less /tmp/terminal-kmp-pager.txt"),
        // `LESS=FRX` (a common default, and what this development machine has)
        // disables paging and the alternate screen, so the pager gets its own
        // flags; `less` also only pages when its input is a file.
        environment = mapOf("LESS" to "-R"),
    ) { session ->
        assertTrue(
            pumpUntil(session) { session.terminal.isAlternateScreen },
            "less took over the screen:\n${screen(session)}",
        )
        assertTrue(
            pumpUntil(session) { screenContains(session, "PAGER-LINE-1") },
            "less drew its first line:\n${screen(session)}",
        )
        session.sendKey(TerminalKey.Character('q'))
        assertTrue(
            pumpUntil(session) { !session.isRunning },
            "less quit:\n${screen(session)}",
        )
    }

    @Test
    fun nanoStartsAndQuitsWithCtrlX() = runIfInstalled("nano", listOf("nano", "-n")) { session ->
        assertTrue(
            pumpUntil(session, timeoutMillis = 15_000) { session.terminal.isAlternateScreen },
            "nano took over the screen",
        )
        // Ctrl+X is what the keyboard encoder must turn into 0x18.
        session.sendKey(TerminalKey.Character('x'), ctrl = true)
        assertTrue(
            pumpUntil(session, timeoutMillis = 15_000) { !session.terminal.isAlternateScreen },
            "nano left the alternate screen:\n${screen(session)}",
        )
    }

    @Test
    fun topRunsAndQuits() = runIfInstalled("top", listOf("top")) { session ->
        assertTrue(
            pumpUntil(session, timeoutMillis = 15_000) { screenContains(session, "PID") },
            "top drew its header:\n${screen(session)}",
        )
        session.sendKey(TerminalKey.Character('q'))
        assertTrue(pumpUntil(session, timeoutMillis = 15_000) { !session.isRunning }, "top quit")
    }

    @Test
    fun htopRunsAndQuits() = runIfInstalled("htop", listOf("htop")) { session ->
        assertTrue(
            pumpUntil(session, timeoutMillis = 15_000) { session.terminal.isAlternateScreen },
            "htop took over the screen",
        )
        session.sendKey(TerminalKey.Character('q'))
        assertTrue(pumpUntil(session, timeoutMillis = 15_000) { !session.isRunning }, "htop quit")
    }

    @Test
    fun pythonRunsAndPrints() = runIfInstalled("python3", listOf("python3", "-c", "print('PY-OK')")) { session ->
        assertTrue(pumpUntil(session) { screenContains(session, "PY-OK") }, "python output:\n${screen(session)}")
    }

    @Test
    fun nodeRunsAndPrints() = runIfInstalled("node", listOf("node", "-e", "console.log('NODE-OK')")) { session ->
        assertTrue(pumpUntil(session) { screenContains(session, "NODE-OK") }, "node output:\n${screen(session)}")
    }

    @Test
    fun tmuxStartsASessionAndDetaches() = runIfInstalled("tmux", listOf("sh", "-c", "tmux -f /dev/null new-session -A -s terminal-kmp-demo")) { session ->
        // tmux repaints the whole screen through the alternate screen and
        // scroll regions; if that works, the terminal is VT-compatible enough
        // for the multiplexer.
        assertTrue(
            pumpUntil(session, timeoutMillis = 20_000) { session.terminal.isAlternateScreen },
            "tmux took over the screen:\n${screen(session)}",
        )
        session.sendText("exit\r")
        assertTrue(
            pumpUntil(session, timeoutMillis = 20_000) { !session.isRunning },
            "the tmux session ended:\n${screen(session)}",
        )
    }

    // ==================== helpers ====================

    private fun runIfInstalled(
        name: String,
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        body: (TerminalSession) -> Unit,
    ) {
        if (!ptyFactory().isSupported) return
        if (!commandExists(name)) {
            println("skipping $name: not installed")
            return
        }
        val session = TerminalSession.spawn(
            PtyConfig(command = command, columns = 100, rows = 30, environment = environment),
        )
        try {
            body(session)
        } finally {
            session.close()
        }
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
