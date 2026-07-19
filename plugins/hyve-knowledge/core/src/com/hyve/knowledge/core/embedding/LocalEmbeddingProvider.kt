// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.embedding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-process ONNX embeddings (all-MiniLM-L6-v2 quantized) via LangChain4j.
 * Requires hyve-embeddings-local.jar on the classpath (optional artifact).
 */
class LocalEmbeddingProvider(
    private val maxChars: Int = 2000,
) : EmbeddingProvider {

    private val model: Any by lazy {
        ensurePluginPresent()
        Class.forName(MODEL_CLASS).getConstructor().newInstance()
    }

    override val modelId: String = "all-minilm-l6-v2-q"
    override val dimension: Int = 384
    override val batchConcurrency: Int get() = 1

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext emptyList()
        val segmentClass = Class.forName(SEGMENT_CLASS)
        val from = segmentClass.getMethod("from", String::class.java)
        val segments = texts.map { from.invoke(null, it.take(maxChars)) }
        val list = ArrayList<Any>(segments)
        val response = model.javaClass.getMethod("embedAll", MutableList::class.java).invoke(model, list)
        val content = response.javaClass.getMethod("content").invoke(response) as List<*>
        content.map { emb -> emb!!.javaClass.getMethod("vector").invoke(emb) as FloatArray }
    }

    override suspend fun embedQuery(query: String): FloatArray = withContext(Dispatchers.Default) {
        val response = model.javaClass.getMethod("embed", String::class.java).invoke(model, query.take(maxChars))
        val embedding = response.javaClass.getMethod("content").invoke(response)
        embedding.javaClass.getMethod("vector").invoke(embedding) as FloatArray
    }

    override suspend fun validate() {
        embedQuery("test")
    }

    companion object {
        private const val MODEL_CLASS =
            "dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel"
        private const val SEGMENT_CLASS = "dev.langchain4j.data.segment.TextSegment"

        fun pluginAvailable(): Boolean = try {
            Class.forName(MODEL_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            false
        }

        fun ensurePluginPresent() {
            if (!pluginAvailable()) {
                throw IllegalStateException(
                    "embeddingProvider=local requires hyve-embeddings-local.jar on the classpath. " +
                        "Build it with: ./gradlew :embeddings-local:shadowJar " +
                        "Or use embeddingProvider=openai / voyage / cohere / gemini / jina / mistral / mixedbread / ollama instead.",
                )
            }
        }
    }
}
