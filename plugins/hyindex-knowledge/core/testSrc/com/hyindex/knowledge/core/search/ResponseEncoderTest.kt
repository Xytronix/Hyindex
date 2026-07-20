package com.hyindex.knowledge.core.search

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResponseEncoderTest {
    private val json = Json { encodeDefaults = true }

    private fun uniform() = buildJsonObject {
        put("results", buildJsonArray {
            add(buildJsonObject { put("a", "1"); put("b", "2") })
            add(buildJsonObject { put("a", "3"); put("b", "4") })
        })
    }

    private fun nonUniform() = buildJsonObject {
        put("results", buildJsonArray {
            add(buildJsonObject { put("a", "1") })
            add(buildJsonObject { put("a", "1"); put("b", "2") })
        })
    }

    @Test fun uniformGivesToon() {
        val out = ResponseEncoder.encode(uniform(), json)
        assertTrue(out.startsWith("results[2]{a,b}:"), "expected TOON, got: $out")
    }

    @Test fun nonUniformFallsBackToJson() {
        val out = ResponseEncoder.encode(nonUniform(), json)
        assertTrue(out.startsWith("{"), "expected JSON fallback, got: $out")
    }
}
