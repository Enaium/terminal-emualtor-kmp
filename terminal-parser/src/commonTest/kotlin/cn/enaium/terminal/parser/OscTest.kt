package cn.enaium.terminal.parser

import cn.enaium.terminal.core.ShellMarkType
import cn.enaium.terminal.core.Terminal
import cn.enaium.terminal.core.TerminalColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun terminal() = Terminal(columns = 20, rows = 4, scrollbackLimit = 100)

class OscTest {

    private val responses = mutableListOf<String>()
    private val clipboardWrites = mutableListOf<Pair<String, String>>()
    private var clipboardContents: String? = null

    private fun terminalWithHandlers(): Terminal {
        val t = terminal()
        t.responseHandler = { responses += it }
        t.clipboardHandler = { text, selection -> clipboardWrites += text to selection }
        t.clipboardProvider = { clipboardContents }
        return t
    }

    @Test
    fun titleAndIconName() {
        val t = terminalWithHandlers()
        VTParser(t).feed("\u001B]0;both\u0007")
        assertEquals("both", t.title)
        assertEquals("both", t.iconName)

        VTParser(t).feed("\u001B]2;title only\u0007")
        assertEquals("title only", t.title)
        assertEquals("both", t.iconName)

        VTParser(t).feed("\u001B]1;icon only\u0007")
        assertEquals("icon only", t.iconName)
    }

    @Test
    fun oscTerminatedByStringTerminator() {
        val t = terminalWithHandlers()
        VTParser(t).feed("\u001B]2;st\u001B\\")
        assertEquals("st", t.title)
    }

    @Test
    fun workingDirectory() {
        val t = terminalWithHandlers()
        VTParser(t).feed("\u001B]7;file://localhost/home/user\u0007")
        assertEquals("file://localhost/home/user", t.workingDirectory)
    }

    @Test
    fun hyperlinkWithId() {
        val t = terminalWithHandlers()
        VTParser(t).feed("\u001B]8;id=x;http://example.com\u0007")
        t.write("l")
        val id = t.lineAt(0).hyperlinkIdAt(0)
        assertTrue(id > 0)
        assertEquals("http://example.com", t.hyperlinkAt(id))

        VTParser(t).feed("\u001B]8;;\u0007")
        t.setCursor(0, 1)
        t.write("m")
        assertEquals(0, t.lineAt(0).hyperlinkIdAt(1))
        assertNull(t.hyperlinkAt(0))
    }

    @Test
    fun paletteSetAndQuery() {
        val t = terminalWithHandlers()
        VTParser(t).feed("\u001B]4;1;#123456\u0007")
        assertEquals(0x123456, t.paletteRgb(1))

        VTParser(t).feed("\u001B]4;1;?\u0007")
        assertEquals("\u001B]4;1;rgb:1212/3434/5656\u001B\\", responses.last())
    }

    @Test
    fun paletteColorSpecs() {
        val t = terminalWithHandlers()
        VTParser(t).feed("\u001B]4;1;#abc\u0007")
        assertEquals(0xAABBCC, t.paletteRgb(1))
        VTParser(t).feed("\u001B]4;2;rgb:12/34/56\u0007")
        assertEquals(0x123456, t.paletteRgb(2))
        VTParser(t).feed("\u001B]4;3;rgb:ffff/ffff/ffff\u0007")
        assertEquals(0xFFFFFF, t.paletteRgb(3))
    }

    @Test
    fun paletteReset() {
        val t = terminalWithHandlers()
        VTParser(t).feed("\u001B]4;1;#123456\u0007")
        VTParser(t).feed("\u001B]104;1\u0007")
        assertEquals(0xCD0000, t.paletteRgb(1))
    }

    @Test
    fun defaultColorSetAndQuery() {
        val t = terminalWithHandlers()
        VTParser(t).feed("\u001B]10;#112233\u0007")
        assertEquals(TerminalColor.rgb(0x11, 0x22, 0x33), t.defaultColor(10))

        VTParser(t).feed("\u001B]11;?\u0007")
        assertEquals("\u001B]11;rgb:0000/0000/0000\u001B\\", responses.last())

        VTParser(t).feed("\u001B]110\u0007")
        assertNull(t.defaultColor(10))
    }

    @Test
    fun clipboardWrite() {
        val t = terminalWithHandlers()
        VTParser(t).feed("\u001B]52;c;aGVsbG8=\u0007")
        assertEquals("hello" to "c", clipboardWrites.last())
    }

    @Test
    fun clipboardQuery() {
        val t = terminalWithHandlers()
        clipboardContents = "hi"
        VTParser(t).feed("\u001B]52;c;?\u0007")
        assertEquals("\u001B]52;c;aGk=\u001B\\", responses.last())
    }

    @Test
    fun clipboardQueryWithoutProviderDoesNothing() {
        val t = terminalWithHandlers()
        clipboardContents = null
        VTParser(t).feed("\u001B]52;c;?\u0007")
        assertTrue(responses.isEmpty())
    }

    @Test
    fun shellIntegrationMarks() {
        val t = terminalWithHandlers()
        val p = VTParser(t)
        p.feed("\u001B]133;A\u0007")
        p.feed("\u001B]133;B\u0007")
        p.feed("\u001B]133;C\u0007")
        p.feed("\u001B]133;D;7\u0007")
        val marks = t.shellMarks()
        assertEquals(4, marks.size)
        assertEquals(ShellMarkType.PROMPT_START, marks[0].type)
        assertEquals(ShellMarkType.PROMPT_END, marks[1].type)
        assertEquals(ShellMarkType.COMMAND_START, marks[2].type)
        assertEquals(ShellMarkType.COMMAND_FINISHED, marks[3].type)
        assertEquals(7, marks[3].exitCode)
    }

    @Test
    fun unknownOscIsIgnored() {
        val t = terminalWithHandlers()
        VTParser(t).feed("\u001B]999;whatever\u0007")
        t.write("ok")
        assertEquals("ok", t.lineAt(0).text(0, 20))
    }

    @Test
    fun belCountsAndBellHandler() {
        val t = terminalWithHandlers()
        var bells = 0
        t.bellHandler = { bells++ }
        VTParser(t).feed("a\u0007b")
        assertEquals(1, t.bellCount)
        assertEquals(1, bells)
    }
}
