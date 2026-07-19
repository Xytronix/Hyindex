package com.hyve.knowledge.extraction

import com.hyve.knowledge.core.extraction.GameDataChunk
import com.hyve.knowledge.core.extraction.GameDataType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameDataTextBuilderTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `buildText for item resolves TranslationProperties Name and Description`() {
        val obj = json.parseToJsonElement(
            """{"TranslationProperties":{"Name":"server.items.Foo.name","Description":"server.items.Foo.desc"}}"""
        ).jsonObject
        val chunk = GameDataChunk(
            id = "gamedata:Foo",
            type = GameDataType.ITEM,
            name = "Foo",
            filePath = "Server/Item/Items/Foo.json",
            fileHash = "",
            rawJson = "",
            tags = emptyList(),
            relatedIds = emptyList(),
            textForEmbedding = "",
        )
        val translations = mapOf(
            "server.items.Foo.name" to "Cauldron",
            "server.items.Foo.desc" to "Brews potions",
        )
        val text = GameDataTextBuilder.buildText(chunk, obj, translations)
        assertThat(text).contains("Display name: Cauldron")
        assertThat(text).contains("Description: Brews potions")
        assertThat(text).doesNotContain("server.items.Foo.name")
        assertThat(text).doesNotContain("server.items.Foo.desc")
    }

    @Test
    fun `buildText for item falls back to raw key when not in map`() {
        val obj = json.parseToJsonElement(
            """{"TranslationProperties":{"Name":"server.items.Unknown.name"}}"""
        ).jsonObject
        val chunk = GameDataChunk(
            id = "gamedata:Unknown",
            type = GameDataType.ITEM,
            name = "Unknown",
            filePath = "Server/Item/Items/Unknown.json",
            fileHash = "",
            rawJson = "",
            tags = emptyList(),
            relatedIds = emptyList(),
            textForEmbedding = "",
        )
        val text = GameDataTextBuilder.buildText(chunk, obj, emptyMap())
        assertThat(text).contains("server.items.Unknown.name")
    }

    @Test
    fun `buildText for NPC resolves NameTranslationKey`() {
        val obj = json.parseToJsonElement(
            """{"NameTranslationKey":"server.npcRoles.Bear_Grizzly.name"}"""
        ).jsonObject
        val chunk = GameDataChunk(
            id = "gamedata:Bear_Grizzly",
            type = GameDataType.NPC,
            name = "Bear_Grizzly",
            filePath = "Server/NPC/Roles/Bear_Grizzly.json",
            fileHash = "",
            rawJson = "",
            tags = emptyList(),
            relatedIds = emptyList(),
            textForEmbedding = "",
        )
        val translations = mapOf("server.npcRoles.Bear_Grizzly.name" to "Grizzly Bear")
        val text = GameDataTextBuilder.buildText(chunk, obj, translations)
        assertThat(text).contains("Display name: Grizzly Bear")
        assertThat(text).doesNotContain("server.npcRoles.Bear_Grizzly.name")
    }

    @Test
    fun `TEXT_BUILDER_VERSION is 12`() {
        assertThat(GameDataTextBuilder.TEXT_BUILDER_VERSION).isEqualTo(12)
    }

    private fun chunk(type: GameDataType, name: String, path: String) = GameDataChunk(
        id = "gamedata:$name",
        type = type,
        name = name,
        filePath = path,
        fileHash = "",
        rawJson = "",
        tags = emptyList(),
        relatedIds = emptyList(),
        textForEmbedding = "",
    )

    @Test
    fun `buildText for AUDIO emits sound name, Files, MaxDistance, Volume`() {
        val obj = json.parseToJsonElement(
            """{"Layers":[{"Files":["Sounds/UI/Durability/Item_Durability_Break_Stereo.ogg"],"Volume":4.06}],"Volume":0.0,"MaxDistance":45}"""
        ).jsonObject
        val c = chunk(GameDataType.AUDIO, "SFX_Item_Break", "Server/Audio/SoundEvents/SFX/UI/SFX_Item_Break.json")
        val text = GameDataTextBuilder.buildText(c, obj)
        assertThat(text).startsWith("Audio: SFX_Item_Break")
        assertThat(text).contains("Sounds/UI/Durability/Item_Durability_Break_Stereo.ogg")
        assertThat(text).contains("MaxDistance: 45")
        assertThat(text).contains("Volume:")
    }

    @Test
    fun `buildText for NPC_SPAWN emits environments, NPC ids and weights, day-time range`() {
        val obj = json.parseToJsonElement(
            """{"Environments":["Env_Zone1_Swamps"],"NPCs":[{"Id":"Snake_Marsh","Weight":25,"SpawnBlockSet":"Soil"},{"Id":"Fen_Stalker","Weight":15,"SpawnBlockSet":"Mud"}],"DayTimeRange":[6,24]}"""
        ).jsonObject
        val c = chunk(GameDataType.NPC_SPAWN, "Spawns_Zone1_Swamps_Predator", "Server/NPC/Spawn/World/Zone1/Spawns_Zone1_Swamps_Predator.json")
        val text = GameDataTextBuilder.buildText(c, obj)
        assertThat(text).startsWith("NPC Spawn: Spawns_Zone1_Swamps_Predator")
        assertThat(text).contains("Environments: Env_Zone1_Swamps")
        assertThat(text).contains("Snake_Marsh")
        assertThat(text).contains("weight 25")
        assertThat(text).contains("Fen_Stalker")
        assertThat(text).contains("DayTimeRange: 6-24")
    }

    @Test
    fun `buildText for NPC_SPAWN handles marker-style NPCs with Name field and Flock`() {
        val obj = json.parseToJsonElement(
            """{"NPCs":[{"Name":"Hyena","Weight":60,"Flock":"Group_Tiny"}]}"""
        ).jsonObject
        val c = chunk(GameDataType.NPC_SPAWN, "Hyena_Marker", "Server/NPC/Spawn/Markers/Hyena.json")
        val text = GameDataTextBuilder.buildText(c, obj)
        assertThat(text).contains("Hyena")
        assertThat(text).contains("weight 60")
        assertThat(text).contains("flock Group_Tiny")
    }

    @Test
    fun `buildText for PROJECTILE_CONFIG emits model, physics, launch force, sound event ids`() {
        val obj = json.parseToJsonElement(
            """{"Model":"Arrow_Crude","Physics":{"Gravity":15,"Bounciness":0,"RotationMode":"VelocityDamped"},"LaunchForce":25,"LaunchLocalSoundEventId":"SFX_Mace_T2_Impact","LaunchWorldSoundEventId":"SFX_Mace_T2_Impact"}"""
        ).jsonObject
        val c = chunk(GameDataType.PROJECTILE_CONFIG, "Projectile_Config_Debug", "Server/ProjectileConfigs/_Debug/Projectile_Config_Debug.json")
        val text = GameDataTextBuilder.buildText(c, obj)
        assertThat(text).startsWith("Projectile Config: Projectile_Config_Debug")
        assertThat(text).contains("Model: Arrow_Crude")
        assertThat(text).contains("Gravity: 15")
        assertThat(text).contains("Bounciness: 0")
        assertThat(text).contains("RotationMode: VelocityDamped")
        assertThat(text).contains("LaunchForce: 25")
        assertThat(text).contains("SFX_Mace_T2_Impact")
    }

    @Test
    fun `buildText for MODEL emits model path, texture, hitbox, eyeheight`() {
        val obj = json.parseToJsonElement(
            """{"Model":"NPC/Beast/Lizard_Sand/Models/Model.blockymodel","Texture":"NPC/Beast/Lizard_Sand/Models/Texture.png","EyeHeight":0.45,"HitBox":{"Max":{"X":0.6,"Y":0.8,"Z":0.6},"Min":{"X":-0.6,"Y":0.0,"Z":-0.6}}}"""
        ).jsonObject
        val c = chunk(GameDataType.MODEL, "Lizard_Sand", "Server/Models/Beast/Lizard_Sand.json")
        val text = GameDataTextBuilder.buildText(c, obj)
        assertThat(text).startsWith("Model: Lizard_Sand")
        assertThat(text).contains("NPC/Beast/Lizard_Sand/Models/Model.blockymodel")
        assertThat(text).contains("NPC/Beast/Lizard_Sand/Models/Texture.png")
        assertThat(text).contains("EyeHeight: 0.45")
        assertThat(text).contains("HitBox:")
    }
}
