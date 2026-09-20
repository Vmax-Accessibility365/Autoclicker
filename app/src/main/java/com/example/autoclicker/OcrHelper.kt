package com.example.autoclicker

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrTextBlock(val text: String, val boundingBox: Rect)

class OcrHelper {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Callback-style API (kept for parity with the original design doc).
     */
    fun recognizeText(bitmap: Bitmap, callback: (List<OcrTextBlock>) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val results = mutableListOf<OcrTextBlock>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        results.add(OcrTextBlock(line.text, box))
                    }
                }
                callback(results)
            }
            .addOnFailureListener {
                callback(emptyList())
            }
    }

    /**
     * Suspend wrapper so the main loop can `await` OCR results instead of
     * racing the next screenshot against an in-flight callback.
     */
    suspend fun recognizeTextSuspend(bitmap: Bitmap): List<OcrTextBlock> =
        suspendCancellableCoroutine { cont ->
            recognizeText(bitmap) { results ->
                if (cont.isActive) cont.resume(results)
            }
        }

    fun close() {
        recognizer.close()
    }
}
