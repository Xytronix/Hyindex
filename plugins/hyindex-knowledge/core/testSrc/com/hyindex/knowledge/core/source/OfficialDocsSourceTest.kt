// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.source

import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.extraction.DocsParser
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files

class OfficialDocsSourceTest {
    @Test
    fun `discovers same-host documentation pages and excludes generated references`() {
        val html = """
            <a href="/">Home</a>
            <a href="/creating-content/">Creating</a>
            <a href="/api/">API</a>
            <a href="/assets/">Assets</a>
            <a href="/_next/static/app.js">Static</a>
            <a href="https://example.test/hytale-generator/density?x=1#top">Density</a>
            <a href="https://other.test/guide">External</a>
            <script>{"url":"/creating-content/asset-packs"}</script>
        """.trimIndent()

        val urls = OfficialDocsSource.discoverPageUrls(html, "https://example.test")

        assertEquals(
            listOf(
                "https://example.test/",
                "https://example.test/creating-content",
                "https://example.test/creating-content/asset-packs",
                "https://example.test/hytale-generator/density",
            ),
            urls,
        )
    }

    @Test
    fun `converts rendered docs article into attributed markdown`() {
        val html = page(
            title = "How to modify Hytale",
            description = "Create and install Asset Packs.",
            body = """
                <h2 id="intro">Introduction<button>Copy Anchor Link</button></h2>
                <p>Asset packs extend the game.</p>
                <h3 id="sample">Sample</h3>
                <pre><code><span>name: Example</span></code></pre>
                <ul><li>One</li><li>Two</li></ul>
            """.trimIndent(),
        )

        val markdown = OfficialDocsSource.pageToMarkdown(
            html,
            "https://docs.hytale.com/creating-content/asset-packs",
            "release",
        )

        assertTrue(markdown.contains("title: \"How to modify Hytale\""))
        assertTrue(markdown.contains("category: official"))
        assertTrue(markdown.contains("Source: https://docs.hytale.com/creating-content/asset-packs"))
        assertTrue(markdown.contains("Documentation version: 0.6.6"))
        assertTrue(markdown.contains("## Introduction"))
        assertTrue(markdown.contains("### Sample"))
        assertTrue(markdown.contains("name: Example"))
        assertFalse(markdown.contains("Copy Anchor Link"))
    }

    @Test
    fun `fetches discovered pages and exposes official docs source ids`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/" -> page(
                    title = "Hytale Documentation",
                    description = "Official docs.",
                    body = "<h2>Guides</h2><p>Start here.</p>",
                    extra = "<script>{\"url\":\"/creating-content/asset-packs\"}</script>",
                )
                "/creating-content/asset-packs" -> page(
                    title = "Asset Packs",
                    description = "Modify content.",
                    body = "<h2>Install</h2><p>Put packs in the Mods folder.</p>",
                )
                else -> "missing"
            }.toByteArray()
            val status = if (exchange.requestURI.path in setOf("/", "/creating-content/asset-packs")) 200 else 404
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val cache = Files.createTempDirectory("hyindex-official-docs-cache").toFile()
            val root = OfficialDocsSource.fetchInto(
                cacheRoot = cache,
                patchline = "release",
                log = StdoutLogProvider,
                baseUrl = "http://127.0.0.1:${server.address.port}",
            )

            assertTrue(root.resolve("index.md").isFile)
            assertTrue(root.resolve("creating-content/asset-packs.md").isFile)
            val parsed = DocsParser.parseLocalMarkdown(listOf(root))
            assertTrue(parsed.errors.isEmpty(), parsed.errors.joinToString())
            assertTrue(parsed.chunks.any { it.id.startsWith("official:") && it.content.contains("Mods folder") })
            assertTrue(parsed.chunks.all { it.category == "official" })
        } finally {
            server.stop(0)
        }
    }

    private fun page(
        title: String,
        description: String,
        body: String,
        extra: String = "",
    ): String = """
        <!doctype html><html><head>
        <title>$title | Hytale Docs</title>
        <meta name="description" content="$description" />
        </head><body>
        <span class="docs-version-number">0.6.6</span>
        <article id="nd-page"><h1>$title</h1><p>$description</p><div data-docs-body="true">$body</div></article>
        $extra
        </body></html>
    """.trimIndent()
}
