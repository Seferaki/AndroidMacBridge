package dev.seferaki.bridge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

internal object ReceivedFilesDirectory {
    private const val FOLDER_NAME = "AndroidMacBridge"
    private const val LEGACY_FOLDER_NAME = "SamsungMacBridge"

    fun relativePath(): String = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER_NAME"

    /** Move files created by older app versions from Downloads into Documents. */
    fun migrateLegacyFiles(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val oldRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/$LEGACY_FOLDER_NAME"
        val destinationPath = relativePath()

        runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf("$oldRelativePath/", oldRelativePath),
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val ownerColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                while (cursor.moveToNext()) {
                    val owner = cursor.getString(ownerColumn)
                    if (owner != null && owner != context.packageName) continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn)
                    )
                    runCatching {
                        resolver.update(
                            uri,
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, destinationPath)
                            },
                            null,
                            null
                        )
                    }
                }
            }
        }
    }
}
