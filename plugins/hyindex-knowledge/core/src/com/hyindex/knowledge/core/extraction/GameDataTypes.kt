// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.extraction

enum class GameDataType(val id: String, val displayName: String) {
    ITEM("item", "Item"),
    RECIPE("recipe", "Recipe"),
    BLOCK("block", "Block"),
    INTERACTION("interaction", "Interaction"),
    DROP("drop", "Drop"),
    NPC("npc", "NPC"),
    NPC_GROUP("npc_group", "NPC Group"),
    NPC_AI("npc_ai", "NPC AI"),
    ENTITY("entity", "Entity"),
    PROJECTILE("projectile", "Projectile"),
    FARMING("farming", "Farming"),
    SHOP("shop", "Shop"),
    ENVIRONMENT("environment", "Environment"),
    WEATHER("weather", "Weather"),
    BIOME("biome", "Biome"),
    WORLDGEN("worldgen", "World Gen"),
    CAMERA("camera", "Camera"),
    OBJECTIVE("objective", "Objective"),
    GAMEPLAY("gameplay", "Gameplay"),
    LOCALIZATION("localization", "Localization"),
    ZONE("zone", "Zone"),
    TERRAIN_LAYER("terrain_layer", "Terrain Layer"),
    CAVE("cave", "Cave"),
    PREFAB("prefab", "Prefab"),
    AUDIO("audio", "Audio"),
    NPC_SPAWN("npc_spawn", "NPC Spawn"),
    MODEL("model", "Model"),
    PROJECTILE_CONFIG("projectile_config", "Projectile Config"),
    ANIMATION("animation", "Animation"),
    INSTANCE("instance", "Instance"),
    SCRIPTED_BRUSH("scripted_brush", "Scripted Brush"),
    MACRO_COMMAND("macro_command", "Macro Command"),
    TAG_PATTERN("tag_pattern", "Tag Pattern"),
    RESPONSE_CURVE("response_curve", "Response Curve"),
    PORTAL("portal", "Portal"),
    PARTICLE("particle", "Particle"),
    ENCOUNTER("encounter", "Encounter"),
    MISC("misc", "Misc"),
    SCHEMA("schema", "Asset Schema"),
    PLUGIN_MANIFEST("plugin_manifest", "Plugin Manifest");

    companion object {
        fun fromId(id: String): GameDataType? = entries.find { it.id == id }
    }
}

data class GameDataChunk(
    val id: String,
    val type: GameDataType,
    val name: String,
    val filePath: String,
    val fileHash: String,
    val rawJson: String,
    val tags: List<String>,
    val relatedIds: List<String>,
    val textForEmbedding: String,
)
