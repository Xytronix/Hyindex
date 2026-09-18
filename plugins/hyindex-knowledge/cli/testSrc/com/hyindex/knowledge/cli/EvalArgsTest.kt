// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EvalArgsTest {
    @Test
    fun `defaults use release and checked-in seed`() {
        val args = EvalArgs.parse(emptyList())
        assertEquals(null, args.golden)
        assertEquals("release", args.patchline)
        assertEquals(null, args.baseline)
        assertEquals(emptyList<String>(), args.sweep)
    }

    @Test
    fun `parses golden baseline and repeated sweeps`() {
        val args = EvalArgs.parse(
            listOf(
                "--golden", "/tmp/golden.jsonl",
                "--patchline", "pre-release",
                "--baseline", "0.8",
                "--sweep", "hybridRrfK=40,60",
                "--sweep", "rerankerTopN=30,50",
            ),
        )
        assertEquals("/tmp/golden.jsonl", args.golden)
        assertEquals("pre-release", args.patchline)
        assertEquals(0.8, args.baseline)
        assertEquals(listOf("hybridRrfK=40,60", "rerankerTopN=30,50"), args.sweep)
    }

    @Test
    fun `rejects removed or unknown evaluation flags`() {
        assertThrows(IllegalStateException::class.java) {
            EvalArgs.parse(listOf("--visual-only"))
        }
    }
}
