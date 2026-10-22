package com.docscanner.ocr.model

import androidx.annotation.StringRes
import com.docscanner.ocr.R
import kotlin.math.roundToInt

/**
 * Recognition language. The bundled ML Kit model reads Latin script and ASCII
 * digits; old stored codes map to English so existing documents still open.
 */
enum class OcrLanguage(val code: String, @StringRes val labelRes: Int) {
    ENGLISH("eng", R.string.lang_english);

    companion object {
        fun fromCode(code: String?): OcrLanguage = ENGLISH
    }
}

/** How a page image is prepared before it is handed to the OCR engine. */
enum class PreprocessMode(@StringRes val labelRes: Int) {
    AUTO(R.string.preprocess_auto),
    GRAYSCALE(R.string.preprocess_grayscale),
    BINARIZED(R.string.preprocess_binarized),
    COLOR(R.string.preprocess_color);

    companion object {
        fun fromName(name: String?): PreprocessMode =
            values().firstOrNull { it.name == name } ?: AUTO
    }
}

enum class ThemeMode(@StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark);

    companion object {
        fun fromName(name: String?): ThemeMode =
            values().firstOrNull { it.name == name } ?: SYSTEM
    }
}

enum class SortOrder(@StringRes val labelRes: Int) {
    NEWEST(R.string.sort_newest),
    OLDEST(R.string.sort_oldest),
    NAME(R.string.sort_name)
}

enum class ExportFormat { TXT, PDF }

/** Bounding box of a text line, in the coordinate space of the page image. */
data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    fun scaled(scaleX: Float, scaleY: Float): OcrBox = OcrBox(
        left = (left * scaleX).roundToInt(),
        top = (top * scaleY).roundToInt(),
        right = (right * scaleX).roundToInt(),
        bottom = (bottom * scaleY).roundToInt()
    )
}

/** One recognised line. [index] keeps the reading order of the page. */
data class OcrLine(
    val index: Int,
    val text: String,
    val box: OcrBox?,
    val confidence: Float
)

/** A number exactly as it was recognised, plus its Latin-digit form. */
data class OcrNumber(
    val value: String,
    val normalized: String,
    val lineIndex: Int
)

data class OcrResult(
    val fullText: String,
    val lines: List<OcrLine>,
    val numbers: List<OcrNumber>
) {
    val numbersText: String get() = numbers.joinToString(separator = "\n") { it.value }
    val numbersNormalizedText: String get() = numbers.joinToString(separator = "\n") { it.normalized }
}

/** One scanned page: image on disk plus the text recognised from it. */
data class ScanPage(
    val id: String,
    val imagePath: String,
    val thumbPath: String,
    val fullText: String,
    val lines: List<OcrLine>,
    val numbers: List<OcrNumber>,
    val createdAt: Long
) {
    val numbersText: String get() = numbers.joinToString(separator = "\n") { it.value }
}

/** A multi-page document stored in the app's private storage. */
data class ScanDocument(
    val id: String,
    val title: String,
    val pages: List<ScanPage>,
    val createdAt: Long,
    val updatedAt: Long,
    val languageCode: String
) {
    val language: OcrLanguage get() = OcrLanguage.fromCode(languageCode)
    val fullText: String
        get() = pages.map { it.fullText }.filter { it.isNotBlank() }.joinToString("\n\n")
    val numbers: List<OcrNumber> get() = pages.flatMap { it.numbers }
    val numbersText: String get() = numbers.joinToString("\n") { it.value }
    val numbersNormalizedText: String get() = numbers.joinToString("\n") { it.normalized }
    val linesCount: Int get() = pages.sumOf { it.lines.size }
    val charactersCount: Int get() = pages.sumOf { it.fullText.length }
    val wordsCount: Int
        get() = fullText.split(WHITESPACE).count { it.isNotBlank() }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** Lightweight row shown in the history list (read from index.json). */
data class DocumentSummary(
    val id: String,
    val title: String,
    val preview: String,
    val pageCount: Int,
    val numbersCount: Int,
    val languageCode: String,
    val thumbPath: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** A captured page that is still in the camera session (cacheDir, not yet a document). */
data class PendingPage(
    val id: String,
    val path: String,
    val thumbPath: String,
    val capturedAt: Long
)

/** Legacy single-scan record (v1 storage), kept only to migrate old installs. */
data class ScanRecord(
    val id: String,
    val imagePath: String,
    val fullText: String,
    val lines: List<OcrLine>,
    val numbers: List<OcrNumber>,
    val createdAt: Long,
    val languageLabel: String
)
