package com.example.model

/**
 * LiveStreamConfig
 * Encapsulates live streaming connection parameters (RTMP Ingest & Stream Key),
 * 5-second stream broadcast delay, in-game teammate voice chat & audio channels,
 * and the customizable video layout overlay configuration.
 *
 * Supports Kick, Twitch, YouTube, and any custom RTMP/RTMPS ingest server.
 * Watermark brand logo is default enabled permanently (no off toggle), with full text customization.
 */
data class LiveStreamConfig(
    val platform: String = PLATFORM_CUSTOM,
    val streamUrl: String = DEFAULT_KICK_RTMP,
    val streamKey: String = "",
    val streamTitle: String = "Mobile Game Live Stream",
    val streamDelaySeconds: Int = 5, // 5s default broadcast delay
    val audioMode: String = AUDIO_MODE_GAME_AND_MIC, // Audio channel routing
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
        const val PLATFORM_KICK = "KICK"
        const val PLATFORM_CUSTOM = "CUSTOM"

        const val DEFAULT_KICK_RTMP = "rtmps://fa7237730644.global-contribute.live-video.net/app/"
        const val DEFAULT_YOUTUBE_RTMP = "rtmp://a.rtmp.youtube.com/live2"
        const val DEFAULT_TWITCH_RTMP = "rtmp://live.twitch.tv/app/"
        const val DEFAULT_INSTAGRAM_RTMP = "rtmps://live-upload.instagram.com:443/rtmp/"
        const val DEFAULT_FACEBOOK_RTMP = "rtmps://live-api-s.facebook.com:443/rtmp/"

        // Live Audio Routing Modes
        const val AUDIO_MODE_INTERNAL_ONLY = "INTERNAL_ONLY" // Device game sound only (mute resistant)
        const val AUDIO_MODE_GAME_AND_VOICE_CHAT = "GAME_AND_VOICE_CHAT" // Internal game + Teammates' in-game voice chat
        const val AUDIO_MODE_GAME_AND_MIC = "GAME_AND_MIC" // Internal game + External mic commentary
        const val AUDIO_MODE_STUDIO_MIX = "STUDIO_MIX" // Game + Teammates + Mic commentary

        fun getDefaultUrlForPlatform(platform: String): String {
            return when (platform.uppercase()) {
                PLATFORM_KICK -> DEFAULT_KICK_RTMP
                PLATFORM_YOUTUBE -> DEFAULT_YOUTUBE_RTMP
                PLATFORM_TWITCH -> DEFAULT_TWITCH_RTMP
                PLATFORM_FACEBOOK -> DEFAULT_FACEBOOK_RTMP
                PLATFORM_INSTAGRAM -> DEFAULT_INSTAGRAM_RTMP
                else -> DEFAULT_KICK_RTMP
            }
        }
    }
}

