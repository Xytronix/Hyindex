package com.hyve.knowledge.core.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportDocsSourceTest {
    @Test fun `ships the four support article urls`() {
        assertEquals(4, SupportDocsSource.ARTICLE_URLS.size)
        assertTrue(SupportDocsSource.ARTICLE_URLS.all { it.startsWith("https://support.hytale.com/hc/en-us/articles/") })
    }
    @Test fun `derives a safe markdown filename from an article url`() {
        val name = SupportDocsSource.fileNameFor(
            "https://support.hytale.com/hc/en-us/articles/45326769420827-Hytale-Server-Manual"
        )
        assertTrue(name.endsWith(".md"))
        assertTrue(name.contains("Hytale-Server-Manual"))
        assertTrue(!name.contains("/"))
    }
}
