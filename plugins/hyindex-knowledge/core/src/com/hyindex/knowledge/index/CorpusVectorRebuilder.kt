// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.CorpusProvenanceService
import com.hyindex.knowledge.core.embedding.EmbeddingProvider
import com.hyindex.knowledge.core.index.ContextualDocumentGroups
import com.hyindex.knowledge.core.index.HnswIndex
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.index.IndexResult
import com.hyindex.knowledge.core.index.SourceChunk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class CorpusVectorRebuilder(private val ctx: IndexContext) {
    fun rebuild(corpus: Corpus): IndexResult {
        require(corpus != Corpus.VISUAL) { "visual re-embedding requires the original media bytes" }
        val rows = ctx.db.query(
            """SELECT id, embedding_text, owning_file, file_path
               FROM nodes
               WHERE corpus = ? AND embedding_text IS NOT NULL
               ORDER BY id""",
            corpus.id,
        ) { result ->
            Row(
                id = result.getString("id"),
                text = result.getString("embedding_text"),
                owningFile = result.getString("owning_file"),
                filePath = result.getString("file_path"),
            )
        }
        if (rows.isEmpty()) return IndexResult(corpus.id, 0, skipped = true, error = null)

        val provider = EmbeddingProvider.fromConfig(ctx.config, corpus)
        runBlocking { provider.validate() }
        val chunks = rows.map { row ->
            SourceChunk(
                corpus = corpus,
                text = row.text,
                owningFile = row.owningFile,
                relativePath = row.filePath,
                filePath = row.filePath,
            )
        }
        ctx.progress.status("Re-embedding ${rows.size} ${corpus.id} chunk(s)...")
        ctx.progress.fraction(0.2)
        val vectors = ContextualDocumentGroups.embedInOrder(chunks, provider, ctx.cache)
        require(vectors.size == rows.size) { "${corpus.id} embeddings ${vectors.size} != rows ${rows.size}" }

        ctx.progress.status("Building ${corpus.id} vector index...")
        ctx.progress.fraction(0.8)
        val dest = Paths.get(ctx.indexDir.absolutePath, "hnsw", corpus.hnswFileName)
        dest.parent?.toFile()?.mkdirs()
        val staging = dest.resolveSibling(corpus.hnswFileName + ".staging")
        val backup = dest.resolveSibling(corpus.hnswFileName + ".backup")
        val hnsw = HnswIndex(vectors.first().size)
        try {
            hnsw.build(vectors)
            Files.deleteIfExists(staging)
            hnsw.save(staging)
        } finally {
            hnsw.close()
        }
        val verify = HnswIndex(vectors.first().size)
        try {
            verify.load(staging)
            require(verify.size() == vectors.size) { "staging HNSW size ${verify.size()} != ${vectors.size}" }
        } finally {
            verify.close()
        }

        val hadDest = Files.exists(dest)
        if (hadDest) {
            Files.deleteIfExists(backup)
            Files.move(dest, backup, StandardCopyOption.REPLACE_EXISTING)
        }
        try {
            try {
                Files.move(staging, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(staging, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            ctx.db.inTransaction { connection ->
                connection.prepareStatement("UPDATE nodes SET chunk_index = ? WHERE id = ?").use { statement ->
                    rows.forEachIndexed { index, row ->
                        statement.setInt(1, index)
                        statement.setString(2, row.id)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            Files.deleteIfExists(backup)
        } catch (error: Exception) {
            if (hadDest && Files.exists(backup)) {
                Files.move(backup, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            throw error
        }

        CorpusProvenanceService(ctx).record(corpus)
        ctx.progress.fraction(1.0)
        return IndexResult(corpus.id, rows.size, skipped = false, error = null)
    }

    private data class Row(
        val id: String,
        val text: String,
        val owningFile: String?,
        val filePath: String?,
    )
}
