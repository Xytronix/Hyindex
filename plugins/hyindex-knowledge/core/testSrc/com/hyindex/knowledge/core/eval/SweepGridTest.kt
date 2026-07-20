package com.hyindex.knowledge.core.eval

import com.hyindex.knowledge.core.config.KnowledgeConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SweepGridTest {
    @Test fun `cartesian product of two axes yields all combos`() {
        val combos = SweepGrid.parse("hybridRrfK=1,2;hybridLexicalLimit=3,4")
        assertEquals(4, combos.size)
        assertEquals(
            setOf(
                mapOf("hybridRrfK" to "1", "hybridLexicalLimit" to "3"),
                mapOf("hybridRrfK" to "1", "hybridLexicalLimit" to "4"),
                mapOf("hybridRrfK" to "2", "hybridLexicalLimit" to "3"),
                mapOf("hybridRrfK" to "2", "hybridLexicalLimit" to "4"),
            ),
            combos.toSet(),
        )
    }

    @Test fun `single axis yields one combo per value`() {
        val combos = SweepGrid.parse("hybridNameWeight=5,10,15")
        assertEquals(3, combos.size)
        assertEquals("5", combos[0]["hybridNameWeight"])
        assertEquals("15", combos[2]["hybridNameWeight"])
    }

    @Test fun `applyOverrides sets a double knob and leaves others intact`() {
        val base = KnowledgeConfig()
        val out = SweepGrid.applyOverrides(base, mapOf("hybridNameWeight" to "5"))
        assertEquals(5.0, out.hybridNameWeight)
        assertEquals(base.hybridBodyWeight, out.hybridBodyWeight)
        assertEquals(base.hybridRrfK, out.hybridRrfK)
    }

    @Test fun `applyOverrides parses int and boolean knobs`() {
        val base = KnowledgeConfig()
        val out = SweepGrid.applyOverrides(base, mapOf("hybridRrfK" to "40", "rerankerEnabled" to "true"))
        assertEquals(40, out.hybridRrfK)
        assertEquals(true, out.rerankerEnabled)
    }

    @Test fun `applyOverrides applies multiple knobs at once`() {
        val base = KnowledgeConfig()
        val out = SweepGrid.applyOverrides(base, mapOf("hybridNameWeight" to "7", "hybridBodyWeight" to "2"))
        assertEquals(7.0, out.hybridNameWeight)
        assertEquals(2.0, out.hybridBodyWeight)
    }

    @Test fun `applyOverrides recognizes delegatePenalty`() {
        val base = KnowledgeConfig()
        val out = SweepGrid.applyOverrides(base, mapOf("delegatePenalty" to "0.5"))
        assertEquals(0.5, out.delegatePenalty)
    }

    @Test fun `parse recognizes delegatePenalty as a knob`() {
        val combos = SweepGrid.parse("delegatePenalty=0.5,0.8,1.0")
        assertEquals(3, combos.size)
        assertEquals("0.5", combos[0]["delegatePenalty"])
    }

    @Test fun `unknown knob name is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            SweepGrid.applyOverrides(KnowledgeConfig(), mapOf("notAKnob" to "1"))
        }
    }

    @Test fun `parse rejects an unknown knob name`() {
        assertThrows(IllegalStateException::class.java) {
            SweepGrid.parse("notAKnob=1,2")
        }
    }

    @Test fun `parse rejects a malformed axis`() {
        assertThrows(IllegalStateException::class.java) {
            SweepGrid.parse("hybridRrfK")
        }
    }
}
