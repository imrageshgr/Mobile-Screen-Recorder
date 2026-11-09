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
    assertEquals(com.example.model.LiveStreamConfig.PLATFORM_YOUTUBE, config.platform)
    assertTrue(config.streamTitle.isNotEmpty())
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
}

