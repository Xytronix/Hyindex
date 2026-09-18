// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.diff

import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.version.VersionResolver
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest


class DiffCache(
    private val basePath: File,
    private val log: LogProvider = StdoutLogProvider,
) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val diffsDir: File get() = File(basePath, "diffs")

    fun get(versionA: String, versionB: String): VersionDiff? {
        val file = diffFile(versionA, versionB)
        if (!file.exists()) return null

        val cacheTime = file.lastModified()


        for ((version, label) in listOf(versionA to "A", versionB to "B")) {
            val meta = versionMetaFile(version)
            val db = versionDbFile(version)
            if (meta.exists() && meta.lastModified() > cacheTime) {
                log.info("Diff cache invalidated: $version meta was updated")
                file.delete()
                return null
            }
            if (db.exists() && db.lastModified() > cacheTime) {
                log.info("Diff cache invalidated: $version database was updated")
                file.delete()
                return null
            }
        }

        return try {
            json.decodeFromString(VersionDiff.serializer(), file.readText())
        } catch (e: Exception) {
            log.warn("Failed to read diff cache: ${e.message}")
            file.delete()
            null
        }
    }

    fun put(diff: VersionDiff) {
        diffsDir.mkdirs()
        val file = diffFile(diff.versionA, diff.versionB)
        file.writeText(json.encodeToString(VersionDiff.serializer(), diff))
    }

    private fun diffFile(versionA: String, versionB: String): File {
        val name = "${stableId(versionA)}--${stableId(versionB)}.diff.json"
        val file = File(diffsDir, name)
        check(VersionResolver.containedUnder(diffsDir, file)) { "diff cache path escapes diffs root" }
        return file
    }

    private fun stableId(version: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(version.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }.take(32)
    }

    private fun versionMetaFile(version: String): File {
        val slug = version.takeIf { VersionResolver.isSafeToken(it) } ?: return File(basePath, "versions/.invalid/version_meta.json")
        return File(File(File(basePath, "versions"), slug), "version_meta.json")
    }

    private fun versionDbFile(version: String): File {
        return VersionResolver.existingKnowledgeDb(basePath, version)
            ?: File(basePath, "versions/.invalid/knowledge.db")
    }

    companion object {
        fun forDefaultPath(): DiffCache {
            val home = System.getProperty("user.home")
            return DiffCache(File(File(home, ".hyindex"), "knowledge"))
        }
    }
}
