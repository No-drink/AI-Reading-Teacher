package com.joey.aireadingteacher.capture

/**
 * Small, platform-independent state machine for deciding when a changing screen has settled.
 * Signatures are low-resolution grayscale samples created locally by [FrameProcessor].
 */
class ScreenChangeDetector(
    private val changeThreshold: Float = CHANGE_THRESHOLD,
    private val stabilityDelayMs: Long = STABILITY_DELAY_MS,
    private val pixelDeltaThreshold: Int = PIXEL_DELTA_THRESHOLD,
) {
    private var previousSignature: ByteArray? = null
    private var lastObservationAtMs: Long? = null
    private var lastSignificantChangeAtMs = 0L
    private var waitingForStability = false

    init {
        require(changeThreshold in 0f..1f) { "changeThreshold must be between 0 and 1" }
        require(stabilityDelayMs > 0L) { "stabilityDelayMs must be positive" }
        require(pixelDeltaThreshold in 0..255) { "pixelDeltaThreshold must be between 0 and 255" }
    }

    fun observe(signature: ByteArray, timestampMs: Long): DetectionResult {
        require(signature.isNotEmpty()) { "Screen signature must not be empty" }
        require(timestampMs >= 0L) { "timestampMs must not be negative" }
        lastObservationAtMs?.let { previousTimestamp ->
            require(timestampMs >= previousTimestamp) { "Observations must use monotonic timestamps" }
        }
        lastObservationAtMs = timestampMs

        val previous = previousSignature
        previousSignature = signature.copyOf()

        if (previous == null) {
            waitingForStability = true
            lastSignificantChangeAtMs = timestampMs
            return DetectionResult(
                event = DetectionEvent.SCREEN_CHANGED,
                difference = 1f,
                remainingStabilityMs = stabilityDelayMs,
            )
        }

        require(previous.size == signature.size) { "Screen signature size changed" }
        val difference = changedPixelRatio(previous, signature)
        if (difference >= changeThreshold) {
            waitingForStability = true
            lastSignificantChangeAtMs = timestampMs
            return DetectionResult(
                event = DetectionEvent.SCREEN_CHANGED,
                difference = difference,
                remainingStabilityMs = stabilityDelayMs,
            )
        }

        return stabilityResult(timestampMs, difference)
    }

    /**
     * Advances the stability timer even when MediaProjection emits no more frames after a page
     * settles. Some Android devices only produce frames while pixels are changing.
     */
    fun poll(timestampMs: Long): DetectionResult {
        require(timestampMs >= 0L) { "timestampMs must not be negative" }
        lastObservationAtMs?.let { previousTimestamp ->
            require(timestampMs >= previousTimestamp) { "Polls must use monotonic timestamps" }
        }
        lastObservationAtMs = timestampMs
        return stabilityResult(timestampMs, difference = 0f)
    }

    private fun stabilityResult(timestampMs: Long, difference: Float): DetectionResult {
        if (!waitingForStability) {
            return DetectionResult(
                event = DetectionEvent.UNCHANGED,
                difference = difference,
            )
        }

        val stableForMs = (timestampMs - lastSignificantChangeAtMs).coerceAtLeast(0L)
        val remainingMs = (stabilityDelayMs - stableForMs).coerceAtLeast(0L)
        if (remainingMs == 0L) {
            waitingForStability = false
            return DetectionResult(
                event = DetectionEvent.STABLE_SCREEN,
                difference = difference,
            )
        }

        return DetectionResult(
            event = DetectionEvent.WAITING_FOR_STABILITY,
            difference = difference,
            remainingStabilityMs = remainingMs,
        )
    }

    private fun changedPixelRatio(previous: ByteArray, current: ByteArray): Float {
        var changedPixels = 0
        for (index in previous.indices) {
            val previousValue = previous[index].toInt() and 0xFF
            val currentValue = current[index].toInt() and 0xFF
            if (kotlin.math.abs(previousValue - currentValue) >= pixelDeltaThreshold) {
                changedPixels++
            }
        }
        return changedPixels.toFloat() / previous.size
    }

    companion object {
        const val CAPTURE_INTERVAL_MS = 100L
        const val CHANGE_THRESHOLD = 0.08f
        const val STABILITY_DELAY_MS = 250L
        const val PIXEL_DELTA_THRESHOLD = 18
        const val SAMPLE_WIDTH = 96
        const val SAMPLE_HEIGHT = 54
    }
}

data class DetectionResult(
    val event: DetectionEvent,
    val difference: Float,
    val remainingStabilityMs: Long = 0L,
)

enum class DetectionEvent {
    UNCHANGED,
    SCREEN_CHANGED,
    WAITING_FOR_STABILITY,
    STABLE_SCREEN,
}
