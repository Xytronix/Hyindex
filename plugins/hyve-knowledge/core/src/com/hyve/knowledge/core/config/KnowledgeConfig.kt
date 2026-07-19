// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths

data class KnowledgeConfig(
    val embeddingProvider: String = "openai",
    val embeddingBaseUrl: String = "",
    val embeddingApiKey: String = "",
    val embeddingCodeModel: String = "text-embedding-3-large",
    val embeddingTextModel: String = "text-embedding-3-large",
    val embeddingDimensions: Int? = null,

    val embeddingConcurrency: Int = 4,
    val indexPath: String = "",
    val resultsPerCorpus: Int = 10,
    val maxRelatedConnections: Int = 5,
    val activeVersion: String = "",
    val docsGithubRepo: String = "HytaleModding/site",
    val docsGithubBranch: String = "main",
    val docsLanguage: String = "en",

    val snippetMaxLength: Int = 1500,
    val nodeContentMaxLength: Int = 8000,
    val sourceMaxChars: Int = 20000,
    val retentionCount: Int = 0,
    val gitToken: String? = null,
    val gitRepoUrl: String = "https://github.com/HypixelStudios/hytale-shared-source.git",

    val blogScorePenalty: Double = 0.85,
    val gamedataUnintentScoreFloor: Double = 0.70,
    val expansionDiscount: Double = 0.4,
    val minExpansionSeedScore: Double = 0.5,
    val minExpansionResultScore: Double = 0.35,
    val perSeedExpansionCap: Int = 3,
    val gamedataWorldNodePenalty: Double = 0.5,
    val gamedataWorldNodeTypes: List<String> = listOf("cave", "prefab", "zone", "worldgen", "instance", "terrain_layer", "environment", "biome"),
    val gamedataFetchLimit: Int = 200,

    val rerankerEnabled: Boolean = false,
    val rerankerModel: String = "rerank-2.5",
    val rerankerTopN: Int = 50,

    val hybridEnabled: Boolean = true,
    val hybridRrfK: Int = 60,
    val hybridLexicalLimit: Int = 100,
    val hybridNameWeight: Double = 10.0,
    val hybridBodyWeight: Double = 1.0,

    val nearDupPenalty: Double = 0.5,
    val nearDupJaccard: Double = 0.8,

    val delegatePenalty: Double = 1.0,
) {
    fun resolvedIndexPath(): File {
        val base = if (indexPath.isNotBlank()) File(indexPath) else defaultBasePath()
        if (activeVersion.isNotBlank()) return File(base, "versions/$activeVersion")
        return base
    }

    fun resolvedBasePath(): File {
        if (indexPath.isNotBlank()) return File(indexPath)
        return defaultBasePath()
    }

    private fun defaultBasePath(): File {
        val home = System.getProperty("user.home")
        return Paths.get(home, ".hyve", "knowledge").toFile()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun configFilePath(): File {
            val home = System.getProperty("user.home")
            return Paths.get(home, ".hyve", "knowledge", "mcp-config.json").toFile()
        }

        fun writeToFile(config: KnowledgeConfig, file: File = configFilePath()) {
            val fileConfig = FileConfig(
                embeddingProvider = config.embeddingProvider,
                embeddingBaseUrl = config.embeddingBaseUrl,
                embeddingApiKey = config.embeddingApiKey,
                embeddingCodeModel = config.embeddingCodeModel,
                embeddingTextModel = config.embeddingTextModel,
                embeddingDimensions = config.embeddingDimensions,
                embeddingConcurrency = config.embeddingConcurrency,
                indexPath = config.indexPath,
                resultsPerCorpus = config.resultsPerCorpus,
                maxRelatedConnections = config.maxRelatedConnections,
                activeVersion = config.activeVersion.ifBlank { null },
                docsGithubRepo = config.docsGithubRepo,
                docsGithubBranch = config.docsGithubBranch,
                docsLanguage = config.docsLanguage,
                snippetMaxLength = config.snippetMaxLength,
                nodeContentMaxLength = config.nodeContentMaxLength,
                sourceMaxChars = config.sourceMaxChars,
                retentionCount = config.retentionCount.takeIf { it != 0 },
                gitToken = config.gitToken,
                gitRepoUrl = config.gitRepoUrl,
                blogScorePenalty = config.blogScorePenalty,
                gamedataUnintentScoreFloor = config.gamedataUnintentScoreFloor,
                expansionDiscount = config.expansionDiscount,
                minExpansionSeedScore = config.minExpansionSeedScore,
                minExpansionResultScore = config.minExpansionResultScore,
                perSeedExpansionCap = config.perSeedExpansionCap,
                gamedataWorldNodePenalty = config.gamedataWorldNodePenalty,
                gamedataWorldNodeTypes = config.gamedataWorldNodeTypes,
                gamedataFetchLimit = config.gamedataFetchLimit,
                rerankerEnabled = config.rerankerEnabled,
                rerankerModel = config.rerankerModel,
                rerankerTopN = config.rerankerTopN,
                hybridEnabled = config.hybridEnabled,
                hybridRrfK = config.hybridRrfK,
                hybridLexicalLimit = config.hybridLexicalLimit,
                hybridNameWeight = config.hybridNameWeight,
                hybridBodyWeight = config.hybridBodyWeight,
                nearDupPenalty = config.nearDupPenalty,
                nearDupJaccard = config.nearDupJaccard,
                delegatePenalty = config.delegatePenalty,
            )
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(FileConfig.serializer(), fileConfig))
        }

        fun loadFromFile(file: File = configFilePath()): KnowledgeConfig? {
            if (!file.exists()) return null
            return try {
                val fc = json.decodeFromString(FileConfig.serializer(), file.readText())
                val defaults = KnowledgeConfig()
                KnowledgeConfig(
                    embeddingProvider = fc.embeddingProvider ?: defaults.embeddingProvider,
                    embeddingBaseUrl = fc.embeddingBaseUrl ?: defaults.embeddingBaseUrl,
                    embeddingApiKey = fc.embeddingApiKey ?: defaults.embeddingApiKey,
                    embeddingCodeModel = fc.embeddingCodeModel ?: defaults.embeddingCodeModel,
                    embeddingTextModel = fc.embeddingTextModel ?: defaults.embeddingTextModel,
                    embeddingDimensions = fc.embeddingDimensions ?: defaults.embeddingDimensions,
                    embeddingConcurrency = fc.embeddingConcurrency ?: defaults.embeddingConcurrency,
                    indexPath = fc.indexPath ?: defaults.indexPath,
                    resultsPerCorpus = fc.resultsPerCorpus ?: defaults.resultsPerCorpus,
                    maxRelatedConnections = fc.maxRelatedConnections ?: defaults.maxRelatedConnections,
                    activeVersion = fc.activeVersion ?: defaults.activeVersion,
                    docsGithubRepo = fc.docsGithubRepo ?: defaults.docsGithubRepo,
                    docsGithubBranch = fc.docsGithubBranch ?: defaults.docsGithubBranch,
                    docsLanguage = fc.docsLanguage ?: defaults.docsLanguage,
                    snippetMaxLength = fc.snippetMaxLength ?: defaults.snippetMaxLength,
                    nodeContentMaxLength = fc.nodeContentMaxLength ?: defaults.nodeContentMaxLength,
                    sourceMaxChars = fc.sourceMaxChars ?: defaults.sourceMaxChars,
                    retentionCount = fc.retentionCount ?: defaults.retentionCount,
                    gitToken = fc.gitToken ?: defaults.gitToken,
                    gitRepoUrl = fc.gitRepoUrl ?: defaults.gitRepoUrl,
                    blogScorePenalty = fc.blogScorePenalty ?: defaults.blogScorePenalty,
                    gamedataUnintentScoreFloor = fc.gamedataUnintentScoreFloor ?: defaults.gamedataUnintentScoreFloor,
                    expansionDiscount = fc.expansionDiscount ?: defaults.expansionDiscount,
                    minExpansionSeedScore = fc.minExpansionSeedScore ?: defaults.minExpansionSeedScore,
                    minExpansionResultScore = fc.minExpansionResultScore ?: defaults.minExpansionResultScore,
                    perSeedExpansionCap = fc.perSeedExpansionCap ?: defaults.perSeedExpansionCap,
                    gamedataWorldNodePenalty = fc.gamedataWorldNodePenalty ?: defaults.gamedataWorldNodePenalty,
                    gamedataWorldNodeTypes = fc.gamedataWorldNodeTypes ?: defaults.gamedataWorldNodeTypes,
                    gamedataFetchLimit = fc.gamedataFetchLimit ?: defaults.gamedataFetchLimit,
                    rerankerEnabled = fc.rerankerEnabled ?: defaults.rerankerEnabled,
                    rerankerModel = fc.rerankerModel ?: defaults.rerankerModel,
                    rerankerTopN = fc.rerankerTopN ?: defaults.rerankerTopN,
                    hybridEnabled = fc.hybridEnabled ?: defaults.hybridEnabled,
                    hybridRrfK = fc.hybridRrfK ?: defaults.hybridRrfK,
                    hybridLexicalLimit = fc.hybridLexicalLimit ?: defaults.hybridLexicalLimit,
                    hybridNameWeight = fc.hybridNameWeight ?: defaults.hybridNameWeight,
                    hybridBodyWeight = fc.hybridBodyWeight ?: defaults.hybridBodyWeight,
                    nearDupPenalty = fc.nearDupPenalty ?: defaults.nearDupPenalty,
                    nearDupJaccard = fc.nearDupJaccard ?: defaults.nearDupJaccard,
                    delegatePenalty = fc.delegatePenalty ?: defaults.delegatePenalty,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    @Serializable
    internal data class FileConfig(
        val embeddingProvider: String? = null,
        val embeddingBaseUrl: String? = null,
        val embeddingApiKey: String? = null,
        val embeddingCodeModel: String? = null,
        val embeddingTextModel: String? = null,
        val embeddingDimensions: Int? = null,
        val embeddingConcurrency: Int? = null,
        val indexPath: String? = null,
        val resultsPerCorpus: Int? = null,
        val maxRelatedConnections: Int? = null,
        val activeVersion: String? = null,
        val docsGithubRepo: String? = null,
        val docsGithubBranch: String? = null,
        val docsLanguage: String? = null,
        val snippetMaxLength: Int? = null,
        val nodeContentMaxLength: Int? = null,
        val sourceMaxChars: Int? = null,
        val retentionCount: Int? = null,
        val gitToken: String? = null,
        val gitRepoUrl: String? = null,
        val blogScorePenalty: Double? = null,
        val gamedataUnintentScoreFloor: Double? = null,
        val expansionDiscount: Double? = null,
        val minExpansionSeedScore: Double? = null,
        val minExpansionResultScore: Double? = null,
        val perSeedExpansionCap: Int? = null,
        val gamedataWorldNodePenalty: Double? = null,
        val gamedataWorldNodeTypes: List<String>? = null,
        val gamedataFetchLimit: Int? = null,
        val rerankerEnabled: Boolean? = null,
        val rerankerModel: String? = null,
        val rerankerTopN: Int? = null,
        val hybridEnabled: Boolean? = null,
        val hybridRrfK: Int? = null,
        val hybridLexicalLimit: Int? = null,
        val hybridNameWeight: Double? = null,
        val hybridBodyWeight: Double? = null,
        val nearDupPenalty: Double? = null,
        val nearDupJaccard: Double? = null,
        val delegatePenalty: Double? = null,
    )
}
