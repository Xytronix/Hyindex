// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.source

import com.hyindex.common.settings.HytaleVersionDetector
import com.hyindex.knowledge.core.logging.LogProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Base64

object GitSourceProvider {

    const val DEFAULT_REPO_URL = "https://github.com/HypixelStudios/hytale-shared-source.git"
    private val PATCHLINE_BRANCH = mapOf("release" to "release", "pre-release" to "pre-release")

    @Serializable
    private data class ProtocolVersion(val crc: Long = 0, val buildNumber: Int = -1)
    private val protoJson = Json { ignoreUnknownKeys = true }

    data class Prepared(val stageDir: File, val worktree: File, val version: HytaleVersionDetector.HytaleVersionInfo)

    @Volatile
    internal var lastGitArgv: List<String> = emptyList()

    internal fun authEnv(token: String?): Map<String, String> {
        if (token.isNullOrEmpty()) return emptyMap()
        val basic = Base64.getEncoder().encodeToString(("x-access-token:$token").toByteArray())
        return mapOf(
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.extraHeader",
            "GIT_CONFIG_VALUE_0" to "Authorization: Basic $basic",
            "GIT_TERMINAL_PROMPT" to "0",
        )
    }

    private fun git(dir: File, vararg a: String, token: String? = null, input: String? = null): String {
        val argv = listOf("git") + a.toList()
        lastGitArgv = argv
        val p = ProcessBuilder(argv).directory(dir).redirectErrorStream(true)
        val env = p.environment()
        env.putAll(authEnv(token))
        val proc = p.start()
        proc.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "git ${a.joinToString(" ")} failed: $out" }
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

    @Synchronized
    fun worktreeFor(repo: File, patchline: String, cacheBase: File, token: String? = null): File {
        val branch = PATCHLINE_BRANCH[patchline] ?: error("unknown patchline: $patchline")
        val revision = git(repo, "rev-parse", "--verify", "origin/$branch^{commit}")
        val wt = File(cacheBase, "worktrees/$patchline")
        check(!Files.isSymbolicLink(wt.toPath()) && wt.canonicalFile.toPath().startsWith(cacheBase.canonicalFile.toPath())) {
            "Refusing worktree outside cache: $wt"
        }
        val existing = worktreeRecord(repo, wt)
        if (existing != null) {
            val lock = existing.firstOrNull { it == "locked" || it.startsWith("locked ") }
            check(lock == null) { "Worktree $wt is $lock; inspect it before retrying" }
            check(wt.isDirectory && File(wt, ".git").isFile) {
                "Worktree $wt is incomplete; inspect it before retrying"
            }
            val gitDir = File(git(wt, "rev-parse", "--absolute-git-dir"))
            val backlink = File(gitDir, "gitdir")
            check(commonGitDir(wt) == commonGitDir(repo) && backlink.isFile &&
                File(backlink.readText().trim()).canonicalFile == File(wt, ".git").canonicalFile) {
                "Worktree $wt points to a different repository or checkout; preserving it"
            }
            check(git(wt, "status", "--porcelain", "--untracked-files=all", "--ignored=matching").isEmpty()) {
                "Worktree $wt has local changes or untracked files; preserving it"
            }
            if (git(wt, "rev-parse", "HEAD") == revision) return wt
        } else {
            check(!Files.exists(wt.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Worktree path $wt already exists without a registration; preserving it"
            }
        }

        check(cacheBase.usableSpace >= 64L * 1024 * 1024) { "Insufficient free space for worktree $wt" }
        val missing = git(repo, "rev-list", "--objects", "--no-object-names", "--missing=print", "--no-walk", revision)
            .lineSequence().filter { it.startsWith('?') }.map { it.drop(1) }.toList()
        if (missing.isNotEmpty()) {
            git(repo, "-c", "fetch.negotiationAlgorithm=noop", "fetch", "origin", "--no-tags",
                "--no-write-fetch-head", "--recurse-submodules=no", "--no-auto-maintenance", "--filter=blob:none", "--stdin",
                token = token, input = missing.joinToString("\n", postfix = "\n"))
        }
        val requiredBytes = git(repo, "ls-tree", "-r", "-l", "-z", revision, token = token)
            .splitToSequence('\u0000').filter { it.isNotEmpty() }.fold(64L * 1024 * 1024) { total, entry ->
                val size = entry.substringBefore('\t').substringAfterLast(' ').toLongOrNull() ?: 0L
                Math.addExact(total, Math.addExact(size, 4096L))
            }
        check(cacheBase.usableSpace >= requiredBytes) {
            "Insufficient free space for worktree $wt: need at least $requiredBytes bytes, available ${cacheBase.usableSpace}"
        }
        if (existing != null) git(repo, "worktree", "remove", wt.absolutePath)
        Files.createDirectories(wt.parentFile.toPath())
        Files.createDirectory(wt.toPath())
        try {
            git(repo, "worktree", "add", "--detach", wt.absolutePath, revision, token = token)
        } catch (failure: Exception) {
            try {
                val partial = worktreeRecord(repo, wt)
                if (partial != null) {
                    val lock = partial.firstOrNull { it == "locked" || it.startsWith("locked ") }
                    check(lock == null || lock == "locked initializing") {
                        "Preserving newly locked worktree $wt: $lock"
                    }
                    git(repo, "worktree", "remove", "--force", "--force", wt.absolutePath)
                }
                check(!wt.exists() || wt.deleteRecursively()) { "Could not remove failed checkout $wt" }
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        return wt
    }

    private fun commonGitDir(repo: File): File = File(git(repo, "rev-parse", "--git-common-dir")).let {
        (if (it.isAbsolute) it else File(repo, it.path)).canonicalFile
    }

    private fun worktreeRecord(repo: File, worktree: File): List<String>? =
        git(repo, "worktree", "list", "--porcelain", "-z")
            .split("\u0000\u0000").map { it.split('\u0000') }
            .firstOrNull { record ->
                record.firstOrNull()?.removePrefix("worktree ")?.let { File(it).canonicalFile } == worktree.canonicalFile
            }

    fun deriveVersion(worktree: File, patchline: String): HytaleVersionDetector.HytaleVersionInfo {
        val full = git(worktree, "rev-parse", "HEAD")
        val tree = git(worktree, "rev-parse", "HEAD^{tree}")
        val short = git(worktree, "rev-parse", "--short", "HEAD")
        val isoDate = git(worktree, "log", "-1", "--format=%cd", "--date=format:%Y-%m-%d")
        val pv = File(worktree, "Protocol/protocol-version.json")
        require(pv.isFile) { "Protocol/protocol-version.json missing in $patchline worktree" }
        val proto = runCatching { protoJson.decodeFromString(ProtocolVersion.serializer(), pv.readText()) }.getOrNull()
        require(proto != null && proto.buildNumber >= 0) { "Protocol/protocol-version.json unparseable in $patchline" }
        val raw = "b${proto.buildNumber}_$isoDate-$short"
        val branch = PATCHLINE_BRANCH[patchline] ?: patchline
        return HytaleVersionDetector.HytaleVersionInfo(patchline, isoDate, short, full, raw, proto.buildNumber, proto.crc, branch, tree)
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
        val wtCanon = worktree.canonicalFile

        val roots = buildList {
            worktree.walkTopDown()
                .onEnter { dir -> dir == worktree || CanonicalRoots.isSafeDirectory(dir, wtCanon) }
                .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && it.invariantSeparatorsPath.endsWith("/src/main/java") }
                .forEach { add(it) }
            File(worktree, "HytaleServer/Protocol/target/generated-sources/java")
                .takeIf { CanonicalRoots.isSafeDirectory(it, wtCanon) }?.let { add(it) }
        }

        for (root in roots) {
            CanonicalRoots.walkSafeFiles(root)
                .filter { it.extension == "java" }
                .forEach { src ->
                    if (!CanonicalRoots.isSafeRegularFile(src, wtCanon)) return@forEach
                    val rel = src.relativeTo(root).invariantSeparatorsPath
                    if (!rel.startsWith("com/")) return@forEach
                    val target = File(stageDir, rel)
                    target.parentFile.mkdirs()
                    if (target.exists()) return@forEach
                    src.copyTo(target, overwrite = false)
                }
        }
        return stageDir
    }
}
