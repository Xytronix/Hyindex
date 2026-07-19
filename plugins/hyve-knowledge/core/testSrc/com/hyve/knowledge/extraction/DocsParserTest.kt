package com.hyve.knowledge.extraction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DocsParserTest {

    @Test fun `H3 section embedding text carries parent H2 breadcrumb but content stays clean`(@TempDir dir: File) {
        val md = """
            # Doc Title

            ## Installation

            Top level body.

            ### Steps

            Do the thing.
        """.trimIndent()
        File(dir, "guide.md").writeText(md)

        val result = DocsParser.parseLocalMarkdown(listOf(dir))
        val h3 = result.chunks.first { it.content.startsWith("## Steps") }

        assertTrue(h3.textForEmbedding.contains("Installation"), "H3 embedding should name parent H2")
        assertFalse(h3.content.contains("Installation"), "H3 content must not contain the breadcrumb")
    }

    @Test fun `H2 section embedding text has no parent breadcrumb`(@TempDir dir: File) {
        val md = """
            # Doc Title

            ## Installation

            Top level body.
        """.trimIndent()
        File(dir, "guide.md").writeText(md)

        val result = DocsParser.parseLocalMarkdown(listOf(dir))
        val h2 = result.chunks.first { it.content.startsWith("## Installation") }

        assertFalse(h2.textForEmbedding.contains("Parent:"), "H2 should not get a parent breadcrumb")
    }

    @Test fun `invalid repo slug returns an error without network`() {
        val result = DocsParser.parseDocs(repoSlug = "no-slash-here", branch = "main", locale = "en")
        assertTrue(result.errors.isNotEmpty(), "expected an error for invalid repo slug")
        assertTrue(result.chunks.isEmpty())
    }

    @Test fun `extractPublishedDate parses news path with year month day`() {
        val date = DocsParser.extractPublishedDate(null, "content/blog/news/2025/3/14/update.md", "body")
        assertEquals("2025-03-14", date)
    }

    @Test fun `extractPublishedDate parses news path with only year month`() {
        val date = DocsParser.extractPublishedDate(null, "news/2024/11/post.mdx", "body")
        assertEquals("2024-11-01", date)
    }

    @Test fun `extractPublishedDate prefers frontmatter date`() {
        val date = DocsParser.extractPublishedDate("2026-01-09", "news/2024/11/post.md", "body")
        assertEquals("2026-01-09", date)
    }

    @Test fun `extractPublishedDate returns null when no date present`() {
        assertNull(DocsParser.extractPublishedDate(null, "docs/guide.md", "no date here"))
    }
}
