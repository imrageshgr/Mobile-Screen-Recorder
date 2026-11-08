package com.example

import com.example.model.DeviceProfile
import com.example.model.RecordingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleAppLogicTest {

    @Test
    fun testDeviceProfileCalculations() {
        val safeProfile = DeviceProfile.DEFAULT_SAFE_FALLBACK
        assertEquals(720, safeProfile.width)
        assertEquals(1280, safeProfile.height)
        assertEquals(30, safeProfile.frameRate)
        assertTrue(safeProfile.bitrate >= 5_000_000)

        val customProfile = DeviceProfile(
            width = 1080,
            height = 1920,
            frameRate = 60,
            bitrate = 12_000_000,
            encoderName = "High Performance Tier"
        )
        assertEquals(1080, customProfile.width)
        assertEquals(1920, customProfile.height)
        assertEquals(60, customProfile.frameRate)
        assertEquals(12_000_000, customProfile.bitrate)
        assertTrue(customProfile.isOptimized)
    }

    @Test
    fun testDeviceProfileDefaults() {
        val defaultProfile = DeviceProfile.DEFAULT_SAFE_FALLBACK
        assertEquals(720, defaultProfile.width)
        assertEquals(1280, defaultProfile.height)
        assertEquals(30, defaultProfile.frameRate)
        assertTrue(defaultProfile.bitrate > 0)
    }
}
