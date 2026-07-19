package com.hyve.knowledge.cli

import com.hyve.knowledge.core.config.KnowledgeConfig
import com.hyve.knowledge.core.db.EmbeddingCacheDatabase
import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.core.index.EmbeddingCacheService
import com.hyve.knowledge.core.index.IndexContext
import com.hyve.knowledge.core.logging.StdoutLogProvider
import com.hyve.knowledge.core.progress.NoopProgressReporter
import com.hyve.knowledge.core.source.GitSourceProvider
import com.hyve.knowledge.core.version.VersionResolver
import com.hyve.knowledge.index.BuildAllIndexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class ArchivalVersionTest {
    private fun git(dir: File, vararg a: String) {
        val p = ProcessBuilder(listOf("git", *a)).directory(dir)
            .redirectErrorStream(true).start()
        check(p.waitFor() == 0) { "git ${a.joinToString(" ")} failed" }
    }

    private fun makeOrigin(): File {
        val origin = Files.createTempDirectory("origin-arch").toFile()
        git(origin, "init", "-q", "-b", "release")
        git(origin, "config", "user.email", "t@t")
        git(origin, "config", "user.name", "t")
        File(origin, "Codec/src/main/java/com/hypixel/hytale/codec/Codec.java")
            .apply { parentFile.mkdirs(); writeText("package com.hypixel.hytale.codec; class Codec {}") }
        File(origin, "Protocol/protocol-version.json")
            .apply { parentFile.mkdirs(); writeText("""{"crc":1316766548,"buildNumber":100}""") }
        git(origin, "add", "-A")
        git(origin, "commit", "-q", "-m", "init")
        return origin
    }

    private fun runIndex(base: File, origin: File, force: Boolean = false): File {
        val cacheBase = File(base, "cache")
        val prepared = GitSourceProvider.prepare("release", cacheBase, StdoutLogProvider, origin.absolutePath)
        val versionInfo = prepared.version
        val slug = versionInfo.slug
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = slug)
        val versionDir = cfg.resolvedIndexPath()

        val recordedRev = readRecordedRevision(versionDir)
        if (!force && File(versionDir, "knowledge.db").exists() && recordedRev == versionInfo.fullRevision) {
            return versionDir
        }
        if (force && versionDir.exists()) wipeVersionIndex(versionDir)
        versionDir.mkdirs()

        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(File(versionDir, "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, decompileDir = prepared.stageDir)
        BuildAllIndexer(ctx, docRoots = emptyList(), versionInfo = versionInfo, corpora = setOf("code")).run(force)
        db.close()
        return versionDir
    }

    private fun readRecordedRevision(versionDir: File): String? {
        val meta = File(versionDir, "version_meta.json")
        if (!meta.exists()) return null
        return Regex("\"fullRevision\"\\s*:\\s*\"([^\"]+)\"").find(meta.readText())?.groupValues?.get(1)
    }

    private fun wipeVersionIndex(versionDir: File) {
        File(versionDir, "knowledge.db").delete()
        File(versionDir, "knowledge.db-wal").delete()
        File(versionDir, "knowledge.db-shm").delete()
        File(versionDir, "hnsw").deleteRecursively()
        File(versionDir, "decompiled").deleteRecursively()
    }

    @Test
    fun `indexer writes to versions slash slug dir and version_meta has fullRevision`() {
        val origin = makeOrigin()
        val base = Files.createTempDirectory("hyve-arch-base").toFile()

        val versionDir = runIndex(base, origin)

        assertThat(versionDir.name).matches("""release_b100_\d{4}-\d{2}-\d{2}-\w+""")
        val meta = File(versionDir, "version_meta.json")
        assertThat(meta).exists()
        assertThat(meta.readText()).contains("\"fullRevision\"")

        listOf(origin, base).forEach { it.deleteRecursively() }
    }

    @Test
    fun `second run on same commit is skipped`() {
        val origin = makeOrigin()
        val base = Files.createTempDirectory("hyve-arch-skip").toFile()

        val versionDir = runIndex(base, origin)
        val metaBefore = File(versionDir, "version_meta.json").lastModified()

        Thread.sleep(50)
        runIndex(base, origin)

        val metaAfter = File(versionDir, "version_meta.json").lastModified()
        assertThat(metaAfter).isEqualTo(metaBefore)

        listOf(origin, base).forEach { it.deleteRecursively() }
    }

    @Test
    fun `force flag wipes and rebuilds the slug dir`() {
        val origin = makeOrigin()
        val base = Files.createTempDirectory("hyve-arch-force").toFile()

        val versionDir = runIndex(base, origin)
        val metaBefore = File(versionDir, "version_meta.json").lastModified()

        Thread.sleep(50)
        runIndex(base, origin, force = true)

        val metaAfter = File(versionDir, "version_meta.json").lastModified()
        assertThat(metaAfter).isGreaterThan(metaBefore)

        listOf(origin, base).forEach { it.deleteRecursively() }
    }

    @Test
    fun `VersionResolver finds the slug dir after indexing`() {
        val origin = makeOrigin()
        val base = Files.createTempDirectory("hyve-arch-resolver").toFile()

        runIndex(base, origin)

        val slug = VersionResolver.latestSlug(base, "release")
        assertThat(slug).isNotNull
        assertThat(slug).matches("""release_b100_\d{4}-\d{2}-\d{2}-\w+""")

        listOf(origin, base).forEach { it.deleteRecursively() }
    }
}
