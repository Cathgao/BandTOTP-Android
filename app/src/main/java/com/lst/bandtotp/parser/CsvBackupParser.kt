package com.lst.bandtotp.parser

import com.lst.bandtotp.model.TOTPInfo
import java.io.BufferedReader
import java.io.StringReader

/**
 * Parser for CSV backup files exported by Microsoft Authenticator, Bitwarden, KeePass, etc.
 */
object CsvBackupParser {

    fun isCsv(content: String): Boolean {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        val firstLine = lines.first()
        return firstLine.contains(",") || firstLine.contains(";") || firstLine.contains("\t")
    }

    fun parse(csvContent: String): List<TOTPInfo> {
        val results = mutableListOf<TOTPInfo>()
        val reader = BufferedReader(StringReader(csvContent))
        val lines = mutableListOf<List<String>>()

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val trimmed = line?.trim() ?: continue
            if (trimmed.isEmpty()) continue

            // Quick check: if the line contains an otpauth URI, parse it directly
            if (trimmed.contains("otpauth://")) {
                val uriStart = trimmed.indexOf("otpauth://")
                var uriEnd = trimmed.indexOfAny(charArrayOf('"', ',', ';', ' ', '\t'), uriStart)
                if (uriEnd == -1) uriEnd = trimmed.length
                val uriStr = trimmed.substring(uriStart, uriEnd)
                val parsed = StandardOtpAuthParser.parse(uriStr)
                if (parsed != null) {
                    results.add(parsed)
                    continue
                }
            }

            val tokens = parseCsvLine(trimmed)
            if (tokens.isNotEmpty()) {
                lines.add(tokens)
            }
        }

        if (lines.isEmpty()) return results

        // Analyze header row if present
        val header = lines.first().map { it.lowercase().trim() }
        var nameCol = -1
        var userCol = -1
        var secretCol = -1
        var otpUriCol = -1

        for (i in header.indices) {
            val col = header[i]
            when {
                col.contains("otp") || col.contains("totp") || col.contains("uri") -> {
                    if (col.contains("secret") || col.contains("key")) {
                        secretCol = i
                    } else {
                        otpUriCol = i
                    }
                }
                col == "secret" || col == "key" || col == "secret key" || col == "secret_key" -> secretCol = i
                col == "issuer" || col == "service" || col == "title" || col == "name" -> nameCol = i
                col == "username" || col == "user" || col == "account" || col == "email" -> userCol = i
            }
        }

        val startIndex = if (secretCol != -1 || otpUriCol != -1 || nameCol != -1) 1 else 0

        for (i in startIndex until lines.size) {
            val row = lines[i]

            // 1. Check if any cell is an otpauth URI
            var parsedFromUri = false
            for (cell in row) {
                if (StandardOtpAuthParser.isOtpAuthUri(cell)) {
                    StandardOtpAuthParser.parse(cell)?.let {
                        results.add(it)
                        parsedFromUri = true
                    }
                    if (parsedFromUri) break
                }
            }
            if (parsedFromUri) continue

            // 2. Check mapped columns
            if (secretCol != -1 && secretCol < row.size) {
                val secret = row[secretCol].trim()
                if (Base32Utils.isValidBase32(secret)) {
                    val name = if (nameCol != -1 && nameCol < row.size) row[nameCol].trim() else "TOTP"
                    val user = if (userCol != -1 && userCol < row.size) row[userCol].trim() else name
                    results.add(
                        TOTPInfo(
                            name = name.ifEmpty { "TOTP" },
                            usr = user.ifEmpty { name },
                            key = Base32Utils.normalize(secret)
                        )
                    )
                    continue
                }
            }

            // 3. Fallback: inspect any cell for valid Base32 secret
            for (j in row.indices) {
                val candidate = row[j].trim()
                if (Base32Utils.isValidBase32(candidate) && candidate.length >= 8) {
                    val name = if (j > 0) row[0].trim() else "TOTP"
                    val user = if (row.size > 1 && j != 1) row[1].trim() else name
                    results.add(
                        TOTPInfo(
                            name = name.ifEmpty { "TOTP" },
                            usr = user.ifEmpty { name },
                            key = Base32Utils.normalize(candidate)
                        )
                    )
                    break
                }
            }
        }

        return results
    }

    private fun parseCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        val delimiter = if (line.contains("\t") && !line.contains(",")) '\t'
        else if (line.contains(";") && !line.contains(",")) ';'
        else ','

        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == delimiter && !inQuotes) {
                tokens.add(sb.toString().trim())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        tokens.add(sb.toString().trim())
        return tokens
    }
}
