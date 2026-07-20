// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.mcp.standalone

import com.hyindex.knowledge.core.config.KnowledgeConfig
import java.io.File

object McpConfig {
    fun load(): KnowledgeConfig {
        val base = KnowledgeConfig.loadFromFile() ?: KnowledgeConfig()
        return base.copy(
            embeddingProvider = env("HYINDEX_EMBEDDING_PROVIDER") ?: base.embeddingProvider,
            embeddingBaseUrl = env("HYINDEX_EMBEDDING_BASE_URL")
                ?: env("HYINDEX_OLLAMA_URL")
                ?: base.embeddingBaseUrl,
            embeddingApiKey = env("HYINDEX_EMBEDDING_API_KEY")
                ?: env("OPENAI_API_KEY")
                ?: env("VOYAGE_API_KEY")
                ?: env("COHERE_API_KEY")
                ?: env("GEMINI_API_KEY")
                ?: env("GOOGLE_API_KEY")
                ?: base.embeddingApiKey,
            embeddingCodeModel = env("HYINDEX_EMBEDDING_CODE_MODEL")
                ?: env("HYINDEX_VOYAGE_CODE_MODEL")
                ?: env("HYINDEX_OLLAMA_CODE_MODEL")
                ?: base.embeddingCodeModel,
            embeddingTextModel = env("HYINDEX_EMBEDDING_TEXT_MODEL")
                ?: env("HYINDEX_VOYAGE_TEXT_MODEL")
                ?: env("HYINDEX_OLLAMA_TEXT_MODEL")
                ?: base.embeddingTextModel,
            embeddingDimensions = env("HYINDEX_EMBEDDING_DIMENSIONS")?.toIntOrNull() ?: base.embeddingDimensions,
            indexPath = env("HYINDEX_INDEX_PATH") ?: base.indexPath,
            activeVersion = env("HYINDEX_ACTIVE_VERSION") ?: base.activeVersion,
            snippetMaxLength = env("HYINDEX_MCP_SNIPPET_MAX")?.toIntOrNull() ?: base.snippetMaxLength,
            nodeContentMaxLength = env("HYINDEX_MCP_NODE_CONTENT_MAX")?.toIntOrNull() ?: base.nodeContentMaxLength,
            sourceMaxChars = env("HYINDEX_MCP_SOURCE_MAX")?.toIntOrNull() ?: base.sourceMaxChars,
        )
    }

    fun configFilePath(): File = KnowledgeConfig.configFilePath()

    fun writeConfig(config: KnowledgeConfig) = KnowledgeConfig.writeToFile(config)

    private fun env(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
}
