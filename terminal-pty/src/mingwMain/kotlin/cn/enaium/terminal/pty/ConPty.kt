package cn.enaium.terminal.pty

import cn.enaium.terminal.pty.conpty.ClosePseudoConsole
import cn.enaium.terminal.pty.conpty.CreatePseudoConsole
import cn.enaium.terminal.pty.conpty.ResizePseudoConsole
import cnames.structs._PROC_THREAD_ATTRIBUTE_LIST
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.getenv
import platform.windows.COORD
import platform.windows.CloseHandle
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.DWORDVar
import platform.windows.DeleteProcThreadAttributeList
import platform.windows.EXTENDED_STARTUPINFO_PRESENT
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GetEnvironmentStringsW
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.InitializeProcThreadAttributeList
import platform.windows.PROCESS_INFORMATION
import platform.windows.ReadFile
import platform.windows.STARTUPINFOEXW
import platform.windows.STILL_ACTIVE
import platform.windows.TerminateProcess
import platform.windows.UpdateProcThreadAttribute
import platform.windows.WCHARVar
import platform.windows.WriteFile

/**
 * `PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE` is a macro in `processthreadsapi.h`:
 * `ProcThreadAttributeValue(22, FALSE, TRUE, FALSE)` = `22 | 0x10000`.
 */
private const val PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE: ULong = 0x10016uL

/** `CREATE_UNICODE_ENVIRONMENT`. */
private const val CREATE_UNICODE_ENVIRONMENT: Int = 0x00000400

/** Allocates a NUL-terminated UTF-16 copy of [s] in this [MemScope]. */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.wideString(s: String): CPointer<WCHARVar> {
    val array = allocArray<WCHARVar>(s.length + 1)
    for (i in s.indices) array[i] = s[i].code.toUShort()
    array[s.length] = 0u
    return array
}

/**
 * Windows pseudoconsole (ConPTY) implementation.
 *
 * `CreatePseudoConsole` binds a pair of pipes to a fresh console host; the
 * client process is launched with the pseudoconsole attached through the
 * `PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE` process attribute. The master side is
 * then plain `ReadFile`/`WriteFile` on those pipes.
 */
@OptIn(ExperimentalForeignApi::class)
private class ConPtyProcess(
    private val hpc: COpaquePointer?,
    private val inputWrite: HANDLE?,
    private val outputRead: HANDLE?,
    private val process: HANDLE?,
    override val pid: Int,
) : PtyProcess {

    private var closed = false

    override val devicePath: String get() = ""

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        return memScoped {
            val read = alloc<DWORDVar>()
            val ok = buffer.usePinned { pinned ->
                ReadFile(outputRead, pinned.addressOf(offset), length.convert(), read.ptr, null)
            }
            if (ok == 0) {
                return@memScoped -1 // EOF / broken pipe: the client is gone
            }
            val n = read.value.toInt()
            if (n == 0) -1 else n
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            val n = memScoped {
                val wrote = alloc<DWORDVar>()
                val ok = data.usePinned { pinned ->
                    WriteFile(
                        inputWrite,
                        pinned.addressOf(offset + written),
                        (length - written).convert(),
                        wrote.ptr,
                        null,
                    )
                }
                if (ok == 0) 0 else wrote.value.toInt()
            }
            if (n <= 0) return
            written += n
        }
    }

    override fun resize(columns: Int, rows: Int) {
        if (closed) return
        ResizePseudoConsole(hpc, coordOf(columns, rows))
    }

    override fun isAlive(): Boolean {
        if (closed) return false
        return memScoped {
            val code = alloc<DWORDVar>()
            if (GetExitCodeProcess(process, code.ptr) == 0) return@memScoped false
            code.value == STILL_ACTIVE.convert<UInt>()
        }
    }

    override fun terminate(force: Boolean) {
        if (closed) return
        TerminateProcess(process, 1u)
    }

    override fun close() {
        if (closed) return
        closed = true
        // Closing the pseudoconsole terminates the attached client and its conhost.
        ClosePseudoConsole(hpc)
        CloseHandle(inputWrite)
        CloseHandle(outputRead)
        CloseHandle(process)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun coordOf(columns: Int, rows: Int) = cValue<COORD> {
    X = columns.coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
    Y = rows.coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
}

@OptIn(ExperimentalForeignApi::class)
private class ConPtyFactory : PtyFactory {

    override val isSupported: Boolean get() = true

    override fun spawn(config: PtyConfig): PtyProcess {
        val command = config.command.ifEmpty { defaultShellCommand() }
        require(command.isNotEmpty()) { "command must not be empty" }
        val size = coordOf(config.columns, config.rows)

        return memScoped {
            val inputRead = alloc<HANDLEVar>()
            val inputWrite = alloc<HANDLEVar>()
            if (CreatePipe(inputRead.ptr, inputWrite.ptr, null, 0u) == 0) {
                throw IllegalStateException("CreatePipe (stdin) failed: ${GetLastError()}")
            }
            val outputRead = alloc<HANDLEVar>()
            val outputWrite = alloc<HANDLEVar>()
            if (CreatePipe(outputRead.ptr, outputWrite.ptr, null, 0u) == 0) {
                CloseHandle(inputRead.value)
                CloseHandle(inputWrite.value)
                throw IllegalStateException("CreatePipe (stdout) failed: ${GetLastError()}")
            }

            val hpcVar = alloc<COpaquePointerVar>()
            val hr = CreatePseudoConsole(size, inputRead.value, outputWrite.value, 0u, hpcVar.ptr)
            // ConPTY duplicated the ends it needs; drop our copies.
            CloseHandle(inputRead.value)
            CloseHandle(outputWrite.value)
            if (hr != 0) {
                CloseHandle(inputWrite.value)
                CloseHandle(outputRead.value)
                throw IllegalStateException("CreatePseudoConsole failed: HRESULT=0x${hr.toUInt().toString(16)}")
            }
            val hpc = hpcVar.value

            // Build the attribute list carrying the pseudoconsole.
            val attrSize = alloc<ULongVar>()
            InitializeProcThreadAttributeList(null, 1u, 0u, attrSize.ptr)
            val attrMem = nativeHeap.allocArray<ByteVar>(attrSize.value.toInt())
            val attrList = attrMem.reinterpret<_PROC_THREAD_ATTRIBUTE_LIST>()
            var attrListReady = false
            var processHandle: HANDLE? = null
            var threadHandle: HANDLE? = null
            try {
                if (InitializeProcThreadAttributeList(attrList, 1u, 0u, attrSize.ptr) == 0) {
                    throw IllegalStateException("InitializeProcThreadAttributeList failed: ${GetLastError()}")
                }
                attrListReady = true
                if (UpdateProcThreadAttribute(
                        attrList,
                        0u,
                        PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE,
                        hpc,
                        sizeOf<COpaquePointerVar>().convert(),
                        null,
                        null,
                    ) == 0
                ) {
                    throw IllegalStateException("UpdateProcThreadAttribute failed: ${GetLastError()}")
                }

                val si = alloc<STARTUPINFOEXW>()
                si.StartupInfo.cb = sizeOf<STARTUPINFOEXW>().convert()
                si.lpAttributeList = attrList

                val envBlock = buildEnvironmentBlock(config)
                val commandLine = wideString(buildCommandLine(command))

                val pi = alloc<PROCESS_INFORMATION>()
                val created = CreateProcessW(
                    null as String?,
                    commandLine,
                    null,
                    null,
                    0,
                    (EXTENDED_STARTUPINFO_PRESENT or CREATE_UNICODE_ENVIRONMENT).toUInt(),
                    envBlock?.reinterpret<COpaque>(),
                    config.workingDirectory,
                    si.StartupInfo.ptr,
                    pi.ptr,
                )
                if (created == 0) {
                    throw IllegalStateException("CreateProcessW failed: ${GetLastError()}")
                }
                processHandle = pi.hProcess
                threadHandle = pi.hThread
                CloseHandle(threadHandle)
                threadHandle = null

                // The attribute list is only needed while creating the process.
                DeleteProcThreadAttributeList(attrList)
                attrListReady = false

                ConPtyProcess(
                    hpc = hpc,
                    inputWrite = inputWrite.value,
                    outputRead = outputRead.value,
                    process = processHandle,
                    pid = pi.dwProcessId.toInt(),
                )
            } catch (t: Throwable) {
                if (attrListReady) DeleteProcThreadAttributeList(attrList)
                if (processHandle != null) CloseHandle(processHandle)
                if (threadHandle != null) CloseHandle(threadHandle)
                ClosePseudoConsole(hpc)
                CloseHandle(inputWrite.value)
                CloseHandle(outputRead.value)
                throw t
            } finally {
                nativeHeap.free(attrMem.rawValue)
            }
        }
    }

    /**
     * Inherits the parent's environment and applies [PtyConfig.environment],
     * `TERM` and `COLORTERM`, returning a `CREATE_UNICODE_ENVIRONMENT` block.
     */
    private fun MemScope.buildEnvironmentBlock(config: PtyConfig): CPointer<WCHARVar>? {
        val block = GetEnvironmentStringsW() ?: return null
        val map = LinkedHashMap<String, String>()
        try {
            var offset = 0
            while (block[offset].toInt() != 0) {
                val entry = readWideString(block, offset)
                offset += entry.length + 1
                val eq = entry.indexOf('=')
                if (eq > 0) map[entry.substring(0, eq)] = entry.substring(eq + 1)
            }
        } finally {
            FreeEnvironmentStringsW(block)
        }
        map.putAll(config.environment)
        map["TERM"] = config.termName
        val colorTerm = config.colorTerm
        if (colorTerm != null) map["COLORTERM"] = colorTerm else map.remove("COLORTERM")
        if (map.isEmpty()) return null

        val entries = map.entries
            .map { "${it.key}=${it.value}" }
            .sortedBy { it.lowercase() }
        // Windows expects a double-NUL terminated block; wideString appends the final NUL.
        return wideString(entries.joinToString("\u0000") + "\u0000")
    }

    private fun readWideString(ptr: CPointer<WCHARVar>, start: Int): String {
        val sb = StringBuilder()
        var i = start
        while (true) {
            val c = ptr[i].toInt()
            if (c == 0) break
            sb.append(c.toChar())
            i++
        }
        return sb.toString()
    }

    /** Quotes [args] following the `CommandLineToArgvW` rules. */
    private fun buildCommandLine(args: List<String>): String {
        val sb = StringBuilder()
        for ((index, arg) in args.withIndex()) {
            if (index > 0) sb.append(' ')
            if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) {
                sb.append(arg)
                continue
            }
            sb.append('"')
            var backslashes = 0
            for (c in arg) {
                when (c) {
                    '\\' -> backslashes++
                    '"' -> {
                        sb.append("\\".repeat(backslashes * 2 + 1))
                        sb.append('"')
                        backslashes = 0
                    }
                    else -> {
                        sb.append("\\".repeat(backslashes))
                        sb.append(c)
                        backslashes = 0
                    }
                }
            }
            sb.append("\\".repeat(backslashes * 2))
            sb.append('"')
        }
        return sb.toString()
    }
}

actual fun ptyFactory(): PtyFactory = ConPtyFactory()

@OptIn(ExperimentalForeignApi::class)
actual fun defaultShellCommand(): List<String> {
    val comspec = getenv("COMSPEC")?.toKString()?.takeIf { it.isNotBlank() } ?: "cmd.exe"
    return listOf(comspec)
}
