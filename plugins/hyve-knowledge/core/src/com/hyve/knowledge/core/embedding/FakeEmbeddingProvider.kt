// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.embedding


class FakeEmbeddingProvider(private val dim: Int = 8) : EmbeddingProvider {
    override val modelId: String = "fake"
    override val dimension: Int = dim
    override suspend fun validate() {}
    override suspend fun embed(texts: List<String>): List<FloatArray> =
        texts.map { FloatArray(dim) { 1.0f } }
    override suspend fun embedQuery(query: String): FloatArray =
        FloatArray(dim) { 1.0f }
}
