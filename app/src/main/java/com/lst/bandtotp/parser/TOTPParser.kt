package com.lst.bandtotp.parser

import com.lst.bandtotp.model.TOTPInfo
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Unified TOTP Parser Facade.
 * Intelligently identifies and parses data from:
 * - Google Authenticator (QR Migration URIs, otpauth-migration://)
 * - Microsoft Authenticator (QR codes, CSV exports, backup text)
 * - Watt Toolkit / Steam++ (.maFile, 2FA JSON backups, Steam secrets)
 * - Aegis, 2FAS, Bitwarden, AndOTP, FreeOTP exports
 * - Standard otpauth:// URIs
 * - Plaintext secrets and lists
 */
object TOTPParser {

    /**
     * Parses arbitrary text input into a list of TOTPInfo objects.
     */
    fun parse(input: String): List<TOTPInfo> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        val results = mutableListOf<TOTPInfo>()

        // 1. Google Authenticator Migration URI
        if (trimmed.contains("otpauth-migration://", ignoreCase = true)) {
            // Find all migration URIs in the text
            val regex = Regex("""otpauth-migration://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
            val matches = regex.findAll(trimmed)
            for (match in matches) {
                results.addAll(GoogleMigrationParser.parse(match.value))
            }
            if (results.isNotEmpty()) {
                return deduplicate(results)
            }
        }

        // 2. Base64 encoded Watt Toolkit QR payload
        if (trimmed.startsWith("watt-qr-b64:")) {
            val b64 = trimmed.substring("watt-qr-b64:".length)
            val rawBytes = try {
                java.util.Base64.getDecoder().decode(b64)
            } catch (e: Exception) {
                try {
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                } catch (e2: Exception) {
                    null
                }
            }
            if (rawBytes != null) {
                val wtResults = WattToolkitQrParser.parse(rawBytes)
                if (wtResults.isNotEmpty()) {
                    return deduplicate(wtResults)
                }
            }
        }

        // 3. Watt Toolkit Binary QR string
        if (WattToolkitQrParser.isWattToolkitString(trimmed)) {
            val wtResults = WattToolkitQrParser.parseString(trimmed)
            if (wtResults.isNotEmpty()) {
                return deduplicate(wtResults)
            }
        }

        // 2. JSON Format (Watt Toolkit, Steam .maFile, Aegis, 2FAS, Bitwarden, etc.)
        if (JsonBackupParser.isJson(trimmed)) {
            val jsonResults = JsonBackupParser.parse(trimmed)
            if (jsonResults.isNotEmpty()) {
                return deduplicate(jsonResults)
            }
        }

        // 3. Extract multiple standard otpauth:// URIs across text/lines
        if (trimmed.contains("otpauth://", ignoreCase = true)) {
            val regex = Regex("""otpauth://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
            val matches = regex.findAll(trimmed)
            for (match in matches) {
                StandardOtpAuthParser.parse(match.value)?.let { results.add(it) }
            }
            if (results.isNotEmpty()) {
                return deduplicate(results)
            }
        }

        // 4. CSV Format
        if (CsvBackupParser.isCsv(trimmed)) {
            val csvResults = CsvBackupParser.parse(trimmed)
            if (csvResults.isNotEmpty()) {
                return deduplicate(csvResults)
            }
        }

        // 5. Line-by-line fallback analysis
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            // Check for otpauth URI
            if (StandardOtpAuthParser.isOtpAuthUri(line)) {
                StandardOtpAuthParser.parse(line)?.let { results.add(it) }
                continue
            }
            // Check for migration URI
            if (GoogleMigrationParser.isMigrationUri(line)) {
                results.addAll(GoogleMigrationParser.parse(line))
                continue
            }
            // Check line format: "Issuer: Account: SECRET" or "Issuer - Account - SECRET" or "Issuer, Account, SECRET"
            val parts = line.split(Regex("[:,-|\\t]")).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                val secretCand = parts.last()
                if (Base32Utils.isValidBase32(secretCand)) {
                    results.add(
                        TOTPInfo(
                            name = parts[0],
                            usr = parts[1],
                            key = Base32Utils.normalize(secretCand)
                        )
                    )
                    continue
                }
            } else if (parts.size == 2) {
                val secretCand = parts[1]
                if (Base32Utils.isValidBase32(secretCand)) {
                    results.add(
                        TOTPInfo(
                            name = parts[0],
                            usr = parts[0],
                            key = Base32Utils.normalize(secretCand)
                        )
                    )
                    continue
                }
            } else if (parts.size == 1 && Base32Utils.isValidBase32(parts[0]) && parts[0].length >= 8) {
                // Just a raw Base32 secret key
                results.add(
                    TOTPInfo(
                        name = "TOTP",
                        usr = "Account",
                        key = Base32Utils.normalize(parts[0])
                    )
                )
            }
        }

        return deduplicate(results)
    }

    /**
     * Reads and parses input from an InputStream.
     */
    fun parseStream(inputStream: InputStream): List<TOTPInfo> {
        val stringBuilder = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stringBuilder.append(line).append("\n")
            }
        }
        return parse(stringBuilder.toString())
    }

    private fun deduplicate(items: List<TOTPInfo>): List<TOTPInfo> {
        val seen = mutableSetOf<String>()
        val uniqueList = mutableListOf<TOTPInfo>()
        for (item in items) {
            val keyFingerprint = "${item.name.trim().lowercase()}|${item.usr.trim().lowercase()}|${item.key.trim().uppercase()}"
            if (seen.add(keyFingerprint)) {
                uniqueList.add(item)
            }
        }
        return uniqueList
    }
}
