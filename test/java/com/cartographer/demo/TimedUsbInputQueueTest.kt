package com.cartographer.demo

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TimedUsbInputQueueTest {
    @Test(timeout = 3000) fun readsContinueWhileSlamConsumerIsBlockedAndArrivalTimesSurvive() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val arrivals = Collections.synchronizedList(mutableListOf<Long>())
        val failures = Collections.synchronizedList(mutableListOf<Exception>())
        val queue = TimedUsbInputQueue(consume = { _, time ->
            if (time == 10L) { entered.countDown(); release.await() }
            arrivals.add(time)
            done.countDown()
        }, onFailure = { failures.add(it) })
        try {
            assertTrue(queue.submit(byteArrayOf(1), 10))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(queue.submit(byteArrayOf(2), 20))
            assertEquals(1, queue.queuedReads)
            release.countDown()
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(10L, 20L), arrivals)
            assertTrue(failures.isEmpty())
        } finally { release.countDown(); queue.close() }
    }

    @Test(timeout = 3000) fun copiesReadBuffersBeforeSerialLibraryCanReuseThem() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val values = Collections.synchronizedList(mutableListOf<Byte>())
        val queue = TimedUsbInputQueue(consume = { data, time ->
            if (time == 10L) { entered.countDown(); release.await() }
            values.add(data[0]); done.countDown()
        }, onFailure = {})
        try {
            queue.submit(byteArrayOf(1), 10)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val input = byteArrayOf(2)
            queue.submit(input, 20)
            input[0] = 99
            release.countDown()
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(1.toByte(), 2.toByte()), values)
        } finally { release.countDown(); queue.close() }
    }

    @Test(timeout = 3000) fun invalidationRejectsSubmissionsCapturedBeforePause() {
        val queue = TimedUsbInputQueue(consume = { _, _ -> }, onFailure = {})
        try {
            val oldEpoch = queue.epoch
            queue.invalidate()
            assertFalse(queue.submit(byteArrayOf(1), 10, oldEpoch))
            assertTrue(queue.submit(byteArrayOf(2), 20, queue.epoch))
        } finally { queue.close() }
    }

    @Test(timeout = 3000) fun pauseBarrierWaitsForActiveNativeConsumerBeforeReset() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pausing = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val queue = TimedUsbInputQueue(consume = { _, _ -> entered.countDown(); release.await() }, onFailure = {})
        try {
            queue.submit(byteArrayOf(1), 10)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val thread = Thread {
                pausing.countDown()
                queue.invalidate()
                paused.countDown()
            }.apply { start() }
            assertTrue(pausing.await(1, TimeUnit.SECONDS))
            assertFalse(paused.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(paused.await(1, TimeUnit.SECONDS))
            thread.join(1000)
        } finally { release.countDown(); queue.close() }
    }

    @Test(timeout = 3000) fun queueOverflowIsVisibleInsteadOfSilentlyDroppingSensorData() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val queue = TimedUsbInputQueue(consume = { _, _ -> entered.countDown(); release.await() },
            onFailure = { failed.countDown() }, maxBytes = 2)
        try {
            queue.submit(byteArrayOf(1), 10)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(queue.submit(byteArrayOf(1, 2, 3), 20))
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            assertFalse(queue.submit(byteArrayOf(1), 30))
        } finally { release.countDown(); queue.close() }
    }

    @Test(timeout = 3000) fun consumerFailureStopsQueueAndIsReported() {
        val failed = CountDownLatch(1)
        val queue = TimedUsbInputQueue(consume = { _, _ -> error("test consumer failure") },
            onFailure = { failed.countDown() })
        try {
            queue.submit(byteArrayOf(1), 10)
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            assertFalse(queue.submit(byteArrayOf(1), 20))
        } finally { queue.close() }
    }
}
