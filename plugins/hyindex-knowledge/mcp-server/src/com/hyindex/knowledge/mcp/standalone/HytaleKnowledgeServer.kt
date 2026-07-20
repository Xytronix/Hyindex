// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.mcp.standalone

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.diff.DiffCache
import com.hyindex.knowledge.core.diff.DiffEngine
import com.hyindex.knowledge.core.diff.DiffExporter
import com.hyindex.knowledge.core.index.CorpusIndexManager
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.version.VersionResolver
import com.hyindex.knowledge.core.search.GraphTraversal
import com.hyindex.knowledge.core.search.KnowledgeSearchService
import com.hyindex.knowledge.core.search.ResponseEncoder
import com.hyindex.knowledge.core.search.SearchResult
import com.hyindex.knowledge.core.search.Snippets
import java.io.File
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*

class HytaleKnowledgeServer(
    private val services: Map<String, KnowledgeSearchService>,
    private val databases: Map<String, KnowledgeDatabase> = emptyMap(),
    private val snippetMaxLength: Int = KnowledgeConfig().snippetMaxLength,
    private val config: KnowledgeConfig = KnowledgeConfig(),
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val basePath = config.resolvedBasePath()
    private val lazyServices = mutableMapOf<String, KnowledgeSearchService>()
    private val lazyDbs = mutableMapOf<String, KnowledgeDatabase>()
    private val latestSlugs by lazy { VersionResolver.resolveAll(basePath) }

    fun createServer(): Server {
        val server = Server(
            Implementation(name = "hyindex-knowledge", version = "1.0.0"),
            ServerOptions(capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                logging = null,
                experimental = null,
                prompts = null,
                resources = null,
            ))
        )

        server.addTools(listOf(
            searchHytaleTool(),
            searchCodeTool(),
            searchClientTool(),
            searchGamedataTool(),
            searchDocsTool(),
            statsTool(),
            diffVersionsTool(),
            getFilePathTool(),
            getHytaleClassTool(),
            searchHytaleTextTool(),
            getHytaleNodeTool(),
            getHytaleRelatedTool(),
            getHytaleRecipeTool(),
            getHytaleDropsTool(),
            getHytaleImplementingCodeTool(),
            getHytaleGamedataForCodeTool(),
            getHytaleUsagesTool(),
            getHytaleShopsTool(),
            getHytaleGroupMembersTool(),
            listVersionsTool(),
            getModdingContextTool(),
            getHytaleSourceTool(),
        ))

        return server
    }

    suspend fun run() {
        val server = createServer()
        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered(),
        )
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        transport.onClose { done.complete(Unit) }
        server.createSession(transport)
        System.err.println("[INFO] Hyindex Knowledge MCP server running on stdio")
        done.await()
    }


    private fun loadForSlug(slug: String): KnowledgeSearchService? {
        lazyServices[slug]?.let { return it }
        val slugConfig = config.copy(activeVersion = slug)
        val dbFile = File(slugConfig.resolvedIndexPath(), "knowledge.db")
        if (!dbFile.exists()) return null
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(dbFile, log)
        val indexManager = CorpusIndexManager(slugConfig, log)
        val service = KnowledgeSearchService(db, indexManager, log, slugConfig)
        lazyDbs[slug] = db
        lazyServices[slug] = service
        return service
    }

    private fun normalizePatchline(patchline: String): String {
        if ('@' in patchline || patchline in latestSlugs) return patchline
        val line = latestSlugs.keys.filter { patchline.startsWith("${it}_") }.maxByOrNull { it.length }
            ?: return patchline
        return "$line@${patchline.removePrefix("${line}_")}"
    }

    internal fun serviceForPatchline(patchline: String, warningOut: StringBuilder? = null): KnowledgeSearchService? {
        val ref = normalizePatchline(patchline)
        if ('@' !in ref) {
            services[ref]?.let { return it }
            val fallback = services.values.firstOrNull() ?: return null
            warningOut?.append(" [Note: patchline '$patchline' not loaded; using '${services.keys.first()}' instead.]")
            return fallback
        }
        val (line, version) = ref.split('@', limit = 2)
        val slug = VersionResolver.resolveSlug(basePath, line, version)
        if (slug == null) {
            warningOut?.append(" [Note: version '$version' not found for '$line'; using latest]")
            return services[line] ?: services.values.firstOrNull()
        }
        if (slug == latestSlugs[line]) return services[line] ?: services.values.firstOrNull()
        val loaded = lazyServices[slug] ?: loadForSlug(slug)
        if (loaded != null) return loaded
        warningOut?.append(" [Note: version '$version' could not be loaded for '$line'; using latest]")
        return services[line] ?: services.values.firstOrNull()
    }

    internal fun dbForPatchline(patchline: String, warningOut: StringBuilder? = null): KnowledgeDatabase? {
        val ref = normalizePatchline(patchline)
        if ('@' !in ref) {
            databases[ref]?.let { return it }
            val fallback = databases.values.firstOrNull() ?: return null
            warningOut?.append(" [Note: patchline '$patchline' not loaded; using '${databases.keys.first()}' instead.]")
            return fallback
        }
        val (line, version) = ref.split('@', limit = 2)
        val slug = VersionResolver.resolveSlug(basePath, line, version)
        if (slug == null) {
            warningOut?.append(" [Note: version '$version' not found for '$line'; using latest]")
            return databases[line] ?: databases.values.firstOrNull()
        }
        if (slug == latestSlugs[line]) return databases[line] ?: databases.values.firstOrNull()
        if (loadForSlug(slug) != null) return lazyDbs[slug]
        warningOut?.append(" [Note: version '$version' could not be loaded for '$line'; using latest]")
        return databases[line] ?: databases.values.firstOrNull()
    }

    internal fun sourceRootForPatchline(patchline: String): File? {
        val ref = normalizePatchline(patchline)
        val slug = if ('@' in ref) {
            val (line, version) = ref.split('@', limit = 2)
            VersionResolver.resolveSlug(basePath, line, version) ?: latestSlugs[line]
        } else {
            latestSlugs[ref] ?: latestSlugs.values.firstOrNull()
        } ?: return null
        return File(File(basePath, "versions/$slug"), "source")
    }

    fun close() {
        lazyServices.values.forEach { it.close() }
        lazyDbs.values.forEach { it.close() }
    }


    private fun searchCodeTool(): RegisteredTool {
        val tool = Tool(
            name = "search_hytale_code",
            description = "Search the decompiled Hytale server codebase using semantic search. " +
                "Returns a bounded source snippet per match; use get_hytale_node for full content. " +
                "pathPrefix: restrict to file paths/classes containing this substring. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "query" to propString("Natural language description of what you're looking for"),
                "classFilter" to propString("Filter results to a specific class name"),
                "classExact" to propBool("Match classFilter exactly (simple class name or FQCN), not as a substring (default false)"),
                "pathPrefix" to propString("Restrict to results whose file path or class id contains this substring (e.g., server/core/inventory)"),
                "pathExact" to propBool("Match pathPrefix as a complete path component or full filename, not as a substring (default false)"),
                "visibility" to propString("Filter to methods with this visibility: public | private | protected | package. Requires a code reindex to populate facets; rows without facet metadata are kept."),
                "annotation" to propString("Filter to methods carrying this annotation simple name (e.g., Override). Requires a code reindex to populate facets; rows without facet metadata are kept."),
                "limit" to propInt("Number of results to return (default 5, max 20)"),
                "expand" to propBool("Enable graph expansion to find related game data, UI, and docs (default false)"),
                "verbosity" to propString("Output detail per result: ids | compact | full (default full). ids = id,displayName,score,corpus only; compact = all fields except snippet; full = all fields."),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("query"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val query = request.arguments?.getString("query") ?: return@RegisteredTool errorResult("Missing 'query' parameter")
            val classFilter = request.arguments?.getString("classFilter")
            val classExact = request.arguments?.getBool("classExact") ?: false
            val pathPrefix = request.arguments?.getString("pathPrefix")
            val pathExact = request.arguments?.getBool("pathExact") ?: false
            val visibility = request.arguments?.getString("visibility")
            val annotation = request.arguments?.getString("annotation")
            val limit = request.arguments?.getInt("limit") ?: 5
            val expand = request.arguments?.getBool("expand") ?: false
            val verbosity = parseVerbosity(request.arguments?.getString("verbosity"))
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            val searchService = serviceForPatchline(patchline, warning) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {
                val results = if (expand) {
                    searchService.searchWithExpansion(query, Corpus.entries, limit.coerceIn(1, 20))
                } else {
                    searchService.searchCode(query, classFilter, limit.coerceIn(1, 20), pathPrefix = pathPrefix, classExact = classExact, pathExact = pathExact, visibility = visibility, annotation = annotation)
                }
                successResult(encodeSearchResults(query, results, warning.toString(), verbosity))
            } catch (e: Exception) {
                errorResult("Search failed: ${e.message}")
            }
        }
    }

    private fun searchClientTool(): RegisteredTool {
        val tool = Tool(
            name = "search_hytale_client_code",
            description = "Search Hytale client UI files (.xaml, .ui, .json) using semantic search. " +
                "Useful for modifying game UI appearance like inventory layout, hotbar, health bars. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "query" to propString("Natural language description of what UI element you're looking for"),
                "classFilter" to propString("Filter results to a specific category (e.g., DesignSystem, InGame, MainMenu)"),
                "limit" to propInt("Number of results to return (default 5, max 20)"),
                "expand" to propBool("Enable graph expansion to find related game data and code (default false)"),
                "verbosity" to propString("Output detail per result: ids | compact | full (default full). ids = id,displayName,score,corpus only; compact = all fields except snippet; full = all fields."),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("query"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val query = request.arguments?.getString("query") ?: return@RegisteredTool errorResult("Missing 'query' parameter")
            val classFilter = request.arguments?.getString("classFilter")
            val limit = request.arguments?.getInt("limit") ?: 5
            val expand = request.arguments?.getBool("expand") ?: false
            val verbosity = parseVerbosity(request.arguments?.getString("verbosity"))
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            val searchService = serviceForPatchline(patchline, warning) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {
                val results = if (expand) {
                    searchService.searchWithExpansion(query, listOf(Corpus.CLIENT, Corpus.GAMEDATA, Corpus.CODE), limit.coerceIn(1, 20))
                } else {
                    searchService.searchCorpus(query, Corpus.CLIENT, limit.coerceIn(1, 20), classFilter)
                }
                successResult(encodeSearchResults(query, results, warning.toString(), verbosity))
            } catch (e: Exception) {
                errorResult("Search failed: ${e.message}")
            }
        }
    }

    private fun searchGamedataTool(): RegisteredTool {
        val tool = Tool(
            name = "search_hytale_gamedata",
            description = "Search vanilla Hytale game data including items, recipes, NPCs, drops, blocks, and more. " +
                "Use this for modding questions like 'how to craft X', 'what drops Y', 'NPC behavior for Z'. " +
                "tag: restrict to items/nodes whose data contains this substring, e.g. Type:Weapon or Uncommon. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "query" to propString("Natural language question about Hytale game data"),
                "type" to propString("Filter by data type (e.g., item, recipe, npc, block, drop, shop). Comma-separate to match any of several types, e.g. item,recipe."),
                "tag" to propString("Restrict to results whose data contains this substring (e.g., Type:Weapon, Uncommon)"),
                "limit" to propInt("Number of results to return (default 5, max 20)"),
                "expand" to propBool("Enable graph expansion to find related code and UI (default false)"),
                "verbosity" to propString("Output detail per result: ids | compact | full (default full). ids = id,displayName,score,corpus only; compact = all fields except snippet; full = all fields."),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("query"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val query = request.arguments?.getString("query") ?: return@RegisteredTool errorResult("Missing 'query' parameter")
            val typeFilters = request.arguments?.getString("type")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?.takeIf { it.isNotEmpty() }
            val tag = request.arguments?.getString("tag")
            val limit = request.arguments?.getInt("limit") ?: 5
            val expand = request.arguments?.getBool("expand") ?: false
            val verbosity = parseVerbosity(request.arguments?.getString("verbosity"))
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            val searchService = serviceForPatchline(patchline, warning) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {
                val results = if (expand) {
                    searchService.searchWithExpansion(query, listOf(Corpus.GAMEDATA, Corpus.CODE, Corpus.CLIENT), limit.coerceIn(1, 20))
                } else if (typeFilters != null) {
                    searchService.searchCorpus(query, Corpus.GAMEDATA, limit.coerceIn(1, 20), typeFilters, snippetContains = tag)
                } else {
                    searchService.searchCorpus(query, Corpus.GAMEDATA, limit.coerceIn(1, 20), searchService.detectGamedataIntent(query), snippetContains = tag)
                }
                successResult(encodeSearchResults(query, results, warning.toString(), verbosity))
            } catch (e: Exception) {
                errorResult("Search failed: ${e.message}")
            }
        }
    }

    private fun searchDocsTool(): RegisteredTool {
        val tool = Tool(
            name = "search_hytale_docs",
            description = "Search Hytale documentation using semantic search: modding guides, blog posts / patch notes, and support articles. " +
                "Pick the 'source' to match intent: patch notes / changelog / release notes / 'what changed in this update' -> source=\"blog\" (optionally sort=\"recency\" for newest first); " +
                "modding guides / tutorials / API reference (plugin development, ECS, blocks, commands, events) -> source=\"modding\"; " +
                "support / help / troubleshooting articles -> source=\"support\"; reverse-engineering notes -> source=\"re\"; search every source -> source=\"all\" (default). " +
                "Use the 'sort' param to order results: relevance (default) | recency (newest published first). " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "query" to propString("Natural language question about Hytale modding"),
                "source" to propString("Filter by doc source: blog, modding, support, re, or all (default all)"),
                "type" to propString("Filter by documentation type"),
                "limit" to propInt("Number of results to return (default 5, max 20)"),
                "expand" to propBool("Enable graph expansion to find related code and game data (default false)"),
                "sort" to propString("Result ordering: relevance (default) or recency (newest published first)"),
                "verbosity" to propString("Output detail per result: ids | compact | full (default full). ids = id,displayName,score,corpus only; compact = all fields except snippet; full = all fields."),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("query"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val query = request.arguments?.getString("query") ?: return@RegisteredTool errorResult("Missing 'query' parameter")
            val source = request.arguments?.getString("source") ?: "all"
            val type = request.arguments?.getString("type")
            val limit = request.arguments?.getInt("limit") ?: 5
            val expand = request.arguments?.getBool("expand") ?: false
            val sort = request.arguments?.getString("sort")
            val verbosity = parseVerbosity(request.arguments?.getString("verbosity"))
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            val searchService = serviceForPatchline(patchline, warning) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {
                val results = if (expand) {
                    searchService.searchWithExpansion(query, listOf(Corpus.DOCS, Corpus.CODE, Corpus.GAMEDATA), limit.coerceIn(1, 20))
                } else {
                    searchDocsFiltered(searchService, query, source, limit.coerceIn(1, 20), type, sort)
                }
                successResult(encodeSearchResults(query, results, warning.toString(), verbosity))
            } catch (e: Exception) {
                errorResult("Search failed: ${e.message}")
            }
        }
    }

    private fun searchHytaleTool(): RegisteredTool {
        val tool = Tool(
            name = "search_hytale",
            description = "Unified semantic search across all Hytale knowledge corpora: server code, client UI, game data, and docs. " +
                "Returns merged top results ranked by score. Each result includes 'corpus' and 'docSource' (for docs, derived from the node id prefix). " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "query" to propString("Natural language query"),
                "limit" to propInt("Number of results to return (default 20, max 100)"),
                "verbosity" to propString("Output detail per result: ids | compact | full (default full). ids = id,displayName,score,corpus only; compact = all fields except snippet; full = all fields."),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("query"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val query = request.arguments?.getString("query") ?: return@RegisteredTool errorResult("Missing 'query' parameter")
            val limit = request.arguments?.getInt("limit") ?: 20
            val verbosity = parseVerbosity(request.arguments?.getString("verbosity"))
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            val searchService = serviceForPatchline(patchline, warning) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {
                val perCorpus = limit.coerceIn(1, 100)
                val merged = Corpus.entries.flatMap { corpus ->
                    try {
                        searchService.searchCorpus(query, corpus, perCorpus)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }.sortedByDescending { it.score }.take(perCorpus)
                successResult(encodeSearchResults(query, merged, warning.toString(), verbosity))
            } catch (e: Exception) {
                errorResult("Search failed: ${e.message}")
            }
        }
    }

    private fun statsTool(): RegisteredTool {
        val tool = Tool(
            name = "hytale_stats",
            description = "Get index statistics for the Hytale knowledge corpora (node counts etc.). " +
                "Use 'corpus' to pick code|gamedata|docs|client|all (default all), and 'patchline' for release|pre-release|<patchline>@<version>.",
            inputSchema = toolSchema(
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                "corpus" to propString("Corpus: code | gamedata | docs | client | all (default all)"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val searchService = serviceForPatchline(patchline) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            val corpus = request.arguments?.getString("corpus") ?: "all"
            try {
                if (corpus == "all") {
                    val obj = buildJsonObject {
                        put("code", json.encodeToJsonElement(com.hyindex.knowledge.core.search.IndexStats.serializer(), searchService.getCorpusStats(Corpus.CODE)))
                        put("gamedata", json.encodeToJsonElement(com.hyindex.knowledge.core.search.IndexStats.serializer(), searchService.getCorpusStats(Corpus.GAMEDATA)))
                        put("docs", json.encodeToJsonElement(com.hyindex.knowledge.core.search.IndexStats.serializer(), searchService.getCorpusStats(Corpus.DOCS)))
                        put("client", json.encodeToJsonElement(com.hyindex.knowledge.core.search.IndexStats.serializer(), searchService.getCorpusStats(Corpus.CLIENT)))
                    }
                    successResult(json.encodeToString(obj))
                } else {
                    val selected = when (corpus) {
                        "code" -> Corpus.CODE
                        "gamedata" -> Corpus.GAMEDATA
                        "docs" -> Corpus.DOCS
                        "client" -> Corpus.CLIENT
                        else -> return@RegisteredTool errorResult("Unknown corpus: $corpus")
                    }
                    val stats = searchService.getCorpusStats(selected)
                    successResult(json.encodeToString(com.hyindex.knowledge.core.search.IndexStats.serializer(), stats))
                }
            } catch (e: Exception) {
                errorResult("Failed to get stats: ${e.message}")
            }
        }
    }

    private fun diffVersionsTool(): RegisteredTool {
        val tool = Tool(
            name = "diff_hytale_versions",
            description = "Compare two indexed Hytale patchlines (or version snapshots) to find what changed. " +
                "Shows added, removed, and changed nodes across code, game data, and client UI. " +
                "By default compares release (A) vs pre-release (B), i.e. what pre-release adds over release. " +
                "Use 'scope' to narrow the diff to a specific class, method, item, or path fragment " +
                "(e.g., scope=\"InventoryService\", scope=\"iron_sword\", scope=\"com/hypixel/hytale/protocol\"). " +
                "Use 'format' to get raw JSON instead of Markdown. " +
                "To diff against a retained snapshot of an old version that was auto-cleared, pass " +
                "versionA or versionB in the form '<patchline>@<version>' (e.g., 'pre-release@0.6.0-pre.1'). " +
                "Auto-cleared old versions remain diffable this way.",
            inputSchema = toolSchema(
                "versionA" to propString("Patchline or snapshot ref for the old snapshot (default: release). " +
                    "Plain patchline: release, pre-release. " +
                    "Snapshot ref: <patchline>@<version>, e.g. pre-release@0.6.0-pre.1 (uses retained snapshot)."),
                "versionB" to propString("Patchline or snapshot ref for the new snapshot (default: pre-release). " +
                    "Plain patchline: release, pre-release. " +
                    "Snapshot ref: <patchline>@<version>, e.g. pre-release@0.6.0-pre.1 (uses retained snapshot)."),
                "corpus" to propString("Filter to a specific corpus: code, gamedata, client, or all (default: all)"),
                "changeType" to propString("Filter by change type: ADDED, REMOVED, CHANGED, or all (default: all)"),
                "dataType" to propString("Filter by data type (e.g., item, recipe, npc)"),
                "scope" to propString("Restrict diff to nodes whose display_name, id, or file_path contains this substring (e.g., InventoryService, iron_sword, com/hypixel/hytale/protocol)"),
                "format" to propString("Output format: markdown (default) or json"),
                "limit" to propInt("Maximum entries to return (default 50, max 200)"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val versionA = request.arguments?.getString("versionA") ?: "release"
            val versionB = request.arguments?.getString("versionB") ?: "pre-release"
            val corpus = request.arguments?.getString("corpus") ?: "all"
            val changeType = request.arguments?.getString("changeType") ?: "all"
            val dataType = request.arguments?.getString("dataType")
            val scope = request.arguments?.getString("scope")
            val format = request.arguments?.getString("format") ?: "markdown"
            val limit = request.arguments?.getInt("limit") ?: 50
            try {
                val log = StdoutLogProvider
                val config = McpConfig.load()
                val basePath = config.resolvedBasePath()


                data class VersionSource(val file: File, val isSnapshot: Boolean, val label: String)

                fun resolveVersion(ref: String): VersionSource? {
                    return if ('@' in ref) {
                        val (patchline, version) = ref.split('@', limit = 2)
                        val f = File(basePath, "snapshots/$patchline/$version.json")
                        VersionSource(f, isSnapshot = true, label = ref)
                    } else {
                        val slug = VersionResolver.latestSlug(basePath, ref) ?: ref
                        val f = File(basePath, "versions/$slug/knowledge.db")
                        VersionSource(f, isSnapshot = false, label = ref)
                    }
                }

                val srcA = resolveVersion(versionA)
                    ?: return@RegisteredTool errorResult("Could not resolve version ref: $versionA")
                val srcB = resolveVersion(versionB)
                    ?: return@RegisteredTool errorResult("Could not resolve version ref: $versionB")

                if (!srcA.file.exists()) return@RegisteredTool errorResult(
                    "Version A source not found at ${srcA.file.absolutePath}" +
                    if (srcA.isSnapshot) " (snapshot for ${versionA} not yet written — run indexer to generate it)" else ""
                )
                if (!srcB.file.exists()) return@RegisteredTool errorResult(
                    "Version B source not found at ${srcB.file.absolutePath}" +
                    if (srcB.isSnapshot) " (snapshot for ${versionB} not yet written — run indexer to generate it)" else ""
                )

                val keyA = if ('@' in versionA) versionA else (VersionResolver.latestSlug(basePath, versionA) ?: versionA)
                val keyB = if ('@' in versionB) versionB else (VersionResolver.latestSlug(basePath, versionB) ?: versionB)
                val canCache = !srcA.isSnapshot && !srcB.isSnapshot && scope == null
                val cache = DiffCache(basePath, log)
                val cached = if (canCache) cache.get(keyA, keyB) else null
                val diff = if (cached != null) {
                    cached
                } else {
                    val engine = DiffEngine(log)
                    val result = engine.computeDiff(
                        versionA = keyA,
                        versionB = keyB,
                        sourceA = srcA.file,
                        sourceB = srcB.file,
                        isSnapshotA = srcA.isSnapshot,
                        isSnapshotB = srcB.isSnapshot,
                        corpusFilter = if (corpus != "all") corpus else null,
                        changeTypeFilter = if (changeType != "all") changeType else null,
                        dataTypeFilter = dataType,
                        scopeFilter = scope,
                        limit = limit.coerceIn(1, 200),
                    )
                    if (canCache) cache.put(result)
                    result
                }

                successResult(if (format == "json") DiffExporter.toJson(diff) else DiffExporter.toMarkdown(diff))
            } catch (e: Exception) {
                errorResult("Diff computation failed: ${e.message}")
            }
        }
    }

    private fun getFilePathTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_file_path",
            description = "Resolve a class name, method name, or file path fragment to the absolute file path " +
                "of the decompiled Hytale source file on disk. Use this when you already know what you're looking for " +
                "and want to read the full file directly instead of doing a semantic search. " +
                "Returns file paths that you can then read with your standard file tools. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "query" to propString("Class name, method name, or file path fragment to look up (e.g., 'PlayerEntity', 'ItemRegistry', 'combat/Damage')"),
                "limit" to propInt("Maximum results to return (default 10, max 50)"),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("query"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val query = request.arguments?.getString("query") ?: return@RegisteredTool errorResult("Missing 'query' parameter")
            val limit = request.arguments?.getInt("limit") ?: 10
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val searchService = serviceForPatchline(patchline) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {
                val results = searchService.lookupFilePaths(query, limit.coerceIn(1, 50))
                if (results.isEmpty()) {
                    val emptyObj = buildJsonObject {
                        put("query", query)
                        put("resultCount", 0)
                        put("results", buildJsonArray {})
                        put("hint", "No matches found. Try a shorter or different name fragment.")
                    }
                    return@RegisteredTool successResult(ResponseEncoder.encode(emptyObj, json))
                }
                val obj = buildJsonObject {
                    put("query", query)
                    put("resultCount", results.size)
                    put("results", buildJsonArray {
                        for (r in results) {
                            add(buildJsonObject {
                                put("displayName", r.displayName)
                                put("filePath", r.filePath)
                                put("nodeType", r.nodeType)
                            })
                        }
                    })
                }
                successResult(ResponseEncoder.encode(obj, json))
            } catch (e: Exception) {
                errorResult("File lookup failed: ${e.message}")
            }
        }
    }

    private fun getHytaleClassTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_class",
            description = "Get the class map of a decompiled Hytale class you found via search: " +
                "every method's name, line range, signature (the first source line), and declaring type. " +
                "Includes all overloads, plus methods inherited from in-corpus supertypes (each flagged 'inherited' with its 'declaringType'); " +
                "own methods come first ordered by line, then inherited. " +
                "Use this to scan what a class exposes without pulling full method bodies; " +
                "for verbatim full source or method bodies, use get_hytale_source. " +
                "Accepts a simple class name (e.g., InventoryService) or a fully qualified name. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "className" to propString("Simple or fully qualified class name (e.g., InventoryService or com.hypixel.hytale.inventory.InventoryService)"),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("className"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val className = request.arguments?.getString("className") ?: return@RegisteredTool errorResult("Missing 'className' parameter")
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val searchService = serviceForPatchline(patchline) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {
                val methods = searchService.getClassMethods(className)
                val obj = buildJsonObject {
                    put("className", className)
                    put("methodCount", methods.size)
                    put("methods", buildJsonArray {
                        for (m in methods) {
                            add(buildJsonObject {
                                put("name", m.name)
                                put("lineStart", m.lineStart)
                                put("lineEnd", m.lineEnd)
                                put("signature", signatureOf(m.content))
                                put("declaringType", m.declaringType)
                                put("inherited", m.inherited)
                            })
                        }
                    })
                    if (methods.isEmpty()) put("hint", "No class matched '$className'. Try a different name or use search_hytale_code.")
                }
                successResult(ResponseEncoder.encode(obj, json))
            } catch (e: Exception) {
                errorResult("Class inspection failed: ${e.message}")
            }
        }
    }

    private fun signatureOf(content: String): String {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return lines.firstOrNull { line ->
            !line.startsWith("@") &&
                !line.startsWith("//") &&
                !line.startsWith("/*") &&
                !line.startsWith("*")
        } ?: lines.firstOrNull() ?: ""
    }

    private fun searchHytaleTextTool(): RegisteredTool {
        val tool = Tool(
            name = "search_hytale_text",
            description = "Literal substring or regex search over indexed node content across any corpus. " +
                "Complements semantic search with exact-token matching that semantic search can't reach " +
                "(JSON keys, enum constants, bindings in gamedata/docs/client). " +
                "Returns up to 5 matching lines per node, each with ±2 lines of context, plus the node id and corpus. " +
                "Use 'corpus' to pick code|gamedata|docs|client|all (default code). " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "query" to propString("Text or regular expression to search for"),
                "isRegex" to propBool("Treat the query as a regular expression (default false)"),
                "corpus" to propString("Corpus: code | gamedata | docs | client | all (default code)"),
                "limit" to propInt("Max matches to return (default 20, max 100)"),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("query"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val query = request.arguments?.getString("query") ?: return@RegisteredTool errorResult("Missing 'query' parameter")
            val isRegex = request.arguments?.getBool("isRegex") ?: false
            val corpusArg = request.arguments?.getString("corpus") ?: "code"
            val corpus = if (corpusArg == "all") null
                else Corpus.entries.firstOrNull { it.id == corpusArg }
                    ?: return@RegisteredTool errorResult("Unknown corpus: $corpusArg")
            val limit = (request.arguments?.getInt("limit") ?: 20).coerceIn(1, 100)
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            val db = dbForPatchline(patchline, warning) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {
                val regex = if (isRegex) {
                    try { Regex(query) } catch (e: Exception) { return@RegisteredTool errorResult("Invalid regex: ${e.message}") }
                } else null

                val candidateIds = if (regex == null) ftsCandidates(db, query, corpus) else null

                val corpusClause = if (corpus != null) " AND corpus = ?" else ""
                val idClause = if (candidateIds != null && candidateIds.isNotEmpty())
                    " AND id IN (${candidateIds.joinToString(",") { "?" }})" else ""
                val likeClause = if (regex == null && idClause.isEmpty()) " AND content LIKE ?" else ""
                val sql = "SELECT id, corpus, file_path, line_start, display_name, content " +
                    "FROM nodes WHERE content IS NOT NULL$corpusClause$idClause$likeClause"
                val params = buildList<Any?> {
                    if (corpus != null) add(corpus.id)
                    if (idClause.isNotEmpty()) addAll(candidateIds!!)
                    if (likeClause.isNotEmpty()) add("%$query%")
                }.toTypedArray()

                val matched = db.query(sql, *params) { rs ->
                    val content = rs.getString("content") ?: return@query emptyList<JsonObject>()
                    val lines = content.lines()
                    val lineStart = rs.getInt("line_start")
                    val id = rs.getString("id") ?: ""
                    val nodeCorpus = rs.getString("corpus") ?: ""
                    val filePath = rs.getString("file_path") ?: ""
                    val displayName = rs.getString("display_name") ?: ""
                    lines.withIndex().filter { (_, line) ->
                        if (regex != null) regex.containsMatchIn(line) else line.contains(query, ignoreCase = true)
                    }.take(MAX_LINES_PER_NODE).map { (idx, line) ->
                        val from = (idx - CONTEXT_LINES).coerceAtLeast(0)
                        val to = (idx + CONTEXT_LINES).coerceAtMost(lines.size - 1)
                        buildJsonObject {
                            put("id", id)
                            put("corpus", nodeCorpus)
                            put("file_path", filePath)
                            put("line", lineStart + idx)
                            put("display_name", displayName)
                            put("text", line.trim())
                            put("context", lines.subList(from, to + 1).joinToString("\n"))
                        }
                    }
                }.flatten().take(limit)

                val resultObj = buildJsonObject {
                    put("query", query)
                    put("isRegex", isRegex)
                    put("corpus", corpusArg)
                    if (warning.isNotEmpty()) put("note", warning.toString().trim())
                    put("resultCount", matched.size)
                    put("matches", buildJsonArray { matched.forEach { add(it) } })
                }
                successResult(ResponseEncoder.encode(resultObj, json))
            } catch (e: Exception) {
                errorResult("Text search failed: ${e.message}")
            }
        }
    }

    private fun ftsCandidates(db: KnowledgeDatabase, query: String, corpus: Corpus?): List<String> {
        val tokens = Regex("[A-Za-z0-9]+").findAll(query).map { it.value }.toList()
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" ") { "\"$it\"" }
        val corpusClause = if (corpus != null) " AND nodes_fts.corpus = ?" else ""
        val params = buildList<Any?> {
            add(match)
            if (corpus != null) add(corpus.id)
        }.toTypedArray()
        return try {
            db.query(
                "SELECT node_id FROM nodes_fts WHERE nodes_fts MATCH ?$corpusClause", *params,
            ) { rs -> rs.getString("node_id") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fuzzyNodeSuggestions(db: KnowledgeDatabase, id: String): List<JsonObject> {
        val simple = id.substringAfterLast('#').substringAfterLast('.')
        if (simple.isBlank()) return emptyList()
        return db.query(
            """SELECT id, display_name, node_type FROM nodes
               WHERE display_name = ? OR display_name LIKE ? OR id LIKE ?
               LIMIT 10""",
            simple, "%#$simple", "%$simple%",
        ) { rs ->
            buildJsonObject {
                put("id", rs.getString("id"))
                put("display_name", rs.getString("display_name") ?: "")
                put("node_type", rs.getString("node_type") ?: "")
            }
        }
    }

    private fun getHytaleNodeTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_node",
            description = "Fetch the full record for a single knowledge graph node by its id, " +
                "plus all immediate neighbors (outgoing and incoming edges). " +
                "Use after a search to inspect a node and see what it connects to. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "id" to propString("Node id (from any search result)"),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("id"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val id = request.arguments?.getString("id") ?: return@RegisteredTool errorResult("Missing 'id' parameter")
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            val searchService = serviceForPatchline(patchline, warning) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            val db = dbForPatchline(patchline) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {
                val resolution = searchService.resolveNodeId(id)
                if (resolution.id == null && resolution.candidates.isNotEmpty()) {
                    return@RegisteredTool successResult(json.encodeToString(buildJsonObject {
                        put("requestedId", id)
                        put("found", false)
                        put("note", "Ambiguous id '$id'. Candidate nodes:")
                        put("candidates", buildJsonArray { resolution.candidates.forEach { add(it) } })
                    }))
                }
                val resolvedId = resolution.id ?: id
                val nodes = db.query(
                    "SELECT id, node_type, display_name, corpus, data_type, file_path, line_start, line_end, content FROM nodes WHERE id = ? LIMIT 1",
                    resolvedId,
                ) { rs ->
                    buildJsonObject {
                        put("id", rs.getString("id"))
                        put("display_name", rs.getString("display_name"))
                        put("node_type", rs.getString("node_type"))
                        put("corpus", rs.getString("corpus") ?: "")
                        put("data_type", rs.getString("data_type") ?: "")
                        put("file_path", rs.getString("file_path") ?: "")
                        val lineStart = rs.getInt("line_start")
                        if (!rs.wasNull()) put("line_start", lineStart)
                        val lineEnd = rs.getInt("line_end")
                        if (!rs.wasNull()) put("line_end", lineEnd)
                        val content = rs.getString("content") ?: ""
                        put("content", Snippets.truncate(content, config.nodeContentMaxLength))
                        put("truncated", content.length > config.nodeContentMaxLength)
                    }
                }
                if (nodes.isEmpty()) {
                    val suggestions = fuzzyNodeSuggestions(db, id)
                    return@RegisteredTool if (suggestions.isEmpty()) {
                        errorResult("Node not found: $id")
                    } else {
                        successResult(json.encodeToString(buildJsonObject {
                            put("requestedId", id)
                            put("found", false)
                            put("note", "No exact match for '$id'. Closest nodes by name:")
                            put("suggestions", buildJsonArray { suggestions.forEach { add(it) } })
                        }))
                    }
                }
                val node = nodes.first()

                val outgoing = db.query(
                    """SELECT e.edge_type, e.target_id, n.display_name, n.corpus
                       FROM edges e LEFT JOIN nodes n ON n.id = e.target_id
                       WHERE e.source_id = ?""",
                    resolvedId,
                ) { rs ->
                    buildJsonObject {
                        put("direction", "outgoing")
                        put("edge_type", rs.getString("edge_type"))
                        put("id", rs.getString("target_id"))
                        put("display_name", rs.getString("display_name") ?: "")
                        put("corpus", rs.getString("corpus") ?: "")
                    }
                }

                val incoming = db.query(
                    """SELECT e.edge_type, e.source_id, n.display_name, n.corpus
                       FROM edges e LEFT JOIN nodes n ON n.id = e.source_id
                       WHERE e.target_id = ?""",
                    resolvedId,
                ) { rs ->
                    buildJsonObject {
                        put("direction", "incoming")
                        put("edge_type", rs.getString("edge_type"))
                        put("id", rs.getString("source_id"))
                        put("display_name", rs.getString("display_name") ?: "")
                        put("corpus", rs.getString("corpus") ?: "")
                    }
                }

                val result = buildJsonObject {
                    put("node", node)
                    if (warning.isNotEmpty()) put("note", warning.toString().trim())
                    put("neighbors", buildJsonArray {
                        outgoing.forEach { add(it) }
                        incoming.forEach { add(it) }
                    })
                }
                successResult(json.encodeToString(result))
            } catch (e: Exception) {
                errorResult("Node lookup failed: ${e.message}")
            }
        }
    }

    private fun getHytaleRelatedTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_related",
            description = "Walk the knowledge graph from a node up to 'depth' hops and return related nodes " +
                "across all corpora (code, gamedata, client, docs). Each result keeps its 'direction' (outgoing/incoming) and 'edge_type'. " +
                "Filter with 'direction' (up = supertypes/parents, down = subtypes/children/callers) and 'edgeTypes' " +
                "(e.g., [EXTENDS, IMPLEMENTS, PERMITS] for the class hierarchy, [INSTANCEOF] for type checks). " +
                "Use after a search to explore the graph around a result. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "id" to propString("Starting node id (from any search result)"),
                "direction" to propEnum(
                    "Which way to walk: up = outgoing edges (a class's supertypes via EXTENDS/IMPLEMENTS), " +
                        "down = incoming edges (its subtypes/implementers/callers), both (default).",
                    listOf("up", "down", "both"),
                ),
                "edgeTypes" to buildJsonObject {
                    put("type", "array")
                    put("description", "Keep only these edge types (e.g., EXTENDS, IMPLEMENTS, PERMITS, INSTANCEOF, CALLS, DECLARED_IN). Omit for all.")
                    put("items", buildJsonObject { put("type", "string") })
                },
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                "depth" to propInt("Number of hops to traverse (default 1, max 3)"),
                "limit" to propInt("Max nodes to return (default 25)"),
                required = listOf("id"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val id = request.arguments?.getString("id") ?: return@RegisteredTool errorResult("Missing 'id' parameter")
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val depth = (request.arguments?.getInt("depth") ?: 1).coerceIn(1, 3)
            val limit = (request.arguments?.getInt("limit") ?: 25).coerceIn(1, 200)
            val direction = (request.arguments?.getString("direction") ?: "both").let { if (it in setOf("up", "down", "both")) it else "both" }
            val edgeTypeFilter = (request.arguments?.get("edgeTypes") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                ?.toSet()?.takeIf { it.isNotEmpty() }
            val warning = StringBuilder()
            val searchService = serviceForPatchline(patchline, warning) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            val db = dbForPatchline(patchline) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {

                val resolution = searchService.resolveNodeId(id)
                if (resolution.id == null && resolution.candidates.isNotEmpty()) {
                    return@RegisteredTool successResult(json.encodeToString(buildJsonObject {
                        put("requestedId", id)
                        put("found", false)
                        put("note", "Ambiguous id '$id'. Candidate nodes:")
                        put("candidates", buildJsonArray { resolution.candidates.forEach { add(it) } })
                    }))
                }
                if (resolution.id == null) return@RegisteredTool errorResult("Node not found: $id")
                val startId = resolution.id


                data class QueueEntry(val nodeId: String, val edgeType: String, val direction: String, val hopDepth: Int)
                data class RelatedNode(
                    val id: String, val displayName: String, val corpus: String,
                    val dataType: String, val edgeType: String, val direction: String, val depth: Int,
                )

                val visited = mutableSetOf(startId)
                val queue = ArrayDeque<QueueEntry>()
                val collected = mutableListOf<RelatedNode>()

                fun neighborsOf(fromId: String): List<Triple<String, String, String>> {
                    val out = mutableListOf<Triple<String, String, String>>()
                    if (direction != "down") {
                        db.query("SELECT target_id, edge_type FROM edges WHERE source_id = ?", fromId) { rs ->
                            Triple(rs.getString("target_id"), rs.getString("edge_type"), "outgoing")
                        }.forEach { out.add(it) }
                    }
                    if (direction != "up") {
                        db.query("SELECT source_id, edge_type FROM edges WHERE target_id = ?", fromId) { rs ->
                            Triple(rs.getString("source_id"), rs.getString("edge_type"), "incoming")
                        }.forEach { out.add(it) }
                    }
                    return out.filter { edgeTypeFilter == null || it.second in edgeTypeFilter }
                }


                fun enqueueNeighbors(fromId: String, currentDepth: Int) {
                    if (currentDepth >= depth) return
                    for ((nid, et, dir) in neighborsOf(fromId)) {
                        if (nid !in visited) queue.addLast(QueueEntry(nid, et, dir, currentDepth + 1))
                    }
                }

                for ((nid, et, dir) in neighborsOf(startId)) {
                    if (nid !in visited) queue.addLast(QueueEntry(nid, et, dir, 1))
                }

                while (queue.isNotEmpty() && collected.size < limit) {
                    val entry = queue.removeFirst()
                    if (entry.nodeId in visited) continue
                    visited.add(entry.nodeId)

                    val nodeRows = db.query(
                        "SELECT id, display_name, corpus, data_type FROM nodes WHERE id = ? LIMIT 1",
                        entry.nodeId,
                    ) { rs ->
                        RelatedNode(
                            rs.getString("id"), rs.getString("display_name") ?: "", rs.getString("corpus") ?: "",
                            rs.getString("data_type") ?: "", entry.edgeType, entry.direction, entry.hopDepth,
                        )
                    }
                    if (nodeRows.isNotEmpty()) {
                        collected.add(nodeRows.first())
                        enqueueNeighbors(entry.nodeId, entry.hopDepth)
                    }
                }

                val ordered = collected.sortedWith(compareBy({ it.edgeType }, { it.displayName }))
                val result = buildJsonObject {
                    put("startId", startId)
                    put("depth", depth)
                    put("direction", direction)
                    put("resultCount", ordered.size)
                    if (warning.isNotEmpty()) put("note", warning.toString().trim())
                    put("related", buildJsonArray {
                        ordered.forEach { rn ->
                            add(buildJsonObject {
                                put("id", rn.id)
                                put("display_name", rn.displayName)
                                put("corpus", rn.corpus)
                                put("data_type", rn.dataType)
                                put("edge_type", rn.edgeType)
                                put("direction", rn.direction)
                                put("depth", rn.depth)
                            })
                        }
                    })
                }
                successResult(json.encodeToString(result))
            } catch (e: Exception) {
                errorResult("Graph traversal failed: ${e.message}")
            }
        }
    }


    private fun traversalResult(
        patchline: String,
        warning: StringBuilder,
        limit: Int,
        verbosity: Verbosity = Verbosity.FULL,
        block: (GraphTraversal) -> List<SearchResult>,
    ): CallToolResult {
        val db = dbForPatchline(patchline, warning) ?: return errorResult("No knowledge index loaded.")
        return try {
            val results = block(GraphTraversal(db))
            successResult(encodeSearchResults("", results.take(limit), warning.toString(), verbosity))
        } catch (e: Exception) {
            errorResult("Graph traversal failed: ${e.message}")
        }
    }

    private fun getHytaleRecipeTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_recipe",
            description = "Walk recipe edges for a given item or recipe node. " +
                "Pass a node id from a search result (e.g., from search_hytale_gamedata) and a 'direction': " +
                "'requires' = REQUIRES_ITEM edges (a recipe's ingredients, or recipes/items that consume an item); " +
                "'produces' = PRODUCES_ITEM edges (a recipe's output, or recipes that produce an item); " +
                "'both' (default) = the union of requires and produces. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "nodeId" to propString("Node id of the item or recipe (from a search result)"),
                "direction" to propEnum(
                    "Which recipe edges to walk: requires | produces | both (default both)",
                    listOf("requires", "produces", "both"),
                ),
                "limit" to propInt("Max results to return (default 10, max 50)"),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("nodeId"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val nodeId = request.arguments?.getString("nodeId") ?: return@RegisteredTool errorResult("Missing 'nodeId' parameter")
            val limit = (request.arguments?.getInt("limit") ?: 10).coerceIn(1, 50)
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val direction = request.arguments?.getString("direction") ?: "both"
            val warning = StringBuilder()
            traversalResult(patchline, warning, limit) { gt ->
                when (direction) {
                    "requires" -> gt.findRecipeInputs(nodeId, limit)
                    "produces" -> gt.findRecipeOutputs(nodeId, limit)
                    else -> (gt.findRecipeInputs(nodeId, limit) + gt.findRecipeOutputs(nodeId, limit)).distinctBy { it.nodeId }
                }
            }
        }
    }

    private fun getHytaleDropsTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_drops",
            description = "Find items dropped by a given entity (mob, boss, block) on death. " +
                "Pass the entity's node id from a search result. " +
                "Walks DROPS_ON_DEATH → DROPS_ITEM edges. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "nodeId" to propString("Node id of the entity or block (from a search result)"),
                "limit" to propInt("Max results to return (default 10, max 50)"),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("nodeId"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val nodeId = request.arguments?.getString("nodeId") ?: return@RegisteredTool errorResult("Missing 'nodeId' parameter")
            val limit = (request.arguments?.getInt("limit") ?: 10).coerceIn(1, 50)
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            traversalResult(patchline, warning, limit) { gt -> gt.findDropsFrom(nodeId, limit) }
        }
    }

    private fun getHytaleImplementingCodeTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_implementing_code",
            description = "Find server-code classes (JavaClass nodes) that implement a given gamedata type or interface. " +
                "Pass a gamedata node id from a search result. " +
                "Walks IMPLEMENTED_BY edges from the gamedata node to code nodes. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "nodeId" to propString("Node id of the gamedata type (from a search result)"),
                "limit" to propInt("Max results to return (default 10, max 50)"),
                "verbosity" to propString("Output detail per result: ids | compact | full (default full). ids = id,displayName,score,corpus only; compact = all fields except snippet; full = all fields."),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("nodeId"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val nodeId = request.arguments?.getString("nodeId") ?: return@RegisteredTool errorResult("Missing 'nodeId' parameter")
            val limit = (request.arguments?.getInt("limit") ?: 10).coerceIn(1, 50)
            val verbosity = parseVerbosity(request.arguments?.getString("verbosity"))
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            traversalResult(patchline, warning, limit, verbosity) { gt -> gt.findImplementingCode(nodeId, limit) }
        }
    }

    private fun getHytaleGamedataForCodeTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_gamedata_for_code",
            description = "Find gamedata nodes (items, entities, etc.) that a given code class implements. " +
                "Pass a code node id (JavaClass) from a search result. " +
                "Reverse of get_hytale_implementing_code — walks IMPLEMENTED_BY edges from gamedata to the given code node. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "nodeId" to propString("Node id of the code class (from a search result)"),
                "limit" to propInt("Max results to return (default 5, max 50)"),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("nodeId"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val nodeId = request.arguments?.getString("nodeId") ?: return@RegisteredTool errorResult("Missing 'nodeId' parameter")
            val limit = (request.arguments?.getInt("limit") ?: 5).coerceIn(1, 50)
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            traversalResult(patchline, warning, limit) { gt -> gt.findGamedataForCode(nodeId, limit) }
        }
    }

    private fun getHytaleUsagesTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_usages",
            description = "Find call relationships for a code method or class. " +
                "Pass a node id from a search result (also accepts a Class#method display name). " +
                "'direction': 'callers' = methods that call the given node (incoming CALLS edges); " +
                "'callees' = methods/classes the given node calls (outgoing CALLS edges). " +
                "Heuristic: edges are derived from the AST without a symbol solver, so resolution is class-granular " +
                "and may over- or under-link. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "id" to propString("Node id of a code method or class (accepts Class#method)"),
                "direction" to propEnum(
                    "Which call edges to walk: callers | callees (default callers)",
                    listOf("callers", "callees"),
                ),
                "limit" to propInt("Max results to return (default 10, max 50)"),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("id"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val id = request.arguments?.getString("id") ?: return@RegisteredTool errorResult("Missing 'id' parameter")
            val limit = (request.arguments?.getInt("limit") ?: 10).coerceIn(1, 50)
            val direction = request.arguments?.getString("direction") ?: "callers"
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            val searchService = serviceForPatchline(patchline, warning) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            val resolution = searchService.resolveNodeId(id)
            if (resolution.id == null && resolution.candidates.isNotEmpty()) {
                return@RegisteredTool successResult(json.encodeToString(buildJsonObject {
                    put("requestedId", id)
                    put("found", false)
                    put("note", "Ambiguous id '$id'. Candidate nodes:")
                    put("candidates", buildJsonArray { resolution.candidates.forEach { add(it) } })
                }))
            }
            val resolvedId = resolution.id ?: id
            traversalResult(patchline, warning, limit) { gt ->
                if (direction == "callees") gt.findCallees(resolvedId, limit)
                else gt.findCallers(resolvedId, limit)
            }
        }
    }

    private fun getHytaleShopsTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_shops",
            description = "Find shops that sell a given item. " +
                "Pass the item's node id from a search result. " +
                "Walks OFFERED_IN_SHOP edges from the item to shop nodes. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "nodeId" to propString("Node id of the item (from a search result)"),
                "limit" to propInt("Max results to return (default 10, max 50)"),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("nodeId"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val nodeId = request.arguments?.getString("nodeId") ?: return@RegisteredTool errorResult("Missing 'nodeId' parameter")
            val limit = (request.arguments?.getInt("limit") ?: 10).coerceIn(1, 50)
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            traversalResult(patchline, warning, limit) { gt -> gt.findShopsSellingItem(nodeId, limit) }
        }
    }

    private fun getHytaleGroupMembersTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_group_members",
            description = "Find the members of a gamedata group node (e.g., an item group, entity group, or loot table group). " +
                "Pass the group's node id from a search result. " +
                "Walks HAS_MEMBER edges from the group to its member nodes. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "nodeId" to propString("Node id of the group (from a search result)"),
                "limit" to propInt("Max results to return (default 10, max 50)"),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("nodeId"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val nodeId = request.arguments?.getString("nodeId") ?: return@RegisteredTool errorResult("Missing 'nodeId' parameter")
            val limit = (request.arguments?.getInt("limit") ?: 10).coerceIn(1, 50)
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            traversalResult(patchline, warning, limit) { gt -> gt.findGroupMembers(nodeId, limit) }
        }
    }

    private fun listVersionsTool(): RegisteredTool {
        val tool = Tool(
            name = "list_hytale_versions",
            description = "List the retained indexed Hytale versions per patchline. " +
                "Use a returned slug or version with the '<patchline>@<version>' ref in any search/query tool to pin to it. " +
                "Each entry has 'branch' (source branch) and 'latest' (the newest commit / branch HEAD, which may have a lower buildNumber than an older build).",
            inputSchema = toolSchema(),
        )
        return RegisteredTool(tool) { _ ->
            try {
                val patchlines = (latestSlugs.keys + listOf("release", "pre-release")).distinct()
                val obj = buildJsonObject {
                    for (patchline in patchlines) {
                        put(patchline, buildJsonArray {
                            for (slug in VersionResolver.listSlugs(basePath, patchline)) {
                                val meta = VersionResolver.readMeta(File(basePath, "versions/$slug/version_meta.json"))
                                add(buildJsonObject {
                                    put("slug", slug)
                                    put("branch", meta?.branch)
                                    put("buildNumber", meta?.buildNumber)
                                    put("date", meta?.date)
                                    put("indexedAt", meta?.indexedAt)
                                    put("latest", slug == latestSlugs[patchline])
                                })
                            }
                        })
                    }
                }
                successResult(json.encodeToString(obj))
            } catch (e: Exception) {
                errorResult("Failed to list versions: ${e.message}")
            }
        }
    }


    private fun getModdingContextTool(): RegisteredTool {
        val tool = Tool(
            name = "get_modding_context",
            description = "one-call modding context bundle — guides + implementing code + gamedata for a topic. " +
                "Returns three guaranteed sections (docs, code, gamedata), each present even when empty. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "topic" to propString("Modding topic to gather context for (e.g., inventory, crafting, commands)"),
                "verbosity" to propString("Output detail per result: ids | compact | full (default full). ids = id,displayName,score,corpus only; compact = all fields except snippet; full = all fields."),
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
                required = listOf("topic"),
            ),
        )
        return RegisteredTool(tool) { request ->
            val topic = request.arguments?.getString("topic") ?: return@RegisteredTool errorResult("Missing 'topic' parameter")
            val verbosity = parseVerbosity(request.arguments?.getString("verbosity"))
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val warning = StringBuilder()
            val searchService = serviceForPatchline(patchline, warning) ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            try {
                val limit = 4
                val docs = searchDocsFiltered(searchService, topic, "modding", limit)
                val code = searchService.searchCorpus(topic, Corpus.CODE, limit, null as Set<String>?)
                val gamedata = searchService.searchCorpus(topic, Corpus.GAMEDATA, limit, searchService.detectGamedataIntent(topic))
                val obj = buildJsonObject {
                    put("topic", topic)
                    if (warning.isNotEmpty()) put("note", warning.toString().trim())
                    put("docs", buildJsonArray { docs.forEach { add(searchResultRow(topic, it, verbosity)) } })
                    put("code", buildJsonArray { code.forEach { add(searchResultRow(topic, it, verbosity)) } })
                    put("gamedata", buildJsonArray { gamedata.forEach { add(searchResultRow(topic, it, verbosity)) } })
                }
                successResult(json.encodeToString(obj))
            } catch (e: Exception) {
                errorResult("Search failed: ${e.message}")
            }
        }
    }

    private fun normalizeSourcePath(raw: String): String {
        val marker = "cache/staged/"
        val at = raw.indexOf(marker)
        val tail = if (at >= 0) {
            val afterMarker = raw.substring(at + marker.length)
            afterMarker.substringAfter('/', missingDelimiterValue = afterMarker)
        } else {
            raw
        }
        return tail.trimStart('/')
    }

    private fun resolveSourceFile(sourceRoot: File, raw: String): Result<File> {
        val canonicalRoot = sourceRoot.canonicalFile
        val relative = normalizeSourcePath(raw)
        val resolved = File(sourceRoot, relative).canonicalFile
        if (resolved != canonicalRoot && !resolved.path.startsWith(canonicalRoot.path + File.separator)) {
            return Result.failure(IllegalArgumentException("Path escapes the source tree: $raw"))
        }
        if (!resolved.isFile) {
            return Result.failure(IllegalArgumentException("Source file not found: $relative"))
        }
        return Result.success(resolved)
    }

    private fun getHytaleSourceTool(): RegisteredTool {
        val tool = Tool(
            name = "get_hytale_source",
            description = "Read verbatim source by className+methodName (a specific method, no line numbers needed), " +
                "by className (whole file), or by path/file_path. " +
                "methodName alone works if unambiguous, else returns candidates. " +
                "Also supports a specific line, a line range, or several files. " +
                "Use the 'patchline' param to target a specific patchline (release|pre-release, default release).",
            inputSchema = toolSchema(
                "className" to propString("Simple or fully qualified class name to resolve to its source file (e.g., ItemContainer or com.hypixel.hytale.inventory.ItemContainer)"),
                "methodName" to propString("Method name to resolve to its exact source range. Pair with className to disambiguate; alone works only if unique."),
                "path" to propString("A single file's relative path or a recorded file_path (from a search/get_hytale_node result)"),
                "startLine" to propInt("1-indexed inclusive start line. Omit with endLine to read the whole file."),
                "endLine" to propInt("1-indexed inclusive end line. Set equal to startLine to read one line."),
                "paths" to buildJsonObject {
                    put("type", "array")
                    put("description", "Batch: read each whole file. When given, startLine/endLine are ignored.")
                    put("items", buildJsonObject { put("type", "string") })
                },
                "patchline" to propString("Patchline or pinned version: release | pre-release | <patchline>@<version> (default release). Call list_hytale_versions to discover versions."),
            ),
        )
        return RegisteredTool(tool) { request ->
            val patchline = request.arguments?.getString("patchline") ?: "release"
            val sourceRoot = sourceRootForPatchline(patchline)
                ?: return@RegisteredTool errorResult("No knowledge index loaded.")
            if (!sourceRoot.isDirectory) {
                return@RegisteredTool errorResult("Source tree not staged for '$patchline' at ${sourceRoot.absolutePath}")
            }
            val batch = request.arguments?.get("paths") as? JsonArray
            try {
                val methodName = request.arguments?.getString("methodName")
                if (methodName != null) {
                    val className = request.arguments?.getString("className")
                    val resolution = serviceForPatchline(patchline)?.resolveMethodSource(className, methodName)
                        ?: return@RegisteredTool errorResult("No knowledge index loaded.")
                    val matches = resolution.matches
                    when {
                        matches.isEmpty() -> return@RegisteredTool errorResult(
                            "Could not resolve method '${className?.let { "$it#" } ?: ""}$methodName' to a source range."
                        )
                        matches.size > 1 -> {
                            val candidatesObj = buildJsonObject {
                                put("methodName", methodName)
                                put("found", false)
                                put("note", "Ambiguous method '$methodName'. Retry with a className. Candidates:")
                                put("candidates", buildJsonArray { matches.forEach { add(it.id) } })
                            }
                            return@RegisteredTool successResult(json.encodeToString(candidatesObj))
                        }
                    }
                    val match = matches.first()
                    val file = resolveSourceFile(sourceRoot, match.owningFile).getOrElse {
                        return@RegisteredTool errorResult(it.message ?: "Invalid path: ${match.owningFile}")
                    }
                    val allLines = file.readLines()
                    val totalLines = allLines.size
                    val from = match.lineStart.coerceIn(1, totalLines.coerceAtLeast(1))
                    val to = match.lineEnd.coerceIn(from, totalLines.coerceAtLeast(1))
                    val body = if (totalLines == 0) "" else allLines.subList(from - 1, to).joinToString("\n")
                    val truncated = body.length > config.sourceMaxChars
                    val obj = buildJsonObject {
                        put("path", normalizeSourcePath(match.owningFile))
                        put("className", match.id.substringBeforeLast('#'))
                        put("methodName", methodName)
                        put("startLine", from)
                        put("endLine", to)
                        put("totalLines", totalLines)
                        put("truncated", truncated)
                        put("content", Snippets.truncate(body, config.sourceMaxChars))
                    }
                    return@RegisteredTool successResult(ResponseEncoder.encode(obj, json))
                }

                if (batch != null) {
                    val files = buildJsonArray {
                        for (element in batch) {
                            val raw = (element as? JsonPrimitive)?.contentOrNull ?: continue
                            val file = resolveSourceFile(sourceRoot, raw).getOrElse {
                                return@RegisteredTool errorResult(it.message ?: "Invalid path: $raw")
                            }
                            val content = file.readText()
                            val totalLines = content.lineSequence().count()
                            val truncated = content.length > config.sourceMaxChars
                            add(buildJsonObject {
                                put("path", normalizeSourcePath(raw))
                                put("totalLines", totalLines)
                                put("truncated", truncated)
                                put("content", Snippets.truncate(content, config.sourceMaxChars))
                            })
                        }
                    }
                    return@RegisteredTool successResult(ResponseEncoder.encode(buildJsonObject { put("files", files) }, json))
                }

                val className = request.arguments?.getString("className")
                val raw = request.arguments?.getString("path")
                    ?: className?.let { name ->
                        serviceForPatchline(patchline)?.resolveClassSourcePath(name)
                            ?: return@RegisteredTool errorResult("Could not resolve className '$name' to a source file.")
                    }
                    ?: return@RegisteredTool errorResult("Provide one of 'className', 'path', or 'paths'.")
                val file = resolveSourceFile(sourceRoot, raw).getOrElse {
                    return@RegisteredTool errorResult(it.message ?: "Invalid path: $raw")
                }
                val allLines = file.readLines()
                val totalLines = allLines.size
                val startLine = request.arguments?.getInt("startLine")
                val endLine = request.arguments?.getInt("endLine")
                val sliced = if (startLine == null && endLine == null) {
                    allLines
                } else {
                    val from = (startLine ?: 1).coerceIn(1, totalLines.coerceAtLeast(1))
                    val to = (endLine ?: totalLines).coerceIn(from, totalLines.coerceAtLeast(1))
                    if (totalLines == 0) emptyList() else allLines.subList(from - 1, to)
                }
                val body = sliced.joinToString("\n")
                val truncated = body.length > config.sourceMaxChars
                val obj = buildJsonObject {
                    put("path", normalizeSourcePath(raw))
                    put("startLine", startLine ?: 1)
                    put("endLine", endLine ?: totalLines)
                    put("totalLines", totalLines)
                    put("truncated", truncated)
                    put("content", Snippets.truncate(body, config.sourceMaxChars))
                }
                successResult(ResponseEncoder.encode(obj, json))
            } catch (e: Exception) {
                errorResult("Source read failed: ${e.message}")
            }
        }
    }

    private fun searchDocsFiltered(
        service: KnowledgeSearchService,
        query: String,
        source: String,
        limit: Int,
        dataTypeFilter: String? = null,
        sort: String? = null,
    ): List<SearchResult> {
        if (source == "all") {
            return service.searchCorpus(query, Corpus.DOCS, limit, dataTypeFilter, sort = sort)
        }
        val candidateLimit = (limit * 5).coerceAtMost(200)
        val prefix = "$source:"
        return service.searchCorpus(query, Corpus.DOCS, candidateLimit, dataTypeFilter, sort = sort)
            .filter { it.nodeId.startsWith(prefix) }
            .take(limit)
    }

    private enum class Verbosity { IDS, COMPACT, FULL }

    private fun parseVerbosity(raw: String?): Verbosity = when (raw?.lowercase()) {
        "ids" -> Verbosity.IDS
        "compact" -> Verbosity.COMPACT
        else -> Verbosity.FULL
    }

    private fun searchResultRow(query: String, r: SearchResult, verbosity: Verbosity): JsonObject = buildJsonObject {
        put("id", r.nodeId)
        put("displayName", r.displayName)
        put("score", r.score)
        put("corpus", r.corpus)
        if (verbosity == Verbosity.IDS) return@buildJsonObject
        if (verbosity == Verbosity.FULL) put("snippet", Snippets.window(r.snippet, query, snippetMaxLength))
        put("filePath", r.filePath)
        put("lineStart", r.lineStart)
        put("relevanceScore", r.relevanceScore?.toString() ?: "")
        put("source", r.source.name)
        val docSource = if (r.corpus == "docs") r.nodeId.substringBefore(":", missingDelimiterValue = "") else ""
        put("docSource", docSource)
        put("dataType", r.dataType ?: "")
        put("bridgedFrom", r.bridgedFrom ?: "")
        put("bridgeEdgeType", r.bridgeEdgeType ?: "")
        put("connectedNodeIds", r.connectedNodeIds.joinToString(","))
    }

    private fun encodeSearchResults(query: String, results: List<SearchResult>, note: String = "", verbosity: Verbosity = Verbosity.FULL): String {
        val obj = buildJsonObject {
            put("query", query)
            put("resultCount", results.size)
            if (note.isNotEmpty()) put("note", note.trim())
            put("results", buildJsonArray {
                for (r in results) {
                    add(searchResultRow(query, r, verbosity))
                }
            })
        }
        return ResponseEncoder.encode(obj, json)
    }

    private fun successResult(jsonText: String): CallToolResult {
        return CallToolResult(content = listOf(TextContent(text = jsonText)))
    }

    private fun errorResult(message: String): CallToolResult {
        return CallToolResult(content = listOf(TextContent(text = message)), isError = true)
    }

    private fun toolSchema(
        vararg properties: Pair<String, JsonObject>,
        required: List<String> = emptyList(),
    ): ToolSchema {
        val props = buildJsonObject {
            for ((name, schema) in properties) {
                put(name, schema)
            }
        }
        return ToolSchema(
            properties = props,
            required = required,
        )
    }

    private fun propString(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun propInt(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun propEnum(description: String, values: List<String>): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray { values.forEach { add(it) } })
    }

    private fun propBool(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun Map<String, JsonElement>.getString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun Map<String, JsonElement>.getInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

    private fun Map<String, JsonElement>.getBool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        const val MAX_LINES_PER_NODE = 5
        const val CONTEXT_LINES = 2
    }
}
