package com.docscanner.ocr

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docscanner.ocr.data.DocumentRepository
import com.docscanner.ocr.data.SettingsRepository
import com.docscanner.ocr.data.ThumbnailLoader
import com.docscanner.ocr.model.DocumentSummary
import com.docscanner.ocr.model.ExportFormat
import com.docscanner.ocr.model.OcrLanguage
import com.docscanner.ocr.model.OcrResult
import com.docscanner.ocr.model.PendingPage
import com.docscanner.ocr.model.PreprocessMode
import com.docscanner.ocr.model.ScanDocument
import com.docscanner.ocr.model.ScanPage
import com.docscanner.ocr.model.ThemeMode
import com.docscanner.ocr.ocr.NumberExtractor
import com.docscanner.ocr.ocr.OcrEngine
import com.docscanner.ocr.ocr.TesseractOcrEngine
import com.docscanner.ocr.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data object Camera : Screen
    data object Result : Screen
    data object History : Screen
    data object Settings : Screen
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class DocumentsDeleted(val ids: List<String>, val text: String) : UiEvent
}

data class UiState(
    val screen: Screen = Screen.Home,
    val language: OcrLanguage = OcrLanguage.ARABIC_ENGLISH,
    val settings: com.docscanner.ocr.data.AppSettings = com.docscanner.ocr.data.AppSettings(),
    val summaries: List<DocumentSummary> = emptyList(),
    val document: ScanDocument? = null,
    val pageIndex: Int = 0,
    val pendingPages: List<PendingPage> = emptyList(),
    val appendDocumentId: String? = null,
    val isProcessing: Boolean = false,
    val processedCount: Int = 0,
    val totalToProcess: Int = 0,
    val isBusy: Boolean = false,
    val errorMessage: String? = null
) {
    val currentPage: ScanPage? get() = document?.pages?.getOrNull(pageIndex)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val engine: OcrEngine = TesseractOcrEngine(application)
    private val repository = DocumentRepository(application)
    private val settingsRepository = SettingsRepository.get(application)

    private val initialSettings = settingsRepository.settings.value
    private val _state = MutableStateFlow(
        UiState(settings = initialSettings, language = initialSettings.language)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var ocrJob: Job? = null
    private var captureSession: String? = null
    private val captureLock = Mutex()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update { it.copy(settings = settings, language = settings.language) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.cleanupOrphans()
            val summaries = repository.loadSummaries()
            _state.update { it.copy(summaries = summaries) }
        }
    }

    // ------------------------------------------------------------- navigation

    fun openHome() {
        _state.update { it.copy(screen = Screen.Home) }
    }

    fun openHistory() {
        _state.update { it.copy(screen = Screen.History) }
        refreshDocuments()
    }

    fun openSettings() {
        _state.update { it.copy(screen = Screen.Settings) }
    }

    fun openDocument(documentId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true) }
            val document = withContext(Dispatchers.IO) { repository.loadDocument(documentId) }
            _state.update { current ->
                if (document == null) {
                    current.copy(isBusy = false)
                } else {
                    current.copy(
                        isBusy = false,
                        document = document,
                        pageIndex = 0,
                        screen = Screen.Result
                    )
                }
            }
            if (document == null) sendMessage(R.string.error_document_load)
        }
    }

    fun refreshDocuments() {
        viewModelScope.launch {
            val summaries = withContext(Dispatchers.IO) { repository.loadSummaries() }
            _state.update { it.copy(summaries = summaries) }
        }
    }

    // --------------------------------------------------------------- settings

    fun selectLanguage(language: OcrLanguage) = settingsRepository.setLanguage(language)

    fun setThemeMode(mode: ThemeMode) = settingsRepository.setThemeMode(mode)

    fun setPreprocessMode(mode: PreprocessMode) = settingsRepository.setPreprocessMode(mode)

    fun setMinConfidence(value: Int) = settingsRepository.setMinConfidence(value)

    fun setAutoStraighten(enabled: Boolean) = settingsRepository.setAutoStraighten(enabled)

    fun setShowBoxes(enabled: Boolean) = settingsRepository.setShowBoxes(enabled)

    fun setColorfulCards(enabled: Boolean) = settingsRepository.setColorfulCards(enabled)

    // ---------------------------------------------------------------- capture

    fun startCapture() {
        captureSession = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                screen = Screen.Camera,
                pendingPages = emptyList(),
                appendDocumentId = null
            )
        }
    }

    fun startAppendCapture(documentId: String) {
        captureSession = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                screen = Screen.Camera,
                pendingPages = emptyList(),
                appendDocumentId = documentId
            )
        }
    }

    fun cancelCapture() {
        val session = captureSession
        captureSession = null
        _state.update {
            it.copy(
                screen = Screen.Home,
                pendingPages = emptyList(),
                appendDocumentId = null
            )
        }
        if (session != null) {
            viewModelScope.launch(Dispatchers.IO) { repository.discardPending(session) }
        }
    }

    fun addCapturedPage(bytes: ByteArray, rotationDegrees: Int) {
        val session = captureSession ?: return
        val pageId = UUID.randomUUID().toString()
        val autoStraighten = _state.value.settings.autoStraighten
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                captureLock.withLock {
                    repository.savePendingPage(session, pageId, bytes, rotationDegrees, autoStraighten)
                }
            }
            if (page == null) {
                if (captureSession == session) sendMessage(R.string.error_load_image)
            } else if (captureSession != session) {
                withContext(Dispatchers.IO) { repository.deletePendingPage(page) }
            } else {
                _state.update { it.copy(pendingPages = it.pendingPages + page) }
            }
        }
    }

    fun removeLastPendingPage() {
        val page = _state.value.pendingPages.lastOrNull() ?: return
        _state.update { it.copy(pendingPages = it.pendingPages.dropLast(1)) }
        viewModelScope.launch(Dispatchers.IO) { repository.deletePendingPage(page) }
    }

    fun finishCapture() {
        val session = captureSession ?: return
        if (_state.value.isProcessing) return
        val appendTo = _state.value.appendDocumentId
        viewModelScope.launch {
            val pages = captureLock.withLock {
                if (captureSession != session) {
                    null
                } else {
                    captureSession = null
                    _state.value.pendingPages
                }
            } ?: return@launch
            if (pages.isEmpty()) {
                cancelCapture()
                return@launch
            }
            val language = _state.value.language
            val pageIds = pages.map { it.id }
            _state.update {
                it.copy(isProcessing = true, processedCount = 0, totalToProcess = pages.size)
            }
            if (appendTo != null) {
                val updated = withContext(Dispatchers.IO) {
                    repository.appendPages(appendTo, pages)
                }
                _state.update {
                    it.copy(
                        pendingPages = emptyList(),
                        appendDocumentId = null,
                        isProcessing = false,
                        totalToProcess = 0
                    )
                }
                if (updated == null) {
                    sendMessage(R.string.error_document_load)
                } else {
                    _state.update {
                        it.copy(
                            document = updated,
                            pageIndex = updated.pages.lastIndex.coerceAtLeast(0),
                            screen = Screen.Result
                        )
                    }
                    refreshDocuments()
                    runOcr(updated.id, pageIds, OcrLanguage.fromCode(updated.languageCode))
                }
            } else {
                val document = withContext(Dispatchers.IO) {
                    repository.createDocument(language.code, pages)
                }
                _state.update {
                    it.copy(
                        pendingPages = emptyList(),
                        isProcessing = false,
                        totalToProcess = 0,
                        document = document,
                        pageIndex = 0,
                        screen = Screen.Result
                    )
                }
                refreshDocuments()
                runOcr(document.id, pageIds, language)
            }
        }
    }

    // ---------------------------------------------------------------- imports

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val session = UUID.randomUUID().toString()
        val autoStraighten = _state.value.settings.autoStraighten
        val language = _state.value.language
        viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = Screen.Result,
                    document = null,
                    pageIndex = 0,
                    isProcessing = true,
                    processedCount = 0,
                    totalToProcess = uris.size
                )
            }
            val pending = mutableListOf<PendingPage>()
            withContext(Dispatchers.IO) {
                uris.forEachIndexed { index, uri ->
                    val page = repository.savePendingPageFromUri(
                        session,
                        UUID.randomUUID().toString(),
                        uri,
                        autoStraighten
                    )
                    if (page != null) pending += page
                    _state.update { it.copy(processedCount = index + 1) }
                }
            }
            if (pending.isEmpty()) {
                _state.update { it.copy(isProcessing = false, totalToProcess = 0) }
                sendMessage(R.string.error_load_image)
                openHome()
                return@launch
            }
            val document = withContext(Dispatchers.IO) {
                repository.createDocument(language.code, pending)
            }
            _state.update {
                it.copy(
                    document = document,
                    pageIndex = 0,
                    isProcessing = false,
                    totalToProcess = 0
                )
            }
            refreshDocuments()
            runOcr(document.id, pending.map { it.id }, language)
        }
    }

    fun appendImages(documentId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val session = UUID.randomUUID().toString()
        val autoStraighten = _state.value.settings.autoStraighten
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isProcessing = true,
                    processedCount = 0,
                    totalToProcess = uris.size
                )
            }
            val pending = mutableListOf<PendingPage>()
            withContext(Dispatchers.IO) {
                uris.forEachIndexed { index, uri ->
                    val page = repository.savePendingPageFromUri(
                        session,
                        UUID.randomUUID().toString(),
                        uri,
                        autoStraighten
                    )
                    if (page != null) pending += page
                    _state.update { it.copy(processedCount = index + 1) }
                }
            }
            if (pending.isEmpty()) {
                _state.update { it.copy(isProcessing = false, totalToProcess = 0) }
                sendMessage(R.string.error_load_image)
                return@launch
            }
            val updated = withContext(Dispatchers.IO) {
                repository.appendPages(documentId, pending)
            }
            if (updated == null) {
                _state.update { it.copy(isProcessing = false, totalToProcess = 0) }
                sendMessage(R.string.error_document_load)
                return@launch
            }
            _state.update {
                it.copy(
                    document = updated,
                    pageIndex = updated.pages.lastIndex.coerceAtLeast(0),
                    isProcessing = false,
                    totalToProcess = 0
                )
            }
            refreshDocuments()
            runOcr(updated.id, pending.map { it.id }, OcrLanguage.fromCode(updated.languageCode))
        }
    }

    // -------------------------------------------------------------------- OCR

    private fun runOcr(
        documentId: String,
        pageIds: List<String>,
        language: OcrLanguage,
        modeOverride: PreprocessMode? = null
    ) {
        if (pageIds.isEmpty()) return
        if (ocrJob?.isActive == true) return
        ocrJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { repository.loadDocument(documentId) }
            if (loaded == null) {
                sendMessage(R.string.error_document_load)
                return@launch
            }
            var document = loaded
            var failures = 0
            _state.update {
                it.copy(isProcessing = true, processedCount = 0, totalToProcess = pageIds.size)
            }
            try {
                pageIds.forEachIndexed { index, pageId ->
                    val currentDocument = document
                    val page = currentDocument.pages.firstOrNull { it.id == pageId }
                    if (page != null) {
                        val mode = modeOverride ?: _state.value.settings.preprocessMode
                        val result = ocrPage(page, language, mode)
                        if (result == null) {
                            failures++
                        } else {
                            val base = currentDocument.copy(
                                pages = currentDocument.pages.map { current ->
                                    if (current.id == pageId) {
                                        current.copy(
                                            fullText = result.fullText,
                                            lines = result.lines,
                                            numbers = result.numbers
                                        )
                                    } else {
                                        current
                                    }
                                },
                                updatedAt = System.currentTimeMillis()
                            )
                            val updated = if (base.title.isBlank() && result.fullText.isNotBlank()) {
                                val title = result.fullText.lineSequence()
                                    .firstOrNull { it.isNotBlank() }
                                    ?.trim()
                                    ?.take(60)
                                    .orEmpty()
                                base.copy(title = title)
                            } else {
                                base
                            }
                            document = updated
                            val snapshot = updated
                            withContext(Dispatchers.IO) { repository.saveDocument(snapshot) }
                            _state.update { current ->
                                current.copy(
                                    document = snapshot,
                                    pageIndex = snapshot.pages
                                        .indexOfFirst { it.id == pageId }
                                        .coerceAtLeast(0)
                                )
                            }
                        }
                    }
                    _state.update { it.copy(processedCount = index + 1) }
                }
            } finally {
                _state.update { current ->
                    current.copy(
                        isProcessing = false,
                        processedCount = 0,
                        totalToProcess = 0,
                        document = if (current.document?.id == documentId) {
                            document
                        } else {
                            current.document
                        }
                    )
                }
            }
            refreshDocuments()
            if (failures > 0) sendMessage(R.string.page_ocr_failed) else sendMessage(R.string.scan_done)
        }
    }

    private suspend fun ocrPage(
        page: ScanPage,
        language: OcrLanguage,
        mode: PreprocessMode
    ): OcrResult? = withContext(Dispatchers.Default) {
        val source = ImageUtils.decodeFile(
            page.imagePath,
            ImageUtils.MAX_DECODE_DIMENSION
        ) ?: return@withContext null
        val prepared = runCatching { ImageUtils.prepareForOcr(source, mode) }.getOrNull()
        if (prepared == null) {
            if (!source.isRecycled) source.recycle()
            return@withContext null
        }
        try {
            val result = runCatching { engine.recognize(prepared, language) }.getOrNull()
            if (result == null) {
                null
            } else {
                val scaleX = source.width.toFloat() / prepared.width.toFloat()
                val scaleY = source.height.toFloat() / prepared.height.toFloat()
                val minConfidence = _state.value.settings.minConfidence
                val lines = result.lines
                    .filter { line ->
                        minConfidence <= 0 || line.confidence <= 0f ||
                            line.confidence >= minConfidence
                    }
                    .mapIndexed { index, line ->
                        line.copy(index = index, box = line.box?.scaled(scaleX, scaleY))
                    }
                OcrResult(
                    fullText = lines.joinToString("\n") { it.text },
                    lines = lines,
                    numbers = NumberExtractor.extract(lines)
                )
            }
        } finally {
            if (prepared !== source && !prepared.isRecycled) prepared.recycle()
            if (!source.isRecycled) source.recycle()
        }
    }

    fun cancelProcessing() {
        ocrJob?.cancel()
    }

    // -------------------------------------------------------------- documents

    fun selectPage(index: Int) {
        val pages = _state.value.document?.pages ?: return
        if (pages.isEmpty()) return
        _state.update { it.copy(pageIndex = index.coerceIn(0, pages.lastIndex)) }
    }

    fun renameDocument(title: String) {
        val document = _state.value.document ?: return
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                repository.renameDocument(document.id, title)
            }
            if (updated != null) {
                _state.update { it.copy(document = updated) }
                refreshDocuments()
                sendMessage(R.string.renamed)
            }
        }
    }

    fun deleteDocument(documentId: String) = deleteDocuments(listOf(documentId))

    fun deleteDocuments(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                ids.filter { repository.softDelete(it) }
            }
            _state.update { current ->
                val currentId = current.document?.id
                current.copy(
                    summaries = current.summaries.filterNot { it.id in deleted },
                    document = if (currentId != null && currentId in deleted) null else current.document,
                    pageIndex = if (currentId != null && currentId in deleted) 0 else current.pageIndex,
                    screen = if (currentId != null && currentId in deleted) Screen.Home else current.screen
                )
            }
            if (deleted.isNotEmpty()) {
                val text = if (deleted.size == 1) {
                    app.getString(R.string.document_deleted)
                } else {
                    app.getString(R.string.documents_deleted, deleted.size)
                }
                _events.trySend(UiEvent.DocumentsDeleted(deleted, text))
            }
        }
    }

    fun undoDelete(ids: List<String>) {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                ids.count { repository.restore(it) }
            }
            refreshDocuments()
            if (restored > 0) sendMessage(R.string.document_restored)
        }
    }

    fun deletePage(pageId: String) {
        val document = _state.value.document ?: return
        if (ocrJob?.isActive == true) return
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                repository.deletePage(document.id, pageId)
            }
            if (updated == null) {
                sendMessage(R.string.error_document_load)
                return@launch
            }
            if (updated.pages.isEmpty()) {
                val deleted = withContext(Dispatchers.IO) { repository.softDelete(document.id) }
                _state.update {
                    it.copy(document = null, pageIndex = 0, screen = Screen.Home)
                }
                refreshDocuments()
                if (deleted) {
                    _events.trySend(
                        UiEvent.DocumentsDeleted(
                            listOf(document.id),
                            app.getString(R.string.document_deleted)
                        )
                    )
                } else {
                    sendMessage(R.string.error_document_load)
                }
            } else {
                val index = _state.value.pageIndex.coerceIn(0, updated.pages.lastIndex)
                _state.update { it.copy(document = updated, pageIndex = index) }
                refreshDocuments()
                sendMessage(R.string.page_deleted)
            }
        }
    }

    fun rotatePage(pageId: String) {
        val document = _state.value.document ?: return
        if (ocrJob?.isActive == true) return
        val page = document.pages.firstOrNull { it.id == pageId } ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(isProcessing = true, processedCount = 0, totalToProcess = 1)
            }
            val updated = withContext(Dispatchers.IO) {
                repository.rotatePage(document.id, pageId)
            }
            if (updated == null) {
                _state.update { it.copy(isProcessing = false, totalToProcess = 0) }
                sendMessage(R.string.error_load_image)
                return@launch
            }
            ThumbnailLoader.get(app).invalidate(page.thumbPath)
            _state.update {
                it.copy(isProcessing = false, totalToProcess = 0, document = updated)
            }
            runOcr(document.id, listOf(pageId), OcrLanguage.fromCode(updated.languageCode))
            refreshDocuments()
        }
    }

    fun reprocessPage(pageId: String) {
        val document = _state.value.document ?: return
        runOcr(document.id, listOf(pageId), document.language, PreprocessMode.BINARIZED)
    }

    fun exportDocument(uri: Uri, format: ExportFormat) {
        val document = _state.value.document ?: return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true) }
            val success = withContext(Dispatchers.IO) {
                repository.export(document, uri, format)
            }
            _state.update { it.copy(isBusy = false) }
            sendMessage(
                if (success) {
                    if (format == ExportFormat.PDF) {
                        R.string.export_saved_pdf
                    } else {
                        R.string.export_saved_txt
                    }
                } else {
                    R.string.export_failed
                }
            )
        }
    }

    private fun sendMessage(@StringRes id: Int, vararg args: Any) {
        _events.trySend(UiEvent.Message(app.getString(id, *args)))
    }

    override fun onCleared() {
        ocrJob?.cancel()
        engine.close()
        super.onCleared()
    }
}
