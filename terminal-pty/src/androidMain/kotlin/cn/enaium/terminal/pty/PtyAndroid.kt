package cn.enaium.terminal.pty

/** Android (JVM) applications cannot spawn a shell through `fork`/`exec`. */
private class UnsupportedPtyFactory(private val platform: String) : PtyFactory {
    override val isSupported: Boolean get() = false

    override fun spawn(config: PtyConfig): PtyProcess =
        throw UnsupportedOperationException(
            "PTY processes are not supported on $platform: Android apps may not fork()/exec() " +
                "a shell. Run the terminal in a foreground service with a native PTY provider instead.",
        )
}

actual fun ptyFactory(): PtyFactory = UnsupportedPtyFactory("Android")

actual fun defaultShellCommand(): List<String> = listOf("/system/bin/sh")
