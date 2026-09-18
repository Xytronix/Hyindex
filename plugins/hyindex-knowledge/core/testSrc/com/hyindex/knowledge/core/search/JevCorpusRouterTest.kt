// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.search

import com.hyindex.knowledge.core.db.Corpus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JevCorpusRouterTest {

    @Test
    fun `parseRoute selects relevant corpora and preserves intent`() {
        val response = """
            {
              "answers": {
                "corpus_code": {"type":"noul","noul":0.91},
                "corpus_docs": {"type":"noul","noul":0.96},
                "corpus_gamedata": {"type":"noul","noul":0.17},
                "corpus_client": {"type":"noul","noul":0.03},
                "intent": {"type":"choice","choice":"api_how_to","probabilities":{"api_how_to":0.94},"confidence":0.9},
                "graph_expansion": {"type":"noul","noul":0.26}
              }
            }
        """.trimIndent()

        val route = JevCorpusRouter.parseRoute(response, Corpus.entries, 0.20)

        assertEquals(listOf(Corpus.DOCS, Corpus.CODE), route!!.corpora)
        assertEquals("api_how_to", route.intent)
        assertEquals(0.26, route.graphExpansionProbability)
        assertEquals(0.96, route.probabilities.getValue(Corpus.DOCS))
    }

    @Test
    fun `parseRoute rejects malformed responses`() {
        assertNull(JevCorpusRouter.parseRoute("{}", Corpus.entries, 0.20))
    }
}
