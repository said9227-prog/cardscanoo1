package com.docscanner.ocr.ocr

import com.docscanner.ocr.model.OcrLine
import com.docscanner.ocr.model.OcrNumber

/**
 * Finds numbers in recognised lines, keeping the page order:
 * line by line, and left to right inside each line.
 */
object NumberExtractor {

    /**
     * Matches Latin digits, Arabic-Indic digits (٠-٩) and Eastern Arabic-Indic digits (۰-۹),
     * including decimal/thousand separators, dates, times, a trailing percent sign,
     * and digit groups separated by spaces (e.g. card / WiFi numbers printed as
     * "546 464 664").
     */
    val NUMBER_REGEX = Regex(
        "[0-9\\u0660-\\u0669\\u06F0-\\u06F9]+" +
            "(?:[.,\\u066B\\u066C:/\\-][0-9\\u0660-\\u0669\\u06F0-\\u06F9]+)*" +
            "(?:[ \\u00A0]+[0-9\\u0660-\\u0669\\u06F0-\\u06F9]+)*%?"
    )

    fun findAll(text: String): List<MatchResult> = NUMBER_REGEX.findAll(text).toList()

    fun extract(lines: List<OcrLine>): List<OcrNumber> {
        val numbers = mutableListOf<OcrNumber>()
        lines.forEach { line ->
            findAll(line.text).forEach { match ->
                val value = match.value.trim()
                if (value.isNotEmpty()) {
                    numbers += OcrNumber(
                        value = value,
                        normalized = normalizeDigits(value),
                        lineIndex = line.index
                    )
                }
            }
        }
        return numbers
    }

    /** Converts Arabic-Indic digits and separators to their Latin equivalents. */
    fun normalizeDigits(text: String): String {
        val builder = StringBuilder(text.length)
        for (character in text) {
            builder.append(
                when (character) {
                    in '\u0660'..'\u0669' -> ('0' + (character - '\u0660'))
                    in '\u06F0'..'\u06F9' -> ('0' + (character - '\u06F0'))
                    '\u066B' -> '.'
                    '\u066C' -> ','
                    else -> character
                }
            )
        }
        return builder.toString()
    }
}
