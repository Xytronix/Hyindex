// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.CorpusProvenanceService
import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.HnswIndex
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.NoopProgressReporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CorpusVectorRebuilderTest {

    @Test
    fun `reembed rebuilds an existing text corpus without source files or deleting other corpora`() {
        val base = Files.createTempDirectory("hyindex-reembed-text").toFile()
        val config = KnowledgeConfig(embeddingProfiles = mapOf("fake" to com.hyindex.knowledge.core.config.EmbeddingProfile("fake", documentModel = "fake")), corpusEmbeddingProfiles = com.hyindex.knowledge.core.db.Corpus.entries.associate { it.id to "fake" }, indexPath = base.absolutePath,
        activeVersion = "release_test",)
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(File(config.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(
            EmbeddingCacheDatabase.forFile(File(base, "embedding-cache.db"), log),
            log,
        )
        fun insert(id: String, corpus: String, filePath: String, chunk: Int?) {
            db.execute(
                "INSERT INTO nodes (id,node_type,display_name,file_path,content,embedding_text,chunk_index,corpus) " +
                    "VALUES (?, 'Chunk', ?, ?, ?, ?, ?, ?)",
                id, id, filePath, "body $id", "embedding $id", chunk, corpus,
            )
        }
        insert("docs:a", "docs", "guide.md", 90)
        insert("docs:b", "docs", "guide.md", 91)
        insert("code:keep", "code", "Keep.java", 7)

        val context = IndexContext(config, db, cache, log, NoopProgressReporter)
        val result = CorpusVectorRebuilder(context).rebuild(Corpus.DOCS)

        assertTrue(result.ok)
        assertEquals(2, result.indexed)
        val ordinals = db.query("SELECT chunk_index FROM nodes WHERE corpus='docs' ORDER BY id") { it.getInt(1) }
        assertEquals(listOf(0, 1), ordinals)
        assertEquals(7, db.query("SELECT chunk_index FROM nodes WHERE id='code:keep'") { it.getInt(1) }.single())
        val dest = context.indexDir.toPath().resolve("hnsw/docs.hnsw")
        val index = HnswIndex(8)
        index.load(dest)
        assertEquals(2, index.size())
        index.close()
        assertFalse(dest.resolveSibling("docs.hnsw.staging").toFile().exists())
        val prov = CorpusProvenanceService(context).load(Corpus.DOCS)
        assertEquals("docs", prov!!.corpus)
        assertEquals("fake", prov.provider)
        db.close()
    }

}
