package cn.enaium.terminal.parser

import cn.enaium.terminal.core.Terminal
import kotlin.test.Test
import kotlin.test.assertEquals

private fun newTerminal(columns: Int = 20, rows: Int = 6) = Terminal(columns, rows, scrollbackLimit = 100)

class DeviceResponseTest {

    private val responses = mutableListOf<String>()

    private fun terminal(columns: Int = 20, rows: Int = 6): Terminal =
        newTerminal(columns, rows).also { it.responseHandler = { r -> responses += r } }

    @Test
    fun primaryDeviceAttributes() {
        val p = VTParser(terminal())
        p.feed("\u001B[c")
        assertEquals("\u001B[?62;1;2;6;9;15;22c", responses.last())
    }

    @Test
    fun secondaryDeviceAttributes() {
        val p = VTParser(terminal())
        p.feed("\u001B[>c")
        assertEquals("\u001B[>0;1;0c", responses.last())
    }

    @Test
    fun deviceStatusReport() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[5n")
        assertEquals("\u001B[0n", responses.last())
    }

    @Test
    fun cursorPositionReport() {
        val t = terminal()
        val p = VTParser(t)
        t.setCursor(2, 3)
        p.feed("\u001B[6n")
        assertEquals("\u001B[3;4R", responses.last())
    }

    @Test
    fun privateCursorPositionReport() {
        val t = terminal()
        val p = VTParser(t)
        t.setCursor(1, 1)
        p.feed("\u001B[?6n")
        assertEquals("\u001B[?2;2R", responses.last())
    }

    @Test
    fun decRequestStatusScrollRegion() {
        val p = VTParser(terminal())
        p.feed("\u001BP\$qr\u001B\\")
        assertEquals("\u001BP1\$r1;6r\u001B\\", responses.last())
    }

    @Test
    fun decRequestStatusSgr() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B[1;31m")
        p.feed("\u001BP\$qm\u001B\\")
        assertEquals("\u001BP1\$r1;38;5;1;49m\u001B\\", responses.last())
    }

    @Test
    fun decRequestStatusUnknownSetting() {
        val p = VTParser(terminal())
        p.feed("\u001BP\$qz\u001B\\")
        assertEquals("\u001BP0\$rz\u001B\\", responses.last())
    }

    @Test
    fun windowSizeReport() {
        val p = VTParser(terminal(columns = 20, rows = 6))
        p.feed("\u001B[18t")
        assertEquals("\u001B[8;6;20t", responses.last())
    }

    @Test
    fun windowTitleReport() {
        val t = terminal()
        val p = VTParser(t)
        p.feed("\u001B]2;hello\u0007")
        p.feed("\u001B[21t")
        assertEquals("\u001B]lhello\u001B\\", responses.last())
    }

    @Test
    fun requestModePrivate() {
        val p = VTParser(terminal())
        p.feed("\u001B[?25\$p")
        assertEquals("\u001B[?25;1\$y", responses.last())
        p.feed("\u001B[?7\$p")
        assertEquals("\u001B[?7;1\$y", responses.last())
    }

    @Test
    fun requestModeAnsi() {
        val p = VTParser(terminal())
        p.feed("\u001B[4\$p")
        assertEquals("\u001B[4;2\$y", responses.last())
    }

    @Test
    fun deviceControlStringsOtherThanDecrqssAreIgnored() {
        val p = VTParser(terminal())
        p.feed("\u001BP0qignored\u001B\\")
        p.feed("ok")
        assertEquals(0, responses.size)
    }

    @Test
    fun sendDeviceAttributesEscZ() {
        val p = VTParser(terminal())
        p.feed("\u001BZ")
        assertEquals("\u001B[?6c", responses.last())
    }
}
