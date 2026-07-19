// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.index

import com.hyve.knowledge.core.db.Corpus
import com.hyve.knowledge.core.embedding.EmbeddingProvider
import com.hyve.knowledge.core.embedding.embedBatched
import com.hyve.knowledge.core.index.HnswIndex
import com.hyve.knowledge.core.index.IndexContext
import com.hyve.knowledge.core.index.IndexResult
import com.hyve.knowledge.extraction.ClientUIParser
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths


class ClientUiIndexer(private val ctx: IndexContext) {
    private var indexedCount = 0
    private var skipped = false

    fun index(force: Boolean = false): IndexResult {
        val clientDataPath = ctx.clientFolder
        if (clientDataPath == null || !clientDataPath.isDirectory) {
            return IndexResult("client", 0, skipped = true, error = null)
        }


        val scanRoot = clientDataPath.parentFile?.takeIf { it.resolve("NodeEditor").isDirectory }
            ?: clientDataPath


        val effectiveForce = force

        val db = ctx.db
        val hashTracker = FileHashTracker(db)
        val indexDir = ctx.indexDir
        indexDir.mkdirs()


        ctx.progress.status("Detecting changes...")
        ctx.progress.fraction(0.0)


        val changes = hashTracker.detectChanges(
            sourceDir = scanRoot,
            corpusType = Corpus.CLIENT.id,
            extensionFilter = setOf("xaml", "ui", "json"),
            fileFilter = { com.hyve.knowledge.extraction.ClientUIParser.isIndexableClientFile(it) },
        )

        ctx.log.info("Changes: +${changes.added.size} ~${changes.changed.size} -${changes.deleted.size} =${changes.unchanged.size}")

        if (!effectiveForce && !changes.hasChanges && changes.unchanged.isNotEmpty()) {
            ctx.log.info("No client UI changes detected, skipping indexing")
            skipped = true
            ctx.progress.fraction(1.0)
            return IndexResult("client", 0, skipped = true, error = null)
        }

        if (ctx.progress.isCanceled) return IndexResult("client", 0, false, "canceled")


        ctx.progress.status("Parsing client UI files...")
        ctx.progress.fraction(0.1)

        val parseResult = ClientUIParser.parseClientData(
            clientDataPath = scanRoot,
            onProgress = { current, total, name ->
                ctx.progress.status(name)
                ctx.progress.fraction(0.1 + (0.1 * current / total.coerceAtLeast(1)))
            },
        )

        if (parseResult.errors.isNotEmpty()) {
            ctx.log.warn("Client UI parse errors (${parseResult.errors.size}): ${parseResult.errors.take(5).joinToString("; ")}")
        }
        ctx.log.info("Parsed ${parseResult.chunks.size} client UI chunks from ${clientDataPath.name}")


        if (ctx.progress.isCanceled) return IndexResult("client", 0, false, "canceled")


        val chunksToEmbed = parseResult.chunks

        if (ctx.progress.isCanceled) return IndexResult("client", 0, false, "canceled")


        val embeddings: List<FloatArray>
        if (chunksToEmbed.isNotEmpty()) {
            ctx.progress.status("Embedding ${chunksToEmbed.size} chunks...")
            ctx.progress.fraction(0.2)

            val provider = EmbeddingProvider.fromConfig(ctx.config, Corpus.CLIENT.embeddingPurpose)
            runBlocking { provider.validate() }

            val texts = chunksToEmbed.map { it.textForEmbedding }
            val cacheService = ctx.cache
            val cacheResult = cacheService.lookup(texts, provider.modelId)

            val uncachedTexts = cacheResult.uncachedIndices.map { texts[it] }
            val newEmbeddings: List<FloatArray> = if (uncachedTexts.isEmpty()) emptyList() else {
                val embedded = runBlocking {
                    provider.embedBatched(
                        uncachedTexts,
                        batchSize = 32,
                        onBatchComplete = { done, total ->
                            ctx.progress.status("Batch $done/$total (${cacheResult.cached.size} cached)")
                            ctx.progress.fraction(0.2 + (0.5 * done / total.coerceAtLeast(1)))
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
        } else {
            embeddings = emptyList()
        }

        if (ctx.progress.isCanceled) return IndexResult("client", 0, false, "canceled")


        ctx.progress.status("Writing index...")
        ctx.progress.fraction(0.7)


        val stalePaths = changes.changed + changes.deleted
        if (stalePaths.isNotEmpty()) {
            hashTracker.removeHashes(stalePaths)
            db.inTransaction { conn ->
                val ps = conn.prepareStatement("DELETE FROM nodes WHERE owning_file = ? AND corpus = ?")
                for (path in stalePaths) {
                    ps.setString(1, path)
                    ps.setString(2, Corpus.CLIENT.id)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        if (chunksToEmbed.isNotEmpty()) {
            db.inTransaction { conn ->
                val ps = conn.prepareStatement(
                    """INSERT OR REPLACE INTO nodes
                       (id, node_type, display_name, file_path, line_start, line_end, content, embedding_text, chunk_index, owning_file, corpus, data_type)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                )
                for ((idx, chunk) in chunksToEmbed.withIndex()) {
                    ps.setString(1, chunk.id)
                    ps.setString(2, chunk.type.id)
                    ps.setString(3, chunk.name)
                    ps.setString(4, chunk.filePath)
                    ps.setNull(5, java.sql.Types.INTEGER)
                    ps.setNull(6, java.sql.Types.INTEGER)
                    ps.setString(7, chunk.content)
                    ps.setString(8, chunk.textForEmbedding)
                    ps.setInt(9, idx)
                    ps.setString(10, chunk.relativePath)
                    ps.setString(11, Corpus.CLIENT.id)
                    ps.setString(12, chunk.category)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            val ftsRows = chunksToEmbed.map { chunk ->
                FtsTokenizer.FtsRow(chunk.id, chunk.name, chunk.content)
            }
            FtsTokenizer.populate(db, Corpus.CLIENT.id, ftsRows, splitBody = false)
        }


        if (changes.currentHashes.isNotEmpty()) {
            hashTracker.updateHashes(changes.currentHashes, Corpus.CLIENT.id)
        }


        ctx.progress.status("Building vector index...")
        ctx.progress.fraction(0.8)

        if (embeddings.isNotEmpty()) {
            val hnswDimension = embeddings.first().size
            val hnsw = HnswIndex(hnswDimension)
            hnsw.build(embeddings)
            val hnswPath = Paths.get(indexDir.absolutePath, "hnsw", Corpus.CLIENT.hnswFileName)
            hnsw.save(hnswPath)
            hnsw.close()
            indexedCount = embeddings.size
        }

        if (ctx.progress.isCanceled) return IndexResult("client", 0, false, "canceled")


        ctx.progress.status("Building UI binding edges...")
        ctx.progress.fraction(0.9)
        buildUIBindsToEdges(db, parseResult.chunks)

        ctx.progress.fraction(1.0)
        ctx.log.info("Client UI index built: $indexedCount files indexed")
        return IndexResult("client", indexedCount, skipped = false, error = null)
    }

    private fun buildUIBindsToEdges(db: com.hyve.knowledge.core.db.KnowledgeDatabase, chunks: List<com.hyve.knowledge.extraction.ClientUIChunk>) {
        if (chunks.isEmpty()) return

        db.execute("DELETE FROM edges WHERE edge_type = 'UI_BINDS_TO' AND source_id LIKE 'ui:%'")

        val extractor = UIBindingExtractor(db)
        val edges = extractor.extractEdges(chunks)
        if (edges.isEmpty()) {
            ctx.log.info("No UI_BINDS_TO edges extracted")
            return
        }

        db.inTransaction { conn ->
            val ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type, target_resolved, metadata) VALUES (?, ?, ?, ?, ?)",
            )
            for (edge in edges) {
                ps.setString(1, edge.sourceId)
                ps.setString(2, edge.targetId)
                ps.setString(3, edge.edgeType)
                ps.setInt(4, if (edge.targetResolved) 1 else 0)
                ps.setString(5, edge.metadata)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        ctx.log.info("Inserted ${edges.size} UI_BINDS_TO edges")
    }
}
