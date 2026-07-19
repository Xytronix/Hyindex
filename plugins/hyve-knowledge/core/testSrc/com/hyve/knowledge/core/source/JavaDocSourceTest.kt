package com.hyve.knowledge.core.source

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JavaDocSourceTest {
    @Test fun `extracts readable text from a javadoc class page`() {
        val html = """
            <html><body><main><h1 class="title">Class Foo</h1>
            <div class="block">Handles foo things in the world.</div>
            <section class="method-details"><h3>doThing</h3>
            <div class="block">Does the thing.</div></section></main></body></html>
        """.trimIndent()
        val text = JavaDocSource.htmlToText(html)
        assertTrue(text.contains("Class Foo"))
        assertTrue(text.contains("Handles foo things"))
        assertTrue(text.contains("doThing"))
    }

    @Test fun `strips script and style blocks`() {
        val html = "<html><head><style>.x{color:red}</style></head><body><script>var a=1;</script><p>Hello world</p></body></html>"
        val text = JavaDocSource.htmlToText(html)
        assertTrue(text.contains("Hello world"))
        assertTrue(!text.contains("color:red") && !text.contains("var a"))
    }
}
