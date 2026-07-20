package com.hyindex.knowledge.core.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlogDocsSourceTest {
    @Test fun `extracts and absolutizes news post urls from index html`() {
        val html = """
            <html><body>
            <a href="/news/2026/5/pre-release-patch-notes-update-6">Update 6</a>
            <a href="/news/2026/3/hytale-patch-notes-update-4">Update 4</a>
            <a href="/news/2026/5/pre-release-patch-notes-update-6">dup</a>
            <a href="/news">Blog</a>
            <a href="/about">About</a>
            <a href="https://hytale.com/news/2026/1/hotfixes-january-2026">hotfix</a>
            </body></html>
        """.trimIndent()
        val urls = BlogDocsSource.postUrls(html)
        assertTrue(urls.contains("https://hytale.com/news/2026/5/pre-release-patch-notes-update-6"))
        assertTrue(urls.contains("https://hytale.com/news/2026/3/hytale-patch-notes-update-4"))
        assertTrue(urls.contains("https://hytale.com/news/2026/1/hotfixes-january-2026"))

        assertEquals(urls.size, urls.distinct().size)
        assertTrue(urls.none { it.endsWith("/news") || it.endsWith("/about") })
    }
    @Test fun `fileNameFor derives safe md name`() {
        val n = BlogDocsSource.fileNameFor("https://hytale.com/news/2026/5/pre-release-patch-notes-update-6")
        assertTrue(n.endsWith(".md") && n.contains("pre-release-patch-notes-update-6") && !n.contains("/"))
    }
}
