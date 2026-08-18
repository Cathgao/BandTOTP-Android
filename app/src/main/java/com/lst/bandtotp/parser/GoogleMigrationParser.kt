package com.lst.bandtotp.parser

import android.net.Uri
import android.util.Base64
import com.lst.bandtotp.model.TOTPInfo
import java.net.URLDecoder

/**
 * Parses Google Authenticator export migration URIs:
 * otpauth-migration://offline?data=...
 *
 * Implements a lightweight, zero-dependency Protobuf decoder for MigrationPayload.
 */
object GoogleMigrationParser {

    fun isMigrationUri(uriString: String): Boolean {
        val trimmed = uriString.trim()
        return trimmed.startsWith("otpauth-migration://", ignoreCase = true) ||
                trimmed.contains("otpauth-migration://offline", ignoreCase = true)
    }

    fun parse(migrationUri: String): List<TOTPInfo> {
        val results = mutableListOf<TOTPInfo>()
        try {
            val trimmed = migrationUri.trim()
            var dataParam: String? = null

            val queryIndex = trimmed.indexOf("data=")
            if (queryIndex != -1) {
                val sub = trimmed.substring(queryIndex + 5)
                val endIndex = sub.indexOf('&')
                dataParam = if (endIndex != -1) sub.substring(0, endIndex) else sub
            }

            if (dataParam.isNullOrEmpty()) {
                try {
                    val uri = Uri.parse(trimmed)
                    dataParam = uri.getQueryParameter("data")
                } catch (e: Throwable) {
                    // ignore unmocked android Uri in JVM tests
                }
            }

            if (dataParam.isNullOrEmpty()) return emptyList()

            // Decode URL percent-encoding if needed
            val decodedDataParam = try {
                URLDecoder.decode(dataParam, "UTF-8")
            } catch (e: Exception) {
                dataParam
            }

            // Decode Base64 (supporting both standard and URL-safe base64, Android and JVM)
            val protoBytes = try {
                java.util.Base64.getDecoder().decode(decodedDataParam)
            } catch (e: Exception) {
                try {
                    java.util.Base64.getUrlDecoder().decode(decodedDataParam)
                } catch (e2: Exception) {
                    try {
                        android.util.Base64.decode(decodedDataParam, android.util.Base64.DEFAULT)
                    } catch (e3: Exception) {
                        try {
                            android.util.Base64.decode(decodedDataParam, android.util.Base64.URL_SAFE)
                        } catch (e4: Exception) {
                            return emptyList()
                        }
                    }
                }
            }

            results.addAll(parseProtobufPayload(protoBytes))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    private fun parseProtobufPayload(bytes: ByteArray): List<TOTPInfo> {
        val list = mutableListOf<TOTPInfo>()
        val reader = ProtoReader(bytes)

        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            val fieldNumber = tag.first
            val wireType = tag.second

            if (fieldNumber == 1 && wireType == 2) {
                // repeated OtpParameters otp_parameters = 1;
                val paramBytes = reader.readBytes()
                parseOtpParameters(paramBytes)?.let { list.add(it) }
            } else {
                reader.skipField(wireType)
            }
        }
        return list
    }

    private fun parseOtpParameters(bytes: ByteArray): TOTPInfo? {
        val reader = ProtoReader(bytes)
        var secretBytes: ByteArray? = null
        var name = ""
        var issuer = ""
        var algorithm = "SHA1"
        var digits = 6
        var period = 30

        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            val fieldNumber = tag.first
            val wireType = tag.second

            when (fieldNumber) {
                1 -> { // bytes secret = 1;
                    if (wireType == 2) {
                        secretBytes = reader.readBytes()
                    } else {
                        reader.skipField(wireType)
                    }
                }
                2 -> { // string name = 2;
                    if (wireType == 2) {
                        name = reader.readString()
                    } else {
                        reader.skipField(wireType)
                    }
                }
                3 -> { // string issuer = 3;
                    if (wireType == 2) {
                        issuer = reader.readString()
                    } else {
                        reader.skipField(wireType)
                    }
                }
                4 -> { // Algorithm algorithm = 4;
                    if (wireType == 0) {
                        val algoVal = reader.readVarint().toInt()
                        algorithm = when (algoVal) {
                            2 -> "SHA256"
                            3 -> "SHA512"
                            4 -> "MD5"
                            else -> "SHA1"
                        }
                    } else {
                        reader.skipField(wireType)
                    }
                }
                5 -> { // DigitCount digits = 5;
                    if (wireType == 0) {
                        val digitVal = reader.readVarint().toInt()
                        digits = when (digitVal) {
                            2 -> 8
                            else -> 6
                        }
                    } else {
                        reader.skipField(wireType)
                    }
                }
                6 -> { // OtpType type = 6; (1=HOTP, 2=TOTP)
                    if (wireType == 0) {
                        reader.readVarint()
                    } else {
                        reader.skipField(wireType)
                    }
                }
                else -> reader.skipField(wireType)
            }
        }

        if (secretBytes == null || secretBytes.isEmpty()) {
            return null
        }

        val base32Secret = Base32Utils.encode(secretBytes)
        if (base32Secret.isEmpty()) return null

        // Parse Name and Issuer
        // Name is often "Issuer:account" or just "account"
        var finalIssuer = issuer.trim()
        var finalUser = name.trim()

        if (finalUser.contains(":")) {
            val parts = finalUser.split(":", limit = 2)
            if (finalIssuer.isEmpty()) {
                finalIssuer = parts[0].trim()
            }
            finalUser = parts[1].trim()
        } else if (finalUser.contains(" (") && finalUser.endsWith(")")) {
            // Handle format like "Issuer (account)"
            val openIdx = finalUser.indexOf(" (")
            if (finalIssuer.isEmpty()) {
                finalIssuer = finalUser.substring(0, openIdx).trim()
            }
            finalUser = finalUser.substring(openIdx + 2, finalUser.length - 1).trim()
        }

        if (finalIssuer.isEmpty()) {
            finalIssuer = if (finalUser.isNotEmpty()) finalUser else "Google Authenticator"
        }

        return TOTPInfo(
            name = finalIssuer,
            usr = finalUser,
            key = base32Secret,
            algorithm = algorithm,
            digits = digits,
            period = period
        )
    }

    /**
     * Minimal Protobuf binary reader.
     */
    private class ProtoReader(private val bytes: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < bytes.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < bytes.size) {
                val b = bytes[pos++].toLong()
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80L) == 0L) {
                    return result
                }
                shift += 7
                if (shift >= 64) break
            }
            return result
        }

        fun readTag(): Pair<Int, Int>? {
            if (!hasMore()) return null
            val tag = readVarint().toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            return Pair(fieldNumber, wireType)
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            val safeLen = length.coerceAtLeast(0)
            val end = (pos + safeLen).coerceAtMost(bytes.size)
            val data = bytes.copyOfRange(pos, end)
            pos = end
            return data
        }

        fun readString(): String {
            return String(readBytes(), Charsets.UTF_8)
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos = (pos + 8).coerceAtMost(bytes.size)
                2 -> {
                    val len = readVarint().toInt().coerceAtLeast(0)
                    pos = (pos + len).coerceAtMost(bytes.size)
                }
                5 -> pos = (pos + 4).coerceAtMost(bytes.size)
                else -> pos = bytes.size
            }
        }
    }
}
