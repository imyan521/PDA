package com.cartographer.demo

import java.io.Closeable
import java.util.ArrayDeque

/** Keep USB reads independent of synchronous scan matching, preserving arrival times. */
class TimedUsbInputQueue(
    private val consume: (ByteArray, Long) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val maxBytes: Int = 256 * 1024,
    private val maxReads: Int = 4096
) : Closeable {
    private data class Read(val data: ByteArray, val receivedAtNs: Long, val epoch: Long)
    private val monitor = Object()
    private val processing = Any()
    private val reads = ArrayDeque<Read>()
    private var queuedBytes = 0
    @Volatile private var closed = false
    @Volatile var epoch = 0L
        private set
    @Volatile var latestDelayNs = 0L
        private set
    @Volatile var maxDelayNs = 0L
        private set
    val queuedReads: Int get() = synchronized(monitor) { reads.size }

    private val worker = Thread({ drain() }, "lidar-input").apply {
        isDaemon = true
        start()
    }

    /** expectedEpoch is captured before checking the caller's pause flag. */
    fun submit(data: ByteArray, receivedAtNs: Long, expectedEpoch: Long = epoch): Boolean {
        var overflow = false
        synchronized(monitor) {
            if (closed || expectedEpoch != epoch) return false
            if (reads.size >= maxReads || data.size > maxBytes - queuedBytes) {
                overflow = true
            } else {
                reads.addLast(Read(data.copyOf(), receivedAtNs, expectedEpoch))
                queuedBytes += data.size
                monitor.notifyAll()
                return true
            }
        }
        if (overflow) fail(IllegalStateException("传感器处理积压超限，已停止接收以避免丢帧后继续建图"))
        return false
    }

    /** Wait for the active consumer, then invalidate old queued/in-flight submissions. */
    fun invalidate() {
        synchronized(processing) {
            synchronized(monitor) {
                epoch++
                reads.clear()
                queuedBytes = 0
            }
        }
    }

    override fun close() {
        synchronized(monitor) {
            closed = true
            reads.clear()
            queuedBytes = 0
            monitor.notifyAll()
        }
        worker.interrupt()
    }

    private fun drain() {
        try {
            while (true) {
                val read = synchronized(monitor) {
                    while (reads.isEmpty() && !closed) monitor.wait()
                    if (closed) return
                    reads.removeFirst().also { queuedBytes -= it.data.size }
                }
                synchronized(processing) {
                    if (!closed && read.epoch == epoch) {
                        latestDelayNs = maxOf(0, clock() - read.receivedAtNs)
                        maxDelayNs = maxOf(maxDelayNs, latestDelayNs)
                        consume(read.data, read.receivedAtNs)
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed) fail(e)
        }
    }

    private fun fail(error: Exception) {
        synchronized(monitor) {
            if (closed) return
            closed = true
            reads.clear()
            queuedBytes = 0
            monitor.notifyAll()
        }
        worker.interrupt()
        onFailure(error)
    }
}
