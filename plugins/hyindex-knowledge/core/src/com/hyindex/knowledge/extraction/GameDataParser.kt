// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.extraction

import com.hyindex.knowledge.core.extraction.GameDataChunk
import com.hyindex.knowledge.core.extraction.GameDataType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.io.path.exists


object GameDataParser {

    data class ParseResult(
        val chunks: List<GameDataChunk>,
        val errors: List<String>,
    )

    private val lenientJson = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }


    private val IGNORED_PATTERNS = listOf(
        Regex("BranchInfo\\.json$"),
        Regex("Mountain_GoblinLair/Entry\\.node\\.json$"),
        Regex("Nodes_Cave_Volcanic/Node_01\\.node\\.json$"),
        Regex("Mine Info\\.json$"),
        Regex("Mineshaft/Mines\\.json$"),
        Regex("!Custom\\.[^/]+\\.json$"),
    )


    private data class PathRule(val pattern: Regex, val type: GameDataType)

    private val PATH_RULES = listOf(

        PathRule(Regex("Server/Item/Items/"), GameDataType.ITEM),
        PathRule(Regex("Server/Item/Recipes/"), GameDataType.RECIPE),
        PathRule(Regex("Server/Item/Block/"), GameDataType.BLOCK),
        PathRule(Regex("Server/Item/Interactions/"), GameDataType.INTERACTION),
        PathRule(Regex("Server/Item/RootInteractions/"), GameDataType.INTERACTION),
        PathRule(Regex("Server/Item/Groups/"), GameDataType.ITEM),
        PathRule(Regex("Server/Item/Category/"), GameDataType.ITEM),
        PathRule(Regex("Server/Item/ResourceTypes/"), GameDataType.ITEM),
        PathRule(Regex("Server/Item/Qualities/"), GameDataType.ITEM),

        PathRule(Regex("Server/Drops/"), GameDataType.DROP),
        PathRule(Regex("Server/NPC/Roles/"), GameDataType.NPC),
        PathRule(Regex("Server/NPC/Groups/"), GameDataType.NPC_GROUP),
        PathRule(Regex("Server/NPC/DecisionMaking/"), GameDataType.NPC_AI),
        PathRule(Regex("Server/NPC/Flocks/"), GameDataType.NPC),
        PathRule(Regex("Server/NPC/Attitude/"), GameDataType.NPC),
        PathRule(Regex("Server/Entity/"), GameDataType.ENTITY),
        PathRule(Regex("Server/Projectiles/"), GameDataType.PROJECTILE),

        PathRule(Regex("Server/Farming/"), GameDataType.FARMING),
        PathRule(Regex("Server/BarterShops/"), GameDataType.SHOP),
        PathRule(Regex("Server/Environments/"), GameDataType.ENVIRONMENT),
        PathRule(Regex("Server/Weathers/"), GameDataType.WEATHER),
        PathRule(Regex("Server/Camera/"), GameDataType.CAMERA),
        PathRule(Regex("Server/Objective/"), GameDataType.OBJECTIVE),
        PathRule(Regex("Server/GameplayConfigs/"), GameDataType.GAMEPLAY),

        PathRule(Regex("Server/HytaleGenerator/Biomes/"), GameDataType.BIOME),
        PathRule(Regex("Server/HytaleGenerator/Assignments/"), GameDataType.WORLDGEN),
        PathRule(Regex("Server/HytaleGenerator/"), GameDataType.WORLDGEN),

        PathRule(Regex("Server/Audio/"), GameDataType.AUDIO),
        PathRule(Regex("Server/NPC/Spawn/"), GameDataType.NPC_SPAWN),
        PathRule(Regex("Server/NPC/Balancing/"), GameDataType.NPC_AI),
        PathRule(Regex("Server/ProjectileConfigs/"), GameDataType.PROJECTILE_CONFIG),
        PathRule(Regex("Server/Models/"), GameDataType.MODEL),
        PathRule(Regex("Server/Item/Animations/"), GameDataType.ANIMATION),
        PathRule(Regex("Server/Item/Unarmed/"), GameDataType.INTERACTION),
        PathRule(Regex("Server/Instances/"), GameDataType.INSTANCE),
        PathRule(Regex("Server/ScriptedBrushes/"), GameDataType.SCRIPTED_BRUSH),
        PathRule(Regex("Server/MacroCommands/"), GameDataType.MACRO_COMMAND),
        PathRule(Regex("Server/TagPatterns/"), GameDataType.TAG_PATTERN),
        PathRule(Regex("Server/ResponseCurves/"), GameDataType.RESPONSE_CURVE),
        PathRule(Regex("Server/PortalTypes/"), GameDataType.PORTAL),
        PathRule(Regex("Server/Particles/"), GameDataType.PARTICLE),
        PathRule(Regex("Server/EncounterManager/"), GameDataType.ENCOUNTER),
        PathRule(Regex("Server/BlockTypeList/"), GameDataType.BLOCK),
        PathRule(Regex("Server/PrefabList/"), GameDataType.PREFAB),
        PathRule(Regex("Server/WordLists/"), GameDataType.LOCALIZATION),
        PathRule(Regex("Server/TriggerVolumes/"), GameDataType.INTERACTION),

        PathRule(Regex("Server/World/.+/Zones/.+/Layers/"), GameDataType.TERRAIN_LAYER),
        PathRule(Regex("Server/World/.+/Zones/.+/Cave/"), GameDataType.CAVE),
        PathRule(Regex("Server/World/.+/Zones/Layers/"), GameDataType.TERRAIN_LAYER),
        PathRule(Regex("Server/World/.+/Zones/Masks/"), GameDataType.ZONE),
        PathRule(Regex("Server/World/.+/Zones/Noise/"), GameDataType.WORLDGEN),
        PathRule(Regex("Server/World/.+/Zones/.+/(Tile|Custom|Zone)\\."), GameDataType.ZONE),
        PathRule(Regex("Server/World/.+/Zones/"), GameDataType.ZONE),

        PathRule(Regex("Server/Prefabs/"), GameDataType.PREFAB),
        PathRule(Regex("Common/Languages/"), GameDataType.LOCALIZATION),
    )


    fun parseAssetsZip(
        zipPath: Path,
        onProgress: ((current: Int, total: Int, file: String) -> Unit)? = null,
    ): ParseResult {
        val chunks = mutableListOf<GameDataChunk>()
        val errors = mutableListOf<String>()

        if (!zipPath.exists()) {
            return ParseResult(emptyList(), listOf("Assets.zip not found at: $zipPath"))
        }


        val totalEntries = countJsonEntries(zipPath)


        var current = 0
        Files.newInputStream(zipPath).buffered().use { fileStream ->
            ZipInputStream(fileStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.endsWith(".json")) {
                        current++
                        onProgress?.invoke(current, totalEntries, name)

                        if (!shouldIgnore(name)) {
                            val type = classifyPath(name)
                            if (type != null) {
                                try {
                                    val bytes = zip.readBytes()
                                    val chunk = parseEntry(name, type, bytes)
                                    if (chunk != null) chunks.add(chunk)
                                } catch (e: Exception) {
                                    val msg = "Failed to parse $name: ${e.message}"
                                    errors.add(msg)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        return ParseResult(chunks, errors)
    }


    fun parseAssetsTree(
        root: Path,
        onProgress: ((current: Int, total: Int, file: String) -> Unit)? = null,
    ): ParseResult {
        val chunks = mutableListOf<GameDataChunk>()
        val errors = mutableListOf<String>()
        if (!root.exists()) return ParseResult(emptyList(), listOf("Assets dir not found at: $root"))

        val allFiles = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }

        val langFiles = allFiles.filter { it.toString().endsWith(".lang") }
        val jsonFiles = allFiles.filter { it.toString().endsWith(".json") }

        val translations = mutableMapOf<String, String>()
        for (langPath in langFiles) {
            val relPath = root.relativize(langPath).toString().replace('\\', '/')
            if (relPath.endsWith("fallback.lang")) continue
            val langDir = relPath.substringBefore("/Languages/", "")
            if (!relPath.contains("/Languages/")) continue
            val prefix = LangParser.derivePrefix(relPath)
            val text = String(Files.readAllBytes(langPath), Charsets.UTF_8)
            val parsed = LangParser.parse(text)
            for ((bareKey, value) in parsed) {
                translations["$prefix.$bareKey"] = value
            }
            val locChunks = buildLocalizationChunks(prefix, parsed, relPath)
            chunks.addAll(locChunks)
        }

        val total = jsonFiles.size
        var current = 0
        for (path in jsonFiles) {
            val name = root.relativize(path).toString().replace('\\', '/')
            current++
            onProgress?.invoke(current, total, name)
            if (shouldIgnore(name)) continue
            if (name.startsWith("Schema/") && name.endsWith(".json")) {
                try {
                    chunks.addAll(SchemaParser.parseSchemaFile(name, Files.readAllBytes(path)))
                } catch (e: Exception) {
                    errors.add("Failed to parse schema $name: ${e.message}")
                }
                continue
            }
            val type = classifyPath(name) ?: continue
            try {
                val chunk = parseEntry(name, type, Files.readAllBytes(path), translations)
                if (chunk != null) chunks.add(chunk)
            } catch (e: Exception) {
                errors.add("Failed to parse $name: ${e.message}")
            }
        }
        return ParseResult(chunks, errors)
    }

    private fun buildLocalizationChunks(
        prefix: String,
        entries: Map<String, String>,
        filePath: String,
    ): List<GameDataChunk> {
        if (entries.isEmpty()) return emptyList()
        val byNamespace = mutableMapOf<String, MutableMap<String, String>>()
        for ((key, value) in entries) {
            val ns = key.substringBefore('.')
            byNamespace.getOrPut(ns) { mutableMapOf() }[key] = value
        }
        if (entries.size <= 50 || byNamespace.size <= 1) {
            val text = GameDataTextBuilder.buildLocalizationText(prefix, entries, filePath)
            val ns = byNamespace.keys.firstOrNull() ?: prefix
            val id = "gamedata:lang:$prefix:$ns"
            val hash = sha256(text.toByteArray())
            return listOf(GameDataChunk(id, GameDataType.LOCALIZATION, prefix, filePath, hash, "", listOf(GameDataType.LOCALIZATION.id), emptyList(), text))
        }
        return byNamespace.map { (ns, nsEntries) ->
            val text = GameDataTextBuilder.buildLocalizationText("$prefix.$ns", nsEntries, filePath)
            val id = "gamedata:lang:$prefix:$ns"
            val hash = sha256(text.toByteArray())
            GameDataChunk(id, GameDataType.LOCALIZATION, "$prefix.$ns", filePath, hash, "", listOf(GameDataType.LOCALIZATION.id), emptyList(), text)
        }
    }


    private fun shouldIgnore(path: String): Boolean =
        IGNORED_PATTERNS.any { it.containsMatchIn(path) }

    private fun classifyPath(path: String): GameDataType? {
        val matched = PATH_RULES.firstOrNull { it.pattern.containsMatchIn(path) }?.type
        if (matched != null) return matched
        return if (path.contains("Server/")) GameDataType.MISC else null
    }

    internal fun classifyForTest(path: String): GameDataType? = classifyPath(path)


    private fun parseEntry(
        entryPath: String,
        type: GameDataType,
        bytes: ByteArray,
        translations: Map<String, String> = emptyMap(),
    ): GameDataChunk? {
        val hash = sha256(bytes)
        val raw = decodeContent(bytes)
        val jsonObj = parseJsonWithFallbacks(raw, entryPath) ?: return null

        val name = deriveName(entryPath, type, jsonObj)
        val id = "gamedata:${entryPath.replace('/', ':')}"
        val tags = extractTags(type, jsonObj)
        val relatedIds = extractRelatedIds(jsonObj)
        val text = GameDataTextBuilder.buildText(
            GameDataChunk(id, type, name, entryPath, hash, raw, tags, relatedIds, ""),
            jsonObj,
            translations,
        )

        return GameDataChunk(
            id = id,
            type = type,
            name = name,
            filePath = entryPath,
            fileHash = hash,
            rawJson = raw,
            tags = tags,
            relatedIds = relatedIds,
            textForEmbedding = text,
        )
    }

    private fun decodeContent(bytes: ByteArray): String {

        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }


    private fun parseJsonWithFallbacks(raw: String, path: String): JsonObject? {

        tryParse(raw)?.let { return it }


        val clean = stripComments(raw)
        tryParse(clean)?.let { return it }


        val fixedComments = fixUnclosedBlockComments(clean)
        tryParse(fixedComments)?.let { return it }


        val stripped = stripInvalidStart(raw)
        tryParse(stripComments(stripped))?.let { return it }


        val firstLine = tryJsonLines(raw)
        if (firstLine != null) return firstLine

        return null
    }

    private fun tryParse(text: String): JsonObject? {
        return try {
            val element = lenientJson.parseToJsonElement(text.trim())
            element.jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inString = false
        var escape = false
        var lastCommaIndex = -1

        while (i < text.length) {
            val c = text[i]
            when {
                escape -> {
                    sb.append(c)
                    escape = false
                    i++
                }
                inString && c == '\\' -> {
                    sb.append(c)
                    escape = true
                    i++
                }
                inString -> {
                    if (c == '"') inString = false
                    sb.append(c)
                    i++
                }
                c == '"' -> {
                    inString = true
                    sb.append(c)
                    lastCommaIndex = -1
                    i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {

                    i += 2
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {

                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                c == ',' -> {
                    lastCommaIndex = sb.length
                    sb.append(c)
                    i++
                }
                c == '}' || c == ']' -> {
                    if (lastCommaIndex != -1) {
                        var onlyWhitespace = true
                        for (idx in lastCommaIndex + 1 until sb.length) {
                            if (!sb[idx].isWhitespace()) {
                                onlyWhitespace = false
                                break
                            }
                        }
                        if (onlyWhitespace) {
                            sb.deleteCharAt(lastCommaIndex)
                        }
                    }
                    sb.append(c)
                    lastCommaIndex = -1
                    i++
                }
                else -> {
                    if (!c.isWhitespace()) {
                        lastCommaIndex = -1
                    }
                    sb.append(c)
                    i++
                }
            }
        }

        return sb.toString()
    }

    private fun fixUnclosedBlockComments(text: String): String {
        val opens = Regex("/\\*").findAll(text).count()
        val closes = Regex("\\*/").findAll(text).count()
        val count = opens - closes
        return if (count > 0) text + " */".repeat(count) else text
    }

    private fun stripInvalidStart(text: String): String {
        val firstBrace = text.indexOf('{').takeIf { it >= 0 } ?: Int.MAX_VALUE
        val firstBracket = text.indexOf('[').takeIf { it >= 0 } ?: Int.MAX_VALUE
        val start = minOf(firstBrace, firstBracket)
        return if (start == Int.MAX_VALUE) text else text.substring(start)
    }

    private fun tryJsonLines(text: String): JsonObject? {
        val match = Regex("}\\s*\\{").find(text) ?: return null
        val first = text.substring(0, match.range.first + 1).trim()
        return tryParse(first)
    }


    private fun deriveName(path: String, type: GameDataType, obj: JsonObject): String {
        val fileStem = path.substringAfterLast('/').removeSuffix(".json")
            .removeSuffix(".node").removeSuffix(".prefab")

        return when (type) {

            GameDataType.PREFAB -> fileStem


            GameDataType.ITEM, GameDataType.RECIPE, GameDataType.DROP -> fileStem


            GameDataType.NPC -> {
                for (key in listOf("name", "Name", "id", "Id", "key", "Key")) {
                    val str = (obj[key] as? JsonPrimitive)?.content
                    if (!str.isNullOrBlank()) return str
                }
                fileStem
            }


            else -> {
                for (key in listOf("name", "Name", "id", "Id", "key", "Key")) {
                    val str = (obj[key] as? JsonPrimitive)?.content
                    if (!str.isNullOrBlank()) return str
                }
                fileStem
            }
        }
    }

    private fun extractTags(type: GameDataType, obj: JsonObject): List<String> {
        val tags = mutableListOf(type.id)


        val tagsEl = obj["Tags"] ?: obj["tags"]
        when (tagsEl) {
            is JsonArray -> {
                tagsEl.forEach { el ->
                    val str = (el as? JsonPrimitive)?.content
                    if (!str.isNullOrBlank()) tags.add(str)
                }
            }
            is JsonObject -> {

                for ((category, values) in tagsEl) {
                    when (values) {
                        is JsonArray -> values.forEach { v ->
                            (v as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let {
                                tags.add("$category:$it")
                            }
                        }
                        is JsonPrimitive -> values.content.takeIf { it.isNotBlank() }?.let {
                            tags.add("$category:$it")
                        }
                        else -> {}
                    }
                }
            }
            else -> {}
        }


        val catsEl = obj["Categories"] ?: obj["categories"]
        when (catsEl) {
            is JsonArray -> catsEl.forEach { el ->
                (el as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
            }
            else -> {}
        }


        val category = (obj["category"] ?: obj["Category"]) as? JsonPrimitive
        category?.content?.takeIf { it.isNotBlank() }?.let { tags.add(it) }

        return tags
    }

    private fun extractRelatedIds(obj: JsonObject): List<String> {
        val ids = mutableListOf<String>()
        for (key in listOf("Items", "items", "Drops", "drops", "Recipes", "recipes",
                           "Requires", "requires", "Rewards", "rewards", "Loot", "loot",
                           "Input", "Output", "PrimaryOutput")) {
            val el = obj[key] ?: continue
            when (el) {
                is JsonArray -> el.forEach { item ->
                    when (item) {
                        is JsonPrimitive -> item.content.takeIf { it.isNotBlank() }?.let { ids.add(it) }
                        is JsonObject -> {

                            val itemId = (item["ItemId"] as? JsonPrimitive)?.content
                                ?: (item["item"] as? JsonPrimitive)?.content
                                ?: (item["id"] as? JsonPrimitive)?.content
                                ?: (item["Id"] as? JsonPrimitive)?.content
                            itemId?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
                        }
                        else -> {}
                    }
                }
                is JsonObject -> {

                    val itemId = (el["ItemId"] as? JsonPrimitive)?.content
                        ?: (el["item"] as? JsonPrimitive)?.content
                    itemId?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
                }
                else -> {}
            }
        }
        return ids
    }


    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun countJsonEntries(zipPath: Path): Int {
        var count = 0
        Files.newInputStream(zipPath).buffered().use { fs ->
            ZipInputStream(fs).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".json")) count++
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return count
    }
}
