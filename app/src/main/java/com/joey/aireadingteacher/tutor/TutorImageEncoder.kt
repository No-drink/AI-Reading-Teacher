package com.joey.aireadingteacher.tutor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TutorImageEncoder {
    suspend fun encode(file: File): EncodedTutorImage = withContext(Dispatchers.IO) {
        require(file.isFile) { "Stable screenshot does not exist" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Stable screenshot is invalid" }

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_DIMENSION * 2 ||
            bounds.outHeight / sampleSize > MAX_DIMENSION * 2
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: error("Unable to decode stable screenshot")
        try {
            EncodedTutorImage(compressWithinLimit(decoded), "image/jpeg")
        } finally {
            decoded.recycle()
        }
    }

    private fun compressWithinLimit(source: Bitmap): ByteArray {
        var current = source
        var ownsCurrent = false
        try {
            for (dimension in DIMENSION_ATTEMPTS) {
                val resized = scaleDown(current, dimension)
                if (resized !== current) {
                    if (ownsCurrent) current.recycle()
                    current = resized
                    ownsCurrent = true
                }
                for (quality in QUALITY_ATTEMPTS) {
                    val bytes = compress(current, quality)
                    if (bytes.size <= RealtimePayloadLimits.MAX_IMAGE_BYTES) return bytes
                }
            }
            val fallback = compress(current, FALLBACK_QUALITY)
            check(fallback.size <= RealtimePayloadLimits.MAX_IMAGE_BYTES) {
                "Tutor screenshot remains too large after compression"
            }
            return fallback
        } finally {
            if (ownsCurrent) current.recycle()
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            "Unable to encode tutor screenshot"
        }
        return output.toByteArray()
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largest
        return bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            filter = true,
        )
    }

    companion object {
        private const val MAX_DIMENSION = 1_600
        private val DIMENSION_ATTEMPTS = intArrayOf(MAX_DIMENSION, 1_280, 1_024, 800, 640)
        private val QUALITY_ATTEMPTS = intArrayOf(82, 70, 58, 46)
        private const val FALLBACK_QUALITY = 35
    }
}

data class EncodedTutorImage(val bytes: ByteArray, val mimeType: String)
