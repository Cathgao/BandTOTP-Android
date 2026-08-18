package com.lst.bandtotp.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.zxing.*
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.io.InputStream
import java.util.Base64

/**
 * Decodes QR code content from Bitmaps and image Uris using ZXing.
 * Supports both plain text and raw binary QR codes (e.g. Watt Toolkit / Brotli compressed).
 */
object QRCodeDecoder {

    private val HINTS = mapOf(
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
    )

    fun decodeFromUri(context: Context, uri: Uri): String? {
        val bitmap = loadBitmap(context, uri) ?: return null
        return decodeFromBitmap(bitmap)
    }

    fun decodeFromBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val reader = MultiFormatReader()

        // 1. Try HybridBinarizer
        try {
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decode(binaryBitmap, HINTS)
            if (result != null) {
                return extractResultPayload(result)
            }
        } catch (e: Exception) {
            // ignore and try fallback
        }

        // 2. Try GlobalHistogramBinarizer (better for low-contrast/dark/inverted QR codes)
        try {
            reader.reset()
            val binaryBitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
            val result = reader.decode(binaryBitmap, HINTS)
            if (result != null) {
                return extractResultPayload(result)
            }
        } catch (e: Exception) {
            // ignore
        }

        // 3. Try Center Cropping (for screenshots with window frames / modals)
        try {
            val cropX = (width * 0.15).toInt()
            val cropY = (height * 0.15).toInt()
            val cropW = (width * 0.7).toInt()
            val cropH = (height * 0.7).toInt()
            if (cropW > 100 && cropH > 100) {
                val croppedSource = source.crop(cropX, cropY, cropW, cropH)
                reader.reset()
                val binaryBitmap = BinaryBitmap(HybridBinarizer(croppedSource))
                val result = reader.decode(binaryBitmap, HINTS)
                if (result != null) {
                    return extractResultPayload(result)
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return null
    }

    fun extractResultPayload(result: Result): String {
        val meta = result.resultMetadata
        if (meta != null && meta.containsKey(ResultMetadataType.BYTE_SEGMENTS)) {
            @Suppress("UNCHECKED_CAST")
            val segments = meta[ResultMetadataType.BYTE_SEGMENTS] as? List<ByteArray>
            if (!segments.isNullOrEmpty()) {
                val bytes = segments[0]
                if (WattToolkitQrParser.isWattToolkitData(bytes)) {
                    val b64 = try {
                        Base64.getEncoder().encodeToString(bytes)
                    } catch (e: Exception) {
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                    return "watt-qr-b64:$b64"
                }
            }
        }
        return result.text ?: ""
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            try {
                val stream: InputStream? = context.contentResolver.openInputStream(uri)
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, options)
            } catch (e2: Exception) {
                e2.printStackTrace()
                null
            }
        }
    }
}
