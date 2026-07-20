// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger


suspend fun EmbeddingProvider.embedBatched(
    texts: List<String>,
    batchSize: Int = 32,
    concurrency: Int = batchConcurrency,
    onBatchComplete: (completed: Int, total: Int) -> Unit = { _, _ -> },
): List<FloatArray> {
    if (texts.isEmpty()) return emptyList()
    val batches = texts.chunked(batchSize.coerceAtLeast(1))
    val total = batches.size
    val semaphore = Semaphore(concurrency.coerceIn(1, total))
    val completed = AtomicInteger(0)
    return coroutineScope {
        batches
            .map { batch ->
                async {
                    semaphore.withPermit { embed(batch) }
                        .also { onBatchComplete(completed.incrementAndGet(), total) }
                }
            }
            .awaitAll()
            .flatten()
    }
}
