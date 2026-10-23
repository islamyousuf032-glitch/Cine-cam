package com.cinecam.camera

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Runs an index-keyed task on a small fixed pool and returns results in index order.
 *
 * Used for independent file writes (export copies, DNG sequence frames) so per-file
 * fsync stalls overlap instead of adding up. On the first failure every other task is
 * cancelled and the pool is fully stopped before the error is rethrown, so the caller's
 * cleanup never races a worker that is still writing.
 */
internal object BoundedParallel {
    const val DEFAULT_WORKERS = 3
    const val MAX_WORKERS = 4

    fun <T : Any> map(workers: Int, count: Int, task: (Int) -> T): List<T> {
        require(workers in 1..MAX_WORKERS) { "worker সংখ্যা সীমার বাইরে" }
        require(count >= 0) { "কাজের সংখ্যা ঋণাত্মক" }
        if (count == 0) return emptyList()
        if (workers == 1 || count == 1) return (0 until count).map(task)

        val pool = Executors.newFixedThreadPool(minOf(workers, count)) { runnable ->
            Thread(runnable, "cinecam-parallel").apply { isDaemon = true }
        }
        @Suppress("UNCHECKED_CAST")
        val results = arrayOfNulls<Any?>(count) as Array<T?>
        val futures = ArrayList<Future<*>>(count)
        try {
            for (index in 0 until count) {
                futures.add(pool.submit(Callable {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
                    results[index] = task(index)
                }))
            }
            var failure: Throwable? = null
            for (future in futures) {
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    if (failure == null) failure = e.cause ?: e
                } catch (e: InterruptedException) {
                    if (failure == null) failure = e
                }
            }
            failure?.let { throw it }
            return results.mapIndexed { index, value ->
                value ?: throw IllegalStateException("parallel result $index হারানো")
            }
        } finally {
            // Cancel + join: no worker may still touch files after the caller cleans up.
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        }
    }
}
