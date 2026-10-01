package cn.enaium.terminal.pty

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.EIO
import platform.posix.O_NOCTTY
import platform.posix.O_RDWR
import platform.posix.SIGHUP
import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.TIOCSCTTY
import platform.posix.TIOCSWINSZ
import platform.posix.WNOHANG
import platform.posix._exit
import platform.posix.chdir
import platform.posix.close as posixClose
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fork
import platform.posix.getenv
import platform.posix.grantpt
import platform.posix.ioctl
import platform.posix.kill
import platform.posix.nanosleep
import platform.posix.open
import platform.posix.POLLIN
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.posix_openpt
import platform.posix.ptsname
import platform.posix.read as posixRead
import platform.posix.setenv
import platform.posix.setsid
import platform.posix.tcgetpgrp
import platform.posix.timespec
import platform.posix.unlockpt
import platform.posix.unsetenv
import platform.posix.waitpid
import platform.posix.winsize
import platform.posix.write as posixWrite

/**
 * Real pseudo-terminal implementation for POSIX desktops (macOS and Linux).
 *
 * The master side is opened with `posix_openpt`/`grantpt`/`unlockpt`/`ptsname`,
 * the slave side is opened with `open`, and the child is created with a raw
 * `fork`. The child only touches async-signal-safe libc entry points
 * (`setsid`, `ioctl`, `dup2`, `close`, `chdir`, `setenv`, `execvp`, `_exit`).
 */
@OptIn(ExperimentalForeignApi::class)
private class PosixPtyProcess(
    private val masterFd: Int,
    override val pid: Int,
    override val devicePath: String,
) : PtyProcess {

    private var closed = false
    private var reaped = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (closed) return -1
            // Wait for readability with a *bounded* wait instead of parking in
            // read(): on macOS closing a pty master blocks until a concurrent
            // read() on it returns, so a reader parked in read() would deadlock
            // close() whenever something still holds the slave open (a shell
            // that started another shell, a background job, ...). Polling
            // returns as soon as data arrives, so interactive latency is
            // unchanged.
            val ready = memScoped {
                val descriptors = allocArray<pollfd>(1)
                descriptors[0].fd = masterFd
                descriptors[0].events = POLLIN.convert()
                poll(descriptors, 1u, READ_POLL_TIMEOUT_MILLIS)
            }
            if (ready < 0) {
                if (errno == EINTR) continue
                return -1
            }
            if (ready == 0) continue // timed out: re-check `closed`
            if (closed) return -1

            val n = buffer.usePinned { pinned ->
                posixRead(masterFd, pinned.addressOf(offset), length.convert())
            }
            if (n > 0) return n.toInt()
            if (n == 0L) return -1 // slave side closed on this platform
            when (errno) {
                EINTR -> continue
                EIO -> return -1 // Linux reports EIO once the slave is gone
                else -> return -1
            }
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            val n = data.usePinned { pinned ->
                posixWrite(masterFd, pinned.addressOf(offset + written), (length - written).convert())
            }
            if (n > 0) {
                written += n.toInt()
                continue
            }
            if (n < 0L && errno == EINTR) continue
            return // broken pipe / closed master: nothing more we can do
        }
    }

    override fun resize(columns: Int, rows: Int) {
        if (closed) return
        memScoped {
            val ws = alloc<winsize>()
            ws.ws_col = columns.coerceAtLeast(0).toUShort()
            ws.ws_row = rows.coerceAtLeast(0).toUShort()
            ioctl(masterFd, TIOCSWINSZ.convert(), ws.ptr)
        }
    }

    override fun isAlive(): Boolean {
        if (reaped) return false
        val r = waitpid(pid, null, WNOHANG)
        if (r == pid || r < 0) { // exited, or already reaped (ECHILD)
            reaped = true
            return false
        }
        return true
    }

    override fun terminate(force: Boolean) {
        if (reaped || closed) return
        kill(pid, if (force) SIGKILL else SIGTERM)
    }

    override fun close() {
        if (closed) return
        closed = true

        // Order matters on macOS: closing a pty master blocks until its slave
        // side is gone (and until a concurrent read() returns), so the child -
        // and whatever it started - is hung up first.
        if (!reaped) {
            // The *foreground process group* is what keeps the slave open when
            // a shell started another shell or a job; hanging up the direct
            // child alone is not enough.
            val foreground = tcgetpgrp(masterFd)
            if (foreground > 0 && foreground != pid) {
                kill(-foreground, SIGHUP)
            }
            kill(pid, SIGHUP)
            // Bounded: a child stuck in an uninterruptible kernel call ignores
            // SIGKILL until that call returns, and blocking on it would hang
            // whoever is quitting the application. The kernel reaps it later.
            reaped = reapWithin(REAP_TIMEOUT_MILLIS)
            if (!reaped) {
                kill(pid, SIGKILL)
                reaped = reapWithin(REAP_TIMEOUT_MILLIS)
            }
        }
        posixClose(masterFd)
    }

    /**
     * Reaps the child within [millis], returning whether it was reaped.
     *
     * Never blocks indefinitely: a child stuck in an uninterruptible kernel
     * state ignores SIGKILL until that call returns, and waiting for it would
     * hang whatever thread is closing the session.
     */
    private fun reapWithin(millis: Int): Boolean {
        val deadline = millis / 20
        repeat(deadline.coerceAtLeast(1)) {
            val r = waitpid(pid, null, WNOHANG)
            if (r == pid || r < 0) return true
            memScoped {
                val ts = alloc<timespec>()
                ts.tv_sec = 0
                ts.tv_nsec = 20_000_000L
                nanosleep(ts.ptr, null)
            }
        }
        return false
    }

    private companion object {
        /**
         * How long the reader waits for output before re-checking whether the
         * session was closed. Data always wakes it immediately.
         */
        const val READ_POLL_TIMEOUT_MILLIS = 100

        /**
         * How long close() waits for the child before giving up on reaping it.
         * A child that is stuck in an uninterruptible kernel call ignores
         * SIGKILL until that call returns; blocking on it would hang the
         * thread that is quitting the application.
         */
        const val REAP_TIMEOUT_MILLIS = 300
    }
}

/** POSIX desktop pty factory. */
@OptIn(ExperimentalForeignApi::class)
private class PosixPtyFactory : PtyFactory {

    override val isSupported: Boolean get() = true

    override fun spawn(config: PtyConfig): PtyProcess {
        val command = config.command.ifEmpty { defaultShellCommand() }
        require(command.isNotEmpty()) { "command must not be empty" }

        val masterFd = posix_openpt(O_RDWR or O_NOCTTY)
        if (masterFd < 0) {
            throw IllegalStateException("posix_openpt() failed: errno=$errno")
        }
        try {
            if (grantpt(masterFd) != 0) {
                throw IllegalStateException("grantpt() failed: errno=$errno")
            }
            if (unlockpt(masterFd) != 0) {
                throw IllegalStateException("unlockpt() failed: errno=$errno")
            }
            val slaveNamePtr = ptsname(masterFd)
                ?: throw IllegalStateException("ptsname() failed: errno=$errno")
            val slavePath = slaveNamePtr.toKString()
            val slaveFd = open(slavePath, O_RDWR or O_NOCTTY)
            if (slaveFd < 0) {
                throw IllegalStateException("open(\"$slavePath\") failed: errno=$errno")
            }

            // Everything the child needs is materialised before the fork: no
            // allocation may happen between fork() and execvp().
            val overrides = LinkedHashMap<String, String>()
            overrides.putAll(config.environment)
            overrides["TERM"] = config.termName
            val colorTerm = config.colorTerm

            val pid = memScoped {
                val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
                for (i in command.indices) argv[i] = command[i].cstr.ptr
                argv[command.size] = null

                val envKeys = ArrayList<CPointer<ByteVar>>(overrides.size + 1)
                val envValues = ArrayList<CPointer<ByteVar>>(overrides.size + 1)
                for ((k, v) in overrides) {
                    envKeys.add(k.cstr.ptr)
                    envValues.add(v.cstr.ptr)
                }
                val unsetColorTerm = if (colorTerm == null) "COLORTERM".cstr.ptr else null
                val colorTermKey = "COLORTERM".cstr.ptr
                val colorTermValue = colorTerm?.cstr?.ptr
                val dirPtr = config.workingDirectory?.cstr?.ptr

                val child = fork()
                if (child == 0) {
                    // --- child: async-signal-safe calls only ---
                    setsid()
                    ioctl(slaveFd, TIOCSCTTY.convert(), null)
                    dup2(slaveFd, STDIN_FILENO)
                    dup2(slaveFd, STDOUT_FILENO)
                    dup2(slaveFd, STDERR_FILENO)
                    if (slaveFd > STDERR_FILENO) posixClose(slaveFd)
                    posixClose(masterFd)
                    if (dirPtr != null) chdir(dirPtr)
                    var i = 0
                    while (i < envKeys.size) {
                        setenv(envKeys[i], envValues[i], 1)
                        i++
                    }
                    if (colorTermValue != null) {
                        setenv(colorTermKey, colorTermValue, 1)
                    } else if (unsetColorTerm != null) {
                        unsetenv(unsetColorTerm)
                    }
                    execvp(argv[0], argv)
                    _exit(127)
                }
                child
            }

            if (pid < 0) {
                posixClose(slaveFd)
                throw IllegalStateException("fork() failed: errno=$errno")
            }
            posixClose(slaveFd)

            // Initial window size on the master side; the kernel mirrors it onto
            // the slave so the child sees the right dimensions from the start.
            memScoped {
                val ws = alloc<winsize>()
                ws.ws_col = config.columns.coerceAtLeast(0).toUShort()
                ws.ws_row = config.rows.coerceAtLeast(0).toUShort()
                ioctl(masterFd, TIOCSWINSZ.convert(), ws.ptr)
            }

            return PosixPtyProcess(masterFd, pid, slavePath)
        } catch (t: Throwable) {
            posixClose(masterFd)
            throw t
        }
    }
}

actual fun ptyFactory(): PtyFactory = PosixPtyFactory()

@OptIn(ExperimentalForeignApi::class)
actual fun defaultShellCommand(): List<String> {
    val shell = getenv("SHELL")?.toKString()?.takeIf { it.isNotBlank() } ?: "/bin/sh"
    return listOf(shell)
}
