package com.lst.bandtotp.parser

import java.util.Locale

/**
 * RFC 4648 Base32 Encoder and Decoder utility.
 * Supports normalization (stripping spaces, dashes, case-insensitivity)
 * and converting raw byte arrays to Base32 secrets.
 */
object Base32Utils {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val DECODE_TABLE = IntArray(128) { -1 }.apply {
        for (i in ALPHABET.indices) {
            val c = ALPHABET[i]
            this[c.code] = i
            this[c.lowercaseChar().code] = i
        }
        // Support common substitutions (e.g. 0 -> O, 1 -> L / I, 8 -> B)
        this['0'.code] = this['O'.code]
        this['1'.code] = this['L'.code]
        this['8'.code] = this['B'.code]
    }

    /**
     * Cleans up and normalizes a Base32 string.
     */
    fun normalize(input: String): String {
        return input.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("=", "")
            .uppercase(Locale.US)
    }

    /**
     * Checks if a string looks like a valid Base32 secret (at least 4 chars long, valid alphabet).
     */
    fun isValidBase32(input: String): Boolean {
        val clean = normalize(input)
        if (clean.length < 4) return false
        return clean.all { it in ALPHABET }
    }

    /**
     * Encodes a ByteArray to standard Base32 string (without padding).
     */
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = java.lang.StringBuilder()
        var buffer = 0
        var bitsLeft = 0

        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                sb.append(ALPHABET[index])
                bitsLeft -= 5
            }
        }

        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            sb.append(ALPHABET[index])
        }

        return sb.toString()
    }

    /**
     * Decodes a Base32 string into a ByteArray.
     */
    fun decode(input: String): ByteArray {
        val clean = normalize(input)
        if (clean.isEmpty()) return ByteArray(0)

        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0

        for (ch in clean) {
            val charCode = ch.code
            val value = if (charCode in DECODE_TABLE.indices) DECODE_TABLE[charCode] else -1
            if (value == -1) continue

            buffer = (buffer shl 5) or value
            bitsLeft += 5

            if (bitsLeft >= 8) {
                val b = (buffer shr (bitsLeft - 8)) and 0xFF
                out.write(b)
                bitsLeft -= 8
            }
        }

        return out.toByteArray()
    }
}
