// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.embedding.EmbeddingProvider
import com.hyindex.knowledge.core.embedding.embedBatched
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.index.ContextualDocumentGroups
import com.hyindex.knowledge.core.index.HnswIndex
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.index.IndexResult
import com.hyindex.knowledge.core.index.SourceChunk
import com.hyindex.knowledge.extraction.JavaChunker
import com.hyindex.knowledge.extraction.JavaExtractor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Paths


class CodeIndexer(private val ctx: IndexContext) {
    private var indexedCount = 0
    private var skipped = false

    fun index(force: Boolean = false): IndexResult {
        val db = ctx.db
        val hashTracker = FileHashTracker(db)
        val decompileDir = ctx.decompileDir
        val indexDir = ctx.indexDir
        indexDir.mkdirs()

        ctx.progress.status("Detecting changes..."); ctx.progress.fraction(0.0)
        val detected = hashTracker.detectChanges(
            sourceDir = decompileDir,
            corpusType = Corpus.CODE.id,
            fileFilter = { it.startsWith("com/hypixel/hytale/") },
        )
        val changes = if (force) {
            FileHashTracker.ChangeSet(
                added = emptySet(),
                changed = detected.currentHashes.keys,
                deleted = detected.deleted,
                unchanged = emptySet(),
                currentHashes = detected.currentHashes,
            )
        } else detected
        ctx.log.info("Changes: +${changes.added.size} ~${changes.changed.size} -${changes.deleted.size} =${changes.unchanged.size}")
        if (!changes.hasChanges && changes.unchanged.isNotEmpty()) {
            skipped = true; ctx.progress.fraction(1.0)
            return IndexResult("code", 0, skipped = true, error = null)
        }
        if (ctx.progress.isCanceled) return IndexResult("code", 0, false, "canceled")

        // Remove stale files' nodes + edges before re-indexing only the delta.
        val stalePaths = changes.changed + changes.deleted
        if (stalePaths.isNotEmpty()) {
            hashTracker.removeHashes(stalePaths, Corpus.CODE.id)
            db.inTransaction { conn ->
                val ps = conn.prepareStatement("DELETE FROM nodes WHERE owning_file = ? AND corpus = 'code'")
                for (path in stalePaths) { ps.setString(1, path); ps.addBatch() }
                ps.executeBatch()
            }
            db.inTransaction { conn ->
                val ps = conn.prepareStatement("DELETE FROM edges WHERE owning_file_id = ?")
                for (path in stalePaths) { ps.setString(1, path); ps.addBatch() }
                ps.executeBatch()
            }
        }

        // Parse only added + changed files.
        val deltaFiles = changes.added + changes.changed
        ctx.progress.status("Parsing ${deltaFiles.size} changed Java file(s)..."); ctx.progress.fraction(0.05)
        val deltaChunks = if (deltaFiles.isEmpty()) emptyList() else JavaChunker.chunkDirectory(
            dir = decompileDir,
            pathFilter = { it in deltaFiles },
            onProgress = { _, idx, total -> ctx.progress.fraction(0.05 + (0.1 * idx / total.coerceAtLeast(1))) },
        )
        ctx.log.info("Parsed ${deltaChunks.size} method chunks from ${deltaFiles.size} changed file(s)")
        if (ctx.progress.isCanceled) return IndexResult("code", 0, false, "canceled")

        if (deltaChunks.isNotEmpty()) {
            db.inTransaction { conn ->
                val ps = conn.prepareStatement(
                    """INSERT OR REPLACE INTO nodes
                       (id, node_type, display_name, file_path, line_start, line_end, content, embedding_text, chunk_index, owning_file, corpus, data_type, metadata)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'code', NULL, ?)"""
                )
                for (chunk in deltaChunks) {
                    val simpleClass = chunk.className.substringAfterLast('.')
                    val displayName = if (chunk.methodName.isBlank()) simpleClass else "$simpleClass#${chunk.methodName}"
                    val embed = chunk.embeddingText
                    ps.setString(1, chunk.id); ps.setString(2, chunk.nodeType)
                    ps.setString(3, displayName)
                    ps.setString(4, chunk.filePath); ps.setInt(5, chunk.lineStart); ps.setInt(6, chunk.lineEnd)
                    ps.setString(7, chunk.content); ps.setString(8, embed); ps.setInt(9, 0)
                    val relPath = File(chunk.filePath).relativeTo(decompileDir).path.replace('\\', '/')
                    ps.setString(10, relPath); ps.setString(11, facetMetadataJson(chunk)); ps.addBatch()
                }
                ps.executeBatch()
            }
            ctx.progress.status("Extracting graph structure..."); ctx.progress.fraction(0.2)
            JavaExtractor.extractAndStore(deltaChunks, db, decompileDir, ctx.log)
        }

        // Rebuild the vector index, FTS and chunk ordinals from the full current corpus.
        val provider = EmbeddingProvider.fromConfig(ctx.config, Corpus.CODE)
        runBlocking { provider.validate() }
        indexedCount = rebuildCodeIndex(provider)

        if (changes.currentHashes.isNotEmpty()) hashTracker.updateHashes(changes.currentHashes, Corpus.CODE.id)
        ctx.progress.fraction(1.0)
        ctx.log.info("Knowledge index built: $indexedCount methods indexed")
        return IndexResult("code", indexedCount, skipped = false, error = null)
    }

    // Rebuild HNSW + FTS + chunk_index from every current code node. Vectors for unchanged
    // nodes come from the shared embedding cache; only genuinely new texts are embedded.
    private class CodeRow(val id: String, val name: String, val body: String, val text: String, val owningFile: String?)

    private fun rebuildCodeIndex(provider: EmbeddingProvider): Int {
        val db = ctx.db
        val indexDir = ctx.indexDir
        val rows = db.query(
            "SELECT id, display_name, content, embedding_text, owning_file FROM nodes WHERE corpus = 'code' AND embedding_text IS NOT NULL ORDER BY id"
        ) { rs -> CodeRow(rs.getString("id"), rs.getString("display_name"), rs.getString("content") ?: "", rs.getString("embedding_text"), rs.getString("owning_file")) }

        if (rows.isEmpty()) {
            FtsTokenizer.populate(db, "code", emptyList(), splitBody = true)
            return 0
        }

        ctx.progress.status("Embedding ${rows.size} code chunk(s)..."); ctx.progress.fraction(0.4)
        val sourceChunks = rows.map { SourceChunk(Corpus.CODE, it.text, owningFile = it.owningFile) }
        val vectors = ContextualDocumentGroups.embedInOrder(sourceChunks, provider, ctx.cache)

        // chunk_index must match the HNSW build order (row order) so ordinal->node lookup stays correct.
        db.inTransaction { conn ->
            val ps = conn.prepareStatement("UPDATE nodes SET chunk_index = ? WHERE id = ?")
            for ((idx, row) in rows.withIndex()) { ps.setInt(1, idx); ps.setString(2, row.id); ps.addBatch() }
            ps.executeBatch()
        }

        ctx.progress.status("Building vector index..."); ctx.progress.fraction(0.8)
        val hnsw = HnswIndex(vectors.first()!!.size)
        hnsw.build(vectors)
        hnsw.save(Paths.get(indexDir.absolutePath, "hnsw", "code.hnsw"))
        hnsw.close()

        FtsTokenizer.populate(db, "code", rows.map { FtsTokenizer.FtsRow(it.id, it.name, it.body) }, splitBody = true)
        return rows.size
    }

    private fun facetMetadataJson(chunk: com.hyindex.knowledge.extraction.MethodChunk): String? {
        val facets = chunk.facets ?: return null
        val annotations = facets.annotations.joinToString(",") { "\"${it}\"" }
        return "{\"visibility\":\"${facets.visibility}\",\"static\":${facets.isStatic}," +
            "\"abstract\":${facets.isAbstract},\"annotations\":[$annotations],\"thin\":${facets.thin}}"
    }
}
