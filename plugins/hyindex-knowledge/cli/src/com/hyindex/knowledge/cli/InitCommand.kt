// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.cli

import com.hyindex.knowledge.core.config.EmbeddingProfile
import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.config.RerankerProfile
import com.hyindex.knowledge.core.db.Corpus
import java.io.File

data class InitArgs(
    val force: Boolean = false,
    val provider: String? = null,
    val gitToken: String? = null,
    val embeddingBaseUrl: String? = null,
    val apiKey: String? = null,
    val codeModel: String? = null,
    val textModel: String? = null,
    val dimensions: Int? = null,
    val rerankerProvider: String? = null,
    val rerankerProtocol: String? = null,
    val rerankerBaseUrl: String? = null,
    val rerankerApiKey: String? = null,
    val rerankerModel: String? = null,
    val rerankerTopN: Int? = null,
    val nonInteractive: Boolean = false,
    val help: Boolean = false,
) {
    companion object {
        val USAGE = """
            Usage: java -jar hyindex-knowledge-indexer.jar init [options]

              --provider <name>             Embedding provider (default: openai)
              --embedding-url <url>         Embedding API base URL
              --api-key <key>               Embedding API key / token
              --code-model <model>          Source-code embedding model
              --text-model <model>          Docs / gamedata / UI embedding model
              --dimensions <n>              Output dimensions when supported
              --git-token <token>           GitHub PAT for private shared-source
              --reranker-provider <name>    Voyage, Cohere, Jina, or a custom service
              --reranker-protocol <name>    voyage | cohere | jina (required for custom providers)
              --reranker-url <url>          Independent reranker API base URL
              --reranker-api-key <key>      Independent reranker API key
              --reranker-model <model>      Reranker model; omit to disable reranking
              --reranker-top-n <n>          Candidate pool (default: 50)
              --non-interactive             Never prompt; use flags / defaults only
              --force                       Overwrite existing configuration
              --help                        Show this help
        """.trimIndent()

        fun parse(args: List<String>): InitArgs {
            var force = false
            var provider: String? = null
            var gitToken: String? = null
            var embeddingBaseUrl: String? = null
            var apiKey: String? = null
            var codeModel: String? = null
            var textModel: String? = null
            var dimensions: Int? = null
            var rerankerProvider: String? = null
            var rerankerProtocol: String? = null
            var rerankerBaseUrl: String? = null
            var rerankerApiKey: String? = null
            var rerankerModel: String? = null
            var rerankerTopN: Int? = null
            var nonInteractive = false
            var help = false
            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--force" -> force = true
                    "--help", "-h" -> help = true
                    "--non-interactive" -> nonInteractive = true
                    "--provider" -> provider = args.valueAfter(++i, "--provider")
                    "--git-token" -> gitToken = args.valueAfter(++i, "--git-token")
                    "--embedding-url" -> embeddingBaseUrl = args.valueAfter(++i, "--embedding-url")
                    "--api-key" -> apiKey = args.valueAfter(++i, "--api-key")
                    "--code-model" -> codeModel = args.valueAfter(++i, "--code-model")
                    "--text-model" -> textModel = args.valueAfter(++i, "--text-model")
                    "--dimensions" -> dimensions = args.valueAfter(++i, "--dimensions").toInt()
                    "--reranker-provider" -> rerankerProvider = args.valueAfter(++i, "--reranker-provider")
                    "--reranker-protocol" -> rerankerProtocol = args.valueAfter(++i, "--reranker-protocol")
                    "--reranker-url" -> rerankerBaseUrl = args.valueAfter(++i, "--reranker-url")
                    "--reranker-api-key" -> rerankerApiKey = args.valueAfter(++i, "--reranker-api-key")
                    "--reranker-model" -> rerankerModel = args.valueAfter(++i, "--reranker-model")
                    "--reranker-top-n" -> rerankerTopN = args.valueAfter(++i, "--reranker-top-n").toInt()
                    else -> error("unknown init arg: $arg")
                }
                i++
            }
            return InitArgs(
                force = force,
                provider = provider.normalized(),
                gitToken = gitToken,
                embeddingBaseUrl = embeddingBaseUrl,
                apiKey = apiKey,
                codeModel = codeModel.nonBlank(),
                textModel = textModel.nonBlank(),
                dimensions = dimensions,
                rerankerProvider = rerankerProvider.normalized(),
                rerankerProtocol = rerankerProtocol.normalized(),
                rerankerBaseUrl = rerankerBaseUrl,
                rerankerApiKey = rerankerApiKey,
                rerankerModel = rerankerModel.nonBlank(),
                rerankerTopN = rerankerTopN,
                nonInteractive = nonInteractive,
                help = help,
            )
        }

        private fun List<String>.valueAfter(index: Int, flag: String): String =
            getOrElse(index) { error("missing value for $flag") }

        private fun String?.normalized(): String? = nonBlank()?.lowercase()
        private fun String?.nonBlank(): String? = this?.ifBlank { null }
    }
}

fun runInit(args: List<String>) {
    val opts = InitArgs.parse(args)
    if (opts.help) {
        println(InitArgs.USAGE)
        return
    }

    val configFile = KnowledgeConfig.configFilePath()
    if (configFile.exists() && !opts.force) {
        println("Config already exists: ${configFile.absolutePath}")
        println("Re-run with --force to overwrite, or edit the file directly.")
        printNextSteps(configFile, KnowledgeConfig.loadFromFile(configFile) ?: KnowledgeConfig())
        return
    }

    val config = buildConfig(collectAnswers(opts))
    KnowledgeConfig.writeToFile(config, configFile)
    println("Wrote ${configFile.absolutePath}")
    printNextSteps(configFile, config)
}

internal data class InitAnswers(
    val provider: String,
    val gitToken: String?,
    val embeddingBaseUrl: String,
    val apiKey: String,
    val codeModel: String? = null,
    val textModel: String? = null,
    val dimensions: Int? = null,
    val rerankerProvider: String? = null,
    val rerankerProtocol: String? = null,
    val rerankerBaseUrl: String? = null,
    val rerankerApiKey: String? = null,
    val rerankerModel: String? = null,
    val rerankerTopN: Int? = null,
)

internal data class ProviderDefaults(
    val provider: String,
    val baseUrl: String,
    val codeModel: String,
    val textModel: String,
    val codeDimensions: Int?,
    val textDimensions: Int?,
    val imageModel: String? = null,
    val imageDimensions: Int? = null,
)

internal fun collectAnswers(opts: InitArgs): InitAnswers {
    val interactive = !opts.nonInteractive && System.console() != null
    val provider = opts.provider ?: "openai"
    if (!interactive) {
        return InitAnswers(
            provider = provider,
            gitToken = opts.gitToken?.ifBlank { null },
            embeddingBaseUrl = opts.embeddingBaseUrl.orEmpty(),
            apiKey = opts.apiKey.orEmpty(),
            codeModel = opts.codeModel,
            textModel = opts.textModel,
            dimensions = opts.dimensions,
            rerankerProvider = opts.rerankerProvider,
            rerankerProtocol = opts.rerankerProtocol,
            rerankerBaseUrl = opts.rerankerBaseUrl,
            rerankerApiKey = opts.rerankerApiKey,
            rerankerModel = opts.rerankerModel,
            rerankerTopN = opts.rerankerTopN,
        )
    }

    println("Hyindex setup — leave blank to keep the default / leave empty.")
    val gitToken = prompt("GitHub PAT / gitToken (optional)", opts.gitToken.orEmpty())
    val selectedProvider = prompt(
        "Embedding provider [openai|voyage|cohere|gemini|jina|mistral|mixedbread|ollama|local|<name>]",
        provider,
    ).ifBlank { "openai" }.lowercase()
    val embeddingBaseUrl = prompt(
        "Embedding base URL",
        opts.embeddingBaseUrl ?: defaultUrlFor(selectedProvider),
    )
    val apiKey = prompt("Embedding API key / token (optional)", opts.apiKey.orEmpty())
    val defaults = providerDefaults(selectedProvider, embeddingBaseUrl)
    val codeModel = prompt("Code embedding model", opts.codeModel ?: defaults.codeModel)
    val textModel = prompt("Text embedding model", opts.textModel ?: defaults.textModel)
    val dimensionDefault = opts.dimensions?.toString().orEmpty()
    val dimensions = prompt("Embedding dimensions (optional)", dimensionDefault)
        .ifBlank { null }
        ?.toIntOrNull()

    val rerankerModel = prompt(
        "Reranker model (optional, blank = disabled)",
        opts.rerankerModel.orEmpty(),
    ).ifBlank { null }
    var rerankerProvider = opts.rerankerProvider
    var rerankerProtocol = opts.rerankerProtocol
    var rerankerBaseUrl = opts.rerankerBaseUrl
    var rerankerApiKey = opts.rerankerApiKey
    var rerankerTopN = opts.rerankerTopN
    if (rerankerModel != null) {
        rerankerProvider = prompt("Reranker provider", rerankerProvider ?: "voyage")
            .ifBlank { "voyage" }
            .lowercase()
        rerankerProtocol = prompt(
            "Reranker protocol [voyage|cohere|jina]",
            rerankerProtocol ?: defaultRerankerProtocolFor(rerankerProvider),
        ).ifBlank { null }
        rerankerBaseUrl = prompt(
            "Reranker base URL",
            rerankerBaseUrl ?: defaultRerankerUrlFor(rerankerProvider),
        )
        rerankerApiKey = prompt("Reranker API key (optional)", rerankerApiKey.orEmpty())
        rerankerTopN = prompt("Reranker candidate pool", (rerankerTopN ?: 50).toString())
            .toIntOrNull()
    }

    return InitAnswers(
        provider = selectedProvider,
        gitToken = gitToken.ifBlank { null },
        embeddingBaseUrl = embeddingBaseUrl,
        apiKey = apiKey,
        codeModel = codeModel.ifBlank { null },
        textModel = textModel.ifBlank { null },
        dimensions = dimensions,
        rerankerProvider = rerankerProvider,
        rerankerProtocol = rerankerProtocol,
        rerankerBaseUrl = rerankerBaseUrl,
        rerankerApiKey = rerankerApiKey,
        rerankerModel = rerankerModel,
        rerankerTopN = rerankerTopN,
    )
}

internal fun buildConfig(answers: InitAnswers): KnowledgeConfig {
    val defaults = providerDefaults(answers.provider, answers.embeddingBaseUrl)
    val code = EmbeddingProfile(
        provider = defaults.provider,
        baseUrl = defaults.baseUrl,
        apiKey = answers.apiKey,
        documentModel = answers.codeModel ?: defaults.codeModel,
        dimensions = answers.dimensions ?: defaults.codeDimensions,
        concurrency = 4,
    )
    val text = EmbeddingProfile(
        provider = defaults.provider,
        baseUrl = defaults.baseUrl,
        apiKey = answers.apiKey,
        documentModel = answers.textModel ?: defaults.textModel,
        dimensions = answers.dimensions ?: defaults.textDimensions,
        concurrency = 4,
    )
    val profiles = linkedMapOf("code" to code, "text" to text)
    val assignments = linkedMapOf(
        Corpus.CODE.id to "code",
        Corpus.DOCS.id to "text",
        Corpus.GAMEDATA.id to "text",
        Corpus.CLIENT.id to "text",
    )
    defaults.imageModel?.let { imageModel ->
        profiles["visual"] = EmbeddingProfile(
            provider = defaults.provider,
            baseUrl = defaults.baseUrl,
            apiKey = answers.apiKey,
            documentModel = imageModel,
            dimensions = answers.dimensions ?: defaults.imageDimensions,
            concurrency = 4,
        )
        assignments[Corpus.VISUAL.id] = "visual"
    }

    val reranker = answers.rerankerModel?.let { model ->
        val provider = answers.rerankerProvider?.ifBlank { null } ?: "voyage"
        RerankerProfile(
            provider = provider,
            baseUrl = answers.rerankerBaseUrl?.ifBlank { null } ?: defaultRerankerUrlFor(provider),
            apiKey = answers.rerankerApiKey.orEmpty(),
            model = model,
            protocol = answers.rerankerProtocol.orEmpty(),
            topN = answers.rerankerTopN ?: 50,
        )
    }

    return KnowledgeConfig(
        embeddingProfiles = profiles,
        corpusEmbeddingProfiles = assignments,
        gitToken = answers.gitToken,
        rerankerProfile = reranker,
    )
}

internal fun providerDefaults(provider: String, baseUrl: String = ""): ProviderDefaults =
    when (provider.lowercase()) {
        "ollama" -> ProviderDefaults(
            "ollama",
            baseUrl.ifBlank { "http://localhost:11434" },
            "qwen3-embedding:8b",
            "nomic-embed-text-v2-moe",
            4096,
            768,
        )
        "voyage" -> ProviderDefaults(
            "voyage",
            baseUrl.ifBlank { "https://api.voyageai.com" },
            "voyage-code-4",
            "voyage-4-large",
            1024,
            1024,
            "voyage-multimodal-3.5",
            1024,
        )
        "cohere" -> ProviderDefaults(
            "cohere",
            baseUrl.ifBlank { "https://api.cohere.com" },
            "embed-v4.0",
            "embed-v4.0",
            1024,
            1024,
        )
        "gemini" -> ProviderDefaults(
            "gemini",
            baseUrl.ifBlank { "https://generativelanguage.googleapis.com/v1beta" },
            "gemini-embedding-2",
            "gemini-embedding-2",
            1024,
            1024,
            "gemini-embedding-2",
            1024,
        )
        "jina" -> ProviderDefaults(
            "jina",
            baseUrl.ifBlank { "https://api.jina.ai" },
            "jina-embeddings-v3",
            "jina-embeddings-v3",
            1024,
            1024,
        )
        "mistral" -> ProviderDefaults(
            "mistral",
            baseUrl.ifBlank { "https://api.mistral.ai" },
            "codestral-embed-2505",
            "mistral-embed",
            1536,
            1024,
        )
        "mixedbread", "mxbai" -> ProviderDefaults(
            "mixedbread",
            baseUrl.ifBlank { "https://api.mixedbread.ai" },
            "mxbai-embed-large",
            "mxbai-embed-large",
            1024,
            1024,
        )
        "local" -> ProviderDefaults(
            "local",
            baseUrl,
            "all-minilm-l6-v2-q",
            "all-minilm-l6-v2-q",
            384,
            384,
        )
        "openai" -> ProviderDefaults(
            "openai",
            baseUrl.ifBlank { "https://api.openai.com" },
            "text-embedding-3-large",
            "text-embedding-3-large",
            3072,
            3072,
        )
        else -> ProviderDefaults(
            provider,
            baseUrl,
            "text-embedding-3-large",
            "text-embedding-3-large",
            null,
            null,
        )
    }

internal fun defaultUrlFor(provider: String): String = providerDefaults(provider).baseUrl

internal fun defaultRerankerProtocolFor(provider: String): String = when (provider.lowercase()) {
    "voyage" -> "voyage"
    "cohere" -> "cohere"
    "jina" -> "jina"
    else -> ""
}

internal fun defaultRerankerUrlFor(provider: String): String = when (provider.lowercase()) {
    "voyage" -> "https://api.voyageai.com"
    "cohere" -> "https://api.cohere.com"
    "jina" -> "https://api.jina.ai"
    else -> ""
}

private fun prompt(label: String, default: String): String {
    val suffix = if (default.isEmpty()) "" else " [$default]"
    print("$label$suffix: ")
    System.out.flush()
    val line = readlnOrNull().orEmpty()
    return if (line.isEmpty()) default else line
}

private fun printNextSteps(configFile: File, config: KnowledgeConfig) {
    val code = config.resolvedEmbeddingProfile(Corpus.CODE)
    println()
    println("Config:")
    println("  gitToken           = ${if (config.gitToken.isNullOrBlank()) "(empty)" else "(set)"}")
    println("  embedding profiles = ${config.embeddingProfiles.keys.joinToString(",")}")
    println("  corpus profiles    = ${config.corpusEmbeddingProfiles.entries.joinToString(",") { "${it.key}:${it.value}" }}")
    println("  reranker           = ${config.rerankerProfile?.let { "${it.provider}:${it.model} (topN=${it.topN})" } ?: "(disabled)"}")
    println("  index path         = ${config.resolvedBasePath().absolutePath}")
    println()
    println("Next:")
    when (code.provider.lowercase()) {
        "local" -> println("  - put hyindex-embeddings-local.jar on the classpath")
        "ollama" -> println("  - ensure Ollama is running at ${code.baseUrl} and pull the configured models")
        "voyage" -> println("  - Voyage uses /v1/embeddings and contextualized embeddings when configured")
        "cohere" -> println("  - Cohere uses /v1/embed with input_type")
        "gemini" -> println("  - Gemini uses native :embedContent or its OpenAI-compatible shim")
        "jina" -> println("  - Jina uses /v1/embeddings with task and late_chunking")
        else -> println("  - ${code.provider} uses an OpenAI-compatible /v1/embeddings endpoint")
    }
    println("  - Edit:   ${configFile.absolutePath}")
    println("  - Index:  java -jar hyindex-knowledge-indexer.jar --patchline release")
    println("  - Serve:  java -jar hyindex-knowledge-mcp.jar")
}
