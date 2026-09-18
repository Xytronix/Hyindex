// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.cli
import com.hyindex.knowledge.core.config.KnowledgeConfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndexerArgsTest {
    @Test fun `defaults`() {
        val a = IndexerArgs.parse(arrayOf())
        assertEquals(setOf("release", "pre-release"), a.patchlines)
        assertEquals(setOf("code","gamedata","client","docs"), a.corpora)
        assertEquals(setOf("official","modding","blog","support","server"), a.docsSources)
        assertEquals(false, a.force)
        assertEquals(false, a.reembed)
        assertEquals(false, a.allowClear)
        assertEquals(false, a.patchlinesExplicit)
        assertEquals(false, a.corporaExplicit)
        assertEquals(false, a.docsSourcesExplicit)
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
        val server = IndexerArgs.parse(arrayOf("--docs-source", "server"))
        assertEquals(setOf("server"), server.docsSources)
        val official = IndexerArgs.parse(arrayOf("--docs-source", "official"))
        assertEquals(setOf("official"), official.docsSources)
        assertTrue(server.docsSourcesExplicit)
        assertTrue(official.docsSourcesExplicit)
    }
    @Test fun `comma-list of named docs sources`() {
        val a = IndexerArgs.parse(arrayOf("--docs-source", "modding,blog"))
        assertEquals(setOf("modding","blog"), a.docsSources)
    }
    @Test fun `all expands to every named docs source`() {
        val a = IndexerArgs.parse(arrayOf("--docs-source", "all"))
        assertEquals(setOf("official","modding","blog","support","server"), a.docsSources)
    }
    @Test fun `config indexing defaults apply unless CLI explicitly overrides them`() {
        val config = KnowledgeConfig(
            indexPatchlines = listOf("release"),
            enabledCorpora = listOf("docs"),
            docsSources = listOf("official", "server"),
        )
        val defaults = IndexerArgs.parse(emptyArray())
        assertEquals(setOf("release"), IndexerArgs.resolvePatchlines(defaults, config))
        assertEquals(setOf("docs"), IndexerArgs.resolveCorpora(defaults, config))
        assertEquals(setOf("official", "server"), IndexerArgs.resolveDocsSources(defaults, config))

        val cli = IndexerArgs.parse(
            arrayOf("--patchline", "pre-release", "--corpus", "code", "--docs-source", "blog"),
        )
        assertEquals(setOf("pre-release"), IndexerArgs.resolvePatchlines(cli, config))
        assertEquals(setOf("code"), IndexerArgs.resolveCorpora(cli, config))
        assertEquals(setOf("blog"), IndexerArgs.resolveDocsSources(cli, config))
    }
    @Test fun `unknown docs sources are rejected`() {
        for (bad in listOf("web", "repo", "local", "javadoc")) {
            assertThrows(IllegalStateException::class.java) {
                IndexerArgs.parse(arrayOf("--docs-source", bad))
            }
        }
    }
    @Test fun `unknown patchlines and corpora are rejected`() {
        assertThrows(IllegalStateException::class.java) {
            IndexerArgs.parse(arrayOf("--patchline", "nightly"))
        }
        assertThrows(IllegalStateException::class.java) {
            IndexerArgs.parse(arrayOf("--corpus", "unknown"))
        }
    }
    @Test fun `explicit flags`() {
        val a = IndexerArgs.parse(arrayOf("--patchline","release","--corpus","code,docs","--docs-source","server","--force"))
        assertEquals(setOf("release"), a.patchlines)
        assertEquals(setOf("code","docs"), a.corpora)
        assertEquals(setOf("server"), a.docsSources)
        assertEquals(true, a.force)
        assertTrue(a.patchlinesExplicit)
        assertTrue(a.corporaExplicit)
        assertTrue(a.docsSourcesExplicit)
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
