// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.index

interface CorpusIndexer {
    val corpus: String
    fun index(ctx: IndexContext): IndexResult
}
