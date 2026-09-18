package com.hyindex.knowledge.core.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class CanonicalRootsTest {
    @Test
    fun `walkSafeFiles skips outbound file symlinks and dir links`() {
        val root = Files.createTempDirectory("canon-root").toFile()
        val outside = Files.createTempDirectory("canon-out").toFile()
        val secret = java.io.File(outside, "secret.java").apply { writeText("class Secret {}") }
        java.io.File(root, "Ok.java").writeText("class Ok {}")
        Files.createSymbolicLink(java.io.File(root, "leak.java").toPath(), secret.toPath())
        val nested = java.io.File(root, "sub").apply { mkdirs() }
        Files.createSymbolicLink(java.io.File(nested, "out.java").toPath(), secret.toPath())
        val linkedDir = java.io.File(outside, "dir").apply { mkdirs() }
        java.io.File(linkedDir, "Hidden.java").writeText("class Hidden {}")
        Files.createSymbolicLink(java.io.File(root, "escape").toPath(), linkedDir.toPath())

        val names = CanonicalRoots.walkSafeFiles(root).map { it.name }.toSet()
        assertEquals(setOf("Ok.java"), names)
        assertFalse(CanonicalRoots.isSafeRegularFile(java.io.File(root, "leak.java"), root))
        assertTrue(CanonicalRoots.isSafeRegularFile(java.io.File(root, "Ok.java"), root))
        root.deleteRecursively()
        outside.deleteRecursively()
    }
}
