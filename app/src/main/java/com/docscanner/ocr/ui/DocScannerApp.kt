package com.docscanner.ocr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.docscanner.ocr.MainViewModel
import com.docscanner.ocr.R
import com.docscanner.ocr.Screen
import com.docscanner.ocr.UiEvent
import com.docscanner.ocr.ui.components.ProgressBar

@Composable
fun DocScannerApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.action_undo)

    BackHandler(enabled = state.screen != Screen.Home) {
        when (state.screen) {
            Screen.Camera -> viewModel.cancelCapture()
            else -> viewModel.openHome()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is UiEvent.DocumentsDeleted -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.text,
                        actionLabel = undoLabel,
                        withDismissAction = true,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoDelete(event.ids)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.primary) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.screen != Screen.Home) {
                        IconButton(onClick = {
                            if (state.screen == Screen.Camera) {
                                viewModel.cancelCapture()
                            } else {
                                viewModel.openHome()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    Text(
                        text = stringResource(screenTitle(state.screen)),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    if (state.screen == Screen.Home) {
                        IconButton(onClick = viewModel::openHistory) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = stringResource(R.string.title_history),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        IconButton(onClick = viewModel::openSettings) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.title_settings),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (state.screen == Screen.Home || state.screen == Screen.History) {
                FloatingActionButton(
                    onClick = viewModel::startCapture,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(R.string.home_capture)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (state.screen) {
                Screen.Home -> HomeScreen(
                    summaries = state.summaries,
                    onOpenCamera = viewModel::startCapture,
                    onPickImages = viewModel::importImages,
                    onOpenHistory = viewModel::openHistory,
                    onOpenSettings = viewModel::openSettings,
                    onOpenDocument = viewModel::openDocument
                )

                Screen.Camera -> CameraScreen(
                    pendingCount = state.pendingPages.size,
                    lastThumbPath = state.pendingPages.lastOrNull()?.thumbPath,
                    onCaptured = viewModel::addCapturedPage,
                    onRemoveLast = viewModel::removeLastPendingPage,
                    onFinish = viewModel::finishCapture,
                    onClose = viewModel::cancelCapture
                )

                Screen.Result -> {
                    val document = state.document
                    if (document == null) {
                        if (state.isProcessing) {
                            ImportProgress(
                                processed = state.processedCount,
                                total = state.totalToProcess
                            )
                        } else {
                            LaunchedEffect(Unit) { viewModel.openHome() }
                        }
                    } else {
                        ResultScreen(
                            document = document,
                            pageIndex = state.pageIndex,
                            isProcessing = state.isProcessing,
                            processedCount = state.processedCount,
                            totalToProcess = state.totalToProcess,
                            showBoxes = state.settings.showBoxes,
                            onToggleBoxes = viewModel::setShowBoxes,
                            onSelectPage = viewModel::selectPage,
                            onRename = viewModel::renameDocument,
                            onDeleteDocument = { viewModel.deleteDocument(document.id) },
                            onAddPages = { viewModel.startAppendCapture(document.id) },
                            onAddImages = { uris -> viewModel.appendImages(document.id, uris) },
                            onRotatePage = viewModel::rotatePage,
                            onReprocessPage = viewModel::reprocessPage,
                            onDeletePage = viewModel::deletePage,
                            onCancelProcessing = viewModel::cancelProcessing,
                            onExport = viewModel::exportDocument,
                            onOpenHistory = viewModel::openHistory
                        )
                    }
                }

                Screen.History -> HistoryScreen(
                    summaries = state.summaries,
                    onOpen = viewModel::openDocument,
                    onDelete = viewModel::deleteDocuments
                )

                Screen.Settings -> SettingsScreen(
                    settings = state.settings,
                    onThemeMode = viewModel::setThemeMode,
                    onPreprocessMode = viewModel::setPreprocessMode,
                    onAutoStraighten = viewModel::setAutoStraighten,
                    onShowBoxes = viewModel::setShowBoxes
                )
            }

            if (state.isBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun ImportProgress(processed: Int, total: Int) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.processing_title),
                style = MaterialTheme.typography.bodyMedium
            )
            if (total > 0) {
                ProgressBar(progress = processed.toFloat() / total.toFloat())
            }
        }
    }
}

private fun screenTitle(screen: Screen): Int = when (screen) {
    Screen.Home -> R.string.title_home
    Screen.Camera -> R.string.title_camera
    Screen.Result -> R.string.title_result
    Screen.History -> R.string.title_history
    Screen.Settings -> R.string.title_settings
}
