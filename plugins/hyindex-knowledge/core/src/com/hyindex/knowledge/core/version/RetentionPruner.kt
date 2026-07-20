// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.version

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
            Comparator { a, b -> VersionResolver.newestMeta.compare(a.second, b.second) }
        )
        sorted.drop(keep).forEach { (dir, _) -> dir.deleteRecursively() }
    }
}
