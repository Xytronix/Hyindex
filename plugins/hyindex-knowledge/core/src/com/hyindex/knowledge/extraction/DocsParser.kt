// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.extraction

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

enum class DocsType(val id: String) {
    GUIDE("guide"),
    REFERENCE("reference"),
    FAQ("faq"),
    EXAMPLE("example");
}

data class DocsChunk(
    val id: String,
    val type: DocsType,
    val title: String,
    val filePath: String,
    val relativePath: String,
    val fileHash: String,
    val content: String,
    val category: String?,
    val description: String?,
    val textForEmbedding: String,
    val publishedDate: String? = null,
)

data class DocsFrontmatter(
    val title: String?,
    val description: String?,
    val category: String?,
    val date: String? = null,
)

data class DocsParseResult(
    val chunks: List<DocsChunk>,
    val errors: List<String>,
)


object DocsParser {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()


    fun classifyType(relativePath: String, content: String): DocsType {
        val lower = relativePath.lowercase()
        return when {
            "reference" in lower || "api" in lower -> DocsType.REFERENCE
            "faq" in lower || "troubleshoot" in lower -> DocsType.FAQ
            isExampleHeavy(relativePath, content) -> DocsType.EXAMPLE
            else -> DocsType.GUIDE
        }
    }

    private fun isExampleHeavy(relativePath: String, content: String): Boolean {
        val lower = relativePath.lowercase()
        if ("example" in lower || "sample" in lower || "tutorial" in lower) return true
        val codeBlockCount = "```".toRegex().findAll(content).count() / 2
        return codeBlockCount >= 6
    }


    fun extractFrontmatter(content: String): Pair<DocsFrontmatter, String> {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) {
            return DocsFrontmatter(null, null, null) to content
        }

        val endIdx = trimmed.indexOf("---", 3)
        if (endIdx == -1) {
            return DocsFrontmatter(null, null, null) to content
        }

        val yaml = trimmed.substring(3, endIdx).trim()
        val body = trimmed.substring(endIdx + 3).trimStart()

        val title = extractYamlField(yaml, "title")
        val description = extractYamlField(yaml, "description")
        val category = extractYamlField(yaml, "category")
        val date = extractYamlField(yaml, "date")

        return DocsFrontmatter(title, description, category, date) to body
    }

    private val NEWS_DATE_PATTERN = Regex("""news/(\d{4})/(\d{1,2})(?:/(\d{1,2}))?""")
    private val ISO_DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

    fun extractPublishedDate(frontmatterDate: String?, relativePath: String, content: String): String? {
        frontmatterDate?.let { raw ->
            ISO_DATE_PATTERN.find(raw)?.let { return it.value }
        }
        for (source in listOf(relativePath, content)) {
            NEWS_DATE_PATTERN.find(source)?.let { m ->
                val year = m.groupValues[1]
                val month = m.groupValues[2].padStart(2, '0')
                val day = m.groupValues[3].ifBlank { "1" }.padStart(2, '0')
                return "$year-$month-$day"
            }
        }
        return null
    }

    private fun extractYamlField(yaml: String, field: String): String? {
        val regex = Regex("""^$field:\s*["']?(.+?)["']?\s*$""", RegexOption.MULTILINE)
        return regex.find(yaml)?.groupValues?.get(1)?.trim()
    }


    fun stripMdx(content: String): String {
        var result = content


        result = result.replace(Regex("""^import\s+.+from\s+['"].+['"]\s*;?\s*$""", RegexOption.MULTILINE), "")


        result = result.replace(Regex("""<[A-Z][A-Za-z0-9.]*[^>]*/>\s*"""), "")


        result = result.replace(Regex("""<([A-Z][A-Za-z0-9.]*)[^>]*>"""), "")
        result = result.replace(Regex("""</([A-Z][A-Za-z0-9.]*)>"""), "")


        result = result.replace(Regex("""\n{3,}"""), "\n\n")

        return result.trim()
    }


    fun buildEmbeddingText(chunk: DocsChunk): String {
        return buildString {
            append("Hytale Modding Docs: ${chunk.title}")
            append("\nType: ${chunk.type.id}")
            chunk.category?.let { append("\nCategory: $it") }
            chunk.description?.let { append("\nDescription: $it") }
            append("\nPath: ${chunk.relativePath}")
            append("\n")
            append(keywordsForType(chunk.type))
            append("\n\n")
            append(chunk.content)
        }
    }

    private fun keywordsForType(type: DocsType): String = when (type) {
        DocsType.GUIDE -> "Keywords: tutorial how to guide walkthrough"
        DocsType.REFERENCE -> "Keywords: API reference documentation method class"
        DocsType.FAQ -> "Keywords: FAQ troubleshoot common problem question answer"
        DocsType.EXAMPLE -> "Keywords: example sample code snippet demo"
    }


    data class DocSection(val heading: String?, val body: String, val level: Int? = null)

    fun splitMarkdownSections(body: String): List<DocSection> {
        val headingRe = Regex("""^(#{2,3})\s+(.+?)\s*#*$""")
        val sections = mutableListOf<DocSection>()
        val intro = StringBuilder()
        var heading: String? = null
        var level: Int? = null
        val buf = StringBuilder()
        var started = false
        var inFence = false
        fun flush() {
            val text = buf.toString().trim()
            if (heading != null || text.isNotBlank()) sections.add(DocSection(heading, text, level))
            buf.clear()
        }
        for (line in body.lines()) {
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) inFence = !inFence
            val m = if (inFence) null else headingRe.matchEntire(line.trimEnd())
            if (m != null) {
                if (!started) {
                    val t = intro.toString().trim(); if (t.isNotBlank()) sections.add(DocSection(null, t)); started = true
                } else flush()
                heading = m.groupValues[2].trim()
                level = m.groupValues[1].length
            } else (if (!started) intro else buf).appendLine(line)
        }
        if (started) flush() else { val t = intro.toString().trim(); if (t.isNotBlank()) sections.add(DocSection(null, t)) }
        return sections.ifEmpty { if (body.isBlank()) emptyList() else listOf(DocSection(null, body.trim())) }
    }

    private fun sectionSlug(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "section" }

    private fun sectionChunks(
        baseId: String, type: DocsType, title: String, relativePath: String,
        absolutePath: String, category: String?, description: String?, strippedBody: String,
        publishedDate: String? = null,
    ): List<DocsChunk> {
        var parentH2: String? = null
        return splitMarkdownSections(strippedBody).mapIndexed { i, sec ->
            val nn = i.toString().padStart(2, '0')
            val slug = sectionSlug(sec.heading ?: "overview")
            val sectionContent = if (sec.heading != null) "## ${sec.heading}\n\n${sec.body}".trim() else sec.body
            val breadcrumb = if (sec.level == 3) parentH2?.let { "Parent: $it\n" } else null
            if (sec.level == 2) parentH2 = sec.heading
            val chunk = DocsChunk(
                id = "$baseId#$nn-$slug", type = type, title = title, filePath = absolutePath,
                relativePath = relativePath, fileHash = computeSHA256(sectionContent.toByteArray()),
                content = sectionContent, category = category, description = description, textForEmbedding = "",
                publishedDate = publishedDate,
            )
            val embedding = buildEmbeddingText(chunk)
            chunk.copy(textForEmbedding = if (breadcrumb != null) breadcrumb + embedding else embedding)
        }
    }


    fun fetchTree(owner: String, repo: String, branch: String): List<String> {
        val url = "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Hyindex-Knowledge")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("GitHub Trees API returned ${response.statusCode()}: ${response.body().take(200)}")
        }

        val body = response.body()

        val pathRegex = Regex(""""path"\s*:\s*"([^"]+)"""")
        return pathRegex.findAll(body)
            .map { it.groupValues[1] }
            .filter { it.endsWith(".md") || it.endsWith(".mdx") }
            .toList()
    }

    fun fetchRawContent(owner: String, repo: String, branch: String, path: String): String {
        val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "Hyindex-Knowledge")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("Failed to fetch $path (${response.statusCode()})")
        }
        return response.body()
    }


    fun cacheDir(): File {
        val home = System.getProperty("user.home")
        return File(home, ".hyindex/knowledge/docs")
    }

    fun cachedFile(relativePath: String): File {
        return File(cacheDir(), relativePath.replace('/', File.separatorChar))
    }

    fun readCache(relativePath: String): String? {
        val file = cachedFile(relativePath)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    fun writeCache(relativePath: String, content: String) {
        val file = cachedFile(relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }


    fun parseDocs(
        repoSlug: String = "HytaleModding/site",
        branch: String = "main",
        locale: String = "en",
        onProgress: ((current: Int, total: Int, file: String) -> Unit)? = null,
    ): DocsParseResult {
        val parts = repoSlug.split("/")
        if (parts.size != 2) {
            return DocsParseResult(emptyList(), listOf("Invalid docsGithubRepo format: $repoSlug"))
        }
        val owner = parts[0]
        val repo = parts[1]

        val chunks = mutableListOf<DocsChunk>()
        val errors = mutableListOf<String>()

        val allPaths: List<String>
        try {
            allPaths = fetchTree(owner, repo, branch)
        } catch (e: Exception) {
            return DocsParseResult(emptyList(), listOf("Failed to fetch GitHub file tree: ${e.message}"))
        }

        val docsPaths = allPaths.filter { path ->
            path.startsWith("content/docs/$locale/")
                && !path.startsWith("content/docs/$locale/contributing/")
                && path != "content/docs/$locale/index.mdx"
                && path != "content/docs/$locale/meta.json"
        }

        for ((idx, path) in docsPaths.withIndex()) {
            onProgress?.invoke(idx + 1, docsPaths.size, path.substringAfterLast('/'))
            try {
                val rawContent = try {
                    val fresh = fetchRawContent(owner, repo, branch, path)
                    writeCache(path, fresh)
                    fresh
                } catch (e: Exception) {
                    System.err.println("Fetch failed for $path, trying cache: ${e.message}")
                    readCache(path) ?: throw e
                }

                if (rawContent.isBlank()) continue

                val (frontmatter, body) = extractFrontmatter(rawContent)
                val strippedBody = stripMdx(body)
                val relativePath = path.removePrefix("content/docs/$locale/")
                val type = classifyType(relativePath, strippedBody)
                val title = frontmatter.title ?: relativePath.substringAfterLast('/').removeSuffix(".md").removeSuffix(".mdx")
                    .replace('-', ' ').replaceFirstChar { it.uppercase() }
                val category = frontmatter.category ?: relativePath.split('/').firstOrNull()
                val publishedDate = extractPublishedDate(frontmatter.date, path, rawContent)

                chunks += sectionChunks(
                    "modding:$relativePath", type, title, relativePath,
                    cachedFile(path).absolutePath, category, frontmatter.description, strippedBody,
                    publishedDate = publishedDate,
                )
            } catch (e: Exception) {
                errors.add("$path: ${e.message}")
            }
        }

        return DocsParseResult(chunks, errors)
    }


    fun parseLocalMarkdown(
        roots: List<java.io.File>,
        onProgress: ((current: Int, total: Int, file: String) -> Unit)? = null,
    ): DocsParseResult {
        val chunks = mutableListOf<DocsChunk>()
        val errors = mutableListOf<String>()
        val validRoots = roots.filter { it.isDirectory }
        val files = validRoots
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && (it.extension == "md" || it.extension == "mdx") }
                    .filterNot { isExcludedDocPath(it.relativeTo(root).path.replace(java.io.File.separatorChar, '/')) }
                    .map { root to it }
            }
        for ((idx, pair) in files.withIndex()) {
            val (root, file) = pair
            onProgress?.invoke(idx + 1, files.size, file.name)
            try {
                val raw = file.readText()
                if (raw.isBlank()) continue
                val relativePath = file.relativeTo(root).path.replace(java.io.File.separatorChar, '/')
                val rootIndex = validRoots.indexOf(root)
                val source = sourceForRoot(root)
                val (frontmatter, body) = extractFrontmatter(raw)
                val strippedBody = stripMdx(body)
                val type = classifyType(relativePath, strippedBody)
                val title = frontmatter.title
                    ?: firstH1(strippedBody)
                    ?: relativePath.substringAfterLast('/').removeSuffix(".md").removeSuffix(".mdx")
                        .replace('-', ' ').replaceFirstChar { it.uppercase() }
                val category = frontmatter.category ?: categoryForPath(relativePath)
                val baseId = if (source == "repo") "repo:$relativePath" else "$source:$rootIndex:$relativePath"
                val publishedDate = extractPublishedDate(frontmatter.date, file.absolutePath, raw)
                chunks += sectionChunks(
                    baseId, type, title, relativePath,
                    file.absolutePath, category, frontmatter.description, strippedBody,
                    publishedDate = publishedDate,
                )
            } catch (e: Exception) {
                errors.add("Failed to parse ${file.name}: ${e.message}")
            }
        }
        return DocsParseResult(chunks, errors)
    }

    private fun sourceForRoot(root: java.io.File): String {
        val p = root.absolutePath
        return when {
            p.contains("/cache/support") -> "support"
            p.contains("/cache/blog")    -> "blog"
            p.contains("/re-docs")        -> "re"
            p.contains("/worktrees/")     -> "repo"
            else -> "local"
        }
    }

    private fun isExcludedDocPath(relativePath: String): Boolean =
        "/Tools/Standalone/DocGen/" in relativePath || "/site/" in relativePath || "/target/" in relativePath

    private fun firstH1(body: String): String? =
        Regex("""^#\s+(.+)$""", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun categoryForPath(relativePath: String): String? {
        val parent = relativePath.substringBeforeLast('/', "").substringAfterLast('/')
        return parent.takeIf { it.isNotBlank() } ?: relativePath.split('/').firstOrNull()
    }

    private fun computeSHA256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
