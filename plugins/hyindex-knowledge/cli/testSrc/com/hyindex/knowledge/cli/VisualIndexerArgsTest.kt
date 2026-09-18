package com.hyindex.knowledge.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisualIndexerArgsTest {

    @Test
    fun `default corpora do not include visual`() {
        val a = IndexerArgs.parse(arrayOf())
        assertEquals(setOf("code", "gamedata", "client", "docs"), a.corpora)
        assertFalse("visual" in a.corpora)
    }

    @Test
    fun `explicit --corpus visual is accepted as an opt-in fifth corpus`() {
        val a = IndexerArgs.parse(arrayOf("--corpus", "visual"))
        assertEquals(setOf("visual"), a.corpora)
    }

    @Test
    fun `visual can be combined with other corpora without becoming the default`() {
        val a = IndexerArgs.parse(arrayOf("--corpus", "docs,visual"))
        assertEquals(setOf("docs", "visual"), a.corpora)
        val defaults = IndexerArgs.parse(arrayOf())
        assertFalse("visual" in defaults.corpora)
        assertTrue("docs" in defaults.corpora)
    }
}
