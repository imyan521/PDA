package com.cartographer.demo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LatestValueWorkerTest {
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var reached = condition()
        while (!reached && System.nanoTime() < deadline) {
            Thread.sleep(1)
            reached = condition()
        }
        assertTrue("Timed out waiting for worker", reached)
    }

    @Test fun slowReadDoesNotBlockCallerOrQueueDisplayTicks() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = LatestValueWorker(0, "display-test")
        try {
            assertTrue(worker.request {
                started.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                1
            })
            assertTrue(started.await(3, TimeUnit.SECONDS))
            repeat(100) { assertFalse(worker.request { fail("Old tick must not run"); 99 }) }
            assertEquals(0, worker.latest)
            release.countDown()
            awaitCondition { worker.latest == 1 }
            awaitCondition { worker.request { 2 } }
            awaitCondition { worker.latest == 2 }
        } finally { release.countDown(); worker.close() }
    }

    @Test fun newSessionDiscardsInFlightResult() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = LatestValueWorker(0, "generation-test")
        try {
            worker.request {
                started.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                1
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            worker.invalidate()
            assertEquals(0, worker.latest)
            assertFalse(worker.request { 99 })
            release.countDown()
            awaitCondition { worker.request { worker.latest + 2 } }
            awaitCondition { worker.latest == 2 }
        } finally { release.countDown(); worker.close() }
    }

    @Test fun failuresAllowNextRefresh() {
        val failed = CountDownLatch(1)
        val worker = LatestValueWorker(0, "failure-test") { failed.countDown() }
        try {
            worker.request { throw IllegalStateException("read failed") }
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertEquals(0, worker.latest)
            awaitCondition { worker.request { 2 } }
            awaitCondition { worker.latest == 2 }
        } finally { worker.close() }
    }

    @Test fun closingRejectsRequestsAndClearsSnapshot() {
        val worker = LatestValueWorker(0, "close-test")
        worker.request { 1 }
        awaitCondition { worker.latest == 1 }
        worker.close()
        assertEquals(0, worker.latest)
        assertFalse(worker.request { 2 })
        worker.close()
    }

    @Test fun invalidationClearsPublishedSnapshot() {
        val worker = LatestValueWorker(0, "invalidate-test")
        try {
            worker.request { 1 }
            awaitCondition { worker.latest == 1 }
            worker.invalidate()
            assertEquals(0, worker.latest)
        } finally { worker.close() }
    }
}
