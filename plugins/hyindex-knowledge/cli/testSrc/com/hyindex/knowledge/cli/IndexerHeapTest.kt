package com.hyindex.knowledge.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndexerHeapTest {
    private val gb = 1024L * 1024 * 1024

    @Test fun `target heap is three quarters of RAM, capped`() {
        assertEquals(6 * gb, targetHeapBytes(8 * gb))
        assertEquals(MAX_TARGET_HEAP, targetHeapBytes(64 * gb))
        assertEquals(0L, targetHeapBytes(0L))
    }

    @Test fun `relaunch when heap is materially below target`() {
        assertTrue(needsLargerHeap(currentMaxBytes = 2 * gb, physicalBytes = 8 * gb))
    }

    @Test fun `no relaunch once heap is adequate (prevents loop)`() {
        assertFalse(needsLargerHeap(currentMaxBytes = 6 * gb, physicalBytes = 8 * gb))
    }

    @Test fun `no relaunch when physical memory is unknown`() {
        assertFalse(needsLargerHeap(currentMaxBytes = 2 * gb, physicalBytes = 0L))
    }
}
