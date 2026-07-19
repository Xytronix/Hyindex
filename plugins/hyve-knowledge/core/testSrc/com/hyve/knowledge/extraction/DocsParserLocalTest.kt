package com.hyve.knowledge.extraction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class DocsParserLocalTest {
    @Test fun `parses local markdown files into docs chunks`() {
        val root = Files.createTempDirectory("hyve-localdocs").toFile()
        val sub = java.io.File(root, "api").apply { mkdirs() }
        java.io.File(sub, "Foo.md").writeText("# Foo\n\nHandles foo things in the world.\n")
        java.io.File(root, "Bar.mdx").writeText("---\ntitle: Bar\n---\nBar does bar.\n")

        java.io.File(root, "notes.txt").writeText("ignore me")

        val result = DocsParser.parseLocalMarkdown(listOf(root))

        assertTrue(result.errors.isEmpty(), "errors: ${result.errors}")
        assertEquals(2, result.chunks.size, "expected 2 markdown chunks")
        assertTrue(result.chunks.any { it.relativePath.endsWith("Foo.md") })
        assertTrue(result.chunks.all { it.textForEmbedding.isNotBlank() })
    }

    @Test fun `repo guide uses H1 title parent-dir category and stable repo id`() {
        val worktree = Files.createTempDirectory("hyve-worktrees-rel").toFile()
        val root = java.io.File(worktree, "worktrees/release").apply { mkdirs() }
        val readme = java.io.File(root, "HytaleServer/builtin/BuilderTools/README.md")
        readme.parentFile.mkdirs()
        readme.writeText("# Scripted Brushes\n\nAll scripted brushes can be authored visually.\n")

        val result = DocsParser.parseLocalMarkdown(listOf(root))

        assertTrue(result.errors.isEmpty(), "errors: ${result.errors}")
        assertEquals(1, result.chunks.size)
        val chunk = result.chunks.first()
        assertEquals("Scripted Brushes", chunk.title)
        assertEquals("BuilderTools", chunk.category)
        assertEquals("repo:HytaleServer/builtin/BuilderTools/README.md#00-overview", chunk.id)
    }

    @Test fun `splits a multi-section markdown file into per-section chunks`() {
        val root = Files.createTempDirectory("hyve-sections").toFile()
        java.io.File(root, "Mob.md").writeText(
            "Intro line.\n\n## Spawning\n\nHow to spawn.\n\n## Drops\n\nLoot tables.\n"
        )

        val result = DocsParser.parseLocalMarkdown(listOf(root))

        assertTrue(result.errors.isEmpty(), "errors: ${result.errors}")
        assertEquals(3, result.chunks.size, "expected overview + 2 heading sections")
        val spawning = result.chunks.first { it.id.endsWith("#01-spawning") }
        assertTrue(spawning.content.contains("Spawning"))
        assertTrue(spawning.content.contains("How to spawn"))
        assertTrue(!spawning.content.contains("Loot tables"))
        val overview = result.chunks.first { it.id.endsWith("#00-overview") }
        assertTrue(overview.content.contains("Intro line"))
        val hashes = result.chunks.map { it.fileHash }.toSet()
        assertEquals(3, hashes.size, "each section hashes its own text")
    }

    @Test fun `a heading inside a fenced code block does not start a new section`() {
        val body = "## Usage\n\nRun it like this:\n\n```sh\n## not a heading\necho hi\n```\n\nDone.\n"

        val sections = DocsParser.splitMarkdownSections(body)

        assertEquals(1, sections.size, "fenced ## must not split the section")
        val sec = sections.first()
        assertEquals("Usage", sec.heading)
        assertTrue(sec.body.contains("## not a heading"))
        assertTrue(sec.body.contains("echo hi"))
        assertTrue(sec.body.contains("Done."))
    }

    @Test fun `a headingless single-paragraph file yields exactly one chunk`() {
        val root = Files.createTempDirectory("hyve-onesection").toFile()
        java.io.File(root, "Note.md").writeText("Just one paragraph of prose.\n")

        val result = DocsParser.parseLocalMarkdown(listOf(root))

        assertTrue(result.errors.isEmpty(), "errors: ${result.errors}")
        assertEquals(1, result.chunks.size)
        assertTrue(result.chunks.first().id.endsWith("#00-overview"))
    }

    @Test fun `walk skips DocGen site and target paths`() {
        val root = Files.createTempDirectory("hyve-skip").toFile()
        val keep = java.io.File(root, "HytaleServer/builtin/HOWTO.md")
        keep.parentFile.mkdirs()
        keep.writeText("# How To\n\nKeep me.\n")
        for (rel in listOf(
            "HytaleServer/Tools/Standalone/DocGen/src/main/resources/index.md",
            "HytaleServer/site/generated.md",
            "HytaleServer/target/generated.md",
        )) {
            val f = java.io.File(root, rel)
            f.parentFile.mkdirs()
            f.writeText("# Skip\n\nSkip me.\n")
        }

        val result = DocsParser.parseLocalMarkdown(listOf(root))

        assertEquals(1, result.chunks.size, "only HOWTO.md should be kept")
        assertTrue(result.chunks.first().relativePath.endsWith("HOWTO.md"))
    }
}
