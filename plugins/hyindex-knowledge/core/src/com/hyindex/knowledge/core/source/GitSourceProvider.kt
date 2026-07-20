// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.source

import com.hyindex.common.settings.HytaleVersionDetector
import com.hyindex.knowledge.core.logging.LogProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.Base64


object GitSourceProvider {

    const val DEFAULT_REPO_URL = "https://github.com/HypixelStudios/hytale-shared-source.git"
    private val PATCHLINE_BRANCH = mapOf("release" to "release", "pre-release" to "pre-release")

    @Serializable
    private data class ProtocolVersion(val crc: Long = 0, val buildNumber: Int = -1)
    private val protoJson = Json { ignoreUnknownKeys = true }

    data class Prepared(val stageDir: File, val worktree: File, val version: HytaleVersionDetector.HytaleVersionInfo)

    internal fun authArgs(token: String?): List<String> {
        if (token == null) return emptyList()
        val basic = Base64.getEncoder().encodeToString(("x-access-token:$token").toByteArray())
        return listOf("-c", "http.extraHeader=Authorization: Basic $basic")
    }

    private fun git(dir: File, vararg a: String, token: String? = null): String {
        val p = ProcessBuilder(listOf("git") + authArgs(token) + a)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${a.joinToString(" ")} failed: $out" }
        return out.trim()
    }


    fun ensureClone(cacheBase: File, repoUrl: String, log: LogProvider, token: String? = null): File {
        val repo = File(cacheBase, "repo")
        if (File(repo, ".git").isDirectory || File(repo, "HEAD").isFile) {
            log.info("git: fetching $repoUrl")
            git(repo, "fetch", "--filter=blob:none", "--prune", "origin", token = token)
        } else {
            log.info("git: cloning $repoUrl (blobless)")
            repo.parentFile.mkdirs()
            git(cacheBase, "clone", "--filter=blob:none", "--no-checkout", repoUrl, repo.absolutePath, token = token)
        }
        return repo
    }


    fun worktreeFor(repo: File, patchline: String, cacheBase: File, token: String? = null): File {
        val branch = PATCHLINE_BRANCH[patchline] ?: error("unknown patchline: $patchline")
        val wt = File(cacheBase, "worktrees/$patchline")


        runCatching { git(repo, "worktree", "remove", "--force", wt.absolutePath) }
        runCatching { git(repo, "worktree", "prune") }
        git(repo, "worktree", "add", "--force", "--detach", wt.absolutePath, "origin/$branch", token = token)
        return wt
    }


    fun deriveVersion(worktree: File, patchline: String): HytaleVersionDetector.HytaleVersionInfo {
        val full = git(worktree, "rev-parse", "HEAD")
        val short = git(worktree, "rev-parse", "--short", "HEAD")
        val isoDate = git(worktree, "log", "-1", "--format=%cd", "--date=format:%Y-%m-%d")
        val pv = File(worktree, "Protocol/protocol-version.json")
        require(pv.isFile) { "Protocol/protocol-version.json missing in $patchline worktree" }
        val proto = runCatching { protoJson.decodeFromString(ProtocolVersion.serializer(), pv.readText()) }.getOrNull()
        require(proto != null && proto.buildNumber >= 0) { "Protocol/protocol-version.json unparseable in $patchline" }
        val raw = "b${proto.buildNumber}_$isoDate-$short"
        val branch = PATCHLINE_BRANCH[patchline] ?: patchline
        return HytaleVersionDetector.HytaleVersionInfo(patchline, isoDate, short, full, raw, proto.buildNumber, proto.crc, branch)
    }

    fun prepare(patchline: String, cacheBase: File, log: LogProvider, repoUrl: String = DEFAULT_REPO_URL, token: String? = null): Prepared {
        val repo = ensureClone(cacheBase, repoUrl, log, token)
        val wt = worktreeFor(repo, patchline, cacheBase, token)
        val version = deriveVersion(wt, patchline)
        val stage = stageFlatRoot(wt, File(cacheBase, "staged/$patchline"))
        return Prepared(stage, wt, version)
    }


    fun stageFlatRoot(worktree: File, stageDir: File): File {
        if (stageDir.exists()) stageDir.deleteRecursively()
        stageDir.mkdirs()

        val roots = buildList {
            worktree.walkTopDown()
                .filter { it.isDirectory && it.invariantSeparatorsPath.endsWith("/src/main/java") }
                .forEach { add(it) }
            File(worktree, "HytaleServer/Protocol/target/generated-sources/java")
                .takeIf { it.isDirectory }?.let { add(it) }
        }

        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { src ->
                    val rel = src.relativeTo(root).invariantSeparatorsPath
                    if (!rel.startsWith("com/")) return@forEach
                    val target = File(stageDir, rel)
                    target.parentFile.mkdirs()
                    if (target.exists()) return@forEach
                    try {
                        Files.createSymbolicLink(target.toPath(), src.toPath())
                    } catch (_: Exception) {
                        src.copyTo(target, overwrite = false)
                    }
                }
        }
        return stageDir
    }
}
