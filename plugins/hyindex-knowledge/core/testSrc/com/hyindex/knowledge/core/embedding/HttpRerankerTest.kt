// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.config.RerankerProfile
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class HttpRerankerTest {
    @Test
    fun `voyage profile uses voyage protocol and parses data response`() {
        val captured = mutableListOf<Captured>()
        val server = startStub(
            captured,
            """{"data":[{"index":1,"relevance_score":0.91},{"index":0,"relevance_score":0.12}]}""",
        )
        try {
            val reranker = RerankerFactory.fromProfile(
                RerankerProfile(
                    provider = "voyage",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    apiKey = "secret",
                    model = "rerank-2.5",
                ),
            )

            assertEquals(listOf(1 to 0.91, 0 to 0.12), reranker.rerank("query", listOf("a", "b")))
            val request = captured.single()
            assertEquals("/v1/rerank", request.path)
            assertEquals("Bearer secret", request.authorization)
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals(2, body["top_k"]!!.jsonPrimitive.int)
            assertTrue("top_n" !in body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `custom provider can select cohere protocol and endpoint`() {
        val captured = mutableListOf<Captured>()
        val server = startStub(
            captured,
            """{"results":[{"index":0,"relevance_score":0.77}]}""",
        )
        try {
            val reranker = RerankerFactory.fromProfile(
                RerankerProfile(
                    provider = "acme",
                    protocol = "cohere",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    endpoint = "/custom/rerank",
                    model = "acme-rerank",
                ),
            )

            assertEquals(listOf(0 to 0.77), reranker.rerank("query", listOf("a")))
            val request = captured.single()
            assertEquals("/custom/rerank", request.path)
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals(1, body["top_n"]!!.jsonPrimitive.int)
            assertTrue("top_k" !in body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `jina profile defaults to v1 endpoint and top-n protocol`() {
        val captured = mutableListOf<Captured>()
        val server = startStub(
            captured,
            """{"results":[{"index":0,"relevance_score":0.5}]}""",
        )
        try {
            val reranker = RerankerFactory.fromProfile(
                RerankerProfile(
                    provider = "jina",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    model = "jina-reranker-v3.5",
                ),
            )

            reranker.rerank("query", listOf("a"))
            val request = captured.single()
            assertEquals("/v1/rerank", request.path)
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals(1, body["top_n"]!!.jsonPrimitive.int)
        } finally {
            server.stop(0)
        }
    }

    private fun startStub(captured: MutableList<Captured>, response: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured += Captured(
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                body = exchange.requestBody.readBytes().decodeToString(),
            )
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private data class Captured(
        val path: String,
        val authorization: String?,
        val body: String,
    )
}
