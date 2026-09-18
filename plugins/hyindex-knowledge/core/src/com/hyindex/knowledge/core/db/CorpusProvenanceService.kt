// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.db

import com.hyindex.knowledge.core.embedding.EmbeddingCompatibility
import com.hyindex.knowledge.core.embedding.EmbeddingProvider
import com.hyindex.knowledge.core.embedding.EmbeddingRole
import com.hyindex.knowledge.core.index.IndexContext

class CorpusProvenanceService(private val ctx: IndexContext) {
    fun record(corpus: Corpus) {
        val count = ctx.db.query(
            "SELECT COUNT(*) FROM nodes WHERE corpus = ?",
            corpus.id,
        ) { it.getInt(1) }.firstOrNull() ?: 0
        if (count == 0) {
            ctx.db.execute("DELETE FROM corpus_provenance WHERE corpus = ?", corpus.id)
            return
        }

        val profile = ctx.config.resolvedEmbeddingProfile(corpus)
        EmbeddingProvider.requireCompatibleModels(profile.documentModel, profile.queryModel)
        val provider = EmbeddingProvider.fromConfig(ctx.config, corpus, EmbeddingRole.DOCUMENT)
        ctx.db.execute(
            """INSERT INTO corpus_provenance
               (corpus, provider, document_model, query_compatible_family, dimensions, indexed_at)
               VALUES (?, ?, ?, ?, ?, datetime('now'))
               ON CONFLICT(corpus) DO UPDATE SET
                 provider=excluded.provider,
                 document_model=excluded.document_model,
                 query_compatible_family=excluded.query_compatible_family,
                 dimensions=excluded.dimensions,
                 indexed_at=excluded.indexed_at""",
            corpus.id,
            profile.provider.trim().lowercase(),
            profile.documentModel,
            EmbeddingCompatibility.family(profile.queryModel),
            provider.dimension,
        )
    }

    fun load(corpus: Corpus): CorpusProvenance? = ctx.db.query(
        """SELECT corpus, provider, document_model, query_compatible_family, dimensions, indexed_at
           FROM corpus_provenance WHERE corpus = ?""",
        corpus.id,
    ) { rs ->
        CorpusProvenance(
            corpus = rs.getString("corpus"),
            provider = rs.getString("provider"),
            documentModel = rs.getString("document_model"),
            queryCompatibleFamily = rs.getString("query_compatible_family"),
            dimensions = rs.getInt("dimensions"),
            indexedAt = rs.getString("indexed_at"),
        )
    }.firstOrNull()
}
