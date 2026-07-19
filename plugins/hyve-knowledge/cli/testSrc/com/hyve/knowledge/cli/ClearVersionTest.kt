package com.hyve.knowledge.cli
import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.core.logging.StdoutLogProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
class ClearVersionTest {


    private fun readVersion(dir: java.io.File): String? {
        val m = Class.forName("com.hyve.knowledge.cli.IndexerMainKt")
            .getDeclaredMethod("readRecordedVersion", java.io.File::class.java)
        m.isAccessible = true
        return m.invoke(null, dir) as String?
    }
    private fun wipe(dir: java.io.File) {
        val m = Class.forName("com.hyve.knowledge.cli.IndexerMainKt")
            .getDeclaredMethod("wipeVersionIndex", java.io.File::class.java)
        m.isAccessible = true
        m.invoke(null, dir)
    }
    private fun snapshot(dir: java.io.File, v: String) {
        val m = Class.forName("com.hyve.knowledge.cli.IndexerMainKt").getDeclaredMethod(
            "snapshotVersionIndex", java.io.File::class.java, String::class.java,
            com.hyve.knowledge.core.logging.LogProvider::class.java)
        m.isAccessible = true
        m.invoke(null, dir, v, StdoutLogProvider)
    }
    @Test fun `reads recorded version`() {
        val d = Files.createTempDirectory("hyve-ver").toFile()
        assertNull(readVersion(d), "no meta -> null")
        java.io.File(d, "version_meta.json").writeText("{\n  \"version\": \"0.5.1\"\n}")
        assertEquals("0.5.1", readVersion(d))
    }
    @Test fun `wipe deletes db hnsw and decompiled`() {
        val d = Files.createTempDirectory("hyve-wipe").toFile()
        java.io.File(d, "knowledge.db").writeText("x")
        java.io.File(d, "hnsw").mkdirs()
        java.io.File(d, "decompiled").mkdirs()
        java.io.File(d, "version_meta.json").writeText("{\n  \"version\": \"0.5.1\"\n}")
        wipe(d)
        assertFalse(java.io.File(d, "knowledge.db").exists())
        assertFalse(java.io.File(d, "hnsw").exists())
        assertFalse(java.io.File(d, "decompiled").exists())
    }
    @Test fun `snapshots superseded version so it stays diffable`() {

        val base = Files.createTempDirectory("hyve-snap").toFile()
        val versionDir = java.io.File(base, "versions/release").also { it.mkdirs() }
        val db = KnowledgeDatabase.forFile(java.io.File(versionDir, "knowledge.db"))
        db.execute("INSERT INTO nodes (id, node_type, display_name) VALUES (?, ?, ?)", "n1", "JavaClass", "PlayerEntity")
        db.close()

        snapshot(versionDir, "0.5.4")

        val snap = java.io.File(base, "snapshots/release/0.5.4.json")
        assertTrue(snap.exists(), "snapshot retained for superseded version")
        assertTrue(snap.readText().contains("PlayerEntity"), "snapshot contains the version's nodes")
    }
}
