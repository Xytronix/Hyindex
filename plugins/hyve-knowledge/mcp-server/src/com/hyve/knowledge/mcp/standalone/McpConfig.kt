// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.mcp.standalone

import com.hyve.knowledge.core.config.KnowledgeConfig
import java.io.File

object McpConfig {
    fun load(): KnowledgeConfig {
        val base = KnowledgeConfig.loadFromFile() ?: KnowledgeConfig()
        return base.copy(
            embeddingProvider = env("HYVE_EMBEDDING_PROVIDER") ?: base.embeddingProvider,
            embeddingBaseUrl = env("HYVE_EMBEDDING_BASE_URL")
                ?: env("HYVE_OLLAMA_URL")
                ?: base.embeddingBaseUrl,
            embeddingApiKey = env("HYVE_EMBEDDING_API_KEY")
                ?: env("OPENAI_API_KEY")
                ?: env("VOYAGE_API_KEY")
                ?: env("COHERE_API_KEY")
                ?: env("GEMINI_API_KEY")
                ?: env("GOOGLE_API_KEY")
                ?: base.embeddingApiKey,
            embeddingCodeModel = env("HYVE_EMBEDDING_CODE_MODEL")
                ?: env("HYVE_VOYAGE_CODE_MODEL")
                ?: env("HYVE_OLLAMA_CODE_MODEL")
                ?: base.embeddingCodeModel,
            embeddingTextModel = env("HYVE_EMBEDDING_TEXT_MODEL")
                ?: env("HYVE_VOYAGE_TEXT_MODEL")
                ?: env("HYVE_OLLAMA_TEXT_MODEL")
                ?: base.embeddingTextModel,
            embeddingDimensions = env("HYVE_EMBEDDING_DIMENSIONS")?.toIntOrNull() ?: base.embeddingDimensions,
            indexPath = env("HYVE_INDEX_PATH") ?: base.indexPath,
            activeVersion = env("HYVE_ACTIVE_VERSION") ?: base.activeVersion,
            snippetMaxLength = env("HYVE_MCP_SNIPPET_MAX")?.toIntOrNull() ?: base.snippetMaxLength,
            nodeContentMaxLength = env("HYVE_MCP_NODE_CONTENT_MAX")?.toIntOrNull() ?: base.nodeContentMaxLength,
            sourceMaxChars = env("HYVE_MCP_SOURCE_MAX")?.toIntOrNull() ?: base.sourceMaxChars,
        )
    }

    fun configFilePath(): File = KnowledgeConfig.configFilePath()

    fun writeConfig(config: KnowledgeConfig) = KnowledgeConfig.writeToFile(config)

    private fun env(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
}
