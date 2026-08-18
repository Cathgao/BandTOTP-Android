package com.lst.bandtotp

import com.lst.bandtotp.parser.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class TOTPParserTest {

    @Test
    fun testBase32Utils() {
        val original = "Hello World!"
        val encoded = Base32Utils.encode(original.toByteArray())
        val decodedBytes = Base32Utils.decode(encoded)
        assertEquals(original, String(decodedBytes))

        // Normalization
        val messyKey = " jbsw-y3dp ehpk 3pxp= "
        val clean = Base32Utils.normalize(messyKey)
        assertEquals("JBSWY3DPEHPK3PXP", clean)
        assertTrue(Base32Utils.isValidBase32(clean))
    }

    @Test
    fun testStandardOtpAuthParser() {
        val uri1 = "otpauth://totp/Google:john.doe@gmail.com?secret=JBSWY3DPEHPK3PXP&issuer=Google"
        val item1 = StandardOtpAuthParser.parse(uri1)
        assertNotNull(item1)
        assertEquals("Google", item1?.name)
        assertEquals("john.doe@gmail.com", item1?.usr)
        assertEquals("JBSWY3DPEHPK3PXP", item1?.key)
        assertEquals(6, item1?.digits)
        assertEquals(30, item1?.period)

        // URL Encoded path without colon
        val uri2 = "otpauth://totp/test%40microsoft.com?secret=EP5O5BC3NZVEE7YI&issuer=Microsoft&algorithm=SHA256&digits=8"
        val item2 = StandardOtpAuthParser.parse(uri2)
        assertNotNull(item2)
        assertEquals("Microsoft", item2?.name)
        assertEquals("test@microsoft.com", item2?.usr)
        assertEquals("EP5O5BC3NZVEE7YI", item2?.key)
        assertEquals("SHA256", item2?.algorithm)
        assertEquals(8, item2?.digits)
    }

    @Test
    fun testGoogleMigrationParser() {
        // Construct protobuf bytes for MigrationPayload
        // OtpParameters 1: secret = "12345678901234567890" (20 bytes), name = "Google:user@gmail.com", issuer = "Google"
        val rawSecret = "12345678901234567890".toByteArray()
        val expectedBase32 = Base32Utils.encode(rawSecret)

        val paramOut = ByteArrayOutputStream()
        // field 1 (bytes secret)
        paramOut.write((1 shl 3) or 2)
        paramOut.write(rawSecret.size)
        paramOut.write(rawSecret)

        // field 2 (string name)
        val nameBytes = "Google:user@gmail.com".toByteArray()
        paramOut.write((2 shl 3) or 2)
        paramOut.write(nameBytes.size)
        paramOut.write(nameBytes)

        // field 3 (string issuer)
        val issuerBytes = "Google".toByteArray()
        paramOut.write((3 shl 3) or 2)
        paramOut.write(issuerBytes.size)
        paramOut.write(issuerBytes)

        // field 4 (algo = SHA1 = 1)
        paramOut.write((4 shl 3) or 0)
        paramOut.write(1)

        // field 5 (digits = 6 = 1)
        paramOut.write((5 shl 3) or 0)
        paramOut.write(1)

        val paramBytes = paramOut.toByteArray()

        val topOut = ByteArrayOutputStream()
        // field 1 (repeated OtpParameters)
        topOut.write((1 shl 3) or 2)
        topOut.write(paramBytes.size)
        topOut.write(paramBytes)

        val topBytes = topOut.toByteArray()
        val base64Data = Base64.getEncoder().encodeToString(topBytes)
        val migrationUri = "otpauth-migration://offline?data=$base64Data"

        val parsed = GoogleMigrationParser.parse(migrationUri)
        assertEquals(1, parsed.size)
        assertEquals("Google", parsed[0].name)
        assertEquals("user@gmail.com", parsed[0].usr)
        assertEquals(expectedBase32, parsed[0].key)
        assertEquals(6, parsed[0].digits)
        assertEquals(30, parsed[0].period)
    }

    @Test
    fun testSteamMaFileParser() {
        // Steam Desktop Authenticator / Watt Toolkit .maFile JSON
        val maFileJson = """
        {
            "account_name": "gamer123",
            "shared_secret": "c2hhcmVkc2VjcmV0MTIz",
            "identity_secret": "identitysecret==",
            "steam_id": "76561198000000000"
        }
        """.trimIndent()

        val items = SteamMaFileParser.parse(maFileJson)
        assertEquals(1, items.size)
        assertEquals("Steam", items[0].name)
        assertEquals("gamer123", items[0].usr)
        val rawBytes = Base64.getDecoder().decode("c2hhcmVkc2VjcmV0MTIz")
        assertEquals(Base32Utils.encode(rawBytes), items[0].key)
        assertEquals(5, items[0].digits)
    }

    @Test
    fun testWattToolkitJsonArray() {
        val jsonArray = """
        [
            {
                "title": "GitHub",
                "account": "dev",
                "secret": "JBSWY3DPEHPK3PXP",
                "digits": 6,
                "period": 30
            },
            {
                "title": "Steam",
                "account": "steam_user",
                "shared_secret": "c2hhcmVkc2VjcmV0MTIz"
            }
        ]
        """.trimIndent()

        val items = JsonBackupParser.parse(jsonArray)
        assertEquals(2, items.size)
        assertEquals("GitHub", items[0].name)
        assertEquals("dev", items[0].usr)
        assertEquals("JBSWY3DPEHPK3PXP", items[0].key)

        assertEquals("Steam", items[1].name)
        assertEquals("steam_user", items[1].usr)
        assertEquals(5, items[1].digits)
    }

    @Test
    fun testCsvBackupParser() {
        val csv = """
        Service,Account,Secret Key
        Microsoft,admin@company.com,EP5O5BC3NZVEE7YI
        Google,myaccount@gmail.com,JBSWY3DPEHPK3PXP
        """.trimIndent()

        val items = CsvBackupParser.parse(csv)
        assertEquals(2, items.size)
        assertEquals("Microsoft", items[0].name)
        assertEquals("admin@company.com", items[0].usr)
        assertEquals("EP5O5BC3NZVEE7YI", items[0].key)

        assertEquals("Google", items[1].name)
        assertEquals("myaccount@gmail.com", items[1].usr)
        assertEquals("JBSWY3DPEHPK3PXP", items[1].key)
    }

    @Test
    fun testUnifiedTOTPParser() {
        val mixedText = """
        Some notes here
        otpauth://totp/TestApp:user1?secret=JBSWY3DPEHPK3PXP&issuer=TestApp
        otpauth://totp/AnotherApp:user2?secret=EP5O5BC3NZVEE7YI
        """.trimIndent()

        val items = TOTPParser.parse(mixedText)
        assertEquals(2, items.size)
        assertEquals("TestApp", items[0].name)
        assertEquals("user1", items[0].usr)
        assertEquals("AnotherApp", items[1].name)
        assertEquals("user2", items[1].usr)
    }

    @Test
    fun testWattToolkitBrotliQr() {
        val b64Qr = "IQIAABsgAgDkSpee7CAWzQZIPzoBBiUDgXKWTmCQZOkUgjIp3Qa4dlJRBVQRVylKoETLI+nyVNcw96J64mWV8Ryhqo0/5i9wt3oYJTPo4VfeiJiWbiqSH8i4onuiUVFkxawYvqFXDCrKExgHVNHEb9yH41AwwsoCLMzDmyHiuwX1csrAgiG7fyq0nZp4MjMPd4VmsxAMOfFoibHkNKfY2ws8FDSPybRbYToSWCBjTcWSJEmGompYEbFhAA85PZR6hMVp4nqpT8GCblmWsAw8FA8QxP5ZhFag22FJct8tvIjO7EBoEzpD80M0d1m8Fv1cJ+qGjmUVmwoPZR6DBSnLSMkiSxBYyjJhOTdneYRFIUn3v0Q/1+1ts2sGmm3bbmqpr+3TOxzH1no6VW3IHhoc6m2SGj+is1pcFCXN9zroDfBAPC8tE+Ym5ChaAz3ouxuSFHhg6RRN3PAu+rcCFJkYRFVk3TBV7InAQ+zThMXsaDM3q8ieMek0B+jXQmfmWP2h4Uxvi2YHmS70OORIXTP3a6WXU+aKYEHS0t9PpjoCjDvCllpzsLMj2I2y+Ehv4k80Cagni9KEHuEOuFGc3OF+rsW6juEkQk83HFzon0HOeevkoyV7t/3/skg4d6Nh4fxXdHW7R1jkhiTdAQ=="
        val rawBytes = Base64.getDecoder().decode(b64Qr)
        val items = WattToolkitQrParser.parse(rawBytes)
        assertEquals(1, items.size)
        assertEquals("Steam", items[0].name)
        assertEquals("cath_gao", items[0].usr)
        assertEquals("MACWJAAAIKFDU7OBBA6SP56YAYXYTI2H", items[0].key)
        assertEquals(5, items[0].digits)
        assertEquals(30, items[0].period)

        // Test via TOTPParser with watt-qr-b64: prefix
        val itemsFromFacade = TOTPParser.parse("watt-qr-b64:$b64Qr")
        assertEquals(1, itemsFromFacade.size)
        assertEquals("Steam", itemsFromFacade[0].name)
        assertEquals("cath_gao", itemsFromFacade[0].usr)
        assertEquals("MACWJAAAIKFDU7OBBA6SP56YAYXYTI2H", itemsFromFacade[0].key)
    }
}
