package cn.enaium.terminal.pty

/**
 * A child process attached to a pseudo-terminal whose master side this object
 * owns.
 *
 * [read] and [write] are blocking and are meant to be called from a dedicated
 * reader thread (see `TerminalSession`); everything else is safe to call from
 * any thread.
 */
interface PtyProcess : AutoCloseable {

    /** The process id of the child (or 0 when the platform does not expose one). */
    val pid: Int

    /** The pty device path, e.g. `/dev/ttys003` (empty when unavailable). */
    val devicePath: String

    /**
     * Reads up to [length] bytes into [buffer], blocking until at least one
     * byte is available. Returns the number of bytes read, or `-1` at end of
     * stream (the child closed the pty).
     */
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int

    /** Writes [length] bytes of [data] to the pty, blocking until all are written. */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset)

    /** Informs the child of a new window size (`TIOCSWINSZ` / `ResizePseudoConsole`). */
    fun resize(columns: Int, rows: Int)

    /** Whether the child process is still running. */
    fun isAlive(): Boolean

    /** Terminates the child: `SIGTERM`/`SIGKILL` on POSIX, `TerminateProcess` on Windows. */
    fun terminate(force: Boolean = false)

    /** Closes the master side and reaps the child. Idempotent. */
    override fun close()
}

/** Spawn configuration for [PtyFactory.spawn]. */
data class PtyConfig(
    /**
     * The command line to execute. An empty list runs the platform default
     * shell ([defaultShellCommand]).
     */
    val command: List<String> = emptyList(),
    val columns: Int = 80,
    val rows: Int = 24,
    /** Working directory for the child; null inherits the parent's. */
    val workingDirectory: String? = null,
    /** Extra environment variables, overriding inherited ones. */
    val environment: Map<String, String> = emptyMap(),
    /** Value for `TERM`. */
    val termName: String = "xterm-256color",
    /** Value for `COLORTERM`; null leaves it unset. */
    val colorTerm: String? = "truecolor",
)

/** Creates pty-backed processes on the current platform. */
interface PtyFactory {

    /**
     * Whether this platform can spawn pty processes at all. False on Android,
     * iOS, tvOS and watchOS, where sandboxing forbids `fork`/`exec`: the
     * terminal core still works there, only the shell cannot run locally.
     */
    val isSupported: Boolean

    /**
     * Spawns [config]'s command attached to a fresh pty and returns the master
     * side. Throws `UnsupportedOperationException` when [isSupported] is false
     * and `IllegalStateException` when the platform call fails.
     */
    fun spawn(config: PtyConfig): PtyProcess
}

/** The pty factory for the current platform. */
expect fun ptyFactory(): PtyFactory

/** Spawns [config] through [ptyFactory]. */
fun spawnPty(config: PtyConfig): PtyProcess = ptyFactory().spawn(config)

/** The platform's default shell command line: `$SHELL`, `%COMSPEC%` or a sane fallback. */
expect fun defaultShellCommand(): List<String>
