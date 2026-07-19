// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.index

import com.hyve.knowledge.core.db.KnowledgeDatabase
import java.io.File
import java.security.MessageDigest


class FileHashTracker(private val db: KnowledgeDatabase) {

    data class ChangeSet(
        val added: Set<String>,
        val changed: Set<String>,
        val deleted: Set<String>,
        val unchanged: Set<String>,

        val currentHashes: Map<String, String> = emptyMap(),
    ) {
        val hasChanges: Boolean get() = added.isNotEmpty() || changed.isNotEmpty() || deleted.isNotEmpty()
        val totalChanged: Int get() = added.size + changed.size
    }


    fun computeHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }


    fun computeHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }


    fun detectChanges(
        sourceDir: File,
        corpusType: String = "java",
        extensionFilter: Set<String>? = null,
        fileFilter: ((String) -> Boolean)? = null,
    ): ChangeSet {

        val existingHashes = loadStoredHashes(corpusType)


        val currentHashes = mutableMapOf<String, String>()
        sourceDir.walkTopDown()
            .filter { file ->
                file.isFile && (extensionFilter == null || file.extension in extensionFilter)
            }
            .forEach { file ->
                val relativePath = file.relativeTo(sourceDir).path.replace('\\', '/')
                if (fileFilter == null || fileFilter(relativePath)) {
                    currentHashes[relativePath] = computeHash(file)
                }
            }

        return computeChangeSet(existingHashes, currentHashes)
    }


    fun computeChangesFromMap(hashes: Map<String, String>, corpusType: String): ChangeSet {
        val existingHashes = loadStoredHashes(corpusType)
        return computeChangeSet(existingHashes, hashes)
    }

    private fun computeChangeSet(
        existingHashes: Map<String, String>,
        currentHashes: Map<String, String>,
    ): ChangeSet {
        val added = mutableSetOf<String>()
        val changed = mutableSetOf<String>()
        val unchanged = mutableSetOf<String>()

        for ((path, hash) in currentHashes) {
            val existingHash = existingHashes[path]
            when {
                existingHash == null -> added.add(path)
                existingHash != hash -> changed.add(path)
                else -> unchanged.add(path)
            }
        }

        val deleted = existingHashes.keys - currentHashes.keys

        return ChangeSet(added, changed, deleted, unchanged, currentHashes)
    }


    fun updateHashes(fileHashes: Map<String, String>, corpusType: String = "java") {
        db.inTransaction { conn ->
            val ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO file_hashes (file_path, file_hash, corpus_type) VALUES (?, ?, ?)"
            )
            for ((path, hash) in fileHashes) {
                ps.setString(1, path)
                ps.setString(2, hash)
                ps.setString(3, corpusType)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }


    fun removeHashes(filePaths: Set<String>) {
        if (filePaths.isEmpty()) return
        db.inTransaction { conn ->
            val ps = conn.prepareStatement("DELETE FROM file_hashes WHERE file_path = ?")
            for (path in filePaths) {
                ps.setString(1, path)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun loadStoredHashes(corpusType: String): Map<String, String> {
        return db.query(
            "SELECT file_path, file_hash FROM file_hashes WHERE corpus_type = ?",
            corpusType,
        ) { rs ->
            rs.getString("file_path") to rs.getString("file_hash")
        }.toMap()
    }
}
