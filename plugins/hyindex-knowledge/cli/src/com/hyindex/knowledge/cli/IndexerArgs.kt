// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.cli
import com.hyindex.knowledge.core.config.KnowledgeConfig

data class IndexerArgs(
    val patchlines: Set<String>,
    val corpora: Set<String>,
    val docsSources: Set<String>,
    val force: Boolean,
    val reembed: Boolean = false,
    val allowClear: Boolean = false,
    val help: Boolean = false,
    val patchlinesExplicit: Boolean = false,
    val corporaExplicit: Boolean = false,
    val docsSourcesExplicit: Boolean = false,
) {
    companion object {
        private val ALL_PATCHLINES = KnowledgeConfig.INDEX_PATCHLINE_IDS
        private val ALL_CORPORA = KnowledgeConfig.DEFAULT_ENABLED_CORPORA.toSet()
        private val DOCS_SOURCES = KnowledgeConfig.DOCS_SOURCE_IDS

        val USAGE = """
            Hyindex Knowledge Indexer

            Usage:
              java -jar hyindex-knowledge-indexer.jar init [options]   # write default config
              java -jar hyindex-knowledge-indexer.jar [options]        # build indexes
              java -jar hyindex-knowledge-indexer.jar eval [options]   # run golden-set eval

            The Hytale source repository is:
              https://github.com/HypixelStudios/hytale-shared-source.git
            Authenticate private access with "gitToken" in config.json.

            Options:
              --patchline <name>    release | pre-release | all          (default: config.json)
              --corpus <list>       code,gamedata,client,docs[,visual]   (default: config.json)
              --docs-source <list>  official,modding,blog,support,server | all   (default: config.json)
              --force               re-clone/re-index, ignore caches
              --reembed             re-embed the EXISTING index in place (apply a config change
                                    such as a new model); skips source fetch and the version-change
                                    wipe. Non-destructive.
              --allow-clear         permit wiping an existing index when the requested version
                                    differs from the indexed one (off by default to avoid
                                    accidentally destroying an index that cannot be rebuilt)
              -h, --help            show this help and exit

            Examples:
              # First-time setup (writes ~/.hyindex/knowledge/config.json)
              java -jar hyindex-knowledge-indexer.jar init

              # Reindex only the pre-release patchline
              java -jar hyindex-knowledge-indexer.jar --patchline pre-release

              # Reindex everything from scratch
              java -jar hyindex-knowledge-indexer.jar --force

              # Only the code corpus for release
              java -jar hyindex-knowledge-indexer.jar --patchline release --corpus code

              # Re-embed existing code in place after a model change (cheap; cache-aware)
              java -jar hyindex-knowledge-indexer.jar --patchline all --corpus code --reembed
        """.trimIndent()

        fun parse(args: Array<String>): IndexerArgs {
            val m = HashMap<String, String>(); var force = false; var help = false
            var reembed = false; var allowClear = false
            var i = 0
            while (i < args.size) {
                when (val k = args[i]) {
                    "--help", "-h" -> help = true
                    "--force" -> force = true
                    "--reembed" -> reembed = true
                    "--allow-clear" -> allowClear = true
                    "--patchline", "--corpus", "--docs-source" ->
                        m[k] = args.getOrElse(++i) { error("missing value for $k") }
                    else -> error("unknown arg: $k")
                }; i++
            }
            val patchlinesExplicit = m.containsKey("--patchline")
            val patch = when (val p = m["--patchline"]) { null, "all" -> ALL_PATCHLINES; else -> setOf(p) }
                .also { selected ->
                    val unknown = selected - KnowledgeConfig.INDEX_PATCHLINE_IDS
                    check(unknown.isEmpty()) { "bad --patchline: $unknown" }
                }
            val corporaExplicit = m.containsKey("--corpus")
            val corpora = m["--corpus"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: ALL_CORPORA
            val unknownCorpora = corpora - KnowledgeConfig.CORPUS_IDS
            check(unknownCorpora.isEmpty()) { "bad --corpus: $unknownCorpora" }
            val docsSourcesExplicit = m.containsKey("--docs-source")
            val docsSources = when (val v = m["--docs-source"]) {
                null, "all" -> DOCS_SOURCES
                else -> v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet().also {
                    val bad = it - DOCS_SOURCES - "all"; check(bad.isEmpty()) { "bad --docs-source: $bad" }
                }.let { if ("all" in it) DOCS_SOURCES else it }
            }
            return IndexerArgs(
                patch, corpora, docsSources, force, reembed, allowClear, help,
                patchlinesExplicit, corporaExplicit, docsSourcesExplicit,
            )
        }

        fun resolvePatchlines(args: IndexerArgs, config: KnowledgeConfig): Set<String> =
            if (args.patchlinesExplicit) args.patchlines else config.resolvedIndexPatchlines()

        fun resolveCorpora(args: IndexerArgs, config: KnowledgeConfig): Set<String> =
            if (args.corporaExplicit) args.corpora else config.resolvedEnabledCorpora()

        fun resolveDocsSources(args: IndexerArgs, config: KnowledgeConfig): Set<String> =
            if (args.docsSourcesExplicit) args.docsSources else config.resolvedDocsSources()
    }
}
