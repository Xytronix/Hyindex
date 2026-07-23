// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.search

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.embedding.Reranker
import com.hyindex.knowledge.core.index.CorpusIndexManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class KnowledgeSearchServiceTest {

    private lateinit var service: KnowledgeSearchService

    @BeforeEach
    fun setUp() {
        val tempFile = Files.createTempFile("search_service_test_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        val config = KnowledgeConfig()
        val indexManager = CorpusIndexManager(config)
        service = KnowledgeSearchService(db, indexManager)
    }


    @Test
    fun `detectGamedataIntent returns recipe and item for crafting queries`() {
        val result = service.detectGamedataIntent("how to craft a torch")
        assertNotNull(result)
        assertTrue(result!!.contains("recipe"))
        assertTrue(result.contains("item"))
    }

    @Test
    fun `detectGamedataIntent returns drop and npc for loot queries`() {
        val result = service.detectGamedataIntent("what drops from goblin warrior")
        assertNotNull(result)
        assertTrue(result!!.contains("drop"))
        assertTrue(result.contains("npc"))
    }

    @Test
    fun `detectGamedataIntent returns shop for merchant queries`() {
        val result = service.detectGamedataIntent("where can I buy leather")
        assertNotNull(result)
        assertTrue(result!!.contains("shop"))
    }

    @Test
    fun `detectGamedataIntent returns null for generic queries`() {
        val result = service.detectGamedataIntent("tell me about the world of Orbis")
        assertNull(result)
    }

    @Test
    fun `detectGamedataIntent returns objective for quest queries`() {
        val result = service.detectGamedataIntent("how to complete the goblin quest")
        assertNotNull(result)
        assertTrue(result!!.contains("objective"))
    }

    @Test
    fun `detectGamedataIntent accumulates multiple intents`() {
        val result = service.detectGamedataIntent("which hostile mobs spawn in which biomes")
        assertNotNull(result)
        assertTrue(result!!.contains("npc"))
        assertTrue(result.contains("npc_group"))
        assertTrue(result.contains("biome"))
    }


    private fun result(nodeId: String, score: Double, bridgedFrom: String? = null, bridgeEdgeType: String? = null): SearchResult {
        return SearchResult(
            nodeId = nodeId,
            displayName = nodeId.substringAfterLast(':'),
            snippet = "test",
            filePath = "test.json",
            lineStart = 0,
            score = score,
            source = ResultSource.VECTOR,
            bridgedFrom = bridgedFrom,
            bridgeEdgeType = bridgeEdgeType,
        )
    }

    @Test
    fun `deduplicateResults keeps higher-scored version`() {
        val results = listOf(
            result("node:A", 0.9),
            result("node:B", 0.8),
            result("node:A", 0.5),
        )
        val deduped = service.deduplicateResults(results)
        assertEquals(2, deduped.size)
        val nodeA = deduped.first { it.nodeId == "node:A" }
        assertEquals(0.9, nodeA.score)
    }

    @Test
    fun `deduplicateResults merges provenance from lower-scored duplicate`() {
        val results = listOf(
            result("node:X", 0.9),
            result("node:X", 0.4, bridgedFrom = "Torch", bridgeEdgeType = "IMPLEMENTED_BY"),
        )
        val deduped = service.deduplicateResults(results)
        assertEquals(1, deduped.size)
        val node = deduped[0]
        assertEquals(0.9, node.score)
        assertEquals("Torch", node.bridgedFrom)
        assertEquals("IMPLEMENTED_BY", node.bridgeEdgeType)
    }


    @Test
    fun `rankCrossCorpus orders mixed-corpus results by score descending`() {
        val results = listOf(
            result("client:NotoSansKR-Medium", 0.54),
            result("code:ParallelRangeTask", 0.81),
            result("gamedata:iron_ingot", 0.40),
        )
        val ranked = service.rankCrossCorpus(results)
        assertEquals(
            listOf("code:ParallelRangeTask", "client:NotoSansKR-Medium", "gamedata:iron_ingot"),
            ranked.map { it.nodeId },
        )
    }

    @Test
    fun `rankCrossCorpus dedupes by node then sorts by score`() {
        val results = listOf(
            result("node:A", 0.3),
            result("node:B", 0.9),
            result("node:A", 0.7),
        )
        val ranked = service.rankCrossCorpus(results)
        assertEquals(2, ranked.size)
        assertEquals(listOf("node:B", "node:A"), ranked.map { it.nodeId })
        assertEquals(0.7, ranked.first { it.nodeId == "node:A" }.score)
    }


    private fun serviceWithNode(id: String, displayName: String, content: String): Pair<KnowledgeDatabase, KnowledgeSearchService> {
        val tempFile = Files.createTempFile("search_byname_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        db.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, content, corpus) " +
                "VALUES (?, 'JavaMethod', ?, ?, 10, ?, 'code')",
            id, displayName, "decompiled/com/hypixel/hytale/component/Store.java", content,
        )
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        return db to svc
    }

    @Test
    fun `searchByName finds a method by its bare name`() {
        val (db, svc) = serviceWithNode(
            "com.hypixel.hytale.component.Store#assertThread", "Store#assertThread",
            "public void assertThread() { /* ... */ }",
        )
        val hits = svc.searchByName("assertThread")
        assertEquals(1, hits.size)
        assertEquals("com.hypixel.hytale.component.Store#assertThread", hits.first().nodeId)
        db.close()
    }

    @Test
    fun `searchCode surfaces an exact-name match without a vector index`() {
        val (db, svc) = serviceWithNode(
            "com.hypixel.hytale.component.Store#assertThread", "Store#assertThread",
            "public void assertThread() { /* ... */ }",
        )
        val results = svc.searchCode("assertThread", null, 5)
        assertTrue(results.isNotEmpty(), "exact-name query should surface the symbol")
        assertEquals("Store#assertThread", results.first().displayName)
        db.close()
    }

    private fun codeServiceWithNodes(vararg rows: Triple<String, String, String>): Pair<KnowledgeDatabase, KnowledgeSearchService> {
        val tempFile = Files.createTempFile("search_pathprefix_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        for ((id, displayName, filePath) in rows) {
            db.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, content, corpus) " +
                    "VALUES (?, 'JavaMethod', ?, ?, 10, 'public void run() {}', 'code')",
                id, displayName, filePath,
            )
        }
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        return db to svc
    }

    @Test
    fun `searchCode pathPrefix narrows results to matching file paths`() {
        val (db, svc) = codeServiceWithNodes(
            Triple("inv.Store#run", "Store#run", "decompiled/server/core/inventory/Store.java"),
            Triple("net.Socket#run", "Socket#run", "decompiled/server/net/Socket.java"),
        )
        val results = svc.searchCode("run", null, 10, pathPrefix = "server/core/inventory")
        assertEquals(1, results.size)
        assertEquals("Store#run", results.first().displayName)
        db.close()
    }

    @Test
    fun `searchCode pathPrefix matches against nodeId too`() {
        val (db, svc) = codeServiceWithNodes(
            Triple("com.hypixel.inventory.Store#run", "Store#run", "decompiled/a/Store.java"),
            Triple("com.hypixel.net.Socket#run", "Socket#run", "decompiled/b/Socket.java"),
        )
        val results = svc.searchCode("run", null, 10, pathPrefix = "inventory")
        assertEquals(1, results.size)
        assertEquals("Store#run", results.first().displayName)
        db.close()
    }

    @Test
    fun `searchCode without pathPrefix returns all matches`() {
        val (db, svc) = codeServiceWithNodes(
            Triple("inv.Store#run", "Store#run", "decompiled/server/core/inventory/Store.java"),
            Triple("net.Socket#run", "Socket#run", "decompiled/server/net/Socket.java"),
        )
        val results = svc.searchCode("run", null, 10)
        assertEquals(2, results.size)
        db.close()
    }

    @Test
    fun `searchCode pathPrefix surfaces a match ranked beyond limit via the widened pool`() {
        val tempFile = Files.createTempFile("search_widen_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        for (i in 0 until 8) {
            db.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, content, corpus) " +
                    "VALUES (?, 'JavaMethod', ?, ?, 10, 'public void run() {}', 'code')",
                "net.S$i#run", "S$i#run", "decompiled/server/net/S$i.java",
            )
        }
        db.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, content, corpus) " +
                "VALUES (?, 'JavaMethod', ?, ?, 10, 'public void run() {}', 'code')",
            "inv.Target#run", "Target#run", "decompiled/server/core/inventory/Target.java",
        )
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        val results = svc.searchCode("run", null, 3, pathPrefix = "server/core/inventory")
        assertEquals(1, results.size)
        assertEquals("Target#run", results.first().displayName)
        db.close()
    }

    @Test
    fun `searchCode classExact keeps only the exact class name and drops a superstring class`() {
        val (db, svc) = codeServiceWithNodes(
            Triple("com.x.ItemContainer#run", "ItemContainer#run", "decompiled/a/ItemContainer.java"),
            Triple("com.x.FetchedItemContainer#run", "FetchedItemContainer#run", "decompiled/a/FetchedItemContainer.java"),
        )
        val results = svc.searchCode("run", "ItemContainer", 10, classExact = true)
        assertEquals(1, results.size)
        assertEquals("ItemContainer#run", results.first().displayName)
        db.close()
    }

    @Test
    fun `searchCode classExact false keeps the superstring class via substring`() {
        val (db, svc) = codeServiceWithNodes(
            Triple("com.x.ItemContainer#run", "ItemContainer#run", "decompiled/a/ItemContainer.java"),
            Triple("com.x.FetchedItemContainer#run", "FetchedItemContainer#run", "decompiled/a/FetchedItemContainer.java"),
        )
        val results = svc.searchCode("run", "ItemContainer", 10, classExact = false)
        assertEquals(2, results.size)
        db.close()
    }

    @Test
    fun `searchCode classExact matches a fully qualified class name`() {
        val (db, svc) = codeServiceWithNodes(
            Triple("com.x.ItemContainer#run", "ItemContainer#run", "decompiled/a/ItemContainer.java"),
            Triple("com.x.FetchedItemContainer#run", "FetchedItemContainer#run", "decompiled/a/FetchedItemContainer.java"),
        )
        val results = svc.searchCode("run", "com.x.ItemContainer", 10, classExact = true)
        assertEquals(1, results.size)
        assertEquals("ItemContainer#run", results.first().displayName)
        db.close()
    }

    @Test
    fun `searchCode pathExact keeps only the exact filename and drops a superstring file`() {
        val (db, svc) = codeServiceWithNodes(
            Triple("com.x.ItemContainer#run", "ItemContainer#run", "decompiled/a/ItemContainer.java"),
            Triple("com.x.CombinedItemContainer#run", "CombinedItemContainer#run", "decompiled/a/CombinedItemContainer.java"),
        )
        val results = svc.searchCode("run", null, 10, pathPrefix = "ItemContainer.java", pathExact = true)
        assertEquals(1, results.size)
        assertEquals("ItemContainer#run", results.first().displayName)
        db.close()
    }

    @Test
    fun `searchCode pathExact false keeps the superstring file via substring`() {
        val (db, svc) = codeServiceWithNodes(
            Triple("com.x.ItemContainer#run", "ItemContainer#run", "decompiled/a/ItemContainer.java"),
            Triple("com.x.CombinedItemContainer#run", "CombinedItemContainer#run", "decompiled/a/CombinedItemContainer.java"),
        )
        val results = svc.searchCode("run", null, 10, pathPrefix = "ItemContainer.java", pathExact = false)
        assertEquals(2, results.size)
        db.close()
    }

    @Test
    fun `searchCode pathExact matches a complete path component`() {
        val (db, svc) = codeServiceWithNodes(
            Triple("com.x.Store#run", "Store#run", "decompiled/server/core/inventory/Store.java"),
            Triple("net.Socket#run", "Socket#run", "decompiled/server/net/Socket.java"),
        )
        val results = svc.searchCode("run", null, 10, pathPrefix = "inventory", pathExact = true)
        assertEquals(1, results.size)
        assertEquals("Store#run", results.first().displayName)
        db.close()
    }

    @Test
    fun `filterByDataTypes keeps results whose dataType is any member of the set`() {
        val results = listOf(
            result("gamedata:iron_sword", 0.9).copy(dataType = "item"),
            result("gamedata:torch_recipe", 0.8).copy(dataType = "recipe"),
            result("gamedata:goblin", 0.7).copy(dataType = "npc"),
        )
        val filtered = service.filterByDataTypes(results, setOf("item", "recipe"))
        assertEquals(setOf("gamedata:iron_sword", "gamedata:torch_recipe"), filtered.map { it.nodeId }.toSet())
    }

    @Test
    fun `filterByDataTypes with a comma-joined single value matches nothing`() {
        val results = listOf(
            result("gamedata:iron_sword", 0.9).copy(dataType = "item"),
            result("gamedata:torch_recipe", 0.8).copy(dataType = "recipe"),
        )
        assertTrue(service.filterByDataTypes(results, setOf("item,recipe")).isEmpty())
    }

    @Test
    fun `filterByDataTypes returns all results when the set is null`() {
        val results = listOf(
            result("gamedata:iron_sword", 0.9).copy(dataType = "item"),
            result("gamedata:torch_recipe", 0.8).copy(dataType = "recipe"),
        )
        assertEquals(2, service.filterByDataTypes(results, null).size)
    }

    @Test
    fun `filterBySnippet narrows to results whose snippet contains the tag`() {
        val results = listOf(
            snippetResult("gamedata:iron_sword", 0.9, "Type:Weapon\nQuality:Common"),
            snippetResult("gamedata:apple", 0.8, "Type:Food\nQuality:Common"),
        )
        val filtered = service.filterBySnippet(results, "Type:Weapon")
        assertEquals(1, filtered.size)
        assertEquals("gamedata:iron_sword", filtered.first().nodeId)
    }

    @Test
    fun `filterBySnippet is case-insensitive`() {
        val results = listOf(snippetResult("gamedata:ring", 0.9, "Quality:Uncommon"))
        assertEquals(1, service.filterBySnippet(results, "uncommon").size)
    }

    @Test
    fun `filterBySnippet returns all results when filter is null or blank`() {
        val results = listOf(
            snippetResult("gamedata:a", 0.9, "Type:Weapon"),
            snippetResult("gamedata:b", 0.8, "Type:Food"),
        )
        assertEquals(2, service.filterBySnippet(results, null).size)
        assertEquals(2, service.filterBySnippet(results, "  ").size)
    }

    @Test
    fun `applyCorpusPenalty down-weights blog docs below an equal-scored repo doc`() {
        val mapped = listOf(
            result("blog:2024/01/patch-notes#00-overview", 0.80),
            result("repo:HytaleServer/builtin/Guide.md#00-overview", 0.80),
        )
        val adjusted = service.applyCorpusPenalty(mapped, com.hyindex.knowledge.core.db.Corpus.DOCS)
        val repo = adjusted.first { it.nodeId.startsWith("repo:") }
        val blog = adjusted.first { it.nodeId.startsWith("blog:") }
        assertTrue(blog.score < repo.score, "blog penalized below repo")
        assertEquals("repo:HytaleServer/builtin/Guide.md#00-overview", adjusted.first().nodeId)
    }

    @Test
    fun `applyCorpusPenalty leaves CODE corpus untouched`() {
        val mapped = listOf(result("blog:x#00-overview", 0.80))
        val adjusted = service.applyCorpusPenalty(mapped, com.hyindex.knowledge.core.db.Corpus.CODE)
        assertEquals(0.80, adjusted.first().score)
    }

    @Test
    fun `applyCorpusPenalty down-weights gamedata world nodes below a real item`() {
        val cave = result("gamedata:cave_deep_01", 0.62).copy(dataType = "cave")
        val item = result("gamedata:iron_sword", 0.58).copy(dataType = "item")
        val adjusted = service.applyCorpusPenalty(listOf(cave, item), com.hyindex.knowledge.core.db.Corpus.GAMEDATA)
        assertEquals("gamedata:iron_sword", adjusted.first().nodeId)
        assertTrue(adjusted.first { it.nodeId == "gamedata:cave_deep_01" }.score < cave.score)
    }

    @Test
    fun `blank query returns empty without hitting the embedding provider`() {
        assertTrue(service.search("").isEmpty())
        assertTrue(service.search("   ").isEmpty())
        assertTrue(service.searchCorpus("", com.hyindex.knowledge.core.db.Corpus.GAMEDATA).isEmpty())
        assertTrue(service.searchCorpus("  ", com.hyindex.knowledge.core.db.Corpus.DOCS).isEmpty())
    }

    @Test
    fun `findByName preserves the node's real corpus`() {
        val tempFile = Files.createTempFile("graph_corpus_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        db.execute(
            "INSERT INTO nodes (id, node_type, display_name, corpus, data_type, content) VALUES (?, 'GameData', ?, 'gamedata', 'npc', ?)",
            "gamedata:Server:NPC:Kweebec.json", "Kweebec", "npc kweebec",
        )
        val hits = GraphTraversal(db).findByName("Kweebec")
        assertEquals(1, hits.size)
        assertEquals("gamedata", hits.first().corpus)
        assertEquals("npc", hits.first().dataType)
        db.close()
    }

    private class FakeReranker(private val order: List<Pair<Int, Double>>) : Reranker {
        var called = false
        override fun rerank(query: String, documents: List<String>): List<Pair<Int, Double>> {
            called = true
            return order
        }
    }

    private fun rerankService(config: KnowledgeConfig, reranker: Reranker): KnowledgeSearchService {
        val tempFile = Files.createTempFile("rerank_service_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        return KnowledgeSearchService(db, CorpusIndexManager(config), config = config, reranker = reranker)
    }

    private fun snippetResult(nodeId: String, score: Double, snippet: String): SearchResult =
        result(nodeId, score).copy(snippet = snippet)

    @Test
    fun `maybeRerank returns results unchanged when disabled`() {
        val fake = FakeReranker(listOf(1 to 0.9, 0 to 0.8))
        val svc = rerankService(KnowledgeConfig(rerankerEnabled = false), fake)
        val input = listOf(snippetResult("node:A", 0.9, "a"), snippetResult("node:B", 0.8, "b"))
        val out = svc.maybeRerank("q", input)
        assertEquals(listOf("node:A", "node:B"), out.map { it.nodeId })
        assertFalse(fake.called)
    }

    @Test
    fun `maybeRerank reorders per the reranker indices when enabled`() {
        val fake = FakeReranker(listOf(2 to 0.95, 0 to 0.60, 1 to 0.30))
        val svc = rerankService(KnowledgeConfig(rerankerEnabled = true), fake)
        val input = listOf(
            snippetResult("node:A", 0.9, "a"),
            snippetResult("node:B", 0.8, "b"),
            snippetResult("node:C", 0.7, "c"),
        )
        val out = svc.maybeRerank("q", input)
        assertEquals(listOf("node:C", "node:A", "node:B"), out.map { it.nodeId })
        assertTrue(out[0].score > out[1].score)
        assertTrue(out[1].score > out[2].score)
    }

    @Test
    fun `maybeRerank returns results unchanged when reranker returns empty`() {
        val fake = FakeReranker(emptyList())
        val svc = rerankService(KnowledgeConfig(rerankerEnabled = true), fake)
        val input = listOf(snippetResult("node:A", 0.9, "a"), snippetResult("node:B", 0.8, "b"))
        val out = svc.maybeRerank("q", input)
        assertEquals(listOf("node:A", "node:B"), out.map { it.nodeId })
        assertTrue(fake.called)
    }

    @Test
    fun `maybeRerank returns single result unchanged`() {
        val fake = FakeReranker(listOf(0 to 0.9))
        val svc = rerankService(KnowledgeConfig(rerankerEnabled = true), fake)
        val input = listOf(snippetResult("node:A", 0.9, "a"))
        val out = svc.maybeRerank("q", input)
        assertEquals(listOf("node:A"), out.map { it.nodeId })
        assertFalse(fake.called)
    }

    @Test
    fun `maybeRerank reranks only top-N and appends the tail`() {
        val fake = FakeReranker(listOf(1 to 0.9, 0 to 0.7))
        val config = KnowledgeConfig(rerankerEnabled = true, rerankerTopN = 2)
        val svc = rerankService(config, fake)
        val input = listOf(
            snippetResult("node:A", 0.9, "a"),
            snippetResult("node:B", 0.8, "b"),
            snippetResult("node:C", 0.7, "c"),
        )
        val out = svc.maybeRerank("q", input)
        assertEquals(listOf("node:B", "node:A", "node:C"), out.map { it.nodeId })
    }

    @Test
    fun `maybeRerank carries real relevance scores while keeping score as the rank ramp`() {
        val fake = FakeReranker(listOf(2 to 0.95, 0 to 0.60, 1 to 0.30))
        val svc = rerankService(KnowledgeConfig(rerankerEnabled = true), fake)
        val input = listOf(
            snippetResult("node:A", 0.9, "a"),
            snippetResult("node:B", 0.8, "b"),
            snippetResult("node:C", 0.7, "c"),
        )
        val out = svc.maybeRerank("q", input)
        assertEquals(listOf("node:C", "node:A", "node:B"), out.map { it.nodeId })
        assertEquals(listOf(0.95, 0.60, 0.30), out.map { it.relevanceScore })
        assertTrue(out[0].score > out[1].score)
        assertTrue(out[1].score > out[2].score)
    }

    @Test
    fun `maybeRerank leaves the tail relevanceScore null`() {
        val fake = FakeReranker(listOf(1 to 0.9, 0 to 0.7))
        val config = KnowledgeConfig(rerankerEnabled = true, rerankerTopN = 2)
        val svc = rerankService(config, fake)
        val input = listOf(
            snippetResult("node:A", 0.9, "a"),
            snippetResult("node:B", 0.8, "b"),
            snippetResult("node:C", 0.7, "c"),
        )
        val out = svc.maybeRerank("q", input)
        assertEquals(0.9, out.first { it.nodeId == "node:B" }.relevanceScore)
        assertEquals(0.7, out.first { it.nodeId == "node:A" }.relevanceScore)
        assertNull(out.first { it.nodeId == "node:C" }.relevanceScore)
    }

    @Test
    fun `maybeRerank leaves relevanceScore null when disabled`() {
        val fake = FakeReranker(listOf(1 to 0.9, 0 to 0.8))
        val svc = rerankService(KnowledgeConfig(rerankerEnabled = false), fake)
        val input = listOf(snippetResult("node:A", 0.9, "a"), snippetResult("node:B", 0.8, "b"))
        val out = svc.maybeRerank("q", input)
        assertNull(out[0].relevanceScore)
        assertNull(out[1].relevanceScore)
    }

    @Test
    fun `maybeRerank leaves relevanceScore null when reranker returns empty`() {
        val fake = FakeReranker(emptyList())
        val svc = rerankService(KnowledgeConfig(rerankerEnabled = true), fake)
        val input = listOf(snippetResult("node:A", 0.9, "a"), snippetResult("node:B", 0.8, "b"))
        val out = svc.maybeRerank("q", input)
        assertNull(out[0].relevanceScore)
        assertNull(out[1].relevanceScore)
    }

    @Test
    fun `rrfFuse normalizes fused scores to a 0 to 1 scale and orders descending`() {
        val a = listOf(result("node:B", 0.0), result("node:A", 0.0), result("node:C", 0.0))
        val b = listOf(result("node:B", 0.0), result("node:A", 0.0))
        val fused = service.rrfFuse(listOf(a, b), 60)
        assertEquals(3, fused.size)
        assertEquals("node:B", fused[0].nodeId)
        assertEquals(1.0, fused[0].score, 1e-9)
        assertTrue(fused.all { it.score in 0.0..1.0 }, "fused scores must be normalized to [0,1]")
        assertTrue(fused[0].score >= fused[1].score && fused[1].score >= fused[2].score, "descending order")
    }

    @Test
    fun `rrfFuse lifts an exact lexical match above a vector low-rank result`() {
        val vector = (0 until 9).map { result("node:V$it", 0.9 - it * 0.01) } + result("node:Exact", 0.30)
        val lexical = listOf(result("node:Exact", 0.0))
        val fused = service.rrfFuse(listOf(vector, lexical), 60)
        assertEquals("node:Exact", fused[0].nodeId, "lexical #1 should win after fusion")
    }

    @Test
    fun `sanitizeFtsQuery quotes terms OR-joined and strips operators`() {
        val out = service.sanitizeFtsQuery("\"player\" * health: id")
        assertEquals("\"player\" OR \"health\" OR \"id\"", out)
        assertFalse(out.contains("*"))
        assertFalse(out.contains(":"))
    }

    @Test
    fun `sanitizeFtsQuery leaves a single token unchanged aside from quoting`() {
        assertEquals("\"inventory\"", service.sanitizeFtsQuery("inventory"))
    }

    @Test
    fun `sanitizeFtsQuery returns blank for operator-only query`() {
        assertEquals("", service.sanitizeFtsQuery("\" * : - ^ ( )"))
    }

    @Test
    fun `searchCode with hybrid disabled returns vector-only dedup behavior`() {
        val (db, svc) = serviceWithNode(
            "com.hypixel.hytale.component.Store#assertThread", "Store#assertThread",
            "public void assertThread() {}",
        )
        val disabled = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig(hybridEnabled = false)), config = KnowledgeConfig(hybridEnabled = false))
        val results = disabled.searchCode("assertThread", null, 5)
        assertEquals(1, results.size)
        assertEquals("Store#assertThread", results.first().displayName)
        db.close()
    }

    @Test
    fun `lexicalSearch finds a code node via camelCase split body`() {
        val tempFile = Files.createTempFile("lexical_code_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        db.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, content, corpus) " +
                "VALUES (?, 'JavaMethod', ?, ?, 10, ?, 'code')",
            "code:Foo#registerPlayerEvent", "Foo#registerPlayerEvent",
            "decompiled/Foo.java", "void registerPlayerEvent() {}",
        )
        com.hyindex.knowledge.index.FtsTokenizer.populate(
            db, "code",
            listOf(com.hyindex.knowledge.index.FtsTokenizer.FtsRow(
                "code:Foo#registerPlayerEvent", "Foo#registerPlayerEvent", "void registerPlayerEvent() {}",
            )),
            splitBody = true,
        )
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        val hits = svc.lexicalSearch("player", com.hyindex.knowledge.core.db.Corpus.CODE, 10)
        assertEquals(1, hits.size)
        assertEquals("code:Foo#registerPlayerEvent", hits.first().nodeId)
        db.close()
    }

    @Test
    fun `lexicalSearch ranks a name-only match above a body-only match via column weights`() {
        val tempFile = Files.createTempFile("lexical_weight_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        fun insert(id: String) = db.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, content, corpus) " +
                "VALUES (?, 'JavaMethod', ?, 'f.java', 1, 'c', 'gamedata')",
            id, id,
        )
        insert("gamedata:in_name")
        insert("gamedata:in_body")
        com.hyindex.knowledge.index.FtsTokenizer.populate(
            db, "gamedata",
            listOf(
                com.hyindex.knowledge.index.FtsTokenizer.FtsRow("gamedata:in_name", "torch", "irrelevant text"),
                com.hyindex.knowledge.index.FtsTokenizer.FtsRow("gamedata:in_body", "irrelevant", "a torch here"),
            ),
            splitBody = false,
        )
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        val hits = svc.lexicalSearch("torch", com.hyindex.knowledge.core.db.Corpus.GAMEDATA, 10)
        assertEquals(2, hits.size)
        assertEquals("gamedata:in_name", hits.first().nodeId, "name boost must rank name match first")
        db.close()
    }

    @Test
    fun `lexicalSearch returns any-term match for a multi-word query`() {
        val tempFile = Files.createTempFile("lexical_or_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        db.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, content, corpus) " +
                "VALUES (?, 'DocsPage', ?, 'd.md', 1, 'c', 'docs')",
            "docs:cmd", "docs:cmd",
        )
        com.hyindex.knowledge.index.FtsTokenizer.populate(
            db, "docs",
            listOf(com.hyindex.knowledge.index.FtsTokenizer.FtsRow("docs:cmd", "Commands", "how to register a command")),
            splitBody = false,
        )
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        val hits = svc.lexicalSearch("inventory command", com.hyindex.knowledge.core.db.Corpus.DOCS, 10)
        assertEquals(1, hits.size, "OR-join surfaces a node matching only one of the terms")
        assertEquals("docs:cmd", hits.first().nodeId)
        db.close()
    }

    @Test
    fun `sortByRecency orders by published date descending with nulls last`() {
        val tempFile = Files.createTempFile("recency_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        fun insert(id: String, date: String?) = db.execute(
            "INSERT INTO nodes (id, node_type, display_name, content, corpus, published_date) " +
                "VALUES (?, 'DocsPage', ?, 'body', 'docs', ?)",
            id, id, date,
        )
        insert("docs:old", "2024-01-01")
        insert("docs:new", "2026-05-05")
        insert("docs:undated", null)
        insert("docs:mid", "2025-06-15")
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        val input = listOf(
            result("docs:undated", 0.9),
            result("docs:old", 0.8),
            result("docs:new", 0.7),
            result("docs:mid", 0.6),
        )
        val sorted = svc.sortByRecency(input)
        assertEquals(listOf("docs:new", "docs:mid", "docs:old", "docs:undated"), sorted.map { it.nodeId })
        db.close()
    }

    private fun classMethodService(): Pair<KnowledgeDatabase, KnowledgeSearchService> {
        val tempFile = Files.createTempFile("class_methods_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        fun insert(id: String, displayName: String, lineStart: Int, lineEnd: Int, content: String, owningFile: String) =
            db.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                    "VALUES (?, 'JavaMethod', ?, ?, ?, ?, ?, ?, 'code')",
                id, displayName, "decompiled/com/x/Store.java", lineStart, lineEnd, content, owningFile,
            )
        insert("com.x.Store#second", "Store#second", 30, 35, "public int second() { return 2; }", "com/x/Store.java")
        insert("com.x.Store#first", "Store#first", 10, 15, "public int first() { return 1; }", "com/x/Store.java")
        insert("com.x.Other#run", "Other#run", 5, 8, "public void run() {}", "com/x/Other.java")
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        return db to svc
    }

    @Test
    fun `getClassMethods lists all methods ordered by line for a simple class name`() {
        val (db, svc) = classMethodService()
        val methods = svc.getClassMethods("Store")
        assertEquals(listOf("Store#first", "Store#second"), methods.map { it.name })
        assertEquals(10, methods.first().lineStart)
        assertEquals(15, methods.first().lineEnd)
        assertTrue(methods.first().content.contains("first"))
        db.close()
    }

    @Test
    fun `getClassMethods resolves a fully qualified class name`() {
        val (db, svc) = classMethodService()
        val methods = svc.getClassMethods("com.x.Store")
        assertEquals(listOf("Store#first", "Store#second"), methods.map { it.name })
        db.close()
    }

    @Test
    fun `getClassMethods returns empty for an unknown class`() {
        val (db, svc) = classMethodService()
        assertTrue(svc.getClassMethods("Nonexistent").isEmpty())
        db.close()
    }

    @Test
    fun `getClassMethods does not surface a JavaFile or JavaType fallback node as a method`() {
        val tempFile = Files.createTempFile("fallback_methods_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        fun fallback(nodeType: String, fqcn: String, owningFile: String) =
            db.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                    "VALUES (?, ?, ?, ?, 1, 200, ?, ?, 'code')",
                fqcn, nodeType, fqcn.substringAfterLast('.'), "decompiled/$owningFile",
                "package com.x;\npublic class ${fqcn.substringAfterLast('.')} {}", owningFile,
            )
        fallback("JavaFile", "com.x.Unparseable", "com/x/Unparseable.java")
        fallback("JavaType", "com.x.Markerless", "com/x/Markerless.java")
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))

        assertTrue(svc.getClassMethods("Unparseable").isEmpty(), "an unparseable JavaFile fallback must not appear as a method")
        assertTrue(svc.getClassMethods("Markerless").isEmpty(), "a method-less JavaType fallback must not appear as a method")
        db.close()
    }

    private fun inheritanceService(): Pair<KnowledgeDatabase, KnowledgeSearchService> {
        val tempFile = Files.createTempFile("inherit_methods_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        fun method(id: String, displayName: String, lineStart: Int, lineEnd: Int, content: String, owningFile: String) =
            db.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, line_end, content, owning_file, corpus) " +
                    "VALUES (?, 'JavaMethod', ?, ?, ?, ?, ?, ?, 'code')",
                id, displayName, "decompiled/$owningFile", lineStart, lineEnd, content, owningFile,
            )
        fun classNode(fqcn: String, owningFile: String) =
            db.execute(
                "INSERT INTO nodes (id, node_type, display_name, owning_file, corpus) VALUES (?, 'JavaClass', ?, ?, 'code')",
                "class:$fqcn", fqcn.substringAfterLast('.'), owningFile,
            )
        method("com.x.Base#build", "Base#build", 5, 7, "public void build() {}", "com/x/Base.java")
        method("com.x.Base#reset", "Base#reset", 9, 11, "public void reset() {}", "com/x/Base.java")
        classNode("com.x.Base", "com/x/Base.java")
        method("com.x.Sub#build", "Sub#build", 4, 6, "public void build() {}", "com/x/Sub.java")
        method("com.x.Sub#run", "Sub#run", 8, 10, "public void run() {}", "com/x/Sub.java")
        method("com.x.Sub#process", "Sub#process", 12, 14, "public void process(int a) {}", "com/x/Sub.java")
        method("com.x.Sub#process~2", "Sub#process", 16, 18, "public void process(String a) {}", "com/x/Sub.java")
        classNode("com.x.Sub", "com/x/Sub.java")
        db.execute(
            "INSERT INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'EXTENDS')",
            "class:com.x.Sub", "class:com.x.Base",
        )
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        return db to svc
    }

    @Test
    fun `getClassMethods inlines inherited methods marked by their declaring type`() {
        val (db, svc) = inheritanceService()
        val methods = svc.getClassMethods("Sub")
        val run = methods.single { it.name == "Sub#run" }
        assertFalse(run.inherited, "own method must not be marked inherited")
        val reset = methods.single { it.name == "Base#reset" }
        assertTrue(reset.inherited, "a supertype method must be marked inherited")
        assertEquals("Base", reset.declaringType, "inherited method must carry its declaring type")
        db.close()
    }

    @Test
    fun `getClassMethods shadows an overridden method with the subclass declaration`() {
        val (db, svc) = inheritanceService()
        val methods = svc.getClassMethods("Sub")
        assertEquals(1, methods.count { it.name.endsWith("#build") }, "an overridden method must appear once, not twice")
        assertTrue(methods.any { it.name == "Sub#build" && !it.inherited }, "the subclass override wins")
        assertFalse(methods.any { it.name == "Base#build" }, "the shadowed supertype declaration is dropped")
        db.close()
    }

    @Test
    fun `getClassMethods keeps distinct overloads of the same name`() {
        val (db, svc) = inheritanceService()
        val methods = svc.getClassMethods("Sub")
        assertEquals(2, methods.count { it.name == "Sub#process" }, "overloads differ by parameters and must both survive")
        db.close()
    }

    private fun diversifyService(config: KnowledgeConfig): KnowledgeSearchService {
        val tempFile = Files.createTempFile("diversify_service_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        return KnowledgeSearchService(db, CorpusIndexManager(config), config = config)
    }

    private fun codeResult(nodeId: String, score: Double, snippet: String): SearchResult =
        SearchResult(
            nodeId = nodeId,
            displayName = nodeId,
            snippet = snippet,
            filePath = "X.java",
            lineStart = 0,
            score = score,
            source = ResultSource.VECTOR,
        )

    @Test
    fun `diversifyResults demotes a same-simple-name duplicate below an untouched result`() {
        val svc = diversifyService(KnowledgeConfig(nearDupPenalty = 0.5))
        val input = listOf(
            codeResult("A#swapItems", 1.0, "alpha"),
            codeResult("B#swapItems", 0.95, "beta"),
            codeResult("C#other", 0.9, "gamma"),
        )
        val out = svc.diversifyResults(input)
        assertEquals(listOf("A#swapItems", "C#other", "B#swapItems"), out.map { it.nodeId })
        assertEquals(1.0, out.first { it.nodeId == "A#swapItems" }.score)
        assertEquals(0.9, out.first { it.nodeId == "C#other" }.score)
        assertEquals(0.95 * 0.5, out.first { it.nodeId == "B#swapItems" }.score, 1e-9)
    }

    @Test
    fun `diversifyResults demotes a near-identical snippet via token Jaccard`() {
        val svc = diversifyService(KnowledgeConfig(nearDupPenalty = 0.5, nearDupJaccard = 0.8))
        val body = "return this.items.swap(from, to, count, flag)"
        val input = listOf(
            codeResult("A#x", 1.0, body),
            codeResult("B#y", 0.9, body),
        )
        val out = svc.diversifyResults(input)
        assertEquals("A#x", out.first().nodeId)
        assertEquals(0.9 * 0.5, out.first { it.nodeId == "B#y" }.score, 1e-9)
    }

    @Test
    fun `diversifyResults is a no-op when penalty is one`() {
        val svc = diversifyService(KnowledgeConfig(nearDupPenalty = 1.0))
        val input = listOf(
            codeResult("A#swapItems", 1.0, "alpha"),
            codeResult("B#swapItems", 0.95, "alpha"),
        )
        val out = svc.diversifyResults(input)
        assertEquals(input.map { it.nodeId }, out.map { it.nodeId })
        assertEquals(0.95, out.first { it.nodeId == "B#swapItems" }.score)
    }

    @Test
    fun `diversifyResults name gate catches a thin delegate with low snippet overlap`() {
        val svc = diversifyService(KnowledgeConfig(nearDupPenalty = 0.5, nearDupJaccard = 0.8))
        val input = listOf(
            codeResult("ItemContainer#swapItems", 1.0, "validate(); reorder(); persist(); notifyListeners();"),
            codeResult("FetchedItemContainer#swapItems", 0.95, "return d.swap(a, b);"),
        )
        val out = svc.diversifyResults(input)
        assertEquals("ItemContainer#swapItems", out.first().nodeId)
        assertEquals(0.95 * 0.5, out.first { it.nodeId == "FetchedItemContainer#swapItems" }.score, 1e-9)
    }

    private fun facetService(): Pair<KnowledgeDatabase, KnowledgeSearchService> {
        val tempFile = Files.createTempFile("facet_filter_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        fun insert(id: String, displayName: String, metadata: String?) = db.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, content, corpus, metadata) " +
                "VALUES (?, 'JavaMethod', ?, 'decompiled/X.java', 10, 'void run() {}', 'code', ?)",
            id, displayName, metadata,
        )
        insert("com.x.A#run", "A#run", "{\"visibility\":\"public\"}")
        insert("com.x.B#run", "B#run", "{\"visibility\":\"private\"}")
        insert("com.x.C#run", "C#run", null)
        return db to KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
    }

    @Test
    fun `searchCode visibility filter drops a private method that has metadata`() {
        val (db, svc) = facetService()
        val results = svc.searchCode("run", null, 10, visibility = "public")
        val ids = results.map { it.nodeId }.toSet()
        assertFalse("com.x.B#run" in ids, "a private method with metadata must be excluded")
        assertTrue("com.x.A#run" in ids, "the public method must remain")
        db.close()
    }

    @Test
    fun `searchCode visibility filter keeps rows without metadata`() {
        val (db, svc) = facetService()
        val results = svc.searchCode("run", null, 10, visibility = "public")
        assertTrue("com.x.C#run" in results.map { it.nodeId }, "absent-metadata rows must not be wrongly dropped pre-reindex")
        db.close()
    }

    @Test
    fun `searchCode annotation filter drops a method whose metadata annotations exclude it`() {
        val tempFile = Files.createTempFile("facet_anno_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        fun insert(id: String, displayName: String, metadata: String?) = db.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, content, corpus, metadata) " +
                "VALUES (?, 'JavaMethod', ?, 'decompiled/X.java', 10, 'void run() {}', 'code', ?)",
            id, displayName, metadata,
        )
        insert("com.x.A#run", "A#run", "{\"annotations\":[\"Override\"]}")
        insert("com.x.B#run", "B#run", "{\"annotations\":[\"Deprecated\"]}")
        insert("com.x.C#run", "C#run", null)
        val svc = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        val results = svc.searchCode("run", null, 10, annotation = "Override")
        val ids = results.map { it.nodeId }.toSet()
        assertTrue("com.x.A#run" in ids, "the annotated method must remain")
        assertFalse("com.x.B#run" in ids, "a method with metadata lacking the annotation must be excluded")
        assertTrue("com.x.C#run" in ids, "absent-metadata rows must not be wrongly dropped")
        db.close()
    }

    private fun delegateService(config: KnowledgeConfig): Pair<KnowledgeDatabase, KnowledgeSearchService> {
        val tempFile = Files.createTempFile("delegate_penalty_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        fun insert(id: String, displayName: String, metadata: String?) = db.execute(
            "INSERT INTO nodes (id, node_type, display_name, file_path, line_start, content, corpus, metadata) " +
                "VALUES (?, 'JavaMethod', ?, 'X.java', 10, 'public void swapItems() {}', 'code', ?)",
            id, displayName, metadata,
        )
        insert("com.x.ItemContainer#swapItems", "ItemContainer#swapItems", "{\"visibility\":\"public\",\"thin\":false}")
        insert("com.x.FetchedItemContainer#swapItems", "FetchedItemContainer#swapItems", "{\"visibility\":\"public\",\"thin\":true}")
        return db to KnowledgeSearchService(db, CorpusIndexManager(config), config = config)
    }

    @Test
    fun `searchCode demotes a thin delegate below a real impl on the hybrid path when penalty is set`() {
        val (penalizedDb, penalized) = delegateService(KnowledgeConfig(delegatePenalty = 0.5, nearDupPenalty = 1.0))
        val (baselineDb, baseline) = delegateService(KnowledgeConfig(delegatePenalty = 1.0, nearDupPenalty = 1.0))
        val penalizedResults = penalized.searchCode("swapItems", null, 10)
        val baselineResults = baseline.searchCode("swapItems", null, 10)

        val penalizedThin = penalizedResults.first { it.nodeId == "com.x.FetchedItemContainer#swapItems" }
        val baselineThin = baselineResults.first { it.nodeId == "com.x.FetchedItemContainer#swapItems" }
        val penalizedReal = penalizedResults.first { it.nodeId == "com.x.ItemContainer#swapItems" }

        assertEquals(baselineThin.score * 0.5, penalizedThin.score, 1e-9, "thin delegate score must be multiplied by the penalty after fusion+rerank")
        assertTrue(penalizedReal.score > penalizedThin.score, "thin delegate must rank below the real impl")
        assertTrue(
            penalizedResults.indexOf(penalizedReal) < penalizedResults.indexOf(penalizedThin),
            "thin delegate must be ordered below the real impl",
        )
        penalizedDb.close()
        baselineDb.close()
    }

    @Test
    fun `searchCode leaves the thin delegate score untouched on the hybrid path when delegatePenalty is one`() {
        val (db, svc) = delegateService(KnowledgeConfig(delegatePenalty = 1.0, nearDupPenalty = 1.0))
        val results = svc.searchCode("swapItems", null, 10)
        assertEquals(results, results.sortedByDescending { it.score }, "default penalty 1.0 must not reorder fused results")
        db.close()
    }

    @Test
    fun `deduplicateResults preserves insertion order`() {
        val results = listOf(
            result("node:First", 0.8),
            result("node:Second", 0.9),
            result("node:Third", 0.7),
        )
        val deduped = service.deduplicateResults(results)
        assertEquals(3, deduped.size)
        assertEquals("node:First", deduped[0].nodeId)
        assertEquals("node:Second", deduped[1].nodeId)
        assertEquals("node:Third", deduped[2].nodeId)
    }
}
