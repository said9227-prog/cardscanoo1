package com.docscanner.ocr.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docscanner.ocr.R
import com.docscanner.ocr.model.DocumentSummary
import com.docscanner.ocr.model.SortOrder
import com.docscanner.ocr.ui.components.EmptyState
import com.docscanner.ocr.ui.components.rememberThumbnail
import com.docscanner.ocr.ui.theme.cardAccent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    summaries: List<DocumentSummary>,
    onOpen: (String) -> Unit,
    onDelete: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(SortOrder.NEWEST) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var visibleCount by remember { mutableStateOf(PAGE_SIZE) }
    var confirmDeleteIds by remember { mutableStateOf<List<String>?>(null) }

    val filtered = remember(summaries, query, sortOrder) {
        val base = if (query.isBlank()) {
            summaries
        } else {
            summaries.filter { summary ->
                summary.title.contains(query, ignoreCase = true) ||
                    summary.preview.contains(query, ignoreCase = true)
            }
        }
        when (sortOrder) {
            SortOrder.NEWEST -> base.sortedByDescending { it.updatedAt }
            SortOrder.OLDEST -> base.sortedBy { it.updatedAt }
            SortOrder.NAME -> base.sortedBy { it.title.lowercase(Locale.getDefault()) }
        }
    }
    val visible = filtered.take(visibleCount)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    visibleCount = PAGE_SIZE
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = stringResource(R.string.history_search_hint)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Sort,
                        contentDescription = stringResource(R.string.history_sort)
                    )
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false }
                ) {
                    SortOrder.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(text = stringResource(option.labelRes)) },
                            onClick = {
                                sortOrder = option
                                sortMenuOpen = false
                            },
                            trailingIcon = {
                                if (sortOrder == option) {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Text(
                    text = stringResource(R.string.history_selected, selected.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { selected = filtered.map { it.id }.toSet() }) {
                    Text(text = stringResource(R.string.action_select_all))
                }
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { confirmDeleteIds = selected.toList() }) {
                        Text(
                            text = stringResource(R.string.action_delete_selected, selected.size),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(onClick = {
                    selectionMode = false
                    selected = emptySet()
                }) {
                    Text(text = stringResource(R.string.action_clear_selection))
                }
            } else {
                Text(
                    text = stringResource(R.string.history_count, filtered.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { selectionMode = true }) {
                    Text(text = stringResource(R.string.action_select))
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Outlined.History,
                    text = if (summaries.isEmpty()) {
                        stringResource(R.string.history_empty)
                    } else {
                        stringResource(R.string.history_no_results)
                    }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible, key = { it.id }) { summary ->
                    val index = filtered.indexOfFirst { it.id == summary.id }
                    HistoryCard(
                        summary = summary,
                        accentIndex = index.coerceAtLeast(0),
                        selectionMode = selectionMode,
                        selected = summary.id in selected,
                        onClick = {
                            if (selectionMode) {
                                selected = if (summary.id in selected) {
                                    selected - summary.id
                                } else {
                                    selected + summary.id
                                }
                            } else {
                                onOpen(summary.id)
                            }
                        },
                        onDelete = { confirmDeleteIds = listOf(summary.id) }
                    )
                }
                if (visible.size < filtered.size) {
                    item {
                        TextButton(
                            onClick = { visibleCount += PAGE_SIZE },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.action_load_more))
                        }
                    }
                }
            }
        }
    }

    val pendingDelete = confirmDeleteIds
    if (pendingDelete != null) {
        val single = pendingDelete.size == 1
        val summary = if (single) {
            summaries.firstOrNull { it.id == pendingDelete.first() }
        } else {
            null
        }
        AlertDialog(
            onDismissRequest = { confirmDeleteIds = null },
            title = {
                Text(
                    text = stringResource(
                        if (single) R.string.confirm_delete_title else R.string.confirm_delete_selected_title
                    )
                )
            },
            text = {
                val untitled = stringResource(R.string.untitled_document)
                val title = summary?.title?.takeIf { it.isNotBlank() } ?: untitled
                Text(
                    text = if (single) {
                        stringResource(R.string.confirm_delete_message, title)
                    } else {
                        stringResource(
                            R.string.confirm_delete_selected_message,
                            pendingDelete.size
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(pendingDelete)
                    confirmDeleteIds = null
                    selected = emptySet()
                    selectionMode = false
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteIds = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun HistoryCard(
    summary: DocumentSummary,
    accentIndex: Int,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val thumbnail = rememberThumbnail(summary.thumbPath, summary.updatedAt)
    val accent = cardAccent(accentIndex)
    val date = remember(summary.updatedAt) {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(summary.updatedAt))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else accent.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
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
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.title.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.untitled_document),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = summary.preview.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.history_empty_preview),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.document_meta_pages_numbers,
                        summary.pageCount,
                        summary.numbersCount
                    ) + " · " + date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (selectionMode) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private const val PAGE_SIZE = 50
