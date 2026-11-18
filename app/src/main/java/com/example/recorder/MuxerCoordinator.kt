package com.example.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * MuxerCoordinator
 * Single, real-time MediaMuxer coordinator that accepts live encoded H.264 video
 * and AAC audio ByteBuffer frames concurrently from VideoEncoderWrapper and InternalAudioRecorder,
 * writing them directly into one synchronized REC_<timestamp>.mp4 file with zero intermediate temp files.
 */
class MuxerCoordinator(
    private val outputFile: File,
    expectedTrackCount: Int
) {
    companion object {
        private const val TAG = "MuxerCoordinator"
        private const val MAX_PENDING_SAMPLES = 300
    }

    private data class QueuedSample(
        val isVideo: Boolean,
        val data: ByteArray,
        val bufferInfo: MediaCodec.BufferInfo
    )

    private var expectedTracks: Int = expectedTrackCount.coerceIn(1, 2)
    private var mediaMuxer: MediaMuxer? = null
    private var isStarted = false
    private var isReleased = false

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    // Per-track PTS normalization so video (Surface clock) & audio (sample clock) start aligned at 0 us
    private var firstVideoRawPtsUs = -1L
    private var firstAudioRawPtsUs = -1L
    private var lastVideoWrittenPtsUs = -1L
    private var lastAudioWrittenPtsUs = -1L

    private val pendingSamples = mutableListOf<QueuedSample>()
    private val lock = Any()

    init {
        try {
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            Log.d(TAG, "Real-time MediaMuxer initialized for ${outputFile.name} (expectedTracks=$expectedTracks)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaMuxer for ${outputFile.name}: ${e.message}", e)
        }
    }

    fun setOrientation(degrees: Int) {
        synchronized(lock) {
            val muxer = mediaMuxer ?: return
            if (!isStarted && !isReleased) {
                try {
                    muxer.setOrientationHint(degrees)
                    Log.d(TAG, "MediaMuxer orientation hint set to $degrees degrees")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set orientation hint: ${e.message}")
                }
            }
        }
    }

    fun setVideoFormat(format: MediaFormat): Int {
        synchronized(lock) {
            val muxer = mediaMuxer ?: return -1
            if (isReleased) return -1
            if (videoTrackIndex >= 0) return videoTrackIndex

            try {
                videoTrackIndex = muxer.addTrack(format)
                Log.d(TAG, "Video track registered at index $videoTrackIndex")
                checkStartConditionLocked()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add video track: ${e.message}", e)
            }
            return videoTrackIndex
        }
    }

    fun setAudioFormat(format: MediaFormat): Int {
        synchronized(lock) {
            val muxer = mediaMuxer ?: return -1
            if (isReleased) return -1
            if (audioTrackIndex >= 0) return audioTrackIndex

            try {
                audioTrackIndex = muxer.addTrack(format)
                Log.d(TAG, "Audio track registered at index $audioTrackIndex")
                checkStartConditionLocked()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add audio track: ${e.message}", e)
            }
            return audioTrackIndex
        }
    }

    /**
     * Called when audio capture could not initialize so the muxer does not wait indefinitely
     * for an audio MediaFormat and starts immediately with the video track alone.
     */
    fun onAudioTrackUnavailable() {
        synchronized(lock) {
            if (isReleased || isStarted) return
            if (expectedTracks > 1) {
                expectedTracks = 1
                Log.w(TAG, "Audio track unavailable; falling back to single video track muxing")
                checkStartConditionLocked()
            }
        }
    }

    /**
     * Accepts a live encoded H.264 video ByteBuffer frame and writes (or queues until start) it in real time.
     */
    fun writeVideoSample(byteBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (isReleased || mediaMuxer == null || bufferInfo.size <= 0) return
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return

            val normalizedPts = normalizeVideoPtsLocked(bufferInfo.presentationTimeUs)
            val safeInfo = MediaCodec.BufferInfo().apply {
                set(bufferInfo.offset, bufferInfo.size, normalizedPts, bufferInfo.flags)
            }

            if (isStarted && videoTrackIndex >= 0) {
                writeLocked(videoTrackIndex, byteBuffer, safeInfo)
            } else {
                queuePendingLocked(isVideo = true, byteBuffer = byteBuffer, info = safeInfo)
            }
        }
    }

    /**
     * Accepts a live encoded AAC audio ByteBuffer frame and writes (or queues until start) it in real time.
     */
    fun writeAudioSample(byteBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (isReleased || mediaMuxer == null || bufferInfo.size <= 0) return
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return

            val normalizedPts = normalizeAudioPtsLocked(bufferInfo.presentationTimeUs)
            val safeInfo = MediaCodec.BufferInfo().apply {
                set(bufferInfo.offset, bufferInfo.size, normalizedPts, bufferInfo.flags)
            }

            if (isStarted && audioTrackIndex >= 0) {
                writeLocked(audioTrackIndex, byteBuffer, safeInfo)
            } else {
                queuePendingLocked(isVideo = false, byteBuffer = byteBuffer, info = safeInfo)
            }
        }
    }

    /**
     * Track-indexed overload for compatibility.
     */
    fun writeSampleData(trackIndex: Int, byteBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            when (trackIndex) {
                videoTrackIndex -> writeVideoSample(byteBuffer, bufferInfo)
                audioTrackIndex -> writeAudioSample(byteBuffer, bufferInfo)
                else -> if (isStarted && trackIndex >= 0) {
                    writeLocked(trackIndex, byteBuffer, bufferInfo)
                }
            }
        }
    }

    private fun normalizeVideoPtsLocked(rawPtsUs: Long): Long {
        if (firstVideoRawPtsUs < 0L) {
            firstVideoRawPtsUs = rawPtsUs
        }
        val relative = (rawPtsUs - firstVideoRawPtsUs).coerceAtLeast(0L)
        val monotonic = if (lastVideoWrittenPtsUs >= 0L && relative <= lastVideoWrittenPtsUs) {
            lastVideoWrittenPtsUs + 1L
        } else {
            relative
        }
        lastVideoWrittenPtsUs = monotonic
        return monotonic
    }

    private fun normalizeAudioPtsLocked(rawPtsUs: Long): Long {
        if (firstAudioRawPtsUs < 0L) {
            firstAudioRawPtsUs = rawPtsUs
        }
        val relative = (rawPtsUs - firstAudioRawPtsUs).coerceAtLeast(0L)
        val monotonic = if (lastAudioWrittenPtsUs >= 0L && relative <= lastAudioWrittenPtsUs) {
            lastAudioWrittenPtsUs + 1L
        } else {
            relative
        }
        lastAudioWrittenPtsUs = monotonic
        return monotonic
    }

    private fun queuePendingLocked(isVideo: Boolean, byteBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (pendingSamples.size >= MAX_PENDING_SAMPLES) {
            // If pending queue fills up waiting for audio track, force-start with available tracks so we never drop frames
            forceStartAvailableTracksLocked()
            if (isStarted) {
                val trackIdx = if (isVideo) videoTrackIndex else audioTrackIndex
                if (trackIdx >= 0) {
                    writeLocked(trackIdx, byteBuffer, info)
                }
                return
            }
            pendingSamples.removeAt(0)
        }

        val data = ByteArray(info.size)
        val prevPos = byteBuffer.position()
        val prevLimit = byteBuffer.limit()
        byteBuffer.position(info.offset)
        byteBuffer.limit(info.offset + info.size)
        byteBuffer.get(data, 0, info.size)
        byteBuffer.limit(prevLimit)
        byteBuffer.position(prevPos)

        val queuedInfo = MediaCodec.BufferInfo().apply {
            set(0, info.size, info.presentationTimeUs, info.flags)
        }
        pendingSamples.add(QueuedSample(isVideo = isVideo, data = data, bufferInfo = queuedInfo))
    }

    private fun checkStartConditionLocked() {
        val muxer = mediaMuxer ?: return
        if (isStarted || isReleased) return

        val tracksReady = when {
            expectedTracks <= 1 -> videoTrackIndex >= 0
            else -> videoTrackIndex >= 0 && audioTrackIndex >= 0
        }

        if (tracksReady) {
            startMuxerAndFlushLocked(muxer)
        }
    }

    private fun forceStartAvailableTracksLocked() {
        val muxer = mediaMuxer ?: return
        if (isStarted || isReleased) return
        if (videoTrackIndex >= 0 || audioTrackIndex >= 0) {
            startMuxerAndFlushLocked(muxer)
        }
    }

    private fun startMuxerAndFlushLocked(muxer: MediaMuxer) {
        try {
            muxer.start()
            isStarted = true
            Log.d(
                TAG,
                "Real-time MediaMuxer started! (VideoTrack=$videoTrackIndex, AudioTrack=$audioTrackIndex, queued=${pendingSamples.size})"
            )

            // Flush queued initial samples in chronological presentation timestamp order
            pendingSamples.sortBy { it.bufferInfo.presentationTimeUs }
            for (sample in pendingSamples) {
                val trackIndex = if (sample.isVideo) videoTrackIndex else audioTrackIndex
                if (trackIndex >= 0) {
                    val buf = ByteBuffer.wrap(sample.data)
                    muxer.writeSampleData(trackIndex, buf, sample.bufferInfo)
                }
            }
            pendingSamples.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaMuxer: ${e.message}", e)
        }
    }

    private fun writeLocked(trackIndex: Int, byteBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val muxer = mediaMuxer ?: return
        try {
            byteBuffer.position(info.offset)
            byteBuffer.limit(info.offset + info.size)
            muxer.writeSampleData(trackIndex, byteBuffer, info)
        } catch (e: Exception) {
            Log.w(TAG, "writeSampleData error on track $trackIndex (pts=${info.presentationTimeUs}): ${e.message}")
        }
    }

    fun isReady(): Boolean {
        synchronized(lock) {
            return isStarted
        }
    }

    fun stopAndRelease() {
        synchronized(lock) {
            if (isReleased) return
            isReleased = true

            val muxer = mediaMuxer ?: return
            try {
                // If stopped before secondary audio track arrived, flush buffered video frames cleanly
                if (!isStarted && pendingSamples.isNotEmpty() && (videoTrackIndex >= 0 || audioTrackIndex >= 0)) {
                    startMuxerAndFlushLocked(muxer)
                } else {
                    pendingSamples.clear()
                }

                if (isStarted) {
                    muxer.stop()
                    isStarted = false
                    Log.d(TAG, "Real-time MediaMuxer stopped cleanly")
                }
                muxer.release()
                mediaMuxer = null
                Log.d(TAG, "MediaMuxer released -> ${outputFile.name} (${outputFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping/releasing MediaMuxer: ${e.message}", e)
                try {
                    muxer.release()
                } catch (_: Exception) {}
                mediaMuxer = null
                isStarted = false
            }
        }
    }
}
