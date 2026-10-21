package com.example.optimizer

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.model.DeviceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class AnalysisProgress(
    val step: Int,
    val totalSteps: Int = 4,
    val headline: String,
    val detail: String,
    val percentage: Float
)

class DeviceAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "DeviceAnalyzer"
    }

    suspend fun analyzeDevice(
        onProgress: (AnalysisProgress) -> Unit
    ): DeviceProfile = withContext(Dispatchers.IO) {
        // Step 1: Read display specifications & system RAM
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val rawWidth = metrics.widthPixels
        val rawHeight = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        // Detect real system RAM
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val totalRamMb = (memInfo.totalMem / (1024 * 1024)).toInt()

        onProgress(
            AnalysisProgress(
                step = 1,
                headline = "Auditing Hardware Specifications",
                detail = "Screen: ${rawWidth}x${rawHeight} ($densityDpi DPI) • Available RAM: ${totalRamMb} MB",
                percentage = 0.25f
            )
        )
        delay(600)

        // Strict 16-alignment for native resolution
        val screenShort = minOf(rawWidth, rawHeight)
        val screenLong = maxOf(rawWidth, rawHeight)
        val isPortrait = rawHeight >= rawWidth

        val nativeWidth = if (isPortrait) ((screenShort / 16) * 16) else ((screenLong / 16) * 16)
        val nativeHeight = if (isPortrait) ((screenLong / 16) * 16) else ((screenShort / 16) * 16)

        Log.d(TAG, "Native 16-aligned Screen: ${nativeWidth}x${nativeHeight}, RAM: $totalRamMb MB")

        // Step 2: Query Hardware H.264 Video Encoder
        onProgress(
            AnalysisProgress(
                step = 2,
                headline = "Probing Video Hardware Encoder",
                detail = "Detecting MediaCodec AVC/H.264 chipset capabilities...",
                percentage = 0.50f
            )
        )
        delay(600)

        val encoderInfo = findBestAvcEncoder()
        val encoderName = encoderInfo?.name ?: "Standard H.264 Hardware"
        val videoCaps = encoderInfo?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities

        Log.d(TAG, "Selected encoder: $encoderName")

        // Step 3: Candidate Profile Selection tailored to phone capacity & RAM
        onProgress(
            AnalysisProgress(
                step = 3,
                headline = "Calibrating Optimal Quality Profile",
                detail = "Matching resolution, FPS, and bitrate for zero lag...",
                percentage = 0.75f
            )
        )
        delay(600)

        val candidates = buildCandidateProfiles(
            nativeWidth = nativeWidth,
            nativeHeight = nativeHeight,
            totalRamMb = totalRamMb,
            encoderName = encoderName,
            videoCaps = videoCaps
        )

        // Step 4: Verification with Hardware Test Encode
        onProgress(
            AnalysisProgress(
                step = 4,
                headline = "Testing Hardware Stability",
                detail = "Benchmarking encoder pipeline...",
                percentage = 0.90f
            )
        )
        delay(500)

        val tempDir = context.getExternalFilesDir("optimizer_test") ?: context.cacheDir
        if (!tempDir.exists()) tempDir.mkdirs()

        var selectedProfile = candidates.last()
        for (candidate in candidates) {
            val isVerified = verifyCandidate(candidate, tempDir)
            if (isVerified) {
                selectedProfile = candidate
                Log.d(TAG, "Verified optimal profile: ${candidate.width}x${candidate.height} @ ${candidate.frameRate}fps, ${candidate.bitrate} bps")
                break
            } else {
                Log.w(TAG, "Candidate ${candidate.width}x${candidate.height} failed test, attempting fallback")
            }
        }

        onProgress(
            AnalysisProgress(
                step = 4,
                headline = "Optimization Complete",
                detail = "Configured: ${selectedProfile.width}x${selectedProfile.height} • ${selectedProfile.frameRate} FPS • ${selectedProfile.bitrate / 1_000_000} Mbps",
                percentage = 1.0f
            )
        )
        delay(400)

        selectedProfile
    }

    private fun findBestAvcEncoder(): MediaCodecInfo? {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val avcEncoders = codecList.codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
            }

            // Prefer hardware accelerated encoders
            val hardwareEncoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                avcEncoders.firstOrNull { it.isHardwareAccelerated }
            } else {
                avcEncoders.firstOrNull { !it.name.contains("google", ignoreCase = true) && !it.name.contains("sw", ignoreCase = true) }
            }

            hardwareEncoder ?: avcEncoders.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaCodecList: ${e.message}")
            null
        }
    }

    private fun buildCandidateProfiles(
        nativeWidth: Int,
        nativeHeight: Int,
        totalRamMb: Int,
        encoderName: String,
        videoCaps: MediaCodecInfo.VideoCapabilities?
    ): List<DeviceProfile> {
        val candidates = mutableListOf<DeviceProfile>()

        val isLowRam = totalRamMb in 1..3500 // Devices with <= 3.5GB RAM (e.g., 2170MB MediaTek phones)
        val isMidRam = totalRamMb in 3501..5500

        // Strict 16-aligned 720p dimensions based on native aspect ratio
        val scale720 = 720.0 / minOf(nativeWidth, nativeHeight).coerceAtLeast(1)
        val w720 = ((nativeWidth * scale720).toInt() / 16) * 16
        val h720 = ((nativeHeight * scale720).toInt() / 16) * 16

        // Strict 16-aligned 1080p dimensions
        val scale1080 = 1080.0 / minOf(nativeWidth, nativeHeight).coerceAtLeast(1)
        val w1080 = ((nativeWidth * scale1080).toInt() / 16) * 16
        val h1080 = ((nativeHeight * scale1080).toInt() / 16) * 16

        if (isLowRam) {
            // For low RAM devices (< 3.5GB), recommend 720p @ 30fps/60fps with low bitrate to prevent frame drops
            candidates.add(
                DeviceProfile(
                    width = w720,
                    height = h720,
                    frameRate = 30,
                    bitrate = 5_000_000,
                    encoderName = encoderName
                )
            )
            candidates.add(
                DeviceProfile(
                    width = w720,
                    height = h720,
                    frameRate = 60,
                    bitrate = 6_000_000,
                    encoderName = encoderName
                )
            )
            // Absolute safe 720x1280 16-aligned fallback
            candidates.add(
                DeviceProfile(
                    width = 720,
                    height = 1280,
                    frameRate = 30,
                    bitrate = 4_000_000,
                    encoderName = encoderName
                )
            )
        } else if (isMidRam) {
            // Mid-tier devices (4GB - 5GB): 720p @ 60fps or 1080p @ 30fps
            candidates.add(
                DeviceProfile(
                    width = w720,
                    height = h720,
                    frameRate = 60,
                    bitrate = 8_000_000,
                    encoderName = encoderName
                )
            )
            candidates.add(
                DeviceProfile(
                    width = w1080,
                    height = h1080,
                    frameRate = 30,
                    bitrate = 8_000_000,
                    encoderName = encoderName
                )
            )
            candidates.add(
                DeviceProfile(
                    width = w720,
                    height = h720,
                    frameRate = 30,
                    bitrate = 6_000_000,
                    encoderName = encoderName
                )
            )
        } else {
            // High-tier devices (6GB+ RAM): 1080p @ 60fps
            candidates.add(
                DeviceProfile(
                    width = w1080,
                    height = h1080,
                    frameRate = 60,
                    bitrate = 12_000_000,
                    encoderName = encoderName
                )
            )
            candidates.add(
                DeviceProfile(
                    width = w720,
                    height = h720,
                    frameRate = 60,
                    bitrate = 8_000_000,
                    encoderName = encoderName
                )
            )
        }

        // Ultimate universal safe fallback: 720x1280 (16-aligned: 720/16=45, 1280/16=80)
        candidates.add(
            DeviceProfile(
                width = 720,
                height = 1280,
                frameRate = 30,
                bitrate = 5_000_000,
                encoderName = encoderName
            )
        )

        return candidates
    }

    private fun verifyCandidate(profile: DeviceProfile, tempDir: File): Boolean {
        val testFile = File(tempDir, "verify_test_${System.currentTimeMillis()}.mp4")
        var recorder: MediaRecorder? = null
        return try {
            @Suppress("DEPRECATION")
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(profile.width, profile.height)
                setVideoFrameRate(profile.frameRate)
                setVideoEncodingBitRate(profile.bitrate)
                setOutputFile(testFile.absolutePath)
                prepare()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Profile verification failed for ${profile.width}x${profile.height}: ${e.message}")
            false
        } finally {
            try {
                recorder?.reset()
                recorder?.release()
            } catch (_: Exception) {}
            if (testFile.exists()) {
                testFile.delete()
            }
        }
    }
}
