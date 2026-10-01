package cn.enaium.terminal.session

import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalInput
import cn.enaium.terminal.core.TerminalKey
import cn.enaium.terminal.core.TerminalMouseAction
import cn.enaium.terminal.core.TerminalMouseButton
import cn.enaium.terminal.parser.VTParser
import cn.enaium.terminal.pty.PtyConfig
import cn.enaium.terminal.pty.PtyProcess
import cn.enaium.terminal.pty.spawnPty
import cn.enaium.terminal.unicode.Utf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wires a [PtyProcess] to a [Terminal] through a [VTParser].
 *
 * The reader runs on a background dispatcher and only ever hands raw byte
 * chunks to an unbounded channel; the terminal state itself is mutated by
 * [pump] on the caller's thread (the UI thread), so renderers never need a
 * lock. A frontend calls [pump] once per frame, then reads [terminal] and
 * sends input through [write] / the `send*` helpers.
 *
 * The session also owns the two directions the parser cannot reach by itself:
 * device responses ([Terminal.respond] is wired to the pty) and resizes.
 */
class TerminalSession(
    /** The terminal state machine. */
    val terminal: Terminal,
    /** The process attached to the pty, or null for an offline session. */
    val pty: PtyProcess? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    /** The escape-sequence parser feeding [terminal]. */
    val parser = VTParser(terminal)

    /** The input encoder (keys, paste, mouse) matching [terminal]'s modes. */
    val input = TerminalInput(terminal)

    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private var readerJob: Job? = null

    /** Invoked when the child process ends (or the pty reports end of stream). */
    var onExit: (() -> Unit)? = null

    /** True while the child process is alive. */
    val isRunning: Boolean get() = pty?.isAlive() ?: false

    init {
        terminal.responseHandler = { text -> write(Utf8.encode(text)) }
        terminal.bellHandler = { onBell?.invoke() }
    }

    /** Invoked when the application rings the bell. */
    var onBell: (() -> Unit)? = null

    /** Starts reading from the pty. Idempotent. */
    fun start() {
        val process = pty ?: return
        if (readerJob != null) return
        readerJob = scope.launch(ptyReaderDispatcher) {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            try {
                while (isActive) {
                    val read = process.read(buffer)
                    if (read < 0) break
                    if (read > 0) inbound.send(buffer.copyOf(read))
                }
            } catch (_: Throwable) {
                // The pty disappeared (process exit, close): the session simply ends.
            } finally {
                onExit?.invoke()
            }
        }
    }

    /**
     * Drains everything the reader collected into the parser. Call it once per
     * frame, on the thread that owns the terminal.
     *
     * Returns true when at least one chunk was parsed.
     */
    fun pump(): Boolean {
        var parsed = false
        while (true) {
            val result = inbound.tryReceive()
            if (result.isFailure) break
            parser.feed(result.getOrThrow())
            parsed = true
        }
        return parsed
    }

    /** Sends raw bytes to the application. */
    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        pty?.write(data)
    }

    /** Sends a key press. */
    fun sendKey(
        key: TerminalKey,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) {
        write(input.key(key, ctrl, alt, shift))
    }

    /** Sends typed text (IME output, plain characters). */
    fun sendText(text: String) {
        write(input.text(text))
    }

    /** Sends pasted text (bracketed when the application asked for it). */
    fun sendPaste(text: String) {
        write(input.paste(text))
    }

    /** Sends a mouse event when the application tracks the mouse. */
    fun sendMouse(
        button: TerminalMouseButton,
        action: TerminalMouseAction,
        column: Int,
        row: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) {
        input.mouse(button, action, column, row, ctrl, alt, shift)?.let(::write)
    }

    /** Sends a wheel event when the application tracks the mouse. */
    fun sendWheel(
        up: Boolean,
        column: Int,
        row: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) {
        input.wheel(up, column, row, ctrl, alt, shift)?.let(::write)
    }

    /** Sends a focus change when the application asked for focus reports. */
    fun sendFocus(gained: Boolean) {
        input.focus(gained)?.let(::write)
    }

    /** Resizes the terminal and tells the application. */
    fun resize(columns: Int, rows: Int) {
        if (columns == terminal.columns && rows == terminal.rows) return
        terminal.resize(columns, rows)
        pty?.resize(columns, rows)
    }

    /** Terminates the child process and stops the reader. */
    override fun close() {
        readerJob?.cancel()
        readerJob = null
        pty?.close()
        scope.cancel()
    }

    companion object {
        private const val READ_BUFFER_SIZE = 8192

        /**
         * Spawns [config]'s command (the platform shell by default) and returns
         * a running session.
         */
        fun spawn(config: PtyConfig = PtyConfig()): TerminalSession {
            val process = spawnPty(config)
            val session = TerminalSession(Terminal(config.columns, config.rows), process)
            session.start()
            return session
        }
    }
}
