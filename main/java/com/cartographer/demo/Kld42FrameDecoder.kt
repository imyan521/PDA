package com.cartographer.demo

/** Streaming A-type framing. USB reads can split a frame at any byte. */
class Kld42FrameDecoder {
    private val buffer = ByteArray(MAX_FRAME_SIZE)
    private var size = 0
    var validFrameCount = 0L
        private set
    var checksumErrorCount = 0L
        private set
    var invalidLengthCount = 0L
        private set
    var discardedByteCount = 0L
        private set

    fun clear() { size = 0 }

    /** Payload includes the trailing checksum, matching Kld42LidarScanParser. */
    fun feed(data: ByteArray, length: Int, onPayload: (ByteArray) -> Unit) {
        val end = length.coerceIn(0, data.size)
        var offset = 0
        while (offset < end) {
            val count = minOf(end - offset, buffer.size - size)
            data.copyInto(buffer, size, offset, offset + count)
            size += count
            offset += count
            consume(onPayload)
        }
    }

    private fun consume(onPayload: (ByteArray) -> Unit) {
        while (true) {
            val head = (0..size - HEADER.size).firstOrNull { index ->
                HEADER.indices.all { buffer[index + it] == HEADER[it] }
            }
            if (head == null) {
                // Retain the longest suffix matching the beginning of the header,
                // including AA AA, AA AA AA and AA AA AA 96.
                val keep = (minOf(size, HEADER.size - 1) downTo 1).firstOrNull { n ->
                    (0 until n).all { buffer[size - n + it] == HEADER[it] }
                } ?: 0
                discard(size - keep)
                return
            }
            discard(head)
            if (size < HEADER_LENGTH) return
            val length = ((buffer[5].toInt() and 255) shl 8) or (buffer[6].toInt() and 255)
            if (length !in 4..MAX_FRAME_SIZE - HEADER_LENGTH) {
                invalidLengthCount++
                discard(1)
                continue
            }
            val frameSize = HEADER_LENGTH + length
            if (size < frameSize) return
            var checksum = 0
            for (i in 5 until frameSize) checksum = checksum xor (buffer[i].toInt() and 255)
            if (checksum != 0) {
                checksumErrorCount++
                discard(1)
                continue
            }
            val payload = buffer.copyOfRange(HEADER_LENGTH, frameSize)
            remove(frameSize)
            validFrameCount++
            onPayload(payload)
        }
    }

    private fun discard(count: Int) {
        discardedByteCount += count
        remove(count)
    }

    private fun remove(count: Int) {
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    companion object {
        // Design section 3.4: 2037 data bytes + command/status/header/checksum.
        const val MAX_FRAME_SIZE = 2048
        private const val HEADER_LENGTH = 7
        private val HEADER = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0x96.toByte(), 0x69)
    }
}
