package com.hyindex.knowledge.core.search

import com.hyindex.knowledge.index.FtsTokenizer

object Snippets {


    fun truncate(text: String, max: Int): String {
        if (max <= 0 || text.length <= max) return text
        val slice = text.substring(0, max)
        val lastNewline = slice.lastIndexOf('\n')
        val cut = if (lastNewline > 0) slice.substring(0, lastNewline) else slice
        return "$cut\n…"
    }

    fun window(text: String, query: String, max: Int): String {
        if (max <= 0 || text.length <= max) return text
        val terms = termsOf(query)
        if (terms.isEmpty()) return truncate(text, max)
        val lower = text.lowercase()
        var match = -1
        for (term in terms) {
            val at = lower.indexOf(term)
            if (at >= 0 && (match < 0 || at < match)) match = at
        }
        if (match < 0 || match < max) return truncate(text, max)
        val start = (match - max / 2).coerceIn(0, text.length - max)
        val end = (start + max).coerceAtMost(text.length)
        val body = text.substring(start, end)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return "$prefix$body$suffix"
    }

    private fun termsOf(query: String): List<String> {
        val terms = LinkedHashSet<String>()
        for (token in query.split(Regex("[^A-Za-z0-9]+"))) {
            if (token.isBlank()) continue
            terms.add(token.lowercase())
            terms.addAll(FtsTokenizer.subWordsOf(token))
        }
        return terms.toList()
    }
}
