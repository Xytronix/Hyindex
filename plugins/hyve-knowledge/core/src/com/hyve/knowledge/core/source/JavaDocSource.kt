// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.source

object JavaDocSource {

    fun htmlToText(html: String): String {
        val noScript = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        val noTags = noScript.replace(Regex("(?s)<[^>]+>"), " ")
        val decoded = noTags
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return decoded.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
    }
}
