package com.cartographer.demo

import org.junit.Assert.*
import org.junit.Test

class Kld42ImuBatchClockTest {
    @Test fun recoversSpacingInsideFirstBatchInsteadOfCollapsingSamples() {
        val clock = Kld42ImuBatchClock()
        assertArrayEquals(longArrayOf(960_000_000, 980_000_000, 1_000_000_000), clock.timestamps(3, 1_000_000_000))
    }

    @Test fun preservesTotalTurnTimeWhenThreeSamplesArriveTogether() {
        val clock = Kld42ImuBatchClock()
        var previous = clock.timestamps(1, 1_000_000_000).single()
        var angle = 0.0
        repeat(50) { batch ->
            for (timestamp in clock.timestamps(3, 1_060_000_000L + batch * 60_000_000L)) {
                angle += 30.0 * (timestamp - previous) / 1e9
                previous = timestamp
            }
        }
        assertEquals(90.0, angle, 1e-8)
    }

    @Test fun retainsAngularAccelerationInsideBatchInsteadOfReplacingItWithLastRate() {
        val clock = Kld42ImuBatchClock()
        val anchor = clock.timestamps(1, 1_000_000_000).single()
        val timestamps = clock.timestamps(3, 1_060_000_000)
        val rates = doubleArrayOf(30.0, 60.0, 90.0)
        fun integrate(times: LongArray): Double {
            var previous = anchor
            var previousRate = 0.0
            var angle = 0.0
            for (i in times.indices) {
                angle += previousRate * (times[i] - previous) / 1e9
                previousRate = rates[i]
                previous = times[i]
            }
            return angle
        }
        assertEquals(1.8, integrate(timestamps), 1e-8)
        // Previously the JNI monotonic guard spread three equal receive times
        // by only 100 ns. Nearly all intra-batch turn acceleration disappears.
        val collapsed = longArrayOf(1_060_000_000, 1_060_000_100, 1_060_000_200)
        assertTrue(integrate(collapsed) < .001)
    }

    @Test fun doesNotFillLongMissingIntervalsWithInventedSamples() {
        val clock = Kld42ImuBatchClock()
        clock.timestamps(1, 1_000_000_000)
        assertArrayEquals(longArrayOf(1_960_000_000, 1_980_000_000, 2_000_000_000), clock.timestamps(3, 2_000_000_000))
    }

    @Test fun reportsCompressedArrivalSpanWithoutClaimingHardwareSynchronization() {
        val clock = Kld42ImuBatchClock()
        clock.timestamps(1, 1_000_000_000)
        val times = clock.timestamps(3, 1_003_000_000)
        assertTrue(times.asList().zipWithNext().all { it.second > it.first })
        assertEquals(1_003_000_000L, times.last())
        assertEquals(1L, clock.compressedBatchCount)
    }
}
