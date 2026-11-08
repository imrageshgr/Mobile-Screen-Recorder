package com.example.recorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.example.repository.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * ShortClipExtractor
 * Extracts the last N seconds of an ongoing or completed MP4 recording
 * into a standalone short clip MP4 in the dedicated "ShortClips" directory.
 *
 * Uses MediaExtractor and MediaMuxer to perform lossless track copying
 * without re-encoding, taking only ~100-200ms.
 */
object ShortClipExtractor {
    private const val TAG = "ShortClipExtractor"
    private const val BUFFER_SIZE = 1024 * 1024 // 1 MB buffer

    /**
     * Extracts a clip of [clipDurationSeconds] duration from [sourceVideoFile].
     * If total video duration is less than or equal to [clipDurationSeconds],
     * it copies the entire video as the clip.
     *
     * Saves into app's ShortClips directory and exports to MediaStore Movies/ShortClips.
     */
    suspend fun extractShortClip(
        context: Context,
        sourceVideoFile: File,
        clipDurationSeconds: Int = 30
    ): Pair<File, Uri?>? = withContext(Dispatchers.IO) {
        if (!sourceVideoFile.exists() || sourceVideoFile.length() <= 0L) {
            Log.e(TAG, "Source video file does not exist or is empty")
            return@withContext null
        }

        // Dedicated ShortClips directory
        val clipsDir = File(context.getExternalFilesDir(null), "ShortClips").apply {
            if (!exists()) mkdirs()
        }
        val clipFile = File(clipsDir, "CLIP_${System.currentTimeMillis()}.mp4")

        try {
            // Determine video duration in microseconds
            var durationUs = 0L
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(sourceVideoFile.absolutePath)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                durationUs = durMs * 1000L
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract duration via metadata retriever: ${e.message}")
            }

            val targetClipDurationUs = clipDurationSeconds.toLong() * 1_000_000L
            val startUs = if (durationUs > targetClipDurationUs) {
                durationUs - targetClipDurationUs
            } else {
                0L
            }

            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(sourceVideoFile.absolutePath)

            val muxer = MediaMuxer(clipFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIndexMap = mutableMapOf<Int, Int>()

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    videoExtractor.selectTrack(i)
                    val outTrack = muxer.addTrack(format)
                    trackIndexMap[i] = outTrack
                }
            }

            if (trackIndexMap.isEmpty()) {
                Log.e(TAG, "No valid video or audio tracks found in source file")
                videoExtractor.release()
                muxer.release()
                clipFile.delete()
                return@withContext null
            }

            muxer.start()

            // Seek to start position on video keyframe
            if (startUs > 0) {
                videoExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            var basePtsUs = -1L

            while (true) {
                buffer.clear()
                val trackIndex = videoExtractor.sampleTrackIndex
                if (trackIndex < 0) break

                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = videoExtractor.sampleTime
                if (basePtsUs < 0) {
                    basePtsUs = sampleTime
                }

                val outTrack = trackIndexMap[trackIndex]
                if (outTrack != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = (sampleTime - basePtsUs).coerceAtLeast(0L)
                    bufferInfo.flags = videoExtractor.sampleFlags
                    muxer.writeSampleData(outTrack, buffer, bufferInfo)
                }

                videoExtractor.advance()
            }

            muxer.stop()
            muxer.release()
            videoExtractor.release()

            if (clipFile.exists() && clipFile.length() > 0L) {
                Log.i(TAG, "Short clip created successfully: ${clipFile.name} (${clipFile.length()} bytes)")
                val uri = VideoRepository.saveVideoToMediaStore(
                    context = context,
                    sourceFile = clipFile,
                    subDirectory = "ShortClips"
                )
                return@withContext Pair(clipFile, uri)
            } else {
                clipFile.delete()
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract short clip: ${e.message}", e)
            try { clipFile.delete() } catch (_: Exception) {}
            return@withContext null
        }
    }
}
