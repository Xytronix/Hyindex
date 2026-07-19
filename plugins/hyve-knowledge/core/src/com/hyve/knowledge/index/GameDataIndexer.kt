// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.index

import com.hyve.knowledge.core.db.Corpus
import com.hyve.knowledge.core.index.CorpusIndexManager
import com.hyve.knowledge.core.index.HnswIndex
import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.core.index.IndexContext
import com.hyve.knowledge.core.index.IndexResult
import com.hyve.knowledge.core.embedding.embedBatched
import com.hyve.knowledge.core.extraction.GameDataChunk
import com.hyve.knowledge.extraction.GameDataParser
import com.hyve.knowledge.extraction.GameDataTextBuilder
import com.hyve.knowledge.extraction.ManifestParser
import com.hyve.knowledge.core.extraction.GameDataType
import com.hyve.knowledge.core.search.SystemClassMapping
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*


class GameDataIndexer(private val ctx: IndexContext) {
    private var indexedCount = 0
    private var skipped = false

    fun index(): IndexResult {
        val db = ctx.db
        val hashTracker = FileHashTracker(db)
        val corpusManager = CorpusIndexManager(ctx.config)
        val indexDir = ctx.indexDir
        indexDir.mkdirs()

        val gameDataDir = ctx.gameDataDir?.takeIf { it.isDirectory }
        if (gameDataDir == null && (ctx.assetsZip == null || !ctx.assetsZip.exists())) {
            ctx.log.warn("GameDataIndexer: no gamedata source (dir or zip), skipping")
            return IndexResult("gamedata", 0, skipped = true, error = null)
        }


        val versionKey = "gamedata:text_builder_version"
        val storedVersion = db.query(
            "SELECT file_hash FROM file_hashes WHERE file_path = ? AND corpus_type = 'gamedata'",
            versionKey,
        ) { it.getString("file_hash") }.firstOrNull()

        val currentVersion = GameDataTextBuilder.TEXT_BUILDER_VERSION.toString()
        if (storedVersion != currentVersion) {
            val existingNodeCount = if (storedVersion == null) {
                db.query("SELECT COUNT(*) FROM nodes WHERE corpus = 'gamedata'") { it.getInt(1) }.firstOrNull() ?: 0
            } else 1

            if (existingNodeCount > 0) {
                ctx.log.info("Text builder version changed (${storedVersion ?: "none"} -> $currentVersion), forcing full re-index")
                ctx.progress.status("Text builder version changed, clearing old index...")
                db.execute("DELETE FROM nodes WHERE corpus = 'gamedata'")
                db.execute("""
                    DELETE FROM edges
                    WHERE (source_id LIKE 'gamedata:%' OR target_id LIKE 'gamedata:%')
                      AND edge_type IN (
                        'RELATES_TO', 'IMPLEMENTED_BY',
                        'REQUIRES_ITEM', 'PRODUCES_ITEM', 'DROPS_ITEM', 'DROPS_ON_DEATH',
                        'OFFERED_IN_SHOP', 'HAS_MEMBER', 'BELONGS_TO_GROUP',
                        'REQUIRES_BENCH', 'TARGETS_GROUP',
                        'SPAWNS_PARTICLE', 'APPLIES_EFFECT', 'REFERENCES_WORLDGEN',
                        'DEPENDS_ON'
                      )
                """)
                db.execute("DELETE FROM file_hashes WHERE corpus_type = 'gamedata'")
            }
        }


        ctx.progress.status("Parsing gamedata...")
        ctx.progress.fraction(0.0)

        var lastProgress = 0.0
        val onProg: (Int, Int, String) -> Unit = { current, total, file ->
            ctx.progress.status(file)
            val frac = 0.3 * current / total.coerceAtLeast(1)
            if (frac - lastProgress > 0.005) {
                ctx.progress.fraction(frac)
                lastProgress = frac
            }
        }
        val parseResult = if (gameDataDir != null)
            GameDataParser.parseAssetsTree(gameDataDir.toPath(), onProg)
        else
            GameDataParser.parseAssetsZip(ctx.assetsZip!!.toPath()) { current, total, file ->
                if (ctx.progress.isCanceled) return@parseAssetsZip
                onProg(current, total, file)
            }

        if (ctx.progress.isCanceled) return IndexResult("gamedata", 0, false, "canceled")

        val manifestChunks = parseManifests()
        val allChunks = disambiguateChunkIds(parseResult.chunks + manifestChunks)
        ctx.log.info("GameDataParser: ${parseResult.chunks.size} chunks, ${manifestChunks.size} manifests, ${parseResult.errors.size} errors")

        for (err in parseResult.errors) {
            db.execute(
                "INSERT INTO index_errors (file_path, error_type, message) VALUES (?, 'parse', ?)",
                err.substringBefore(':'), err,
            )
        }


        ctx.progress.status("Detecting changes...")
        ctx.progress.fraction(0.3)

        val hashMap = allChunks.associate { it.filePath to it.fileHash }
        val changes = hashTracker.computeChangesFromMap(hashMap, "gamedata")
        ctx.log.info("Changes: +${changes.added.size} ~${changes.changed.size} -${changes.deleted.size} =${changes.unchanged.size}")

        if (!changes.hasChanges && changes.unchanged.isNotEmpty()) {
            ctx.log.info("No game data changes detected, skipping indexing")
            skipped = true
            ctx.progress.fraction(1.0)
            return IndexResult("gamedata", 0, skipped = true, error = null)
        }


        val chunksToEmbed = allChunks

        if (ctx.progress.isCanceled) return IndexResult("gamedata", 0, false, "canceled")


        val embeddings: List<FloatArray>
        if (chunksToEmbed.isNotEmpty()) {
            ctx.progress.status("Embedding ${chunksToEmbed.size} game data chunks...")
            ctx.progress.fraction(0.35)

            val provider = corpusManager.getProvider(Corpus.GAMEDATA)
            runBlocking { provider.validate() }

            val texts = chunksToEmbed.map { it.textForEmbedding }
            val cacheService = ctx.cache
            val cacheResult = cacheService.lookup(texts, provider.modelId)

            val uncachedTexts = cacheResult.uncachedIndices.map { texts[it] }
            val newEmbeddings: List<FloatArray> = if (uncachedTexts.isEmpty()) emptyList() else {
                val embedded = runBlocking {
                    provider.embedBatched(
                        uncachedTexts,
                        batchSize = 32,
                        onBatchComplete = { done, total ->
                            ctx.progress.status("Batch $done/$total (${cacheResult.cached.size} cached)")
                            ctx.progress.fraction(0.35 + (0.35 * done / total.coerceAtLeast(1)))
                        },
                    )
                }
                cacheService.store(uncachedTexts, embedded, provider.modelId)
                embedded
            }

            val merged = arrayOfNulls<FloatArray>(texts.size)
            for ((idx, vec) in cacheResult.cached) { merged[idx] = vec }
            for ((i, origIdx) in cacheResult.uncachedIndices.withIndex()) { merged[origIdx] = newEmbeddings[i] }
            embeddings = merged.map { it!! }
        } else {
            embeddings = emptyList()
        }

        if (ctx.progress.isCanceled) return IndexResult("gamedata", 0, false, "canceled")


        ctx.progress.status("Writing game data index...")
        ctx.progress.fraction(0.7)

        val stalePaths = changes.changed + changes.deleted
        if (stalePaths.isNotEmpty()) {
            hashTracker.removeHashes(stalePaths)
            db.inTransaction { conn ->
                val ps = conn.prepareStatement("DELETE FROM nodes WHERE owning_file = ? AND corpus = 'gamedata'")
                for (path in stalePaths) {
                    ps.setString(1, path)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        if (chunksToEmbed.isNotEmpty()) {
            db.inTransaction { conn ->
                val ps = conn.prepareStatement(
                    """INSERT OR REPLACE INTO nodes
                       (id, node_type, display_name, file_path, content, embedding_text, chunk_index, owning_file, corpus, data_type)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'gamedata', ?)"""
                )
                for ((idx, chunk) in chunksToEmbed.withIndex()) {
                    ps.setString(1, chunk.id)
                    ps.setString(2, "GameData")
                    ps.setString(3, chunk.name)
                    ps.setString(4, chunk.filePath)
                    ps.setString(5, chunk.rawJson.take(8192))
                    ps.setString(6, chunk.textForEmbedding)
                    ps.setInt(7, idx)
                    ps.setString(8, chunk.filePath)
                    ps.setString(9, chunk.type.id)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            val ftsRows = chunksToEmbed.map { chunk ->
                FtsTokenizer.FtsRow(chunk.id, chunk.name, chunk.rawJson.take(8192))
            }
            FtsTokenizer.populate(db, "gamedata", ftsRows, splitBody = false)
        }

        if (changes.currentHashes.isNotEmpty()) {
            hashTracker.updateHashes(changes.currentHashes, "gamedata")
        }

        hashTracker.updateHashes(mapOf(versionKey to currentVersion), "gamedata")


        ctx.progress.status("Building game data vector index...")
        ctx.progress.fraction(0.8)

        if (embeddings.isNotEmpty()) {
            val hnswPath = corpusManager.hnswPath(Corpus.GAMEDATA)
            val hnswDimension = embeddings.first().size
            val hnsw = HnswIndex(hnswDimension)
            hnsw.build(embeddings)
            hnsw.save(hnswPath)
            hnsw.close()
            indexedCount = embeddings.size
        }


        ctx.progress.status("Building game data edges...")
        ctx.progress.fraction(0.9)

        buildGameDataEdges(db, allChunks)

        ctx.progress.fraction(1.0)
        ctx.log.info("Game data index built: $indexedCount chunks indexed")
        return IndexResult("gamedata", indexedCount, skipped = false, error = null)
    }

    private fun disambiguateChunkIds(chunks: List<GameDataChunk>): List<GameDataChunk> {
        val seen = HashMap<String, Int>()
        return chunks.map { chunk ->
            val occurrence = seen.merge(chunk.id, 1, Int::plus)!!
            if (occurrence == 1) chunk else chunk.copy(id = "${chunk.id}~$occurrence")
        }
    }

    private fun parseManifests(): List<GameDataChunk> {
        val root = ctx.manifestRoot?.takeIf { it.isDirectory } ?: return emptyList()
        val relBase = root.parentFile ?: root
        return root.walkTopDown()
            .filter { it.isFile && it.name == "manifest.json" && it.path.contains("src/main/resources") }
            .mapNotNull { file ->
                val relPath = file.relativeTo(relBase).path.replace(java.io.File.separatorChar, '/')
                ManifestParser.parse(relPath, file.readBytes())
            }
            .toList()
    }


    internal data class EdgeRow(
        val sourceId: String,
        val targetId: String,
        val edgeType: String,
        val metadata: String? = null,
        val targetResolved: Boolean = true,
    )

    private fun buildGameDataEdges(db: KnowledgeDatabase, chunks: List<GameDataChunk>) {
        if (chunks.isEmpty()) return

        db.execute("""
            DELETE FROM edges
            WHERE (source_id LIKE 'gamedata:%' OR target_id LIKE 'gamedata:%')
              AND edge_type IN (
                'RELATES_TO', 'IMPLEMENTED_BY',
                'REQUIRES_ITEM', 'PRODUCES_ITEM', 'DROPS_ITEM', 'DROPS_ON_DEATH',
                'OFFERED_IN_SHOP', 'HAS_MEMBER', 'BELONGS_TO_GROUP',
                'REQUIRES_BENCH', 'TARGETS_GROUP',
                'SPAWNS_PARTICLE', 'APPLIES_EFFECT', 'REFERENCES_WORLDGEN',
                'DEPENDS_ON'
              )
        """)

        val stemLookup = buildStemLookup(db)
        val allEdges = mutableListOf<EdgeRow>()

        val manifestNames = chunks.asSequence()
            .filter { it.type == GameDataType.PLUGIN_MANIFEST }
            .map { it.name }
            .toSet()

        for (chunk in chunks) {
            val json = parseChunkJson(chunk) ?: continue
            when (chunk.type) {
                GameDataType.ITEM -> allEdges += extractItemEdges(chunk, json, stemLookup)
                GameDataType.RECIPE -> allEdges += extractRecipeEdges(chunk, json, stemLookup)
                GameDataType.DROP -> allEdges += extractDropEdges(chunk, json, stemLookup)
                GameDataType.NPC -> allEdges += extractNpcEdges(chunk, json, stemLookup)
                GameDataType.SHOP -> allEdges += extractShopEdges(chunk, json, stemLookup)
                GameDataType.NPC_GROUP -> allEdges += extractGroupEdges(chunk, json, stemLookup)
                GameDataType.OBJECTIVE -> allEdges += extractObjectiveEdges(chunk, json, stemLookup)
                GameDataType.ENTITY -> allEdges += extractEntityEdges(chunk, json, stemLookup)
                GameDataType.BLOCK -> allEdges += extractBlockEdges(chunk, json, stemLookup)
                GameDataType.FARMING -> allEdges += extractFarmingEdges(chunk, json, stemLookup)
                GameDataType.PROJECTILE -> allEdges += extractProjectileEdges(chunk, json)
                GameDataType.WEATHER -> allEdges += extractWeatherEdges(chunk, json)
                GameDataType.INTERACTION -> allEdges += extractInteractionEdges(chunk, json)
                GameDataType.ZONE -> allEdges += extractZoneEdges(chunk, json, stemLookup)
                GameDataType.PLUGIN_MANIFEST -> allEdges += extractManifestEdges(chunk, json, manifestNames)
                else -> {}
            }
        }

        if (allEdges.isNotEmpty()) {
            db.inTransaction { conn ->
                val ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type, target_resolved, metadata) VALUES (?, ?, ?, ?, ?)"
                )
                for (edge in allEdges) {
                    ps.setString(1, edge.sourceId)
                    ps.setString(2, edge.targetId)
                    ps.setString(3, edge.edgeType)
                    ps.setInt(4, if (edge.targetResolved) 1 else 0)
                    ps.setString(5, edge.metadata)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        val edgeCounts = allEdges.groupBy { it.edgeType }.mapValues { it.value.size }
        ctx.log.info("Game data edges: $edgeCounts")

        buildImplementedByEdges(db, chunks)
    }

    private fun buildStemLookup(db: KnowledgeDatabase): Map<String, List<String>> {
        return db.query(
            "SELECT id, display_name FROM nodes WHERE corpus = 'gamedata'"
        ) { rs -> rs.getString("display_name").lowercase() to rs.getString("id") }
            .groupBy({ it.first }, { it.second })
    }

    private fun resolveStem(
        stem: String,
        sourceId: String,
        edgeType: String,
        stemLookup: Map<String, List<String>>,
        metadata: String? = null,
    ): List<EdgeRow> {
        val targets = stemLookup[stem.lowercase()] ?: return emptyList()
        val multiMatch = targets.size > 1
        return targets.filter { it != sourceId }.map { targetId ->
            val finalMeta = if (multiMatch && metadata != null) {
                metadata.trimEnd('}') + ", \"multi_match\": true}"
            } else if (multiMatch) {
                "{\"multi_match\": true}"
            } else {
                metadata
            }
            EdgeRow(sourceId, targetId, edgeType, finalMeta)
        }
    }

    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun parseChunkJson(chunk: GameDataChunk): JsonObject? {
        return try {
            val text = stripJsoncComments(chunk.rawJson)
            lenientJson.parseToJsonElement(text.trim()).jsonObject
        } catch (e: Exception) {
            ctx.log.debug("Failed to re-parse chunk ${chunk.id}: ${e.message}")
            null
        }
    }

    private fun stripJsoncComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inString = false
        var escape = false
        while (i < text.length) {
            val c = text[i]
            when {
                escape -> { sb.append(c); escape = false; i++ }
                inString && c == '\\' -> { sb.append(c); escape = true; i++ }
                inString -> { if (c == '"') inString = false; sb.append(c); i++ }
                c == '"' -> { inString = true; sb.append(c); i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    i += 2; while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2; while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++; i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString().replace(Regex(",\\s*([}\\]])"), "$1")
    }

    internal fun extractItemEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()

        obj["BlockType"]?.jsonObjectOrNull()?.let { bt ->
            bt["Particles"]?.jsonArrayOrNull()?.forEach { el ->
                val o = el.jsonObjectOrNull() ?: return@forEach
                o.str("SystemId", "systemId")?.let { systemId ->
                    edges += EdgeRow(chunk.id, "virtual:particle:$systemId", "SPAWNS_PARTICLE",
                        """{"trigger":"block_state"}""", targetResolved = false)
                }
            }
        }

        val recipe = obj["Recipe"]?.jsonObjectOrNull() ?: return edges

        extractBenchRequirementEdges(chunk, recipe, edges)

        for (key in listOf("Input", "inputs", "ingredients")) {
            recipe[key]?.jsonArrayOrNull()?.let { inputs ->
                for (input in inputs) {
                    val o = input.jsonObjectOrNull() ?: continue
                    val itemId = o.str("ItemId", "item", "id")
                    if (itemId != null) {
                        edges += resolveStem(itemId, chunk.id, "REQUIRES_ITEM", stemLookup)
                    } else {
                        o.str("ResourceTypeId")?.let { resId ->
                            edges += EdgeRow(chunk.id, "virtual:resource:$resId", "REQUIRES_ITEM", targetResolved = false)
                        }
                    }
                }
                if (edges.isNotEmpty()) return edges
            }
        }
        return edges
    }

    internal fun extractRecipeEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()

        extractBenchRequirementEdges(chunk, obj, edges)

        for (key in listOf("Input", "inputs", "ingredients")) {
            obj[key]?.jsonArrayOrNull()?.let { inputs ->
                for (input in inputs) {
                    val o = input.jsonObjectOrNull() ?: continue
                    val itemId = o.str("ItemId", "item", "id")
                    if (itemId != null) {
                        edges += resolveStem(itemId, chunk.id, "REQUIRES_ITEM", stemLookup)
                    } else {
                        o.str("ResourceTypeId")?.let { resId ->
                            edges += EdgeRow(chunk.id, "virtual:resource:$resId", "REQUIRES_ITEM", targetResolved = false)
                        }
                    }
                }
                if (edges.isNotEmpty()) break
            }
        }

        obj["PrimaryOutput"]?.jsonObjectOrNull()?.let { po ->
            po.str("ItemId", "item", "id")?.let { itemId ->
                edges += resolveStem(itemId, chunk.id, "PRODUCES_ITEM", stemLookup, """{"role": "primary"}""")
            }
        }

        for (key in listOf("Output", "outputs")) {
            obj[key]?.jsonArrayOrNull()?.let { outputs ->
                for (output in outputs) {
                    val o = output.jsonObjectOrNull() ?: continue
                    o.str("ItemId", "item", "id")?.let { itemId ->
                        edges += resolveStem(itemId, chunk.id, "PRODUCES_ITEM", stemLookup, """{"role": "secondary"}""")
                    }
                }
                break
            }
        }

        obj["result"]?.jsonObjectOrNull()?.let { result ->
            result.str("ItemId", "item", "id")?.let { itemId ->
                edges += resolveStem(itemId, chunk.id, "PRODUCES_ITEM", stemLookup, """{"role": "primary"}""")
            }
        }

        return edges
    }

    internal fun extractDropEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()
        val itemIds = mutableListOf<String>()

        obj["Container"]?.jsonObjectOrNull()?.let { extractContainerItemIds(it, itemIds) }

        if (itemIds.isEmpty()) {
            for (key in listOf("Drops", "drops", "entries", "items", "loot")) {
                obj[key]?.jsonArrayOrNull()?.forEach { el ->
                    val o = el.jsonObjectOrNull()
                    val itemId = o?.str("ItemId", "item", "id") ?: (el as? JsonPrimitive)?.content
                    if (!itemId.isNullOrBlank()) itemIds += itemId
                }
            }
        }

        for (itemId in itemIds) {
            edges += resolveStem(itemId, chunk.id, "DROPS_ITEM", stemLookup)
        }
        return edges
    }

    internal fun extractNpcEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()
        val modify = obj["Modify"]?.jsonObjectOrNull()

        val particleSources = listOf(obj, modify).filterNotNull()
        for (src in particleSources) {
            src["ApplicationEffects"]?.jsonObjectOrNull()?.let { ae ->
                ae["Particles"]?.jsonArrayOrNull()?.forEach { el ->
                    val systemId = el.jsonObjectOrNull()?.str("SystemId", "systemId")
                        ?: (el as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    if (systemId != null) {
                        edges += EdgeRow(chunk.id, "virtual:particle:$systemId", "SPAWNS_PARTICLE",
                            """{"trigger":"applied"}""", targetResolved = false)
                    }
                }
            }
        }

        val targetGroupSources = listOf(
            obj["TargetGroups"]?.jsonArrayOrNull(),
            modify?.get("TargetGroups")?.jsonArrayOrNull(),
            obj["AcceptedNpcGroups"]?.jsonArrayOrNull(),
        )
        for (arr in targetGroupSources) {
            arr?.forEach { el ->
                val id = el.jsonObjectOrNull()?.str("Id", "id") ?: (el as? JsonPrimitive)?.content
                if (!id.isNullOrBlank()) edges += resolveStem(id, chunk.id, "TARGETS_GROUP", stemLookup)
            }
        }

        val dropList = obj.str("DropList") ?: modify?.str("DropList")
        if (dropList != null) {
            edges += resolveStem(dropList, chunk.id, "DROPS_ON_DEATH", stemLookup)
        }

        for (key in listOf("Drops", "drops")) {
            val dropsEl = modify?.get(key) ?: obj[key] ?: continue
            when (dropsEl) {
                is JsonPrimitive -> dropsEl.content.takeIf { it.isNotBlank() }?.let {
                    edges += resolveStem(it, chunk.id, "DROPS_ON_DEATH", stemLookup)
                }
                is JsonObject -> dropsEl.str("Id", "id")?.let {
                    edges += resolveStem(it, chunk.id, "DROPS_ON_DEATH", stemLookup)
                }
                is JsonArray -> dropsEl.forEach { el ->
                    val id = el.jsonObjectOrNull()?.str("Id", "id") ?: (el as? JsonPrimitive)?.content
                    if (!id.isNullOrBlank()) edges += resolveStem(id, chunk.id, "DROPS_ON_DEATH", stemLookup)
                }
            }
            break
        }

        return edges
    }

    internal fun extractShopEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()

        obj["TradeSlots"]?.jsonArrayOrNull()?.forEach { slot ->
            val slotObj = slot.jsonObjectOrNull() ?: return@forEach

            slotObj["Trade"]?.jsonObjectOrNull()?.let { trade ->
                trade["Output"]?.jsonObjectOrNull()?.str("ItemId")?.let { itemId ->
                    edges += resolveStem(itemId, chunk.id, "OFFERED_IN_SHOP", stemLookup)
                }
            }

            slotObj["Trades"]?.jsonArrayOrNull()?.forEach { trade ->
                trade.jsonObjectOrNull()?.get("Output")?.jsonObjectOrNull()?.str("ItemId")?.let { itemId ->
                    edges += resolveStem(itemId, chunk.id, "OFFERED_IN_SHOP", stemLookup)
                }
            }
        }

        for (key in listOf("Items", "items")) {
            obj[key]?.jsonArrayOrNull()?.forEach { item ->
                val o = item.jsonObjectOrNull()
                val itemId = o?.str("ItemId", "item", "id") ?: (item as? JsonPrimitive)?.content
                if (!itemId.isNullOrBlank()) {
                    edges += resolveStem(itemId, chunk.id, "OFFERED_IN_SHOP", stemLookup)
                }
            }
        }

        return edges
    }

    internal fun extractGroupEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()
        val npcIds = mutableListOf<String>()

        for (key in listOf("Members", "members")) {
            obj[key]?.jsonArrayOrNull()?.let { arr ->
                for (member in arr) {
                    val o = member.jsonObjectOrNull()
                    val npcId = o?.str("NPC", "npc", "id") ?: (member as? JsonPrimitive)?.content
                    if (!npcId.isNullOrBlank()) npcIds += npcId
                }
                if (npcIds.isNotEmpty()) break
            }
        }

        if (npcIds.isEmpty()) {
            for (key in listOf("NPCs", "npcs")) {
                obj[key]?.jsonArrayOrNull()?.let { arr ->
                    for (npc in arr) {
                        val o = npc.jsonObjectOrNull()
                        val id = o?.str("Id", "id") ?: (npc as? JsonPrimitive)?.content
                        if (!id.isNullOrBlank()) npcIds += id
                    }
                    if (npcIds.isNotEmpty()) break
                }
            }
        }

        for (npcId in npcIds) {
            val resolved = resolveStem(npcId, chunk.id, "HAS_MEMBER", stemLookup)
            edges += resolved
            for (edge in resolved) {
                edges += EdgeRow(edge.targetId, chunk.id, "BELONGS_TO_GROUP", edge.metadata, edge.targetResolved)
            }
        }

        return edges
    }

    private fun extractBenchRequirementEdges(chunk: GameDataChunk, source: JsonObject, edges: MutableList<EdgeRow>) {
        val benchEl = source["BenchRequirement"]
        when (benchEl) {
            is JsonPrimitive -> {
                val id = benchEl.content.takeIf { it.isNotBlank() }
                if (id != null) edges += EdgeRow(chunk.id, "virtual:bench:$id", "REQUIRES_BENCH", targetResolved = false)
            }
            is JsonArray -> {
                benchEl.forEach { el ->
                    val id = el.jsonObjectOrNull()?.str("Id", "id") ?: (el as? JsonPrimitive)?.content
                    if (!id.isNullOrBlank()) edges += EdgeRow(chunk.id, "virtual:bench:$id", "REQUIRES_BENCH", targetResolved = false)
                }
            }
            is JsonObject -> {
                benchEl.str("Id", "id")?.let { id ->
                    edges += EdgeRow(chunk.id, "virtual:bench:$id", "REQUIRES_BENCH", targetResolved = false)
                }
            }
            else -> {}
        }
        if (benchEl == null) {
            source.str("station")?.takeIf { it.isNotBlank() }?.let { id ->
                edges += EdgeRow(chunk.id, "virtual:bench:$id", "REQUIRES_BENCH", targetResolved = false)
            }
        }
    }

    internal fun extractObjectiveEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()
        for (key in listOf("TaskSets", "taskSets")) {
            obj[key]?.jsonArrayOrNull()?.forEach { setEl ->
                setEl.jsonObjectOrNull()?.let { taskSet ->
                    for (tKey in listOf("Tasks", "tasks")) {
                        taskSet[tKey]?.jsonArrayOrNull()?.forEach { taskEl ->
                            val task = taskEl.jsonObjectOrNull() ?: return@forEach
                            val groupId = task.str("NPCGroupId", "npcGroupId", "GroupId", "groupId")
                            if (groupId != null) {
                                edges += resolveStem(groupId, chunk.id, "TARGETS_GROUP", stemLookup)
                            }
                        }
                    }
                }
            }
        }
        return edges
    }

    internal fun extractEntityEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()

        obj["ApplicationEffects"]?.jsonObjectOrNull()?.let { ae ->
            ae["Particles"]?.jsonArrayOrNull()?.forEach { el ->
                val systemId = el.jsonObjectOrNull()?.str("SystemId", "systemId")
                    ?: (el as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                if (systemId != null) {
                    edges += EdgeRow(chunk.id, "virtual:particle:$systemId", "SPAWNS_PARTICLE",
                        """{"trigger":"applied"}""", targetResolved = false)
                }
            }
        }

        for (key in listOf("Drops", "drops")) {
            val dropsEl = obj[key] ?: continue
            when (dropsEl) {
                is JsonPrimitive -> dropsEl.content.takeIf { it.isNotBlank() }?.let {
                    edges += resolveStem(it, chunk.id, "DROPS_ON_DEATH", stemLookup)
                }
                is JsonObject -> dropsEl.str("Id", "id")?.let {
                    edges += resolveStem(it, chunk.id, "DROPS_ON_DEATH", stemLookup)
                }
                else -> {}
            }
            break
        }
        return edges
    }

    internal fun extractBlockEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()

        obj["Particles"]?.jsonObjectOrNull()?.let { particles ->
            for ((event, value) in particles) {
                val systemId = value.jsonObjectOrNull()?.str("SystemId", "systemId")
                    ?: (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                if (systemId != null) {
                    edges += EdgeRow(chunk.id, "virtual:particle:$systemId", "SPAWNS_PARTICLE",
                        """{"trigger":"$event"}""", targetResolved = false)
                }
            }
        }

        for (key in listOf("Drops", "drops", "lootTable")) {
            val dropsEl = obj[key] ?: continue
            when (dropsEl) {
                is JsonPrimitive -> dropsEl.content.takeIf { it.isNotBlank() }?.let {
                    edges += resolveStem(it, chunk.id, "DROPS_ON_DEATH", stemLookup)
                }
                is JsonObject -> dropsEl.str("Id", "id")?.let {
                    edges += resolveStem(it, chunk.id, "DROPS_ON_DEATH", stemLookup)
                }
                else -> {}
            }
            break
        }
        return edges
    }

    internal fun extractFarmingEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()
        obj["AcceptedNpcGroups"]?.jsonArrayOrNull()?.forEach { el ->
            val id = el.jsonObjectOrNull()?.str("Id", "id") ?: (el as? JsonPrimitive)?.content
            if (!id.isNullOrBlank()) edges += resolveStem(id, chunk.id, "TARGETS_GROUP", stemLookup)
        }
        return edges
    }

    internal fun extractProjectileEdges(chunk: GameDataChunk, obj: JsonObject): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()
        obj["HitParticles"]?.jsonObjectOrNull()?.str("SystemId", "systemId")?.let { systemId ->
            edges += EdgeRow(chunk.id, "virtual:particle:$systemId", "SPAWNS_PARTICLE",
                """{"trigger":"hit"}""", targetResolved = false)
        }
        obj["DeathParticles"]?.jsonObjectOrNull()?.str("SystemId", "systemId")?.let { systemId ->
            edges += EdgeRow(chunk.id, "virtual:particle:$systemId", "SPAWNS_PARTICLE",
                """{"trigger":"death"}""", targetResolved = false)
        }
        return edges
    }

    internal fun extractWeatherEdges(chunk: GameDataChunk, obj: JsonObject): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()
        obj["Particle"]?.jsonObjectOrNull()?.str("SystemId", "systemId")?.let { systemId ->
            edges += EdgeRow(chunk.id, "virtual:particle:$systemId", "SPAWNS_PARTICLE",
                """{"trigger":"ambient"}""", targetResolved = false)
        }
        return edges
    }

    internal fun extractInteractionEdges(chunk: GameDataChunk, obj: JsonObject): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()

        for (key in listOf("Effects", "effects")) {
            obj[key]?.jsonArrayOrNull()?.forEach { el ->
                val effectId = el.jsonObjectOrNull()?.str("EffectId", "effectId")
                if (effectId != null) {
                    edges += EdgeRow(chunk.id, "virtual:effect:$effectId", "APPLIES_EFFECT",
                        targetResolved = false)
                }
            }
        }

        val action = obj.str("Action", "action")
        if (action != null && action.contains("Effect", ignoreCase = true)) {
            obj.str("EffectId", "effectId")?.let { effectId ->
                edges += EdgeRow(chunk.id, "virtual:effect:$effectId", "APPLIES_EFFECT",
                    targetResolved = false)
            }
        }

        return edges
    }

    internal fun extractZoneEdges(
        chunk: GameDataChunk, obj: JsonObject, stemLookup: Map<String, List<String>>,
    ): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()

        obj["NoiseMask"]?.jsonObjectOrNull()?.str("File", "file")?.let { file ->
            edges += EdgeRow(chunk.id, "virtual:worldgen:$file", "REFERENCES_WORLDGEN",
                targetResolved = false)
        }

        return edges
    }

    private fun extractManifestEdges(chunk: GameDataChunk, obj: JsonObject, manifestNames: Set<String>): List<EdgeRow> {
        val edges = mutableListOf<EdgeRow>()
        fun emit(depsKey: String, optional: Boolean) {
            val deps = obj[depsKey]?.jsonObjectOrNull() ?: return
            for (key in deps.keys) {
                val depName = key.substringAfterLast(':')
                if (depName.isBlank()) continue
                edges += EdgeRow(
                    chunk.id, "gamedata:manifest:$depName", "DEPENDS_ON",
                    metadata = if (optional) "{\"optional\": true}" else null,
                    targetResolved = depName in manifestNames,
                )
            }
        }
        emit("Dependencies", false)
        emit("OptionalDependencies", true)
        return edges
    }

    private fun extractContainerItemIds(container: JsonObject, out: MutableList<String>) {
        container["Item"]?.jsonObjectOrNull()?.str("ItemId")?.let { out += it; return }
        container.str("ItemId")?.let { out += it; return }

        for (key in listOf("Containers", "Multiple", "Choice", "Single", "Items", "Entries")) {
            when (val sub = container[key]) {
                is JsonArray -> sub.forEach { el -> el.jsonObjectOrNull()?.let { extractContainerItemIds(it, out) } }
                is JsonObject -> extractContainerItemIds(sub, out)
                else -> {}
            }
        }
        container["Container"]?.jsonObjectOrNull()?.let { extractContainerItemIds(it, out) }
    }

    private fun buildImplementedByEdges(db: KnowledgeDatabase, chunks: List<GameDataChunk>) {
        val typeIds = chunks.map { it.type.id }.toSet()
        val typeToClassNames = SystemClassMapping.forDataTypes(typeIds)
        val allClassNames = typeToClassNames.values.flatMap { it.classes }.toSet()

        if (allClassNames.isEmpty()) return

        val placeholders = allClassNames.joinToString(",") { "?" }
        val classNameToNodeIds = db.query(
            "SELECT id, display_name FROM nodes WHERE node_type = 'JavaClass' AND display_name IN ($placeholders)",
            *allClassNames.toTypedArray(),
        ) { rs -> rs.getString("display_name") to rs.getString("id") }
            .groupBy({ it.first }, { it.second })

        val codeEdges = mutableListOf<Pair<String, String>>()
        for (chunk in chunks) {
            val info = typeToClassNames[chunk.type.id] ?: continue
            for (className in info.classes) {
                val nodeIds = classNameToNodeIds[className] ?: continue
                for (nodeId in nodeIds) {
                    codeEdges.add(chunk.id to nodeId)
                }
            }
        }

        if (codeEdges.isNotEmpty()) {
            db.inTransaction { conn ->
                val ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type) VALUES (?, ?, 'IMPLEMENTED_BY')"
                )
                for ((src, tgt) in codeEdges) {
                    ps.setString(1, src)
                    ps.setString(2, tgt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            ctx.log.info("Game data edges: ${codeEdges.size} IMPLEMENTED_BY links created")
        }
    }

    private fun JsonObject.str(vararg keys: String): String? {
        for (key in keys) {
            val v = this[key]?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            if (v != null) return v
        }
        return null
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
}
