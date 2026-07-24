// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.cli

import com.hyindex.common.settings.HytaleVersionDetector
import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.diff.DiffEngine
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.StdoutProgressReporter
import com.hyindex.knowledge.core.source.GitSourceProvider
import com.hyindex.knowledge.core.version.RetentionPruner
import com.hyindex.knowledge.core.version.VersionResolver
import com.hyindex.knowledge.index.BuildAllIndexer
import java.io.File
import java.lang.management.ManagementFactory


const val MAX_TARGET_HEAP = 8L * 1024 * 1024 * 1024


internal fun targetHeapBytes(physicalBytes: Long): Long =
    if (physicalBytes <= 0) 0 else minOf(physicalBytes * 3 / 4, MAX_TARGET_HEAP)


internal fun needsLargerHeap(currentMaxBytes: Long, physicalBytes: Long): Boolean {
    val target = targetHeapBytes(physicalBytes)
    return target > 0 && currentMaxBytes < target * 9 / 10
}


private fun physicalMemoryBytes(): Long =
    (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.totalMemorySize ?: 0L


private fun relaunchWithLargerHeapOrNull(args: Array<String>): Int? {
    if (System.getenv("HYINDEX_INDEXER_HEAP_OK") == "1") return null
    val physical = physicalMemoryBytes()
    if (!needsLargerHeap(Runtime.getRuntime().maxMemory(), physical)) return null

    val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    val xmxMb = targetHeapBytes(physical) / (1024 * 1024)


    val cmd = listOf(javaBin, "-Xmx${xmxMb}m", "--add-modules", "jdk.incubator.vector",
        "-cp", System.getProperty("java.class.path"),
        "com.hyindex.knowledge.cli.IndexerMainKt") + args
    println("Heap too small for indexing; relaunching with -Xmx${xmxMb}m")
    return ProcessBuilder(cmd).inheritIO()
        .also { it.environment()["HYINDEX_INDEXER_HEAP_OK"] = "1" }
        .start().waitFor()
}


private fun readRecordedVersion(versionDir: java.io.File): String? {
    val meta = java.io.File(versionDir, "version_meta.json")
    if (!meta.exists()) return null
    return Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(meta.readText())?.groupValues?.get(1)
}


private fun readRecordedTree(versionDir: java.io.File): String? {
    val meta = java.io.File(versionDir, "version_meta.json")
    if (!meta.exists()) return null
    return Regex("\"treeRevision\"\\s*:\\s*\"([^\"]+)\"").find(meta.readText())?.groupValues?.get(1)
}

private fun seedFromPrevious(fromDir: java.io.File, toDir: java.io.File, log: com.hyindex.knowledge.core.logging.LogProvider) {
    for (name in listOf("knowledge.db", "knowledge.db-wal", "knowledge.db-shm")) {
        val src = java.io.File(fromDir, name)
        if (src.exists()) src.copyTo(java.io.File(toDir, name), overwrite = true)
    }
    val hnsw = java.io.File(fromDir, "hnsw")
    if (hnsw.isDirectory) hnsw.copyRecursively(java.io.File(toDir, "hnsw"), overwrite = true)
    log.info("Seeded ${toDir.name} from ${fromDir.name} for incremental indexing")
}


private fun snapshotVersionIndex(versionDir: java.io.File, version: String, log: com.hyindex.knowledge.core.logging.LogProvider) {
    val dbFile = java.io.File(versionDir, "knowledge.db")
    if (!dbFile.exists()) return
    val patchline = versionDir.name
    val base = versionDir.parentFile.parentFile
    val snapshotFile = java.io.File(base, "snapshots/$patchline/$version.json")
    if (snapshotFile.exists()) return
    runCatching {
        DiffEngine(log).writeSnapshot(dbFile, snapshotFile)
        log.info("Snapshot written: ${snapshotFile.absolutePath}")
    }.onFailure { e ->
        log.warn("Failed to write snapshot for $patchline/$version: ${e.message}")
    }
}


private fun wipeVersionIndex(versionDir: java.io.File) {
    java.io.File(versionDir, "knowledge.db").delete()
    java.io.File(versionDir, "knowledge.db-wal").delete()
    java.io.File(versionDir, "knowledge.db-shm").delete()
    java.io.File(versionDir, "hnsw").deleteRecursively()
    java.io.File(versionDir, "decompiled").deleteRecursively()
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "init") {
        if (args.getOrNull(1) in setOf("-h", "--help")) { println(InitArgs.USAGE); return }
        runInit(args.drop(1))
        return
    }
    if (args.firstOrNull() == "eval") {
        if (args.getOrNull(1) in setOf("-h", "--help")) { println(EvalArgs.USAGE); return }
        runEval(args.drop(1))
        return
    }
    val opts = IndexerArgs.parse(args)
    if (opts.help) { println(IndexerArgs.USAGE); return }
    relaunchWithLargerHeapOrNull(args)?.let { kotlin.system.exitProcess(it) }
    val baseConfig = KnowledgeConfig.loadFromFile() ?: KnowledgeConfig()
    val log = StdoutLogProvider
    val baseDir = baseConfig.resolvedBasePath()

    var hadError = false

    for (patchline in opts.patchlines) {
        if (opts.reembed) {
            val slug = VersionResolver.latestSlug(baseDir, patchline) ?: run {
                log.warn("--reembed: no existing index for $patchline — skipping")
                continue
            }
            val cfg = baseConfig.copy(activeVersion = slug)
            val versionDir = cfg.resolvedIndexPath()
            val version = readRecordedVersion(versionDir) ?: run {
                log.warn("--reembed: no version_meta in $slug — skipping")
                continue
            }
            println("=== Re-embedding $slug ===")
            val db = KnowledgeDatabase.forFile(File(cfg.resolvedIndexPath(), "knowledge.db"), log)
            val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(File(baseDir, "embedding-cache.db"), log), log)
            val ctx = IndexContext(
                config = cfg, db = db, cache = cache, log = log, progress = StdoutProgressReporter(patchline),
                decompileDir = File(cfg.resolvedIndexPath(), "decompiled"),
                assetsZip = null,
                gameDataDir = null,
                clientFolder = null,
                docsDir = null,
            )
            val results = BuildAllIndexer(ctx, emptyList(), null, opts.corpora, false).run(true)
            db.close()
            results.forEach { r ->
                val state = when { r.error != null -> "FAILED: ${r.error}"; r.skipped -> "skipped"; else -> "${r.indexed} indexed" }
                println("  ${r.corpus}: $state")
                if (r.error != null) hadError = true
            }
            continue
        }

        val gitPrepared = if (opts.corpora.any { it in setOf("code", "gamedata", "client") })
            runCatching {
                GitSourceProvider.prepare(
                    patchline,
                    File(baseDir, "cache"),
                    log,
                    repoUrl = baseConfig.gitRepoUrl.ifBlank { GitSourceProvider.DEFAULT_REPO_URL },
                    token = baseConfig.gitToken,
                )
            }
                .onFailure { log.warn("git source failed for $patchline: ${it.message}") }.getOrNull()
        else null

        if (opts.corpora.any { it in setOf("code", "gamedata", "client") } && gitPrepared == null) {
            hadError = true; continue
        }

        val versionInfo = gitPrepared?.version ?: HytaleVersionDetector.HytaleVersionInfo(patchline, "", "", "", "")
        val slug = versionInfo.slug
        val cfg = baseConfig.copy(activeVersion = slug)
        val versionDir = cfg.resolvedIndexPath()

        val priorDir = if (!opts.force) {
            VersionResolver.latestSlug(baseDir, patchline)
                ?.let { baseConfig.copy(activeVersion = it).resolvedIndexPath() }
                ?.takeIf { File(it, "knowledge.db").exists() }
        } else null

        if (priorDir != null && readRecordedTree(priorDir) == versionInfo.treeRevision) {
            log.info("Patchline $patchline: content unchanged since ${priorDir.name} (tree ${versionInfo.treeRevision.take(12)}) — skipping")
            continue
        }
        if (opts.force && versionDir.exists()) {
            log.info("Patchline $patchline: --force; wiping $slug")
            wipeVersionIndex(versionDir)
        }
        versionDir.mkdirs()
        if (priorDir != null && priorDir != versionDir && !File(versionDir, "knowledge.db").exists()) {
            seedFromPrevious(priorDir, versionDir, log)
        }

        println("=== Indexing $slug ===")

        val ctxDecompileDir = gitPrepared?.stageDir ?: File(cfg.resolvedIndexPath(), "decompiled")

        val docRoots = buildList {
            if ("docs" in opts.corpora) {
                if ("server" in opts.docsSources) gitPrepared?.worktree?.let { add(it) }
                if ("support" in opts.docsSources)
                    runCatching { com.hyindex.knowledge.core.source.SupportDocsSource.fetchInto(File(baseDir, "cache"), log, force = opts.force) }.getOrNull()?.let { add(it) }
                if ("blog" in opts.docsSources)
                    runCatching { com.hyindex.knowledge.core.source.BlogDocsSource.fetchInto(File(baseDir, "cache"), log, force = opts.force) }.getOrNull()?.let { add(it) }
                File(baseDir, "re-docs").takeIf { it.isDirectory }?.let { add(it) }
            }
        }

        val includeGithubDocs = ("docs" in opts.corpora) && ("modding" in opts.docsSources)

        val db = KnowledgeDatabase.forFile(File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(File(baseDir, "embedding-cache.db"), log), log)
        val ctx = IndexContext(
            config = cfg, db = db, cache = cache, log = log, progress = StdoutProgressReporter(patchline),
            decompileDir = ctxDecompileDir,
            assetsZip = null,
            gameDataDir = gitPrepared?.let { File(it.worktree, "HytaleAssets") },
            manifestRoot = gitPrepared?.let { File(it.worktree, "HytaleServer") },
            clientFolder = gitPrepared?.let { File(it.worktree, "HytaleAssets/Common/UI") },
            docsDir = null,
        )
        val results = BuildAllIndexer(ctx, docRoots, versionInfo, opts.corpora, includeGithubDocs).run(opts.force)
        db.close()

        KnowledgeConfig.writeToFile(cfg)
        val patchlineHadError = results.any { it.error != null }
        results.forEach { r ->
            val state = when { r.error != null -> "FAILED: ${r.error}"; r.skipped -> "skipped"; else -> "${r.indexed} indexed" }
            println("  ${r.corpus}: $state")
        }
        if (patchlineHadError) {
            hadError = true
        } else if (cfg.retentionCount > 0) {
            RetentionPruner.pruneRetention(baseDir, patchline, cfg.retentionCount)
        }
    }
    if (hadError) kotlin.system.exitProcess(1)
}
