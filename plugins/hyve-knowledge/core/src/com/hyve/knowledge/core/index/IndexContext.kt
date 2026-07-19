// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.index

import com.hyve.knowledge.core.config.KnowledgeConfig
import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.core.logging.LogProvider
import com.hyve.knowledge.core.progress.ProgressReporter
import java.io.File


class IndexContext(
    val config: KnowledgeConfig,
    val db: KnowledgeDatabase,
    val cache: EmbeddingCacheService,
    val log: LogProvider,
    val progress: ProgressReporter,
    val decompileDir: File = decompileDirFor(config),
    val assetsZip: File? = null,

    val gameDataDir: File? = null,
    val manifestRoot: File? = null,

    val clientFolder: File? = null,
    val docsDir: File? = null,
) {
    val indexDir: File get() = config.resolvedIndexPath()

    companion object {
        fun decompileDirFor(config: KnowledgeConfig): File =
            File(config.resolvedIndexPath(), "decompiled")
    }
}

data class IndexResult(
    val corpus: String,
    val indexed: Int,
    val skipped: Boolean,
    val error: String?,
) {
    val ok: Boolean get() = error == null
}
