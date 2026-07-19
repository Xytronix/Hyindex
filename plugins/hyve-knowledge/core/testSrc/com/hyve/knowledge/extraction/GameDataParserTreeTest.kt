package com.hyve.knowledge.extraction

import com.hyve.knowledge.core.extraction.GameDataType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameDataParserTreeTest {
    private val itemJson = """{"TranslationProperties":{"Name":"server.items.Foo.name"},"MaxStack":99}"""
    private val entryPath = "Server/Item/Items/Foo.json"

    @Test
    fun `tree parse matches zip parse for identical content`() {

        val root = Files.createTempDirectory("assets")
        val f = root.resolve(entryPath); Files.createDirectories(f.parent); Files.writeString(f, itemJson)

        val zip = Files.createTempFile("assets", ".zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry(entryPath)); z.write(itemJson.toByteArray()); z.closeEntry()
        }

        val treeRes = GameDataParser.parseAssetsTree(root)
        val zipRes = GameDataParser.parseAssetsZip(zip)

        assertThat(treeRes.chunks.filter { it.type != GameDataType.LOCALIZATION }).hasSize(1)
        assertThat(treeRes.chunks.filter { it.type != GameDataType.LOCALIZATION }.map { it.id })
            .isEqualTo(zipRes.chunks.map { it.id })
        assertThat(treeRes.chunks.filter { it.type != GameDataType.LOCALIZATION }.first().type)
            .isEqualTo(zipRes.chunks.first().type)

        File(root.toString()).deleteRecursively(); Files.deleteIfExists(zip)
    }

    @Test
    fun `two-pass resolves lang key in item textForEmbedding and emits localization chunk`() {
        val root = Files.createTempDirectory("assets-lang")
        val langPath = "Server/Languages/en-US/server.lang"
        val langContent = "items.Foo.name = Cauldron\n"
        val langFile = root.resolve(langPath)
        Files.createDirectories(langFile.parent)
        Files.writeString(langFile, langContent)

        val itemFile = root.resolve(entryPath)
        Files.createDirectories(itemFile.parent)
        Files.writeString(itemFile, itemJson)

        val result = GameDataParser.parseAssetsTree(root)

        val itemChunk = result.chunks.first { it.type != GameDataType.LOCALIZATION }
        assertThat(itemChunk.textForEmbedding).contains("Cauldron")
        assertThat(itemChunk.textForEmbedding).doesNotContain("server.items.Foo.name")

        val locChunks = result.chunks.filter { it.type == GameDataType.LOCALIZATION }
        assertThat(locChunks).isNotEmpty

        File(root.toString()).deleteRecursively()
    }

    @Test
    fun `Schema json is routed to SchemaParser and yields SCHEMA type chunk`() {
        val root = Files.createTempDirectory("assets-schema")
        val schemaJson = """
            {
              "type": "object",
              "${'$'}id": "Item.json",
              "title": "Item",
              "hytale": { "path": "Item/Items", "extension": ".json" },
              "properties": {
                "Parent": { "type": "string", "description": "Inherit from parent." },
                "MaxStackSize": { "type": "integer", "description": "Max stack." }
              }
            }
        """.trimIndent()
        val schemaFile = root.resolve("Schema/Item.json")
        Files.createDirectories(schemaFile.parent)
        Files.writeString(schemaFile, schemaJson)

        val result = GameDataParser.parseAssetsTree(root)

        val schemaChunks = result.chunks.filter { it.type == GameDataType.SCHEMA }
        assertThat(schemaChunks).hasSize(1)
        val chunk = schemaChunks.first()
        assertThat(chunk.id).isEqualTo("gamedata:schema:Item")
        assertThat(chunk.textForEmbedding).contains("Item")
        assertThat(chunk.textForEmbedding).contains("Item/Items")

        File(root.toString()).deleteRecursively()
    }

    @Test
    fun `Schema common json yields per-definition chunks`() {
        val root = Files.createTempDirectory("assets-schema-common")
        val commonJson = """
            {
              "${'$'}id": "common.json",
              "definitions": {
                "MaterialQuantity": {
                  "type": "object",
                  "title": "MaterialQuantity",
                  "properties": { "Quantity": { "type": "integer" } }
                },
                "BenchRequirement": {
                  "type": "object",
                  "title": "BenchRequirement",
                  "properties": { "BenchType": { "type": "string" } }
                }
              }
            }
        """.trimIndent()
        val commonFile = root.resolve("Schema/common.json")
        Files.createDirectories(commonFile.parent)
        Files.writeString(commonFile, commonJson)

        val result = GameDataParser.parseAssetsTree(root)

        val schemaChunks = result.chunks.filter { it.type == GameDataType.SCHEMA }
        assertThat(schemaChunks).hasSize(2)
        assertThat(schemaChunks.map { it.id }).containsExactlyInAnyOrder(
            "gamedata:schema:def:MaterialQuantity",
            "gamedata:schema:def:BenchRequirement",
        )

        File(root.toString()).deleteRecursively()
    }

    @Test
    fun `Schema files do not appear as instance chunks and Server items still parse normally`() {
        val root = Files.createTempDirectory("assets-schema-nodup")
        val schemaJson = """
            {
              "${'$'}id": "Item.json",
              "title": "Item",
              "hytale": { "path": "Item/Items", "extension": ".json" },
              "properties": {}
            }
        """.trimIndent()
        val schemaFile = root.resolve("Schema/Item.json")
        Files.createDirectories(schemaFile.parent)
        Files.writeString(schemaFile, schemaJson)

        val serverJson = """{"Id":"Foo"}"""
        val serverFile = root.resolve("Server/Item/Items/Foo.json")
        Files.createDirectories(serverFile.parent)
        Files.writeString(serverFile, serverJson)

        val result = GameDataParser.parseAssetsTree(root)

        val schemaChunks = result.chunks.filter { it.type == GameDataType.SCHEMA }
        assertThat(schemaChunks).hasSize(1)
        val itemChunks = result.chunks.filter { it.type == GameDataType.ITEM }
        assertThat(itemChunks).hasSize(1)
        assertThat(result.chunks.none { it.type == GameDataType.SCHEMA && it.filePath.startsWith("Server/") }).isTrue

        File(root.toString()).deleteRecursively()
    }

    @Test
    fun `fallback lang is excluded from translation map`() {
        val root = Files.createTempDirectory("assets-fallback")
        val fallbackPath = "Server/Languages/fallback.lang"
        val fallbackContent = "en-GB = en-US\n"
        val fallbackFile = root.resolve(fallbackPath)
        Files.createDirectories(fallbackFile.parent)
        Files.writeString(fallbackFile, fallbackContent)

        val itemFile = root.resolve(entryPath)
        Files.createDirectories(itemFile.parent)
        Files.writeString(itemFile, itemJson)

        val result = GameDataParser.parseAssetsTree(root)
        val locChunks = result.chunks.filter { it.type == GameDataType.LOCALIZATION }
        assertThat(locChunks).isEmpty()

        File(root.toString()).deleteRecursively()
    }
}
