// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.source

import com.hyindex.knowledge.core.logging.LogProvider
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class BlogSection(val title: String, val text: String)


object BlogDocsSource {
    const val BASE = "https://hytale.com"
    const val INDEX_URL = "$BASE/news"
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()


    fun postUrls(indexHtml: String): List<String> {
        val re = Regex("href=\"((?:https://hytale\\.com)?/news/\\d+/\\d+/[A-Za-z0-9-]+)\"")
        return re.findAll(indexHtml).map { it.groupValues[1] }
            .map { if (it.startsWith("http")) it else BASE + it }
            .distinct().toList()
    }

    fun fileNameFor(url: String): String =
        url.substringAfterLast('/').ifBlank { "post" }.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".md"


    fun splitSections(articleHtml: String): List<BlogSection> {
        val headingRe = Regex("(?is)<h[23][^>]*>(.*?)</h[23]>")
        val matches = headingRe.findAll(articleHtml).toList()
        if (matches.isEmpty()) {
            val t = JavaDocSource.htmlToText(articleHtml)
            return if (t.isBlank()) emptyList() else listOf(BlogSection("Overview", t))
        }
        val sections = ArrayList<BlogSection>()

        val intro = JavaDocSource.htmlToText(articleHtml.substring(0, matches.first().range.first))
        if (intro.isNotBlank()) sections.add(BlogSection("Overview", intro))
        for ((i, m) in matches.withIndex()) {
            val title = JavaDocSource.htmlToText(m.groupValues[1]).trim().ifBlank { "Section ${i + 1}" }
            val bodyStart = m.range.last + 1
            val bodyEnd = if (i + 1 < matches.size) matches[i + 1].range.first else articleHtml.length
            val body = JavaDocSource.htmlToText(articleHtml.substring(bodyStart, bodyEnd)).trim()
            if (body.isNotBlank() || title.isNotBlank()) sections.add(BlogSection(title, body))
        }
        return sections.filter { !isNoisySection(it) }
    }

    private val NOISE_KEYWORDS = listOf(
        "security check", "checking your browser", "just a moment",
        "cloudflare", "enable javascript", "verify you are human", "ray id"
    )

    private fun isNoisySection(sec: BlogSection): Boolean {
        if (sec.text.isBlank()) return true
        val titleLower = sec.title.lowercase()
        val textLower = sec.text.lowercase()
        return NOISE_KEYWORDS.any { it in titleLower || it in textLower }
    }

    private fun sectionSlug(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)


    fun fetchInto(cacheRoot: File, log: LogProvider, maxPosts: Int = 300, force: Boolean = false): File {
        val out = File(cacheRoot, "blog").apply { mkdirs() }
        val index = runCatching { getString(INDEX_URL) }.getOrNull()
        if (index == null) { log.warn("blog: failed to fetch $INDEX_URL"); return out }
        val urls = postUrls(index).take(maxPosts)
        log.info("Blog: ${urls.size} news/patch-notes posts")
        var ok = 0
        for (u in urls) {
            val html = runCatching { getString(u) }.getOrNull() ?: continue
            val postSlug = u.substringAfterLast('/').ifBlank { "post" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

            out.listFiles { f -> f.name.startsWith("${postSlug}__") && f.name.endsWith(".md") }
                ?.forEach { it.delete() }
            val sections = splitSections(html)
            for ((idx, sec) in sections.withIndex()) {
                val nn = idx.toString().padStart(2, '0')
                val slug = sectionSlug(sec.title)
                val fileName = "${postSlug}__${nn}-${slug}.md"
                File(out, fileName).writeText("# ${u.removePrefix("$BASE/")} — ${sec.title}\n\n${sec.text}\n")
            }
            ok++
        }
        log.info("Blog: $ok/${urls.size} posts fetched")
        return out
    }

    private fun getString(url: String): String {
        val resp = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() == 200) { "HTTP ${resp.statusCode()} for $url" }
        return resp.body()
    }
}
