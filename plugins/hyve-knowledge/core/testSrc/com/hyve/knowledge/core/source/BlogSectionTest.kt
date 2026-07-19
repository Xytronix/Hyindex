package com.hyve.knowledge.core.source
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class BlogSectionTest {
    @Test fun `splits article into heading sections with intro`() {
        val html = """
            <article><p>Update overview text.</p>
            <h2>Update 6, Part 1</h2><p>Block Palette Presets added.</p>
            <h2>Hotfixes</h2><p>Grass no longer spreads under water.</p></article>
        """.trimIndent()
        val secs = BlogDocsSource.splitSections(html)
        assertTrue(secs.size >= 3, "expected intro + 2 sections, got ${secs.size}")
        assertEquals("Overview", secs.first().title)
        assertTrue(secs.any { it.title.contains("Part 1") && it.text.contains("Block Palette") })
        assertTrue(secs.any { it.title.contains("Hotfixes") && it.text.contains("Grass") })
    }
    @Test fun `no headings yields single section`() {
        val secs = BlogDocsSource.splitSections("<p>Just one blob of text.</p>")
        assertEquals(1, secs.size); assertTrue(secs[0].text.contains("one blob"))
    }
    @Test fun `bot-check only article yields no sections`() {
        val html = "<h2>Security check</h2><p>Checking your browser before accessing… Ray ID: abc</p>"
        val secs = BlogDocsSource.splitSections(html)
        assertTrue(secs.isEmpty(), "expected no sections but got $secs")
    }
    @Test fun `real section is kept alongside filtered bot-check section`() {
        val html = """
            <h2>Security check</h2><p>Checking your browser before accessing… Ray ID: abc</p>
            <h2>Update Notes</h2><p>Block Palette Presets added.</p>
        """.trimIndent()
        val secs = BlogDocsSource.splitSections(html)
        assertEquals(1, secs.size, "expected 1 real section, got $secs")
        assertTrue(secs[0].title.contains("Update Notes"))
    }
}
