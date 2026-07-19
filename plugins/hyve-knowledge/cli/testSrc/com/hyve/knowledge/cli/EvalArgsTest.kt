package com.hyve.knowledge.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EvalArgsTest {
    @Test fun `defaults`() {
        val a = EvalArgs.parse(emptyList())
        assertNull(a.golden)
        assertEquals("release", a.patchline)
        assertNull(a.baseline)
    }

    @Test fun `explicit options`() {
        val a = EvalArgs.parse(listOf("--golden", "/tmp/g.jsonl", "--patchline", "pre-release", "--baseline", "0.6"))
        assertEquals("/tmp/g.jsonl", a.golden)
        assertEquals("pre-release", a.patchline)
        assertEquals(0.6, a.baseline)
        assertTrue(a.sweep.isEmpty())
    }

    @Test fun `sweep axes accumulate across repeated flags`() {
        val a = EvalArgs.parse(listOf("--sweep", "hybridRrfK=40,60", "--sweep", "hybridNameWeight=5,10"))
        assertEquals(listOf("hybridRrfK=40,60", "hybridNameWeight=5,10"), a.sweep)
    }

    @Test fun `usage mentions sweep`() {
        assertTrue(EvalArgs.USAGE.contains("--sweep"))
    }

    @Test fun `unknown eval arg is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            EvalArgs.parse(listOf("--source", "git"))
        }
    }

    @Test fun `usage lists every eval option`() {
        val u = EvalArgs.USAGE
        listOf("--golden", "--patchline", "--baseline").forEach {
            assertTrue(u.contains(it), "usage should mention $it")
        }
    }
}
