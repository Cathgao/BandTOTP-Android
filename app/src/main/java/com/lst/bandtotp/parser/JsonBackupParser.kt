package com.lst.bandtotp.parser

import com.lst.bandtotp.model.TOTPInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal JSON Backup Parser.
 * Supports:
 * - Watt Toolkit / Steam++ 2FA JSON backups
 * - Aegis JSON export
 * - 2FAS JSON export
 * - Bitwarden JSON export
 * - AndOTP / FreeOTP JSON export
 * - General arrays of TOTP JSON objects
 */
object JsonBackupParser {

    fun isJson(text: String): Boolean {
        val trimmed = text.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    fun parse(jsonContent: String): List<TOTPInfo> {
        val results = mutableListOf<TOTPInfo>()
        val trimmed = jsonContent.trim()
        try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                results.addAll(parseJsonArray(array))
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)

                // First check if it's a Steam .maFile
                if (SteamMaFileParser.isMaFileJson(trimmed)) {
                    val maResults = SteamMaFileParser.parse(trimmed)
                    if (maResults.isNotEmpty()) return maResults
                }

                // Check known container keys
                val arrayKeys = listOf(
                    "entries", "authenticators", "tokens", "items", "accounts",
                    "services", "data", "list", "vault"
                )

                var foundArray = false
                for (key in arrayKeys) {
                    if (obj.has(key)) {
                        val sub = obj.optJSONArray(key)
                        if (sub != null && sub.length() > 0) {
                            results.addAll(parseJsonArray(sub))
                            foundArray = true
                            break
                        }
                    }
                }

                // Check nested Aegis structure: db -> entries
                if (!foundArray && obj.has("db")) {
                    val dbObj = obj.optJSONObject("db")
                    val dbEntries = dbObj?.optJSONArray("entries")
                    if (dbEntries != null) {
                        results.addAll(parseJsonArray(dbEntries))
                        foundArray = true
                    }
                }

                // If not an array container, try parsing as a single TOTP object
                if (!foundArray) {
                    parseJsonObject(obj)?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    private fun parseJsonArray(array: JSONArray): List<TOTPInfo> {
        val list = mutableListOf<TOTPInfo>()
        for (i in 0 until array.length()) {
            val itemObj = array.optJSONObject(i)
            if (itemObj != null) {
                parseJsonObject(itemObj)?.let { list.add(it) }
            } else {
                val itemStr = array.optString(i, "")
                if (itemStr.isNotEmpty()) {
                    if (StandardOtpAuthParser.isOtpAuthUri(itemStr)) {
                        StandardOtpAuthParser.parse(itemStr)?.let { list.add(it) }
                    } else if (GoogleMigrationParser.isMigrationUri(itemStr)) {
                        list.addAll(GoogleMigrationParser.parse(itemStr))
                    }
                }
            }
        }
        return list
    }

    fun parseJsonObject(obj: JSONObject): TOTPInfo? {
        try {
            // 1. Check if it has an otpauth URI field
            val uriKeys = listOf("uri", "otpauth", "totp", "otp_auth", "url", "link")
            for (key in uriKeys) {
                val uriVal = obj.optString(key, "")
                if (uriVal.isNotEmpty()) {
                    if (StandardOtpAuthParser.isOtpAuthUri(uriVal)) {
                        StandardOtpAuthParser.parse(uriVal)?.let { return it }
                    } else if (GoogleMigrationParser.isMigrationUri(uriVal)) {
                        val parsed = GoogleMigrationParser.parse(uriVal)
                        if (parsed.isNotEmpty()) return parsed.first()
                    }
                }
            }

            // 2. Check for Steam .maFile object
            if (obj.has("shared_secret")) {
                SteamMaFileParser.parseSingleObject(obj)?.let { return it }
            }

            // 3. Extract Secret Key
            val secretKeys = listOf(
                "key", "secret", "secretKey", "secret_key", "totp_secret",
                "shared_secret", "secretValue"
            )
            var secret = ""
            for (key in secretKeys) {
                val s = obj.optString(key, "")
                if (s.isNotEmpty()) {
                    secret = s
                    break
                }
            }

            // Check nested Aegis "info" -> "secret"
            if (secret.isEmpty() && obj.has("info")) {
                val info = obj.optJSONObject("info")
                secret = info?.optString("secret", "") ?: ""
            }

            // Check nested 2FAS "otp" -> "account" / "secret"
            val otpObj = obj.optJSONObject("otp")
            if (secret.isEmpty() && otpObj != null) {
                secret = otpObj.optString("secret", "")
            }

            // Check nested Bitwarden login -> totp
            val loginObj = obj.optJSONObject("login")
            if (loginObj != null) {
                val totpUri = loginObj.optString("totp", "")
                if (totpUri.isNotEmpty()) {
                    if (StandardOtpAuthParser.isOtpAuthUri(totpUri)) {
                        return StandardOtpAuthParser.parse(totpUri)
                    } else if (Base32Utils.isValidBase32(totpUri)) {
                        secret = totpUri
                    }
                }
            }

            if (secret.isEmpty()) return null

            val cleanSecret = Base32Utils.normalize(secret)
            if (!Base32Utils.isValidBase32(cleanSecret)) return null

            // 4. Extract Issuer / Service Name
            val issuerKeys = listOf("name", "issuer", "service", "title", "label", "provider")
            var issuer = ""
            for (key in issuerKeys) {
                val v = obj.optString(key, "")
                if (v.isNotEmpty()) {
                    issuer = v
                    break
                }
            }
            if (issuer.isEmpty() && otpObj != null) {
                issuer = otpObj.optString("issuer", "")
            }

            // 5. Extract Username / Account
            val userKeys = listOf("usr", "account", "username", "account_name", "user", "email")
            var account = ""
            for (key in userKeys) {
                val v = obj.optString(key, "")
                if (v.isNotEmpty()) {
                    account = v
                    break
                }
            }
            if (account.isEmpty() && otpObj != null) {
                account = otpObj.optString("account", "")
            }

            if (issuer.isEmpty() && account.isNotEmpty()) {
                issuer = account
            } else if (issuer.isEmpty()) {
                issuer = "TOTP"
            }
            if (account.isEmpty()) {
                account = issuer
            }

            // 6. Algorithm, Digits, Period
            var algorithm = obj.optString("algorithm", "").ifEmpty {
                obj.optString("algo", "").ifEmpty {
                    obj.optJSONObject("info")?.optString("algo", "SHA1") ?: "SHA1"
                }
            }.uppercase()

            var digits = obj.optInt("digits", 0)
            if (digits <= 0) {
                digits = obj.optJSONObject("info")?.optInt("digits", 6) ?: 6
            }
            if (digits <= 0 && otpObj != null) {
                digits = otpObj.optInt("digits", 6)
            }
            if (digits <= 0) digits = 6

            var period = obj.optInt("period", 0)
            if (period <= 0) {
                period = obj.optInt("periodSeconds", 0)
            }
            if (period <= 0) {
                period = obj.optJSONObject("info")?.optInt("period", 30) ?: 30
            }
            if (period <= 0 && otpObj != null) {
                period = otpObj.optInt("period", 30)
            }
            if (period <= 0) period = 30

            return TOTPInfo(
                name = issuer,
                usr = account,
                key = cleanSecret,
                algorithm = algorithm,
                digits = digits,
                period = period
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
