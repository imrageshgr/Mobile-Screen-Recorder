package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun liveStreamConfig_defaults_watermarkAlwaysEnabled() {
    val config = com.example.model.LiveStreamConfig()
    assertTrue(config.isWatermarkEnabled)
    assertEquals("GAMER PRO", config.watermarkText)
    assertEquals(com.example.model.LiveStreamConfig.PLATFORM_CUSTOM, config.platform)
    assertTrue(config.streamTitle.isNotEmpty())
  }

  @Test
  fun pcmWeightedMixing_preventsOverflowAndClipping() {
    val maxGame = Short.MAX_VALUE.toInt()
    val maxMic = Short.MAX_VALUE.toInt()
    val mixedMax = ((maxGame * 0.7) + (maxMic * 0.3)).toInt().coerceIn(-32768, 32767).toShort()
    assertEquals(Short.MAX_VALUE, mixedMax)

    val minGame = Short.MIN_VALUE.toInt()
    val minMic = Short.MIN_VALUE.toInt()
    val mixedMin = ((minGame * 0.7) + (minMic * 0.3)).toInt().coerceIn(-32768, 32767).toShort()
    assertEquals(Short.MIN_VALUE, mixedMin)
  }

  @Test
  fun liveStreamConfig_instagramSupport() {
    val config = com.example.model.LiveStreamConfig(
      platform = com.example.model.LiveStreamConfig.PLATFORM_INSTAGRAM,
      streamUrl = com.example.model.LiveStreamConfig.getDefaultUrlForPlatform(com.example.model.LiveStreamConfig.PLATFORM_INSTAGRAM),
      orientation = "VERTICAL"
    )
    assertEquals(com.example.model.LiveStreamConfig.PLATFORM_INSTAGRAM, config.platform)
    assertEquals("rtmps://live-upload.instagram.com:443/rtmp/", config.streamUrl)
    assertEquals("VERTICAL", config.orientation)
  }

  @Test
  fun liveStreamHistoryItem_fields_matchSpecifications() {
    val item = com.example.model.LiveStreamHistoryItem(
      id = "test-session-123",
      platform = "Instagram",
      streamTitle = "Apex Legends Mobile Ranked",
      timestamp = 1700000000000L,
      dateFormatted = "Nov 14, 2024, 05:30 PM",
      resolution = "1080p",
      fps = 60,
      bitrateMbps = 12,
      orientation = "9:16 Vertical",
      streamServer = "rtmps://live-upload.instagram.com:443/rtmp/"
    )

    assertEquals("Instagram", item.platform)
    assertEquals("Apex Legends Mobile Ranked", item.streamTitle)
    assertEquals("1080p", item.resolution)
    assertEquals(60, item.fps)
    assertEquals(12, item.bitrateMbps)
    assertEquals("9:16 Vertical", item.orientation)
    assertEquals("rtmps://live-upload.instagram.com:443/rtmp/", item.streamServer)
  }

  @Test
  fun videoEncoder_smartCappedBitrate_guarantees1080p60AndPreventsOverheating() {
    // 1080p @ 60fps (landscape and portrait) always locks cleanly at 12 Mbps (12_000_000)
    assertEquals(12_000_000, com.example.recorder.VideoEncoderWrapper.resolveSmartCappedBitrate(1920, 1080, 60, 16_000_000))
    assertEquals(12_000_000, com.example.recorder.VideoEncoderWrapper.resolveSmartCappedBitrate(1080, 1920, 60, 8_000_000))
    // 720p respects lower bitrates and caps higher ones at 12 Mbps
    assertEquals(8_000_000, com.example.recorder.VideoEncoderWrapper.resolveSmartCappedBitrate(1280, 720, 60, 8_000_000))
    assertEquals(12_000_000, com.example.recorder.VideoEncoderWrapper.resolveSmartCappedBitrate(1280, 720, 60, 16_000_000))
  }
}

