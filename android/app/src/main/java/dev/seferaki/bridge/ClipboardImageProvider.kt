package dev.seferaki.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.util.UUID

/** Read-only, URI-grant-only provider for the current synchronized clipboard image. */
class ClipboardImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = resolve(uri)?.let { mimeType(it) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = resolve(uri) ?: return null
        val columns = projection?.map { it }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> file.name
            OpenableColumns.SIZE -> file.length()
            else -> null
        } })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Файл доступен только для чтения")
        val file = resolve(uri) ?: throw FileNotFoundException("Временное изображение истекло")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private fun resolve(uri: Uri): File? {
        val app = context ?: return null
        if (uri.scheme != "content" || uri.authority != authority(app) || uri.pathSegments.size != 1) return null
        val name = uri.lastPathSegment ?: return null
        if (!name.matches(Regex("[0-9a-fA-F-]{36}\\.(png|jpg)"))) return null
        val root = directory(app).canonicalFile
        val file = File(root, name).canonicalFile
        if (file.parentFile != root || !file.isFile || file.length() !in 1..ClipboardImage.MAX_BYTES.toLong()) return null
        if (System.currentTimeMillis() - file.lastModified() !in 0..IMAGE_TTL_MILLIS) return null
        return file
    }

    companion object {
        private const val IMAGE_TTL_MILLIS = 24L * 60 * 60 * 1000
        private const val DIRECTORY_NAME = "clipboard-images"

        private fun authority(context: Context) = "${context.packageName}.clipboard-images"
        private fun directory(context: Context) = File(context.cacheDir, DIRECTORY_NAME)
        private fun mimeType(file: File) = if (file.extension == "png") "image/png" else "image/jpeg"

        internal fun write(context: Context, image: ClipboardImage): Uri {
            val root = directory(context)
            if (!root.exists() && !root.mkdirs()) throw FileNotFoundException("Не удалось создать временное хранилище изображения")
            val extension = if (image.mimeType == "image/png") "png" else "jpg"
            val file = File(root, "${UUID.randomUUID()}.$extension")
            try {
                FileOutputStream(file).use { stream -> stream.write(image.data); stream.fd.sync() }
                file.setReadable(false, false)
                file.setReadable(true, true)
                pruneExpired(context)
                return Uri.Builder().scheme("content").authority(authority(context)).appendPath(file.name).build()
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

        internal fun delete(context: Context, uri: Uri) {
            val root = directory(context).canonicalFile
            val file = File(root, uri.lastPathSegment ?: return).canonicalFile
            if (file.parentFile == root) file.delete()
        }

        internal fun cleanupAfterClipboardChange(context: Context) {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Android may return null to a background service even when the clipboard
            // contains our URI. Keep the granted file until the clipboard is readable
            // again or its TTL expires; deleting it here would break a later paste.
            val clip = runCatching { manager.primaryClip }.getOrNull() ?: return
            val uri = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            val keep = if (uri?.authority == authority(context)) setOf(uri.lastPathSegment ?: "") else emptySet()
            prune(context, keep)
        }

        internal fun markCurrent(context: Context, uri: Uri) {
            val name = uri.lastPathSegment ?: return
            val root = directory(context).canonicalFile
            val file = File(root, name).canonicalFile
            if (file.parentFile != root || !file.isFile) return
            prune(context, setOf(name))
        }

        private fun pruneExpired(context: Context) {
            directory(context).listFiles()?.forEach { file ->
                val expired = System.currentTimeMillis() - file.lastModified() !in 0..IMAGE_TTL_MILLIS
                if (expired) file.delete()
            }
        }

        private fun prune(context: Context, keep: Set<String>) {
            directory(context).listFiles()?.forEach { file ->
                val expired = System.currentTimeMillis() - file.lastModified() !in 0..IMAGE_TTL_MILLIS
                if (file.name !in keep || expired) file.delete()
            }
        }
    }
}
