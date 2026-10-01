package cn.enaium.terminal.pty

import com.pty4j.PtyProcess as Pty4jProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * JVM implementation backed by [pty4j](https://github.com/JetBrains/pty4j).
 *
 * pty4j handles the platform split for us: on POSIX it forks a child on a
 * pty; on Windows it drives ConPTY (or winpty) through the same API.
 */
private class JvmPtyProcess(private val process: Pty4jProcess) : PtyProcess {

    private val input: InputStream = process.inputStream
    private val output: OutputStream = process.outputStream

    override val pid: Int get() = process.pid().toInt()

    override val devicePath: String get() = ""

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return input.read(buffer, offset, length)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        output.write(data, offset, length)
        output.flush()
    }

    override fun resize(columns: Int, rows: Int) {
        process.setWinSize(WinSize(columns, rows))
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun terminate(force: Boolean) {
        if (force) process.destroyForcibly() else process.destroy()
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
    }
}

private class JvmPtyFactory : PtyFactory {

    override val isSupported: Boolean get() = true

    override fun spawn(config: PtyConfig): PtyProcess {
        val command = config.command.ifEmpty { defaultShellCommand() }
        require(command.isNotEmpty()) { "command must not be empty" }

        val environment = HashMap<String, String>(System.getenv())
        environment.putAll(config.environment)
        environment["TERM"] = config.termName
        val colorTerm = config.colorTerm
        if (colorTerm != null) environment["COLORTERM"] = colorTerm else environment.remove("COLORTERM")

        val builder = PtyProcessBuilder(command.toTypedArray())
            .setEnvironment(environment)
            .setInitialColumns(config.columns)
            .setInitialRows(config.rows)
            .setConsole(false)
        config.workingDirectory?.let { builder.setDirectory(it) }
        if (isWindows()) {
            builder.setUseWinConPty(true)
        }

        return JvmPtyProcess(builder.start())
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("win") == true
}

actual fun ptyFactory(): PtyFactory = JvmPtyFactory()

actual fun defaultShellCommand(): List<String> {
    val os = System.getProperty("os.name")?.lowercase() ?: ""
    return if (os.contains("win")) {
        val comspec = System.getenv("COMSPEC")?.takeIf { it.isNotBlank() } ?: "cmd.exe"
        listOf(comspec)
    } else {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"
        listOf(shell)
    }
}
