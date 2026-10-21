package com.example.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

class MuxerCoordinator(
    private val outputFile: File,
    private val expectedTrackCount: Int
) {
    companion object {
        private const val TAG = "MuxerCoordinator"
    }

    private data class QueuedSample(
        val trackIndex: Int,
        val data: ByteArray,
        val bufferInfo: MediaCodec.BufferInfo
    )

    private var mediaMuxer: MediaMuxer? = null
    private var isStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val pendingSamples = mutableListOf<QueuedSample>()
    private val lock = Any()

    init {
        try {
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            Log.d(TAG, "MediaMuxer initialized for ${outputFile.name} (expecting $expectedTrackCount tracks)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaMuxer: ${e.message}", e)
        }
    }

    fun setOrientation(degrees: Int) {
        synchronized(lock) {
            val muxer = mediaMuxer ?: return
            if (!isStarted) {
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
            if (videoTrackIndex >= 0) return videoTrackIndex

            try {
                videoTrackIndex = muxer.addTrack(format)
                Log.d(TAG, "Video track registered at index $videoTrackIndex")
                checkStartCondition()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add video track: ${e.message}", e)
            }
            return videoTrackIndex
        }
    }

    fun setAudioFormat(format: MediaFormat): Int {
        synchronized(lock) {
            val muxer = mediaMuxer ?: return -1
            if (audioTrackIndex >= 0) return audioTrackIndex

            try {
                audioTrackIndex = muxer.addTrack(format)
                Log.d(TAG, "Audio track registered at index $audioTrackIndex")
                checkStartCondition()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add audio track: ${e.message}", e)
            }
            return audioTrackIndex
        }
    }

    private fun checkStartCondition() {
        val muxer = mediaMuxer ?: return
        if (isStarted) return

        val tracksReady = when (expectedTrackCount) {
            1 -> videoTrackIndex >= 0
            else -> videoTrackIndex >= 0 && audioTrackIndex >= 0
        }

        if (tracksReady) {
            try {
                muxer.start()
                isStarted = true
                Log.d(TAG, "MediaMuxer started successfully! Expected tracks: $expectedTrackCount (Video: $videoTrackIndex, Audio: $audioTrackIndex)")

                // Flush all queued samples in arrival order
                for (sample in pendingSamples) {
                    val byteBuffer = ByteBuffer.wrap(sample.data)
                    muxer.writeSampleData(sample.trackIndex, byteBuffer, sample.bufferInfo)
                }
                pendingSamples.clear()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaMuxer: ${e.message}", e)
            }
        }
    }

    fun writeSampleData(trackIndex: Int, byteBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val muxer = mediaMuxer ?: return
            if (trackIndex < 0 || bufferInfo.size <= 0) return

            if (isStarted) {
                try {
                    muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
                } catch (e: Exception) {
                    Log.w(TAG, "writeSampleData failed for track $trackIndex: ${e.message}")
                }
            } else {
                // Buffer sample in memory until all tracks are added and muxer starts
                if (pendingSamples.size < 600) {
                    val data = ByteArray(bufferInfo.size)
                    val originalPos = byteBuffer.position()
                    byteBuffer.position(bufferInfo.offset)
                    byteBuffer.get(data, 0, bufferInfo.size)
                    byteBuffer.position(originalPos)

                    val infoCopy = MediaCodec.BufferInfo().apply {
                        set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }
                    pendingSamples.add(QueuedSample(trackIndex, data, infoCopy))
                }
            }
        }
    }

    fun isReady(): Boolean {
        synchronized(lock) {
            return isStarted
        }
    }

    fun stopAndRelease() {
        synchronized(lock) {
            val muxer = mediaMuxer ?: return
            try {
                if (isStarted) {
                    muxer.stop()
                    isStarted = false
                    Log.d(TAG, "MediaMuxer stopped successfully")
                }
                muxer.release()
                mediaMuxer = null
                Log.d(TAG, "MediaMuxer released and output file finalized")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping/releasing MediaMuxer: ${e.message}", e)
                try {
                    muxer.release()
                } catch (_: Exception) {}
                mediaMuxer = null
            }
        }
    }
}
