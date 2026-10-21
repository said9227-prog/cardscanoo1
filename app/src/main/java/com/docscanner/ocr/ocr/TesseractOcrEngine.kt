package com.docscanner.ocr.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.docscanner.ocr.R
import com.docscanner.ocr.model.OcrBox
import com.docscanner.ocr.model.OcrLanguage
import com.docscanner.ocr.model.OcrLine
import com.docscanner.ocr.model.OcrResult
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device OCR using Tesseract 5 (Tesseract4Android).
 *
 * The trained models live in assets/tessdata and are copied to the app's private
 * storage on first use, because Tesseract reads them from the file system.
 */
class TesseractOcrEngine(private val context: Context) : OcrEngine {

    private val lock = Any()
    private var baseApi: TessBaseAPI? = null
    private var initializedLanguage: String? = null

    override suspend fun recognize(
        bitmap: Bitmap,
        language: OcrLanguage
    ): OcrResult = withContext(Dispatchers.Default) {
        val api = synchronized(lock) { obtainApi(language) }
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        synchronized(lock) {
            api.setImage(argb)
            var lines = readLines(api)

            // Safety net: if the iterator returned nothing but Tesseract did read
            // text, fall back to the plain text result (without boxes).
            if (lines.isEmpty()) {
                val plainText = api.getUTF8Text().orEmpty()
                lines = plainText
                    .split('\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapIndexed { index, line ->
                        OcrLine(index = index, text = line, box = null, confidence = 0f)
                    }
            }

            OcrResult(
                fullText = lines.joinToString(separator = "\n") { it.text },
                lines = lines,
                numbers = NumberExtractor.extract(lines)
            )
        }
    }

    private fun obtainApi(language: OcrLanguage): TessBaseAPI {
        baseApi?.let { existing ->
            if (initializedLanguage == language.code) return existing
            runCatching { existing.recycle() }
            baseApi = null
            initializedLanguage = null
        }

        val dataPath = ensureTessData(language)
        val api = TessBaseAPI()
        val config = mapOf(
            "preserve_interword_spaces" to "1",
            "user_defined_dpi" to "300"
        )
        var initialized = api.init(dataPath, language.code, TessBaseAPI.OEM_LSTM_ONLY, config)
        if (!initialized) {
            // Older / combined models may need the default engine mode.
            initialized = api.init(dataPath, language.code, TessBaseAPI.OEM_DEFAULT, config)
        }
        if (!initialized) {
            runCatching { api.recycle() }
            throw IllegalStateException(context.getString(R.string.error_engine_init))
        }
        api.setPageSegMode(TessBaseAPI.PSM_AUTO)
        baseApi = api
        initializedLanguage = language.code
        return api
    }

    private fun ensureTessData(language: OcrLanguage): String {
        val root = File(context.filesDir, "tesseract").apply { mkdirs() }
        val tessdata = File(root, "tessdata").apply { mkdirs() }

        language.code.split('+').forEach { code ->
            val target = File(tessdata, "$code.traineddata")
            if (!target.exists() || target.length() <= 0L) {
                context.assets.open("tessdata/$code.traineddata").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return root.absolutePath
    }

    /**
     * Walks the page word by word. Tesseract's iterator already returns words in
     * reading order (including right-to-left scripts). Words are grouped into the
     * line they belong to, so the output keeps the exact order of the page.
     */
    private fun readLines(api: TessBaseAPI): List<OcrLine> {
        val iterator = api.resultIterator ?: return emptyList()
        val lines = mutableListOf<OcrLine>()
        try {
            iterator.begin()

            var positioned = iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_WORD)
            while (!positioned) {
                if (!iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD)) return emptyList()
                positioned = iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_WORD)
            }

            val builder = StringBuilder()
            var startNewLine = true
            var lineIndex = 0
            var confidenceSum = 0f
            var confidenceCount = 0
            var hasBox = false
            var left = 0
            var top = 0
            var right = 0
            var bottom = 0

            fun resetLine() {
                builder.setLength(0)
                confidenceSum = 0f
                confidenceCount = 0
                hasBox = false
            }

            fun flushLine() {
                val text = builder.toString().trim()
                if (text.isNotEmpty()) {
                    val box = if (hasBox) OcrBox(left, top, right, bottom) else null
                    val confidence = if (confidenceCount > 0) confidenceSum / confidenceCount else 0f
                    lines += OcrLine(index = lineIndex, text = text, box = box, confidence = confidence)
                    lineIndex++
                }
                resetLine()
            }

            while (true) {
                if (startNewLine) flushLine()

                val word = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val cleanWord = word?.trim().orEmpty()
                if (cleanWord.isNotEmpty()) {
                    if (builder.isNotEmpty()) builder.append(' ')
                    builder.append(cleanWord)

                    confidenceSum += iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    confidenceCount++

                    val rect: Rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (!hasBox) {
                        left = rect.left
                        top = rect.top
                        right = rect.right
                        bottom = rect.bottom
                        hasBox = true
                    } else {
                        left = minOf(left, rect.left)
                        top = minOf(top, rect.top)
                        right = maxOf(right, rect.right)
                        bottom = maxOf(bottom, rect.bottom)
                    }
                }

                if (!iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD)) break
                startNewLine = iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
            }

            flushLine()
        } finally {
            runCatching { iterator.delete() }
        }
        return lines
    }

    override fun close() {
        synchronized(lock) {
            baseApi?.let { api -> runCatching { api.recycle() } }
            baseApi = null
            initializedLanguage = null
        }
    }
}
