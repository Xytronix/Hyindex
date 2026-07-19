package com.hyve.knowledge.cli

import com.hyve.knowledge.core.config.KnowledgeConfig
import com.hyve.knowledge.core.db.EmbeddingCacheDatabase
import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.core.index.EmbeddingCacheService
import com.hyve.knowledge.core.index.IndexContext
import com.hyve.knowledge.core.logging.StdoutLogProvider
import com.hyve.knowledge.core.progress.NoopProgressReporter
import com.hyve.knowledge.core.source.GitSourceProvider
import com.hyve.knowledge.index.CodeIndexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class GitSourceIndexTest {
    private fun git(dir: File, vararg a: String) {
        val p = ProcessBuilder(listOf("git", *a)).directory(dir)
            .redirectErrorStream(true).start()
        check(p.waitFor() == 0) { "git ${a.joinToString(" ")} failed" }
    }

    @Test
    fun `git-source staged files are indexed by CodeIndexer`() {

        val origin = Files.createTempDirectory("origin-index").toFile()
        git(origin, "init", "-q", "-b", "release")
        git(origin, "config", "user.email", "t@t")
        git(origin, "config", "user.name", "t")
        File(origin, "Codec/src/main/java/com/hypixel/hytale/codec/Codec.java")
            .apply { parentFile.mkdirs(); writeText("package com.hypixel.hytale.codec; public class Codec { public int encode(int x) { return x; } }") }
        File(origin, "Protocol/protocol-version.json")
            .apply { parentFile.mkdirs(); writeText("""{"crc":0,"buildNumber":1}""") }
        git(origin, "add", "-A")
        git(origin, "commit", "-q", "-m", "init")

        val cacheBase = Files.createTempDirectory("cache-index").toFile()
        val prepared = GitSourceProvider.prepare(
            patchline = "release", cacheBase = cacheBase, repoUrl = origin.absolutePath, log = StdoutLogProvider)


        val base = Files.createTempDirectory("hyve-git-index").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_test")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(File(base, "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, decompileDir = prepared.stageDir)

        val result = CodeIndexer(ctx).index()

        assertThat(result.ok).describedAs("CodeIndexer should succeed: ${result.error}").isTrue()
        val rows = db.query(
            "SELECT owning_file FROM nodes WHERE corpus='code' AND owning_file LIKE 'com/hypixel/hytale/%'"
        ) { it.getString(1) }
        assertThat(rows).describedAs("should have code nodes under com/hypixel/hytale/").isNotEmpty()

        db.close()
        listOf(origin, cacheBase, base).forEach { it.deleteRecursively() }
    }
}
