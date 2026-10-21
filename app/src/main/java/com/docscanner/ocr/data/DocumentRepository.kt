package com.docscanner.ocr.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.docscanner.ocr.R
import com.docscanner.ocr.model.DocumentSummary
import com.docscanner.ocr.model.ExportFormat
import com.docscanner.ocr.model.OcrBox
import com.docscanner.ocr.model.OcrLanguage
import com.docscanner.ocr.model.OcrLine
import com.docscanner.ocr.model.OcrNumber
import com.docscanner.ocr.model.PendingPage
import com.docscanner.ocr.model.ScanDocument
import com.docscanner.ocr.model.ScanPage
import com.docscanner.ocr.model.ScanRecord
import com.docscanner.ocr.util.ImageUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID

/**
 * Multi-page document storage.
 *
 * filesDir/documents/
 *   index.json          lightweight summaries (history list)
 *   docs/<id>.json      full document with recognised lines
 *   images/<id>_<pid>.jpg
 *   thumbs/<id>_<pid>.jpg
 *   trash/<id>.json     soft deleted documents (undo window)
 *
 * Metadata is written atomically and the index stays small, so opening the app
 * with hundreds of documents never parses the full OCR text.
 */
class DocumentRepository(context: Context) {

    private val appContext = context.applicationContext

    private val root = File(appContext.filesDir, "documents").apply { mkdirs() }
    private val docsDir = File(root, "docs").apply { mkdirs() }
    private val imagesDir = File(root, "images").apply { mkdirs() }
    private val thumbsDir = File(root, "thumbs").apply { mkdirs() }
    private val trashDir = File(root, "trash").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val pendingRoot = File(appContext.cacheDir, "pending").apply { mkdirs() }

    // ---------------------------------------------------------------- pending

    fun savePendingPage(
        sessionId: String,
        pageId: String,
        bytes: ByteArray,
        rotationDegrees: Int,
        autoStraighten: Boolean
    ): PendingPage? {
        val dir = pendingDirectory(sessionId)
        val image = File(dir, "$pageId.jpg")
        val thumb = File(dir, "$pageId.thumb.jpg")
        val saved = ImageUtils.savePageImage(bytes, rotationDegrees, image, thumb, autoStraighten)
        return if (saved && image.exists()) {
            PendingPage(pageId, image.absolutePath, thumb.absolutePath, System.currentTimeMillis())
        } else {
            image.delete()
            thumb.delete()
            null
        }
    }

    fun savePendingPageFromUri(
        sessionId: String,
        pageId: String,
        uri: Uri,
        autoStraighten: Boolean
    ): PendingPage? {
        val bitmap = ImageUtils.decodeFromUri(appContext, uri) ?: return null
        val dir = pendingDirectory(sessionId)
        val image = File(dir, "$pageId.jpg")
        val thumb = File(dir, "$pageId.thumb.jpg")
        val saved = try {
            ImageUtils.saveBitmapAsPage(bitmap, image, thumb, autoStraighten)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        return if (saved && image.exists()) {
            PendingPage(pageId, image.absolutePath, thumb.absolutePath, System.currentTimeMillis())
        } else {
            image.delete()
            thumb.delete()
            null
        }
    }

    fun pendingDirectory(sessionId: String): File = File(pendingRoot, sessionId).apply { mkdirs() }

    fun discardPending(sessionId: String) {
        runCatching { pendingDirectory(sessionId).deleteRecursively() }
    }

    fun deletePendingPage(page: PendingPage) {
        runCatching { File(page.path).delete() }
        runCatching { File(page.thumbPath).delete() }
    }

    // --------------------------------------------------------------- documents

    fun createDocument(languageCode: String, pending: List<PendingPage>): ScanDocument {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val pages = takePendingInto(id, pending)
        val document = ScanDocument(
            id = id,
            title = "",
            pages = pages,
            createdAt = now,
            updatedAt = now,
            languageCode = languageCode
        )
        if (pages.isNotEmpty()) saveDocument(document)
        return document
    }

    fun appendPages(documentId: String, pending: List<PendingPage>): ScanDocument? {
        val current = loadDocument(documentId) ?: return null
        val added = takePendingInto(documentId, pending)
        if (added.isEmpty()) return current
        val updated = current.copy(
            pages = current.pages + added,
            updatedAt = System.currentTimeMillis()
        )
        saveDocument(updated)
        return updated
    }

    private fun takePendingInto(documentId: String, pending: List<PendingPage>): List<ScanPage> {
        val pages = mutableListOf<ScanPage>()
        for (item in pending) {
            val imageTarget = File(imagesDir, "${documentId}_${item.id}.jpg")
            val thumbTarget = File(thumbsDir, "${documentId}_${item.id}.jpg")
            val imageMoved = moveFile(File(item.path), imageTarget)
            if (!imageMoved && !imageTarget.exists()) continue
            val thumbMoved = moveFile(File(item.thumbPath), thumbTarget)
            if (!thumbMoved && (!thumbTarget.exists() || thumbTarget.length() == 0L)) {
                ImageUtils.createThumbnailFile(imageTarget.absolutePath, thumbTarget.absolutePath)
            }
            pages += ScanPage(
                id = item.id,
                imagePath = imageTarget.absolutePath,
                thumbPath = thumbTarget.absolutePath,
                fullText = "",
                lines = emptyList(),
                numbers = emptyList(),
                createdAt = item.capturedAt
            )
        }
        pending.mapNotNull { File(it.path).parentFile }
            .distinct()
            .forEach { dir -> if (dir.listFiles().isNullOrEmpty()) runCatching { dir.delete() } }
        return pages
    }

    fun loadDocument(documentId: String): ScanDocument? {
        val file = File(docsDir, "$documentId.json")
        if (!file.exists()) return null
        return runCatching { parseDocument(file.readText()) }
            .getOrNull()
            ?.takeIf { it.id.isNotBlank() }
    }

    fun saveDocument(document: ScanDocument) {
        writeAtomic(File(docsDir, "${document.id}.json"), documentToJson(document).toString())
        val summaries = loadSummaries().filterNot { it.id == document.id } + toSummary(document)
        writeIndex(summaries.sortedByDescending { it.updatedAt })
    }

    fun renameDocument(documentId: String, title: String): ScanDocument? {
        val document = loadDocument(documentId) ?: return null
        val updated = document.copy(
            title = title.trim(),
            updatedAt = System.currentTimeMillis()
        )
        saveDocument(updated)
        return updated
    }

    fun deletePage(documentId: String, pageId: String): ScanDocument? {
        val document = loadDocument(documentId) ?: return null
        val page = document.pages.firstOrNull { it.id == pageId } ?: return document
        runCatching { File(page.imagePath).delete() }
        runCatching { File(page.thumbPath).delete() }
        val remaining = document.pages.filterNot { it.id == pageId }
        val updated = document.copy(pages = remaining, updatedAt = System.currentTimeMillis())
        saveDocument(updated)
        return updated
    }

    fun rotatePage(documentId: String, pageId: String): ScanDocument? {
        val document = loadDocument(documentId) ?: return null
        val page = document.pages.firstOrNull { it.id == pageId } ?: return document
        if (!ImageUtils.rotateFile(page.imagePath, 90f)) return null
        ImageUtils.createThumbnailFile(page.imagePath, page.thumbPath)
        val cleared = document.copy(
            pages = document.pages.map { current ->
                if (current.id == pageId) {
                    current.copy(fullText = "", lines = emptyList(), numbers = emptyList())
                } else {
                    current
                }
            },
            updatedAt = System.currentTimeMillis()
        )
        saveDocument(cleared)
        return cleared
    }

    // ----------------------------------------------------------------- history

    fun loadSummaries(): List<DocumentSummary> {
        if (indexFile.exists()) {
            val parsed = runCatching { parseSummaries(indexFile.readText()) }.getOrNull()
            if (parsed != null) return parsed
        }
        return rebuildIndex()
    }

    private fun rebuildIndex(): List<DocumentSummary> {
        val summaries = docsDir.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { parseDocument(file.readText()) }.getOrNull() }
            .filter { it.id.isNotBlank() }
            .map { toSummary(it) }
            .sortedByDescending { it.updatedAt }
        writeIndex(summaries)
        return summaries
    }

    fun softDelete(documentId: String): Boolean = runCatching {
        val source = File(docsDir, "$documentId.json")
        if (!source.exists()) return false
        val target = File(trashDir, "$documentId.json")
        if (!moveFile(source, target)) return false
        writeIndex(loadSummaries().filterNot { it.id == documentId })
        true
    }.getOrDefault(false)

    fun restore(documentId: String): Boolean = runCatching {
        val source = File(trashDir, "$documentId.json")
        if (!source.exists()) return false
        val target = File(docsDir, "$documentId.json")
        if (!moveFile(source, target)) return false
        val document = loadDocument(documentId) ?: return false
        val summaries = loadSummaries().filterNot { it.id == documentId } + toSummary(document)
        writeIndex(summaries.sortedByDescending { it.updatedAt })
        true
    }.getOrDefault(false)

    fun purge(documentId: String) {
        runCatching { File(docsDir, "$documentId.json").delete() }
        runCatching { File(trashDir, "$documentId.json").delete() }
        deleteImagesFor(documentId)
    }

    /**
     * Removes files that no document references, deletes stale captures and
     * clears trash older than a week. Runs on IO at startup.
     */
    fun cleanupOrphans() {
        runCatching { migrateLegacyIfNeeded() }
        runCatching { pendingRoot.listFiles()?.forEach { it.deleteRecursively() } }
        runCatching {
            val documents = loadAllDocuments()
            val images = mutableSetOf<String>()
            val thumbs = mutableSetOf<String>()
            documents.forEach { document ->
                document.pages.forEach { page ->
                    images += File(page.imagePath).name
                    thumbs += File(page.thumbPath).name
                }
            }
            trashDir.listFiles { file -> file.extension == "json" }.orEmpty().forEach { file ->
                runCatching { parseDocument(file.readText()) }.getOrNull()?.pages?.forEach { page ->
                    images += File(page.imagePath).name
                    thumbs += File(page.thumbPath).name
                }
            }
            imagesDir.listFiles().orEmpty().forEach { file ->
                if (file.name !in images) runCatching { file.delete() }
            }
            thumbsDir.listFiles().orEmpty().forEach { file ->
                if (file.name !in thumbs) runCatching { file.delete() }
            }
            val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MS
            trashDir.listFiles { file -> file.extension == "json" }.orEmpty().forEach { file ->
                val document = runCatching { parseDocument(file.readText()) }.getOrNull()
                if (document != null && document.updatedAt < cutoff) purge(document.id)
            }
        }
    }

    private fun loadAllDocuments(): List<ScanDocument> =
        docsDir.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { parseDocument(file.readText()) }.getOrNull() }
            .filter { it.id.isNotBlank() }

    // ------------------------------------------------------------------ export

    fun export(document: ScanDocument, uri: Uri, format: ExportFormat): Boolean = runCatching {
        val output = appContext.contentResolver.openOutputStream(uri) ?: return false
        output.use { stream ->
            when (format) {
                ExportFormat.TXT -> {
                    writeText(document, stream)
                    true
                }
                ExportFormat.PDF -> writePdf(document, stream)
            }
        }
    }.getOrDefault(false)

    private fun writeText(document: ScanDocument, output: OutputStream) {
        val title = document.title.ifBlank { appContext.getString(R.string.app_name) }
        output.write((title + "\n\n").toByteArray(Charsets.UTF_8))
        val multiplePages = document.pages.size > 1
        document.pages.forEachIndexed { index, page ->
            if (multiplePages) {
                val header = appContext.getString(R.string.export_page_header, index + 1)
                output.write((header + "\n\n").toByteArray(Charsets.UTF_8))
            }
            output.write(page.fullText.toByteArray(Charsets.UTF_8))
            output.write("\n\n".toByteArray(Charsets.UTF_8))
        }
        output.flush()
    }

    private fun writePdf(document: ScanDocument, output: OutputStream): Boolean {
        val pdf = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        return try {
            var written = 0
            document.pages.forEachIndexed { index, page ->
                val bitmap = ImageUtils.decodeFile(page.imagePath, 2000)
                if (bitmap != null) {
                    try {
                        val info = PdfDocument.PageInfo
                            .Builder(PDF_WIDTH, PDF_HEIGHT, index + 1)
                            .create()
                        val pdfPage = pdf.startPage(info)
                        val canvas = pdfPage.canvas
                        canvas.drawColor(Color.WHITE)
                        val availableWidth = PDF_WIDTH - 2 * PDF_MARGIN
                        val availableHeight = PDF_HEIGHT - 2 * PDF_MARGIN
                        val scale = minOf(
                            availableWidth.toFloat() / bitmap.width,
                            availableHeight.toFloat() / bitmap.height
                        )
                        val drawWidth = bitmap.width * scale
                        val drawHeight = bitmap.height * scale
                        val left = (PDF_WIDTH - drawWidth) / 2f
                        val top = (PDF_HEIGHT - drawHeight) / 2f
                        val destination = RectF(left, top, left + drawWidth, top + drawHeight)
                        canvas.drawBitmap(bitmap, null, destination, paint)
                        pdf.finishPage(pdfPage)
                        written++
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
            if (written == 0) {
                false
            } else {
                pdf.writeTo(output)
                output.flush()
                true
            }
        } catch (error: Exception) {
            false
        } finally {
            runCatching { pdf.close() }
        }
    }

    private fun deleteImagesFor(documentId: String) {
        imagesDir.listFiles().orEmpty()
            .filter { it.name.startsWith("${documentId}_") }
            .forEach { runCatching { it.delete() } }
        thumbsDir.listFiles().orEmpty()
            .filter { it.name.startsWith("${documentId}_") }
            .forEach { runCatching { it.delete() } }
    }

    // --------------------------------------------------------------- migration

    private fun migrateLegacyIfNeeded() {
        val legacyRoot = File(appContext.filesDir, "scans")
        val legacyIndex = File(legacyRoot, "index.json")
        if (!legacyIndex.exists()) return

        val records = runCatching {
            val array = JSONArray(legacyIndex.readText())
            (0 until array.length()).mapNotNull { index ->
                runCatching { parseLegacyRecord(array.getJSONObject(index)) }.getOrNull()
            }
        }.getOrDefault(emptyList())

        records.forEach { record ->
            runCatching { migrateLegacyRecord(record) }
        }

        runCatching {
            val marker = File(legacyRoot, "index.migrated.json")
            if (marker.exists()) marker.delete()
            legacyIndex.renameTo(marker)
            File(legacyRoot, "images").listFiles().orEmpty().forEach { file ->
                if (file.length() == 0L) file.delete()
            }
        }
    }

    private fun migrateLegacyRecord(record: ScanRecord) {
        val sourceImage = File(record.imagePath)
        if (!sourceImage.exists()) return
        val documentId = record.id.ifBlank { UUID.randomUUID().toString() }
        val pageId = "p1"
        if (File(docsDir, "$documentId.json").exists()) return

        val imageTarget = File(imagesDir, "${documentId}_$pageId.jpg")
        val thumbTarget = File(thumbsDir, "${documentId}_$pageId.jpg")
        val moved = moveFile(sourceImage, imageTarget) ||
            runCatching { sourceImage.copyTo(imageTarget, overwrite = true) }.isSuccess
        if (!moved && !imageTarget.exists()) return
        ImageUtils.createThumbnailFile(imageTarget.absolutePath, thumbTarget.absolutePath)

        val page = ScanPage(
            id = pageId,
            imagePath = imageTarget.absolutePath,
            thumbPath = thumbTarget.absolutePath,
            fullText = record.fullText,
            lines = record.lines,
            numbers = record.numbers,
            createdAt = record.createdAt
        )
        val title = record.fullText.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(60)
            .orEmpty()
        val document = ScanDocument(
            id = documentId,
            title = title,
            pages = listOf(page),
            createdAt = record.createdAt,
            updatedAt = record.createdAt,
            languageCode = OcrLanguage.ARABIC_ENGLISH.code
        )
        saveDocument(document)
    }

    // -------------------------------------------------------------------- json

    private fun documentToJson(document: ScanDocument): JSONObject = JSONObject().apply {
        put("version", 2)
        put("id", document.id)
        put("title", document.title)
        put("createdAt", document.createdAt)
        put("updatedAt", document.updatedAt)
        put("language", document.languageCode)
        put("pages", JSONArray().apply { document.pages.forEach { put(pageToJson(it)) } })
    }

    private fun pageToJson(page: ScanPage): JSONObject = JSONObject().apply {
        put("id", page.id)
        put("imagePath", page.imagePath)
        put("thumbPath", page.thumbPath)
        put("fullText", page.fullText)
        put("createdAt", page.createdAt)
        put("lines", JSONArray().apply { page.lines.forEach { put(lineToJson(it)) } })
        put("numbers", JSONArray().apply { page.numbers.forEach { put(numberToJson(it)) } })
    }

    private fun lineToJson(line: OcrLine): JSONObject = JSONObject().apply {
        put("index", line.index)
        put("text", line.text)
        put("confidence", line.confidence.toDouble())
        line.box?.let { box ->
            put("box", JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
        }
    }

    private fun numberToJson(number: OcrNumber): JSONObject = JSONObject().apply {
        put("value", number.value)
        put("normalized", number.normalized)
        put("lineIndex", number.lineIndex)
    }

    private fun parseDocument(text: String): ScanDocument {
        val json = JSONObject(text)
        val pagesArray = json.optJSONArray("pages") ?: JSONArray()
        val pages = (0 until pagesArray.length()).mapNotNull { index ->
            runCatching { parsePage(pagesArray.getJSONObject(index)) }.getOrNull()
        }
        return ScanDocument(
            id = json.optString("id"),
            title = json.optString("title"),
            pages = pages,
            createdAt = json.optLong("createdAt"),
            updatedAt = json.optLong("updatedAt"),
            languageCode = json.optString("language", OcrLanguage.ARABIC_ENGLISH.code)
        )
    }

    private fun parsePage(json: JSONObject): ScanPage = ScanPage(
        id = json.optString("id"),
        imagePath = json.optString("imagePath"),
        thumbPath = json.optString("thumbPath"),
        fullText = json.optString("fullText"),
        lines = parseLines(json.optJSONArray("lines")),
        numbers = parseNumbers(json.optJSONArray("numbers")),
        createdAt = json.optLong("createdAt")
    )

    private fun parseLines(array: JSONArray?): List<OcrLine> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val json = array.getJSONObject(index)
                val boxArray = json.optJSONArray("box")
                val box = if (boxArray != null && boxArray.length() == 4) {
                    OcrBox(
                        left = boxArray.getInt(0),
                        top = boxArray.getInt(1),
                        right = boxArray.getInt(2),
                        bottom = boxArray.getInt(3)
                    )
                } else {
                    null
                }
                OcrLine(
                    index = json.optInt("index", index),
                    text = json.optString("text"),
                    box = box,
                    confidence = json.optDouble("confidence", 0.0).toFloat()
                )
            }.getOrNull()
        }
    }

    private fun parseNumbers(array: JSONArray?): List<OcrNumber> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val json = array.getJSONObject(index)
                val value = json.optString("value")
                OcrNumber(
                    value = value,
                    normalized = json.optString("normalized", value),
                    lineIndex = json.optInt("lineIndex", -1)
                )
            }.getOrNull()
        }
    }

    private fun summaryToJson(summary: DocumentSummary): JSONObject = JSONObject().apply {
        put("id", summary.id)
        put("title", summary.title)
        put("preview", summary.preview)
        put("pageCount", summary.pageCount)
        put("numbersCount", summary.numbersCount)
        put("language", summary.languageCode)
        put("thumbPath", summary.thumbPath)
        put("createdAt", summary.createdAt)
        put("updatedAt", summary.updatedAt)
    }

    private fun parseSummaries(text: String): List<DocumentSummary> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val json = array.getJSONObject(index)
                DocumentSummary(
                    id = json.optString("id"),
                    title = json.optString("title"),
                    preview = json.optString("preview"),
                    pageCount = json.optInt("pageCount", 1),
                    numbersCount = json.optInt("numbersCount", 0),
                    languageCode = json.optString("language", OcrLanguage.ARABIC_ENGLISH.code),
                    thumbPath = json.optString("thumbPath"),
                    createdAt = json.optLong("createdAt"),
                    updatedAt = json.optLong("updatedAt")
                )
            }.getOrNull()
        }.filter { it.id.isNotBlank() }
    }

    private fun toSummary(document: ScanDocument): DocumentSummary {
        val preview = document.pages.asSequence()
            .flatMap { it.lines.asSequence() }
            .map { it.text }
            .firstOrNull { it.isNotBlank() }
            ?: document.fullText.lineSequence().firstOrNull { it.isNotBlank() }
            ?: ""
        return DocumentSummary(
            id = document.id,
            title = document.title,
            preview = preview.take(160),
            pageCount = document.pages.size,
            numbersCount = document.numbers.size,
            languageCode = document.languageCode,
            thumbPath = document.pages.firstOrNull()?.thumbPath.orEmpty(),
            createdAt = document.createdAt,
            updatedAt = document.updatedAt
        )
    }

    private fun parseLegacyRecord(json: JSONObject): ScanRecord {
        val linesArray = json.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until linesArray.length()).mapNotNull { index ->
            runCatching {
                val lineJson = linesArray.getJSONObject(index)
                val boxArray = lineJson.optJSONArray("box")
                val box = if (boxArray != null && boxArray.length() == 4) {
                    OcrBox(
                        left = boxArray.getInt(0),
                        top = boxArray.getInt(1),
                        right = boxArray.getInt(2),
                        bottom = boxArray.getInt(3)
                    )
                } else {
                    null
                }
                OcrLine(
                    index = lineJson.optInt("index", index),
                    text = lineJson.optString("text"),
                    box = box,
                    confidence = lineJson.optDouble("confidence", 0.0).toFloat()
                )
            }.getOrNull()
        }
        val numbersArray = json.optJSONArray("numbers") ?: JSONArray()
        val numbers = (0 until numbersArray.length()).mapNotNull { index ->
            runCatching {
                val numberJson = numbersArray.getJSONObject(index)
                val value = numberJson.optString("value")
                OcrNumber(
                    value = value,
                    normalized = numberJson.optString("normalized", value),
                    lineIndex = numberJson.optInt("lineIndex", -1)
                )
            }.getOrNull()
        }
        return ScanRecord(
            id = json.optString("id"),
            imagePath = json.optString("imagePath"),
            fullText = json.optString("fullText"),
            lines = lines,
            numbers = numbers,
            createdAt = json.optLong("createdAt"),
            languageLabel = json.optString("language")
        )
    }

    // ------------------------------------------------------------------ files

    private fun writeIndex(summaries: List<DocumentSummary>) {
        val array = JSONArray()
        summaries.forEach { array.put(summaryToJson(it)) }
        writeAtomic(indexFile, array.toString())
    }

    private fun writeAtomic(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(content)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            runCatching { temp.copyTo(file, overwrite = true) }
            temp.delete()
        }
    }

    private fun moveFile(source: File, target: File): Boolean {
        if (!source.exists()) return false
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return true
        return runCatching {
            source.copyTo(target, overwrite = true)
            source.delete()
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val PDF_WIDTH = 1240
        const val PDF_HEIGHT = 1754
        const val PDF_MARGIN = 36
        const val TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
