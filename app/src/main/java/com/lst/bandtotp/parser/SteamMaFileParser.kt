package com.lst.bandtotp.parser

import android.util.Base64
import com.lst.bandtotp.model.TOTPInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for Steam .maFile (Steam Desktop Authenticator / Watt Toolkit Steam 2FA).
 * Automatically decodes Base64 `shared_secret` to Base32 TOTP secret.
 */
object SteamMaFileParser {

    fun isMaFileJson(jsonStr: String): Boolean {
        val trimmed = jsonStr.trim()
        return (trimmed.startsWith("{") && trimmed.contains("\"shared_secret\"")) ||
                (trimmed.startsWith("[") && trimmed.contains("\"shared_secret\""))
    }

    fun parse(content: String): List<TOTPInfo> {
        val results = mutableListOf<TOTPInfo>()
        val trimmed = content.trim()
        try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    parseSingleObject(obj)?.let { results.add(it) }
                }
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                parseSingleObject(obj)?.let { results.add(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    fun parseSingleObject(obj: JSONObject): TOTPInfo? {
        try {
            // Check if there is already an otpauth URI field
            val uriStr = obj.optString("uri", "")
            if (uriStr.isNotEmpty() && StandardOtpAuthParser.isOtpAuthUri(uriStr)) {
                StandardOtpAuthParser.parse(uriStr)?.let { return it }
            }

            val sharedSecret = obj.optString("shared_secret", "").trim()
            val accountName = obj.optString("account_name", "").trim().ifEmpty {
                obj.optString("account", "").trim().ifEmpty {
                    obj.optString("steam_id", "Steam")
                }
            }

            if (sharedSecret.isEmpty()) {
                // Check other possible secret fields
                val secretKey = obj.optString("secretKey", "").trim().ifEmpty {
                    obj.optString("secret", "").trim()
                }
                if (secretKey.isNotEmpty() && Base32Utils.isValidBase32(secretKey)) {
                    return TOTPInfo(
                        name = "Steam",
                        usr = accountName,
                        key = Base32Utils.normalize(secretKey),
                        algorithm = "SHA1",
                        digits = 5,
                        period = 30
                    )
                }
                return null
            }

            // shared_secret in Steam .maFile is standard Base64
            val rawBytes = try {
                java.util.Base64.getDecoder().decode(sharedSecret)
            } catch (e: Exception) {
                try {
                    android.util.Base64.decode(sharedSecret, android.util.Base64.DEFAULT)
                } catch (e2: Exception) {
                    null
                }
            } ?: return null

            val base32Secret = Base32Utils.encode(rawBytes)
            if (base32Secret.isEmpty()) return null

            return TOTPInfo(
                name = "Steam",
                usr = accountName,
                key = base32Secret,
                algorithm = "SHA1",
                digits = 5,
                period = 30
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
