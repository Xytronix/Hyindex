// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.source

import com.hyindex.knowledge.core.logging.LogProvider
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse


object SupportDocsSource {
    val ARTICLE_URLS: List<String> = listOf(
        "https://support.hytale.com/hc/en-us/articles/45328341414043-Server-Provider-Authentication-Guide",
        "https://support.hytale.com/hc/en-us/articles/45326769420827-Hytale-Server-Manual",
        "https://support.hytale.com/hc/en-us/articles/45324238811291-Joining-Friends",
        "https://support.hytale.com/hc/en-us/articles/45419578597403-Slow-Connection-World-Not-Loading-on-Server",
    )

    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()


    fun fileNameFor(url: String): String {
        val slug = url.substringAfterLast('/').ifBlank { "article" }
        return slug.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".md"
    }


    fun fetchInto(cacheRoot: File, log: LogProvider, force: Boolean = false): File {
        val out = File(cacheRoot, "support").apply { mkdirs() }
        var ok = 0; var cached = 0
        for (url in ARTICLE_URLS) {
            val outFile = File(out, fileNameFor(url))
            if (outFile.exists() && !force) { cached++; ok++; continue }
            val text = runCatching { JavaDocSource.htmlToText(getString(url)) }.getOrNull()
            if (text == null) { log.warn("support docs: failed to fetch $url"); continue }
            outFile.writeText("# ${url.substringAfterLast('/')}\n\n$text\n")
            ok++
        }
        log.info("Support docs: fetched ${ok - cached}, cached(skipped) $cached (total ok $ok/${ARTICLE_URLS.size})")
        return out
    }

    private fun getString(url: String): String {
        val resp = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() == 200) { "HTTP ${resp.statusCode()} for $url" }
        return resp.body()
    }
}
