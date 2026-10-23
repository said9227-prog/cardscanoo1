package com.docscanner.ocr.data

import android.content.Context
import com.docscanner.ocr.model.PreprocessMode
import com.docscanner.ocr.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences. A single application-wide instance keeps the state in memory
 * and mirrors every change to SharedPreferences.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val preprocessMode: PreprocessMode = PreprocessMode.AUTO,
    val autoStraighten: Boolean = true,
    val showBoxes: Boolean = true
)

class SettingsRepository private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setPreprocessMode(mode: PreprocessMode) = update { it.copy(preprocessMode = mode) }

    fun setAutoStraighten(enabled: Boolean) = update { it.copy(autoStraighten = enabled) }

    fun setShowBoxes(enabled: Boolean) = update { it.copy(showBoxes = enabled) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.edit()
            .putString(KEY_THEME, updated.themeMode.name)
            .putString(KEY_PREPROCESS, updated.preprocessMode.name)
            .putBoolean(KEY_STRAIGHTEN, updated.autoStraighten)
            .putBoolean(KEY_BOXES, updated.showBoxes)
            .apply()
    }

    private fun read(): AppSettings = AppSettings(
        themeMode = ThemeMode.fromName(prefs.getString(KEY_THEME, null)),
        preprocessMode = PreprocessMode.fromName(prefs.getString(KEY_PREPROCESS, null)),
        autoStraighten = prefs.getBoolean(KEY_STRAIGHTEN, true),
        showBoxes = prefs.getBoolean(KEY_BOXES, true)
    )

    companion object {
        private const val PREFS_NAME = "docscanner_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_PREPROCESS = "preprocess"
        private const val KEY_STRAIGHTEN = "straighten"
        private const val KEY_BOXES = "boxes"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
