// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.index

import com.hyve.knowledge.core.db.EmbeddingPurpose
import com.hyve.knowledge.core.embedding.EmbeddingProvider
import com.hyve.knowledge.core.embedding.embedBatched
import com.hyve.knowledge.core.index.HnswIndex
import com.hyve.knowledge.core.index.IndexContext
import com.hyve.knowledge.core.index.IndexResult
import com.hyve.knowledge.extraction.JavaChunker
import com.hyve.knowledge.extraction.JavaExtractor
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
            corpusType = "java",
            fileFilter = { it.startsWith("com/hypixel/hytale/") },
        )


        val changes = if (force) {
            FileHashTracker.ChangeSet(
                added = emptySet(),
                changed = detected.currentHashes.keys,
                deleted = emptySet(),
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

        ctx.progress.status("Parsing decompiled Java files..."); ctx.progress.fraction(0.05)
        val allChunks = JavaChunker.chunkDirectory(
            dir = decompileDir,
            pathFilter = { it.startsWith("com/hypixel/hytale/") },
            onProgress = { _, idx, total ->
                ctx.progress.fraction(0.05 + (0.1 * idx / total.coerceAtLeast(1)))
            },
        )
        ctx.log.info("Parsed ${allChunks.size} method chunks from ${decompileDir.name}")
        if (ctx.progress.isCanceled) return IndexResult("code", 0, false, "canceled")


        val chunksToEmbed = allChunks

        val embeddings: List<FloatArray>
        if (chunksToEmbed.isEmpty()) {
            embeddings = emptyList()
        } else {
            ctx.progress.status("Embedding ${chunksToEmbed.size} chunks..."); ctx.progress.fraction(0.15)
            val provider = EmbeddingProvider.fromConfig(ctx.config, EmbeddingPurpose.CODE)
            runBlocking { provider.validate() }
            val texts = chunksToEmbed.map { it.embeddingText }
            val cacheService = ctx.cache
            val cacheResult = cacheService.lookup(texts, provider.modelId)
            val uncachedTexts = cacheResult.uncachedIndices.map { texts[it] }
            val newEmbeddings: List<FloatArray> = if (uncachedTexts.isEmpty()) emptyList() else {
                val embedded = runBlocking {
                    provider.embedBatched(
                        uncachedTexts,
                        batchSize = 32,
                        onBatchComplete = { done, total ->
                            ctx.progress.fraction(0.15 + (0.55 * done / total.coerceAtLeast(1)))
                        },
                    )
                }
                cacheService.store(uncachedTexts, embedded, provider.modelId)
                embedded
            }
            val merged = arrayOfNulls<FloatArray>(texts.size)
            for ((idx, vec) in cacheResult.cached) { merged[idx] = vec }
            for ((i, origIdx) in cacheResult.uncachedIndices.withIndex()) { merged[origIdx] = newEmbeddings[i] }
            embeddings = merged.map { it!! }
        }
        if (ctx.progress.isCanceled) return IndexResult("code", 0, false, "canceled")

        ctx.progress.status("Writing index..."); ctx.progress.fraction(0.7)
        val stalePaths = changes.changed + changes.deleted
        if (stalePaths.isNotEmpty()) {
            hashTracker.removeHashes(stalePaths)
            db.inTransaction { conn ->
                val ps = conn.prepareStatement("DELETE FROM nodes WHERE owning_file = ?")
                for (path in stalePaths) { ps.setString(1, path); ps.addBatch() }
                ps.executeBatch()
            }
            db.inTransaction { conn ->
                val ps = conn.prepareStatement("DELETE FROM edges WHERE owning_file_id = ?")
                for (path in stalePaths) { ps.setString(1, path); ps.addBatch() }
                ps.executeBatch()
            }
        }
        if (chunksToEmbed.isNotEmpty()) {
            db.inTransaction { conn ->
                val ps = conn.prepareStatement(
                    """INSERT OR REPLACE INTO nodes
                       (id, node_type, display_name, file_path, line_start, line_end, content, embedding_text, chunk_index, owning_file, corpus, data_type, metadata)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'code', NULL, ?)"""
                )
                for ((idx, chunk) in chunksToEmbed.withIndex()) {
                    val simpleClass = chunk.className.substringAfterLast('.')
                    val displayName = if (chunk.methodName.isBlank()) simpleClass else "$simpleClass#${chunk.methodName}"
                    ps.setString(1, chunk.id); ps.setString(2, chunk.nodeType)
                    ps.setString(3, displayName)
                    ps.setString(4, chunk.filePath); ps.setInt(5, chunk.lineStart); ps.setInt(6, chunk.lineEnd)
                    ps.setString(7, chunk.content); ps.setString(8, chunk.embeddingText); ps.setInt(9, idx)
                    val relPath = File(chunk.filePath).relativeTo(decompileDir).path.replace('\\', '/')
                    ps.setString(10, relPath); ps.setString(11, facetMetadataJson(chunk)); ps.addBatch()


                    if ((idx + 1) % 2000 == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
            val ftsRows = chunksToEmbed.map { chunk ->
                val simpleClass = chunk.className.substringAfterLast('.')
                val displayName = if (chunk.methodName.isBlank()) simpleClass else "$simpleClass#${chunk.methodName}"
                FtsTokenizer.FtsRow(chunk.id, displayName, chunk.content)
            }
            FtsTokenizer.populate(db, "code", ftsRows, splitBody = true)
        }
        if (changes.currentHashes.isNotEmpty()) hashTracker.updateHashes(changes.currentHashes)

        ctx.progress.status("Building vector index..."); ctx.progress.fraction(0.8)
        if (embeddings.isNotEmpty()) {
            val hnsw = HnswIndex(embeddings.first().size)
            hnsw.build(embeddings)
            hnsw.save(Paths.get(indexDir.absolutePath, "hnsw", "code.hnsw"))
            hnsw.close()
            indexedCount = embeddings.size
        }

        ctx.progress.status("Extracting graph structure..."); ctx.progress.fraction(0.9)
        JavaExtractor.extractAndStore(allChunks, db, decompileDir, ctx.log)
        ctx.progress.fraction(1.0)
        val fallbackCount = allChunks.count { it.nodeType == "JavaFile" }
        if (fallbackCount > 0) {
            ctx.log.info("Code index: $fallbackCount file(s) could not be parsed; indexed as file-level fallback nodes")
        }
        val typeOnlyCount = allChunks.count { it.nodeType == "JavaType" }
        if (typeOnlyCount > 0) {
            ctx.log.info("Code index: $typeOnlyCount type(s) had no methods; indexed as type-level nodes")
        }
        ctx.log.info("Knowledge index built: $indexedCount methods indexed")
        return IndexResult("code", indexedCount, skipped = false, error = null)
    }

    private fun facetMetadataJson(chunk: com.hyve.knowledge.extraction.MethodChunk): String? {
        val facets = chunk.facets ?: return null
        val annotations = facets.annotations.joinToString(",") { "\"${it}\"" }
        return "{\"visibility\":\"${facets.visibility}\",\"static\":${facets.isStatic}," +
            "\"abstract\":${facets.isAbstract},\"annotations\":[$annotations],\"thin\":${facets.thin}}"
    }
}
