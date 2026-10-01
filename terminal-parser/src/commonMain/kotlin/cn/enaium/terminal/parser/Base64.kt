package cn.enaium.terminal.parser

import cn.enaium.terminal.unicode.Utf8

/**
 * Base64 as used by OSC 52 (clipboard): standard alphabet, `=` padding on
 * encode, tolerant decoding (whitespace and missing padding are accepted).
 */
internal object Base64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val chunk = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(chunk shr 18) and 0x3F])
            sb.append(ALPHABET[(chunk shr 12) and 0x3F])
            sb.append(ALPHABET[(chunk shr 6) and 0x3F])
            sb.append(ALPHABET[chunk and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val chunk = bytes[i].toInt() and 0xFF shl 16
                sb.append(ALPHABET[(chunk shr 18) and 0x3F])
                sb.append(ALPHABET[(chunk shr 12) and 0x3F])
                sb.append("==")
            }

            2 -> {
                val chunk = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                sb.append(ALPHABET[(chunk shr 18) and 0x3F])
                sb.append(ALPHABET[(chunk shr 12) and 0x3F])
                sb.append(ALPHABET[(chunk shr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    fun encode(text: String): String = encode(Utf8.encode(text))

    /** Decodes [text], or null when it is not valid base64. */
    fun decode(text: String): ByteArray? {
        val out = ByteArray(text.length / 4 * 3 + 3)
        var length = 0
        var buffer = 0
        var bits = 0
        for (c in text) {
            if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue
            val value = if (c.code < 128) DECODE_TABLE[c.code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[length++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(length)
    }

    /** Decodes [text] as UTF-8, or null when it is not valid base64. */
    fun decodeToString(text: String): String? = decode(text)?.let { Utf8.decode(it) }
}
