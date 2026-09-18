// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class VoyageContextualResponseTest {

    @Test
    fun `context query accepts gateway-flattened embedding response`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/contextualizedembeddings") { exchange ->
            val payload = """{"object":"list","model":"voyage-context-4","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3,0.4]}],"usage":{"total_tokens":2}}"""
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val provider = VoyageAIProvider(
                apiKey = "test-key",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                model = "voyage-context-4",
                dimensions = 4,
            )
            assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), provider.embedQuery("inventory"))
        } finally {
            server.stop(0)
        }
    }
}
