package com.cartographer.demo

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Background work with one in-flight request and no backlog of old UI ticks. */
class LatestValueWorker<T>(
    private val initialValue: T,
    threadName: String,
    private val onError: (Exception) -> Unit = {}
) : AutoCloseable {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, threadName).apply { isDaemon = true }
    }
    private var running = false
    private var closed = false
    private var generation = 0L
    @Volatile var latest: T = initialValue
        private set

    fun request(task: () -> T): Boolean = synchronized(lock) {
        if (closed || running) return false
        val requestedGeneration = generation
        running = true
        try {
            executor.execute {
                try {
                    val result = task()
                    synchronized(lock) {
                        if (!closed && requestedGeneration == generation) latest = result
                    }
                } catch (error: Exception) {
                    onError(error)
                } finally {
                    synchronized(lock) { running = false }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            running = false
            false
        }
    }

    fun invalidate() = synchronized(lock) {
        generation++
        latest = initialValue
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            generation++
            latest = initialValue
        }
        executor.shutdownNow()
    }
}
