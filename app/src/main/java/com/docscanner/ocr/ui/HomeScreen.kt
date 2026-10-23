package com.docscanner.ocr.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docscanner.ocr.R
import com.docscanner.ocr.model.DocumentPageSummary
import com.docscanner.ocr.model.DocumentSummary
import com.docscanner.ocr.ocr.NumberExtractor
import com.docscanner.ocr.ui.components.EmptyState
import com.docscanner.ocr.ui.components.copyToClipboard
import com.docscanner.ocr.ui.components.rememberThumbnail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SearchHit(
    val summary: DocumentSummary,
    val pageIndex: Int,
    val thumbPath: String,
    val numbers: List<String>
)

@Composable
fun HomeScreen(
    summaries: List<DocumentSummary>,
    copyCounts: Map<String, Int>,
    onNumberCopied: (String) -> Unit,
    onOpenCamera: () -> Unit,
    onPickImages: (List<Uri>) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDocument: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val hits = remember(summaries, searchQuery) { searchNumbers(summaries, searchQuery) }

    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris ->
        if (uris.isNotEmpty()) onPickImages(uris)
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = NumberExtractor.normalizeDigits(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            placeholder = { Text(text = stringResource(R.string.home_search_hint)) },
            leadingIcon = {
                Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Outlined.Clear,
                            contentDescription = stringResource(R.string.home_search_clear)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Search
            )
        )

        if (searchQuery.isBlank()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CameraButton(onClick = onOpenCamera)

                OutlinedButton(
                    onClick = {
                        pickImagesLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(imageVector = Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.home_pick_multiple))
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.home_recent),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenHistory) {
                        Text(text = stringResource(R.string.home_history, summaries.size))
                    }
                }

                if (summaries.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.home_no_documents),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(18.dp)
                        )
                    }
                } else {
                    summaries.take(3).forEach { summary ->
                        RecentDocumentCard(
                            summary = summary,
                            copyCount = copyCounts[summary.id] ?: 0,
                            onNumberCopied = onNumberCopied,
                            onClick = { onOpenDocument(summary.id, 0) }
                        )
                    }
                }

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(imageVector = Icons.Outlined.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.title_settings))
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.home_tips_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        listOf(
                            R.string.home_tip_1,
                            R.string.home_tip_2,
                            R.string.home_tip_3,
                            R.string.home_tip_4
                        ).forEach { tip ->
                            Text(
                                text = "• " + stringResource(tip),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(76.dp))
            }
        } else if (hits.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Outlined.Search,
                    text = stringResource(R.string.home_search_empty)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.home_search_results, hits.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                items(hits, key = { "${it.summary.id}:${it.pageIndex}" }) { hit ->
                    SearchResultCard(
                        hit = hit,
                        copyCount = copyCounts[hit.summary.id] ?: 0,
                        onNumberCopied = onNumberCopied,
                        onClick = { onOpenDocument(hit.summary.id, hit.pageIndex) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraButton(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(158.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_capture),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_capture_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun RecentDocumentCard(
    summary: DocumentSummary,
    copyCount: Int,
    onNumberCopied: (String) -> Unit,
    onClick: () -> Unit
) {
    val date = remember(summary.updatedAt) {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(summary.updatedAt))
    }
    DocumentCard(
        summary = summary,
        numbers = summary.numbers,
        numbersTitle = stringResource(R.string.home_numbers_title),
        thumbnailPath = summary.thumbPath,
        meta = stringResource(
            R.string.document_meta_pages_numbers,
            summary.pageCount,
            summary.numbersCount
        ) + " · " + date,
        copyCount = copyCount,
        onNumberCopied = onNumberCopied,
        onClick = onClick
    )
}

@Composable
private fun SearchResultCard(
    hit: SearchHit,
    copyCount: Int,
    onNumberCopied: (String) -> Unit,
    onClick: () -> Unit
) {
    DocumentCard(
        summary = hit.summary,
        numbers = hit.numbers,
        numbersTitle = stringResource(R.string.home_search_matches),
        thumbnailPath = hit.thumbPath,
        meta = stringResource(
            R.string.home_search_hit_meta,
            hit.pageIndex + 1,
            hit.numbers.size
        ),
        copyCount = copyCount,
        onNumberCopied = onNumberCopied,
        onClick = onClick
    )
}

@Composable
private fun DocumentCard(
    summary: DocumentSummary,
    numbers: List<String>,
    numbersTitle: String,
    thumbnailPath: String,
    meta: String,
    copyCount: Int,
    onNumberCopied: (String) -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbnail = rememberThumbnail(thumbnailPath, summary.updatedAt)
    var expanded by rememberSaveable(summary.id, numbersTitle) { mutableStateOf(false) }
    val visibleNumbers = if (expanded) numbers else numbers.take(NUMBER_PREVIEW)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
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
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(44.dp)
                            .align(Alignment.Center)
                    )
                }

                if (copyCount > 0) {
                    CopyBadge(
                        count = copyCount,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text(
                            text = summary.title.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.untitled_document),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = numbersTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    if (copyCount > 0) {
                        Text(
                            text = stringResource(R.string.home_copy_count, copyCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (numbers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_numbers_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    visibleNumbers.forEach { number ->
                        NumberCopyRow(
                            number = number,
                            onCopy = {
                                copyToClipboard(context, number)
                                onNumberCopied(summary.id)
                            }
                        )
                    }
                    if (numbers.size > NUMBER_PREVIEW) {
                        Text(
                            text = if (expanded) {
                                stringResource(R.string.home_show_less)
                            } else {
                                stringResource(R.string.home_show_all_numbers, numbers.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { expanded = !expanded }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.94f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = stringResource(R.string.home_copied_star, count),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun NumberCopyRow(
    number: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onCopy)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = stringResource(R.string.action_copy),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun searchNumbers(summaries: List<DocumentSummary>, rawQuery: String): List<SearchHit> {
    val query = NumberExtractor.normalizeDigits(rawQuery)
    if (query.isBlank()) return emptyList()

    return buildList {
        summaries.forEach { summary ->
            val pages = summary.pages.ifEmpty {
                listOf(DocumentPageSummary(summary.thumbPath, summary.numbers))
            }
            pages.forEachIndexed { pageIndex, page ->
                val matches = page.numbers.filter { number ->
                    NumberExtractor.matchesNormalized(number, query)
                }
                if (matches.isNotEmpty()) {
                    add(
                        SearchHit(
                            summary = summary,
                            pageIndex = pageIndex,
                            thumbPath = page.thumbPath,
                            numbers = matches
                        )
                    )
                }
            }
        }
    }
}

private const val NUMBER_PREVIEW = 8
