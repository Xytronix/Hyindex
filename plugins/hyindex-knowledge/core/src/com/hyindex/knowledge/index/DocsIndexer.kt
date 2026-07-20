// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.embedding.EmbeddingProvider
import com.hyindex.knowledge.core.embedding.embedBatched
import com.hyindex.knowledge.core.index.HnswIndex
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.index.IndexResult
import com.hyindex.knowledge.extraction.DocsChunk
import com.hyindex.knowledge.extraction.DocsParser
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Paths


class DocsIndexer(
    private val ctx: IndexContext,
    private val docRoots: List<File> = emptyList(),
    private val includeGithubDocs: Boolean = true,
) {
    private var indexedCount = 0
    private var skipped = false

    fun index(): IndexResult {
        val db = ctx.db
        val hashTracker = FileHashTracker(db)
        val indexDir = ctx.indexDir
        indexDir.mkdirs()


        ctx.progress.status("Fetching docs...")
        ctx.progress.fraction(0.0)

        val allChunks = mutableListOf<DocsChunk>()
        val errors = mutableListOf<String>()

        if (includeGithubDocs) {
            val gh = DocsParser.parseDocs(
                repoSlug = ctx.config.docsGithubRepo,
                branch = ctx.config.docsGithubBranch,
                locale = ctx.config.docsLanguage,
                onProgress = { c, t, f -> ctx.progress.status("docs $c/$t $f") },
            )
            allChunks += gh.chunks; errors += gh.errors
        }

        if (docRoots.isNotEmpty()) {
            val local = DocsParser.parseLocalMarkdown(docRoots) { c, t, f -> ctx.progress.status("docs(local) $c/$t $f") }
            allChunks += local.chunks; errors += local.errors
        }

        if (errors.isNotEmpty()) {
            ctx.log.warn("Docs parse errors (${errors.size}): ${errors.take(5).joinToString("; ")}")
        }
        ctx.log.info("Fetched ${allChunks.size} docs chunks")

        if (allChunks.isEmpty() && errors.isEmpty()) {
            return IndexResult("docs", 0, skipped = true, error = null)
        }

        if (ctx.progress.isCanceled) return IndexResult("docs", 0, false, "canceled")

        val priority = listOf("repo", "modding", "support", "blog", "re", "local")
        fun rank(id: String) = priority.indexOf(id.substringBefore(':')).let { if (it < 0) priority.size else it }
        val deduped = allChunks
            .sortedBy { rank(it.id) }
            .distinctBy { it.fileHash }
        allChunks.clear(); allChunks.addAll(deduped)


        ctx.progress.status("Detecting changes...")
        ctx.progress.fraction(0.3)

        val currentHashes = allChunks.associate { it.id to it.fileHash }
        val changes = hashTracker.computeChangesFromMap(currentHashes, Corpus.DOCS.id)

        ctx.log.info("Changes: +${changes.added.size} ~${changes.changed.size} -${changes.deleted.size} =${changes.unchanged.size}")

        if (!changes.hasChanges && changes.unchanged.isNotEmpty()) {
            ctx.log.info("No docs changes detected, skipping indexing")
            skipped = true
            ctx.progress.fraction(1.0)
            return IndexResult("docs", 0, skipped = true, error = null)
        }


        val chunksToEmbed = allChunks

        if (ctx.progress.isCanceled) return IndexResult("docs", 0, false, "canceled")


        val embeddings: List<FloatArray>
        if (chunksToEmbed.isNotEmpty()) {
            ctx.progress.status("Embedding ${chunksToEmbed.size} docs...")
            ctx.progress.fraction(0.35)

            val provider = EmbeddingProvider.fromConfig(ctx.config, Corpus.DOCS.embeddingPurpose)
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
                            ctx.progress.fraction(0.35 + (0.45 * done / total.coerceAtLeast(1)))
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

        if (ctx.progress.isCanceled) return IndexResult("docs", 0, false, "canceled")


        ctx.progress.status("Writing index...")
        ctx.progress.fraction(0.8)

        val stalePaths = changes.changed + changes.deleted
        if (stalePaths.isNotEmpty()) {
            hashTracker.removeHashes(stalePaths)
            db.inTransaction { conn ->
                val ps = conn.prepareStatement("DELETE FROM nodes WHERE id = ? AND corpus = ?")
                for (path in stalePaths) {
                    ps.setString(1, path)
                    ps.setString(2, Corpus.DOCS.id)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        if (chunksToEmbed.isNotEmpty()) {
            db.inTransaction { conn ->
                val ps = conn.prepareStatement(
                    """INSERT OR REPLACE INTO nodes
                       (id, node_type, display_name, file_path, line_start, line_end, content, embedding_text, chunk_index, owning_file, corpus, data_type, published_date)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                )
                for ((idx, chunk) in chunksToEmbed.withIndex()) {
                    ps.setString(1, chunk.id)
                    ps.setString(2, "DocsPage")
                    ps.setString(3, chunk.title)
                    ps.setString(4, chunk.filePath)
                    ps.setNull(5, java.sql.Types.INTEGER)
                    ps.setNull(6, java.sql.Types.INTEGER)
                    ps.setString(7, chunk.content)
                    ps.setString(8, chunk.textForEmbedding)
                    ps.setInt(9, idx)
                    ps.setString(10, chunk.relativePath)
                    ps.setString(11, Corpus.DOCS.id)
                    ps.setString(12, chunk.type.id)
                    ps.setString(13, chunk.publishedDate)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            val ftsRows = chunksToEmbed.map { chunk ->
                FtsTokenizer.FtsRow(chunk.id, chunk.title, chunk.content)
            }
            FtsTokenizer.populate(db, Corpus.DOCS.id, ftsRows, splitBody = false)
        }


        if (changes.currentHashes.isNotEmpty()) {
            hashTracker.updateHashes(changes.currentHashes, Corpus.DOCS.id)
        }


        ctx.progress.status("Building vector index...")
        ctx.progress.fraction(0.85)

        if (embeddings.isNotEmpty()) {
            val hnswDimension = embeddings.first().size
            val hnsw = HnswIndex(hnswDimension)
            hnsw.build(embeddings)
            val hnswPath = Paths.get(indexDir.absolutePath, "hnsw", Corpus.DOCS.hnswFileName)
            hnsw.save(hnswPath)
            hnsw.close()
            indexedCount = embeddings.size
        }

        if (ctx.progress.isCanceled) return IndexResult("docs", 0, false, "canceled")


        ctx.progress.status("Building docs reference edges...")
        ctx.progress.fraction(0.9)

        buildDocsReferenceEdges(db, allChunks)

        ctx.progress.fraction(1.0)
        ctx.log.info("Docs index built: $indexedCount pages indexed")
        return IndexResult("docs", indexedCount, skipped = false, error = null)
    }


    private val BACKTICK_PATTERN = Regex("""`([A-Za-z]\w+)`""")
    private val PASCAL_CASE_PATTERN = Regex("""\b([A-Z][a-z]+(?:[A-Z][a-z]+)+)\b""")

    private fun buildDocsReferenceEdges(db: com.hyindex.knowledge.core.db.KnowledgeDatabase, chunks: List<DocsChunk>) {
        if (chunks.isEmpty()) return


        db.execute("DELETE FROM edges WHERE edge_type = 'DOCS_REFERENCES' AND source_id IN (SELECT id FROM nodes WHERE corpus = '${Corpus.DOCS.id}')")


        val nameLookup = db.query(
            "SELECT id, display_name FROM nodes WHERE corpus IN ('code', 'gamedata') AND display_name IS NOT NULL"
        ) { rs -> rs.getString("display_name") to rs.getString("id") }
            .groupBy({ it.first }, { it.second })

        if (nameLookup.isEmpty()) {
            ctx.log.info("No code/gamedata nodes for DOCS_REFERENCES — skipping (index code and gamedata first)")
            return
        }

        val allEdges = mutableListOf<Triple<String, String, Int>>()

        for (chunk in chunks) {
            val candidates = mutableSetOf<String>()


            BACKTICK_PATTERN.findAll(chunk.content).forEach { match ->
                candidates.add(match.groupValues[1])
            }


            PASCAL_CASE_PATTERN.findAll(chunk.content).forEach { match ->
                candidates.add(match.groupValues[1])
            }


            for (candidate in candidates) {
                val targets = nameLookup[candidate] ?: continue
                for (targetId in targets) {
                    allEdges.add(Triple(chunk.id, targetId, 1))
                }
            }
        }

        if (allEdges.isNotEmpty()) {
            db.inTransaction { conn ->
                val ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type, target_resolved) VALUES (?, ?, 'DOCS_REFERENCES', ?)"
                )
                for ((sourceId, targetId, resolved) in allEdges) {
                    ps.setString(1, sourceId)
                    ps.setString(2, targetId)
                    ps.setInt(3, resolved)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        ctx.log.info("DOCS_REFERENCES: ${allEdges.size} edges from ${chunks.size} docs")
    }
}
