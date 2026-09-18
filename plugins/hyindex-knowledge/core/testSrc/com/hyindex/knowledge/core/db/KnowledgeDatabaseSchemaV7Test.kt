// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.db

import com.hyindex.knowledge.core.logging.StdoutLogProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.nio.file.Files

class KnowledgeDatabaseSchemaV7Test {
    @Test
    fun `fresh database has minimal provenance and composite file hashes`() {
        val file = Files.createTempDirectory("hyindex-v7").resolve("knowledge.db").toFile()
        val db = KnowledgeDatabase.forFile(file, StdoutLogProvider)
        val columns = db.query("PRAGMA table_info(corpus_provenance)") { it.getString("name") }.toSet()
        assertEquals(
            setOf("corpus", "provider", "document_model", "query_compatible_family", "dimensions", "indexed_at"),
            columns,
        )
        val keys = db.query("PRAGMA table_info(file_hashes)") {
            it.getString("name") to it.getInt("pk")
        }.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }
        assertEquals(listOf("corpus_type", "file_path"), keys)
        assertEquals(7, db.query("SELECT MAX(version) FROM schema_version") { it.getInt(1) }.single())
        db.close()
    }

    @Test
    fun `v7 normalizes java hashes and permits same path in another corpus`() {
        val file = Files.createTempDirectory("hyindex-v7-upgrade").resolve("knowledge.db").toFile()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)")
                stmt.executeUpdate(
                    "CREATE TABLE nodes (id TEXT PRIMARY KEY,node_type TEXT NOT NULL,display_name TEXT NOT NULL,corpus TEXT NOT NULL DEFAULT 'code',chunk_index INTEGER)",
                )
                stmt.executeUpdate(
                    "CREATE TABLE file_hashes (file_path TEXT PRIMARY KEY,file_hash TEXT NOT NULL,corpus_type TEXT NOT NULL DEFAULT 'java',indexed_at TEXT NOT NULL DEFAULT (datetime('now')))",
                )
                stmt.executeUpdate("INSERT INTO schema_version VALUES (6, datetime('now'))")
                stmt.executeUpdate("INSERT INTO file_hashes (file_path,file_hash,corpus_type) VALUES ('same.txt','a','java')")
            }
        }
        val db = KnowledgeDatabase.forFile(file, StdoutLogProvider)
        db.execute("INSERT INTO file_hashes (corpus_type,file_path,file_hash) VALUES ('docs','same.txt','b')")
        val rows = db.query("SELECT corpus_type,file_hash FROM file_hashes WHERE file_path='same.txt' ORDER BY corpus_type") {
            it.getString(1) to it.getString(2)
        }
        assertEquals(listOf("code" to "a", "docs" to "b"), rows)
        db.close()
    }

    @Test
    fun `opening migrated database is idempotent`() {
        val file = Files.createTempDirectory("hyindex-v7-idem").resolve("knowledge.db").toFile()
        KnowledgeDatabase.forFile(file, StdoutLogProvider).close()
        KnowledgeDatabase.forFile(file, StdoutLogProvider).use { db ->
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name='corpus_provenance'") { it.getString(1) }.isNotEmpty())
        }
    }
}
