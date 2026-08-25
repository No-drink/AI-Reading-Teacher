package com.joey.aireadingteacher.capture

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream

class FrameProcessor(private val outputDirectory: File) {
    /**
     * Aggressively samples the full-resolution RGBA frame into a small grayscale signature.
     * This avoids allocating a full Bitmap for routine change detection.
     */
    fun sample(image: Image, sampleWidth: Int, sampleHeight: Int): ByteArray {
        require(sampleWidth > 0 && sampleHeight > 0) { "Sample dimensions must be positive" }
        val plane = image.planes.first()
        require(plane.pixelStride >= 3) { "Unexpected screen frame pixel stride" }

        val buffer = plane.buffer
        val signature = ByteArray(sampleWidth * sampleHeight)
        for (sampleY in 0 until sampleHeight) {
            val sourceY = ((sampleY * image.height) + image.height / 2) / sampleHeight
            for (sampleX in 0 until sampleWidth) {
                val sourceX = ((sampleX * image.width) + image.width / 2) / sampleWidth
                val offset =
                    sourceY.coerceAtMost(image.height - 1) * plane.rowStride +
                        sourceX.coerceAtMost(image.width - 1) * plane.pixelStride
                val firstChannel = buffer.get(offset).toInt() and 0xFF
                val secondChannel = buffer.get(offset + 1).toInt() and 0xFF
                val thirdChannel = buffer.get(offset + 2).toInt() and 0xFF
                signature[sampleY * sampleWidth + sampleX] =
                    ((firstChannel + secondChannel + thirdChannel) / 3).toByte()
            }
        }
        return signature
    }

    fun save(image: Image): File {
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        buffer.rewind()
        val paddedBitmap = createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)

        val frameBitmap = if (paddedWidth == image.width) {
            paddedBitmap
        } else {
            Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height).also {
                paddedBitmap.recycle()
            }
        }

        try {
            check(outputDirectory.exists() || outputDirectory.mkdirs()) {
                "Unable to create debug capture directory"
            }
            val temporaryFile = File(outputDirectory, "$OUTPUT_FILE_NAME.tmp")
            FileOutputStream(temporaryFile).use { stream ->
                check(frameBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Unable to encode debug capture"
                }
            }

            val outputFile = File(outputDirectory, OUTPUT_FILE_NAME)
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "Could not remove the previous debug frame")
            }
            check(temporaryFile.renameTo(outputFile)) {
                "Unable to publish the latest debug capture"
            }
            return outputFile
        } finally {
            frameBitmap.recycle()
        }
    }

    companion object {
        private const val TAG = "AIReadingTeacher"
        const val OUTPUT_FILE_NAME = "latest_capture.png"
    }
}
