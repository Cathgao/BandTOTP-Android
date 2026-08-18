package com.lst.bandtotp.parser

import android.net.Uri
import com.lst.bandtotp.model.TOTPInfo
import java.net.URLDecoder
import java.util.Locale

/**
 * Robust parser for standard otpauth://totp/ URIs.
 * Handles edge cases like missing issuer, path URL encoding, custom digit counts, etc.
 */
object StandardOtpAuthParser {

    fun isOtpAuthUri(uriString: String): Boolean {
        val trimmed = uriString.trim()
        return trimmed.startsWith("otpauth://", ignoreCase = true)
    }

    fun parse(uriString: String): TOTPInfo? {
        try {
            val trimmed = uriString.trim()
            if (!isOtpAuthUri(trimmed)) return null

            // Extract query parameters and path
            val queryIdx = trimmed.indexOf('?')
            val pathPart = if (queryIdx != -1) trimmed.substring(0, queryIdx) else trimmed
            val queryPart = if (queryIdx != -1) trimmed.substring(queryIdx + 1) else ""

            val prefix = "otpauth://"
            val schemeEnd = pathPart.indexOf(prefix, ignoreCase = true)
            val afterScheme = if (schemeEnd != -1) pathPart.substring(schemeEnd + prefix.length) else pathPart
            val slashIdx = afterScheme.indexOf('/')
            val rawPath = if (slashIdx != -1) afterScheme.substring(slashIdx + 1) else ""

            val decodedPath = try {
                URLDecoder.decode(rawPath, "UTF-8")
            } catch (e: Exception) {
                rawPath
            }

            var pathIssuer = ""
            var pathAccount = decodedPath

            if (decodedPath.contains(":")) {
                val parts = decodedPath.split(":", limit = 2)
                pathIssuer = parts[0].trim()
                pathAccount = parts[1].trim()
            }

            // Parse Query Map
            val queryParams = mutableMapOf<String, String>()
            if (queryPart.isNotEmpty()) {
                val pairs = queryPart.split("&")
                for (pair in pairs) {
                    val kv = pair.split("=", limit = 2)
                    if (kv.isNotEmpty()) {
                        val k = kv[0].trim().lowercase(Locale.ROOT)
                        val v = if (kv.size > 1) {
                            try { URLDecoder.decode(kv[1].trim(), "UTF-8") } catch (e: Exception) { kv[1].trim() }
                        } else ""
                        queryParams[k] = v
                    }
                }
            }

            val secretParam = queryParams["secret"]
                ?: queryParams["key"]
                ?: return null

            val cleanSecret = Base32Utils.normalize(secretParam)
            if (cleanSecret.isEmpty()) return null

            val queryIssuer = queryParams["issuer"]?.trim() ?: ""
            val algorithmParam = (queryParams["algorithm"] ?: "SHA1").uppercase(Locale.ROOT)
            val digitsParam = queryParams["digits"]?.toIntOrNull() ?: 6
            val periodParam = queryParams["period"]?.toIntOrNull() ?: 30

            var finalIssuer = if (queryIssuer.isNotEmpty()) queryIssuer else pathIssuer
            var finalAccount = pathAccount

            if (finalIssuer.isEmpty() && finalAccount.isNotEmpty()) {
                finalIssuer = finalAccount
            } else if (finalIssuer.isEmpty()) {
                finalIssuer = "TOTP"
            }

            if (finalAccount.isEmpty()) {
                finalAccount = finalIssuer
            }

            return TOTPInfo(
                name = finalIssuer,
                usr = finalAccount,
                key = cleanSecret,
                algorithm = algorithmParam,
                digits = digitsParam,
                period = periodParam
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
