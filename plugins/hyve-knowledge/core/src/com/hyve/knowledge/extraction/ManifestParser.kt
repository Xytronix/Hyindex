// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.extraction

import com.hyve.knowledge.core.extraction.GameDataChunk
import com.hyve.knowledge.core.extraction.GameDataType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

object ManifestParser {

    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(relPath: String, bytes: ByteArray): GameDataChunk? {
        val raw = String(bytes, Charsets.UTF_8)
        val obj = tryParse(raw) ?: return null
        val name = (obj["Name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null

        val dependencyKeys = (obj["Dependencies"] as? JsonObject)?.keys?.toList() ?: emptyList()
        val subPluginMains = (obj["SubPlugins"] as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("Main") as? JsonPrimitive)?.content?.takeIf { m -> m.isNotBlank() } }
            ?: emptyList()

        val hash = sha256(bytes)
        val id = "gamedata:manifest:$name"
        val relatedIds = dependencyKeys + subPluginMains
        val text = GameDataTextBuilder.buildManifestText(
            GameDataChunk(id, GameDataType.PLUGIN_MANIFEST, name, relPath, hash, raw, listOf(GameDataType.PLUGIN_MANIFEST.id), relatedIds, ""),
            obj,
        )

        return GameDataChunk(
            id = id,
            type = GameDataType.PLUGIN_MANIFEST,
            name = name,
            filePath = relPath,
            fileHash = hash,
            rawJson = raw,
            tags = listOf(GameDataType.PLUGIN_MANIFEST.id),
            relatedIds = relatedIds,
            textForEmbedding = text,
        )
    }

    private fun tryParse(text: String): JsonObject? =
        try {
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
