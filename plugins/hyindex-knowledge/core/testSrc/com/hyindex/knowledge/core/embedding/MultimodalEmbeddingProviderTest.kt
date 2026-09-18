package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.config.EmbeddingProfile
import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class MultimodalEmbeddingProviderTest {

    @Test
    fun `voyage multimodal 3 point 5 query is text-only and uses the multimodal embeddings endpoint`() = runBlocking {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = startStub(captured)
        try {
            val provider = VoyageAIProvider(
                apiKey = "vk-test",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                model = "voyage-multimodal-3.5",
            )
            val vec = provider.embedQuery("iron sword icon")
            assertEquals(1024, vec.size)

            assertEquals(1, captured.size)
            val req = captured.single()
            assertTrue(req.path.contains("multimodal"), "query must hit multimodal embeddings, got ${req.path}")
            val body = Json.parseToJsonElement(req.body).jsonObject
            assertEquals("voyage-multimodal-3.5", body["model"]!!.jsonPrimitive.content)
            assertEquals("query", body["input_type"]!!.jsonPrimitive.content)
            val content = body["inputs"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            assertEquals("text", content.single().jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse(req.body.contains("image_base64") || req.body.contains("image_url"), "query must not send image bytes")
            assertTrue(req.body.contains("iron sword icon"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `document embed for visual assets includes image bytes plus descriptive text`() = runBlocking {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = startStub(captured)
        try {
            val provider = VoyageAIProvider(
                apiKey = "vk-test",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                model = "voyage-multimodal-3.5",
            )
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val vecs = provider.embedDocuments(
                listOf(
                    MultimodalDocument(
                        text = "visual asset IronSword path=Icons/Weapons/IronSword.png",
                        imageBytes = png,
                        mediaType = "image/png",
                    ),
                ),
            )
            assertEquals(1, vecs.size)
            assertEquals(1024, vecs.single().size)

            val req = captured.single()
            assertTrue(req.path.contains("multimodal"), "document embed must hit multimodal embeddings, got ${req.path}")
            val body = Json.parseToJsonElement(req.body).jsonObject
            assertEquals("document", body["input_type"]!!.jsonPrimitive.content)
            val content = body["inputs"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            val image = content.single { it.jsonObject["type"]?.jsonPrimitive?.content == "image_base64" }.jsonObject
            val encoded = Base64.getEncoder().encodeToString(png)
            assertEquals("data:image/png;base64,$encoded", image["image_base64"]!!.jsonPrimitive.content)
            assertTrue(req.body.contains("IronSword"))
            assertFalse(req.body.contains("/v1/embeddings\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `visual corpus fromConfig uses multimodal model not the text encoder`() {
        val cfg = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "code" to EmbeddingProfile(provider = "voyage", apiKey = "vk-test", documentModel = "voyage-code-4"),
                "text" to EmbeddingProfile(provider = "voyage", apiKey = "vk-test", documentModel = "voyage-4-large"),
                "visual" to EmbeddingProfile(provider = "voyage", apiKey = "vk-test", documentModel = "voyage-multimodal-3.5"),
            ),
            corpusEmbeddingProfiles = mapOf("code" to "code", "docs" to "text", "gamedata" to "text", "client" to "text", "visual" to "visual"),
        )
        val visual = EmbeddingProvider.fromConfig(cfg, Corpus.VISUAL)
        assertEquals("voyage-multimodal-3.5", visual.modelId)
        assertEquals(1024, visual.dimension)
        assertTrue(visual.supportsMultimodalDocuments)
    }

    @Test
    fun `only voyage multimodal and gemini embedding 2 advertise multimodal documents`() {
        assertTrue(VoyageAIProvider(apiKey = "k", model = "voyage-multimodal-3.5").supportsMultimodalDocuments)
        assertFalse(VoyageAIProvider(apiKey = "k", model = "voyage-code-4").supportsMultimodalDocuments)
        assertFalse(VoyageAIProvider(apiKey = "k", model = "voyage-4-large").supportsMultimodalDocuments)
        assertTrue(GeminiEmbeddingProvider(apiKey = "k", model = "gemini-embedding-2").supportsMultimodalDocuments)
        assertFalse(GeminiEmbeddingProvider(apiKey = "k", model = "text-embedding-004").supportsMultimodalDocuments)
        val openai = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "openai" to EmbeddingProfile(provider = "openai", apiKey = "k", documentModel = "text-embedding-3-large"),
            ),
            corpusEmbeddingProfiles = mapOf("code" to "openai", "docs" to "openai", "gamedata" to "openai", "client" to "openai"),
        )
        assertFalse(
            EmbeddingProvider.fromConfig(openai, Corpus.DOCS).supportsMultimodalDocuments,
        )
    }

    private data class Captured(val path: String, val body: String)

    private fun startStub(captured: MutableList<Captured>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            captured += Captured(exchange.requestURI.path, body)
            val dim = 1024
            val embedding = (1..dim).joinToString(",") { "0.01" }
            val payload = """{"object":"list","data":[{"index":0,"embedding":[$embedding]}],"model":"voyage-multimodal-3.5"}"""
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }
}
