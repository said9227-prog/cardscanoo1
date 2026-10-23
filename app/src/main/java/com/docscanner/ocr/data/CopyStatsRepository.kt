package com.docscanner.ocr.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Counts how many times numbers from each document have been copied. The counts
 * are tiny and persisted as JSON in SharedPreferences so the star badge survives
 * app restarts.
 */
class CopyStatsRepository private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _counts = MutableStateFlow(read())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    @Synchronized
    fun recordCopy(documentId: String): Int {
        val updated = _counts.value.toMutableMap()
        val next = (updated[documentId] ?: 0) + 1
        updated[documentId] = next
        _counts.value = updated
        persist(updated)
        return next
    }

    private fun read(): Map<String, Int> = runCatching {
        val raw = prefs.getString(KEY_COUNTS, null) ?: return emptyMap()
        val json = JSONObject(raw)
        json.keys().asSequence()
            .mapNotNull { key ->
                val count = json.optInt(key, 0)
                if (count > 0) key to count else null
            }
            .toMap()
    }.getOrDefault(emptyMap())

    private fun persist(counts: Map<String, Int>) {
        val json = JSONObject()
        counts.forEach { (documentId, count) -> json.put(documentId, count) }
        prefs.edit().putString(KEY_COUNTS, json.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "docscanner_copy_stats"
        private const val KEY_COUNTS = "counts"

        @Volatile
        private var instance: CopyStatsRepository? = null

        fun get(context: Context): CopyStatsRepository =
            instance ?: synchronized(this) {
                instance ?: CopyStatsRepository(context).also { instance = it }
            }
    }
}
