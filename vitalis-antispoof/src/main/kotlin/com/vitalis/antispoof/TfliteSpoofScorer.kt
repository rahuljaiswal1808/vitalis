package com.vitalis.antispoof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.ImageProxy
import com.vitalis.camera.FrameUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * TensorFlow Lite passive spoof scorer — **SCAFFOLD ONLY**.
 *
 * No validated model ships with Vitalis (feature doc §5, §10 step 5): shipping an
 * un-evaluated model gives false confidence. This class is the integration point for a
 * model you have trained/sourced (e.g. a MobileNet binary real-vs-spoof classifier on
 * CelebA-Spoof / NUAA) and can **quote an eval number for**.
 *
 * Contract of the supplied `.tflite`:
 *  - input : [1, inputSize, inputSize, 3] float32, RGB, normalized to [0,1]
 *  - output: [1, 1] float32 sigmoid = probability the face is REAL
 *
 * If the asset is missing or fails to load, [isAvailable] is false and the orchestrator
 * silently runs active-challenge-only — it never crashes the session.
 */
class TfliteSpoofScorer private constructor(
    private val interpreter: Interpreter?,
    private val inputSize: Int
) : PassiveSpoofScorer {

    override val isAvailable: Boolean get() = interpreter != null

    override fun scoreRealProbability(image: ImageProxy): Float {
        val interp = interpreter ?: return 1f
        return try {
            val bitmap = imageToBitmap(image) ?: return 1f
            val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val input = toInputBuffer(scaled)
            val output = Array(1) { FloatArray(1) }
            interp.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "Passive inference failed; treating frame as inconclusive.", t)
            1f
        }
    }

    override fun close() {
        interpreter?.close()
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap? {
        val jpeg = FrameUtils.toJpeg(image, quality = 85)
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    private fun toInputBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255f) // R
            buffer.putFloat(((p shr 8) and 0xFF) / 255f)  // G
            buffer.putFloat((p and 0xFF) / 255f)          // B
        }
        buffer.rewind()
        return buffer
    }

    companion object {
        private const val TAG = "VitalisPassive"

        /**
         * Try to load a model from `assets/[assetPath]`. Returns a scorer whose
         * [isAvailable] is false if the asset is absent — safe to call unconditionally.
         */
        @JvmStatic
        @JvmOverloads
        fun fromAsset(
            context: Context,
            assetPath: String = "vitalis_spoof.tflite",
            inputSize: Int = 128
        ): TfliteSpoofScorer {
            val interpreter = try {
                context.assets.openFd(assetPath).use { fd ->
                    java.io.FileInputStream(fd.fileDescriptor).channel.use { channel ->
                        val buffer = channel.map(
                            java.nio.channels.FileChannel.MapMode.READ_ONLY,
                            fd.startOffset,
                            fd.declaredLength
                        )
                        Interpreter(buffer)
                    }
                }
            } catch (t: Throwable) {
                Log.i(TAG, "No passive model at assets/$assetPath; running active-only. (${t.message})")
                null
            }
            return TfliteSpoofScorer(interpreter, inputSize)
        }
    }
}
