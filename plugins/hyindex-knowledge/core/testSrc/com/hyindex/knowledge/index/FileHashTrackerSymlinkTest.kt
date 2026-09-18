package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files

class FileHashTrackerSymlinkTest {
    @Test
    fun `detectChanges does not hash outbound secret files`() {
        val base = Files.createTempDirectory("hash-root").toFile()
        val outside = Files.createTempDirectory("hash-out").toFile()
        val secret = java.io.File(outside, "secret.java").apply { writeText("class Secret {}") }
        java.io.File(base, "Ok.java").writeText("class Ok {}")
        Files.createSymbolicLink(java.io.File(base, "leak.java").toPath(), secret.toPath())
        val db = KnowledgeDatabase.forFile(java.io.File(base, "k.db"), StdoutLogProvider)
        val changes = FileHashTracker(db).detectChanges(base, corpusType = "code", extensionFilter = setOf("java"))
        assertEquals(setOf("Ok.java"), changes.added)
        assertFalse(changes.currentHashes.keys.any { it.contains("leak") || it.contains("secret") })
        db.close()
        base.deleteRecursively()
        outside.deleteRecursively()
    }
}
