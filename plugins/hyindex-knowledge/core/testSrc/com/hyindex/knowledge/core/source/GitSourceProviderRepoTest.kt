package com.hyindex.knowledge.core.source

import com.hyindex.knowledge.core.logging.StdoutLogProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

class GitSourceProviderRepoTest {
    private fun git(dir: File, vararg a: String) {
        val p = ProcessBuilder(listOf("git", *a)).directory(dir)
            .redirectErrorStream(true).start()
        check(p.waitFor() == 0) { "git ${a.joinToString(" ")} failed" }
    }

    @Test
    fun `prepare clones a branch, stages sources, derives a git version`() {
        val origin = Files.createTempDirectory("origin").toFile()
        git(origin, "init", "-q", "-b", "release")
        git(origin, "config", "user.email", "t@t"); git(origin, "config", "user.name", "t")
        File(origin, "Codec/src/main/java/com/hypixel/hytale/codec/Codec.java")
            .apply { parentFile.mkdirs(); writeText("package com.hypixel.hytale.codec; class Codec {}") }
        File(origin, "Protocol/protocol-version.json")
            .apply { parentFile.mkdirs(); writeText("""{"crc":1316766548,"buildNumber":100}""") }
        git(origin, "add", "-A"); git(origin, "commit", "-q", "-m", "init")

        val cacheBase = Files.createTempDirectory("cache").toFile()
        val prepared = GitSourceProvider.prepare(
            patchline = "release", cacheBase = cacheBase, repoUrl = origin.absolutePath, log = StdoutLogProvider)

        assertThat(File(prepared.stageDir, "com/hypixel/hytale/codec/Codec.java")).exists()
        assertThat(prepared.worktree).isDirectory()
        assertThat(File(prepared.worktree, "Codec/src/main/java/com/hypixel/hytale/codec/Codec.java")).exists()
        assertThat(prepared.version.patchline).isEqualTo("release")
        assertThat(prepared.version.fullRevision).hasSize(40)
        assertThat(prepared.version.shortHash).isNotBlank()
        assertThat(prepared.version.buildNumber).isEqualTo(100)
        assertThat(prepared.version.protocolCrc).isEqualTo(1316766548L)
        assertThat(prepared.version.treeRevision).hasSize(40)
        assertThat(prepared.version.treeRevision).isNotEqualTo(prepared.version.fullRevision)
        assertThat(prepared.version.slug).matches("""release_b100_\d{4}-\d{2}-\d{2}-\w+""")

        listOf(origin, cacheBase).forEach { it.deleteRecursively() }
    }

    @Test
    fun `prepare fails when Protocol protocol-version json is absent`() {
        val origin = Files.createTempDirectory("origin-noproto").toFile()
        git(origin, "init", "-q", "-b", "release")
        git(origin, "config", "user.email", "t@t"); git(origin, "config", "user.name", "t")
        File(origin, "Codec/src/main/java/com/hypixel/hytale/codec/Codec.java")
            .apply { parentFile.mkdirs(); writeText("package com.hypixel.hytale.codec; class Codec {}") }
        git(origin, "add", "-A"); git(origin, "commit", "-q", "-m", "init")

        val cacheBase = Files.createTempDirectory("cache-noproto").toFile()
        assertThrows<Exception> {
            GitSourceProvider.prepare(
                patchline = "release", cacheBase = cacheBase, repoUrl = origin.absolutePath, log = StdoutLogProvider)
        }

        listOf(origin, cacheBase).forEach { it.deleteRecursively() }
    }
}
