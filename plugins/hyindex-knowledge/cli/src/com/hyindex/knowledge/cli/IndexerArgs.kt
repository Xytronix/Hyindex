// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.cli

data class IndexerArgs(
    val patchlines: Set<String>,
    val corpora: Set<String>,
    val docsSources: Set<String>,
    val force: Boolean,
    val reembed: Boolean = false,
    val allowClear: Boolean = false,
    val help: Boolean = false,
) {
    companion object {
        private val ALL_PATCHLINES = setOf("release", "pre-release")
        private val ALL_CORPORA = setOf("code", "gamedata", "client", "docs")
        private val DOCS_SOURCES = setOf("modding", "blog", "support", "server")

        val USAGE = """
            Hyindex Knowledge Indexer

            Usage:
              java -jar hyindex-knowledge-indexer.jar init [options]   # write default config
              java -jar hyindex-knowledge-indexer.jar [options]        # build indexes
              java -jar hyindex-knowledge-indexer.jar eval [options]   # run golden-set eval

            The Hytale source repo defaults to:
              https://github.com/HypixelStudios/hytale-shared-source.git
            Override with "gitRepoUrl" / authenticate with "gitToken" in mcp-config.json.

            Options:
              --patchline <name>    release | pre-release | all          (default: all)
              --corpus <list>       code,gamedata,client,docs            (default: all)
              --docs-source <list>  modding,blog,support,server | all     (default: all)
              --force               re-clone/re-index, ignore caches
              --reembed             re-embed the EXISTING index in place (apply a config change
                                    such as a new model); skips source fetch and the version-change
                                    wipe. Non-destructive.
              --allow-clear         permit wiping an existing index when the requested version
                                    differs from the indexed one (off by default to avoid
                                    accidentally destroying an index that cannot be rebuilt)
              -h, --help            show this help and exit

            Examples:
              # First-time setup (writes ~/.hyindex/knowledge/mcp-config.json)
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
            val patch = when (val p = m["--patchline"]) { null, "all" -> ALL_PATCHLINES; else -> setOf(p) }
            val corpora = m["--corpus"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: ALL_CORPORA
            val docsSources = when (val v = m["--docs-source"]) {
                null, "all" -> DOCS_SOURCES
                else -> v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet().also {
                    val bad = it - DOCS_SOURCES - "all"; check(bad.isEmpty()) { "bad --docs-source: $bad" }
                }.let { if ("all" in it) DOCS_SOURCES else it }
            }
            return IndexerArgs(patch, corpora, docsSources, force, reembed, allowClear, help)
        }
    }
}
