// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.index

import com.hyindex.common.settings.HytaleVersionDetector
import com.hyindex.knowledge.core.index.CorpusIndexer
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.index.IndexResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

class BuildAllIndexer(
    private val ctx: IndexContext,
    private val docRoots: List<File>,
    private val versionInfo: HytaleVersionDetector.HytaleVersionInfo?,
    private val corpora: Set<String> = setOf("code", "gamedata", "client", "docs"),
    private val includeGithubDocs: Boolean = true,
) {
    fun run(force: Boolean = false, extraPlugins: List<CorpusIndexer> = emptyList()): List<IndexResult> {
        val results = mutableListOf<IndexResult>()
        ctx.indexDir.mkdirs()
        if ("code" in corpora) results += safe("code") { CodeIndexer(ctx).index(force) }
        if ("gamedata" in corpora) results += safe("gamedata") { GameDataIndexer(ctx).index() }
        if ("client" in corpora) results += safe("client") { ClientUiIndexer(ctx).index(force) }
        if ("docs" in corpora) results += safe("docs") { DocsIndexer(ctx, docRoots, includeGithubDocs).index() }
        val plugins = extraPlugins.ifEmpty { java.util.ServiceLoader.load(CorpusIndexer::class.java).toList() }
        for (plugin in plugins) {
            if (plugin.corpus in corpora || corpora.isEmpty()) {
                results += safe(plugin.corpus) { plugin.index(ctx) }
            }
        }
        versionInfo?.let { writeVersionMeta(ctx.indexDir, it) }
        return results
    }

    private inline fun safe(corpus: String, block: () -> IndexResult): IndexResult =
        try { block() } catch (e: Exception) {
            ctx.log.warn("$corpus indexing failed: ${e.message}")
            IndexResult(corpus, 0, skipped = false, error = e.message ?: "unknown")
        }

    @Serializable
    private data class VersionMeta(
        val patchline: String,
        val branch: String,
        val version: String,
        val date: String,
        val shortHash: String,
        val fullRevision: String,
        val treeRevision: String,
        val slug: String,
        val buildNumber: Int,
        val protocolCrc: Long,
        val indexedAt: String,
    )

    private val metaJson = Json { prettyPrint = true }

    private fun writeVersionMeta(versionDir: File, info: HytaleVersionDetector.HytaleVersionInfo) {
        val meta = VersionMeta(
            patchline = info.patchline,
            branch = info.branch,
            version = info.rawVersion,
            date = info.date,
            shortHash = info.shortHash,
            fullRevision = info.fullRevision,
            treeRevision = info.treeRevision,
            slug = info.slug,
            buildNumber = info.buildNumber,
            protocolCrc = info.protocolCrc,
            indexedAt = Instant.now().toString(),
        )
        File(versionDir, "version_meta.json").writeText(metaJson.encodeToString(VersionMeta.serializer(), meta))
    }
}
