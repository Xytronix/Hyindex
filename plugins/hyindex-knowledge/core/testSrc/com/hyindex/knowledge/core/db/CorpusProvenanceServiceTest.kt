// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.db

import com.hyindex.knowledge.core.config.EmbeddingProfile
import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.embedding.EmbeddingCompatibility
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.NoopProgressReporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class CorpusProvenanceServiceTest {
    @Test
    fun `record stores minimal resolved profile fingerprint`() {
        val env = environment(
            KnowledgeConfig(
                embeddingProfiles = mapOf(
                    "code" to EmbeddingProfile(
                        provider = "fake",
                        documentModel = "voyage-code-4",
                        queryModel = "voyage-context-4",
                        dimensions = 8,
                    ),
                ),
                corpusEmbeddingProfiles = mapOf("code" to "code"),
            ),
        )
        env.db.execute(
            "INSERT INTO nodes (id,node_type,display_name,content,embedding_text,corpus) " +
                "VALUES ('code:a','Chunk','A','class A {}','embed A','code')",
        )
        CorpusProvenanceService(env.ctx).record(Corpus.CODE)
        val row = CorpusProvenanceService(env.ctx).load(Corpus.CODE)!!
        assertEquals("code", row.corpus)
        assertEquals("fake", row.provider)
        assertEquals("voyage-code-4", row.documentModel)
        assertEquals(EmbeddingCompatibility.VOYAGE_4_FAMILY, row.queryCompatibleFamily)
        assertEquals(8, row.dimensions)
        assertNotNull(row.indexedAt)
        env.close()
    }

    @Test
    fun `empty corpus removes stale provenance without constructing provider`() {
        val env = environment(
            KnowledgeConfig(corpusEmbeddingProfiles = mapOf("code" to "missing")),
        )
        env.db.execute(
            """INSERT INTO corpus_provenance
               (corpus,provider,document_model,query_compatible_family,dimensions,indexed_at)
               VALUES ('code','fake','old','old',8,datetime('now'))""",
        )
        CorpusProvenanceService(env.ctx).record(Corpus.CODE)
        assertNull(CorpusProvenanceService(env.ctx).load(Corpus.CODE))
        env.close()
    }

    @Test
    fun `incompatible document and query profile is rejected`() {
        val env = environment(
            KnowledgeConfig(
                embeddingProfiles = mapOf(
                    "bad" to EmbeddingProfile(
                        provider = "fake",
                        documentModel = "voyage-code-4",
                        queryModel = "gemini-embedding-2",
                        dimensions = 8,
                    ),
                ),
                corpusEmbeddingProfiles = mapOf("code" to "bad"),
            ),
        )
        env.db.execute(
            "INSERT INTO nodes (id,node_type,display_name,content,embedding_text,corpus) " +
                "VALUES ('code:bad','Chunk','bad','body','body','code')",
        )
        assertThrows(Exception::class.java) { CorpusProvenanceService(env.ctx).record(Corpus.CODE) }
        env.close()
    }

    private fun environment(config: KnowledgeConfig): Env {
        val base = Files.createTempDirectory("hyindex-min-prov").toFile()
        val cfg = config.copy(indexPath = base.absolutePath, activeVersion = "test")
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), StdoutLogProvider)
        val cache = EmbeddingCacheService(
            EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), StdoutLogProvider),
            StdoutLogProvider,
        )
        return Env(db, IndexContext(cfg, db, cache, StdoutLogProvider, NoopProgressReporter))
    }

    private data class Env(val db: KnowledgeDatabase, val ctx: IndexContext) {
        fun close() = db.close()
    }
}
