package com.example.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.model.RecordingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

class RecordingRepository(private val context: Context) {

    companion object {
        private const val TAG = "RecordingRepository"
        const val SUBDIRECTORY_NAME = "Regonex Recorder"
    }

    suspend fun getRecentRecordings(): List<RecordingItem> = withContext(Dispatchers.IO) {
        val recordings = mutableListOf<RecordingItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        val selection: String?
        val selectionArgs: Array<String>?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            selectionArgs = arrayOf("%$SUBDIRECTORY_NAME%", "Regonex_%")
        } else {
            selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            selectionArgs = arrayOf("Regonex_%")
        }

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val rawName = cursor.getString(nameColumn) ?: "Recording_$id"
                    val displayName = rawName.removeSuffix(".mp4")
                    val size = cursor.getLong(sizeColumn)
                    val duration = cursor.getLong(durationColumn)
                    val dateAddedSec = cursor.getLong(dateColumn)
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    // Skip corrupt or empty entries
                    if (size > 0) {
                        recordings.add(
                            RecordingItem(
                                id = id,
                                title = displayName,
                                uri = contentUri,
                                fileSizeBytes = size,
                                durationMs = duration,
                                dateAddedMs = dateAddedSec * 1000L,
                                width = width,
                                height = height
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recent recordings: ${e.message}", e)
        }

        recordings
    }

    suspend fun saveVideoToGallery(
        sourceFile: File,
        fileName: String,
        durationMs: Long,
        width: Int,
        height: Int
    ): Uri? = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            Log.e(TAG, "Cannot publish to gallery: sourceFile missing or empty: ${sourceFile.absolutePath}")
            return@withContext null
        }

        Log.d(TAG, "Beginning gallery publication for ${sourceFile.name} (${sourceFile.length()} bytes, duration: ${durationMs}ms)")

        val nowSeconds = System.currentTimeMillis() / 1000L
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.TITLE, fileName)
            put(MediaStore.Video.Media.DISPLAY_NAME, "$fileName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, nowSeconds)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowSeconds)
            if (durationMs > 0) {
                put(MediaStore.Video.Media.DURATION, durationMs)
            }
            if (width > 0 && height > 0) {
                put(MediaStore.Video.Media.WIDTH, width)
                put(MediaStore.Video.Media.HEIGHT, height)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$SUBDIRECTORY_NAME")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val targetDir = File(moviesDir, SUBDIRECTORY_NAME)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val destFile = File(targetDir, "$fileName.mp4")
                put(MediaStore.Video.Media.DATA, destFile.absolutePath)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        var videoUri: Uri? = null
        try {
            videoUri = resolver.insert(collection, contentValues)
            if (videoUri == null) {
                Log.e(TAG, "Failed to create MediaStore entry for video")
                if (sourceFile.exists()) sourceFile.delete()
                return@withContext null
            }

            // Copy file data to destination stream
            resolver.openOutputStream(videoUri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                outputStream.flush()
            }

            // Clear pending flag once writing finishes so the file becomes immediately visible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(videoUri, updateValues, null, null)
                Log.d(TAG, "Cleared MediaStore IS_PENDING flag for $videoUri")
            }

            // Trigger MediaScanner for immediate discovery
            scanMediaFile(context, videoUri)

            // Clean up temporary local recording file
            if (sourceFile.exists()) {
                sourceFile.delete()
            }

            Log.d(TAG, "Successfully published and scanned video: $videoUri")
            videoUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed during gallery publication: ${e.message}", e)
            videoUri?.let { uri ->
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {}
            }
            if (sourceFile.exists()) {
                sourceFile.delete()
            }
            null
        }
    }

    private suspend fun scanMediaFile(context: Context, uri: Uri) = suspendCancellableCoroutine<Unit> { continuation ->
        try {
            val path = getFilePathFromUri(context, uri)
            val scanTargets = if (!path.isNullOrEmpty()) arrayOf(path) else arrayOf(uri.toString())
            MediaScannerConnection.scanFile(
                context,
                scanTargets,
                arrayOf("video/mp4")
            ) { _, _ ->
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
    }

    private fun getFilePathFromUri(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val proj = arrayOf(MediaStore.Video.Media.DATA)
                context.contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                        return cursor.getString(colIdx)
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    suspend fun deleteRecording(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val rows = context.contentResolver.delete(uri, null, null)
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete recording: ${e.message}", e)
            false
        }
    }

    suspend fun deleteAllRecordings(): Int = withContext(Dispatchers.IO) {
        val currentRecordings = getRecentRecordings()
        var deletedCount = 0
        for (item in currentRecordings) {
            if (deleteRecording(item.uri)) {
                deletedCount++
            }
        }
        deletedCount
    }
}
