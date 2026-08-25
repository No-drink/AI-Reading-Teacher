package com.joey.aireadingteacher.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenChangeDetectorTest {
    @Test
    fun `production capture policy refreshes shortly after a page settles`() {
        assertEquals(100L, ScreenChangeDetector.CAPTURE_INTERVAL_MS)
        assertEquals(250L, ScreenChangeDetector.STABILITY_DELAY_MS)
    }

    @Test
    fun `first frame becomes stable only after the configured delay`() {
        val detector = detector()
        val frame = signature()

        assertEquals(DetectionEvent.SCREEN_CHANGED, detector.observe(frame, 0).event)
        assertEquals(DetectionEvent.WAITING_FOR_STABILITY, detector.poll(249).event)
        assertEquals(DetectionEvent.STABLE_SCREEN, detector.poll(250).event)
        assertEquals(DetectionEvent.UNCHANGED, detector.observe(frame, 251).event)
    }

    @Test
    fun `significant movement restarts the stability timer`() {
        val detector = detector()
        val firstPage = signature()
        val turningPage = signature(changedPixels = 4, value = 100)
        val secondPage = signature(changedPixels = 8, value = 200)

        detector.observe(firstPage, 0)
        assertEquals(DetectionEvent.STABLE_SCREEN, detector.observe(firstPage, 250).event)
        assertEquals(DetectionEvent.SCREEN_CHANGED, detector.observe(turningPage, 1_000).event)
        assertEquals(DetectionEvent.SCREEN_CHANGED, detector.observe(secondPage, 1_500).event)
        assertEquals(
            DetectionEvent.WAITING_FOR_STABILITY,
            detector.observe(secondPage, 1_749).event,
        )
        assertEquals(DetectionEvent.STABLE_SCREEN, detector.observe(secondPage, 1_750).event)
    }

    @Test
    fun `minor pixel differences do not start a new change sequence`() {
        val detector = detector()
        val stablePage = signature()
        val smallDifference = signature(changedPixels = 2, value = 100)

        detector.observe(stablePage, 0)
        detector.observe(stablePage, 250)

        val result = detector.observe(smallDifference, 1_000)
        assertEquals(DetectionEvent.UNCHANGED, result.event)
        assertEquals(0.2f, result.difference, 0.0001f)
    }

    @Test
    fun `large change after stability produces one new stable event`() {
        val detector = detector()
        val firstPage = signature()
        val secondPage = signature(changedPixels = 4, value = 100)

        detector.observe(firstPage, 0)
        detector.observe(firstPage, 250)
        assertEquals(DetectionEvent.SCREEN_CHANGED, detector.observe(secondPage, 1_000).event)
        assertEquals(DetectionEvent.STABLE_SCREEN, detector.observe(secondPage, 1_250).event)
        assertEquals(DetectionEvent.UNCHANGED, detector.observe(secondPage, 2_400).event)
    }

    private fun detector() = ScreenChangeDetector(
        changeThreshold = 0.3f,
        stabilityDelayMs = 250L,
        pixelDeltaThreshold = 18,
    )

    private fun signature(changedPixels: Int = 0, value: Int = 0): ByteArray =
        ByteArray(10) { index -> if (index < changedPixels) value.toByte() else 0 }
}
