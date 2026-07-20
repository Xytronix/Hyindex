package com.hyindex.knowledge.cli

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.NoopProgressReporter
import com.hyindex.knowledge.core.source.GitSourceProvider
import com.hyindex.knowledge.index.GameDataIndexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class GitGameDataIndexTest {
    private fun git(dir: File, vararg a: String) {
        val p = ProcessBuilder(listOf("git", *a)).directory(dir)
            .redirectErrorStream(true).start()
        check(p.waitFor() == 0) { "git ${a.joinToString(" ")} failed" }
    }

    @Test
    fun `git-source gamedata is indexed from the worktree HytaleAssets directory`() {

        val origin = Files.createTempDirectory("origin-gd").toFile()
        git(origin, "init", "-q", "-b", "release")
        git(origin, "config", "user.email", "t@t")
        git(origin, "config", "user.name", "t")
        File(origin, "HytaleAssets/Server/Item/Items/Foo.json")
            .apply { parentFile.mkdirs(); writeText("""{"Id":"Foo"}""") }
        File(origin, "Protocol/protocol-version.json")
            .apply { parentFile.mkdirs(); writeText("""{"crc":0,"buildNumber":1}""") }
        git(origin, "add", "-A")
        git(origin, "commit", "-q", "-m", "init")

        val cacheBase = Files.createTempDirectory("cache-gd").toFile()
        val prepared = GitSourceProvider.prepare(
            patchline = "release", cacheBase = cacheBase, repoUrl = origin.absolutePath, log = StdoutLogProvider)


        val base = Files.createTempDirectory("hyindex-git-gd").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_test")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(File(base, "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter,
            gameDataDir = File(prepared.worktree, "HytaleAssets"))

        val result = GameDataIndexer(ctx).index()

        assertThat(result.ok).describedAs("GameDataIndexer should succeed: ${result.error}").isTrue()
        assertThat(result.skipped).describedAs("should not be skipped").isFalse()
        val rows = db.query("SELECT id FROM nodes WHERE corpus='gamedata'") { it.getString(1) }
        assertThat(rows).describedAs("should have at least one gamedata node").isNotEmpty()

        db.close()
        listOf(origin, cacheBase, base).forEach { it.deleteRecursively() }
    }
}
