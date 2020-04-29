package com.whelksoft.yasea

import java.util.*

class SrsAllocator @JvmOverloads constructor(private val individualAllocationSize: Int, initialAllocationCount: Int = 0) {
    inner class Allocation(size: Int) {
        private val data: ByteArray
        private var size: Int
        fun array(): ByteArray {
            return data
        }

        fun size(): Int {
            return size
        }

        fun appendOffset(offset: Int) {
            size += offset
        }

        fun clear() {
            size = 0
        }

        fun put(b: Byte) {
            data[size++] = b
        }

        fun put(b: Byte, pos: Int) {
            var pos = pos
            data[pos++] = b
            size = if (pos > size) pos else size
        }

        fun put(s: Short) {
            put(s.toByte())
            put((s.toInt() ushr 8) as Byte)
        }

        fun put(i: Int) {
            put(i.toByte())
            put((i ushr 8).toByte())
            put((i ushr 16).toByte())
            put((i ushr 24).toByte())
        }

        fun put(bs: ByteArray) {
            System.arraycopy(bs, 0, data, size, bs.size)
            size += bs.size
        }

        init {
            data = ByteArray(size)
            this.size = 0
        }
    }

    @Volatile
    private var availableSentinel: Int
    private var availableAllocations: Array<Allocation?>

    @Synchronized
    fun allocate(size: Int): Allocation? {
        for (i in 0 until availableSentinel) {
            if (availableAllocations[i]!!.size() >= size) {
                val ret = availableAllocations[i]
                availableAllocations[i] = null
                return ret
            }
        }
        return Allocation(if (size > individualAllocationSize) size else individualAllocationSize)
    }

    @Synchronized
    fun release(allocation: Allocation?) {
        allocation!!.clear()
        for (i in 0 until availableSentinel) {
            if (availableAllocations[i]!!.size() == 0) {
                availableAllocations[i] = allocation
                return
            }
        }
        if (availableSentinel + 1 > availableAllocations.size) {
            availableAllocations = Arrays.copyOf(availableAllocations, availableAllocations.size * 2)
        }
        availableAllocations[availableSentinel++] = allocation
    }
    /**
     * Constructs an instance with some [Allocation]s created up front.
     *
     *
     *
     * @param individualAllocationSize The length of each individual [Allocation].
     * @param initialAllocationCount The number of allocations to create up front.
     */
    /**
     * Constructs an instance without creating any [Allocation]s up front.
     *
     * @param individualAllocationSize The length of each individual [Allocation].
     */
    init {
        availableSentinel = initialAllocationCount + 10
        availableAllocations = arrayOfNulls(availableSentinel)
        for (i in 0 until availableSentinel) {
            availableAllocations[i] = Allocation(individualAllocationSize)
        }
    }
}