// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.config

import kotlinx.serialization.Serializable

@Serializable
data class EmbeddingProfile(
    val provider: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val documentModel: String,
    val queryModel: String? = null,
    val dimensions: Int? = null,
    val concurrency: Int? = null,
)

data class ResolvedEmbeddingProfile(
    val name: String,
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
    val documentModel: String,
    val queryModel: String,
    val dimensions: Int?,
    val concurrency: Int,
)
