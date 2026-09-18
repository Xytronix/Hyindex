// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.source

import com.hyindex.knowledge.core.logging.LogProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class GitSourceProviderAuthTest {

    @Test
    fun `authEnv is empty when token is null`() {
        assertEquals(emptyMap<String, String>(), GitSourceProvider.authEnv(null))
    }

    @Test
    fun `authEnv puts basic header in env not argv keys`() {
        val token = "ghp_x"
        val env = GitSourceProvider.authEnv(token)
        val expected = Base64.getEncoder().encodeToString(("x-access-token:$token").toByteArray())
        assertEquals("Authorization: Basic $expected", env["GIT_CONFIG_VALUE_0"])
        assertEquals("http.extraHeader", env["GIT_CONFIG_KEY_0"])
        assertFalse(env.values.any { it == token })
    }

    @Test
    fun `prepare with token keeps PAT out of argv and log messages`() {
        val token = "ghp_secret_token_xyz_never_argv"
        val origin = Files.createTempDirectory("origin-auth").toFile()
        git(origin, "init", "-q", "-b", "release")
        git(origin, "config", "user.email", "t@t")
        git(origin, "config", "user.name", "t")
        File(origin, "Codec/src/main/java/com/hypixel/hytale/codec/Codec.java")
            .apply { parentFile.mkdirs(); writeText("package com.hypixel.hytale.codec; class Codec {}") }
        File(origin, "Protocol/protocol-version.json")
            .apply { parentFile.mkdirs(); writeText("""{"crc":1,"buildNumber":1}""") }
        git(origin, "add", "-A")
        git(origin, "commit", "-q", "-m", "init")

        val cacheBase = Files.createTempDirectory("cache-auth").toFile()
        val log = CaptureLog()
        GitSourceProvider.prepare(
            patchline = "release",
            cacheBase = cacheBase,
            repoUrl = origin.absolutePath,
            log = log,
            token = token,
        )
        val argv = GitSourceProvider.lastGitArgv.joinToString("\u0000")
        assertFalse(argv.contains(token))
        assertFalse(argv.contains("Authorization"))
        assertFalse(argv.contains("x-access-token"))
        assertTrue(log.lines.none { it.contains(token) })
        listOf(origin, cacheBase).forEach { it.deleteRecursively() }
    }

    @Test
    fun `prepare without token still clones local repo`() {
        val origin = Files.createTempDirectory("origin-notoken").toFile()
        git(origin, "init", "-q", "-b", "release")
        git(origin, "config", "user.email", "t@t")
        git(origin, "config", "user.name", "t")
        File(origin, "Codec/src/main/java/com/hypixel/hytale/codec/Codec.java")
            .apply { parentFile.mkdirs(); writeText("package com.hypixel.hytale.codec; class Codec {}") }
        File(origin, "Protocol/protocol-version.json")
            .apply { parentFile.mkdirs(); writeText("""{"crc":1,"buildNumber":2}""") }
        git(origin, "add", "-A")
        git(origin, "commit", "-q", "-m", "init")
        val cacheBase = Files.createTempDirectory("cache-notoken").toFile()
        val prepared = GitSourceProvider.prepare(
            "release",
            cacheBase,
            CaptureLog(),
            origin.absolutePath,
            token = null,
        )
        assertTrue(File(prepared.stageDir, "com/hypixel/hytale/codec/Codec.java").isFile)
        listOf(origin, cacheBase).forEach { it.deleteRecursively() }
    }

    private fun git(dir: File, vararg a: String) {
        val p = ProcessBuilder(listOf("git", *a)).directory(dir).redirectErrorStream(true).start()
        check(p.waitFor() == 0) { "git ${a.joinToString(" ")} failed" }
    }

    private class CaptureLog : LogProvider {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += message }
        override fun warn(message: String, throwable: Throwable?) { lines += message }
        override fun error(message: String, throwable: Throwable?) { lines += message }
        override fun debug(message: String) { lines += message }
    }
}
