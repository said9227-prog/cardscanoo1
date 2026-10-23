package com.docscanner.ocr.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docscanner.ocr.R
import com.docscanner.ocr.model.ExportFormat
import com.docscanner.ocr.model.OcrLine
import com.docscanner.ocr.model.ScanDocument
import com.docscanner.ocr.model.ScanPage
import com.docscanner.ocr.ocr.NumberExtractor
import com.docscanner.ocr.ui.components.CompactButton
import com.docscanner.ocr.ui.components.copyToClipboard
import com.docscanner.ocr.ui.components.rememberThumbnail
import com.docscanner.ocr.ui.components.shareImage
import com.docscanner.ocr.ui.components.shareText
import com.docscanner.ocr.ui.theme.BoxFill
import com.docscanner.ocr.ui.theme.BoxStroke
import com.docscanner.ocr.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

@Composable
fun ResultScreen(
    document: ScanDocument,
    pageIndex: Int,
    isProcessing: Boolean,
    processedCount: Int,
    totalToProcess: Int,
    showBoxes: Boolean,
    onToggleBoxes: (Boolean) -> Unit,
    onSelectPage: (Int) -> Unit,
    onRename: (String) -> Unit,
    onDeleteDocument: () -> Unit,
    onAddPages: () -> Unit,
    onAddImages: (List<Uri>) -> Unit,
    onRotatePage: (String) -> Unit,
    onReprocessPage: (String) -> Unit,
    onDeletePage: (String) -> Unit,
    onCancelProcessing: () -> Unit,
    onExport: (Uri, ExportFormat) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val page = document.pages.getOrNull(pageIndex)

    var showRename by remember { mutableStateOf(false) }
    var renameText by remember(document.title) { mutableStateOf(document.title) }
    var confirmDeleteDocument by remember { mutableStateOf(false) }
    var confirmDeletePage by remember { mutableStateOf(false) }

    val stamp = remember(document.updatedAt) {
        SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(document.updatedAt))
    }
    val exportPdf = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) onExport(uri, ExportFormat.PDF) }
    val exportTxt = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) onExport(uri, ExportFormat.TXT) }
    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris -> if (uris.isNotEmpty()) onAddImages(uris) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Column(modifier = Modifier.fillMaxSize()) {
            DocumentHeader(
                document = document,
                pageIndex = pageIndex,
                showBoxes = showBoxes,
                onToggleBoxes = onToggleBoxes,
                onSelectPage = onSelectPage,
                onRenameClick = {
                    renameText = document.title
                    showRename = true
                }
            )

            if (isProcessing) {
                ProcessingCard(
                    processed = processedCount,
                    total = totalToProcess,
                    onCancel = onCancelProcessing
                )
            }

            val textPane: @Composable (Modifier) -> Unit = { paneModifier ->
                TextPane(
                    document = document,
                    page = page,
                    isProcessing = isProcessing,
                    onRenameClick = {
                        renameText = document.title
                        showRename = true
                    },
                    onAddPages = onAddPages,
                    onPickImages = {
                        pickImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onExportPdf = { exportPdf.launch("scan_$stamp.pdf") },
                    onExportTxt = { exportTxt.launch("scan_$stamp.txt") },
                    onRotatePage = { page?.let { onRotatePage(it.id) } },
                    onReprocessPage = { page?.let { onReprocessPage(it.id) } },
                    onDeletePage = { confirmDeletePage = true },
                    onDeleteDocument = { confirmDeleteDocument = true },
                    onOpenHistory = onOpenHistory,
                    modifier = paneModifier
                )
            }

            if (wide) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    PreviewPane(
                        page = page,
                        showBoxes = showBoxes,
                        version = document.updatedAt,
                        modifier = Modifier
                            .weight(0.34f)
                            .fillMaxHeight()
                            .padding(8.dp)
                    )
                    textPane(
                        Modifier
                            .weight(0.66f)
                            .fillMaxHeight()
                    )
                }
            } else {
                PreviewPane(
                    page = page,
                    showBoxes = showBoxes,
                    version = document.updatedAt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.25f)
                )
                textPane(
                    Modifier
                        .fillMaxWidth()
                        .weight(0.75f)
                )
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(text = stringResource(R.string.action_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = { Text(text = stringResource(R.string.result_title_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(renameText)
                    showRename = false
                }) {
                    Text(text = stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (confirmDeleteDocument) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDocument = false },
            title = { Text(text = stringResource(R.string.confirm_delete_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.confirm_delete_message,
                        document.title.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.untitled_document)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteDocument = false
                    onDeleteDocument()
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDocument = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (confirmDeletePage) {
        AlertDialog(
            onDismissRequest = { confirmDeletePage = false },
            title = { Text(text = stringResource(R.string.confirm_delete_page_title)) },
            text = { Text(text = stringResource(R.string.confirm_delete_page_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeletePage = false
                    page?.let { onDeletePage(it.id) }
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePage = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun DocumentHeader(
    document: ScanDocument,
    pageIndex: Int,
    showBoxes: Boolean,
    onToggleBoxes: (Boolean) -> Unit,
    onSelectPage: (Int) -> Unit,
    onRenameClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.untitled_document),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.result_doc_stats,
                        document.pages.size,
                        document.linesCount,
                        document.numbers.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onRenameClick) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.action_rename),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (document.pages.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                itemsIndexed(document.pages, key = { _, item -> item.id }) { index, item ->
                    PageThumbnail(
                        page = item,
                        number = index + 1,
                        selected = index == pageIndex,
                        version = document.updatedAt,
                        onClick = { onSelectPage(index) }
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.result_overlay),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = showBoxes, onCheckedChange = onToggleBoxes)
        }
    }
}

@Composable
private fun PageThumbnail(
    page: ScanPage,
    number: Int,
    selected: Boolean,
    version: Long,
    onClick: () -> Unit
) {
    val thumbnail = rememberThumbnail(page.thumbPath, version)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    },
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun ProcessingCard(
    processed: Int,
    total: Int,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (total > 0) {
                        stringResource(
                            R.string.processing_page,
                            (processed + 1).coerceAtMost(total),
                            total
                        )
                    } else {
                        stringResource(R.string.processing_title)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(R.string.action_cancel_processing))
                }
            }
            com.docscanner.ocr.ui.components.ProgressBar(
                progress = if (total > 0) processed.toFloat() / total.toFloat() else 0f
            )
        }
    }
}

@Composable
private fun PreviewPane(
    page: ScanPage?,
    showBoxes: Boolean,
    version: Long,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101010))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (page == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                val bitmap by produceState<Bitmap?>(
                    initialValue = null,
                    page.imagePath,
                    version
                ) {
                    value = withContext(Dispatchers.IO) {
                        ImageUtils.decodeFile(page.imagePath, ImageUtils.MAX_DECODE_DIMENSION)
                    }
                }
                val loaded = bitmap
                if (loaded != null) {
                    DocumentPreview(
                        bitmap = loaded,
                        pageId = page.id,
                        version = version,
                        lines = page.lines,
                        showBoxes = showBoxes
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DocumentPreview(
    bitmap: Bitmap,
    pageId: String,
    version: Long,
    lines: List<OcrLine>,
    showBoxes: Boolean
) {
    val density = LocalDensity.current
    var scale by remember(pageId, version) { mutableStateOf(1f) }
    var offset by remember(pageId, version) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color(0xFF101010))
    ) {
        val containerWidth = with(density) { maxWidth.toPx() }
        val containerHeight = with(density) { maxHeight.toPx() }

        if (containerWidth > 0f && containerHeight > 0f && bitmap.width > 0 && bitmap.height > 0) {
            val fit = min(containerWidth / bitmap.width, containerHeight / bitmap.height)
            val drawWidth = bitmap.width * fit
            val drawHeight = bitmap.height * fit

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(
                        width = with(density) { drawWidth.toDp() },
                        height = with(density) { drawHeight.toDp() }
                    )
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale > 1f) offset + pan else Offset.Zero
                        }
                    }
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )

                if (showBoxes) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scaleX = size.width / bitmap.width
                        val scaleY = size.height / bitmap.height

                        lines.forEach { line ->
                            val box = line.box ?: return@forEach
                            val topLeft = Offset(box.left * scaleX, box.top * scaleY)
                            val rectSize = Size(
                                width = (box.width * scaleX).coerceAtLeast(2f),
                                height = (box.height * scaleY).coerceAtLeast(2f)
                            )
                            drawRect(
                                color = BoxFill,
                                topLeft = topLeft,
                                size = rectSize
                            )
                            drawRect(
                                color = BoxStroke,
                                topLeft = topLeft,
                                size = rectSize,
                                style = Stroke(width = 1.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextPane(
    document: ScanDocument,
    page: ScanPage?,
    isProcessing: Boolean,
    onRenameClick: () -> Unit,
    onAddPages: () -> Unit,
    onPickImages: () -> Unit,
    onExportPdf: () -> Unit,
    onExportTxt: () -> Unit,
    onRotatePage: () -> Unit,
    onReprocessPage: () -> Unit,
    onDeletePage: () -> Unit,
    onDeleteDocument: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var editMode by remember(document.id) { mutableStateOf(false) }
    var textState by remember(document.id, document.updatedAt) {
        mutableStateOf(TextFieldValue(document.fullText))
    }
    val accent = MaterialTheme.colorScheme.primary
    val annotatedText = remember(textState.text, accent) {
        highlightNumbers(textState.text, accent)
    }
    val numberRanges = remember(textState.text) {
        NumberExtractor.findAll(textState.text).map { it.range to it.value }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(editMode) {
        if (editMode) runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActionsPanel(
            hasPage = page != null,
            isProcessing = isProcessing,
            onRenameClick = onRenameClick,
            onAddPages = onAddPages,
            onPickImages = onPickImages,
            onExportPdf = onExportPdf,
            onExportTxt = onExportTxt,
            onShareText = { shareText(context, textState.text) },
            onSharePage = { page?.let { shareImage(context, it.imagePath) } },
            onRotatePage = onRotatePage,
            onReprocessPage = onReprocessPage,
            onDeletePage = onDeletePage,
            onDeleteDocument = onDeleteDocument,
            onOpenHistory = onOpenHistory
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.result_full_text),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(
                                R.string.result_text_stats,
                                document.linesCount,
                                document.numbers.size
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = muted
                        )
                    }
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(
                            imageVector = if (editMode) {
                                Icons.Outlined.Check
                            } else {
                                Icons.Outlined.Edit
                            },
                            contentDescription = stringResource(
                                if (editMode) R.string.action_done else R.string.action_edit_text
                            ),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (editMode) {
                    OutlinedTextField(
                        value = textState,
                        onValueChange = { textState = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .focusRequester(focusRequester),
                        placeholder = { Text(text = stringResource(R.string.result_page_empty)) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp),
                        shape = RoundedCornerShape(12.dp)
                    )
                } else if (textState.text.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.result_page_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted
                        )
                    }
                } else {
                    ClickableText(
                        text = annotatedText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 2.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 27.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = { offset ->
                            numberRanges.firstOrNull { offset in it.first }?.let { (_, value) ->
                                copyToClipboard(context, value)
                            }
                        }
                    )
                }

                Text(
                    text = stringResource(
                        if (editMode) R.string.result_edit_hint else R.string.result_number_hint
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactButton(
                        text = stringResource(R.string.action_copy_all),
                        onClick = { copyToClipboard(context, textState.text) },
                        icon = Icons.Outlined.ContentCopy
                    )
                    if (editMode) {
                        CompactButton(
                            text = stringResource(R.string.action_select_all),
                            onClick = {
                                focusRequester.requestFocus()
                                textState = TextFieldValue(
                                    textState.text,
                                    TextRange(0, textState.text.length),
                                    textState.composition
                                )
                            },
                            icon = Icons.Outlined.SelectAll
                        )
                        CompactButton(
                            text = stringResource(R.string.action_clear),
                            onClick = { textState = TextFieldValue("") },
                            icon = Icons.Outlined.Clear
                        )
                    }
                    CompactButton(
                        text = stringResource(R.string.action_reprocess),
                        onClick = onReprocessPage,
                        enabled = page != null && !isProcessing,
                        icon = Icons.Outlined.Refresh
                    )
                }
            }
        }
    }
}

private fun highlightNumbers(text: String, accent: Color): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        NumberExtractor.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            withStyle(
                SpanStyle(
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    background = accent.copy(alpha = 0.12f)
                )
            ) {
                append(match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

@Composable
private fun ActionsPanel(
    hasPage: Boolean,
    isProcessing: Boolean,
    onRenameClick: () -> Unit,
    onAddPages: () -> Unit,
    onPickImages: () -> Unit,
    onExportPdf: () -> Unit,
    onExportTxt: () -> Unit,
    onShareText: () -> Unit,
    onSharePage: () -> Unit,
    onRotatePage: () -> Unit,
    onReprocessPage: () -> Unit,
    onDeletePage: () -> Unit,
    onDeleteDocument: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactButton(
                text = stringResource(R.string.action_rename),
                onClick = onRenameClick,
                icon = Icons.Outlined.Edit
            )
            CompactButton(
                text = stringResource(R.string.action_export_pdf),
                onClick = onExportPdf,
                icon = Icons.Outlined.PictureAsPdf
            )
            CompactButton(
                text = stringResource(R.string.action_export_txt),
                onClick = onExportTxt,
                icon = Icons.Outlined.Description
            )
            CompactButton(
                text = stringResource(R.string.action_share),
                onClick = onShareText,
                icon = Icons.Outlined.Share
            )
            CompactButton(
                text = stringResource(R.string.action_share_page_image),
                onClick = onSharePage,
                enabled = hasPage,
                icon = Icons.Outlined.Share
            )
            CompactButton(
                text = stringResource(R.string.action_add_pages),
                onClick = onAddPages,
                icon = Icons.Outlined.PhotoCamera
            )
            CompactButton(
                text = stringResource(R.string.action_add_images),
                onClick = onPickImages,
                icon = Icons.Outlined.AddPhotoAlternate
            )
            CompactButton(
                text = stringResource(R.string.title_history),
                onClick = onOpenHistory,
                icon = Icons.Outlined.History
            )
            CompactButton(
                text = stringResource(R.string.action_rotate),
                onClick = onRotatePage,
                enabled = hasPage && !isProcessing,
                icon = Icons.Outlined.RotateRight
            )
            CompactButton(
                text = stringResource(R.string.action_reprocess),
                onClick = onReprocessPage,
                enabled = hasPage && !isProcessing,
                icon = Icons.Outlined.Refresh
            )
            CompactButton(
                text = stringResource(R.string.action_delete_page),
                onClick = onDeletePage,
                enabled = hasPage && !isProcessing,
                icon = Icons.Outlined.Delete
            )
            CompactButton(
                text = stringResource(R.string.action_delete_document),
                onClick = onDeleteDocument,
                enabled = !isProcessing,
                icon = Icons.Outlined.Delete
            )
        }
    }
}
