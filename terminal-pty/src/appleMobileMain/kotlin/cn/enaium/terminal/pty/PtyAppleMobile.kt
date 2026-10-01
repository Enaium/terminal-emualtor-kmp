package cn.enaium.terminal.pty

/** Apple mobile platforms (iOS/tvOS/watchOS) sandbox out `fork`/`exec`. */
private class UnsupportedPtyFactory(private val platform: String) : PtyFactory {
    override val isSupported: Boolean get() = false

    override fun spawn(config: PtyConfig): PtyProcess =
        throw UnsupportedOperationException(
            "PTY processes are not supported on $platform: the application sandbox forbids fork()/exec(). " +
                "Use an out-of-process or remote shell instead.",
        )
}

actual fun ptyFactory(): PtyFactory = UnsupportedPtyFactory("Apple mobile (iOS/tvOS/watchOS)")

actual fun defaultShellCommand(): List<String> = listOf("/bin/sh")
