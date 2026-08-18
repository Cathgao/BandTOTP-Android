package com.lst.bandtotp.parser

import com.lst.bandtotp.model.TOTPInfo
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * Parser for Watt Toolkit (Steam++ / 瓦特工具箱) binary QR codes.
 *
 * Watt Toolkit compresses 2FA token data using Brotli compression (with header 0x21 ...),
 * containing Steam Guard shared_secret, OTP URIs, or MessagePack structures.
 */
object WattToolkitQrParser {

    fun isWattToolkitData(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        // Header starts with '!' (0x21) or Brotli stream
        return bytes[0] == 0x21.toByte() || (bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte())
    }

    fun isWattToolkitString(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.startsWith("!") || text.startsWith("\u0021")) return true
        val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
        return isWattToolkitData(bytes)
    }

    fun parse(bytes: ByteArray): List<TOTPInfo> {
        val decompressed = tryDecompress(bytes) ?: bytes
        return extractTotpFromPayload(decompressed)
    }

    fun parseString(text: String): List<TOTPInfo> {
        val latin1Bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
        val list1 = parse(latin1Bytes)
        if (list1.isNotEmpty()) return list1

        val utf8Bytes = text.toByteArray(StandardCharsets.UTF_8)
        return parse(utf8Bytes)
    }

    private fun tryDecompress(data: ByteArray): ByteArray? {
        // 1. Try Brotli at offset 4 (standard Watt Toolkit 0x21 0x02 0x00 0x00 header)
        for (offset in listOf(4, 0, 8, 2, 6)) {
            if (offset < data.size) {
                try {
                    val bis = BrotliInputStream(ByteArrayInputStream(data, offset, data.size - offset))
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var n: Int
                    while (bis.read(buffer).also { n = it } != -1) {
                        out.write(buffer, 0, n)
                    }
                    bis.close()
                    val result = out.toByteArray()
                    if (result.isNotEmpty()) {
                        return result
                    }
                } catch (e: Exception) {
                    // try next offset
                }
            }
        }

        // 2. Try GZIP
        try {
            val gis = GZIPInputStream(ByteArrayInputStream(data))
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var n: Int
            while (gis.read(buffer).also { n = it } != -1) {
                out.write(buffer, 0, n)
            }
            gis.close()
            return out.toByteArray()
        } catch (e: Exception) {
            // ignore
        }

        // 3. Try ZLIB / Inflater
        for (nowrap in listOf(false, true)) {
            for (offset in listOf(0, 2, 4)) {
                if (offset < data.size) {
                    try {
                        val inflater = Inflater(nowrap)
                        inflater.setInput(data, offset, data.size - offset)
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (!inflater.finished() && !inflater.needsInput()) {
                            val count = inflater.inflate(buffer)
                            if (count == 0) break
                            out.write(buffer, 0, count)
                        }
                        inflater.end()
                        val res = out.toByteArray()
                        if (res.isNotEmpty()) return res
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }

        return null
    }

    private fun extractTotpFromPayload(data: ByteArray): List<TOTPInfo> {
        val results = mutableListOf<TOTPInfo>()
        val rawLatin1 = String(data, StandardCharsets.ISO_8859_1)

        // 1. Look for Steam Guard shared_secret (Base64)
        val sharedSecretPattern = Pattern.compile(""""shared_secret"\s*:\s*"([^"]+)"""")
        val matcher = sharedSecretPattern.matcher(rawLatin1)
        if (matcher.find()) {
            val rawSecretStr = matcher.group(1)
                ?.replace("\\u002B", "+")
                ?.replace("\\u002b", "+")
                ?.replace("\\/", "/")
                ?.trim() ?: ""

            if (rawSecretStr.isNotEmpty()) {
                val secretBytes = try {
                    Base64.getDecoder().decode(rawSecretStr)
                } catch (e: Exception) {
                    try {
                        android.util.Base64.decode(rawSecretStr, android.util.Base64.DEFAULT)
                    } catch (e2: Exception) {
                        null
                    }
                }

                if (secretBytes != null && secretBytes.isNotEmpty()) {
                    val base32Secret = Base32Utils.encode(secretBytes)

                    // Find account name
                    var accountName = "Steam"
                    // Try pattern like (cath_gao) or "account_name":"xxx"
                    val parenMatch = Pattern.compile("""\(([a-zA-Z0-9_\-\.]+)\)""").matcher(rawLatin1)
                    if (parenMatch.find()) {
                        accountName = parenMatch.group(1) ?: accountName
                    } else {
                        val accMatch = Pattern.compile(""""account_name"\s*:\s*"([^"]+)"""").matcher(rawLatin1)
                        if (accMatch.find()) {
                            accountName = accMatch.group(1) ?: accountName
                        } else {
                            val uriAccMatch = Pattern.compile("""otpauth://totp/[^:]*:([a-zA-Z0-9_\-\.]+)\?""").matcher(rawLatin1)
                            if (uriAccMatch.find()) {
                                accountName = uriAccMatch.group(1) ?: accountName
                            }
                        }
                    }

                    results.add(
                        TOTPInfo(
                            name = "Steam",
                            usr = accountName,
                            key = base32Secret,
                            algorithm = "SHA1",
                            digits = 5,
                            period = 30
                        )
                    )
                    return results
                }
            }
        }

        // 2. Look for otpauth URI embedded in data
        val uriPattern = Pattern.compile("""otpauth://totp/[^\s"'\x00-\x1F]+""")
        val uriMatcher = uriPattern.matcher(rawLatin1)
        while (uriMatcher.find()) {
            val uriStr = uriMatcher.group()
            StandardOtpAuthParser.parse(uriStr)?.let { results.add(it) }
        }
        if (results.isNotEmpty()) return results

        // 3. Look for 32-character Base32 secrets
        val b32Pattern = Pattern.compile("""\b([A-Z2-7]{16,32})\b""")
        val b32Matcher = b32Pattern.matcher(rawLatin1)
        while (b32Matcher.find()) {
            val keyCand = b32Matcher.group(1) ?: continue
            if (Base32Utils.isValidBase32(keyCand) && keyCand.length >= 16) {
                var issuer = "Steam"
                if (rawLatin1.contains("Google", ignoreCase = true)) issuer = "Google"
                if (rawLatin1.contains("Microsoft", ignoreCase = true)) issuer = "Microsoft"

                results.add(
                    TOTPInfo(
                        name = issuer,
                        usr = "Account",
                        key = keyCand,
                        digits = if (issuer == "Steam") 5 else 6,
                        period = 30
                    )
                )
                break
            }
        }

        return results
    }
}
