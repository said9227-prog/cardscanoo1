package com.docscanner.ocr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.docscanner.ocr.model.ThemeMode
import com.docscanner.ocr.ui.DocScannerApp
import com.docscanner.ocr.ui.theme.DocScannerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launcher shortcut / quick action: jump straight to the camera.
        if (intent?.action == ACTION_SCAN_CAMERA) {
            viewModel.startCapture()
        }

        val isArabic = resources.configuration.locales[0].language == "ar"

        setContent {
            val state by viewModel.state.collectAsState()
            val darkTheme = when (state.settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            DocScannerTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isArabic) {
                        LayoutDirection.Rtl
                    } else {
                        LayoutDirection.Ltr
                    }
                ) {
                    DocScannerApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SCAN_CAMERA) {
            viewModel.startCapture()
        }
    }

    companion object {
        const val ACTION_SCAN_CAMERA = "com.docscanner.ocr.action.SCAN_CAMERA"
    }
}
