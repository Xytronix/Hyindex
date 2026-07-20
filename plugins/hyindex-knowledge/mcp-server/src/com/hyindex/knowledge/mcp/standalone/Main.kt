// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.mcp.standalone

import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.CorpusIndexManager
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.search.KnowledgeSearchService
import com.hyindex.knowledge.core.version.VersionResolver
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() {
    val log = StdoutLogProvider


    log.info("Loading configuration...")
    val config = McpConfig.load()
    log.info("Embedding provider: ${config.embeddingProvider}")
    val basePath = config.resolvedBasePath()
    log.info("Base path: ${basePath.absolutePath}")


    val patchlines = listOf("release", "pre-release")
    val services = linkedMapOf<String, KnowledgeSearchService>()
    val databases = linkedMapOf<String, KnowledgeDatabase>()

    val resolved = VersionResolver.resolveAll(basePath)

    for (patchline in patchlines) {
        val slug = resolved[patchline] ?: patchline
        val patchlineConfig = config.copy(activeVersion = slug)
        val dbFile = File(patchlineConfig.resolvedIndexPath(), "knowledge.db")
        if (!dbFile.exists()) {
            log.info("Skipping patchline '$patchline': no knowledge.db at ${dbFile.absolutePath}")
            continue
        }
        log.info("Loading patchline '$patchline' (version '$slug') from ${dbFile.absolutePath}")
        val db = KnowledgeDatabase.forFile(dbFile, log)
        val indexManager = CorpusIndexManager(patchlineConfig, log)

        for (corpus in Corpus.entries) {
            val hnswPath = indexManager.hnswPath(corpus)
            if (hnswPath.toFile().exists()) {
                log.info("  HNSW index found for ${corpus.displayName}: $hnswPath")
            } else {
                log.warn("  HNSW index missing for ${corpus.displayName}: $hnswPath — vector search will be unavailable for this corpus")
            }
        }

        databases[patchline] = db
        services[patchline] = KnowledgeSearchService(db, indexManager, log, patchlineConfig)
    }

    if (services.isEmpty()) {
        log.error("No knowledge indexes found. Checked patchlines: $patchlines under ${File(basePath, "versions").absolutePath}")
        log.error("Run 'Build All Knowledge Indices' with the Hyindex indexer first to create an index.")
        System.exit(1)
    }

    log.info("Loaded patchlines: ${services.keys}")


    val server = HytaleKnowledgeServer(services, databases, config.snippetMaxLength, config)
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down...")
        server.close()
        services.values.forEach { it.close() }
        databases.values.forEach { it.close() }
    })

    runBlocking {
        server.run()
    }
}
