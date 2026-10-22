package com.docscanner.ocr.ocr

import android.content.Context
import android.graphics.Bitmap
import com.docscanner.ocr.model.OcrBox
import com.docscanner.ocr.model.OcrLanguage
import com.docscanner.ocr.model.OcrLine
import com.docscanner.ocr.model.OcrResult
import kotlin.math.max
import kotlin.math.min

/**
 * Picks the best on-device engine for the requested language:
 *
 *  - English  -> ML Kit (Latin + digits, bundled offline model).
 *  - Arabic   -> Tesseract (`ara`), because ML Kit has no offline Arabic model.
 *  - Arabic + English -> both engines, merged line by line. Arabic lines come from
 *    Tesseract, Latin/digit lines are taken from ML Kit when it recognised them
 *    better, and ML Kit-only digit lines (numbers Tesseract missed) are added.
 *
 * Both engines receive the same prepared bitmap, so their bounding boxes share a
 * coordinate space and can be compared directly.
 */
class HybridOcrEngine(context: Context) : OcrEngine {

    private val tesseract = TesseractOcrEngine(context)
    private val mlKit = MlKitOcrEngine()

    override suspend fun recognize(
        bitmap: Bitmap,
        language: OcrLanguage
    ): OcrResult = when (language) {
        OcrLanguage.ENGLISH -> mlKit.recognize(bitmap, language)
        OcrLanguage.ARABIC -> tesseract.recognize(bitmap, language)
        OcrLanguage.ARABIC_ENGLISH -> {
            val arabic = tesseract.recognize(bitmap, language)
            val latin = runCatching {
                mlKit.recognize(bitmap, OcrLanguage.ENGLISH)
            }.getOrNull()
            when {
                latin == null || latin.lines.isEmpty() -> arabic
                arabic.lines.isEmpty() -> latin
                else -> merge(arabic, latin)
            }
        }
    }

    override fun close() {
        tesseract.close()
        mlKit.close()
    }

    private fun merge(primary: OcrResult, latin: OcrResult): OcrResult {
        val usedLatin = BooleanArray(latin.lines.size)
        val merged = mutableListOf<OcrLine>()

        primary.lines.forEach { line ->
            val primaryBox = line.box

            var bestIndex = -1
            var bestScore = 0f
            if (primaryBox != null && !containsArabic(line.text)) {
                latin.lines.forEachIndexed { index, candidate ->
                    val candidateBox = candidate.box ?: return@forEachIndexed
                    if (usedLatin[index]) return@forEachIndexed
                    val score = overlap(primaryBox, candidateBox)
                    if (score > bestScore) {
                        bestScore = score
                        bestIndex = index
                    }
                }
            }

            if (bestIndex >= 0 && bestScore >= MIN_OVERLAP) {
                usedLatin[bestIndex] = true
                val latinLine = latin.lines[bestIndex]
                merged += line.copy(
                    text = chooseText(line.text, latinLine.text),
                    confidence = max(line.confidence, latinLine.confidence),
                    box = primaryBox ?: latinLine.box
                )
            } else {
                merged += line
            }
        }

        // Only add ML Kit-only lines when they contain a number. This recovers the
        // digits Tesseract missed without letting Latin garbage from Arabic pages in.
        latin.lines.forEachIndexed { index, line ->
            if (!usedLatin[index] && line.text.isNotBlank() && containsDigit(line.text)) {
                merged += line
            }
        }

        val primaryHasBoxes = primary.lines.any { it.box != null }
        val ordered = if (primaryHasBoxes) {
            merged.sortedWith(
                compareBy(
                    { it.box?.top ?: Int.MAX_VALUE },
                    { it.box?.left ?: Int.MAX_VALUE }
                )
            )
        } else {
            merged
        }

        val deduped = mutableListOf<OcrLine>()
        ordered.forEach { line ->
            val duplicate = deduped.any { existing ->
                existing.text == line.text && boxesOverlap(existing.box, line.box)
            }
            if (!duplicate) deduped += line
        }

        val reindexed = deduped.mapIndexed { index, line -> line.copy(index = index) }
        return OcrResult(
            fullText = reindexed.joinToString(separator = "\n") { it.text },
            lines = reindexed,
            numbers = NumberExtractor.extract(reindexed)
        )
    }

    private fun chooseText(primary: String, latin: String): String {
        val primaryHasDigit = containsDigit(primary)
        val latinHasDigit = containsDigit(latin)
        return when {
            latinHasDigit && !primaryHasDigit -> latin
            latin.length >= (primary.length * 0.7f) -> latin
            else -> primary
        }
    }

    private fun containsDigit(text: String): Boolean =
        NumberExtractor.NUMBER_REGEX.containsMatchIn(text)

    private fun containsArabic(text: String): Boolean = text.any { character ->
        character.code in 0x0600..0x06FF ||
            character.code in 0x0750..0x077F ||
            character.code in 0x08A0..0x08FF ||
            character.code in 0xFB50..0xFDFF ||
            character.code in 0xFE70..0xFEFF
    }

    private fun overlap(a: OcrBox, b: OcrBox): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left).toLong() * (bottom - top).toLong()
        val union = a.width.toLong() * a.height +
            b.width.toLong() * b.height - intersection
        if (union <= 0L) return 0f
        return intersection.toFloat() / union.toFloat()
    }

    private fun boxesOverlap(a: OcrBox?, b: OcrBox?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return overlap(a, b) >= 0.5f
    }

    private companion object {
        const val MIN_OVERLAP = 0.12f
    }
}
