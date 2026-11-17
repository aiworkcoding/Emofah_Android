package com.emofah.agencies

import androidx.exifinterface.media.ExifInterface
import com.emofah.agencies.camera.ImageQualityMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedImageQualityTest {

    @Test
    fun computeScaledDimensionsRespectsMaxBoundAndNeverUpscales() {
        val (w1, h1) = ImageQualityMath.computeScaledDimensions(3200, 1600, 640)
        assertEquals(640, w1)
        assertEquals(320, h1)

        val (w2, h2) = ImageQualityMath.computeScaledDimensions(480, 360, 640)
        assertEquals(480, w2)
        assertEquals(360, h2)
    }

    @Test
    fun exifOrientationMapsToExpectedDegreesAndFlip() {
        assertEquals(90, ImageQualityMath.exifOrientationToDegrees(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(180, ImageQualityMath.exifOrientationToDegrees(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(270, ImageQualityMath.exifOrientationToDegrees(ExifInterface.ORIENTATION_ROTATE_270))
        assertTrue(ImageQualityMath.isHorizontalFlip(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertFalse(ImageQualityMath.isHorizontalFlip(ExifInterface.ORIENTATION_ROTATE_90))
    }

    @Test
    fun lumaGridDetectsDarkBlurryVsBrightSharpCaptures() {
        val darkFlat = IntArray(32 * 32) { 20 }
        val darkAssessment = ImageQualityMath.assessLumaGrid(darkFlat, 32, 32)
        assertTrue(darkAssessment.isTooDark)
        assertTrue(darkAssessment.isTooBlurry)
        assertTrue(darkAssessment.hasWarning)

        val brightChecker = IntArray(32 * 32) { idx ->
            val x = idx % 32
            val y = idx / 32
            if ((x + y) % 2 == 0) 210 else 90
        }
        val sharpAssessment = ImageQualityMath.assessLumaGrid(brightChecker, 32, 32)
        assertFalse(sharpAssessment.isTooDark)
        assertFalse(sharpAssessment.isTooBlurry)
        assertFalse(sharpAssessment.hasWarning)
    }
}
