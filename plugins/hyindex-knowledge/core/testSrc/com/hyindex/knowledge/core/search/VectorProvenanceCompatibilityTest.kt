// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.search

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.embedding.EmbeddingCompatibility
import com.hyindex.knowledge.core.embedding.EmbeddingException
import com.hyindex.knowledge.core.index.CorpusIndexManager
import com.hyindex.knowledge.core.index.HnswIndex
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class VectorProvenanceCompatibilityTest {

    @Test
    fun `same-dimension Gemini2 vs Voyage provenance rejects vector query`() {
        val fixture = indexedCode("voyage-code-4")
        try {
            insertProvenance(
                fixture.db,
                family = EmbeddingCompatibility.family("gemini-embedding-2"),
                dimensions = 8,
            )
            val error = assertThrows(EmbeddingException.IncompatibleFamily::class.java) {
                fixture.service.vectorSearch("register command", fixture.db, 5)
            }
            assertTrue(error.message!!.contains("gemini-embedding-2") || error.message!!.contains("voyage-code-4"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `matching provenance family and dimensions allow vector query`() {
        val fixture = indexedCode("voyage-code-4")
        try {
            insertProvenance(
                fixture.db,
                family = EmbeddingCompatibility.family("voyage-code-4"),
                dimensions = 8,
            )
            val results = fixture.service.vectorSearch("register command", fixture.db, 5)
            assertTrue(results.isNotEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy DB without provenance keeps dimension fallback`() {
        val fixture = indexedCode("voyage-code-4")
        try {
            val count = fixture.db.query("SELECT COUNT(*) FROM corpus_provenance") { it.getInt(1) }.first()
            assertEquals(0, count)
            val results = fixture.service.vectorSearch("register command", fixture.db, 5)
            assertTrue(results.isNotEmpty())
        } finally {
            fixture.close()
        }
    }

    private data class Fixture(
        val service: KnowledgeSearchService,
        val db: KnowledgeDatabase,
        val indexManager: CorpusIndexManager,
    ) {
        fun close() {
            indexManager.closeAll()
            db.close()
        }
    }

    private fun indexedCode(codeModel: String): Fixture {
        val indexDir = Files.createTempDirectory("prov_vec_").toFile()
        indexDir.deleteOnExit()
        val config = KnowledgeConfig(
            embeddingProfiles = mapOf("fake" to com.hyindex.knowledge.core.config.EmbeddingProfile("fake", documentModel = codeModel)),
            corpusEmbeddingProfiles = com.hyindex.knowledge.core.db.Corpus.entries.associate { it.id to "fake" },
            indexPath = indexDir.absolutePath,
            hybridEnabled = false,
        )
        val db = KnowledgeDatabase.forFile(java.io.File(indexDir, "knowledge.db"))
        val indexManager = CorpusIndexManager(config)
        val vectors = ArrayList<FloatArray>(4)
        repeat(4) { ordinal ->
            val body = "code-body-$ordinal unique Node$ordinal"
            db.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, content, embedding_text, chunk_index, corpus, data_type) " +
                    "VALUES (?, 'Chunk', ?, ?, ?, ?, ?, 'code', 'JavaMethod')",
                "code:n$ordinal",
                "Node$ordinal",
                "code/Other.java",
                body,
                body,
                ordinal,
            )
            vectors += FloatArray(8) { 1.0f }
        }
        val index = HnswIndex(8)
        index.build(vectors)
        index.save(indexManager.hnswPath(Corpus.CODE))
        index.close()
        val service = KnowledgeSearchService(db, indexManager, config = config)
        return Fixture(service, db, indexManager)
    }

    private fun insertProvenance(db: KnowledgeDatabase, family: String, dimensions: Int) {
        db.execute(
            """
            INSERT INTO corpus_provenance (
                corpus, provider, document_model, query_compatible_family, dimensions, indexed_at
            ) VALUES (?, 'fake', 'voyage-code-4', ?, ?, datetime('now'))
            """.trimIndent(),
            Corpus.CODE.id,
            family,
            dimensions,
        )
    }
}
