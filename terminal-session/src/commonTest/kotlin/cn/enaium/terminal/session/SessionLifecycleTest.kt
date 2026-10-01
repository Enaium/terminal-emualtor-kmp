package cn.enaium.terminal.session

import cn.enaium.terminal.pty.PtyConfig
import cn.enaium.terminal.pty.ptyFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Closing a session must never block, in particular when the child already
 * exited on its own (a pty master closed while another thread is parked in
 * `read()` blocks on macOS, which used to hang the whole application).
 */
class SessionLifecycleTest {

    @Test
    fun closingASessionWhoseShellExitedDoesNotBlock() {
        if (!ptyFactory().isSupported) return
        val session = TerminalSession.spawn(
            PtyConfig(command = listOf("sh", "-c", "echo bye; exit 0"), columns = 80, rows = 24),
        )
        var exited = false
        session.onExit = { exited = true }

        var waited = 0L
        while (waited < 5_000 && !exited) {
            session.pump()
            runBlocking { delay(20) }
            waited += 20
        }
        assertTrue(exited, "the reader noticed the shell exit")
        // The reader's last chunk is already in the queue: drain it before
        // looking at the screen.
        session.pump()
        assertTrue(screenContains(session, "bye"), "the output before the exit was parsed:\n${screen(session)}")

        val mark = TimeSource.Monotonic.markNow()
        session.close()
        val millis = mark.elapsedNow().inWholeMilliseconds
        assertTrue(millis < 5_000, "close() returned promptly, took ${millis}ms")
    }

    @Test
    fun closingASessionWithAGrandchildHoldingThePtyDoesNotBlock() {
        if (!ptyFactory().isSupported) return
        // `sh -c "sleep 300; exit 0"` forks a grandchild that inherits the pty
        // and outlives the shell. Closing the master while something still
        // holds the slave open used to block forever on macOS, because the
        // reader was parked in read() at the same time.
        val session = TerminalSession.spawn(
            PtyConfig(command = listOf("sh", "-c", "sleep 300; exit 0"), columns = 80, rows = 24),
        )
        var waited = 0L
        while (waited < 2_000 && session.terminal.lineAt(0).isBlank) {
            session.pump()
            runBlocking { delay(20) }
            waited += 20
        }

        val mark = TimeSource.Monotonic.markNow()
        session.close()
        val millis = mark.elapsedNow().inWholeMilliseconds
        assertTrue(millis < 5_000, "close() returned promptly, took ${millis}ms")
    }

    @Test
    fun closingASessionWithALiveShellDoesNotBlock() {
        if (!ptyFactory().isSupported) return
        val session = TerminalSession.spawn(PtyConfig(columns = 80, rows = 24))
        var waited = 0L
        while (waited < 5_000 && !screenContains(session, "$")) {
            session.pump()
            runBlocking { delay(20) }
            waited += 20
        }

        val mark = TimeSource.Monotonic.markNow()
        session.close()
        val millis = mark.elapsedNow().inWholeMilliseconds
        assertTrue(millis < 5_000, "close() returned promptly, took ${millis}ms")
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
}
