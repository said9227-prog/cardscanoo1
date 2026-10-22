package com.docscanner.ocr.ocr

import android.graphics.Bitmap
import com.docscanner.ocr.model.OcrBox
import com.docscanner.ocr.model.OcrLanguage
import com.docscanner.ocr.model.OcrLine
import com.docscanner.ocr.model.OcrResult
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google ML Kit on-device text recognition (v2, bundled Latin model).
 *
 * The model ships inside the APK, so recognition works with no network. It reads
 * Latin script and ASCII digits very accurately, which is what card numbers, WiFi
 * passwords and English text need. It does not support Arabic script; those pages
 * are handled by [TesseractOcrEngine] through [HybridOcrEngine].
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(
        bitmap: Bitmap,
        language: OcrLanguage
    ): OcrResult = withContext(Dispatchers.Default) {
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
        val lines = mutableListOf<OcrLine>()
        var index = 0

        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val content = line.text.trim()
                if (content.isNotEmpty()) {
                    val rect = line.boundingBox
                    lines += OcrLine(
                        index = index++,
                        text = content,
                        box = rect?.let { OcrBox(it.left, it.top, it.right, it.bottom) },
                        // ML Kit does not expose a documented line confidence; 0 means
                        // "unknown" and is never filtered out by the confidence setting.
                        confidence = 0f
                    )
                }
            }
        }

        OcrResult(
            fullText = lines.joinToString(separator = "\n") { it.text },
            lines = lines,
            numbers = NumberExtractor.extract(lines)
        )
    }

    override fun close() {
        runCatching { recognizer.close() }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        continuation.cancel()
    }
}
