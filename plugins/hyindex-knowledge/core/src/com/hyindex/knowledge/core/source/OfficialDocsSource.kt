// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.source

import com.hyindex.knowledge.core.logging.LogProvider
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.ArrayDeque

/** Fetches the human-authored pages from the official Hytale documentation portal. */
object OfficialDocsSource {
    const val RELEASE_BASE_URL = "https://docs.hytale.com"
    const val PRE_RELEASE_BASE_URL = "https://pre-release.docs.hytale.com"

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun baseUrlFor(patchline: String): String =
        if (patchline == "pre-release") PRE_RELEASE_BASE_URL else RELEASE_BASE_URL

    fun fetchInto(
        cacheRoot: File,
        patchline: String,
        log: LogProvider,
        baseUrl: String = baseUrlFor(patchline),
        maxPages: Int = 256,
    ): File {
        val output = File(cacheRoot, "official-docs/$patchline").apply { mkdirs() }
        val normalizedBase = baseUrl.trimEnd('/')
        val rootHtml = runCatching { getString("$normalizedBase/") }.getOrElse { error ->
            log.warn("official docs: failed to fetch $normalizedBase/: ${error.message}")
            return output
        }

        val pending = ArrayDeque<String>()
        val seen = linkedSetOf<String>()
        pending.add("$normalizedBase/")
        discoverPageUrls(rootHtml, normalizedBase).forEach(pending::add)

        val retained = mutableSetOf<File>()
        var fetched = 0
        var failed = 0
        while (pending.isNotEmpty() && seen.size < maxPages) {
            val url = pending.removeFirst()
            if (!seen.add(url)) continue
            val target = cacheFileFor(output, URI.create(url).path)
            retained += target.canonicalFile
            val html = if (url == "$normalizedBase/") rootHtml else runCatching { getString(url) }.getOrNull()
            if (html == null) {
                failed++
                continue
            }
            discoverPageUrls(html, normalizedBase).forEach { if (it !in seen) pending.add(it) }
            val markdown = pageToMarkdown(html, url, patchline)
            if (markdown.isBlank()) {
                failed++
                continue
            }
            target.parentFile?.mkdirs()
            if (!target.exists() || target.readText() != markdown) target.writeText(markdown)
            fetched++
        }

        output.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.canonicalFile !in retained }
            .forEach(File::delete)
        log.info("Official docs ($patchline): $fetched pages fetched, $failed failed")
        return output
    }

    internal fun discoverPageUrls(html: String, baseUrl: String): List<String> {
        val base = URI.create(baseUrl.trimEnd('/') + "/")
        val rawLinks = sequence {
            for (match in HREF.findAll(html)) yield(match.groupValues[1])
            for (match in TREE_URL.findAll(html)) yield(match.groupValues[1])
        }
        return rawLinks.mapNotNull { raw ->
            val decoded = decodeEntities(raw)
            if (decoded.startsWith("#") || decoded.startsWith("mailto:") || decoded.startsWith("javascript:")) {
                return@mapNotNull null
            }
            val resolved = runCatching { base.resolve(decoded) }.getOrNull() ?: return@mapNotNull null
            if (!resolved.host.equals(base.host, ignoreCase = true)) return@mapNotNull null
            val path = normalizedPagePath(resolved.path) ?: return@mapNotNull null
            URI(resolved.scheme, resolved.userInfo, resolved.host, resolved.port, path, null, null).toString()
        }.distinct().sorted().toList()
    }

    internal fun pageToMarkdown(html: String, sourceUrl: String, patchline: String): String {
        val article = ARTICLE.find(html)?.groupValues?.get(1) ?: return ""
        val title = H1.find(article)?.groupValues?.get(1)
            ?.let(JavaDocSource::htmlToText)
            ?.ifBlank { null }
            ?: TITLE.find(html)?.groupValues?.get(1)?.let(JavaDocSource::htmlToText)?.substringBefore(" | ")
            ?: URI.create(sourceUrl).path.trim('/').substringAfterLast('-').ifBlank { "Hytale Documentation" }
        val description = META_DESCRIPTION.find(html)?.groupValues?.get(1)
            ?.let(::decodeEntities)
            ?.trim()
            .orEmpty()
        val version = DOCS_VERSION.find(html)?.groupValues?.get(1)
            ?.let(JavaDocSource::htmlToText)
            ?.trim()
            .orEmpty()
        val body = articleToMarkdown(article)
        if (body.isBlank()) return ""
        return buildString {
            appendLine("---")
            appendLine("title: \"${yamlEscape(title)}\"")
            if (description.isNotBlank()) appendLine("description: \"${yamlEscape(description)}\"")
            appendLine("category: official")
            appendLine("---")
            appendLine()
            appendLine("Source: $sourceUrl")
            appendLine("Patchline: $patchline")
            if (version.isNotBlank()) appendLine("Documentation version: $version")
            appendLine()
            append(body.trim())
            appendLine()
        }
    }

    private fun articleToMarkdown(articleHtml: String): String {
        var html = articleHtml
            .replace(SCRIPT_OR_STYLE, " ")
            .replace(BUTTON, " ")
            .replace(SVG, " ")
        html = PRE.replace(html) { match ->
            val code = JavaDocSource.htmlToText(match.groupValues[1])
            "\n```\n$code\n```\n"
        }
        html = HEADING.replace(html) { match ->
            val level = match.groupValues[1]
            val text = JavaDocSource.htmlToText(match.groupValues[2])
            "\n${"#".repeat(level.toInt())} $text\n"
        }
        html = LINK.replace(html) { match ->
            val href = decodeEntities(match.groupValues[1])
            val text = JavaDocSource.htmlToText(match.groupValues[2])
            if (text.isBlank() || href.startsWith("#")) text else "[$text]($href)"
        }
        html = html
            .replace(LIST_ITEM_OPEN, "\n- ")
            .replace(LIST_ITEM_CLOSE, "\n")
            .replace(BLOCK_END, "\n")
            .replace(BREAK, "\n")
        return JavaDocSource.htmlToText(html)
            .lineSequence()
            .map(String::trimEnd)
            .filterNot { it == "Copy Anchor Link" }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun cacheFileFor(root: File, path: String): File {
        val normalized = path.trim('/').ifBlank { "index" }
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, "$normalized.md").canonicalFile
        require(target.toPath().startsWith(canonicalRoot.toPath())) { "Official docs path escapes cache root: $path" }
        return target
    }

    private fun normalizedPagePath(rawPath: String): String? {
        val path = rawPath.ifBlank { "/" }
        if (path.startsWith("/_next/") || path.startsWith("/images/") || path.startsWith("/api") || path.startsWith("/assets")) {
            return null
        }
        val last = path.substringAfterLast('/')
        if ('.' in last) return null
        return if (path == "/") path else "/${path.trim('/')}"
    }

    private fun getString(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "Hyindex-Knowledge")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
        return response.body()
    }

    private fun yamlEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

    private val HREF = Regex("""(?is)href=["']([^"']+)["']""")
    private val TREE_URL = Regex("""["']url["']\s*:\s*["']([^"']+)["']""")
    private val ARTICLE = Regex("""(?is)<article\b[^>]*id=["']nd-page["'][^>]*>(.*?)</article>""")
    private val H1 = Regex("""(?is)<h1\b[^>]*>(.*?)</h1>""")
    private val TITLE = Regex("""(?is)<title>(.*?)</title>""")
    private val META_DESCRIPTION = Regex("""(?is)<meta\s+name=["']description["']\s+content=["']([^"']*)["'][^>]*>""")
    private val DOCS_VERSION = Regex("""(?is)docs-version-number["'][^>]*>([^<]+)<""")
    private val SCRIPT_OR_STYLE = Regex("""(?is)<(script|style)\b[^>]*>.*?</\1>""")
    private val BUTTON = Regex("""(?is)<button\b[^>]*>.*?</button>""")
    private val SVG = Regex("""(?is)<svg\b[^>]*>.*?</svg>""")
    private val PRE = Regex("""(?is)<pre\b[^>]*>(.*?)</pre>""")
    private val HEADING = Regex("""(?is)<h([1-3])\b[^>]*>(.*?)</h\1>""")
    private val LINK = Regex("""(?is)<a\b[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""")
    private val LIST_ITEM_OPEN = Regex("""(?is)<li\b[^>]*>""")
    private val LIST_ITEM_CLOSE = Regex("""(?is)</li>""")
    private val BLOCK_END = Regex("""(?is)</(?:p|div|section|ul|ol|figure|blockquote)>""")
    private val BREAK = Regex("""(?is)<br\s*/?>|<hr\s*/?>""")
}
