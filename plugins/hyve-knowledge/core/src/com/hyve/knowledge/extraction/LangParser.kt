// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.extraction

object LangParser {

    fun parse(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val stripped = if (text.startsWith('﻿')) text.substring(1) else text
        val lines = stripped.lines()
        var i = 0
        while (i < lines.size) {
            var line = lines[i]
            i++
            while (line.endsWith("\\")) {
                line = line.dropLast(1).trimEnd() + " " + (if (i < lines.size) lines[i].trimStart() else "")
                i++
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 1) continue
            val key = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    fun derivePrefix(path: String): String {
        val langMarker = "Languages/"
        val markerIdx = path.indexOf(langMarker)
        if (markerIdx < 0) return path.removeSuffix(".lang").replace('/', '.')
        val afterLanguages = path.substring(markerIdx + langMarker.length)
        val slashAfterLocale = afterLanguages.indexOf('/')
        if (slashAfterLocale < 0) return afterLanguages.removeSuffix(".lang")
        val afterLocale = afterLanguages.substring(slashAfterLocale + 1)
        return afterLocale.removeSuffix(".lang").replace('/', '.')
    }
}
