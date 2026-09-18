// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.search

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.embedding.Reranker
import com.hyindex.knowledge.core.index.CorpusIndexManager
import com.hyindex.knowledge.core.index.HnswIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class CandidateRankingTest {

    @Test
    fun `searchAcrossCorpora gives the routed primary 30 candidates and secondaries 10-15`() {
        val capturing = CapturingReranker()
        val fixture = indexedService(
            nodesPerCorpus = 40,
            corpora = listOf(Corpus.CODE, Corpus.DOCS, Corpus.CLIENT),
            rerankerTopN = 60,
            reranker = capturing,
            router = RecordingRouter(
                CorpusRoute(
                    corpora = listOf(Corpus.CODE, Corpus.DOCS, Corpus.CLIENT),
                    probabilities = mapOf(
                        Corpus.CODE to 0.92,
                        Corpus.DOCS to 0.48,
                        Corpus.CLIENT to 0.22,
                    ),
                    intent = "api_how_to",
                    graphExpansionProbability = 0.1,
                ),
            ),
        )
        try {
            fixture.service.searchAcrossCorpora("how do I register a command", fixture.corpora, perCorpus = 10)

            val counts = capturing.corpusCounts()
            assertEquals(30, counts[Corpus.CODE], "primary corpus should fetch 30 first-pass candidates: $counts")
            val docs = counts.getValue(Corpus.DOCS)
            val client = counts.getValue(Corpus.CLIENT)
            assertTrue(docs in 10..15, "docs secondary budget should be 10-15, got $docs")
            assertTrue(client in 10..15, "client secondary budget should be 10-15, got $client")
            assertTrue(docs > client, "higher Jev probability should receive the larger secondary budget")
            assertTrue(capturing.lastDocuments.size <= 60)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `searchAcrossCorpora first-pass candidates never exceed rerankerTopN`() {
        val capturing = CapturingReranker()
        val fixture = indexedService(
            nodesPerCorpus = 40,
            corpora = listOf(Corpus.CODE, Corpus.DOCS, Corpus.CLIENT),
            rerankerTopN = 42,
            reranker = capturing,
            router = RecordingRouter(
                CorpusRoute(
                    corpora = listOf(Corpus.CODE, Corpus.DOCS, Corpus.CLIENT),
                    probabilities = mapOf(
                        Corpus.CODE to 0.9,
                        Corpus.DOCS to 0.4,
                        Corpus.CLIENT to 0.3,
                    ),
                    intent = "api_how_to",
                    graphExpansionProbability = 0.1,
                ),
            ),
        )
        try {
            fixture.service.searchAcrossCorpora("register command", fixture.corpora, perCorpus = 10)
            assertTrue(
                capturing.lastDocuments.size <= 42,
                "first-pass rerank pool must not exceed rerankerTopN, got ${capturing.lastDocuments.size}",
            )
            val counts = capturing.corpusCounts()
            assertEquals(30, counts[Corpus.CODE], "primary still gets 30 inside the cap: $counts")
            assertEquals(42, capturing.lastDocuments.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `searchAcrossCorpora without a route keeps the per-corpus limit`() {
        val capturing = CapturingReranker()
        val fixture = indexedService(
            nodesPerCorpus = 40,
            corpora = listOf(Corpus.CODE, Corpus.DOCS, Corpus.CLIENT),
            rerankerTopN = 50,
            reranker = capturing,
            router = CorpusRouter { _, _ -> null },
        )
        try {
            fixture.service.searchAcrossCorpora("register command", fixture.corpora, perCorpus = 10)
            val counts = capturing.corpusCounts()
            assertEquals(10, counts[Corpus.CODE], "unrouted code budget is perCorpus: $counts")
            assertEquals(10, counts[Corpus.DOCS], "unrouted docs budget is perCorpus: $counts")
            assertEquals(10, counts[Corpus.CLIENT], "unrouted client budget is perCorpus: $counts")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `reranker documents are cleaned structured text and the query carries intent`() {
        val capturing = CapturingReranker()
        val fixture = indexedService(
            nodesPerCorpus = 2,
            corpora = listOf(Corpus.DOCS),
            rerankerTopN = 10,
            reranker = capturing,
            router = RecordingRouter(
                CorpusRoute(
                    corpora = listOf(Corpus.DOCS),
                    probabilities = mapOf(Corpus.DOCS to 0.97),
                    intent = "api_how_to",
                    graphExpansionProbability = 0.05,
                ),
            ),
            docsBoilerplate = true,
        )
        try {
            fixture.service.searchAcrossCorpora("register a custom command", fixture.corpora, perCorpus = 5)
            assertTrue(capturing.called)
            assertTrue(
                capturing.lastQuery.contains("api_how_to"),
                "rerank query should include the routed intent instruction, got: ${capturing.lastQuery}",
            )
            val doc = capturing.lastDocuments.first { it.contains("RegisterCommand") || it.contains("docs-body-0") }
            assertTrue(doc.contains("docs"), "structured doc should name the corpus: $doc")
            assertTrue(doc.contains("RegisterCommand"), "structured doc should include the title/symbol: $doc")
            assertTrue(doc.contains("modding/commands.md"), "structured doc should include the path: $doc")
            assertTrue(doc.contains("guide"), "structured doc should include the data type: $doc")
            assertTrue(doc.contains("docs-body-0"), "structured doc should include the stored content: $doc")
            assertFalse(
                doc.contains("Hytale Modding Docs:"),
                "corpus boilerplate must be stripped from rerank documents: $doc",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `post-rerank results cap repeated sources while retaining distinct sources`() {
        val capturing = CapturingReranker { document ->
            when {
                "docs-body" in document -> 0.99
                "code-body" in document -> 0.90
                "gamedata-body" in document -> 0.80
                else -> 0.70
            }
        }
        val fixture = indexedService(
            nodesPerCorpus = 4,
            corpora = listOf(Corpus.DOCS, Corpus.CODE, Corpus.GAMEDATA, Corpus.CLIENT),
            rerankerTopN = 50,
            reranker = capturing,
            router = RecordingRouter(
                CorpusRoute(
                    corpora = listOf(Corpus.DOCS, Corpus.CODE, Corpus.GAMEDATA, Corpus.CLIENT),
                    probabilities = mapOf(
                        Corpus.DOCS to 0.8,
                        Corpus.CODE to 0.7,
                        Corpus.GAMEDATA to 0.6,
                        Corpus.CLIENT to 0.5,
                    ),
                    intent = "api_how_to",
                    graphExpansionProbability = 0.1,
                ),
            ),
        )
        try {
            val results = fixture.service.searchAcrossCorpora(
                "register a custom command",
                fixture.corpora,
                perCorpus = 8,
            )
            val bySource = results.groupBy { it.corpus to it.filePath }
            for ((source, hits) in bySource) {
                val cap = if (source.first == "code") 2 else 1
                assertTrue(hits.size <= cap, "source $source exceeds cap $cap: ${hits.map { it.nodeId }}")
            }
            val byCorpus = results.groupBy { it.corpus }
            assertEquals(1, byCorpus["docs"]?.size, "adjacent docs chunks share one source")
            assertTrue((byCorpus["code"]?.size ?: 0) >= 3, "distinct code files should remain")
            assertTrue((byCorpus["gamedata"]?.size ?: 0) >= 2, "distinct gamedata files should remain")
            assertEquals(8, results.size, "diversification should backfill from distinct sources")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `weak first-pass relevance runs one graph broaden without a second router call`() {
        val capturing = CapturingReranker { 0.18 }
        val router = RecordingRouter(
            CorpusRoute(
                corpora = listOf(Corpus.CODE, Corpus.GAMEDATA),
                probabilities = mapOf(Corpus.CODE to 0.7, Corpus.GAMEDATA to 0.4),
                intent = "api_how_to",
                graphExpansionProbability = 0.2,
            ),
        )
        val fixture = indexedService(
            nodesPerCorpus = 2,
            corpora = listOf(Corpus.CODE, Corpus.GAMEDATA),
            rerankerTopN = 20,
            reranker = capturing,
            router = router,
            withCodeGamedataEdge = true,
        )
        try {
            val results = fixture.service.searchAcrossCorpora("register command", fixture.corpora, perCorpus = 4)
            assertEquals(1, router.calls, "adaptive broaden must not call the corpus router again")
            assertTrue(
                results.any { it.nodeId == "gamedata:linked-item" },
                "weak Voyage relevance should add the graph neighbor: ${results.map { it.nodeId }}",
            )
        } finally {
            fixture.close()
        }
    }
    @Test
    fun `strong first-pass relevance skips the graph broaden`() {
        val capturing = CapturingReranker { 0.70 }
        val fixture = indexedService(
            nodesPerCorpus = 2,
            corpora = listOf(Corpus.CODE, Corpus.GAMEDATA),
            rerankerTopN = 20,
            reranker = capturing,
            router = RecordingRouter(
                CorpusRoute(
                    corpora = listOf(Corpus.CODE, Corpus.GAMEDATA),
                    probabilities = mapOf(Corpus.CODE to 0.51, Corpus.GAMEDATA to 0.49),
                    intent = "api_how_to",
                    graphExpansionProbability = 0.2,
                ),
            ),
            withCodeGamedataEdge = true,
        )
        try {
            val results = fixture.service.searchAcrossCorpora("register command", fixture.corpora, perCorpus = 4)
            assertFalse(
                results.any { it.nodeId == "gamedata:linked-item" && it.source == ResultSource.GRAPH },
                "strong relevance should skip graph expansion: ${results.map { it.nodeId to it.source }}",
            )
        } finally {
            fixture.close()
        }
    }

    private class RecordingRouter(private val route: CorpusRoute) : CorpusRouter {
        var calls = 0
        override fun route(query: String, availableCorpora: List<Corpus>): CorpusRoute? {
            calls++
            return route
        }
    }

    private class CapturingReranker(
        private val scoreFor: (String) -> Double = { 0.5 },
    ) : Reranker {
        var called = false
        var lastQuery: String = ""
        var lastDocuments: List<String> = emptyList()
        override fun rerank(query: String, documents: List<String>): List<Pair<Int, Double>> {
            called = true
            lastQuery = query
            lastDocuments = documents
            return documents.mapIndexed { index, document -> index to scoreFor(document) }
                .sortedByDescending { it.second }
        }

        fun corpusCounts(): Map<Corpus, Int> = Corpus.entries.associateWith { corpus ->
            lastDocuments.count { document ->
                document.contains("${corpus.id}-body-") || document.contains("${corpus.id}:")
            }
        }
    }

    private data class Fixture(
        val service: KnowledgeSearchService,
        val db: KnowledgeDatabase,
        val indexManager: CorpusIndexManager,
        val corpora: List<Corpus>,
    ) {
        fun close() {
            indexManager.closeAll()
            db.close()
        }
    }

    private fun indexedService(
        nodesPerCorpus: Int,
        corpora: List<Corpus>,
        rerankerTopN: Int,
        reranker: Reranker,
        router: CorpusRouter,
        docsBoilerplate: Boolean = false,
        withCodeGamedataEdge: Boolean = false,
    ): Fixture {
        val indexDir = Files.createTempDirectory("candidate_rank_").toFile()
        indexDir.deleteOnExit()
        val config = KnowledgeConfig(
            embeddingProfiles = mapOf("fake" to com.hyindex.knowledge.core.config.EmbeddingProfile("fake", documentModel = "fake")),
            corpusEmbeddingProfiles = com.hyindex.knowledge.core.db.Corpus.entries.associate { it.id to "fake" },
            indexPath = indexDir.absolutePath,
            hybridEnabled = false,
            rerankerProfile = com.hyindex.knowledge.core.config.RerankerProfile(
                provider = "fake",
                model = "fake-rerank",
                topN = rerankerTopN,
            ),
            jevRoutingEnabled = false,
        )
        val db = KnowledgeDatabase.forFile(java.io.File(indexDir, "knowledge.db"))
        val indexManager = CorpusIndexManager(config)
        for (corpus in corpora) {
            val vectors = ArrayList<FloatArray>(nodesPerCorpus)
            repeat(nodesPerCorpus) { ordinal ->
                val id = "${corpus.id}:n$ordinal"
                val display = if (corpus == Corpus.DOCS && ordinal == 0) "RegisterCommand" else "Node$ordinal"
                val path = when (corpus) {
                    Corpus.DOCS -> "modding/commands.md"
                    Corpus.CODE -> if (ordinal < 3) "code/Shared.java" else "code/Other.java"
                    Corpus.GAMEDATA -> if (ordinal < 2) "gamedata/shared.json" else "gamedata/$ordinal.json"
                    Corpus.CLIENT -> if (ordinal < 2) "client/Shared.ui" else "client/$ordinal.ui"
                    Corpus.VISUAL -> if (ordinal < 2) "visual/shared.png" else "visual/$ordinal.png"
                }
                val dataType = when (corpus) {
                    Corpus.DOCS -> "guide"
                    Corpus.GAMEDATA -> "item"
                    Corpus.CODE -> "JavaMethod"
                    Corpus.CLIENT -> "view"
                    Corpus.VISUAL -> "raster"
                }
                val body = "${corpus.id}-body-$ordinal unique $display"
                val embedding = if (docsBoilerplate && corpus == Corpus.DOCS) {
                    "Hytale Modding Docs: $display\nType: ${dataType}\n\n$body"
                } else {
                    body
                }
                db.execute(
                    "INSERT INTO nodes (id, node_type, display_name, file_path, content, embedding_text, chunk_index, corpus, data_type) " +
                        "VALUES (?, 'Chunk', ?, ?, ?, ?, ?, ?, ?)",
                    id, display, path, body, embedding, ordinal, corpus.id, dataType,
                )
                vectors += FloatArray(8) { 1.0f }
            }
            val index = HnswIndex(8)
            index.build(vectors)
            index.save(indexManager.hnswPath(corpus))
            index.close()
        }
        if (withCodeGamedataEdge) {
            db.execute(
                "INSERT INTO nodes (id, node_type, display_name, file_path, content, embedding_text, chunk_index, corpus, data_type) " +
                    "VALUES (?, 'GameData', ?, ?, ?, ?, ?, 'gamedata', 'item')",
                "gamedata:linked-item",
                "LinkedItem",
                "gamedata/linked.json",
                "graph-only neighbor",
                "graph-only neighbor",
                99,
            )
            db.execute(
                "INSERT INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'IMPLEMENTED_BY')",
                "gamedata:linked-item",
                "code:n0",
            )
        }
        val service = KnowledgeSearchService(
            db,
            indexManager,
            config = config,
            reranker = reranker,
            corpusRouter = router,
        )
        return Fixture(service, db, indexManager, corpora)
    }
}
