package com.hyindex.knowledge.core.search

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToonEncoderTest {

    @Test fun uniformFlatArrayIsTabular() {
        val obj = buildJsonObject {
            put("query", "PlayerEntity")
            put("resultCount", 2)
            put("results", buildJsonArray {
                add(buildJsonObject { put("displayName", "PlayerEntity"); put("filePath", "/src/P.java"); put("nodeType", "JavaClass") })
                add(buildJsonObject { put("displayName", "PlayerRenderer"); put("filePath", "/src/R.java"); put("nodeType", "JavaClass") })
            })
        }
        val expected = """
            query: PlayerEntity
            resultCount: 2
            results[2]{displayName,filePath,nodeType}:
              PlayerEntity,/src/P.java,JavaClass
              PlayerRenderer,/src/R.java,JavaClass
        """.trimIndent()
        assertEquals(expected, ToonEncoder.encode(obj))
    }

    @Test fun nonUniformObjectArrayThrows() {
        val obj = buildJsonObject {
            put("results", buildJsonArray {
                add(buildJsonObject { put("a", "1") })
                add(buildJsonObject { put("a", "1"); put("b", "2") })
            })
        }
        assertThrows(ToonNotApplicable::class.java) { ToonEncoder.encode(obj) }
    }

    @Test fun nestedObjectValueInRowThrows() {
        val obj = buildJsonObject {
            put("results", buildJsonArray {
                add(buildJsonObject { put("a", "1"); put("nested", buildJsonObject { put("x", "y") }) })
            })
        }
        assertThrows(ToonNotApplicable::class.java) { ToonEncoder.encode(obj) }
    }

    @Test fun quotingAndEscaping() {
        val obj = buildJsonObject {
            put("rows", buildJsonArray {
                add(buildJsonObject {
                    put("plain", "hello")
                    put("comma", "a,b")
                    put("code", "void f(){\n  return;\n}")
                    put("numlike", "05")
                    put("reserved", "true")
                })
            })
        }
        val expected = """
            rows[1]{plain,comma,code,numlike,reserved}:
              hello,"a,b","void f(){\n  return;\n}","05","true"
        """.trimIndent()
        assertEquals(expected, ToonEncoder.encode(obj))
    }

    @Test fun emptyArrayIsBracketColon() {
        val obj = buildJsonObject { put("results", buildJsonArray { }) }
        assertEquals("results: []", ToonEncoder.encode(obj))
    }

    @Test fun nestedObjectIndents() {
        val obj = buildJsonObject {
            put("node", buildJsonObject { put("id", "x"); put("corpus", "code") })
        }
        val expected = """
            node:
              id: x
              corpus: code
        """.trimIndent()
        assertEquals(expected, ToonEncoder.encode(obj))
    }

    @Test fun numbersAndBooleansUnquoted() {
        val obj = buildJsonObject {
            put("rows", buildJsonArray {
                add(buildJsonObject { put("score", 0.91); put("line", 42); put("flag", true) })
            })
        }
        val expected = """
            rows[1]{score,line,flag}:
              0.91,42,true
        """.trimIndent()
        assertEquals(expected, ToonEncoder.encode(obj))
    }

    @Test fun searchResultRowsWithDifferingDataTypeAreTabular() {
        val obj = buildJsonObject {
            put("query", "weapon")
            put("resultCount", 2)
            put("results", buildJsonArray {
                add(buildJsonObject {
                    put("id", "gamedata:Sword")
                    put("displayName", "Sword")
                    put("snippet", "a sword")
                    put("filePath", "items/Sword.json")
                    put("lineStart", 1)
                    put("score", 0.9)
                    put("relevanceScore", "")
                    put("docSource", "")
                    put("dataType", "item")
                    put("bridgedFrom", "")
                    put("bridgeEdgeType", "")
                    put("connectedNodeIds", "")
                })
                add(buildJsonObject {
                    put("id", "gamedata:World")
                    put("displayName", "World")
                    put("snippet", "a world")
                    put("filePath", "world/World.json")
                    put("lineStart", 2)
                    put("score", 0.8)
                    put("relevanceScore", "")
                    put("docSource", "")
                    put("dataType", "")
                    put("bridgedFrom", "")
                    put("bridgeEdgeType", "")
                    put("connectedNodeIds", "a,b")
                })
            })
        }
        val encoded = ToonEncoder.encode(obj)
        assertTrue(
            encoded.contains("results[2]{id,displayName,snippet,filePath,lineStart,score,relevanceScore,docSource,dataType,bridgedFrom,bridgeEdgeType,connectedNodeIds}:"),
            "uniform rows should encode as a TOON table; got: $encoded",
        )
        assertTrue(encoded.contains("\"a,b\""), "connectedNodeIds should be a quoted comma string; got: $encoded")
    }

    @Test fun leadingHyphenStringsQuoted() {
        val obj = buildJsonObject {
            put("rows", buildJsonArray {
                add(buildJsonObject { put("arrow", "->"); put("dec", "-x"); put("neg", "-3") })
            })
        }
        val expected = """
            rows[1]{arrow,dec,neg}:
              "->","-x","-3"
        """.trimIndent()
        assertEquals(expected, ToonEncoder.encode(obj))
    }
}
