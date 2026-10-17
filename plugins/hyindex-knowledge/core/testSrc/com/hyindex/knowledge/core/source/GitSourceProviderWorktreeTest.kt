package com.hyindex.knowledge.core.source

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class GitSourceProviderWorktreeTest {
    @TempDir
    lateinit var root: File

    private fun git(dir: File, vararg args: String): String {
        val proc = ProcessBuilder(listOf("git", *args)).directory(dir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out.trim()
    }

    private fun repository(filtered: Boolean = false): File {
        val repo = File(root, "repo").apply { mkdir() }
        git(repo, "init", "-q", "-b", "release")
        git(repo, "config", "user.name", "Worktree test")
        git(repo, "config", "user.email", "test@example.invalid")
        File(repo, "source.txt").writeText("first revision\n")
        File(repo, ".gitignore").writeText("ignored.txt\n")
        if (filtered) File(repo, ".gitattributes").writeText("*.txt filter=broken\n")
        git(repo, "add", ".")
        git(repo, "commit", "-qm", "first")
        git(repo, "update-ref", "refs/remotes/origin/release", "HEAD")
        return repo
    }

    private fun worktree(repo: File): File = GitSourceProvider.worktreeFor(repo, "release", root)

    @Test
    fun `reuses a clean worktree at the requested commit without checking files out again`() {
        val repo = repository()
        val wt = worktree(repo)
        val source = File(wt, "source.txt")
        val timestamp = FileTime.fromMillis(1_000_000)
        Files.setLastModifiedTime(source.toPath(), timestamp)

        assertThat(worktree(repo).canonicalFile).isEqualTo(wt.canonicalFile)
        assertThat(Files.getLastModifiedTime(source.toPath())).isEqualTo(timestamp)
    }

    @Test
    fun `preserves tracked edits instead of force removing them`() {
        val repo = repository()
        val wt = worktree(repo)
        File(wt, "source.txt").writeText("local edits\n")

        assertThrows<IllegalStateException> { worktree(repo) }

        assertThat(File(wt, "source.txt").readText()).isEqualTo("local edits\n")
    }

    @Test
    fun `preserves untracked files instead of force removing them`() {
        val repo = repository()
        val wt = worktree(repo)
        File(wt, "local.txt").writeText("local content")

        assertThrows<IllegalStateException> { worktree(repo) }

        assertThat(File(wt, "local.txt").readText()).isEqualTo("local content")
    }

    @Test
    fun `preserves ignored files instead of force removing them`() {
        val repo = repository()
        val wt = worktree(repo)
        File(wt, "ignored.txt").writeText("local content")

        assertThrows<IllegalStateException> { worktree(repo) }

        assertThat(File(wt, "ignored.txt").readText()).isEqualTo("local content")
    }

    @Test
    fun `preserves a user locked worktree with an actionable error`() {
        val repo = repository()
        val wt = worktree(repo)
        git(repo, "worktree", "lock", "--reason", "user maintenance", wt.absolutePath)

        val failure = assertThrows<IllegalStateException> { worktree(repo) }

        assertThat(failure.message).contains("locked", "user maintenance")
        assertThat(git(repo, "worktree", "list", "--porcelain")).contains("locked user maintenance")
        assertThat(File(wt, "source.txt").readText()).isEqualTo("first revision\n")
    }

    @Test
    fun `preserves a previously interrupted initializing worktree`() {
        val repo = repository()
        val wt = File(root, "worktrees/release")
        git(repo, "worktree", "add", "--detach", "--no-checkout", wt.absolutePath, "HEAD")
        git(repo, "worktree", "lock", "--reason", "initializing", wt.absolutePath)

        val failure = assertThrows<IllegalStateException> { worktree(repo) }

        assertThat(failure.message).contains("locked", "initializing")
        assertThat(git(repo, "worktree", "list", "--porcelain")).contains("locked initializing")
        assertThat(File(wt, ".git")).exists()
        assertThat(File(wt, "source.txt")).doesNotExist()
    }

    @Test
    fun `preserves an existing unregistered directory`() {
        val repo = repository()
        val wt = File(root, "worktrees/release").apply { mkdirs() }
        File(wt, "local.txt").writeText("partial checkout")

        assertThrows<IllegalStateException> { worktree(repo) }

        assertThat(File(wt, "local.txt").readText()).isEqualTo("partial checkout")
    }

    @Test
    fun `checks out an updated revision when the old worktree is clean`() {
        val repo = repository()
        val wt = worktree(repo)
        File(repo, "source.txt").writeText("second revision\n")
        git(repo, "commit", "-qam", "second")
        git(repo, "update-ref", "refs/remotes/origin/release", "HEAD")

        worktree(repo)

        assertThat(File(wt, "source.txt").readText()).isEqualTo("second revision\n")
        assertThat(git(wt, "rev-parse", "HEAD")).isEqualTo(git(repo, "rev-parse", "HEAD"))
    }

    @Test
    fun `refuses insufficient space before creating a worktree`() {
        val repo = repository()
        val fullCache = object : File(root.absolutePath) {
            override fun getUsableSpace(): Long = 0
        }

        val failure = assertThrows<IllegalStateException> {
            GitSourceProvider.worktreeFor(repo, "release", fullCache)
        }

        assertThat(failure.message).containsIgnoringCase("space")
        assertThat(File(root, "worktrees/release")).doesNotExist()
        assertThat(git(repo, "worktree", "list", "--porcelain")).doesNotContain("worktrees/release")
    }

    @Test
    fun `insufficient space preserves the old clean checkout`() {
        val repo = repository()
        val wt = worktree(repo)
        File(repo, "source.txt").writeText("second revision\n")
        git(repo, "commit", "-qam", "second")
        git(repo, "update-ref", "refs/remotes/origin/release", "HEAD")
        val fullCache = object : File(root.absolutePath) {
            override fun getUsableSpace(): Long = 0
        }

        assertThrows<IllegalStateException> {
            GitSourceProvider.worktreeFor(repo, "release", fullCache)
        }

        assertThat(File(wt, "source.txt").readText()).isEqualTo("first revision\n")
    }

    @Test
    fun `does not prune unrelated worktree registrations`() {
        val repo = repository()
        val unrelated = File(root, "unrelated")
        git(repo, "worktree", "add", "--detach", unrelated.absolutePath, "HEAD")
        unrelated.deleteRecursively()

        worktree(repo)

        assertThat(git(repo, "worktree", "list", "--porcelain")).contains(unrelated.canonicalPath)
    }

    private fun partialClone(): Pair<File, File> {
        val origin = repository()
        repeat(20) { File(origin, "blob$it.txt").writeText("unique blob $it\n") }
        git(origin, "add", ".")
        git(origin, "commit", "-qm", "many blobs")
        git(origin, "config", "uploadpack.allowFilter", "true")
        val clone = File(root, "partial")
        git(root, "clone", "--filter=blob:none", "--no-checkout", origin.toPath().toUri().toString(), clone.absolutePath)
        val trace = File(root, "fetch.trace")
        git(clone, "config", "remote.origin.uploadpack", "env GIT_TRACE=${trace.absolutePath} git-upload-pack")
        return clone to trace
    }

    @Test
    fun `hydrates a blobless checkout in one bulk fetch instead of one fetch per blob`() {
        val (clone, trace) = partialClone()

        val wt = worktree(clone)

        assertThat(File(wt, "blob19.txt").readText()).isEqualTo("unique blob 19\n")
        assertThat(trace.readLines().count { it.contains("built-in: git upload-pack") }).isEqualTo(1)
    }

    @Test
    fun `checks capacity before hydrating a blobless checkout`() {
        val (clone, trace) = partialClone()
        val fullCache = object : File(root.absolutePath) {
            override fun getUsableSpace(): Long = 0
        }

        assertThrows<IllegalStateException> {
            GitSourceProvider.worktreeFor(clone, "release", fullCache)
        }

        assertThat(trace).doesNotExist()
        assertThat(File(root, "worktrees/release")).doesNotExist()
    }

    @Test
    fun `expanded blob size is included in the capacity check`() {
        val repo = repository()
        File(repo, "large.txt").writeBytes(ByteArray(2 * 1024 * 1024) { 65 })
        git(repo, "add", ".")
        git(repo, "commit", "-qm", "large compressed blob")
        git(repo, "update-ref", "refs/remotes/origin/release", "HEAD")
        val limitedCache = object : File(root.absolutePath) {
            override fun getUsableSpace(): Long = 65L * 1024 * 1024
        }

        assertThrows<IllegalStateException> {
            GitSourceProvider.worktreeFor(repo, "release", limitedCache)
        }

        assertThat(File(root, "worktrees/release")).doesNotExist()
    }

    @Test
    fun `rejects a registered checkout whose git file points to a foreign repository`() {
        val repo = repository()
        val wt = worktree(repo)
        val foreignRepo = File(root, "foreign-repo")
        val foreignWorktree = File(root, "foreign-worktree")
        git(root, "clone", "--no-checkout", repo.absolutePath, foreignRepo.absolutePath)
        git(foreignRepo, "worktree", "add", "--detach", foreignWorktree.absolutePath, "HEAD")
        val foreignPointer = File(foreignWorktree, ".git").readText()
        File(wt, ".git").writeText(foreignPointer)

        assertThrows<IllegalStateException> { worktree(repo) }

        assertThat(File(wt, ".git").readText()).isEqualTo(foreignPointer)
        assertThat(File(foreignWorktree, "source.txt").readText()).isEqualTo("first revision\n")
    }

    @Test
    fun `rejects a checkout whose git file points to a different worktree of the same repository`() {
        val repo = repository()
        val wt = worktree(repo)
        val sibling = File(root, "sibling")
        git(repo, "worktree", "add", "--detach", sibling.absolutePath, "HEAD")
        val siblingPointer = File(sibling, ".git").readText()
        File(wt, ".git").writeText(siblingPointer)

        assertThrows<IllegalStateException> { worktree(repo) }

        assertThat(File(wt, ".git").readText()).isEqualTo(siblingPointer)
        assertThat(File(sibling, "source.txt").readText()).isEqualTo("first revision\n")
    }

    @Test
    fun `removes only its own interrupted checkout and preserves the original failure`() {
        val repo = repository(filtered = true)
        git(repo, "config", "filter.broken.smudge", """
            echo CHECKOUT_INTERRUPTED >&2; kill -KILL "${'$'}(ps -o ppid= -p "${'$'}PPID" | tr -d ' ')"; exit 1
        """.trimIndent())
        git(repo, "config", "filter.broken.required", "true")

        val failure = assertThrows<IllegalStateException> { worktree(repo) }

        assertThat(failure.message).contains("CHECKOUT_INTERRUPTED")
        assertThat(File(root, "worktrees/release")).doesNotExist()
        assertThat(git(repo, "worktree", "list", "--porcelain")).doesNotContain("worktrees/release")
        git(repo, "config", "filter.broken.smudge", "cat")
        assertThat(File(worktree(repo), "source.txt").readText()).isEqualTo("first revision\n")
    }
}
