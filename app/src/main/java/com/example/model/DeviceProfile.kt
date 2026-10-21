package com.example.model

data class DeviceProfile(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrate: Int,
    val audioBitrate: Int = 128_000,
    val audioSampleRate: Int = 44_100,
    val encoderName: String = "Hardware H.264",
    val analysisVersion: Int = CURRENT_VERSION,
    val analyzedTimestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val CURRENT_VERSION = 1

        val DEFAULT_SAFE_FALLBACK = DeviceProfile(
            width = 720,
            height = 1280,
            frameRate = 30,
            bitrate = 5_000_000,
            encoderName = "Fallback Safe Profile"
        )
    }

    val isOptimized: Boolean
        get() = width > 0 && height > 0 && frameRate > 0 && bitrate > 0

    fun getSaferProfile(): DeviceProfile {
        return when {
            width > 1080 -> copy(width = 1080, height = (height * 1080 / width / 2) * 2, frameRate = 30, bitrate = 7_000_000)
            frameRate > 30 -> copy(frameRate = 30, bitrate = (bitrate * 0.7).toInt())
            width > 720 -> copy(width = 720, height = (height * 720 / width / 2) * 2, frameRate = 30, bitrate = 4_500_000)
            else -> DEFAULT_SAFE_FALLBACK
        }
    }
}
