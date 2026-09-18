// Copyright 2026 Hyindex. All rights reserved.
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class GeminiEmbedding2ProviderTest {

    @Test
    fun `code query uses code retrieval task prefix and omits legacy taskType`() = runBlocking {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = startStub(captured, dim = 1024)
        try {
            val provider = gemini2FromConfig(server, Corpus.CODE)
            val vec = provider.embedQuery("find BlockType registry")
            assertEquals(1024, vec.size)

            val body = Json.parseToJsonElement(captured.single().body).jsonObject
            assertFalse(body.containsKey("taskType"), "Embedding 2 must not send Gemini 1 taskType")
            assertEquals(
                "task: code retrieval | query: find BlockType registry",
                firstText(body),
            )
            assertEquals(1024, outputDimensionality(body))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `text query uses search result task prefix and omits legacy taskType`() = runBlocking {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = startStub(captured, dim = 1024)
        try {
            val provider = gemini2FromConfig(server, Corpus.DOCS)
            provider.embedQuery("how do NPCs pathfind")

            val body = Json.parseToJsonElement(captured.single().body).jsonObject
            assertFalse(body.containsKey("taskType"), "Embedding 2 must not send Gemini 1 taskType")
            assertEquals(
                "task: search result | query: how do NPCs pathfind",
                firstText(body),
            )
            assertEquals(1024, outputDimensionality(body))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `documents use title none text prefix and nested 1024 outputDimensionality`() = runBlocking {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = startStub(captured, dim = 1024)
        try {
            val provider = gemini2FromConfig(server, Corpus.CODE)
            val vecs = provider.embed(listOf("class Foo {}"))
            assertEquals(1, vecs.size)
            assertEquals(1024, vecs.single().size)

            val body = Json.parseToJsonElement(captured.single().body).jsonObject
            assertFalse(body.containsKey("taskType"), "Embedding 2 documents must not send taskType")
            assertEquals("title: none | text: class Foo {}", firstText(body))
            assertEquals(1024, outputDimensionality(body))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `legacy gemini embedding 001 still sends taskType and does not prefix content`() = runBlocking {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = startStub(captured, dim = 3072)
        try {
            val provider = GeminiEmbeddingProvider(
                apiKey = "AIza-test",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                model = "gemini-embedding-001",
                dimensions = 3072,
            )
            provider.embedQuery("legacy query")
            provider.embed(listOf("legacy document"))

            assertEquals(2, captured.size)
            val query = Json.parseToJsonElement(captured[0].body).jsonObject
            val document = Json.parseToJsonElement(captured[1].body).jsonObject
            assertEquals("RETRIEVAL_QUERY", query["taskType"]!!.jsonPrimitive.content)
            assertEquals("legacy query", firstText(query))
            assertEquals("RETRIEVAL_DOCUMENT", document["taskType"]!!.jsonPrimitive.content)
            assertEquals("legacy document", firstText(document))
            assertFalse(firstText(query).startsWith("task:"))
            assertFalse(firstText(document).startsWith("title:"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `native multimodal documents send text plus inlineData and preserve media type`() = runBlocking {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = startStub(captured, dim = 1024)
        try {
            val provider = gemini2FromConfig(server, Corpus.VISUAL)
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

            val body = Json.parseToJsonElement(captured.single().body).jsonObject
            assertFalse(body.containsKey("taskType"), "Embedding 2 multimodal must not send taskType")
            val parts = body["content"]!!.jsonObject["parts"]!!.jsonArray
            assertTrue(parts.size >= 2, "native multimodal must send text plus inlineData parts")
            val textPart = parts.first { it.jsonObject.containsKey("text") }.jsonObject
            assertTrue(textPart["text"]!!.jsonPrimitive.content.contains("IronSword"))
            val inline = parts.first { it.jsonObject.containsKey("inlineData") }.jsonObject["inlineData"]!!.jsonObject
            assertEquals("image/png", inline["mimeType"]!!.jsonPrimitive.content)
            assertEquals(Base64.getEncoder().encodeToString(png), inline["data"]!!.jsonPrimitive.content)
            assertEquals(1024, outputDimensionality(body))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `fromConfig passes corpus purpose so code and text queries differ`() = runBlocking {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = startStub(captured, dim = 1024)
        try {
            val code = gemini2FromConfig(server, Corpus.CODE)
            val text = gemini2FromConfig(server, Corpus.DOCS)
            val image = gemini2FromConfig(server, Corpus.VISUAL)
            assertEquals("gemini-embedding-2", code.modelId)
            assertEquals("gemini-embedding-2", text.modelId)
            assertEquals("gemini-embedding-2", image.modelId)
            assertEquals(1024, code.dimension)
            assertEquals(1024, text.dimension)
            assertEquals(1024, image.dimension)

            code.embedQuery("shared query")
            text.embedQuery("shared query")
            val codeBody = Json.parseToJsonElement(captured[0].body).jsonObject
            val textBody = Json.parseToJsonElement(captured[1].body).jsonObject
            assertEquals("task: code retrieval | query: shared query", firstText(codeBody))
            assertEquals("task: search result | query: shared query", firstText(textBody))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `gemini 2 constructor with null dimensions reports and sends 1024`() = runBlocking {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = startStub(captured, dim = 1024)
        try {
            val provider = GeminiEmbeddingProvider(
                apiKey = "AIza-test",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                model = "gemini-embedding-2",
            )
            assertEquals(1024, provider.dimension)
            provider.embedQuery("dim check")
            val body = Json.parseToJsonElement(captured.single().body).jsonObject
            assertEquals(provider.dimension, outputDimensionality(body))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `429 with google retryDelay retries without waiting exponential fallback`() = runBlocking {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = start429ThenOk(captured, dim = 1024)
        try {
            val provider = GeminiEmbeddingProvider(
                apiKey = "AIza-test",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                model = "gemini-embedding-2",
            )
            val started = System.nanoTime()
            val vec = provider.embedQuery("quota burst")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(1024, vec.size)
            assertEquals(2, captured.size)
            assertTrue(
                elapsedMs < 4_000,
                "parsed retryDelay must beat 5s exponential fallback; waited ${elapsedMs}ms",
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `permanent billing 429s fail immediately without retry`() {
        for (message in listOf(
            "Your prepayment credits are depleted. Please top up to continue.",
            "Your project has exceeded its monthly spending cap.",
        )) {
            assertPermanentBilling429(message)
        }
    }

    private fun gemini2FromConfig(server: HttpServer, corpus: Corpus): EmbeddingProvider {
        val cfg = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "gemini" to EmbeddingProfile(
                    provider = "gemini",
                    apiKey = "AIza-test",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    documentModel = "gemini-embedding-2",
                    dimensions = 1024,
                ),
            ),
            corpusEmbeddingProfiles = Corpus.entries.associate { it.id to "gemini" },
        )
        return EmbeddingProvider.fromConfig(cfg, corpus)
    }

    private fun firstText(body: kotlinx.serialization.json.JsonObject): String {
        val content = body["content"]?.jsonObject
            ?: body["requests"]!!.jsonArray.first().jsonObject["content"]!!.jsonObject
        return content["parts"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    }
    private fun outputDimensionality(body: kotlinx.serialization.json.JsonObject): Int {
        return body["embedContentConfig"]!!.jsonObject["outputDimensionality"]!!.jsonPrimitive.content.toInt()
    }

    private data class Captured(val path: String, val body: String)

    private fun startStub(captured: MutableList<Captured>, dim: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            captured += Captured(exchange.requestURI.path, body)
            val parsed = Json.parseToJsonElement(body).jsonObject
            val n = parsed["requests"]?.jsonArray?.size ?: 1
            val values = (1..dim).joinToString(",") { "0.01" }
            val payload = if (parsed.containsKey("requests")) {
                val entries = (1..n).joinToString(",") { """{"values":[$values]}""" }
                """{"embeddings":[$entries]}"""
            } else {
                """{"embedding":{"values":[$values]}}"""
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun start429ThenOk(captured: MutableList<Captured>, dim: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = java.util.concurrent.atomic.AtomicInteger(0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            captured += Captured(exchange.requestURI.path, body)
            if (hits.getAndIncrement() == 0) {
                val err = """{"error":{"code":429,"message":"Resource exhausted. Please retry in 0.05s.","details":[{"retryDelay":"0.05s"}]}}"""
                val bytes = err.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(429, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            val values = (1..dim).joinToString(",") { "0.01" }
            val payload = """{"embedding":{"values":[$values]}}"""
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun assertPermanentBilling429(message: String) {
        val captured = CopyOnWriteArrayList<Captured>()
        val server = startPermanent429(captured, message)
        try {
            val provider = GeminiEmbeddingProvider(
                apiKey = "AIza-test",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                model = "gemini-embedding-2",
            )
            val err = assertThrows(EmbeddingException.ApiError::class.java) {
                runBlocking { provider.embedQuery("quota") }
            }
            assertTrue(err.message!!.contains("429"), err.message)
            assertTrue(err.message!!.contains(message), err.message)
            assertEquals(1, captured.size)
        } finally {
            server.stop(0)
        }
    }

    private fun startPermanent429(captured: MutableList<Captured>, message: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            captured += Captured(exchange.requestURI.path, body)
            val err = """{"error":{"code":429,"message":"$message"}}"""
            val bytes = err.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(429, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

}
