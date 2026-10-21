package com.example.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe ring buffer for streaming 16-bit PCM audio chunks between reader
 * threads and the audio mixing thread. Absorbs heavy system scheduling jitter,
 * garbage collection pauses, and thermal slowdown.
 */
class AudioRingBuffer(val capacityShorts: Int = 65_536) {

    private val buffer = ShortArray(capacityShorts)
    private var writeHead = 0
    private var readHead = 0
    private var availableSamples = 0
    private val lock = Any()

    val totalOverruns = AtomicLong(0L)
    val totalUnderruns = AtomicLong(0L)

    fun write(data: ShortArray, offset: Int, count: Int): Int {
        if (count <= 0) return 0
        synchronized(lock) {
            val spaceLeft = capacityShorts - availableSamples
            if (spaceLeft < count) {
                totalOverruns.incrementAndGet()
            }

            val toWrite = minOf(count, spaceLeft)
            if (toWrite <= 0) {
                return 0
            }

            for (i in 0 until toWrite) {
                buffer[writeHead] = data[offset + i]
                writeHead = (writeHead + 1) % capacityShorts
            }
            availableSamples += toWrite
            return toWrite
        }
    }

    /**
     * Reads up to [count] shorts into [out] at [offset].
     * Returns the actual number of shorts read.
     * If fewer than [count] are available, reads whatever is available without blocking.
     */
    fun read(out: ShortArray, offset: Int, count: Int): Int {
        if (count <= 0) return 0
        synchronized(lock) {
            val toRead = minOf(count, availableSamples)
            if (toRead <= 0) {
                totalUnderruns.incrementAndGet()
                return 0
            }

            for (i in 0 until toRead) {
                out[offset + i] = buffer[readHead]
                readHead = (readHead + 1) % capacityShorts
            }
            availableSamples -= toRead
            return toRead
        }
    }

    fun available(): Int = synchronized(lock) { availableSamples }

    fun clear() = synchronized(lock) {
        writeHead = 0
        readHead = 0
        availableSamples = 0
    }
}
