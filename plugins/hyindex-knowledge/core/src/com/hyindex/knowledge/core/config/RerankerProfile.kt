// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.config

import kotlinx.serialization.Serializable

/**
 * Configuration for an HTTP reranking endpoint.
 *
 * [provider] identifies the service and supplies protocol defaults for Voyage,
 * Cohere, and Jina. Custom services can select one of those wire formats with
 * [protocol] and override the relative or absolute [endpoint].
 */
@Serializable
data class RerankerProfile(
    val provider: String,
    val baseUrl: String = "",
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String,
    val protocol: String = "",
    val topN: Int = 50,
)
