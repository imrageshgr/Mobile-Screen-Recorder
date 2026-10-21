package com.example.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.model.RecordingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

/**
 * VideoRepository
 * Failsafe MediaStore & local storage video repository.
 *
 * Implements dual-source querying:
 * 1. Primary: MediaStore.Video.Media under Movies/ScreenRecordings and REC_*.mp4
 * 2. Fallback: Local app directory (Movies/MyRecordings and Movies)
 * Ensures recorded videos are NEVER missing from the in-app "Recordings" screen.
 */
class VideoRepository(private val context: Context) {

    companion object {
        private const val TAG = "VideoRepository"
        const val SUBDIRECTORY_NAME = "ScreenRecordings"
        const val LOCAL_RECORDINGS_DIR = "MyRecordings"

        /**
         * Copies sourceFile into MediaStore.Video.Media under Movies/ScreenRecordings
         * with IS_PENDING = 1, copies byte stream, then updates IS_PENDING = 0.
         */
        suspend fun saveVideoToMediaStore(context: Context, sourceFile: File): Uri? = withContext(Dispatchers.IO) {
            if (!sourceFile.exists() || sourceFile.length() <= 0L) {
                Log.e(TAG, "Source file does not exist or is empty: ${sourceFile.absolutePath}")
                return@withContext null
            }

            val fileName = sourceFile.nameWithoutExtension
            val nowSeconds = System.currentTimeMillis() / 1000L

            var durationMs = 0L
            var width = 0
            var height = 0
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(sourceFile.absolutePath)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                retriever.release()
            } catch (_: Exception) {}

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.TITLE, fileName)
                put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, nowSeconds)
                put(MediaStore.Video.Media.DATE_MODIFIED, nowSeconds)
                if (durationMs > 0) put(MediaStore.Video.Media.DURATION, durationMs)
                if (width > 0 && height > 0) {
                    put(MediaStore.Video.Media.WIDTH, width)
                    put(MediaStore.Video.Media.HEIGHT, height)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Recordings")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    val targetDir = File(moviesDir, "Recordings")
                    if (!targetDir.exists()) targetDir.mkdirs()
                    val destFile = File(targetDir, sourceFile.name)
                    put(MediaStore.Video.Media.DATA, destFile.absolutePath)
                }
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

            var videoUri: Uri? = null
            try {
                videoUri = resolver.insert(collection, contentValues)
                if (videoUri == null) {
                    Log.e(TAG, "MediaStore insert returned null URI")
                    return@withContext null
                }

                resolver.openOutputStream(videoUri)?.use { outStream ->
                    FileInputStream(sourceFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    outStream.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val updateValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(videoUri, updateValues, null, null)
                }

                scanMediaFile(context, videoUri)
                Log.i(TAG, "Successfully exported video to MediaStore: $videoUri")
                videoUri
            } catch (e: Exception) {
                Log.e(TAG, "Error saving video to MediaStore: ${e.message}", e)
                videoUri?.let { uri ->
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                }
                null
            }
        }

        private suspend fun scanMediaFile(context: Context, uri: Uri) = suspendCancellableCoroutine<Unit> { cont ->
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(uri.toString()),
                    arrayOf("video/mp4")
                ) { _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    /**
     * Dual-source gallery query:
     * 1. MediaStore.Video.Media (RELATIVE_PATH LIKE "%ScreenRecordings%" OR DISPLAY_NAME LIKE "REC_%.mp4")
     * 2. Local files: Movies/MyRecordings and Movies directories inside getExternalFilesDir
     */
    suspend fun getRecordedVideos(): List<RecordingItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<RecordingItem>()
        val seenKeys = mutableSetOf<String>()

        // 1. Query MediaStore
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

        val selection: String
        val selectionArgs: Array<String>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            selectionArgs = arrayOf("%Recordings%", "%ScreenRecordings%", "REC_%", "ScreenRecord_%", "regonex_%")
        } else {
            selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            selectionArgs = arrayOf("REC_%", "ScreenRecord_%", "regonex_%")
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
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val rawName = cursor.getString(nameCol) ?: "REC_$id.mp4"
                    val displayName = rawName.removeSuffix(".mp4")
                    val size = cursor.getLong(sizeCol)
                    val duration = cursor.getLong(durCol)
                    val dateAddedSec = cursor.getLong(dateCol)
                    val width = cursor.getInt(wCol)
                    val height = cursor.getInt(hCol)

                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    if (size > 0) {
                        seenKeys.add(displayName.lowercase())
                        seenKeys.add(rawName.lowercase())
                        list.add(
                            RecordingItem(
                                id = id,
                                title = displayName,
                                uri = uri,
                                fileSizeBytes = size,
                                durationMs = duration,
                                dateAddedMs = if (dateAddedSec > 0) dateAddedSec * 1000L else System.currentTimeMillis(),
                                width = width,
                                height = height
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore recordings: ${e.message}", e)
        }

        // 2. Query Local Files: Movies/MyRecordings and root Movies directory
        try {
            val searchDirs = mutableListOf<File>()
            val appRecordingsDir = File(context.getExternalFilesDir(null), "Recordings")
            if (appRecordingsDir.exists() && appRecordingsDir.isDirectory) {
                searchDirs.add(appRecordingsDir)
            }
            val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (moviesDir != null && moviesDir.exists()) {
                searchDirs.add(moviesDir)
                val moviesRecDir = File(moviesDir, "Recordings")
                if (moviesRecDir.exists() && moviesRecDir.isDirectory) {
                    searchDirs.add(moviesRecDir)
                }
                val myRecDir = File(moviesDir, LOCAL_RECORDINGS_DIR)
                if (myRecDir.exists() && myRecDir.isDirectory) {
                    searchDirs.add(myRecDir)
                }
            }

            for (dir in searchDirs) {
                val localFiles = dir.listFiles { f ->
                    f.isFile && f.name.endsWith(".mp4", ignoreCase = true) && f.length() > 0
                } ?: emptyArray()

                for (file in localFiles) {
                    val baseName = file.nameWithoutExtension
                    val rawName = file.name
                    if (!seenKeys.contains(baseName.lowercase()) && !seenKeys.contains(rawName.lowercase())) {
                        seenKeys.add(baseName.lowercase())
                        seenKeys.add(rawName.lowercase())

                        var duration = 0L
                        var width = 0
                        var height = 0
                        try {
                            val retriever = MediaMetadataRetriever()
                            retriever.setDataSource(file.absolutePath)
                            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            retriever.release()
                        } catch (_: Exception) {}

                        val fileUri = try {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        } catch (_: Exception) {
                            Uri.fromFile(file)
                        }

                        list.add(
                            RecordingItem(
                                id = file.lastModified(),
                                title = baseName,
                                uri = fileUri,
                                fileSizeBytes = file.length(),
                                durationMs = duration,
                                dateAddedMs = file.lastModified(),
                                width = width,
                                height = height
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying local fallback recordings: ${e.message}", e)
        }

        list.sortByDescending { it.dateAddedMs }
        list
    }

    /**
     * Save helper instance method.
     */
    suspend fun saveVideoToMediaStore(sourceFile: File): Uri? {
        return saveVideoToMediaStore(context, sourceFile)
    }

    /**
     * Deletes both the MediaStore URI entry and the local file if present.
     */
    suspend fun deleteVideo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        var deletedAny = false
        var targetDisplayName: String? = null

        // 1. If it is a file URI, delete the file directly
        if (uri.scheme == "file") {
            try {
                val file = File(uri.path ?: "")
                targetDisplayName = file.nameWithoutExtension
                if (file.exists() && file.delete()) {
                    deletedAny = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed deleting file URI: ${e.message}", e)
            }
        }

        // 2. If it is a content URI, query display name and delete via ContentResolver
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        targetDisplayName = name?.removeSuffix(".mp4")
                    }
                }
            } catch (_: Exception) {}

            try {
                val rows = context.contentResolver.delete(uri, null, null)
                if (rows > 0) {
                    deletedAny = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "ContentResolver delete failed: ${e.message}", e)
            }
        }

        // 3. Delete matching local file in MyRecordings or Movies directory if present
        try {
            val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val searchDirs = mutableListOf<File>()
            if (moviesDir != null && moviesDir.exists()) {
                searchDirs.add(moviesDir)
                val myRecDir = File(moviesDir, LOCAL_RECORDINGS_DIR)
                if (myRecDir.exists()) searchDirs.add(myRecDir)
            }

            for (dir in searchDirs) {
                val files = dir.listFiles() ?: emptyArray()
                for (file in files) {
                    val matchesName = targetDisplayName != null && (
                        file.nameWithoutExtension.equals(targetDisplayName, ignoreCase = true) ||
                        file.name.equals(targetDisplayName, ignoreCase = true)
                    )
                    val matchesUri = uri.toString().contains(file.name)

                    if (matchesName || matchesUri) {
                        if (file.delete()) {
                            deletedAny = true
                            Log.d(TAG, "Deleted matching local file: ${file.absolutePath}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up local file during delete: ${e.message}")
        }

        deletedAny
    }

    suspend fun deleteAllVideos(): Int = withContext(Dispatchers.IO) {
        val currentList = getRecordedVideos()
        var count = 0
        for (item in currentList) {
            if (deleteVideo(item.uri)) {
                count++
            }
        }
        count
    }
}
