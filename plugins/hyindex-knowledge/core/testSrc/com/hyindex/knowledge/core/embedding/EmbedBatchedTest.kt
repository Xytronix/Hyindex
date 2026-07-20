// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class EmbedBatchedTest {


    private class RecordingProvider(
        private val delayMs: Long = 0,
        private val dim: Int = 4,
    ) : EmbeddingProvider {
        override val modelId: String = "recording"
        override val dimension: Int = dim
        val maxInFlight = AtomicInteger(0)
        private val inFlight = AtomicInteger(0)

        override suspend fun validate() {}
        override suspend fun embedQuery(query: String): FloatArray = FloatArray(dim)
        override suspend fun embed(texts: List<String>): List<FloatArray> {
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { m -> max(m, now) }
            try {
                if (delayMs > 0) delay(delayMs)

                return texts.map { t -> FloatArray(dim).also { it[0] = t.removePrefix("t").toFloat() } }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    fun `preserves input order across concurrent batches`() = runBlocking {
        val provider = RecordingProvider()
        val texts = (0 until 50).map { "t$it" }

        val out = provider.embedBatched(texts, batchSize = 5, concurrency = 4)

        assertEquals(50, out.size)
        for (i in texts.indices) {
            assertEquals(i.toFloat(), out[i][0], "vector at position $i is out of order")
        }
    }

    @Test
    fun `runs batches concurrently but never exceeds the concurrency cap`() = runBlocking {
        val provider = RecordingProvider(delayMs = 25)
        val texts = (0 until 16).map { "t$it" }

        val out = provider.embedBatched(texts, batchSize = 1, concurrency = 4)

        assertEquals(16, out.size)
        val peak = provider.maxInFlight.get()
        assertTrue(peak in 2..4, "expected 2..4 concurrent batches, observed $peak")
    }

    @Test
    fun `concurrency of 1 embeds serially`() = runBlocking {
        val provider = RecordingProvider(delayMs = 10)

        val out = provider.embedBatched((0 until 8).map { "t$it" }, batchSize = 1, concurrency = 1)

        assertEquals(8, out.size)
        assertEquals(1, provider.maxInFlight.get(), "concurrency=1 must run strictly serially")
    }

    @Test
    fun `empty input returns empty`() = runBlocking {
        assertEquals(0, RecordingProvider().embedBatched(emptyList(), batchSize = 5, concurrency = 4).size)
    }

    @Test
    fun `defaults concurrency to the provider's batchConcurrency`() = runBlocking {

        val provider = RecordingProvider(delayMs = 5)
        val out = provider.embedBatched((0 until 4).map { "t$it" }, batchSize = 1)
        assertEquals(4, out.size)
        assertEquals(1, provider.maxInFlight.get())
    }
}
