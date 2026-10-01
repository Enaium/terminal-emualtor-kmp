package cn.enaium.terminal.pty

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PtyTest {

    @Test
    fun defaultShellCommandIsNotEmpty() {
        assertTrue(defaultShellCommand().isNotEmpty(), "default shell command must not be empty")
    }

    @Test
    fun spawnEchoesCommandOutput() {
        val factory = ptyFactory()
        if (!factory.isSupported) return

        val pty = factory.spawn(PtyConfig(command = listOf("sh", "-c", "echo hello")))
        try {
            assertTrue(pty.pid >= 0, "spawned process must have a pid")
            val output = readUntilEof(pty)
            assertTrue(output.contains("hello"), "expected 'hello' in pty output, got: '$output'")
        } finally {
            pty.close()
        }
    }

    @Test
    fun resizeWriteTerminateAndClose() {
        val factory = ptyFactory()
        if (!factory.isSupported) return

        val pty = factory.spawn(PtyConfig(command = listOf("sh", "-c", "sleep 30")))
        try {
            assertTrue(pty.isAlive(), "child should be running right after spawn")

            pty.resize(120, 40)
            pty.write("x".encodeToByteArray())

            pty.terminate()
            pty.close()
            // close() reaps / drops the master, so the process must be gone afterwards.
            assertFalse(pty.isAlive(), "child should not be alive after close()")
            pty.close() // idempotent
            assertFalse(pty.isAlive())
        } finally {
            pty.close()
        }
    }

    /** Reads until the child closes the pty (EOF). */
    private fun readUntilEof(pty: PtyProcess): String {
        val out = StringBuilder()
        val buffer = ByteArray(512)
        while (true) {
            val n = pty.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            out.append(buffer.decodeToString(0, n))
        }
        return out.toString()
    }
}