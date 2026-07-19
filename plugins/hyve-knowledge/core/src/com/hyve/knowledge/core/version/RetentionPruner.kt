// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.version

import java.io.File

object RetentionPruner {
    fun pruneRetention(basePath: File, patchline: String, keep: Int) {
        if (keep <= 0) return
        val dirs = File(basePath, "versions").listFiles { f -> f.isDirectory } ?: return
        val entries = dirs.mapNotNull { dir ->
            val metaFile = File(dir, "version_meta.json")
            if (!metaFile.isFile) return@mapNotNull null
            val meta = VersionResolver.readMeta(metaFile) ?: return@mapNotNull null
            if (meta.patchline != patchline) return@mapNotNull null
            Pair(dir, meta)
        }
        val sorted = entries.sortedWith(
            compareByDescending<Pair<File, VersionResolver.MetaInfo>> { it.second.buildNumber ?: -1 }
                .thenByDescending { it.second.date ?: "" }
                .thenByDescending { it.second.indexedAt ?: "" }
        )
        sorted.drop(keep).forEach { (dir, _) -> dir.deleteRecursively() }
    }
}
