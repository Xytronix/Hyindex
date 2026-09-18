// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.config

import com.hyindex.knowledge.core.db.Corpus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths

private const val DEFAULT_EMBEDDING_CONCURRENCY = 4

private val DEFAULT_EMBEDDING_PROFILES = mapOf(
    "code" to EmbeddingProfile(
        provider = "openai",
        baseUrl = "https://api.openai.com",
        documentModel = "text-embedding-3-large",
        dimensions = 3072,
        concurrency = DEFAULT_EMBEDDING_CONCURRENCY,
    ),
    "text" to EmbeddingProfile(
        provider = "openai",
        baseUrl = "https://api.openai.com",
        documentModel = "text-embedding-3-large",
        dimensions = 3072,
        concurrency = DEFAULT_EMBEDDING_CONCURRENCY,
    ),
)

private val DEFAULT_CORPUS_EMBEDDING_PROFILES = mapOf(
    Corpus.CODE.id to "code",
    Corpus.DOCS.id to "text",
    Corpus.GAMEDATA.id to "text",
    Corpus.CLIENT.id to "text",
)

data class KnowledgeConfig(
    val embeddingProfiles: Map<String, EmbeddingProfile> = DEFAULT_EMBEDDING_PROFILES,
    val corpusEmbeddingProfiles: Map<String, String> = DEFAULT_CORPUS_EMBEDDING_PROFILES,
    val visualRasterRoot: String = "",

    val indexPath: String = "",
    val resultsPerCorpus: Int = 10,
    val maxRelatedConnections: Int = 5,
    val activeVersion: String = "",
    val docsGithubRepo: String = "HytaleModding/site",
    val docsGithubBranch: String = "main",
    val docsLanguage: String = "en",
    val indexPatchlines: List<String> = DEFAULT_INDEX_PATCHLINES,
    val enabledCorpora: List<String> = DEFAULT_ENABLED_CORPORA,
    val docsSources: List<String> = DEFAULT_DOCS_SOURCES,

    val snippetMaxLength: Int = 1500,
    val nodeContentMaxLength: Int = 8000,
    val sourceMaxChars: Int = 20000,
    val retentionCount: Int = 0,
    val gitToken: String? = null,

    val blogScorePenalty: Double = 0.85,
    val gamedataUnintentScoreFloor: Double = 0.70,
    val expansionDiscount: Double = 0.4,
    val minExpansionSeedScore: Double = 0.5,
    val minExpansionResultScore: Double = 0.35,
    val perSeedExpansionCap: Int = 3,
    val gamedataWorldNodePenalty: Double = 0.5,
    val gamedataWorldNodeTypes: List<String> = listOf(
        "cave",
        "prefab",
        "zone",
        "worldgen",
        "instance",
        "terrain_layer",
        "environment",
        "biome",
    ),
    val gamedataFetchLimit: Int = 200,

    val rerankerProfile: RerankerProfile? = null,

    val jevRoutingEnabled: Boolean = false,
    val jevApiKey: String = "",
    val jevModel: String = "jev-latest",
    val jevBaseUrl: String = "https://api.typesafe.ai",
    val jevCorpusThreshold: Double = 0.20,
    val routedCandidatesPerCorpus: Int = 15,

    val hybridEnabled: Boolean = true,
    val hybridRrfK: Int = 60,
    val hybridLexicalLimit: Int = 100,
    val hybridNameWeight: Double = 10.0,
    val hybridBodyWeight: Double = 1.0,

    val nearDupPenalty: Double = 0.5,
    val nearDupJaccard: Double = 0.8,

    val delegatePenalty: Double = 1.0,
) {
    fun resolvedEmbeddingProfile(corpus: Corpus): ResolvedEmbeddingProfile {
        val profileName = corpusEmbeddingProfiles[corpus.id]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Corpus '${corpus.id}' has no embedding profile assignment")
        val profile = embeddingProfiles[profileName]
            ?: throw IllegalArgumentException(
                "Corpus '${corpus.id}' references missing embedding profile '$profileName'",
            )
        require(profile.provider.isNotBlank()) { "Embedding profile '$profileName' has no provider" }
        require(profile.documentModel.isNotBlank()) { "Embedding profile '$profileName' has no documentModel" }
        require(profile.dimensions == null || profile.dimensions > 0) {
            "Embedding profile '$profileName' dimensions must be positive"
        }
        return ResolvedEmbeddingProfile(
            name = profileName,
            provider = profile.provider,
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            documentModel = profile.documentModel,
            queryModel = profile.queryModel?.takeIf { it.isNotBlank() } ?: profile.documentModel,
            dimensions = profile.dimensions,
            concurrency = profile.concurrency?.takeIf { it > 0 } ?: DEFAULT_EMBEDDING_CONCURRENCY,
        )
    }

    fun resolvedDocumentModel(corpus: Corpus): String =
        resolvedEmbeddingProfile(corpus).documentModel

    fun resolvedQueryModel(corpus: Corpus): String =
        resolvedEmbeddingProfile(corpus).queryModel

    fun resolvedDimensions(corpus: Corpus): Int? =
        resolvedEmbeddingProfile(corpus).dimensions

    fun resolvedIndexPatchlines(): Set<String> {
        val resolved = indexPatchlines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val unknown = resolved - INDEX_PATCHLINE_IDS
        require(unknown.isEmpty()) { "Unknown indexPatchlines: $unknown" }
        return resolved
    }

    fun resolvedEnabledCorpora(): Set<String> {
        val resolved = enabledCorpora.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val unknown = resolved - CORPUS_IDS
        require(unknown.isEmpty()) { "Unknown enabledCorpora: $unknown" }
        return resolved
    }

    fun resolvedDocsSources(): Set<String> {
        val resolved = docsSources.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val unknown = resolved - DOCS_SOURCE_IDS
        require(unknown.isEmpty()) { "Unknown docsSources: $unknown" }
        return resolved
    }

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
        return Paths.get(home, ".hyindex", "knowledge").toFile()
    }

    companion object {
        val DEFAULT_INDEX_PATCHLINES: List<String> = listOf("release", "pre-release")
        val INDEX_PATCHLINE_IDS: Set<String> = DEFAULT_INDEX_PATCHLINES.toSet()
        val DEFAULT_ENABLED_CORPORA: List<String> = listOf("code", "gamedata", "client", "docs")
        val CORPUS_IDS: Set<String> = Corpus.entries.mapTo(linkedSetOf(), Corpus::id)

        val DEFAULT_DOCS_SOURCES: List<String> =
            listOf("official", "modding", "blog", "support", "server")
        val DOCS_SOURCE_IDS: Set<String> = DEFAULT_DOCS_SOURCES.toSet()

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun configFilePath(): File {
            val home = System.getProperty("user.home")
            return Paths.get(home, ".hyindex", "knowledge", "config.json").toFile()
        }

        fun writeToFile(config: KnowledgeConfig, file: File = configFilePath()) {
            val fileConfig = FileConfig(
                embeddingProfiles = config.embeddingProfiles,
                corpusEmbeddingProfiles = config.corpusEmbeddingProfiles,
                visualRasterRoot = config.visualRasterRoot.takeIf { it.isNotBlank() },
                indexPath = config.indexPath,
                resultsPerCorpus = config.resultsPerCorpus,
                maxRelatedConnections = config.maxRelatedConnections,
                activeVersion = config.activeVersion.ifBlank { null },
                docsGithubRepo = config.docsGithubRepo,
                docsGithubBranch = config.docsGithubBranch,
                docsLanguage = config.docsLanguage,
                indexPatchlines = config.indexPatchlines,
                enabledCorpora = config.enabledCorpora,
                docsSources = config.docsSources,
                snippetMaxLength = config.snippetMaxLength,
                nodeContentMaxLength = config.nodeContentMaxLength,
                sourceMaxChars = config.sourceMaxChars,
                retentionCount = config.retentionCount.takeIf { it != 0 },
                gitToken = config.gitToken,
                blogScorePenalty = config.blogScorePenalty,
                gamedataUnintentScoreFloor = config.gamedataUnintentScoreFloor,
                expansionDiscount = config.expansionDiscount,
                minExpansionSeedScore = config.minExpansionSeedScore,
                minExpansionResultScore = config.minExpansionResultScore,
                perSeedExpansionCap = config.perSeedExpansionCap,
                gamedataWorldNodePenalty = config.gamedataWorldNodePenalty,
                gamedataWorldNodeTypes = config.gamedataWorldNodeTypes,
                gamedataFetchLimit = config.gamedataFetchLimit,
                rerankerProfile = config.rerankerProfile,
                jevRoutingEnabled = config.jevRoutingEnabled,
                jevApiKey = config.jevApiKey,
                jevModel = config.jevModel,
                jevBaseUrl = config.jevBaseUrl,
                jevCorpusThreshold = config.jevCorpusThreshold,
                routedCandidatesPerCorpus = config.routedCandidatesPerCorpus,
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
                    embeddingProfiles = fc.embeddingProfiles ?: defaults.embeddingProfiles,
                    corpusEmbeddingProfiles = fc.corpusEmbeddingProfiles ?: defaults.corpusEmbeddingProfiles,
                    visualRasterRoot = fc.visualRasterRoot ?: defaults.visualRasterRoot,
                    indexPath = fc.indexPath ?: defaults.indexPath,
                    resultsPerCorpus = fc.resultsPerCorpus ?: defaults.resultsPerCorpus,
                    maxRelatedConnections = fc.maxRelatedConnections ?: defaults.maxRelatedConnections,
                    activeVersion = fc.activeVersion ?: defaults.activeVersion,
                    docsGithubRepo = fc.docsGithubRepo ?: defaults.docsGithubRepo,
                    docsGithubBranch = fc.docsGithubBranch ?: defaults.docsGithubBranch,
                    docsLanguage = fc.docsLanguage ?: defaults.docsLanguage,
                    indexPatchlines = fc.indexPatchlines ?: defaults.indexPatchlines,
                    enabledCorpora = fc.enabledCorpora ?: defaults.enabledCorpora,
                    docsSources = fc.docsSources ?: defaults.docsSources,
                    snippetMaxLength = fc.snippetMaxLength ?: defaults.snippetMaxLength,
                    nodeContentMaxLength = fc.nodeContentMaxLength ?: defaults.nodeContentMaxLength,
                    sourceMaxChars = fc.sourceMaxChars ?: defaults.sourceMaxChars,
                    retentionCount = fc.retentionCount ?: defaults.retentionCount,
                    gitToken = fc.gitToken ?: defaults.gitToken,
                    blogScorePenalty = fc.blogScorePenalty ?: defaults.blogScorePenalty,
                    gamedataUnintentScoreFloor = fc.gamedataUnintentScoreFloor ?: defaults.gamedataUnintentScoreFloor,
                    expansionDiscount = fc.expansionDiscount ?: defaults.expansionDiscount,
                    minExpansionSeedScore = fc.minExpansionSeedScore ?: defaults.minExpansionSeedScore,
                    minExpansionResultScore = fc.minExpansionResultScore ?: defaults.minExpansionResultScore,
                    perSeedExpansionCap = fc.perSeedExpansionCap ?: defaults.perSeedExpansionCap,
                    gamedataWorldNodePenalty = fc.gamedataWorldNodePenalty ?: defaults.gamedataWorldNodePenalty,
                    gamedataWorldNodeTypes = fc.gamedataWorldNodeTypes ?: defaults.gamedataWorldNodeTypes,
                    gamedataFetchLimit = fc.gamedataFetchLimit ?: defaults.gamedataFetchLimit,
                    rerankerProfile = fc.rerankerProfile ?: defaults.rerankerProfile,
                    jevRoutingEnabled = fc.jevRoutingEnabled ?: defaults.jevRoutingEnabled,
                    jevApiKey = fc.jevApiKey ?: defaults.jevApiKey,
                    jevModel = fc.jevModel ?: defaults.jevModel,
                    jevBaseUrl = fc.jevBaseUrl ?: defaults.jevBaseUrl,
                    jevCorpusThreshold = fc.jevCorpusThreshold ?: defaults.jevCorpusThreshold,
                    routedCandidatesPerCorpus = fc.routedCandidatesPerCorpus ?: defaults.routedCandidatesPerCorpus,
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
        val embeddingProfiles: Map<String, EmbeddingProfile>? = null,
        val corpusEmbeddingProfiles: Map<String, String>? = null,
        val visualRasterRoot: String? = null,
        val indexPath: String? = null,
        val resultsPerCorpus: Int? = null,
        val maxRelatedConnections: Int? = null,
        val activeVersion: String? = null,
        val docsGithubRepo: String? = null,
        val docsGithubBranch: String? = null,
        val docsLanguage: String? = null,
        val indexPatchlines: List<String>? = null,
        val enabledCorpora: List<String>? = null,
        val docsSources: List<String>? = null,
        val snippetMaxLength: Int? = null,
        val nodeContentMaxLength: Int? = null,
        val sourceMaxChars: Int? = null,
        val retentionCount: Int? = null,
        val gitToken: String? = null,
        val blogScorePenalty: Double? = null,
        val gamedataUnintentScoreFloor: Double? = null,
        val expansionDiscount: Double? = null,
        val minExpansionSeedScore: Double? = null,
        val minExpansionResultScore: Double? = null,
        val perSeedExpansionCap: Int? = null,
        val gamedataWorldNodePenalty: Double? = null,
        val gamedataWorldNodeTypes: List<String>? = null,
        val gamedataFetchLimit: Int? = null,
        val rerankerProfile: RerankerProfile? = null,
        val jevRoutingEnabled: Boolean? = null,
        val jevApiKey: String? = null,
        val jevModel: String? = null,
        val jevBaseUrl: String? = null,
        val jevCorpusThreshold: Double? = null,
        val routedCandidatesPerCorpus: Int? = null,
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
