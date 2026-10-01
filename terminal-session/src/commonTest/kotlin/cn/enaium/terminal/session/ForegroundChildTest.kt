package cn.enaium.terminal.session

import cn.enaium.terminal.pty.PtyConfig
import cn.enaium.terminal.pty.defaultShellCommand
import cn.enaium.terminal.pty.ptyFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Reproduces: start a shell, run another shell inside it, then quit. */
class ForegroundChildTest {

    @Test
    fun closingWithASecondShellInTheForegroundDoesNotBlock() {
        if (!ptyFactory().isSupported) return
        val session = TerminalSession.spawn(PtyConfig(columns = 100, rows = 30))
        var waited = 0L
        while (waited < 5_000 && !screen(session).contains("$")) {
            session.pump(); runBlocking { delay(20) }; waited += 20
        }
        println("shell ready after ${waited}ms")

        session.sendText("fish\n")
        waited = 0
        while (waited < 10_000 && !screen(session).contains("Welcome to fish")) {
            session.pump(); runBlocking { delay(20) }; waited += 20
        }
        println("fish started after ${waited}ms; running=${session.isRunning}")
        assertTrue(screen(session).contains("Welcome to fish"), "fish is in the foreground:\n${screen(session)}")

        val mark = TimeSource.Monotonic.markNow()
        session.close()
        println("close() took ${mark.elapsedNow().inWholeMilliseconds}ms")
        assertTrue(mark.elapsedNow().inWholeMilliseconds < 5_000, "close() returned promptly")
    }

    private fun screen(session: TerminalSession): String {
        val terminal = session.terminal
        val sb = StringBuilder()
        for (row in 0 until terminal.rows) sb.append(terminal.lineAt(row).text(0, terminal.columns)).append('\n')
        return sb.toString()
    }
}
