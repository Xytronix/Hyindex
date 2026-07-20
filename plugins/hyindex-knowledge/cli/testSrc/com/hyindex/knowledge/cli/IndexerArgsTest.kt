package com.hyindex.knowledge.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndexerArgsTest {
    @Test fun `defaults`() {
        val a = IndexerArgs.parse(arrayOf())
        assertEquals(setOf("release", "pre-release"), a.patchlines)
        assertEquals(setOf("code","gamedata","client","docs"), a.corpora)
        assertEquals(setOf("modding","blog","support","server"), a.docsSources)
        assertEquals(false, a.force)
        assertEquals(false, a.reembed)
        assertEquals(false, a.allowClear)
    }
    @Test fun `reembed and allow-clear flags`() {
        val a = IndexerArgs.parse(arrayOf("--patchline","release","--corpus","code","--reembed"))
        assertTrue(a.reembed)
        assertEquals(false, a.allowClear)
        assertEquals(false, a.force)
        val b = IndexerArgs.parse(arrayOf("--allow-clear"))
        assertTrue(b.allowClear)
        assertEquals(false, b.reembed)
    }
    @Test fun `single named docs source`() {
        val a = IndexerArgs.parse(arrayOf("--docs-source", "server"))
        assertEquals(setOf("server"), a.docsSources)
    }
    @Test fun `comma-list of named docs sources`() {
        val a = IndexerArgs.parse(arrayOf("--docs-source", "modding,blog"))
        assertEquals(setOf("modding","blog"), a.docsSources)
    }
    @Test fun `all expands to every named docs source`() {
        val a = IndexerArgs.parse(arrayOf("--docs-source", "all"))
        assertEquals(setOf("modding","blog","support","server"), a.docsSources)
    }
    @Test fun `unknown docs sources are rejected`() {
        for (bad in listOf("web", "repo", "local", "javadoc")) {
            assertThrows(IllegalStateException::class.java) {
                IndexerArgs.parse(arrayOf("--docs-source", bad))
            }
        }
    }
    @Test fun `explicit flags`() {
        val a = IndexerArgs.parse(arrayOf("--patchline","release","--corpus","code,docs","--docs-source","server","--force"))
        assertEquals(setOf("release"), a.patchlines)
        assertEquals(setOf("code","docs"), a.corpora)
        assertEquals(setOf("server"), a.docsSources)
        assertEquals(true, a.force)
    }
    @Test fun `help flag`() {
        assertEquals(true, IndexerArgs.parse(arrayOf("--help")).help)
        assertEquals(true, IndexerArgs.parse(arrayOf("-h")).help)
        assertEquals(false, IndexerArgs.parse(arrayOf()).help)
    }
    @Test fun `usage lists every option`() {
        val u = IndexerArgs.USAGE
        listOf("--patchline", "--corpus", "--docs-source", "--force", "--reembed", "--allow-clear", "--help").forEach {
            assertTrue(u.contains(it), "usage should mention $it")
        }
    }
    @Test fun `unknown flag is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            IndexerArgs.parse(arrayOf("--source", "git"))
        }
    }
}
