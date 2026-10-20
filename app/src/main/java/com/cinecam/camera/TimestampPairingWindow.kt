package com.cinecam.camera

import java.util.TreeMap

/** Handler-confined exact timestamp join. Caller pauses acquisition instead of evicting Images.
 * Resource ownership: caller closes replaced/cleared Images and every returned matched Image.
 */
internal class TimestampPairingWindow<I : Any, R : Any>(private val imageLimit: Int = 2,
                                                       private val resultLimit: Int = 12) {
    init { require(imageLimit > 0 && resultLimit > 0) }
    private val images = TreeMap<Long, I>()
    private val results = TreeMap<Long, R>()
    val canAcquire: Boolean get() = images.size < imageLimit
    val imageTimestamps: List<Long> get() = images.keys.toList()
    val resultTimestamps: List<Long> get() = results.keys.toList()
    fun addImage(timestamp: Long, image: I): I? {
        check(canAcquire || images.containsKey(timestamp)) { "Image acquisition exceeded bounded window" }
        return images.put(timestamp, image)
    }
    fun addResult(timestamp: Long, result: R): Long? {
        results[timestamp] = result
        return if (results.size > resultLimit) results.pollFirstEntry().key else null
    }
    data class Pair<I, R>(val timestamp: Long, val image: I, val result: R)
    fun pollMatch(): Pair<I, R>? {
        val timestamp = images.keys.firstOrNull { results.containsKey(it) } ?: return null
        return Pair(timestamp, images.remove(timestamp)!!, results.remove(timestamp)!!)
    }
    fun clearImages(): List<I> = images.values.toList().also { images.clear() }
    fun clearResults() { results.clear() }
}
