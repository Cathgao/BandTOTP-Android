package com.lst.bandtotp.model

import org.json.JSONObject

/**
 * TOTP Account Data Model
 * Sent to Xiaomi Band app in the format:
 * {"name":"issuer","usr":"account","key":"SECRET","algorithm":"SHA1","digits":6,"period":30}
 */
data class TOTPInfo(
    val name: String,               // Issuer / Service name (e.g., Google, Microsoft, Steam)
    val usr: String,                // Account name / username / email
    val key: String,                // Base32 encoded secret key
    val algorithm: String = "SHA1", // Hash algorithm (SHA1, SHA256, SHA512, MD5)
    val digits: Int = 6,            // Number of digits (usually 6, Steam is 5, sometimes 8)
    val period: Int = 30            // Time step in seconds (usually 30)
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("usr", usr)
            put("key", key)
            put("algorithm", algorithm)
            put("digits", digits)
            put("period", period)
        }
    }

    override fun toString(): String {
        return toJson().toString()
    }
}
