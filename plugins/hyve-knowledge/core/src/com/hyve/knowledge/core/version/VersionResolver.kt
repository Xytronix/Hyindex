// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.version

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

object VersionResolver {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class MetaInfo(
        val patchline: String? = null,
        val buildNumber: Int? = null,
        val date: String? = null,
        val indexedAt: String? = null,
    )

    fun readMeta(metaFile: File): MetaInfo? =
        runCatching { json.decodeFromString(MetaInfo.serializer(), metaFile.readText()) }.getOrNull()

    private val newestFirst =
        compareByDescending<Pair<String, MetaInfo>>({ it.second.buildNumber ?: -1 })
            .thenByDescending { it.second.date ?: "" }
            .thenByDescending { it.second.indexedAt ?: "" }

    private fun scan(basePath: File): List<Pair<String, MetaInfo>> {
        val dirs = File(basePath, "versions").listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            if (!File(dir, "knowledge.db").isFile) return@mapNotNull null
            val metaFile = File(dir, "version_meta.json")
            if (!metaFile.isFile) return@mapNotNull null
            val meta = readMeta(metaFile) ?: return@mapNotNull null
            if (meta.patchline == null) return@mapNotNull null
            dir.name to meta
        }
    }

    fun resolveAll(basePath: File): Map<String, String> =
        scan(basePath)
            .groupBy { it.second.patchline!! }
            .mapValues { (_, es) -> es.sortedWith(newestFirst).first().first }

    fun latestSlug(basePath: File, patchline: String): String? = resolveAll(basePath)[patchline]

    fun listSlugs(basePath: File, patchline: String): List<String> =
        scan(basePath)
            .filter { it.second.patchline == patchline }
            .sortedWith(newestFirst)
            .map { it.first }

    fun resolveSlug(basePath: File, patchline: String, version: String): String? {
        val candidates = scan(basePath)
            .filter { it.second.patchline == patchline }
            .sortedWith(newestFirst)
        return candidates.firstOrNull { (slug, meta) ->
            slug == version ||
                slug == "${patchline}_$version" ||
                slug.removePrefix("${patchline}_") == version ||
                meta.buildNumber?.let { version == "b$it" || version == "$it" } == true ||
                meta.date == version ||
                slug.contains(version)
        }?.first
    }
}
