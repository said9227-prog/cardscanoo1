package com.docscanner.ocr.ocr

import android.graphics.Bitmap
import com.docscanner.ocr.model.OcrLanguage
import com.docscanner.ocr.model.OcrResult

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap, language: OcrLanguage): OcrResult
    fun close()
}
