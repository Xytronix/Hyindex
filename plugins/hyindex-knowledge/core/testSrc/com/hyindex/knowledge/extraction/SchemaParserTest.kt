package com.hyindex.knowledge.extraction

import com.hyindex.knowledge.core.extraction.GameDataType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SchemaParserTest {

    @Test
    fun `normal schema yields one chunk with schema type and id`() {
        val json = """
            {
              "type": "object",
              "${'$'}id": "Item.json",
              "title": "Item",
              "hytale": {
                "path": "Item/Items",
                "extension": ".json"
              },
              "properties": {
                "Parent": {
                  "type": "string",
                  "description": "Inherit properties from the named asset."
                },
                "MaxStackSize": {
                  "type": "integer",
                  "description": "Maximum stack size for this item.",
                  "default": 1
                },
                "Quality": {
                  "type": "string",
                  "description": "Item quality tier.",
                  "enum": ["Common", "Rare", "Epic"]
                }
              }
            }
        """.trimIndent()

        val chunks = SchemaParser.parseSchemaFile("Schema/Item.json", json.toByteArray())

        assertThat(chunks).hasSize(1)
        val chunk = chunks.first()
        assertThat(chunk.type).isEqualTo(GameDataType.SCHEMA)
        assertThat(chunk.id).isEqualTo("gamedata:schema:Item")
        assertThat(chunk.name).isEqualTo("Item")
        assertThat(chunk.textForEmbedding).contains("Item")
        assertThat(chunk.textForEmbedding).contains("Item/Items")
        assertThat(chunk.textForEmbedding).contains("Parent")
        assertThat(chunk.textForEmbedding).contains("Inherit properties from the named asset.")
        assertThat(chunk.textForEmbedding).contains("MaxStackSize")
        assertThat(chunk.textForEmbedding).contains("Maximum stack size for this item.")
        assertThat(chunk.textForEmbedding).contains("Quality")
        assertThat(chunk.textForEmbedding).contains("Common")
    }

    @Test
    fun `polymorphic schema includes hytaleSchemaTypeField values`() {
        val json = """
            {
              "${'$'}id": "Condition.json",
              "title": "Condition",
              "hytale": {
                "path": "NPC/DecisionMaking/Conditions",
                "extension": ".json"
              },
              "hytaleSchemaTypeField": {
                "property": "Type",
                "values": ["FlockSize", "HasTarget", "LineOfSight"]
              }
            }
        """.trimIndent()

        val chunks = SchemaParser.parseSchemaFile("Schema/Condition.json", json.toByteArray())

        assertThat(chunks).hasSize(1)
        val text = chunks.first().textForEmbedding
        assertThat(text).contains("FlockSize")
        assertThat(text).contains("HasTarget")
        assertThat(text).contains("LineOfSight")
    }

    @Test
    fun `definition library yields one chunk per definition`() {
        val json = """
            {
              "${'$'}id": "common.json",
              "definitions": {
                "MaterialQuantity": {
                  "type": "object",
                  "title": "MaterialQuantity",
                  "properties": {
                    "ItemId": { "type": "string" },
                    "Quantity": { "type": "integer", "default": 1 }
                  }
                },
                "BenchRequirement": {
                  "type": "object",
                  "title": "BenchRequirement",
                  "description": "Requires a crafting bench.",
                  "properties": {
                    "BenchType": { "type": "string", "description": "Type of bench required." }
                  }
                }
              }
            }
        """.trimIndent()

        val chunks = SchemaParser.parseSchemaFile("Schema/common.json", json.toByteArray())

        assertThat(chunks).hasSize(2)
        val ids = chunks.map { it.id }
        assertThat(ids).contains("gamedata:schema:def:MaterialQuantity")
        assertThat(ids).contains("gamedata:schema:def:BenchRequirement")
        assertThat(chunks.all { it.type == GameDataType.SCHEMA }).isTrue
        val mqChunk = chunks.first { it.id == "gamedata:schema:def:MaterialQuantity" }
        assertThat(mqChunk.textForEmbedding).contains("MaterialQuantity")
        assertThat(mqChunk.textForEmbedding).contains("ItemId")
        val brChunk = chunks.first { it.id == "gamedata:schema:def:BenchRequirement" }
        assertThat(brChunk.textForEmbedding).contains("BenchRequirement")
        assertThat(brChunk.textForEmbedding).contains("BenchType")
        assertThat(brChunk.textForEmbedding).contains("Type of bench required.")
    }

    @Test
    fun `dollar-prefixed properties are skipped`() {
        val json = """
            {
              "${'$'}id": "Item.json",
              "title": "Item",
              "hytale": { "path": "Item/Items", "extension": ".json" },
              "properties": {
                "${'$'}Title": { "description": "Internal comment field." },
                "${'$'}Comment": { "description": "Another internal field." },
                "Name": { "type": "string", "description": "The item name." }
              }
            }
        """.trimIndent()

        val chunks = SchemaParser.parseSchemaFile("Schema/Item.json", json.toByteArray())

        assertThat(chunks).hasSize(1)
        val text = chunks.first().textForEmbedding
        assertThat(text).contains("Name")
        assertThat(text).doesNotContain("\$Title")
        assertThat(text).doesNotContain("Internal comment field.")
    }

    @Test
    fun `invalid schema returns empty list`() {
        val json = """{"Id":"schema"}"""
        val chunks = SchemaParser.parseSchemaFile("Schema/Item.json", json.toByteArray())
        assertThat(chunks).isEmpty()
    }
}
