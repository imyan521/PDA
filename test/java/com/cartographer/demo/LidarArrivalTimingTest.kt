package com.cartographer.demo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LidarArrivalTimingTest {
    @Test fun scanUsesUsbArrivalRatherThanTimeSpentParsing() {
        val timestamps = mutableListOf<Long>()
        val scans = mutableListOf<FloatArray>()
        // Construct before acquisition, then process reads 500 ms late.
        var processingTime = 1_000_000_000L
        val parser = LidarParser(
            scanSink = { time, _, ranges, _ -> timestamps.add(time); scans.add(ranges) },
            clock = { processingTime },
            scanLog = {}
        )
        parser.enableLidar()
        processingTime = 1_800_000_000L
        val read = revolution()
        for (time in listOf(1_100_000_000L, 1_200_000_000L, 1_300_000_000L)) {
            parser.onDataReceived(read, read.size, time)
        }
        assertEquals(listOf(1_300_000_000L), timestamps)
        assertEquals(360, scans.single().size)
        assertArrayEquals(FloatArray(360) { 3f }, scans.single(), 0.0001f)
    }

    @Test fun convenienceEntrySamplesClockOncePerUsbRead() {
        var clockCalls = 0
        val parser = LidarParser(
            scanSink = { _, _, _, _ -> },
            clock = { 1_000_000_000L + ++clockCalls * 100_000_000L },
            scanLog = {}
        )
        parser.enableLidar()
        val read = revolution()
        repeat(3) { parser.onDataReceived(read, read.size) }
        // One constructor anchor, then one sample per read, regardless of packet count.
        assertEquals(4, clockCalls)
    }

    @Test fun slowSlamConsumerCannotRetimestampLaterPackets() {
        fun run(delayNs: Long): List<Long> {
            var now = 1_000_000_000L
            val timestamps = mutableListOf<Long>()
            val parser = LidarParser(
                scanSink = { time, _, _, _ -> timestamps.add(time); now += delayNs },
                clock = { now },
                scanLog = {}
            )
            parser.enableLidar()
            val read = revolution()
            repeat(8) { index ->
                val arrival = 1_100_000_000L + index * 100_000_000L
                now = arrival
                parser.onDataReceived(read, read.size, arrival)
            }
            return timestamps
        }
        val normal = run(0L)
        assertEquals(6, normal.size)
        assertEquals(normal, run(250_000_000L))
    }

    private fun revolution(): ByteArray = (0 until 4).fold(byteArrayOf()) { bytes, sector ->
        bytes + packet(sector * 90)
    }

    private fun packet(startDegrees: Int): ByteArray {
        val count = 90
        val data = ByteArray(10 + count * 3)
        data[0] = 0xAA.toByte()
        data[1] = 0x55
        data[3] = count.toByte()
        for ((offset, angle) in listOf(4 to startDegrees, 6 to startDegrees + count - 1)) {
            val raw = (angle * 64 shl 1) or 1
            data[offset] = raw.toByte()
            data[offset + 1] = (raw ushr 8).toByte()
        }
        for (i in 0 until count) {
            data[10 + i * 3 + 1] = (3000 shl 2).toByte()
            data[10 + i * 3 + 2] = (3000 ushr 6).toByte()
        }
        return data
    }
}
