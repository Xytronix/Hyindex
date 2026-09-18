// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.version

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

object VersionResolver {
    private val SAFE_TOKEN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class MetaInfo(
        val patchline: String? = null,
        val branch: String? = null,
        val buildNumber: Int? = null,
        val date: String? = null,
        val indexedAt: String? = null,
    )

    fun isSafeToken(value: String): Boolean {
        if (value.isEmpty() || value.contains("..")) return false
        if (value.any { it == '/' || it == '\\' || it == '\u0000' }) return false
        if (value.startsWith("/") || value.startsWith("\\")) return false
        return SAFE_TOKEN.matches(value)
    }

    fun isSafePatchline(value: String): Boolean =
        value == "release" || value == "pre-release" || isSafeToken(value)

    fun containedUnder(root: File, candidate: File): Boolean {
        val rootCanon = root.canonicalFile
        val child = candidate.canonicalFile
        val prefix = rootCanon.path
        return child == rootCanon || child.path.startsWith(prefix + File.separator)
    }

    fun existingVersionDir(basePath: File, slug: String): File? {
        if (!isSafeToken(slug)) return null
        val versions = File(basePath, "versions")
        val dir = File(versions, slug)
        if (!containedUnder(versions, dir) || !dir.isDirectory) return null
        return dir
    }

    fun existingKnowledgeDb(basePath: File, slug: String): File? {
        val dir = existingVersionDir(basePath, slug) ?: return null
        val db = File(dir, "knowledge.db")
        if (!db.isFile || !containedUnder(dir, db) || db.name != "knowledge.db") return null
        return db
    }

    fun existingSnapshot(basePath: File, patchline: String, version: String): File? {
        if (!isSafeToken(patchline) || !isSafeToken(version)) return null
        val snapshots = File(basePath, "snapshots")
        val file = File(File(snapshots, patchline), "$version.json")
        if (!containedUnder(snapshots, file) || !file.isFile) return null
        return file
    }

    fun readMeta(metaFile: File): MetaInfo? =
        runCatching { json.decodeFromString(MetaInfo.serializer(), metaFile.readText()) }.getOrNull()

    internal val newestMeta: Comparator<MetaInfo> =
        compareByDescending<MetaInfo> { it.date ?: "" }
            .thenByDescending { it.indexedAt ?: "" }
            .thenByDescending { it.buildNumber ?: -1 }

    private val newestFirst =
        Comparator<Pair<String, MetaInfo>> { a, b -> newestMeta.compare(a.second, b.second) }

    private fun scan(basePath: File): List<Pair<String, MetaInfo>> {
        val dirs = File(basePath, "versions").listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            if (!isSafeToken(dir.name)) return@mapNotNull null
            if (!File(dir, "knowledge.db").isFile) return@mapNotNull null
            val metaFile = File(dir, "version_meta.json")
            if (!metaFile.isFile) return@mapNotNull null
            val meta = readMeta(metaFile) ?: return@mapNotNull null
            if (meta.patchline == null || !isSafeToken(meta.patchline)) return@mapNotNull null
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
        if (!isSafeToken(patchline) || !isSafeToken(version)) return null
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
        }?.first?.takeIf { existingVersionDir(basePath, it) != null }
    }

    fun resolveCliSlug(basePath: File, patchline: String): String? {
        if (patchline == "release" || patchline == "pre-release") {
            return latestSlug(basePath, patchline)?.takeIf { existingKnowledgeDb(basePath, it) != null }
        }
        if (!isSafeToken(patchline)) return null
        if (existingKnowledgeDb(basePath, patchline) != null) return patchline
        return latestSlug(basePath, patchline)?.takeIf { existingKnowledgeDb(basePath, it) != null }
    }
}
