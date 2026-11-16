package dev.seferaki.bridge

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.MediaStore
import java.io.Closeable
import java.util.LinkedHashSet
import java.util.Locale

internal data class ScreenshotMediaItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String
)

/** Observes new, completed system screenshots without opening the Bridge activity. */
internal class ClipboardScreenshotObserver(
    context: Context,
    private val onScreenshot: (ScreenshotMediaItem) -> Unit
) : Closeable {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val seenUris = LinkedHashSet<String>()
    private var startedAtSeconds = 0L
    private var registered = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (!registered) return
            if (uri != null && uri.lastPathSegment?.toLongOrNull() != null) {
                inspect(uri, attempt = 0)
            } else {
                inspectRecent()
            }
        }
    }

    /** Returns false when full image access has not been granted. */
    fun start(): Boolean {
        if (!hasFullImagePermission()) {
            return false
        }
        runCatching {
            // Snapshot existing screenshot IDs before listening, then scan the short gap after
            // registration. This avoids both re-sending old screenshots and missing a capture
            // made while the observer was being registered.
            rememberExistingScreenshots()
            startedAtSeconds = System.currentTimeMillis() / 1000L
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            registered = true
            inspectRecent()
        }.onFailure {
            close()
            return false
        }
        return true
    }

    override fun close() {
        if (!registered) return
        registered = false
        runCatching { resolver.unregisterContentObserver(observer) }
        handler.removeCallbacksAndMessages(null)
    }

    private fun rememberExistingScreenshots() {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        runCatching {
            resolver.query(uri, PROJECTION, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val pendingColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)
                var count = 0
                while (cursor.moveToNext() && count++ < MAX_BASELINE_ROWS) {
                    if (cursor.getInt(pendingColumn) != 0) continue
                    val name = cursor.getString(nameColumn) ?: continue
                    val path = cursor.getString(pathColumn).orEmpty()
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    if (!isScreenshot(name, path, mime)) continue
                    seenUris.add(ContentUris.withAppendedId(uri, cursor.getLong(idColumn)).toString())
                }
            }
        }
    }

    private fun inspectRecent() {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf(startedAtSeconds.toString())
        runCatching {
            resolver.query(uri, PROJECTION, selection, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    inspect(ContentUris.withAppendedId(uri, cursor.getLong(idColumn)), attempt = 0)
                }
            }
        }
    }

    private fun inspect(uri: Uri, attempt: Int) {
        var canRetry = false
        val item = runCatching {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) { canRetry = true; return@use null }
                val pendingColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)
                if (cursor.getInt(pendingColumn) != 0) { canRetry = true; return@use null }
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: return@use null
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)).orEmpty()
                val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)).orEmpty()
                if (!isScreenshot(name, path, mime)) return@use null
                ScreenshotMediaItem(uri, name, mime)
            }
        }.getOrNull()

        if (item == null) {
            if (canRetry && attempt < MAX_PENDING_RETRIES && registered) {
                handler.postDelayed({ inspect(uri, attempt + 1) }, PENDING_RETRY_DELAY_MS)
            }
            return
        }
        synchronized(seenUris) {
            if (!seenUris.add(uri.toString())) return
            while (seenUris.size > MAX_REMEMBERED_URIS) seenUris.remove(seenUris.first())
        }
        onScreenshot(item)
    }

    private fun isScreenshot(name: String, path: String, mime: String): Boolean {
        if (mime != "image/png" && mime != "image/jpeg") return false
        val normalizedName = name.lowercase(Locale.ROOT)
        val normalizedPath = path.lowercase(Locale.ROOT).trim('/')
        val screenshotFolder = normalizedPath.split('/').any {
            it == "screenshots" || it == "screen shots" || it == "снимки экрана"
        }
        val screenshotName = normalizedName.startsWith("screenshot") ||
            normalizedName.startsWith("screen shot") ||
            normalizedName.startsWith("снимок экрана") ||
            normalizedName.startsWith("снимок_экрана")
        return screenshotFolder || screenshotName
    }

    private fun hasFullImagePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return appContext.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val MAX_PENDING_RETRIES = 5
        private const val PENDING_RETRY_DELAY_MS = 500L
        private const val MAX_BASELINE_ROWS = 1000
        private const val MAX_REMEMBERED_URIS = 512
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.IS_PENDING
        )
    }
}
