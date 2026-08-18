package com.vitalis.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** Helpers to turn a CameraX [ImageProxy] into the neutral values the engine needs. */
object FrameUtils {

    /**
     * Average luma of the frame in [0,1], read straight from the Y plane.
     * Cheap enough to run per-frame; feeds the low-light gate (§8).
     */
    fun averageLuma(image: ImageProxy): Float {
        val yPlane = image.planes.getOrNull(0) ?: return -1f
        val buffer = yPlane.buffer.duplicate()
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val width = image.width
        val height = image.height
        if (width == 0 || height == 0) return -1f

        var sum = 0L
        var count = 0
        // Sub-sample: every 4th pixel on every 4th row keeps this O(frame/16).
        var row = 0
        while (row < height) {
            var col = 0
            val rowStart = row * rowStride
            while (col < width) {
                val idx = rowStart + col * pixelStride
                if (idx < buffer.limit()) {
                    sum += (buffer.get(idx).toInt() and 0xFF)
                    count++
                }
                col += 4
            }
            row += 4
        }
        return if (count == 0) -1f else (sum.toFloat() / count) / 255f
    }

    /**
     * Encode the frame as JPEG bytes for [com.vitalis.core.LivenessResult.Success].
     * Only called once, on the verified frame — the library never writes it to disk (F6).
     */
    fun toJpeg(image: ImageProxy, quality: Int = 90): ByteArray {
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Copy Y respecting row stride.
        val yBuffer = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        var pos = 0
        for (r in 0 until height) {
            var col = 0
            val rowStart = r * yRowStride
            while (col < width) {
                nv21[pos++] = yBuffer.get(rowStart + col)
                col++
            }
        }

        // Interleave V,U into NV21 chroma respecting strides.
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (r in 0 until chromaHeight) {
            for (c in 0 until chromaWidth) {
                val vIndex = r * vRowStride + c * vPixelStride
                val uIndex = r * uRowStride + c * uPixelStride
                if (pos < nv21.size) nv21[pos++] = vBuffer.get(vIndex)
                if (pos < nv21.size) nv21[pos++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }
}
