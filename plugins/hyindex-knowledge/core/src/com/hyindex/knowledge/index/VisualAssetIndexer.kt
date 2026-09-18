package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.embedding.EmbeddingProvider
import com.hyindex.knowledge.core.embedding.MultimodalDocument
import com.hyindex.knowledge.core.index.HnswIndex
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.index.IndexResult
import com.hyindex.knowledge.core.source.CanonicalRoots
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
class VisualAssetIndexer(private val ctx: IndexContext) {
    var lastDocuments: List<MultimodalDocument> = emptyList()
        private set
    internal var persistTestHook: (() -> Unit)? = null

    fun index(): IndexResult {
        val rootPath = ctx.config.visualRasterRoot.trim()
        if (rootPath.isEmpty()) {
            return IndexResult(Corpus.VISUAL.id, 0, skipped = false, error = null)
        }
        if (!VisualMedia.isMultimodalConfig(ctx.config)) {
            return IndexResult(
                Corpus.VISUAL.id,
                0,
                skipped = false,
                error = "visual profile requires Gemini Embedding 2 or Voyage multimodal",
            )
        }
        val root = File(rootPath)
        val rootPathNio = root.toPath()
        if (Files.isSymbolicLink(rootPathNio)) {
            return IndexResult(
                Corpus.VISUAL.id,
                0,
                skipped = true,
                error = "visual raster root is a directory symlink: $rootPath",
            )
        }
        if (!root.exists() || !Files.isDirectory(rootPathNio, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return IndexResult(
                Corpus.VISUAL.id,
                0,
                skipped = true,
                error = "visual raster root missing or not a directory: $rootPath",
            )
        }
        if (!CanonicalRoots.isAccessibleDirectory(root)) {
            return IndexResult(
                Corpus.VISUAL.id,
                0,
                skipped = true,
                error = "visual raster root is unreadable: $rootPath",
            )
        }

        val gemini = VisualMedia.usesGemini(ctx.config)
        val accepted = VisualMedia.acceptedExtensions(ctx.config)
        val assets = CanonicalRoots.walkSafeFiles(root)
            .filter { it.extension.lowercase() in accepted }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()

        val rows = mutableListOf<VisualRow>()
        for (file in assets) {
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val bytes = file.readBytes()
            val inspected = VisualMedia.inspect(bytes, file.extension, gemini) ?: continue
            val name = file.nameWithoutExtension
            val combined = buildString {
                appendLine("visual asset $name")
                appendLine("path=$relative")
                append("data type: ${if (inspected.kind == VisualMedia.Kind.PDF) "pdf" else "raster"}")
            }
            val text = combined
            rows += VisualRow(
                id = "visual:$relative",
                name = name,
                path = relative,
                text = text,
                bytes = inspected.bytes,
                mediaType = inspected.mediaType,
                contentHash = sha256(inspected.bytes),
            )
        }

        if (rows.isEmpty()) {
            wipeVisual()
            return IndexResult(Corpus.VISUAL.id, 0, skipped = false, error = null)
        }

        val provider = EmbeddingProvider.fromConfig(ctx.config, Corpus.VISUAL)
        val fake = provider is com.hyindex.knowledge.core.embedding.FakeEmbeddingProvider
        if (!provider.supportsMultimodalDocuments && !fake) {
            return IndexResult(
                Corpus.VISUAL.id,
                0,
                skipped = false,
                error = "visual embedding provider does not support multimodal documents",
            )
        }
        val documents = rows.map { MultimodalDocument(it.text, it.bytes, it.mediaType) }
        lastDocuments = documents
        val embeddings = runBlocking {
            provider.validate()
            provider.embedDocuments(documents)
        }
        if (embeddings.size != rows.size) {
            return IndexResult(
                Corpus.VISUAL.id,
                0,
                skipped = false,
                error = "visual embedding count ${embeddings.size} != asset count ${rows.size}",
            )
        }

        val dest = Paths.get(ctx.indexDir.absolutePath, "hnsw", Corpus.VISUAL.hnswFileName)
        dest.parent.toFile().mkdirs()
        val staging = dest.resolveSibling("${Corpus.VISUAL.hnswFileName}.staging")
        val backup = dest.resolveSibling("${Corpus.VISUAL.hnswFileName}.prev")
        val hnsw = HnswIndex(embeddings.first().size)
        try {
            hnsw.build(embeddings)
            hnsw.save(staging)
            if (hnsw.size() != rows.size) {
                Files.deleteIfExists(staging)
                return IndexResult(
                    Corpus.VISUAL.id,
                    0,
                    skipped = false,
                    error = "visual HNSW size ${hnsw.size()} != asset count ${rows.size}",
                )
            }
        } finally {
            hnsw.close()
        }

        val hadDest = Files.isRegularFile(dest)
        if (hadDest) {
            Files.copy(dest, backup, StandardCopyOption.REPLACE_EXISTING)
        }
        try {
            try {
                Files.move(staging, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(staging, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            persistVisualRows(rows)
        } catch (error: Exception) {
            if (hadDest && Files.isRegularFile(backup)) {
                Files.move(backup, dest, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.deleteIfExists(dest)
            }
            throw error
        } finally {
            Files.deleteIfExists(backup)
            Files.deleteIfExists(staging)
        }

        return IndexResult(Corpus.VISUAL.id, rows.size, skipped = false, error = null)
    }

    private fun persistVisualRows(rows: List<VisualRow>) {
        val corpus = Corpus.VISUAL.id
        ctx.db.inTransaction { conn ->
            conn.prepareStatement(
                """UPDATE edges SET target_resolved = 0
                   WHERE target_id IN (SELECT id FROM nodes WHERE corpus = ?)""",
            ).use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """DELETE FROM edges
                   WHERE source_id IN (SELECT id FROM nodes WHERE corpus = ?)""",
            ).use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM nodes_fts WHERE corpus = ?").use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM nodes WHERE corpus = ?").use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM file_hashes WHERE corpus_type = ?").use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM corpus_provenance WHERE corpus = ?").use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            val sql =
                """INSERT OR REPLACE INTO nodes
                   (id, node_type, display_name, file_path, line_start, line_end, content, embedding_text, chunk_index, owning_file, corpus, data_type)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            conn.prepareStatement(sql).use { ps ->
                for ((idx, row) in rows.withIndex()) {
                    ps.setString(1, row.id)
                    ps.setString(2, "VisualAsset")
                    ps.setString(3, row.name)
                    ps.setString(4, row.path)
                    ps.setNull(5, java.sql.Types.INTEGER)
                    ps.setNull(6, java.sql.Types.INTEGER)
                    ps.setString(7, row.text)
                    ps.setString(8, row.text)
                    ps.setInt(9, idx)
                    ps.setString(10, row.path)
                    ps.setString(11, corpus)
                    ps.setString(12, if (row.mediaType == "application/pdf") "pdf" else "raster")
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.prepareStatement(
                "INSERT OR REPLACE INTO file_hashes (file_path, file_hash, corpus_type) VALUES (?, ?, ?)",
            ).use { ps ->
                for (row in rows) {
                    ps.setString(1, row.path)
                    ps.setString(2, row.contentHash)
                    ps.setString(3, corpus)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.prepareStatement(
                "INSERT INTO nodes_fts(node_id, corpus, name, body) VALUES(?, ?, ?, ?)",
            ).use { ps ->
                for (row in rows) {
                    ps.setString(1, row.id)
                    ps.setString(2, corpus)
                    ps.setString(3, row.name)
                    ps.setString(4, row.text)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            persistTestHook?.invoke()
        }
    }

    private fun wipeVisual() {
        val corpus = Corpus.VISUAL.id
        ctx.db.inTransaction { conn ->
            conn.prepareStatement(
                """UPDATE edges SET target_resolved = 0
                   WHERE target_id IN (SELECT id FROM nodes WHERE corpus = ?)""",
            ).use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """DELETE FROM edges
                   WHERE source_id IN (SELECT id FROM nodes WHERE corpus = ?)""",
            ).use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            try {
                conn.prepareStatement("DELETE FROM nodes_fts WHERE corpus = ?").use { ps ->
                    ps.setString(1, corpus)
                    ps.executeUpdate()
                }
            } catch (_: Exception) {
            }
            conn.prepareStatement("DELETE FROM nodes WHERE corpus = ?").use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM file_hashes WHERE corpus_type = ?").use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            try {
                conn.prepareStatement("DELETE FROM corpus_provenance WHERE corpus = ?").use { ps ->
                    ps.setString(1, corpus)
                    ps.executeUpdate()
                }
            } catch (_: Exception) {
            }
        }
        val hnsw = File(ctx.indexDir, "hnsw/${Corpus.VISUAL.hnswFileName}")
        if (hnsw.exists()) hnsw.delete()
        File(ctx.indexDir, "hnsw/${Corpus.VISUAL.hnswFileName}.staging").delete()
    }


    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private data class VisualRow(
        val id: String,
        val name: String,
        val path: String,
        val text: String,
        val bytes: ByteArray,
        val mediaType: String,
        val contentHash: String,
    )

    companion object {
        val ACCEPTED: Set<String> = VisualMedia.VOYAGE_RASTERS
    }
}
