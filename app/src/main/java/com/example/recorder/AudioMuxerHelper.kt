package com.example.recorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object AudioMuxerHelper {
    private const val TAG = "AudioMuxerHelper"
    private const val BUFFER_SIZE = 1024 * 1024 // 1 MB buffer for fast block copy

    /**
     * Fast, loss-free track muxing: Combines H.264 video track from videoSourceFile
     * and AAC audio track from audioSourceFile into destinationFile.
     *
     * CRITICAL: Tracks are interleaved in strictly monotonic presentation timestamp (PTS) order.
     * Writing all video frames followed by audio frames causes MPEG-4 timestamp rewinds,
     * leading to severe playback freezing, audio desync, and player glitches.
     */
    fun mergeVideoAndAudio(
        videoSourceFile: File,
        audioSourceFile: File,
        destinationFile: File
    ): Boolean {
        if (!videoSourceFile.exists() || videoSourceFile.length() <= 0L) {
            Log.e(TAG, "Video source file is missing or empty")
            return false
        }

        // If audio source is missing, too small (< 2KB), or empty, preserve video by direct copy
        if (!audioSourceFile.exists() || audioSourceFile.length() < 2048L) {
            Log.w(TAG, "Audio source is empty or missing, copying video directly to destination")
            return copyDirectly(videoSourceFile, destinationFile)
        }

        val tempMergedFile = File(destinationFile.parentFile ?: destinationFile.absoluteFile.parentFile, "merged_${System.currentTimeMillis()}.mp4")

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor = MediaExtractor().apply {
                setDataSource(videoSourceFile.absolutePath)
            }
            audioExtractor = MediaExtractor().apply {
                setDataSource(audioSourceFile.absolutePath)
            }

            val inVideoTrack = findTrack(videoExtractor, "video/")
            if (inVideoTrack < 0) {
                Log.e(TAG, "No video track found in video source")
                return copyDirectly(videoSourceFile, destinationFile)
            }

            val inAudioTrack = findTrack(audioExtractor, "audio/")
            if (inAudioTrack < 0) {
                Log.w(TAG, "No audio track found in audio source, copying video only")
                return copyDirectly(videoSourceFile, destinationFile)
            }

            muxer = MediaMuxer(tempMergedFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            videoExtractor.selectTrack(inVideoTrack)
            val videoFormat = videoExtractor.getTrackFormat(inVideoTrack)
            val outVideoTrack = muxer.addTrack(videoFormat)

            audioExtractor.selectTrack(inAudioTrack)
            val audioFormat = audioExtractor.getTrackFormat(inAudioTrack)
            val outAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()

            // Interleave both video and audio tracks chronologically
            interleaveTracks(
                videoExtractor = videoExtractor,
                audioExtractor = audioExtractor,
                muxer = muxer,
                outVideoTrack = outVideoTrack,
                outAudioTrack = outAudioTrack
            )

            muxer.stop()
            muxer.release()
            muxer = null

            // Safely move tempMergedFile to destinationFile
            if (tempMergedFile.exists() && tempMergedFile.length() > 0L) {
                if (destinationFile.exists()) destinationFile.delete()
                val renamed = tempMergedFile.renameTo(destinationFile)
                if (!renamed) {
                    tempMergedFile.copyTo(destinationFile, overwrite = true)
                    tempMergedFile.delete()
                }
                Log.d(TAG, "Successfully interleaved & merged video & audio -> ${destinationFile.name} (${destinationFile.length()}b)")
                return true
            } else {
                Log.w(TAG, "Merged file was empty, falling back to direct video copy")
                return copyDirectly(videoSourceFile, destinationFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during MediaMuxer merge: ${e.message}", e)
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            try { tempMergedFile.delete() } catch (_: Exception) {}
            return copyDirectly(videoSourceFile, destinationFile)
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            if (tempMergedFile.exists()) {
                try { tempMergedFile.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun interleaveTracks(
        videoExtractor: MediaExtractor,
        audioExtractor: MediaExtractor,
        muxer: MediaMuxer,
        outVideoTrack: Int,
        outAudioTrack: Int
    ) {
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        val bufferInfo = MediaCodec.BufferInfo()

        var videoDone = false
        var audioDone = false

        var videoBasePtsUs = -1L
        var audioBasePtsUs = -1L
        var lastVideoPtsUs = 0L
        var lastAudioPtsUs = 0L

        while (!videoDone || !audioDone) {
            val rawVideoTime = if (!videoDone) videoExtractor.sampleTime else Long.MAX_VALUE
            val rawAudioTime = if (!audioDone) audioExtractor.sampleTime else Long.MAX_VALUE

            if (videoBasePtsUs < 0 && rawVideoTime in 0 until Long.MAX_VALUE) {
                videoBasePtsUs = rawVideoTime
            }
            if (audioBasePtsUs < 0 && rawAudioTime in 0 until Long.MAX_VALUE) {
                audioBasePtsUs = rawAudioTime
            }

            val normVideoTime = if (!videoDone && videoBasePtsUs >= 0 && rawVideoTime in 0 until Long.MAX_VALUE) {
                (rawVideoTime - videoBasePtsUs).coerceAtLeast(0L)
            } else Long.MAX_VALUE

            val normAudioTime = if (!audioDone && audioBasePtsUs >= 0 && rawAudioTime in 0 until Long.MAX_VALUE) {
                (rawAudioTime - audioBasePtsUs).coerceAtLeast(0L)
            } else Long.MAX_VALUE

            if (!videoDone && (audioDone || normVideoTime <= normAudioTime)) {
                buffer.clear()
                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize >= 0) {
                    val pts = if (videoBasePtsUs >= 0) {
                        (videoExtractor.sampleTime - videoBasePtsUs).coerceAtLeast(lastVideoPtsUs)
                    } else lastVideoPtsUs
                    lastVideoPtsUs = pts

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = pts
                    bufferInfo.flags = videoExtractor.sampleFlags
                    muxer.writeSampleData(outVideoTrack, buffer, bufferInfo)
                    videoExtractor.advance()
                } else {
                    videoDone = true
                }
            } else if (!audioDone) {
                buffer.clear()
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize >= 0) {
                    val pts = if (audioBasePtsUs >= 0) {
                        (audioExtractor.sampleTime - audioBasePtsUs).coerceAtLeast(lastAudioPtsUs)
                    } else lastAudioPtsUs
                    lastAudioPtsUs = pts

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = pts
                    bufferInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(outAudioTrack, buffer, bufferInfo)
                    audioExtractor.advance()
                } else {
                    audioDone = true
                }
            }
        }
    }

    private fun copyDirectly(source: File, destination: File): Boolean {
        return try {
            if (source.absolutePath == destination.absolutePath) return true
            source.copyTo(destination, overwrite = true)
            destination.exists() && destination.length() > 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed direct copy: ${e.message}")
            false
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith(mimePrefix)) {
                return i
            }
        }
        return -1
    }
}
