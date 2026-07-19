// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.eval

import com.hyve.knowledge.core.config.KnowledgeConfig

object SweepGrid {
    fun parse(spec: String): List<Map<String, String>> {
        val axes = spec.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { axis ->
                val eq = axis.indexOf('=')
                if (eq <= 0) error("malformed sweep axis (expected name=v1,v2): $axis")
                val name = axis.substring(0, eq).trim()
                if (name !in KNOBS) error("unknown sweep knob: $name")
                val values = axis.substring(eq + 1).split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (values.isEmpty()) error("sweep axis has no values: $axis")
                name to values
            }
        var combos = listOf<Map<String, String>>(emptyMap())
        for ((name, values) in axes) {
            combos = combos.flatMap { combo -> values.map { combo + (name to it) } }
        }
        return combos
    }

    fun applyOverrides(base: KnowledgeConfig, overrides: Map<String, String>): KnowledgeConfig {
        var c = base
        for ((name, value) in overrides) {
            c = when (name) {
                "blogScorePenalty" -> c.copy(blogScorePenalty = value.toDouble())
                "gamedataUnintentScoreFloor" -> c.copy(gamedataUnintentScoreFloor = value.toDouble())
                "expansionDiscount" -> c.copy(expansionDiscount = value.toDouble())
                "minExpansionSeedScore" -> c.copy(minExpansionSeedScore = value.toDouble())
                "minExpansionResultScore" -> c.copy(minExpansionResultScore = value.toDouble())
                "gamedataWorldNodePenalty" -> c.copy(gamedataWorldNodePenalty = value.toDouble())
                "hybridNameWeight" -> c.copy(hybridNameWeight = value.toDouble())
                "hybridBodyWeight" -> c.copy(hybridBodyWeight = value.toDouble())
                "perSeedExpansionCap" -> c.copy(perSeedExpansionCap = value.toInt())
                "gamedataFetchLimit" -> c.copy(gamedataFetchLimit = value.toInt())
                "rerankerTopN" -> c.copy(rerankerTopN = value.toInt())
                "hybridRrfK" -> c.copy(hybridRrfK = value.toInt())
                "hybridLexicalLimit" -> c.copy(hybridLexicalLimit = value.toInt())
                "rerankerEnabled" -> c.copy(rerankerEnabled = value.toBooleanStrict())
                "hybridEnabled" -> c.copy(hybridEnabled = value.toBooleanStrict())
                "nearDupPenalty" -> c.copy(nearDupPenalty = value.toDouble())
                "nearDupJaccard" -> c.copy(nearDupJaccard = value.toDouble())
                "delegatePenalty" -> c.copy(delegatePenalty = value.toDouble())
                else -> error("unknown sweep knob: $name")
            }
        }
        return c
    }

    val KNOBS: Set<String> = setOf(
        "blogScorePenalty",
        "gamedataUnintentScoreFloor",
        "expansionDiscount",
        "minExpansionSeedScore",
        "minExpansionResultScore",
        "gamedataWorldNodePenalty",
        "hybridNameWeight",
        "hybridBodyWeight",
        "perSeedExpansionCap",
        "gamedataFetchLimit",
        "rerankerTopN",
        "hybridRrfK",
        "hybridLexicalLimit",
        "rerankerEnabled",
        "hybridEnabled",
        "nearDupPenalty",
        "nearDupJaccard",
        "delegatePenalty",
    )
}
