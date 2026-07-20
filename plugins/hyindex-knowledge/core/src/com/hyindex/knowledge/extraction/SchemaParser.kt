// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.extraction

import com.hyindex.knowledge.core.extraction.GameDataChunk
import com.hyindex.knowledge.core.extraction.GameDataType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

object SchemaParser {

    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parseSchemaFile(relPath: String, bytes: ByteArray): List<GameDataChunk> {
        val raw = String(bytes, Charsets.UTF_8)
        val obj = tryParse(raw) ?: return emptyList()
        val hash = sha256(bytes)

        val definitions = obj["definitions"] as? JsonObject
        val hasHytale = obj["hytale"] is JsonObject

        if (definitions != null && !hasHytale) {
            return definitions.entries.map { (name, defEl) ->
                val defObj = defEl as? JsonObject ?: JsonObject(emptyMap())
                val text = buildDefinitionText(name, defObj)
                GameDataChunk(
                    id = "gamedata:schema:def:$name",
                    type = GameDataType.SCHEMA,
                    name = name,
                    filePath = relPath,
                    fileHash = hash,
                    rawJson = raw,
                    tags = listOf(GameDataType.SCHEMA.id),
                    relatedIds = emptyList(),
                    textForEmbedding = text,
                )
            }
        }

        val title = (obj["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val hytaleObj = obj["hytale"] as? JsonObject ?: return emptyList()
        val text = GameDataTextBuilder.buildSchemaText(title, hytaleObj, obj)
        return listOf(
            GameDataChunk(
                id = "gamedata:schema:$title",
                type = GameDataType.SCHEMA,
                name = title,
                filePath = relPath,
                fileHash = hash,
                rawJson = raw,
                tags = listOf(GameDataType.SCHEMA.id),
                relatedIds = emptyList(),
                textForEmbedding = text,
            )
        )
    }

    private fun buildDefinitionText(name: String, obj: JsonObject): String {
        val sb = StringBuilder()
        sb.appendLine("Schema definition: $name")
        val title = (obj["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (title != null && title != name) sb.appendLine("Title: $title")
        val desc = (obj["description"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (desc != null) sb.appendLine("Description: $desc")
        val props = obj["properties"] as? JsonObject
        if (props != null) {
            for ((propName, propEl) in props) {
                if (propName.startsWith("$")) continue
                val propObj = propEl as? JsonObject ?: continue
                val propDesc = (propObj["description"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: (propObj["markdownDescription"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                val propType = resolveType(propObj)
                val sb2 = StringBuilder(propName)
                if (propType != null) sb2.append(" ($propType)")
                if (propDesc != null) sb2.append(": $propDesc")
                sb.appendLine(sb2.toString())
                val enumArr = propObj["enum"] as? JsonArray
                if (enumArr != null) {
                    val values = enumArr.mapNotNull { (it as? JsonPrimitive)?.content }
                    if (values.isNotEmpty()) sb.appendLine("  enum: ${values.joinToString(", ")}")
                }
                val default = propObj["default"]
                if (default is JsonPrimitive) sb.appendLine("  default: ${default.content}")
            }
        }
        return sb.toString().trim()
    }

    private fun resolveType(propObj: JsonObject): String? {
        val typeEl = propObj["type"] ?: return null
        return when (typeEl) {
            is JsonPrimitive -> typeEl.content.takeIf { it.isNotBlank() }
            is JsonArray -> typeEl.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { it != "null" } }
                .joinToString("|").takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun tryParse(text: String): JsonObject? = try {
        lenientJson.parseToJsonElement(text.trim()).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
