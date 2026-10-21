package com.docscanner.ocr.data

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.docscanner.ocr.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Semaphore

/**
 * Decodes small page previews off the main thread, limits how many decodes run at
 * the same time and keeps the results in a memory-bounded LRU cache. This is what
 * keeps long documents and history lists smooth.
 */
class ThumbnailLoader(context: Context) {

    private class Entry(val bitmap: Bitmap, val stamp: Long, val bytes: Int)

    private val cache = object : LruCache<String, Entry>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Entry): Int = value.bytes
    }

    private val gate = Semaphore(3)

    suspend fun load(path: String): Bitmap? = withContext(Dispatchers.IO) {
        val stamp = File(path).lastModified()
        cache.get(path)?.let { entry ->
            if (entry.stamp == stamp) return@withContext entry.bitmap
        }
        gate.acquire()
        try {
            val cached = cache.get(path)
            if (cached != null && cached.stamp == stamp) return@withContext cached.bitmap
            val bitmap = ImageUtils.decodeFile(path, THUMB_SIZE) ?: return@withContext null
            cache.put(path, Entry(bitmap, stamp, bitmap.byteCount))
            bitmap
        } finally {
            gate.release()
        }
    }

    fun invalidate(path: String) {
        cache.remove(path)
    }

    companion object {
        private const val THUMB_SIZE = 360

        private fun maxCacheBytes(): Int {
            val eighth = (Runtime.getRuntime().maxMemory() / 8).toInt()
            return eighth.coerceIn(8 * 1024 * 1024, 40 * 1024 * 1024)
        }

        @Volatile
        private var instance: ThumbnailLoader? = null

        fun get(context: Context): ThumbnailLoader =
            instance ?: synchronized(this) {
                instance ?: ThumbnailLoader(context.applicationContext).also { instance = it }
            }
    }
}
