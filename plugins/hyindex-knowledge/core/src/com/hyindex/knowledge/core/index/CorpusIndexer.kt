// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.index

interface CorpusIndexer {
    val corpus: String
    fun index(ctx: IndexContext): IndexResult
}
