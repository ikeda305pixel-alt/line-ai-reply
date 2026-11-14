package com.antigravity.lineaireply.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ScreenshotDetectionHelper naming detection rules.
 */
class ScreenshotDetectionTest {

    @Test
    fun testIsScreenshotNaming() {
        assertTrue(ScreenshotDetectionHelper.isScreenshot("Screenshot_20261114-120000.png", null))
        assertTrue(ScreenshotDetectionHelper.isScreenshot("screenshot_1.jpg", null))
        assertTrue(ScreenshotDetectionHelper.isScreenshot("スクリーンショット_2026.png", null))
        assertTrue(ScreenshotDetectionHelper.isScreenshot("image1.png", "Pictures/Screenshots/"))
        assertTrue(ScreenshotDetectionHelper.isScreenshot("photo.jpg", "DCIM/スクリーンショット/"))

        assertFalse(ScreenshotDetectionHelper.isScreenshot("camera_photo.jpg", "DCIM/Camera/"))
        assertFalse(ScreenshotDetectionHelper.isScreenshot("downloaded_meme.png", "Download/"))
    }

    @Test
    fun testCacheInvalidate() {
        ScreenshotDetectionHelper.invalidateCache()
        val cached = ScreenshotDetectionHelper.getCachedScreenshot(maxAgeSeconds = 20)
        org.junit.Assert.assertNull(cached)
    }
}
