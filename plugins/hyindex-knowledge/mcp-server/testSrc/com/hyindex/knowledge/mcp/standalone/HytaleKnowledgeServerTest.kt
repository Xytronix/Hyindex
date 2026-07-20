// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.mcp.standalone

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.CorpusIndexManager
import com.hyindex.knowledge.core.search.KnowledgeSearchService
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class HytaleKnowledgeServerTest {

    private lateinit var server: HytaleKnowledgeServer
    private lateinit var db: KnowledgeDatabase
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        val tempFile = Files.createTempFile("mcp_server_test_", ".db").toFile()
        tempFile.deleteOnExit()
        db = KnowledgeDatabase.forFile(tempFile)
        val config = KnowledgeConfig()
        val indexManager = CorpusIndexManager(config)
        val searchService = KnowledgeSearchService(db, indexManager)
        server = HytaleKnowledgeServer(mapOf("release" to searchService))
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }


    @Test
    fun `createServer registers all tools`() {
        val mcpServer = server.createServer()
        assertEquals(22, mcpServer.tools.size)
    }

    @Test
    fun `get_hytale_usages returns the calling method for direction callers`() = runBlocking {
        val tempFile = Files.createTempFile("usages_callers_", ".db").toFile()
        tempFile.deleteOnExit()
        val udb = KnowledgeDatabase.forFile(tempFile)
        udb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, corpus, content) VALUES (?, 'JavaMethod', ?, 'src/Caller.java', 'code', ?)",
            "com.hytale.Caller#run", "Caller#run", "void run(){ new Callee().ping(); }",
        )
        udb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, corpus, content) VALUES (?, 'JavaMethod', ?, 'src/Callee.java', 'code', ?)",
            "com.hytale.Callee#ping", "Callee#ping", "void ping(){}",
        )
        udb.execute(
            "INSERT INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'CALLS')",
            "com.hytale.Caller#run", "com.hytale.Callee#ping",
        )
        val svc = KnowledgeSearchService(udb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to udb))
        val tool = srv.createServer().tools["get_hytale_usages"]!!

        val result = tool.handler(callToolRequest("get_hytale_usages", buildJsonObject {
            put("id", "com.hytale.Callee#ping")
            put("direction", "callers")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("com.hytale.Caller#run"), "expected the caller in the output; got: $text")
        udb.close()
    }

    @Test
    fun `createServer does not register what_crafts_hytale_item`() {
        val mcpServer = server.createServer()
        assertFalse("what_crafts_hytale_item" in mcpServer.tools.keys)
    }

    @Test
    fun `get_hytale_recipe exposes a direction parameter`() {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["get_hytale_recipe"]!!.tool
        assertNotNull(tool.inputSchema.properties?.get("direction"))
    }

    @Test
    fun `createServer registers get_modding_context`() {
        val mcpServer = server.createServer()
        assertTrue("get_modding_context" in mcpServer.tools.keys)
    }

    @Test
    fun `get_modding_context returns all three sections`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["get_modding_context"]!!
        val result = tool.handler(callToolRequest("get_modding_context", buildJsonObject {
            put("topic", "inventory")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val parsed = json.parseToJsonElement(text).jsonObject
        assertAll(
            { assertNotNull(parsed["docs"]) },
            { assertNotNull(parsed["code"]) },
            { assertNotNull(parsed["gamedata"]) },
        )
    }

    @Test
    fun `get_modding_context keeps all three sections present on empty db`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["get_modding_context"]!!
        val result = tool.handler(callToolRequest("get_modding_context", buildJsonObject {
            put("topic", "inventory")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val parsed = json.parseToJsonElement(text).jsonObject
        assertAll(
            { assertNotNull(parsed["docs"]) },
            { assertNotNull(parsed["code"]) },
            { assertNotNull(parsed["gamedata"]) },
        )
    }

    @Test
    fun `createServer does not register the removed docs tools`() {
        val mcpServer = server.createServer()
        val names = mcpServer.tools.keys
        assertAll(
            { assertFalse("search_hytale_modding" in names) },
            { assertFalse("search_hytale_patchnotes" in names) },
        )
    }

    @Test
    fun `search_hytale_docs source blog with recency sort returns patchnotes shape on empty db`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_docs"]!!
        val result = tool.handler(callToolRequest("search_hytale_docs", buildJsonObject {
            put("query", "what changed in the latest update")
            put("source", "blog")
            put("sort", "recency")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("resultCount: 0"), "blog+recency should return the empty patchnotes shape; got: $text")
    }

    @Test
    fun `search_hytale_docs source modding returns modding shape on empty db`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_docs"]!!
        val result = tool.handler(callToolRequest("search_hytale_docs", buildJsonObject {
            put("query", "how to register a command")
            put("source", "modding")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("resultCount: 0"), "modding source should return the empty modding shape; got: $text")
    }

    @Test
    fun `createServer registers all expected tool names`() {
        val mcpServer = server.createServer()
        val names = mcpServer.tools.keys
        assertAll(
            { assertTrue("search_hytale_code" in names) },
            { assertTrue("search_hytale_client_code" in names) },
            { assertTrue("search_hytale_gamedata" in names) },
            { assertTrue("search_hytale_docs" in names) },
            { assertTrue("hytale_stats" in names) },
            { assertTrue("diff_hytale_versions" in names) },
            { assertTrue("get_hytale_file_path" in names) },
            { assertTrue("search_hytale_text" in names) },
            { assertTrue("get_hytale_node" in names) },
        )
    }

    @Test
    fun `search tools have required query parameter`() {
        val mcpServer = server.createServer()
        val searchTools = listOf(
            "search_hytale_code",
            "search_hytale_client_code",
            "search_hytale_gamedata",
            "search_hytale_docs",
        )
        for (name in searchTools) {
            val tool = mcpServer.tools[name]!!.tool
            assertTrue(
                tool.inputSchema.required?.contains("query") == true,
                "$name should require 'query' parameter"
            )
        }
    }

    @Test
    fun `stats tool has no required parameters`() {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["hytale_stats"]!!.tool
        assertTrue(
            tool.inputSchema.required.isNullOrEmpty(),
            "hytale_stats should have no required parameters"
        )
    }


    @Test
    fun `search_hytale_code exposes visibility and annotation parameters`() {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_code"]!!.tool
        assertNotNull(tool.inputSchema.properties?.get("visibility"), "search_hytale_code should expose a 'visibility' parameter")
        assertNotNull(tool.inputSchema.properties?.get("annotation"), "search_hytale_code should expose an 'annotation' parameter")
    }

    @Test
    fun `all index-reading tools expose a patchline parameter`() {
        val mcpServer = server.createServer()
        val toolsWithPatchline = listOf(
            "search_hytale_code",
            "search_hytale_client_code",
            "search_hytale_gamedata",
            "search_hytale_docs",
            "hytale_stats",
            "get_hytale_file_path",
        )
        for (name in toolsWithPatchline) {
            val tool = mcpServer.tools[name]!!.tool
            assertNotNull(
                tool.inputSchema.properties?.get("patchline"),
                "$name should have a 'patchline' parameter"
            )
        }
    }


    @Test
    fun `serviceForPatchline returns service for exact patchline match`() {
        val tempFile = Files.createTempFile("pl_test_", ".db").toFile()
        tempFile.deleteOnExit()
        val releaseDb = KnowledgeDatabase.forFile(tempFile)
        val releaseService = KnowledgeSearchService(releaseDb, CorpusIndexManager(KnowledgeConfig()))

        val tempFile2 = Files.createTempFile("pl_test2_", ".db").toFile()
        tempFile2.deleteOnExit()
        val preReleaseDb = KnowledgeDatabase.forFile(tempFile2)
        val preReleaseService = KnowledgeSearchService(preReleaseDb, CorpusIndexManager(KnowledgeConfig()))

        val srv = HytaleKnowledgeServer(mapOf("release" to releaseService, "pre-release" to preReleaseService))

        assertSame(releaseService, srv.serviceForPatchline("release"))
        assertSame(preReleaseService, srv.serviceForPatchline("pre-release"))

        releaseDb.close()
        preReleaseDb.close()
    }

    @Test
    fun `serviceForPatchline falls back to first service for unknown patchline`() {
        val warning = StringBuilder()
        val result = server.serviceForPatchline("unknown-patchline", warning)
        assertNotNull(result)
        assertTrue(warning.toString().contains("not loaded"), "Should mention not loaded in warning")
    }

    @Test
    fun `serviceForPatchline returns null when no services loaded`() {
        val emptySrv = HytaleKnowledgeServer(emptyMap())
        assertNull(emptySrv.serviceForPatchline("release"))
    }

    private fun versionsBase(): File {
        val base = Files.createTempDirectory("versions_tool_base_").toFile()
        fun ver(slug: String, patchline: String, build: Int, date: String, indexedAt: String) {
            val d = File(base, "versions/$slug").apply { mkdirs() }
            File(d, "knowledge.db").writeText("x")
            File(d, "version_meta.json").writeText(
                """{"patchline":"$patchline","branch":"$patchline","buildNumber":$build,"date":"$date","indexedAt":"$indexedAt"}"""
            )
        }
        ver("release_b100_2026-06-01-aaa", "release", 100, "2026-06-01", "2026-06-02T00:00:00Z")
        ver("pre-release_b178_2026-07-17-bbb", "pre-release", 178, "2026-07-17", "2026-07-18T07:32:22Z")
        ver("pre-release_b164_2026-07-23-ccc", "pre-release", 164, "2026-07-23", "2026-07-23T14:19:56Z")
        return base
    }

    @Test
    fun `serviceForPatchline routes a slug to its own patchline instead of release`() {
        val config = KnowledgeConfig(indexPath = versionsBase().absolutePath)
        val relDb = KnowledgeDatabase.forFile(Files.createTempFile("rel_", ".db").toFile())
        val preDb = KnowledgeDatabase.forFile(Files.createTempFile("pre_", ".db").toFile())
        val relSvc = KnowledgeSearchService(relDb, CorpusIndexManager(config))
        val preSvc = KnowledgeSearchService(preDb, CorpusIndexManager(config))
        val srv = HytaleKnowledgeServer(
            mapOf("release" to relSvc, "pre-release" to preSvc),
            mapOf("release" to relDb, "pre-release" to preDb),
            config = config,
        )

        val warning = StringBuilder()
        assertSame(preSvc, srv.serviceForPatchline("pre-release_b164_2026-07-23-ccc", warning))
        assertEquals("", warning.toString(), "a resolved slug should not emit a fallback warning")
        assertSame(preSvc, srv.serviceForPatchline("pre-release_b164", StringBuilder()))
        assertSame(preSvc, srv.serviceForPatchline("pre-release@b999", StringBuilder()))

        relDb.close()
        preDb.close()
    }

    @Test
    fun `list_hytale_versions marks the newest commit latest and tags branch`() = runBlocking {
        val config = KnowledgeConfig(indexPath = versionsBase().absolutePath)
        val vdb = KnowledgeDatabase.forFile(Files.createTempFile("lv_", ".db").toFile())
        val svc = KnowledgeSearchService(vdb, CorpusIndexManager(config))
        val srv = HytaleKnowledgeServer(mapOf("pre-release" to svc), mapOf("pre-release" to vdb), config = config)
        val tool = srv.createServer().tools["list_hytale_versions"]!!

        val result = tool.handler(callToolRequest("list_hytale_versions"))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val pre = json.parseToJsonElement(text).jsonObject["pre-release"]!!.jsonArray.map { it.jsonObject }
        val b178 = pre.first { it["buildNumber"]!!.jsonPrimitive.content == "178" }
        val b164 = pre.first { it["buildNumber"]!!.jsonPrimitive.content == "164" }
        assertEquals("false", b178["latest"]!!.jsonPrimitive.content)
        assertEquals("true", b164["latest"]!!.jsonPrimitive.content)
        assertEquals("pre-release", b164["branch"]!!.jsonPrimitive.content)
        assertEquals("pre-release", b178["branch"]!!.jsonPrimitive.content)

        vdb.close()
    }


    @Test
    fun `search_hytale_code returns error when query is missing`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_code"]!!
        val result = tool.handler(callToolRequest("search_hytale_code"))

        assertTrue(result.isError == true)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("Missing 'query' parameter"))
    }

    @Test
    fun `search_hytale_code returns empty results for query on empty db`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_code"]!!
        val result = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "how do items drop")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text

        assertTrue(text.contains("how do items drop"), "output should contain the query")
        assertTrue(text.contains("0"), "output should indicate zero results")
    }

    @Test
    fun `search_hytale_gamedata returns error when query is missing`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_gamedata"]!!
        val result = tool.handler(callToolRequest("search_hytale_gamedata"))

        assertTrue(result.isError == true)
    }

    @Test
    fun `search_hytale_docs returns empty results on empty db`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_docs"]!!
        val result = tool.handler(callToolRequest("search_hytale_docs", buildJsonObject {
            put("query", "how to create a mod")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text

        assertTrue(text.contains("0"), "output should indicate zero results")
    }

    @Test
    fun `search_hytale_code exposes a pathPrefix parameter`() {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_code"]!!.tool
        assertNotNull(tool.inputSchema.properties?.get("pathPrefix"))
    }

    @Test
    fun `search_hytale_gamedata exposes a tag parameter`() {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_gamedata"]!!.tool
        assertNotNull(tool.inputSchema.properties?.get("tag"))
    }

    @Test
    fun `search_hytale_code pathPrefix narrows results to matching paths`() = runBlocking {
        val tempFile = Files.createTempFile("pathprefix_tool_", ".db").toFile()
        tempFile.deleteOnExit()
        val cdb = KnowledgeDatabase.forFile(tempFile)
        cdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 1, 'code', 'void run() {}')",
            "inv.Store#run", "Store#run", "decompiled/server/core/inventory/Store.java",
        )
        cdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 1, 'code', 'void run() {}')",
            "net.Socket#run", "Socket#run", "decompiled/server/net/Socket.java",
        )
        val svc = KnowledgeSearchService(cdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to cdb))
        val tool = srv.createServer().tools["search_hytale_code"]!!

        val result = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "run")
            put("pathPrefix", "server/core/inventory")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("Store#run"), "expected the inventory match; got: $text")
        assertFalse(text.contains("Socket#run"), "net match should be filtered out; got: $text")
        cdb.close()
    }

    @Test
    fun `search_hytale_code exposes classExact and pathExact parameters`() {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_code"]!!.tool
        assertAll(
            { assertNotNull(tool.inputSchema.properties?.get("classExact")) },
            { assertNotNull(tool.inputSchema.properties?.get("pathExact")) },
        )
    }

    @Test
    fun `search_hytale_code pathExact narrows to the exact filename`() = runBlocking {
        val tempFile = Files.createTempFile("pathexact_tool_", ".db").toFile()
        tempFile.deleteOnExit()
        val cdb = KnowledgeDatabase.forFile(tempFile)
        cdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 1, 'code', 'void run() {}')",
            "com.x.ItemContainer#run", "ItemContainer#run", "decompiled/a/ItemContainer.java",
        )
        cdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 1, 'code', 'void run() {}')",
            "com.x.CombinedItemContainer#run", "CombinedItemContainer#run", "decompiled/a/CombinedItemContainer.java",
        )
        val svc = KnowledgeSearchService(cdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to cdb))
        val tool = srv.createServer().tools["search_hytale_code"]!!

        val exact = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "run")
            put("pathPrefix", "ItemContainer.java")
            put("pathExact", true)
        }))
        assertNull(exact.isError)
        val exactText = (exact.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(exactText.contains("ItemContainer#run"), "exact should keep ItemContainer; got: $exactText")
        assertFalse(exactText.contains("CombinedItemContainer#run"), "exact should drop CombinedItemContainer; got: $exactText")

        val loose = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "run")
            put("pathPrefix", "ItemContainer.java")
        }))
        assertNull(loose.isError)
        val looseText = (loose.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(looseText.contains("CombinedItemContainer#run"), "substring default should keep CombinedItemContainer; got: $looseText")
        cdb.close()
    }

    @Test
    fun `search_hytale_code classExact excludes a superstring class`() = runBlocking {
        val tempFile = Files.createTempFile("classexact_tool_", ".db").toFile()
        tempFile.deleteOnExit()
        val cdb = KnowledgeDatabase.forFile(tempFile)
        cdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 1, 'code', 'void run() {}')",
            "com.x.ItemContainer#run", "ItemContainer#run", "decompiled/a/ItemContainer.java",
        )
        cdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 1, 'code', 'void run() {}')",
            "com.x.FetchedItemContainer#run", "FetchedItemContainer#run", "decompiled/a/FetchedItemContainer.java",
        )
        val svc = KnowledgeSearchService(cdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to cdb))
        val tool = srv.createServer().tools["search_hytale_code"]!!

        val exact = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "run")
            put("classFilter", "ItemContainer")
            put("classExact", true)
        }))
        assertNull(exact.isError)
        val exactText = (exact.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(exactText.contains("ItemContainer#run"), "exact should keep ItemContainer; got: $exactText")
        assertFalse(exactText.contains("FetchedItemContainer#run"), "exact should drop FetchedItemContainer; got: $exactText")

        val loose = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "run")
            put("classFilter", "ItemContainer")
        }))
        assertNull(loose.isError)
        val looseText = (loose.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(looseText.contains("FetchedItemContainer#run"), "substring default should keep FetchedItemContainer; got: $looseText")
        cdb.close()
    }

    @Test
    fun `search_hytale_gamedata type description notes comma-separation`() {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_gamedata"]!!.tool
        val typeProp = tool.inputSchema.properties?.get("type")!!.jsonObject
        assertTrue(
            typeProp["description"]!!.jsonPrimitive.content.contains("comma", ignoreCase = true),
            "type description should mention comma-separation; got: ${typeProp["description"]}",
        )
    }

    @Test
    fun `search_hytale_gamedata accepts a comma-separated type without error`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["search_hytale_gamedata"]!!
        val result = tool.handler(callToolRequest("search_hytale_gamedata", buildJsonObject {
            put("query", "how to craft a torch")
            put("type", "item,recipe")
        }))
        assertNull(result.isError, "comma-separated type should route through the Set overload without error")
    }

    @Test
    fun `search tool respects limit parameter`() = runBlocking {


        val tempFile = Files.createTempFile("limit_test_", ".db").toFile()
        tempFile.deleteOnExit()
        val ldb = KnowledgeDatabase.forFile(tempFile)
        repeat(5) { i ->
            ldb.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                    "VALUES (?, 'JavaMethod', ?, ?, 1, 'code', ?)",
                "com.example.N$i#m", "N$i#m", "decompiled/com/example/N$i.java",
                "void m() { needleToken(); }",
            )
        }
        val svc = KnowledgeSearchService(ldb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to ldb))
        val tool = srv.createServer().tools["search_hytale_text"]!!

        val result = tool.handler(callToolRequest("search_hytale_text", buildJsonObject {
            put("query", "needleToken")
            put("limit", 3)
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text

        assertTrue(text.contains("resultCount: 3"), "limit=3 should cap matches at 3; got: $text")
        ldb.close()
    }


    @Test
    fun `hytale_stats code returns valid stats on empty db`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["hytale_stats"]!!
        val result = tool.handler(callToolRequest("hytale_stats", buildJsonObject {
            put("corpus", "code")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val parsed = json.parseToJsonElement(text).jsonObject
        assertEquals(0, parsed["nodeCount"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `hytale_stats gamedata returns valid stats on empty db`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["hytale_stats"]!!
        val result = tool.handler(callToolRequest("hytale_stats", buildJsonObject {
            put("corpus", "gamedata")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val parsed = json.parseToJsonElement(text).jsonObject
        assertNotNull(parsed["nodeCount"])
    }

    @Test
    fun `hytale_stats all returns a section per corpus without error`() = runBlocking {
        val mcpServer = server.createServer()
        val tool = mcpServer.tools["hytale_stats"]!!
        val result = tool.handler(callToolRequest("hytale_stats"))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val parsed = json.parseToJsonElement(text).jsonObject
        assertAll(
            { assertNotNull(parsed["code"]) },
            { assertNotNull(parsed["gamedata"]) },
            { assertNotNull(parsed["docs"]) },
            { assertNotNull(parsed["client"]) },
        )
    }


    @Test
    fun `get_hytale_node suggests candidates when the id is not found`() = runBlocking {
        val tempFile = Files.createTempFile("node_fuzzy_", ".db").toFile()
        tempFile.deleteOnExit()
        val ndb = KnowledgeDatabase.forFile(tempFile)
        ndb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.hypixel.hytale.component.TickingThread#assertThread", "TickingThread#assertThread",
            "public void assertThread() {}",
        )
        val svc = KnowledgeSearchService(ndb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to ndb))
        val tool = srv.createServer().tools["get_hytale_node"]!!

        val result = tool.handler(callToolRequest("get_hytale_node", buildJsonObject {
            put("id", "com.hypixel.hytale.component.Other#assertThread")
        }))

        assertNull(result.isError, "not-found with suggestions should not be a hard error")
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val parsed = json.parseToJsonElement(text).jsonObject
        val suggestions = parsed["suggestions"]!!.jsonArray
        assertTrue(
            suggestions.any { it.jsonObject["id"]?.jsonPrimitive?.content == "com.hypixel.hytale.component.TickingThread#assertThread" },
            "expected the existing assertThread node among suggestions",
        )
        ndb.close()
    }


    @Test
    fun `get_hytale_node emits line range for a code node`() = runBlocking {
        val tempFile = Files.createTempFile("node_lines_", ".db").toFile()
        tempFile.deleteOnExit()
        val ndb = KnowledgeDatabase.forFile(tempFile)
        ndb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 42, 58, 'code', ?)",
            "com.x.Store#foo", "Store#foo", "decompiled/com/x/Store.java", "public void foo() {}",
        )
        val svc = KnowledgeSearchService(ndb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to ndb))
        val tool = srv.createServer().tools["get_hytale_node"]!!

        val result = tool.handler(callToolRequest("get_hytale_node", buildJsonObject {
            put("id", "com.x.Store#foo")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val node = json.parseToJsonElement(text).jsonObject["node"]!!.jsonObject
        assertEquals(42, node["line_start"]?.jsonPrimitive?.content?.toInt())
        assertEquals(58, node["line_end"]?.jsonPrimitive?.content?.toInt())
        ndb.close()
    }

    @Test
    fun `get_hytale_node truncates long content and sets the truncated flag`() = runBlocking {
        val tempFile = Files.createTempFile("node_trunc_", ".db").toFile()
        tempFile.deleteOnExit()
        val ndb = KnowledgeDatabase.forFile(tempFile)
        val longContent = (1..2000).joinToString("\n") { "line $it of a very long decompiled method body" }
        ndb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.x.Big#huge", "Big#huge", longContent,
        )
        val svc = KnowledgeSearchService(ndb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to ndb))
        val tool = srv.createServer().tools["get_hytale_node"]!!

        val result = tool.handler(callToolRequest("get_hytale_node", buildJsonObject {
            put("id", "com.x.Big#huge")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val node = json.parseToJsonElement(text).jsonObject["node"]!!.jsonObject
        assertTrue(node["truncated"]?.jsonPrimitive?.content?.toBoolean() == true, "truncated flag should be true; got: $text")
        assertTrue(node["content"]!!.jsonPrimitive.content.endsWith("…"), "content should end with the truncation marker; got tail: ...${node["content"]!!.jsonPrimitive.content.takeLast(20)}")
        ndb.close()
    }

    @Test
    fun `get_hytale_node returns full content with truncated false for short content`() = runBlocking {
        val tempFile = Files.createTempFile("node_short_", ".db").toFile()
        tempFile.deleteOnExit()
        val ndb = KnowledgeDatabase.forFile(tempFile)
        val shortContent = "public void tiny() {\n    doThing();\n}"
        ndb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.x.Tiny#tiny", "Tiny#tiny", shortContent,
        )
        val svc = KnowledgeSearchService(ndb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to ndb))
        val tool = srv.createServer().tools["get_hytale_node"]!!

        val result = tool.handler(callToolRequest("get_hytale_node", buildJsonObject {
            put("id", "com.x.Tiny#tiny")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val node = json.parseToJsonElement(text).jsonObject["node"]!!.jsonObject
        assertTrue(node["truncated"]?.jsonPrimitive?.content?.toBoolean() == false, "truncated flag should be false; got: $text")
        assertEquals(shortContent, node["content"]!!.jsonPrimitive.content)
        ndb.close()
    }


    @Test
    fun `get_hytale_node resolves an unambiguous display_name to the node`() = runBlocking {
        val tempFile = Files.createTempFile("node_resolve_dn_", ".db").toFile()
        tempFile.deleteOnExit()
        val ndb = KnowledgeDatabase.forFile(tempFile)
        ndb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.hypixel.hytale.inventory.ItemContainer#swapItems", "ItemContainer#swapItems",
            "public void swapItems() {}",
        )
        val svc = KnowledgeSearchService(ndb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to ndb))
        val tool = srv.createServer().tools["get_hytale_node"]!!

        val result = tool.handler(callToolRequest("get_hytale_node", buildJsonObject {
            put("id", "ItemContainer#swapItems")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val parsed = json.parseToJsonElement(text).jsonObject
        assertNull(parsed["suggestions"], "an unambiguous display_name should resolve, not suggest; got: $text")
        val node = parsed["node"]!!.jsonObject
        assertEquals("com.hypixel.hytale.inventory.ItemContainer#swapItems", node["id"]?.jsonPrimitive?.content)
        ndb.close()
    }

    @Test
    fun `get_hytale_node resolves a FQCN-suffix id to the node`() = runBlocking {
        val tempFile = Files.createTempFile("node_resolve_suffix_", ".db").toFile()
        tempFile.deleteOnExit()
        val ndb = KnowledgeDatabase.forFile(tempFile)
        ndb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaClass', ?, 'code', ?)",
            "com.hypixel.hytale.inventory.ItemContainer", "ItemContainer",
            "public class ItemContainer {}",
        )
        val svc = KnowledgeSearchService(ndb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to ndb))
        val tool = srv.createServer().tools["get_hytale_node"]!!

        val result = tool.handler(callToolRequest("get_hytale_node", buildJsonObject {
            put("id", "inventory.ItemContainer")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val parsed = json.parseToJsonElement(text).jsonObject
        assertNull(parsed["suggestions"], "a FQCN-suffix should resolve, not suggest; got: $text")
        assertEquals("com.hypixel.hytale.inventory.ItemContainer", parsed["node"]!!.jsonObject["id"]?.jsonPrimitive?.content)
        ndb.close()
    }

    @Test
    fun `get_hytale_node returns the node for an exact full id`() = runBlocking {
        val tempFile = Files.createTempFile("node_exact_", ".db").toFile()
        tempFile.deleteOnExit()
        val ndb = KnowledgeDatabase.forFile(tempFile)
        ndb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.x.Store#foo", "Store#foo", "public void foo() {}",
        )
        val svc = KnowledgeSearchService(ndb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to ndb))
        val tool = srv.createServer().tools["get_hytale_node"]!!

        val result = tool.handler(callToolRequest("get_hytale_node", buildJsonObject {
            put("id", "com.x.Store#foo")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val parsed = json.parseToJsonElement(text).jsonObject
        assertNull(parsed["suggestions"])
        assertEquals("com.x.Store#foo", parsed["node"]!!.jsonObject["id"]?.jsonPrimitive?.content)
        ndb.close()
    }

    @Test
    fun `get_hytale_node returns candidates for an ambiguous display_name`() = runBlocking {
        val tempFile = Files.createTempFile("node_ambig_", ".db").toFile()
        tempFile.deleteOnExit()
        val ndb = KnowledgeDatabase.forFile(tempFile)
        ndb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.x.Store#dup", "Store#dup", "public void dup() {}",
        )
        ndb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.y.Store#dup", "Store#dup", "public void dup() {}",
        )
        val svc = KnowledgeSearchService(ndb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to ndb))
        val tool = srv.createServer().tools["get_hytale_node"]!!

        val result = tool.handler(callToolRequest("get_hytale_node", buildJsonObject {
            put("id", "Store#dup")
        }))

        assertNull(result.isError, "ambiguity should not be a hard error")
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        val parsed = json.parseToJsonElement(text).jsonObject
        assertNull(parsed["node"], "ambiguous id must not return a single wrong node; got: $text")
        assertTrue(text.contains("com.x.Store#dup"), "candidates should list both ids; got: $text")
        assertTrue(text.contains("com.y.Store#dup"), "candidates should list both ids; got: $text")
        ndb.close()
    }

    @Test
    fun `get_hytale_related resolves an unambiguous display_name and returns neighbors`() = runBlocking {
        val tempFile = Files.createTempFile("related_resolve_", ".db").toFile()
        tempFile.deleteOnExit()
        val rdb = KnowledgeDatabase.forFile(tempFile)
        rdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaClass', ?, 'code', ?)",
            "class:com.x.ItemContainer", "ItemContainer", "public class ItemContainer {}",
        )
        rdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.x.ItemContainer#swapItems", "ItemContainer#swapItems", "public void swapItems() {}",
        )
        rdb.execute(
            "INSERT INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'DECLARED_IN')",
            "com.x.ItemContainer#swapItems", "class:com.x.ItemContainer",
        )
        val svc = KnowledgeSearchService(rdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to rdb))
        val tool = srv.createServer().tools["get_hytale_related"]!!

        val result = tool.handler(callToolRequest("get_hytale_related", buildJsonObject {
            put("id", "ItemContainer#swapItems")
        }))

        assertNull(result.isError, "an unambiguous display_name should resolve, not fail")
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("class:com.x.ItemContainer"), "should return the DECLARED_IN class neighbor; got: $text")
        rdb.close()
    }

    @Test
    fun `get_hytale_related returns neighbors for an exact full id`() = runBlocking {
        val tempFile = Files.createTempFile("related_exact_", ".db").toFile()
        tempFile.deleteOnExit()
        val rdb = KnowledgeDatabase.forFile(tempFile)
        rdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaClass', ?, 'code', ?)",
            "class:com.x.ItemContainer", "ItemContainer", "public class ItemContainer {}",
        )
        rdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.x.ItemContainer#swapItems", "ItemContainer#swapItems", "public void swapItems() {}",
        )
        rdb.execute(
            "INSERT INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'DECLARED_IN')",
            "com.x.ItemContainer#swapItems", "class:com.x.ItemContainer",
        )
        val svc = KnowledgeSearchService(rdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to rdb))
        val tool = srv.createServer().tools["get_hytale_related"]!!

        val result = tool.handler(callToolRequest("get_hytale_related", buildJsonObject {
            put("id", "com.x.ItemContainer#swapItems")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("class:com.x.ItemContainer"), "exact id should still return neighbors; got: $text")
        rdb.close()
    }

    @Test
    fun `get_hytale_related returns candidates for an ambiguous display_name`() = runBlocking {
        val tempFile = Files.createTempFile("related_ambig_", ".db").toFile()
        tempFile.deleteOnExit()
        val rdb = KnowledgeDatabase.forFile(tempFile)
        rdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.x.Store#dup", "Store#dup", "public void dup() {}",
        )
        rdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.y.Store#dup", "Store#dup", "public void dup() {}",
        )
        val svc = KnowledgeSearchService(rdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to rdb))
        val tool = srv.createServer().tools["get_hytale_related"]!!

        val result = tool.handler(callToolRequest("get_hytale_related", buildJsonObject {
            put("id", "Store#dup")
        }))

        assertNull(result.isError, "ambiguity should not be a hard error")
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("com.x.Store#dup"), "candidates should list both ids; got: $text")
        assertTrue(text.contains("com.y.Store#dup"), "candidates should list both ids; got: $text")
        rdb.close()
    }

    @Test
    fun `get_hytale_related reports not found for a truly unknown id`() = runBlocking {
        val tempFile = Files.createTempFile("related_unknown_", ".db").toFile()
        tempFile.deleteOnExit()
        val rdb = KnowledgeDatabase.forFile(tempFile)
        rdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaMethod', ?, 'code', ?)",
            "com.x.Store#foo", "Store#foo", "public void foo() {}",
        )
        val svc = KnowledgeSearchService(rdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to rdb))
        val tool = srv.createServer().tools["get_hytale_related"]!!

        val result = tool.handler(callToolRequest("get_hytale_related", buildJsonObject {
            put("id", "NoSuchClass#nope")
        }))

        assertTrue(result.isError == true)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("Node not found"), "unknown id should report not found; got: $text")
        rdb.close()
    }

    @Test
    fun `search_hytale_text finds a literal substring in indexed content`() = runBlocking {
        val tempFile = Files.createTempFile("text_search_", ".db").toFile()
        tempFile.deleteOnExit()
        val tdb = KnowledgeDatabase.forFile(tempFile)
        tdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 100, 'code', ?)",
            "com.hypixel.hytale.component.Store#assertThread", "Store#assertThread",
            "decompiled/com/hypixel/hytale/component/Store.java",
            "public void assertThread() {\n    throw new IllegalStateException(\"Assert not in thread!\");\n}",
        )
        val svc = KnowledgeSearchService(tdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to tdb))
        val tool = srv.createServer().tools["search_hytale_text"]!!

        val result = tool.handler(callToolRequest("search_hytale_text", buildJsonObject {
            put("query", "Assert not in thread")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text

        assertTrue(text.contains("Store#assertThread"), "output should contain the matching method name")
        tdb.close()
    }

    @Test
    fun `search_hytale_text gamedata corpus finds a JSON key via LIKE fallback with context`() = runBlocking {
        val tempFile = Files.createTempFile("text_gamedata_", ".db").toFile()
        tempFile.deleteOnExit()
        val tdb = KnowledgeDatabase.forFile(tempFile)
        tdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'GameData', ?, ?, 1, 'gamedata', ?)",
            "gamedata:item:iron_sword", "IronSword", "items/iron_sword.json",
            "{\n  \"Name\": \"IronSword\",\n  \"MaxDurability\": 120,\n  \"Type\": \"Weapon\"\n}",
        )
        val svc = KnowledgeSearchService(tdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to tdb))
        val tool = srv.createServer().tools["search_hytale_text"]!!

        val result = tool.handler(callToolRequest("search_hytale_text", buildJsonObject {
            put("query", "MaxDurability")
            put("corpus", "gamedata")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("MaxDurability"), "gamedata corpus should reach the JSON key; got: $text")
        assertTrue(text.contains("IronSword"), "matching line should carry ±context (the Name line above); got: $text")
        assertTrue(text.contains("Weapon"), "matching line should carry ±context (the Type line below); got: $text")
        tdb.close()
    }

    @Test
    fun `search_hytale_text returns up to N matching lines per node not just the first`() = runBlocking {
        val tempFile = Files.createTempFile("text_multiline_", ".db").toFile()
        tempFile.deleteOnExit()
        val tdb = KnowledgeDatabase.forFile(tempFile)
        tdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'GameData', ?, ?, 1, 'gamedata', ?)",
            "gamedata:block:foo", "Foo", "blocks/foo.json",
            "needleToken one\nfiller\nneedleToken two\nfiller\nneedleToken three",
        )
        val svc = KnowledgeSearchService(tdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to tdb))
        val tool = srv.createServer().tools["search_hytale_text"]!!

        val result = tool.handler(callToolRequest("search_hytale_text", buildJsonObject {
            put("query", "needleToken")
            put("corpus", "gamedata")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("needleToken one"), "should include the first occurrence; got: $text")
        assertTrue(text.contains("needleToken two"), "should include the second occurrence; got: $text")
        assertTrue(text.contains("needleToken three"), "should include the third occurrence, not just the first; got: $text")
        tdb.close()
    }

    @Test
    fun `search_hytale_text result rows carry id and corpus`() = runBlocking {
        val tempFile = Files.createTempFile("text_idcorpus_", ".db").toFile()
        tempFile.deleteOnExit()
        val tdb = KnowledgeDatabase.forFile(tempFile)
        tdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'GameData', ?, ?, 1, 'gamedata', ?)",
            "gamedata:item:torch", "Torch", "items/torch.json",
            "{\n  \"MaxDurability\": 5\n}",
        )
        val svc = KnowledgeSearchService(tdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to tdb))
        val tool = srv.createServer().tools["search_hytale_text"]!!

        val result = tool.handler(callToolRequest("search_hytale_text", buildJsonObject {
            put("query", "MaxDurability")
            put("corpus", "gamedata")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("gamedata:item:torch"), "row should carry the node id for chaining; got: $text")
        assertTrue(text.contains("gamedata"), "row should carry the corpus; got: $text")
        tdb.close()
    }

    @Test
    fun `search_hytale_text default corpus code still works`() = runBlocking {
        val tempFile = Files.createTempFile("text_default_code_", ".db").toFile()
        tempFile.deleteOnExit()
        val tdb = KnowledgeDatabase.forFile(tempFile)
        tdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 100, 'code', ?)",
            "com.hypixel.hytale.component.Store#assertThread", "Store#assertThread",
            "decompiled/com/hypixel/hytale/component/Store.java",
            "public void assertThread() {\n    throw new IllegalStateException(\"Assert not in thread!\");\n}",
        )
        tdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'GameData', ?, ?, 1, 'gamedata', ?)",
            "gamedata:item:thread", "Thread", "items/thread.json",
            "{\n  \"Assert not in thread\": true\n}",
        )
        val svc = KnowledgeSearchService(tdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to tdb))
        val tool = srv.createServer().tools["search_hytale_text"]!!

        val result = tool.handler(callToolRequest("search_hytale_text", buildJsonObject {
            put("query", "Assert not in thread")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("Store#assertThread"), "default corpus=code should still find the code node; got: $text")
        assertFalse(text.contains("gamedata:item:thread"), "default corpus=code must not leak the gamedata node; got: $text")
        tdb.close()
    }


    private fun hierarchyServer(): Pair<KnowledgeDatabase, HytaleKnowledgeServer> {
        val tempFile = Files.createTempFile("related_hier_", ".db").toFile()
        tempFile.deleteOnExit()
        val hdb = KnowledgeDatabase.forFile(tempFile)
        fun node(id: String, dn: String) =
            hdb.execute("INSERT INTO nodes (id, node_type, display_name, corpus, content) VALUES (?, 'JavaClass', ?, 'code', '')", id, dn)
        fun edge(src: String, tgt: String, type: String) =
            hdb.execute("INSERT INTO edges (source_id, target_id, edge_type) VALUES (?, ?, ?)", src, tgt, type)
        node("class:com.x.Base", "Base")
        node("class:com.x.Iface", "Iface")
        node("class:com.x.Sub", "Sub")
        node("class:com.x.Leaf", "Leaf")
        node("class:com.x.Helper", "Helper")
        edge("class:com.x.Sub", "class:com.x.Base", "EXTENDS")
        edge("class:com.x.Sub", "class:com.x.Iface", "IMPLEMENTS")
        edge("class:com.x.Leaf", "class:com.x.Sub", "EXTENDS")
        edge("class:com.x.Sub", "class:com.x.Helper", "CALLS")
        val svc = KnowledgeSearchService(hdb, CorpusIndexManager(KnowledgeConfig()))
        return hdb to HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to hdb))
    }

    @Test
    fun `get_hytale_related direction up with edgeTypes returns only the parents`() = runBlocking {
        val (hdb, srv) = hierarchyServer()
        val tool = srv.createServer().tools["get_hytale_related"]!!
        val result = tool.handler(callToolRequest("get_hytale_related", buildJsonObject {
            put("id", "class:com.x.Sub")
            put("direction", "up")
            putJsonArray("edgeTypes") { add("EXTENDS"); add("IMPLEMENTS") }
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("class:com.x.Base"), "parent via EXTENDS should be present; got: $text")
        assertTrue(text.contains("class:com.x.Iface"), "parent via IMPLEMENTS should be present; got: $text")
        assertFalse(text.contains("class:com.x.Leaf"), "a child (incoming) must be excluded by direction up; got: $text")
        assertFalse(text.contains("class:com.x.Helper"), "a CALLS neighbor must be excluded by edgeTypes; got: $text")
        assertTrue(text.contains("outgoing"), "output must keep the direction field; got: $text")
        hdb.close()
    }

    @Test
    fun `get_hytale_related direction down returns only the children`() = runBlocking {
        val (hdb, srv) = hierarchyServer()
        val tool = srv.createServer().tools["get_hytale_related"]!!
        val result = tool.handler(callToolRequest("get_hytale_related", buildJsonObject {
            put("id", "class:com.x.Sub")
            put("direction", "down")
            putJsonArray("edgeTypes") { add("EXTENDS") }
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("class:com.x.Leaf"), "child via incoming EXTENDS should be present; got: $text")
        assertFalse(text.contains("class:com.x.Base"), "a parent (outgoing) must be excluded by direction down; got: $text")
        assertFalse(text.contains("class:com.x.Iface"), "a parent (outgoing) must be excluded; got: $text")
        hdb.close()
    }

    private fun classDb(): Pair<KnowledgeDatabase, HytaleKnowledgeServer> {
        val tempFile = Files.createTempFile("class_tool_", ".db").toFile()
        tempFile.deleteOnExit()
        val cdb = KnowledgeDatabase.forFile(tempFile)
        fun insert(id: String, displayName: String, lineStart: Int, lineEnd: Int, content: String) =
            cdb.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                    "VALUES (?, 'JavaMethod', ?, 'decompiled/com/x/Store.java', ?, ?, ?, 'com/x/Store.java', 'code')",
                id, displayName, lineStart, lineEnd, content,
            )
        insert("com.x.Store#bar", "Store#bar", 30, 35, "public int bar(String s) {\n    return s.length();\n}")
        insert("com.x.Store#foo", "Store#foo", 10, 15, "public void foo() {\n    doThing();\n}")
        val svc = KnowledgeSearchService(cdb, CorpusIndexManager(KnowledgeConfig()))
        return cdb to HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to cdb))
    }

    @Test
    fun `get_hytale_class outline lists all methods ordered by line with signatures`() = runBlocking {
        val (cdb, srv) = classDb()
        val tool = srv.createServer().tools["get_hytale_class"]!!
        val result = tool.handler(callToolRequest("get_hytale_class", buildJsonObject {
            put("className", "Store")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.indexOf("Store#foo") < text.indexOf("Store#bar"), "methods ordered by line; got: $text")
        assertTrue(text.contains("public void foo()"), "signature should be the first content line; got: $text")
        assertTrue(text.contains("public int bar(String s)"), "signature should be the first content line; got: $text")
        assertFalse(text.contains("doThing"), "outline should not include method bodies; got: $text")
        cdb.close()
    }

    @Test
    fun `get_hytale_class does not accept full and never emits method bodies`() = runBlocking {
        val (cdb, srv) = classDb()
        val tool = srv.createServer().tools["get_hytale_class"]!!
        assertNull(tool.tool.inputSchema.properties?.get("full"), "full param should be removed")
        val result = tool.handler(callToolRequest("get_hytale_class", buildJsonObject {
            put("className", "Store")
            put("full", true)
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertFalse(text.contains("doThing"), "outline must never include method bodies; got: $text")
        cdb.close()
    }

    private fun annotatedClassDb(): Pair<KnowledgeDatabase, HytaleKnowledgeServer> {
        val tempFile = Files.createTempFile("annotated_class_tool_", ".db").toFile()
        tempFile.deleteOnExit()
        val cdb = KnowledgeDatabase.forFile(tempFile)
        fun insert(id: String, displayName: String, lineStart: Int, lineEnd: Int, content: String) =
            cdb.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                    "VALUES (?, 'JavaMethod', ?, 'decompiled/com/x/Store.java', ?, ?, ?, 'com/x/Store.java', 'code')",
                id, displayName, lineStart, lineEnd, content,
            )
        insert("com.x.Store#bar", "Store#bar", 30, 36, "@Nonnull\npublic Foo bar(int x) {\n    return new Foo(x);\n}")
        insert("com.x.Store#baz", "Store#baz", 40, 48, "/**\n * Docs for baz.\n */\npublic void baz() {\n    work();\n}")
        insert("com.x.Store#foo", "Store#foo", 10, 15, "public void foo() {\n    doThing();\n}")
        val svc = KnowledgeSearchService(cdb, CorpusIndexManager(KnowledgeConfig()))
        return cdb to HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to cdb))
    }

    @Test
    fun `get_hytale_class signature skips a leading annotation to the declaration`() = runBlocking {
        val (cdb, srv) = annotatedClassDb()
        val tool = srv.createServer().tools["get_hytale_class"]!!
        val result = tool.handler(callToolRequest("get_hytale_class", buildJsonObject {
            put("className", "Store")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("public Foo bar(int x) {"), "signature should be the declaration, not the annotation; got: $text")
        assertFalse(text.contains("@Nonnull"), "signature must not be the leading annotation; got: $text")
        cdb.close()
    }

    @Test
    fun `get_hytale_class signature skips a leading javadoc block to the declaration`() = runBlocking {
        val (cdb, srv) = annotatedClassDb()
        val tool = srv.createServer().tools["get_hytale_class"]!!
        val result = tool.handler(callToolRequest("get_hytale_class", buildJsonObject {
            put("className", "Store")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("public void baz()"), "signature should be the declaration, not the javadoc; got: $text")
        assertFalse(text.contains("Docs for baz"), "signature must not be the javadoc body; got: $text")
        cdb.close()
    }

    @Test
    fun `get_hytale_class signature is unchanged for a plain declaration on line 1`() = runBlocking {
        val (cdb, srv) = annotatedClassDb()
        val tool = srv.createServer().tools["get_hytale_class"]!!
        val result = tool.handler(callToolRequest("get_hytale_class", buildJsonObject {
            put("className", "Store")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("public void foo()"), "plain declaration signature should be unchanged; got: $text")
        cdb.close()
    }

    @Test
    fun `get_hytale_class returns empty result for unknown class`() = runBlocking {
        val (cdb, srv) = classDb()
        val tool = srv.createServer().tools["get_hytale_class"]!!
        val result = tool.handler(callToolRequest("get_hytale_class", buildJsonObject {
            put("className", "Nonexistent")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("0"), "unknown class should report zero methods; got: $text")
        cdb.close()
    }

    private fun inheritedClassDb(): Pair<KnowledgeDatabase, HytaleKnowledgeServer> {
        val tempFile = Files.createTempFile("inherited_class_tool_", ".db").toFile()
        tempFile.deleteOnExit()
        val cdb = KnowledgeDatabase.forFile(tempFile)
        fun method(id: String, displayName: String, lineStart: Int, lineEnd: Int, content: String, owningFile: String) =
            cdb.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                    "VALUES (?, 'JavaMethod', ?, ?, ?, ?, ?, ?, 'code')",
                id, displayName, "decompiled/$owningFile", lineStart, lineEnd, content, owningFile,
            )
        fun classNode(fqcn: String, owningFile: String) =
            cdb.execute(
                "INSERT INTO nodes (id, node_type, display_name, owning_file, corpus) VALUES (?, 'JavaClass', ?, ?, 'code')",
                "class:$fqcn", fqcn.substringAfterLast('.'), owningFile,
            )
        method("com.x.Base#ping", "Base#ping", 5, 7, "public void ping() {}", "com/x/Base.java")
        classNode("com.x.Base", "com/x/Base.java")
        method("com.x.Sub#run", "Sub#run", 4, 6, "public void run() {}", "com/x/Sub.java")
        classNode("com.x.Sub", "com/x/Sub.java")
        cdb.execute(
            "INSERT INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'EXTENDS')",
            "class:com.x.Sub", "class:com.x.Base",
        )
        val svc = KnowledgeSearchService(cdb, CorpusIndexManager(KnowledgeConfig()))
        return cdb to HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to cdb))
    }

    @Test
    fun `get_hytale_class inlines inherited methods marked by declaring type`() = runBlocking {
        val (cdb, srv) = inheritedClassDb()
        val tool = srv.createServer().tools["get_hytale_class"]!!
        val result = tool.handler(callToolRequest("get_hytale_class", buildJsonObject {
            put("className", "Sub")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("methodCount: 2"), "count should include the inherited method; got: $text")
        assertTrue(text.contains("Sub#run"), "own method should be present; got: $text")
        assertTrue(text.contains("Base#ping"), "inherited method should be inlined; got: $text")
        assertTrue(text.contains("declaringType"), "each method should carry its declaring type; got: $text")
        assertTrue(text.contains("inherited"), "output should flag inherited methods; got: $text")
        cdb.close()
    }

    private fun homonymClassDb(): Pair<KnowledgeDatabase, HytaleKnowledgeServer> {
        val tempFile = Files.createTempFile("homonym_class_", ".db").toFile()
        tempFile.deleteOnExit()
        val cdb = KnowledgeDatabase.forFile(tempFile)
        fun method(id: String, displayName: String, content: String, owningFile: String) =
            cdb.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                    "VALUES (?, 'JavaMethod', ?, ?, 1, 3, ?, ?, 'code')",
                id, displayName, "decompiled/$owningFile", content, owningFile,
            )
        fun classNode(fqcn: String, owningFile: String) =
            cdb.execute(
                "INSERT INTO nodes (id, node_type, display_name, owning_file, corpus) VALUES (?, 'JavaClass', ?, ?, 'code')",
                "class:$fqcn", fqcn.substringAfterLast('.'), owningFile,
            )
        // Insert the unrelated homonym first so a buggy simple-name LIMIT 1 lookup would prefer it.
        classNode("com.b.Main", "com/b/Main.java")
        method("com.b.Main#show", "Main#show", "public void show() {}", "com/b/Main.java")
        classNode("com.a.Main", "com/a/Main.java")
        method("com.a.Main#boot", "Main#boot", "public void boot() {}", "com/a/Main.java")
        val svc = KnowledgeSearchService(cdb, CorpusIndexManager(KnowledgeConfig()))
        return cdb to HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to cdb))
    }

    @Test
    fun `get_hytale_class resolves a fully qualified name to the exact same-named class`() = runBlocking {
        val (cdb, srv) = homonymClassDb()
        val tool = srv.createServer().tools["get_hytale_class"]!!

        val aResult = tool.handler(callToolRequest("get_hytale_class", buildJsonObject { put("className", "com.a.Main") }))
        val aText = (aResult.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(aText.contains("Main#boot"), "FQN com.a.Main must resolve to its own method; got: $aText")
        assertFalse(aText.contains("Main#show"), "FQN com.a.Main must not pull in com.b.Main's method; got: $aText")

        val bResult = tool.handler(callToolRequest("get_hytale_class", buildJsonObject { put("className", "com.b.Main") }))
        val bText = (bResult.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(bText.contains("Main#show"), "FQN com.b.Main must resolve to its own method; got: $bText")
        assertFalse(bText.contains("Main#boot"), "FQN com.b.Main must not pull in com.a.Main's method; got: $bText")
        cdb.close()
    }

    @Test
    fun `resolveMethodSource with a fully qualified class matches only that class`() {
        val tempFile = Files.createTempFile("homonym_method_", ".db").toFile()
        tempFile.deleteOnExit()
        val cdb = KnowledgeDatabase.forFile(tempFile)
        fun method(id: String, displayName: String, owningFile: String) =
            cdb.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                    "VALUES (?, 'JavaMethod', ?, ?, 1, 3, '', ?, 'code')",
                id, displayName, "decompiled/$owningFile", owningFile,
            )
        method("com.a.Main#boot", "Main#boot", "com/a/Main.java")
        method("com.b.Main#boot", "Main#boot", "com/b/Main.java")
        val svc = KnowledgeSearchService(cdb, CorpusIndexManager(KnowledgeConfig()))

        val res = svc.resolveMethodSource("com.a.Main", "boot")
        assertEquals(
            1, res.matches.size,
            "a fully qualified class must not match a same-named class in another package; got: ${res.matches.map { it.id }}",
        )
        assertEquals("com.a.Main#boot", res.matches.first().id)
        cdb.close()
    }

    @Test
    fun `get_hytale_api_surface is not registered`() {
        val mcpServer = server.createServer()
        assertFalse("get_hytale_api_surface" in mcpServer.tools.keys)
    }

    @Test
    fun `search results with differing dataType serialize as a TOON table`() = runBlocking {
        val tempFile = Files.createTempFile("toon_uniform_", ".db").toFile()
        tempFile.deleteOnExit()
        val tdb = KnowledgeDatabase.forFile(tempFile)
        tdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 1, 'code', ?)",
            "com.x.A#needleMethod", "needleMethod", "decompiled/com/x/A.java",
            "public void needleMethod() {}",
        )
        tdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content, data_type) " +
                "VALUES (?, 'JavaField', ?, ?, 1, 'code', ?, 'field')",
            "com.x.B#OtherThing", "OtherThing", "decompiled/com/x/B.java",
            "public int OtherThing = needleMethod;",
        )
        com.hyindex.knowledge.index.FtsTokenizer.populate(
            tdb, "code",
            listOf(
                com.hyindex.knowledge.index.FtsTokenizer.FtsRow("com.x.A#needleMethod", "needleMethod", "public void needleMethod() {}"),
                com.hyindex.knowledge.index.FtsTokenizer.FtsRow("com.x.B#OtherThing", "OtherThing", "public int OtherThing = needleMethod;"),
            ),
            splitBody = true,
        )
        val svc = KnowledgeSearchService(tdb, CorpusIndexManager(KnowledgeConfig()))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to tdb))
        val tool = srv.createServer().tools["search_hytale_code"]!!

        val result = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "needleMethod")
        }))

        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text

        assertTrue(text.contains("needleMethod"), "expected the name-matched row")
        assertTrue(text.contains("OtherThing"), "expected the fts-only row with a data_type")
        assertFalse(text.contains("\"results\""), "differing dataType should not force JSON; got: $text")
        assertTrue(
            text.contains("results[2]{") && text.contains(",dataType,") && text.contains(",connectedNodeIds}:"),
            "rows should encode as a uniform TOON table with fixed columns; got: $text",
        )
        tdb.close()
    }

    private fun verbosityServer(): Pair<KnowledgeDatabase, HytaleKnowledgeServer> {
        val tempFile = Files.createTempFile("verbosity_", ".db").toFile()
        tempFile.deleteOnExit()
        val vdb = KnowledgeDatabase.forFile(tempFile)
        vdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, corpus, content) " +
                "VALUES (?, 'JavaMethod', ?, ?, 1, 'code', ?)",
            "com.x.A#needleMethod", "needleMethod", "decompiled/com/x/A.java",
            "public void needleMethod() { return 42; }",
        )
        com.hyindex.knowledge.index.FtsTokenizer.populate(
            vdb, "code",
            listOf(
                com.hyindex.knowledge.index.FtsTokenizer.FtsRow("com.x.A#needleMethod", "needleMethod", "public void needleMethod() { return 42; }"),
            ),
            splitBody = true,
        )
        val svc = KnowledgeSearchService(vdb, CorpusIndexManager(KnowledgeConfig()))
        return vdb to HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to vdb))
    }

    @Test
    fun `search verbosity ids returns only id displayName score corpus`() = runBlocking {
        val (vdb, srv) = verbosityServer()
        val tool = srv.createServer().tools["search_hytale_code"]!!
        val result = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "needleMethod")
            put("verbosity", "ids")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("needleMethod"), "expected the matched row; got: $text")
        assertFalse(text.contains("snippet"), "ids verbosity must omit snippet; got: $text")
        assertFalse(text.contains("filePath"), "ids verbosity must omit filePath; got: $text")
        assertFalse(text.contains("dataType"), "ids verbosity must omit dataType; got: $text")
        assertFalse(text.contains("connectedNodeIds"), "ids verbosity must omit connectedNodeIds; got: $text")
        assertTrue(
            text.contains("results[1]{id,displayName,score,corpus}:"),
            "ids verbosity should expose exactly id,displayName,score,corpus; got: $text",
        )
        vdb.close()
    }

    @Test
    fun `search verbosity compact omits snippet but keeps other fields`() = runBlocking {
        val (vdb, srv) = verbosityServer()
        val tool = srv.createServer().tools["search_hytale_code"]!!
        val result = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "needleMethod")
            put("verbosity", "compact")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("needleMethod"), "expected the matched row; got: $text")
        assertFalse(text.contains("snippet"), "compact verbosity must omit snippet; got: $text")
        assertTrue(text.contains("filePath"), "compact verbosity must keep filePath; got: $text")
        assertTrue(text.contains("connectedNodeIds"), "compact verbosity must keep connectedNodeIds; got: $text")
        vdb.close()
    }

    @Test
    fun `search verbosity full keeps the snippet field`() = runBlocking {
        val (vdb, srv) = verbosityServer()
        val tool = srv.createServer().tools["search_hytale_code"]!!
        val result = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "needleMethod")
            put("verbosity", "full")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("snippet"), "full verbosity must keep snippet; got: $text")
        assertTrue(text.contains("connectedNodeIds"), "full verbosity must keep connectedNodeIds; got: $text")
        vdb.close()
    }

    @Test
    fun `search default verbosity keeps the snippet field`() = runBlocking {
        val (vdb, srv) = verbosityServer()
        val tool = srv.createServer().tools["search_hytale_code"]!!
        val result = tool.handler(callToolRequest("search_hytale_code", buildJsonObject {
            put("query", "needleMethod")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("snippet"), "default verbosity must behave like full and keep snippet; got: $text")
        vdb.close()
    }

    @Test
    fun `search tools expose a verbosity parameter`() {
        val mcpServer = server.createServer()
        val toolsWithVerbosity = listOf(
            "search_hytale",
            "search_hytale_code",
            "search_hytale_client_code",
            "search_hytale_gamedata",
            "search_hytale_docs",
            "get_hytale_implementing_code",
            "get_modding_context",
        )
        for (name in toolsWithVerbosity) {
            val tool = mcpServer.tools[name]!!.tool
            assertNotNull(
                tool.inputSchema.properties?.get("verbosity"),
                "$name should have a 'verbosity' parameter",
            )
        }
    }

    @Test
    fun `get_modding_context honors verbosity ids through the shared builder`() = runBlocking {
        val (vdb, srv) = verbosityServer()
        val tool = srv.createServer().tools["get_modding_context"]!!
        val result = tool.handler(callToolRequest("get_modding_context", buildJsonObject {
            put("topic", "needleMethod")
            put("verbosity", "ids")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("needleMethod"), "expected the code row; got: $text")
        assertFalse(text.contains("snippet"), "ids verbosity must omit snippet from modding context; got: $text")
        vdb.close()
    }

    private val fooLines = (1..10).map { "line $it of Foo" }

    private fun sourceServer(): Pair<KnowledgeDatabase, HytaleKnowledgeServer> {
        val base = Files.createTempDirectory("src_tool_base_").toFile()
        val versionDir = File(base, "versions/release_v1")
        val sourceDir = File(versionDir, "source/pkg")
        sourceDir.mkdirs()
        File(sourceDir, "Foo.java").writeText(fooLines.joinToString("\n"))
        val dbFile = File(versionDir, "knowledge.db")
        val sdb = KnowledgeDatabase.forFile(dbFile)
        sdb.getConnection()
        sdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                "VALUES (?, 'JavaMethod', ?, 'source/pkg/Foo.java', 3, 5, ?, 'pkg/Foo.java', 'code')",
            "com.x.Foo#bar", "Foo#bar", "void bar() {}",
        )
        sdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                "VALUES (?, 'JavaMethod', ?, 'source/pkg/Foo.java', 6, 7, ?, 'pkg/Foo.java', 'code')",
            "com.x.Foo#dup", "Foo#dup", "void dup() {}",
        )
        sdb.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                "VALUES (?, 'JavaMethod', ?, 'source/pkg/Foo.java', 8, 9, ?, 'pkg/Foo.java', 'code')",
            "com.x.Baz#dup", "Baz#dup", "void dup() {}",
        )
        File(versionDir, "version_meta.json").writeText(
            """{"patchline":"release","buildNumber":1,"date":"2026-01-01","indexedAt":"2026-01-01T00:00:00Z"}"""
        )
        val config = KnowledgeConfig(indexPath = base.absolutePath)
        val svc = KnowledgeSearchService(sdb, CorpusIndexManager(config))
        val srv = HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to sdb), config = config)
        return sdb to srv
    }

    @Test
    fun `createServer registers get_hytale_source`() {
        val mcpServer = server.createServer()
        assertTrue("get_hytale_source" in mcpServer.tools.keys)
    }

    @Test
    fun `get_hytale_source reads the whole file`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("path", "pkg/Foo.java")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("totalLines: 10"), "whole-file should report 10 total lines; got: $text")
        for (line in fooLines) assertTrue(text.contains(line), "whole-file content should contain '$line'; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source reads a line range`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("path", "pkg/Foo.java")
            put("startLine", 3)
            put("endLine", 5)
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("content: \"line 3 of Foo\\nline 4 of Foo\\nline 5 of Foo\""), "range should return exactly lines 3-5; got: $text")
        assertFalse(text.contains("line 2 of Foo"), "range should exclude line 2; got: $text")
        assertFalse(text.contains("line 6 of Foo"), "range should exclude line 6; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source reads a single line`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("path", "pkg/Foo.java")
            put("startLine", 4)
            put("endLine", 4)
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("content: line 4 of Foo"), "single line should be line 4; got: $text")
        assertFalse(text.contains("line 3 of Foo"), "single line should exclude line 3; got: $text")
        assertFalse(text.contains("line 5 of Foo"), "single line should exclude line 5; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source normalizes a recorded file_path`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("path", "/root/.hyindex/knowledge/cache/staged/release/pkg/Foo.java")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("line 1 of Foo"), "normalized recorded path should read Foo.java; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source reads several files in batch`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            putJsonArray("paths") {
                add("pkg/Foo.java")
                add("cache/staged/release/pkg/Foo.java")
            }
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("files[2]"), "batch should return two file entries; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source rejects a traversal path`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("path", "../../../../../../etc/passwd")
        }))
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(result.isError == true, "traversal must be rejected; got: $text")
        assertFalse(text.contains("root:"), "must not leak file contents; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source returns a clear error for a missing file`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("path", "pkg/Missing.java")
        }))
        assertTrue(result.isError == true)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("not found", ignoreCase = true), "should report not found; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source resolves a className via the DB`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("className", "Foo")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("totalLines: 10"), "className resolution should read the whole Foo.java; got: $text")
        for (line in fooLines) assertTrue(text.contains(line), "className content should contain '$line'; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source returns a clear error for an unresolved className`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("className", "Nonexistent")
        }))
        assertTrue(result.isError == true)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("Nonexistent"), "error should name the unresolved class; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source resolves className plus methodName to the method line range`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("className", "Foo")
            put("methodName", "bar")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("content: \"line 3 of Foo\\nline 4 of Foo\\nline 5 of Foo\""), "method range should be lines 3-5; got: $text")
        assertFalse(text.contains("line 2 of Foo"), "method range should exclude line 2; got: $text")
        assertFalse(text.contains("line 6 of Foo"), "method range should exclude line 6; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source resolves a unique methodName without a className`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("methodName", "bar")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("content: \"line 3 of Foo\\nline 4 of Foo\\nline 5 of Foo\""), "unique method should resolve to lines 3-5; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source returns candidates for an ambiguous methodName`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("methodName", "dup")
        }))
        assertNull(result.isError, "ambiguity should not be a hard error")
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("com.x.Foo#dup"), "candidates should list Foo#dup; got: $text")
        assertTrue(text.contains("com.x.Baz#dup"), "candidates should list Baz#dup; got: $text")
        assertFalse(text.contains("line 6 of Foo"), "ambiguous method must not return content; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source returns a clear error for an unknown methodName`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("methodName", "noSuchMethod")
        }))
        assertTrue(result.isError == true)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("noSuchMethod"), "error should name the unresolved method; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source resolves a qualified methodName via display_name`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("methodName", "Foo#bar")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("content: \"line 3 of Foo\\nline 4 of Foo\\nline 5 of Foo\""), "qualified display_name should resolve to lines 3-5; got: $text")
        sdb.close()
    }

    @Test
    fun `get_hytale_source resolves a fully qualified methodName via id`() = runBlocking {
        val (sdb, srv) = sourceServer()
        val tool = srv.createServer().tools["get_hytale_source"]!!
        val result = tool.handler(callToolRequest("get_hytale_source", buildJsonObject {
            put("methodName", "com.x.Foo#bar")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("content: \"line 3 of Foo\\nline 4 of Foo\\nline 5 of Foo\""), "qualified id should resolve to lines 3-5; got: $text")
        sdb.close()
    }

    private fun recipeServer(): Pair<KnowledgeDatabase, HytaleKnowledgeServer> {
        val tempFile = Files.createTempFile("recipe_tool_", ".db").toFile()
        tempFile.deleteOnExit()
        val rdb = KnowledgeDatabase.forFile(tempFile)
        for ((id, name) in listOf(
            "gamedata:recipe:torch_recipe" to "TorchRecipe",
            "gamedata:item:wood_log" to "WoodLog",
            "gamedata:item:torch" to "Torch",
        )) {
            rdb.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, corpus) VALUES (?, 'GameData', ?, 'test.json', 'gamedata')",
                id, name,
            )
        }
        rdb.execute(
            "INSERT INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'REQUIRES_ITEM')",
            "gamedata:recipe:torch_recipe", "gamedata:item:wood_log",
        )
        rdb.execute(
            "INSERT INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'PRODUCES_ITEM')",
            "gamedata:recipe:torch_recipe", "gamedata:item:torch",
        )
        val svc = KnowledgeSearchService(rdb, CorpusIndexManager(KnowledgeConfig()))
        return rdb to HytaleKnowledgeServer(mapOf("release" to svc), mapOf("release" to rdb))
    }

    @Test
    fun `get_hytale_recipe direction requires returns only the ingredient`() = runBlocking {
        val (rdb, srv) = recipeServer()
        val tool = srv.createServer().tools["get_hytale_recipe"]!!
        val result = tool.handler(callToolRequest("get_hytale_recipe", buildJsonObject {
            put("nodeId", "gamedata:recipe:torch_recipe")
            put("direction", "requires")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("gamedata:item:wood_log"), "requires should return the ingredient; got: $text")
        assertFalse(text.contains("gamedata:item:torch"), "requires must not return the output; got: $text")
        rdb.close()
    }

    @Test
    fun `get_hytale_recipe direction produces returns only the output`() = runBlocking {
        val (rdb, srv) = recipeServer()
        val tool = srv.createServer().tools["get_hytale_recipe"]!!
        val result = tool.handler(callToolRequest("get_hytale_recipe", buildJsonObject {
            put("nodeId", "gamedata:recipe:torch_recipe")
            put("direction", "produces")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("gamedata:item:torch"), "produces should return the output; got: $text")
        assertFalse(text.contains("gamedata:item:wood_log"), "produces must not return the ingredient; got: $text")
        rdb.close()
    }

    @Test
    fun `get_hytale_recipe default direction returns both ingredient and output`() = runBlocking {
        val (rdb, srv) = recipeServer()
        val tool = srv.createServer().tools["get_hytale_recipe"]!!
        val result = tool.handler(callToolRequest("get_hytale_recipe", buildJsonObject {
            put("nodeId", "gamedata:recipe:torch_recipe")
        }))
        assertNull(result.isError)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue(text.contains("gamedata:item:wood_log"), "both should include the ingredient; got: $text")
        assertTrue(text.contains("gamedata:item:torch"), "both should include the output; got: $text")
        rdb.close()
    }

    private fun callToolRequest(
        name: String,
        arguments: JsonObject? = null,
    ): CallToolRequest = CallToolRequest(
        params = CallToolRequestParams(
            name = name,
            arguments = arguments,
        )
    )
}
