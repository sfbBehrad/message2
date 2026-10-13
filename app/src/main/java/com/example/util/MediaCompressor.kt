package com.example.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.math.min

object MediaCompressor {

    private const val MAX_DIMENSION = 800
    private const val COMPRESSION_QUALITY = 50

    /**
     * Scales bitmap to max 800px dimension and compresses to JPEG at 50% quality.
     */
    fun compressBitmap(rawBytes: ByteArray, maxDim: Int = MAX_DIMENSION, quality: Int = COMPRESSION_QUALITY): ByteArray {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)

            val width = options.outWidth
            val height = options.outHeight

            if (width <= 0 || height <= 0) return rawBytes

            var inSampleSize = 1
            if (width > maxDim || height > maxDim) {
                val halfWidth = width / 2
                val halfHeight = height / 2
                while ((halfWidth / inSampleSize) >= maxDim && (halfHeight / inSampleSize) >= maxDim) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            val sampledBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return rawBytes

            // Scale down to exact bounds if still over maxDim
            val maxCurrent = max(sampledBitmap.width, sampledBitmap.height)
            val finalBitmap = if (maxCurrent > maxDim) {
                val ratio = maxDim.toFloat() / maxCurrent.toFloat()
                val targetW = (sampledBitmap.width * ratio).toInt()
                val targetH = (sampledBitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(sampledBitmap, targetW, targetH, true)
            } else {
                sampledBitmap
            }

            val outputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val compressedBytes = outputStream.toByteArray()

            if (finalBitmap != sampledBitmap) {
                sampledBitmap.recycle()
            }
            finalBitmap.recycle()

            compressedBytes
        } catch (e: Exception) {
            e.printStackTrace()
            rawBytes
        }
    }

    /**
     * GZIP compresses a byte array to significantly shrink payloads over 26-byte radio chunks.
     */
    fun gzipCompress(data: ByteArray): ByteArray {
        return try {
            val byteStream = ByteArrayOutputStream()
            GZIPOutputStream(byteStream).use { gzipStream ->
                gzipStream.write(data)
                gzipStream.finish()
            }
            byteStream.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            data
        }
    }

    /**
     * Decompresses GZIP data, falling back to original bytes if not GZIP formatted.
     */
    fun gzipDecompress(data: ByteArray): ByteArray {
        // GZIP Magic numbers: 0x1F, 0x8B
        if (data.size < 2 || data[0] != 0x1F.toByte() || data[1] != 0x8B.toByte()) {
            return data
        }
        return try {
            val byteIn = ByteArrayInputStream(data)
            val gzipIn = GZIPInputStream(byteIn)
            val byteOut = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var len: Int
            while (gzipIn.read(buffer).also { len = it } > 0) {
                byteOut.write(buffer, 0, len)
            }
            byteOut.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            data
        }
    }
}
