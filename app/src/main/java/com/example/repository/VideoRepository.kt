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
         * Copies sourceFile into MediaStore.Video.Media under Movies/{subDirectory}
         * with IS_PENDING = 1, copies byte stream, then updates IS_PENDING = 0.
         */
        suspend fun saveVideoToMediaStore(
            context: Context,
            sourceFile: File,
            subDirectory: String = "Recordings"
        ): Uri? = withContext(Dispatchers.IO) {
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
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$subDirectory")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    val targetDir = File(moviesDir, subDirectory)
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

                try {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(sourceFile.absolutePath),
                        arrayOf("video/mp4"),
                        null
                    )
                } catch (_: Exception) {}

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
    }

    /**
     * Collects all deduplicated local recording & short-clip directories searched by both
     * getRecordedVideos() and deleteVideo() so UI listing and deletion stay 100% in sync.
     */
    private fun getLocalSearchDirectories(): List<File> {
        val dirs = LinkedHashMap<String, File>()
        fun addIfDir(dir: File?) {
            try {
                if (dir != null && dir.exists() && dir.isDirectory) {
                    val key = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                    dirs[key] = dir
                }
            } catch (_: Exception) {}
        }

        try {
            val externalRoot = context.getExternalFilesDir(null)
            if (externalRoot != null) {
                addIfDir(File(externalRoot, "Recordings"))
                addIfDir(File(externalRoot, "ShortClips"))
            }

            val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (moviesDir != null) {
                addIfDir(moviesDir)
                addIfDir(File(moviesDir, "Recordings"))
                addIfDir(File(moviesDir, "ShortClips"))
                addIfDir(File(moviesDir, LOCAL_RECORDINGS_DIR))
            }

            @Suppress("DEPRECATION")
            val publicMoviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (publicMoviesDir != null) {
                addIfDir(publicMoviesDir)
                addIfDir(File(publicMoviesDir, "Recordings"))
                addIfDir(File(publicMoviesDir, "ShortClips"))
                addIfDir(File(publicMoviesDir, SUBDIRECTORY_NAME))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving local search directories: ${e.message}")
        }

        return dirs.values.toList()
    }

    /**
     * Quickly validates that a MediaStore URI still points to a real, non-empty backing file.
     * Returns false if the file size is 0 or the URI cannot be opened (e.g. deleted via file manager).
     */
    private fun isMediaStoreUriValid(uri: Uri, reportedSize: Long): Boolean {
        if (reportedSize <= 0L) return false
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val afdLen = afd.length
                val statSize = try {
                    afd.parcelFileDescriptor.statSize
                } catch (_: Exception) {
                    -1L
                }
                // Reject if either descriptor explicitly reports 0 bytes on disk
                if (afdLen == 0L || statSize == 0L) {
                    false
                } else {
                    val effectiveSize = when {
                        afdLen > 0L -> afdLen
                        statSize > 0L -> statSize
                        else -> reportedSize
                    }
                    effectiveSize > 0L
                }
            } ?: false
        } catch (e: Exception) {
            Log.d(TAG, "Skipping ghost or unreadable MediaStore entry ($uri): ${e.message}")
            false
        }
    }

    /**
     * Dual-source gallery query with active ghost-entry filtering:
     * 1. MediaStore.Video.Media: verifies reported size > 0 AND URI can actually be opened.
     * 2. Local files: strictly verifies file.exists() && file.isFile && file.length() > 0 before adding.
     */
    suspend fun getRecordedVideos(): List<RecordingItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<RecordingItem>()
        val seenKeys = mutableSetOf<String>()

        // 1. Query MediaStore with active ghost/invalid URI validation
        try {
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
                selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
                selectionArgs = arrayOf("%Recordings%", "%ScreenRecordings%", "%ShortClips%", "REC_%", "CLIP_%", "ScreenRecord_%", "regonex_%")
            } else {
                selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
                selectionArgs = arrayOf("REC_%", "CLIP_%", "ScreenRecord_%", "regonex_%")
            }

            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

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
                    try {
                        val id = cursor.getLong(idCol)
                        val rawName = cursor.getString(nameCol) ?: "REC_$id.mp4"
                        val displayName = rawName.removeSuffix(".mp4")
                        val size = cursor.getLong(sizeCol)
                        val duration = cursor.getLong(durCol)
                        val dateAddedSec = cursor.getLong(dateCol)
                        val width = cursor.getInt(wCol)
                        val height = cursor.getInt(hCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                        // Skip ghost entries: require size > 0 and verifiable openable URI
                        if (size <= 0L || !isMediaStoreUriValid(uri, size)) {
                            continue
                        }

                        val isClip = rawName.startsWith("CLIP_", ignoreCase = true) ||
                            displayName.contains("clip", ignoreCase = true)

                        seenKeys.add(displayName.lowercase())
                        seenKeys.add(rawName.lowercase())
                        list.add(
                            RecordingItem(
                                id = id,
                                title = displayName,
                                uri = uri,
                                fileSizeBytes = size,
                                durationMs = duration,
                                dateAddedMs = if (dateAddedSec > 0L) dateAddedSec * 1000L else System.currentTimeMillis(),
                                width = width,
                                height = height,
                                isClip = isClip
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping invalid MediaStore row: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore recordings: ${e.message}", e)
        }

        // 2. Query Local Files with strict file.exists() && file.length() > 0 validation
        try {
            val searchDirs = getLocalSearchDirectories()

            for (dir in searchDirs) {
                val localFiles = try {
                    dir.listFiles { f ->
                        f.exists() && f.isFile && f.name.endsWith(".mp4", ignoreCase = true) && f.length() > 0L
                    } ?: emptyArray()
                } catch (e: Exception) {
                    Log.w(TAG, "Error listing directory ${dir.absolutePath}: ${e.message}")
                    emptyArray()
                }

                for (file in localFiles) {
                    try {
                        // Strict check right before adding to guarantee no ghost/zero-byte entries
                        val fileLength = file.length()
                        if (!file.exists() || !file.isFile || fileLength <= 0L) {
                            continue
                        }

                        val baseName = file.nameWithoutExtension
                        val rawName = file.name
                        if (seenKeys.contains(baseName.lowercase()) || seenKeys.contains(rawName.lowercase())) {
                            continue
                        }

                        seenKeys.add(baseName.lowercase())
                        seenKeys.add(rawName.lowercase())

                        var duration = 0L
                        var width = 0
                        var height = 0
                        var retriever: MediaMetadataRetriever? = null
                        try {
                            retriever = MediaMetadataRetriever()
                            retriever.setDataSource(file.absolutePath)
                            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        } catch (_: Exception) {
                        } finally {
                            try { retriever?.release() } catch (_: Exception) {}
                        }

                        val fileUri = try {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        } catch (_: Exception) {
                            Uri.fromFile(file)
                        }

                        val isClip = rawName.startsWith("CLIP_", ignoreCase = true) ||
                            file.parentFile?.name?.equals("ShortClips", ignoreCase = true) == true ||
                            baseName.contains("clip", ignoreCase = true)

                        list.add(
                            RecordingItem(
                                id = file.lastModified(),
                                title = baseName,
                                uri = fileUri,
                                fileSizeBytes = fileLength,
                                durationMs = duration,
                                dateAddedMs = file.lastModified(),
                                width = width,
                                height = height,
                                isClip = isClip
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unreadable local file ${file.name}: ${e.message}")
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
     * Deletes both the physical local file and its MediaStore entry (and vice versa),
     * wrapped in robust try-catch blocks so stale or externally removed files never crash.
     */
    suspend fun deleteVideo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        var deletedAny = false
        var targetDisplayName: String? = null
        var resolvedFilePath: String? = null

        try {
            // 1. Inspect URI to gather file path and display name before deletion
            when (uri.scheme) {
                "file" -> {
                    try {
                        val path = uri.path
                        if (!path.isNullOrBlank()) {
                            val file = File(path)
                            resolvedFilePath = file.absolutePath
                            targetDisplayName = file.nameWithoutExtension
                            if (file.exists() && file.delete()) {
                                deletedAny = true
                                Log.d(TAG, "Deleted file URI directly: ${file.absolutePath}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed deleting file URI directly: ${e.message}")
                    }
                }
                "content" -> {
                    // Extract filename from FileProvider path segment if applicable
                    try {
                        if (uri.authority?.contains("fileprovider", ignoreCase = true) == true) {
                            val lastSegment = uri.lastPathSegment?.substringAfterLast('/')
                            if (!lastSegment.isNullOrBlank()) {
                                targetDisplayName = lastSegment.removeSuffix(".mp4")
                            }
                        }
                    } catch (_: Exception) {}

                    // Query MediaStore for DISPLAY_NAME (and DATA when accessible) before removing row
                    try {
                        val queryCols = arrayOf(
                            MediaStore.Video.Media.DISPLAY_NAME,
                            MediaStore.Video.Media.DATA
                        )
                        context.contentResolver.query(uri, queryCols, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                                if (nameIdx >= 0) {
                                    val name = cursor.getString(nameIdx)
                                    if (!name.isNullOrBlank()) {
                                        targetDisplayName = name.removeSuffix(".mp4")
                                    }
                                }
                                val dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                                if (dataIdx >= 0) {
                                    val dataPath = cursor.getString(dataIdx)
                                    if (!dataPath.isNullOrBlank()) {
                                        resolvedFilePath = dataPath
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Metadata pre-query skipped for $uri: ${e.message}")
                    }
                }
            }

            // 2. Explicitly call ContentResolver.delete(uri, null, null) to remove the MediaStore / provider entry
            try {
                val rows = context.contentResolver.delete(uri, null, null)
                if (rows > 0) {
                    deletedAny = true
                    Log.d(TAG, "ContentResolver deleted $rows row(s) for $uri")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ContentResolver delete warning for $uri: ${e.message}")
            }

            // 3. Delete resolved backing file if still present on disk
            resolvedFilePath?.takeIf { it.isNotBlank() }?.let { filePath ->
                try {
                    val file = File(filePath)
                    if (file.exists() && file.delete()) {
                        deletedAny = true
                        Log.d(TAG, "Deleted resolved backing file: ${file.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not delete resolved backing file: ${e.message}")
                }
            }

            // 4. Delete matching local files across all app & Movies directories (Recordings, ShortClips, MyRecordings, etc.)
            val displayName = targetDisplayName?.takeIf { it.isNotBlank() }
            try {
                val searchDirs = getLocalSearchDirectories()
                val lastSegment = uri.lastPathSegment?.substringAfterLast('/')

                for (dir in searchDirs) {
                    val files = try { dir.listFiles() ?: emptyArray() } catch (_: Exception) { emptyArray() }
                    for (file in files) {
                        try {
                            val matchesName = displayName != null && (
                                file.nameWithoutExtension.equals(displayName, ignoreCase = true) ||
                                file.name.equals(displayName, ignoreCase = true)
                            )
                            val matchesSegment = !lastSegment.isNullOrBlank() && (
                                file.name.equals(lastSegment, ignoreCase = true) ||
                                file.nameWithoutExtension.equals(lastSegment.removeSuffix(".mp4"), ignoreCase = true)
                            )
                            val matchesUri = uri.toString().contains(file.name)

                            if (matchesName || matchesSegment || matchesUri) {
                                if (file.exists() && file.delete()) {
                                    deletedAny = true
                                    Log.d(TAG, "Deleted matching local file: ${file.absolutePath}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed deleting local file ${file.name}: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up local files during delete: ${e.message}")
            }

            // 5. Vice versa: ensure any remaining MediaStore entry matching this file name is also deleted
            if (displayName != null) {
                try {
                    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    } else {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                    val exactMp4Name = if (displayName.endsWith(".mp4", ignoreCase = true)) {
                        displayName
                    } else {
                        "$displayName.mp4"
                    }
                    val rows = context.contentResolver.delete(
                        collection,
                        "${MediaStore.Video.Media.DISPLAY_NAME} = ? OR ${MediaStore.Video.Media.DISPLAY_NAME} = ?",
                        arrayOf(exactMp4Name, displayName)
                    )
                    if (rows > 0) {
                        deletedAny = true
                        Log.d(TAG, "Cleaned up $rows matching MediaStore row(s) for $exactMp4Name")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore cleanup by display name warning: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error deleting video ($uri): ${e.message}", e)
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
