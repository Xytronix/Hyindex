// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.mcp.standalone

import com.hyindex.knowledge.core.config.KnowledgeConfig
import java.io.File

object McpConfig {
    fun load(): KnowledgeConfig {
        val base = KnowledgeConfig.loadFromFile() ?: KnowledgeConfig()
        val reranker = base.rerankerProfile?.let { profile ->
            profile.copy(
                provider = env("HYINDEX_RERANKER_PROVIDER") ?: profile.provider,
                baseUrl = env("HYINDEX_RERANKER_BASE_URL") ?: profile.baseUrl,
                endpoint = env("HYINDEX_RERANKER_ENDPOINT") ?: profile.endpoint,
                apiKey = env("HYINDEX_RERANKER_API_KEY") ?: profile.apiKey,
                model = env("HYINDEX_RERANKER_MODEL") ?: profile.model,
                protocol = env("HYINDEX_RERANKER_PROTOCOL") ?: profile.protocol,
                topN = env("HYINDEX_RERANKER_TOP_N")?.toIntOrNull() ?: profile.topN,
            )
        }
        return base.copy(
            visualRasterRoot = env("HYINDEX_VISUAL_RASTER_ROOT") ?: base.visualRasterRoot,
            indexPath = env("HYINDEX_INDEX_PATH") ?: base.indexPath,
            activeVersion = env("HYINDEX_ACTIVE_VERSION") ?: base.activeVersion,
            snippetMaxLength = env("HYINDEX_MCP_SNIPPET_MAX")?.toIntOrNull() ?: base.snippetMaxLength,
            nodeContentMaxLength = env("HYINDEX_MCP_NODE_CONTENT_MAX")?.toIntOrNull() ?: base.nodeContentMaxLength,
            sourceMaxChars = env("HYINDEX_MCP_SOURCE_MAX")?.toIntOrNull() ?: base.sourceMaxChars,
            rerankerProfile = reranker,
        )
    }

    fun configFilePath(): File = KnowledgeConfig.configFilePath()

    fun writeConfig(config: KnowledgeConfig) = KnowledgeConfig.writeToFile(config)

    private fun env(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }

}
