// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.source

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission

object CanonicalRoots {
    fun isInside(path: File, root: File): Boolean {
        val r = try {
            root.canonicalFile
        } catch (_: Exception) {
            return false
        }
        val p = try {
            path.canonicalFile
        } catch (_: Exception) {
            return false
        }
        val rp = r.path
        val pp = p.path
        return pp == rp || pp.startsWith(rp + File.separator)
    }

    fun isSafeDirectory(dir: File, root: File): Boolean {
        val p = dir.toPath()
        if (Files.isSymbolicLink(p)) return false
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return false
        return isInside(dir, root)
    }

    fun isSafeRegularFile(file: File, root: File): Boolean {
        val p = file.toPath()
        if (Files.isSymbolicLink(p)) return false
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return false
        return isInside(file, root)
    }

    fun isAccessibleDirectory(dir: File): Boolean {
        val p = dir.toPath()
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return false
        if (!Files.isReadable(p) || !Files.isExecutable(p)) return false
        return try {
            val perms = Files.getPosixFilePermissions(p)
            val hasRead = perms.any {
                it == PosixFilePermission.OWNER_READ ||
                    it == PosixFilePermission.GROUP_READ ||
                    it == PosixFilePermission.OTHERS_READ
            }
            val hasExec = perms.any {
                it == PosixFilePermission.OWNER_EXECUTE ||
                    it == PosixFilePermission.GROUP_EXECUTE ||
                    it == PosixFilePermission.OTHERS_EXECUTE
            }
            hasRead && hasExec
        } catch (_: UnsupportedOperationException) {
            true
        }
    }

    fun walkSafeFiles(root: File): Sequence<File> {
        if (!root.exists() || !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return emptySequence()
        }
        val canon = try {
            root.canonicalFile
        } catch (_: Exception) {
            return emptySequence()
        }
        return root.walkTopDown()
            .onEnter { dir -> dir == root || isSafeDirectory(dir, canon) }
            .asSequence()
            .filter { isSafeRegularFile(it, canon) }
    }
}
