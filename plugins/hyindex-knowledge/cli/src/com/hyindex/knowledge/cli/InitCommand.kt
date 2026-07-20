// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.cli

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.source.GitSourceProvider
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
    val rerankerModel: String? = null,
    val rerankerTopN: Int? = null,
    val nonInteractive: Boolean = false,
    val help: Boolean = false,
) {
    companion object {
        val USAGE = """
            Usage: java -jar hyindex-knowledge-indexer.jar init [options]

              With a TTY, prompts for PAT, provider, embedding URL, API key,
              code model, text model, optional dimensions, and optional reranker
              (blank keeps the shown default / leaves empty).

              --provider <name>        openai (default) | voyage | cohere | gemini | jina |
                                       mistral | mixedbread | ollama | local | <any>
                                       openai / any other name = OpenAI-compatible /v1/embeddings
                                       voyage = Voyage protocol; cohere = Cohere /v1/embed
                                       gemini = native Google :embedContent (or .../openai shim)
                                       local requires optional hyindex-embeddings-local.jar
              --git-token <token>      GitHub PAT for private shared-source (optional)
              --embedding-url <url>    Embedding API base URL (optional; any endpoint for that protocol)
              --api-key <key>          Embedding API key / token (optional)
              --code-model <model>     Model for source-code embeddings (optional)
              --text-model <model>     Model for docs / gamedata / UI chunk embeddings (optional)
              --dimensions <n>         Optional output dimensions when the provider supports it
              --reranker-model <model> Optional Voyage-compatible reranker model
              --reranker-top-n <n>     Candidate pool when reranker is enabled (default 50)
              --non-interactive        Never prompt; use flags / defaults only
              --force                  Overwrite existing ~/.hyindex/knowledge/mcp-config.json
              --help                   Show this help
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
            var rerankerModel: String? = null
            var rerankerTopN: Int? = null
            var nonInteractive = false
            var help = false
            var i = 0
            while (i < args.size) {
                when (val a = args[i]) {
                    "--force" -> force = true
                    "--help", "-h" -> help = true
                    "--non-interactive" -> nonInteractive = true
                    "--provider" -> provider = args.getOrElse(++i) { error("missing value for --provider") }
                    "--git-token" -> gitToken = args.getOrElse(++i) { error("missing value for --git-token") }
                    "--embedding-url" -> embeddingBaseUrl = args.getOrElse(++i) { error("missing value for --embedding-url") }
                    "--api-key" -> apiKey = args.getOrElse(++i) { error("missing value for --api-key") }
                    "--code-model" -> codeModel = args.getOrElse(++i) { error("missing value for --code-model") }
                    "--text-model" -> textModel = args.getOrElse(++i) { error("missing value for --text-model") }
                    "--dimensions" -> dimensions = args.getOrElse(++i) { error("missing value for --dimensions") }.toInt()
                    "--reranker-model" -> rerankerModel = args.getOrElse(++i) { error("missing value for --reranker-model") }
                    "--reranker-top-n" -> rerankerTopN = args.getOrElse(++i) { error("missing value for --reranker-top-n") }.toInt()
                    else -> error("unknown init arg: $a")
                }
                i++
            }
            return InitArgs(
                force = force,
                provider = provider?.lowercase()?.ifBlank { null },
                gitToken = gitToken,
                embeddingBaseUrl = embeddingBaseUrl,
                apiKey = apiKey,
                codeModel = codeModel?.ifBlank { null },
                textModel = textModel?.ifBlank { null },
                dimensions = dimensions,
                rerankerModel = rerankerModel?.ifBlank { null },
                rerankerTopN = rerankerTopN,
                nonInteractive = nonInteractive,
                help = help,
            )
        }
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

    val answers = collectAnswers(opts)
    val config = buildConfig(answers)
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
    val rerankerModel: String? = null,
    val rerankerTopN: Int? = null,
)

internal fun collectAnswers(opts: InitArgs): InitAnswers {
    val interactive = opts.nonInteractive.not() && System.console() != null
    val defaultProvider = opts.provider ?: "openai"

    if (interactive.not()) {
        return InitAnswers(
            provider = defaultProvider,
            gitToken = opts.gitToken?.ifBlank { null },
            embeddingBaseUrl = opts.embeddingBaseUrl ?: "",
            apiKey = opts.apiKey ?: "",
            codeModel = opts.codeModel,
            textModel = opts.textModel,
            dimensions = opts.dimensions,
            rerankerModel = opts.rerankerModel,
            rerankerTopN = opts.rerankerTopN,
        )
    }

    println("Hyindex setup — leave blank to keep the default / leave empty.")
    val gitToken = prompt("GitHub PAT / gitToken (optional)", opts.gitToken ?: "")
    val provider = prompt(
        "Embedding provider [openai|voyage|cohere|gemini|jina|mistral|mixedbread|ollama|local|<name>]",
        defaultProvider,
    ).ifBlank { "openai" }.lowercase()
    val urlDefault = opts.embeddingBaseUrl ?: defaultUrlFor(provider)
    val embeddingBaseUrl = prompt("Embedding base URL (optional)", urlDefault)
    val apiKey = prompt("Embedding API key / token (optional)", opts.apiKey ?: "")
    val defaults = providerDefaults(provider, embeddingBaseUrl, apiKey, gitToken.ifBlank { null })
    val codeModel = prompt(
        "Code embedding model (source)",
        opts.codeModel ?: defaults.embeddingCodeModel,
    )
    val textModel = prompt(
        "Text embedding model (docs / gamedata / UI chunks)",
        opts.textModel ?: defaults.embeddingTextModel,
    )
    val dimDefault = (opts.dimensions ?: defaults.embeddingDimensions)?.toString() ?: ""
    val dimensionsRaw = prompt("Embedding dimensions (optional)", dimDefault)
    val dimensions = dimensionsRaw.ifBlank { null }?.toIntOrNull()
    val rerankerModel = prompt(
        "Reranker model (optional, blank = disabled)",
        opts.rerankerModel ?: "",
    ).ifBlank { null }
    val rerankerTopN = if (rerankerModel == null) {
        null
    } else {
        val topDefault = (opts.rerankerTopN ?: 50).toString()
        prompt("Reranker top-N candidate pool", topDefault).ifBlank { topDefault }.toIntOrNull()
    }
    return InitAnswers(
        provider = provider,
        gitToken = gitToken.ifBlank { null },
        embeddingBaseUrl = embeddingBaseUrl,
        apiKey = apiKey,
        codeModel = codeModel.ifBlank { null },
        textModel = textModel.ifBlank { null },
        dimensions = dimensions,
        rerankerModel = rerankerModel,
        rerankerTopN = rerankerTopN,
    )
}

internal fun buildConfig(a: InitAnswers): KnowledgeConfig {
    val base = providerDefaults(a.provider, a.embeddingBaseUrl, a.apiKey, a.gitToken)
    val rerankerModel = a.rerankerModel?.ifBlank { null }
    return base.copy(
        embeddingCodeModel = a.codeModel?.ifBlank { null } ?: base.embeddingCodeModel,
        embeddingTextModel = a.textModel?.ifBlank { null } ?: base.embeddingTextModel,
        embeddingDimensions = a.dimensions ?: base.embeddingDimensions,
        rerankerEnabled = rerankerModel != null,
        rerankerModel = rerankerModel ?: base.rerankerModel,
        rerankerTopN = a.rerankerTopN ?: base.rerankerTopN,
    )
}

internal fun providerDefaults(
    provider: String,
    embeddingBaseUrl: String,
    apiKey: String,
    gitToken: String?,
): KnowledgeConfig {
    val base = KnowledgeConfig(
        embeddingProvider = provider,
        gitRepoUrl = GitSourceProvider.DEFAULT_REPO_URL,
        gitToken = gitToken,
        embeddingApiKey = apiKey,
        embeddingBaseUrl = embeddingBaseUrl,
    )
    return when (provider.lowercase()) {
        "ollama" -> base.copy(
            embeddingBaseUrl = embeddingBaseUrl.ifBlank { "http://localhost:11434" },
            embeddingCodeModel = "qwen3-embedding:8b",
            embeddingTextModel = "nomic-embed-text-v2-moe",
        )
        "voyage" -> base.copy(
            embeddingBaseUrl = embeddingBaseUrl.ifBlank { "https://api.voyageai.com" },
            embeddingCodeModel = "voyage-code-3",
            embeddingTextModel = "voyage-4-large",
        )
        "cohere" -> base.copy(
            embeddingBaseUrl = embeddingBaseUrl.ifBlank { "https://api.cohere.com" },
            embeddingCodeModel = "embed-v4.0",
            embeddingTextModel = "embed-v4.0",
        )
        "gemini" -> base.copy(
            embeddingBaseUrl = embeddingBaseUrl.ifBlank {
                "https://generativelanguage.googleapis.com/v1beta"
            },
            embeddingCodeModel = "gemini-embedding-001",
            embeddingTextModel = "gemini-embedding-001",
        )
        "jina" -> base.copy(
            embeddingBaseUrl = embeddingBaseUrl.ifBlank { "https://api.jina.ai" },
            embeddingCodeModel = "jina-embeddings-v3",
            embeddingTextModel = "jina-embeddings-v3",
            embeddingDimensions = 1024,
        )
        "mistral" -> base.copy(
            embeddingBaseUrl = embeddingBaseUrl.ifBlank { "https://api.mistral.ai" },
            embeddingCodeModel = "codestral-embed-2505",
            embeddingTextModel = "mistral-embed",
        )
        "mixedbread", "mxbai" -> base.copy(
            embeddingProvider = "mixedbread",
            embeddingBaseUrl = embeddingBaseUrl.ifBlank { "https://api.mixedbread.ai" },
            embeddingCodeModel = "mxbai-embed-large",
            embeddingTextModel = "mxbai-embed-large",
        )
        "local" -> base.copy(
            embeddingBaseUrl = embeddingBaseUrl,
        )
        else -> base.copy(
            embeddingBaseUrl = embeddingBaseUrl,
            embeddingCodeModel = "text-embedding-3-large",
            embeddingTextModel = "text-embedding-3-large",
        )
    }
}

internal fun defaultUrlFor(provider: String): String = when (provider.lowercase()) {
    "ollama" -> "http://localhost:11434"
    "voyage" -> "https://api.voyageai.com"
    "cohere" -> "https://api.cohere.com"
    "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
    "jina" -> "https://api.jina.ai"
    "mistral" -> "https://api.mistral.ai"
    "mixedbread", "mxbai" -> "https://api.mixedbread.ai"
    "local" -> ""
    "openai" -> "https://api.openai.com"
    else -> ""
}

private fun prompt(label: String, default: String): String {
    val suffix = if (default.isEmpty()) "" else " [$default]"
    print("$label$suffix: ")
    System.out.flush()
    val line = readlnOrNull() ?: ""
    return if (line.isEmpty()) default else line
}

private fun printNextSteps(configFile: File, config: KnowledgeConfig) {
    println()
    println("Config:")
    println("  gitRepoUrl         = ${config.gitRepoUrl}")
    println("  gitToken           = ${if (config.gitToken.isNullOrBlank()) "(empty)" else "(set)"}")
    println("  embeddingProvider  = ${config.embeddingProvider}")
    println("  embeddingBaseUrl   = ${config.embeddingBaseUrl.ifBlank { "(empty)" }}")
    println("  embeddingApiKey    = ${if (config.embeddingApiKey.isBlank()) "(empty)" else "(set)"}")
    println("  code/text models   = ${config.embeddingCodeModel} / ${config.embeddingTextModel}")
    println("  dimensions         = ${config.embeddingDimensions ?: "(default)"}")
    println("  reranker           = ${if (config.rerankerEnabled) "${config.rerankerModel} (topN=${config.rerankerTopN})" else "(disabled)"}")
    println("  index path         = ${config.resolvedBasePath().absolutePath}")
    println()
    println("Next:")
    when (config.embeddingProvider.lowercase()) {
        "local" -> println("  - put hyindex-embeddings-local.jar on the classpath (./gradlew :embeddings-local:shadowJar)")
        "ollama" -> {
            println("  - ensure Ollama is running at ${config.embeddingBaseUrl.ifBlank { "http://localhost:11434" }}")
            println("  - pull models: ${config.embeddingCodeModel} and ${config.embeddingTextModel}")
        }
        "voyage" -> println("  - Voyage uses /v1/embeddings (+ /v1/contextualizedembeddings for voyage-context-*)")
        "cohere" -> println("  - Cohere uses /v1/embed with input_type + embeddingApiKey")
        "gemini" -> println("  - Gemini uses native :embedContent (or .../openai OpenAI-compatible shim)")
        "jina" -> println("  - Jina uses /v1/embeddings with task + late_chunking")
        "mistral", "mixedbread", "mxbai" -> println("  - OpenAI-compatible /v1/embeddings at provider base URL")
        else -> println("  - OpenAI-compatible /v1/embeddings at embeddingBaseUrl (blank → https://api.openai.com)")
    }
    println("  - Index:  java -jar hyindex-knowledge-indexer.jar --patchline release")
    println("  - Serve:  java -jar hyindex-knowledge-mcp.jar")
}
