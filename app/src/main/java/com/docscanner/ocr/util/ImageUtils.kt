package com.docscanner.ocr.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.docscanner.ocr.model.PreprocessMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

object ImageUtils {

    /** Longest side of the bitmap that is handed to the OCR engines. */
    const val MAX_OCR_DIMENSION = 2600

    /** Longest side of a stored page / preview bitmap. */
    const val MAX_DECODE_DIMENSION = 2600

    /** Small images (distant small cards) are upscaled to this before OCR. */
    private const val MIN_OCR_DIMENSION = 1400

    private const val MAX_SKEW_SAMPLE = 500
    private const val MAX_SKEW_DEGREES = 8f
    private const val MIN_SKEW_DEGREES = 0.7f

    private const val CONTRAST = 1.15f
    private const val CONTRAST_OFFSET = -18f

    fun decodeFromUri(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                MAX_DECODE_DIMENSION
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return@runCatching null

        val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
            runCatching { ExifInterface(stream).rotationDegrees }.getOrDefault(0)
        } ?: 0
        rotate(bitmap, rotation.toFloat())
    }.getOrNull()

    fun decodeJpeg(
        bytes: ByteArray,
        rotationDegrees: Int,
        maxDimension: Int = MAX_DECODE_DIMENSION
    ): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@runCatching null
        rotate(bitmap, rotationDegrees.toFloat())
    }.getOrNull()

    fun decodeFile(path: String, maxDimension: Int = 0): Bitmap? = runCatching {
        if (maxDimension <= 0) {
            BitmapFactory.decodeFile(path)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, options)
        }
    }.getOrNull()

    /**
     * Downscale + enhance the page for OCR. AUTO binarises low-contrast / unevenly
     * lit pages and uses a contrast-boosted grayscale copy otherwise.
     */
    fun prepareForOcr(source: Bitmap, mode: PreprocessMode): Bitmap {
        val sized = scaleDown(source, MAX_OCR_DIMENSION)
        val scaled = ensureMinSize(sized, MIN_OCR_DIMENSION)
        if (scaled !== sized && sized !== source && !sized.isRecycled) sized.recycle()
        val resolved = when (mode) {
            PreprocessMode.AUTO ->
                if (isLowContrast(scaled)) PreprocessMode.BINARIZED else PreprocessMode.GRAYSCALE
            else -> mode
        }
        val prepared = when (resolved) {
            PreprocessMode.COLOR -> scaled
            PreprocessMode.BINARIZED -> binarize(scaled)
            else -> toGrayscale(scaled)
        }
        if (prepared !== scaled && scaled !== source && !scaled.isRecycled) scaled.recycle()
        return prepared
    }

    fun scaleDown(source: Bitmap, maxDimension: Int): Bitmap {
        val longestSide = max(source.width, source.height)
        if (longestSide <= maxDimension) return source
        val ratio = maxDimension.toFloat() / longestSide.toFloat()
        val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /**
     * Upscales tiny pages (e.g. a small WiFi card photographed from a distance) so
     * each character is large enough for the recognisers. Capped at 2x to keep
     * memory use predictable.
     */
    fun ensureMinSize(source: Bitmap, minDimension: Int): Bitmap {
        val longestSide = max(source.width, source.height)
        if (longestSide <= 0 || longestSide >= minDimension) return source
        val ratio = (minDimension.toFloat() / longestSide.toFloat()).coerceAtMost(2f)
        val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val rotated = rotateBitmap(source, degrees)
        if (rotated !== source && !source.isRecycled) source.recycle()
        return rotated
    }

    /**
     * Estimates how much the page is tilted (degrees, -8..8) using a horizontal
     * projection profile on a small grayscale copy.
     */
    fun detectSkewAngle(source: Bitmap): Float {
        val sample = scaleDown(source, MAX_SKEW_SAMPLE)
        return try {
            estimateSkew(sample)
        } finally {
            if (sample !== source && !sample.isRecycled) sample.recycle()
        }
    }

    /**
     * Straightens a tilted page. The rotation is undone if a second measurement
     * shows it did not improve the alignment, so the original can never get worse.
     */
    fun straighten(source: Bitmap): Bitmap {
        val angle = detectSkewAngle(source)
        if (abs(angle) < MIN_SKEW_DEGREES) return source
        val rotated = rotateBitmap(source, -angle)
        if (rotated === source) return source
        val residual = detectSkewAngle(rotated)
        return if (abs(residual) < abs(angle) - 0.25f) {
            rotated
        } else {
            if (!rotated.isRecycled) rotated.recycle()
            source
        }
    }

    fun createThumbnail(source: Bitmap, maxDimension: Int = 360): Bitmap {
        val longestSide = max(source.width, source.height).coerceAtLeast(1)
        val ratio = maxDimension.toFloat() / longestSide.toFloat()
        val width = (source.width * ratio).roundToInt().coerceIn(1, maxDimension)
        val height = (source.height * ratio).roundToInt().coerceIn(1, maxDimension)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) return scaled
        return source.copy(Bitmap.Config.ARGB_8888, false) ?: source
    }

    fun writeJpeg(bitmap: Bitmap, file: File, quality: Int = 90): Boolean {
        file.parentFile?.mkdirs()
        return runCatching {
            file.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }
        }.getOrDefault(false)
    }

    /** Saves a camera frame (raw JPEG bytes) straight to disk and writes its thumbnail. */
    fun savePageImage(
        bytes: ByteArray,
        rotationDegrees: Int,
        imageFile: File,
        thumbFile: File,
        autoStraighten: Boolean
    ): Boolean {
        val bitmap = try {
            decodeJpeg(bytes, rotationDegrees, MAX_DECODE_DIMENSION)
        } catch (error: OutOfMemoryError) {
            null
        } ?: return false
        return try {
            saveBitmapAsPage(bitmap, imageFile, thumbFile, autoStraighten)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Crops the centre of a captured JPEG (used by the small-card camera mode).
     * Returns upright JPEG bytes, so the caller passes rotation 0 to the OCR pipeline.
     */
    fun cropCenterJpeg(
        bytes: ByteArray,
        rotationDegrees: Int,
        widthFraction: Float,
        heightFraction: Float,
        quality: Int = 95
    ): ByteArray? = runCatching {
        val source = decodeJpeg(bytes, rotationDegrees, MAX_DECODE_DIMENSION)
            ?: return@runCatching null
        try {
            val width = (source.width * widthFraction.coerceIn(0.1f, 1f))
                .roundToInt().coerceIn(1, source.width)
            val height = (source.height * heightFraction.coerceIn(0.1f, 1f))
                .roundToInt().coerceIn(1, source.height)
            val left = ((source.width - width) / 2).coerceAtLeast(0)
            val top = ((source.height - height) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(source, left, top, width, height)
            try {
                val output = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, quality, output)
                output.toByteArray()
            } finally {
                if (cropped !== source && !cropped.isRecycled) cropped.recycle()
            }
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }.getOrNull()

    /** Writes a page image (optionally straightened) plus its thumbnail. */
    fun saveBitmapAsPage(
        source: Bitmap,
        imageFile: File,
        thumbFile: File,
        autoStraighten: Boolean
    ): Boolean {
        var straightened: Bitmap? = null
        var page = source
        if (autoStraighten) {
            val attempt = runCatching { straighten(source) }.getOrNull()
            if (attempt != null && attempt !== source) {
                straightened = attempt
                page = attempt
            }
        }
        return try {
            runCatching {
                if (!writeJpeg(page, imageFile, 90)) {
                    false
                } else {
                    val thumb = createThumbnail(page)
                    try {
                        writeJpeg(thumb, thumbFile, 78)
                    } finally {
                        if (thumb !== page) thumb.recycle()
                    }
                }
            }.getOrDefault(false)
        } finally {
            straightened?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /** Rotates a stored page in place (used by the "rotate page" action). */
    fun rotateFile(path: String, degrees: Float): Boolean {
        val decoded = try {
            decodeFile(path, MAX_DECODE_DIMENSION)
        } catch (error: OutOfMemoryError) {
            null
        } ?: return false
        return try {
            val rotated = runCatching { rotateBitmap(decoded, degrees) }
                .getOrNull()
                ?: return false
            try {
                val target = File(path)
                val temp = File(target.parentFile, target.name + ".tmp")
                if (!writeJpeg(rotated, temp, 90)) {
                    temp.delete()
                    false
                } else {
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) {
                        runCatching { temp.copyTo(target, overwrite = true) }
                        temp.delete()
                    }
                    true
                }
            } finally {
                if (rotated !== decoded && !rotated.isRecycled) rotated.recycle()
            }
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    fun createThumbnailFile(sourcePath: String, thumbPath: String): Boolean {
        val bitmap = decodeFile(sourcePath, 720) ?: return false
        return try {
            runCatching {
                val thumb = createThumbnail(bitmap)
                try {
                    writeJpeg(thumb, File(thumbPath), 78)
                } finally {
                    if (thumb !== bitmap) thumb.recycle()
                }
            }.getOrDefault(false)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        while (max(width, height) / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun estimateSkew(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 40 || height < 40) return 0f

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var luminanceSum = 0L
        for (pixel in pixels) luminanceSum += luminance(pixel)
        val mean = luminanceSum.toDouble() / pixels.size
        val threshold = (mean * 0.82).toInt()

        val dark = BooleanArray(pixels.size)
        var darkCount = 0
        for (index in pixels.indices) {
            if (luminance(pixels[index]) < threshold) {
                dark[index] = true
                darkCount++
            }
        }
        if (darkCount < pixels.size / 200) return 0f

        var bestAngle = 0f
        var bestScore = profileScore(dark, width, height, 0f)
        var angle = -MAX_SKEW_DEGREES
        while (angle <= MAX_SKEW_DEGREES) {
            if (abs(angle) >= 0.5f) {
                val score = profileScore(dark, width, height, angle)
                if (score > bestScore * 1.02) {
                    bestScore = score
                    bestAngle = angle
                }
            }
            angle += 0.5f
        }
        return bestAngle
    }

    private fun profileScore(dark: BooleanArray, width: Int, height: Int, degrees: Float): Double {
        val tangent = tan(Math.toRadians(degrees.toDouble())).toFloat()
        val extra = (abs(tangent) * width).toInt() + 2
        val bins = IntArray(height + extra)
        val offset = if (tangent < 0f) (-tangent * width).toInt() + 1 else 0

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!dark[row + x]) continue
                val index = y + offset + (x * tangent).toInt()
                if (index in bins.indices) bins[index]++
            }
        }

        var total = 0.0
        var square = 0.0
        for (value in bins) {
            total += value
            square += value.toDouble() * value
        }
        val count = bins.size.toDouble()
        val mean = total / count
        return square / count - mean * mean
    }

    private fun isLowContrast(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 64).coerceAtLeast(1)
        val stepY = (bitmap.height / 64).coerceAtLeast(1)
        var sum = 0.0
        var square = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val value = luminance(bitmap.getPixel(x, y))
                sum += value
                square += value.toDouble() * value
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false
        val mean = sum / count
        val variance = square / count - mean * mean
        // Only binarise really flat pages; thin digits on normal photos survive better
        // with the contrast-boosted grayscale path.
        return variance < 500.0
    }

    private fun toGrayscale(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val contrast = ColorMatrix(
            floatArrayOf(
                CONTRAST, 0f, 0f, 0f, CONTRAST_OFFSET,
                0f, CONTRAST, 0f, 0f, CONTRAST_OFFSET,
                0f, 0f, CONTRAST, 0f, CONTRAST_OFFSET,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayscale.postConcat(contrast)
        paint.colorFilter = ColorMatrixColorFilter(grayscale)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /**
     * Adaptive threshold (local mean via integral image). Works much better than a
     * global threshold on photos with shadows or uneven lighting.
     */
    private fun binarize(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = IntArray(pixels.size)
        for (index in pixels.indices) gray[index] = luminance(pixels[index])

        val stride = width + 1
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            val row = y * width
            for (x in 0 until width) {
                rowSum += gray[row + x]
                integral[(y + 1) * stride + (x + 1)] =
                    integral[y * stride + (x + 1)] + rowSum
            }
        }

        var graySum = 0L
        for (value in gray) graySum += value
        val thresholdOffset = max(8, (graySum / gray.size / 40).toInt())
        val radius = (min(width, height) / 20).coerceAtLeast(8)
        val output = IntArray(pixels.size)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()

        for (y in 0 until height) {
            val y1 = (y - radius).coerceAtLeast(0)
            val y2 = (y + radius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val x1 = (x - radius).coerceAtLeast(0)
                val x2 = (x + radius).coerceAtMost(width - 1)
                val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integral[(y2 + 1) * stride + (x2 + 1)] -
                    integral[y1 * stride + (x2 + 1)] -
                    integral[(y2 + 1) * stride + x1] +
                    integral[y1 * stride + x1]
                val mean = sum / count
                val value = gray[y * width + x]
                output[y * width + x] = if (value < mean - thresholdOffset) black else white
            }
        }

        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun luminance(pixel: Int): Int {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }
}
