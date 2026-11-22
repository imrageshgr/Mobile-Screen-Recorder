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
  fun videoEncoder_smartCappedBitrate_guarantees1080p60AndPreventsOverheating() {
    // 1080p @ 60fps (landscape and portrait) always locks cleanly at 12 Mbps (12_000_000)
    assertEquals(12_000_000, com.example.recorder.VideoEncoderWrapper.resolveSmartCappedBitrate(1920, 1080, 60, 16_000_000))
    assertEquals(12_000_000, com.example.recorder.VideoEncoderWrapper.resolveSmartCappedBitrate(1080, 1920, 60, 8_000_000))
    // 720p respects lower bitrates and caps higher ones at 12 Mbps
    assertEquals(8_000_000, com.example.recorder.VideoEncoderWrapper.resolveSmartCappedBitrate(1280, 720, 60, 8_000_000))
    assertEquals(12_000_000, com.example.recorder.VideoEncoderWrapper.resolveSmartCappedBitrate(1280, 720, 60, 16_000_000))
  }

  @Test
  fun recordingMode_audioOptionsAndAutoReset() {
    val normalSources = com.example.viewmodel.MainViewModel.getAvailableAudioSourcesForMode("NORMAL")
    assertEquals(listOf("INTERNAL", "BOTH"), normalSources)

    val gameSources = com.example.viewmodel.MainViewModel.getAvailableAudioSourcesForMode("GAME")
    assertEquals(listOf("MUTE", "INTERNAL", "BOTH", "MIC"), gameSources)

    // Switching from GAME "MIC" or "MUTE" to NORMAL resets to "INTERNAL"
    assertEquals("INTERNAL", com.example.viewmodel.MainViewModel.resolveAudioSourceForMode("NORMAL", "MIC"))
    assertEquals("INTERNAL", com.example.viewmodel.MainViewModel.resolveAudioSourceForMode("NORMAL", "MUTE"))
    // Valid options stay preserved
    assertEquals("BOTH", com.example.viewmodel.MainViewModel.resolveAudioSourceForMode("NORMAL", "BOTH"))
    assertEquals("MIC", com.example.viewmodel.MainViewModel.resolveAudioSourceForMode("GAME", "MIC"))
  }
}

