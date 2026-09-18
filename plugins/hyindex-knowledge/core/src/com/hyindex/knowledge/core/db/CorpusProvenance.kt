// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.db

data class CorpusProvenance(
    val corpus: String,
    val provider: String,
    val documentModel: String,
    val queryCompatibleFamily: String,
    val dimensions: Int,
    val indexedAt: String,
)
