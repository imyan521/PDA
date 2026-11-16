package com.cartographer.demo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Kld42FrameDecoderTest {
    // Design section 5.3: enable onboard IMU acknowledgement.
    private val ack = hex("AA AA AA 96 69 00 05 09 49 90 03 D6")

    @Test fun acceptsEveryPossibleUsbSplit() {
        for (split in 1 until ack.size) {
            val decoder = Kld42FrameDecoder()
            val payloads = mutableListOf<ByteArray>()
            decoder.feed(ack.copyOfRange(0, split), split, payloads::add)
            decoder.feed(ack.copyOfRange(split, ack.size), ack.size - split, payloads::add)
            assertEquals("split=$split", 1, payloads.size)
            assertArrayEquals(ack.copyOfRange(7, ack.size), payloads.single())
        }
    }

    @Test fun acceptsOneByteReads() {
        val decoder = Kld42FrameDecoder()
        val payloads = mutableListOf<ByteArray>()
        ack.forEach { decoder.feed(byteArrayOf(it), 1, payloads::add) }
        assertEquals(1, payloads.size)
    }

    @Test fun acceptsDocumentedImuPacketWithPaddingAndNextFrame() {
        val frame = hex("AA AA AA 96 69 00 28 09 4A 90 " +
            "CD 8C 72 3E 67 E6 C8 3D CD D0 29 41 FF E4 3F C2 " +
            "FF 2F F2 BF E8 A3 8C C4 00 80 40 3F 00 00 9C BE 4D F6 99 BE CE")
        val stream = frame + ByteArray(17) + ack
        val decoder = Kld42FrameDecoder()
        val payloads = mutableListOf<ByteArray>()
        decoder.feed(stream, stream.size, payloads::add)
        assertEquals(2, payloads.size)
        assertArrayEquals(frame.copyOfRange(7, frame.size), payloads[0])
        assertEquals(0L, decoder.checksumErrorCount)
    }

    @Test fun rejectsCorruptionAndRecoversTheFollowingFrame() {
        val corrupt = ack.copyOf().apply { this[9] = 0x41 }
        val decoder = Kld42FrameDecoder()
        val payloads = mutableListOf<ByteArray>()
        val stream = corrupt + ack
        decoder.feed(stream, stream.size, payloads::add)
        assertEquals(1, payloads.size)
        assertEquals(1L, decoder.checksumErrorCount)
    }

    @Test fun invalidLengthDoesNotBlockFollowingFrames() {
        val decoder = Kld42FrameDecoder()
        val payloads = mutableListOf<ByteArray>()
        val stream = hex("AA AA AA 96 69 FF FF") + ack
        decoder.feed(stream, stream.size, payloads::add)
        assertEquals(1, payloads.size)
        assertEquals(1L, decoder.invalidLengthCount)
    }

    @Test fun retainsAllFramesInLargeUsbBatch() {
        val stream = ByteArray(ack.size * 1000) { ack[it % ack.size] }
        val decoder = Kld42FrameDecoder()
        val payloads = mutableListOf<ByteArray>()
        decoder.feed(stream, stream.size, payloads::add)
        assertEquals(1000, payloads.size)
    }

    @Test fun clearDiscardsOnlyTheUnfinishedFrame() {
        val decoder = Kld42FrameDecoder()
        val payloads = mutableListOf<ByteArray>()
        decoder.feed(ack.copyOfRange(0, 6), 6, payloads::add)
        decoder.clear()
        decoder.feed(ack, ack.size, payloads::add)
        assertEquals(1, payloads.size)
    }

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
