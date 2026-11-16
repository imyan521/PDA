package com.cartographer.demo

/** Receive-time estimates for batched IMUs. No hardware timestamp is available. */
class Kld42ImuBatchClock {
    private var lastTimestampNs = 0L
    var batchedSampleCount = 0L
        private set
    var compressedBatchCount = 0L
        private set

    fun timestamps(count: Int, receivedAtNs: Long): LongArray {
        if (count == 0) return LongArray(0)
        require(count > 0)
        if (lastTimestampNs != 0L && receivedAtNs <= lastTimestampNs) {
            throw IllegalStateException("板载 IMU 接收时间未递增，请重新连接设备")
        }
        val step = when {
            lastTimestampNs == 0L -> NOMINAL_PERIOD_NS
            receivedAtNs - lastTimestampNs > REANCHOR_GAP_NS ->
                minOf(NOMINAL_PERIOD_NS, (receivedAtNs - lastTimestampNs) / count)
            else -> minOf(MAX_PERIOD_NS, (receivedAtNs - lastTimestampNs) / count)
        }
        require(step > 0L) { "板载 IMU 批量时间跨度不足" }
        if (count > 1) {
            batchedSampleCount += count
            if (step < MIN_PLAUSIBLE_PERIOD_NS) compressedBatchCount++
        }
        val timestamps = LongArray(count) { receivedAtNs - (count - 1L - it) * step }
        lastTimestampNs = receivedAtNs
        return timestamps
    }

    companion object {
        // Design: MCU reports at approximately 20 ms, despite chip ODR = 100 Hz.
        private const val NOMINAL_PERIOD_NS = 20_000_000L
        private const val MAX_PERIOD_NS = 40_000_000L
        private const val MIN_PLAUSIBLE_PERIOD_NS = 5_000_000L
        private const val REANCHOR_GAP_NS = 500_000_000L
    }
}
