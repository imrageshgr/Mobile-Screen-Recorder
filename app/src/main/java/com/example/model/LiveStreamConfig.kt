package com.example.model

/**
 * LiveStreamConfig
 * Encapsulates live streaming connection parameters (RTMP Ingest & Stream Key)
 * and the customizable video layout overlay configuration.
 *
 * Supported platforms: YouTube, Twitch, Facebook, Instagram, and Custom RTMP.
 * Watermark brand logo is default enabled permanently (no off toggle), with full text customization.
 */
data class LiveStreamConfig(
    val platform: String = PLATFORM_YOUTUBE,
    val streamUrl: String = getDefaultUrlForPlatform(PLATFORM_YOUTUBE),
    val streamKey: String = "",
    val streamTitle: String = "Mobile Game Live Stream",
    val orientation: String = "HORIZONTAL", // "HORIZONTAL" (16:9 landscape) or "VERTICAL" (9:16 portrait)
    val isLayoutEnabled: Boolean = true,
    val bannerText: String = "GAMER_PRO • LIVE",
    val bannerPosition: String = "BOTTOM_LEFT", // "BOTTOM_LEFT", "TOP_LEFT", "BOTTOM_RIGHT", "TOP_RIGHT", "BOTTOM_CENTER"
    val badgeStyle: String = "LIVE_RED", // "LIVE_RED", "GAMER_GOLD", "NEON_CYAN", "CLEAN_MONO"
    val isCameraPipEnabled: Boolean = true,
    val cameraPipPosition: String = "TOP_RIGHT", // "TOP_RIGHT", "TOP_LEFT", "BOTTOM_RIGHT", "BOTTOM_LEFT"
    val isWatermarkEnabled: Boolean = true, // Default permanently enabled (no toggle to disable)
    val watermarkText: String = "GAMER PRO",
    val watermarkPosition: String = "TOP_RIGHT"
) {
    val isConfigured: Boolean
        get() = streamUrl.isNotBlank() && streamKey.isNotBlank()

    companion object {
        const val PLATFORM_YOUTUBE = "YOUTUBE"
        const val PLATFORM_TWITCH = "TWITCH"
        const val PLATFORM_FACEBOOK = "FACEBOOK"
        const val PLATFORM_INSTAGRAM = "INSTAGRAM"
        const val PLATFORM_CUSTOM = "CUSTOM"

        fun getDefaultUrlForPlatform(platform: String): String {
            return when (platform) {
                PLATFORM_YOUTUBE -> "rtmp://a.rtmp.youtube.com/live2"
                PLATFORM_TWITCH -> "rtmp://live.twitch.tv/app/"
                PLATFORM_FACEBOOK -> "rtmps://live-api-s.facebook.com:443/rtmp/"
                PLATFORM_INSTAGRAM -> "rtmps://live-upload.instagram.com:443/rtmp/"
                else -> "rtmp://"
            }
        }
    }
}
