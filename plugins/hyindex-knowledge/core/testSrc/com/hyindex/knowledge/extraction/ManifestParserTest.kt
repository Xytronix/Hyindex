package com.hyindex.knowledge.extraction

import com.hyindex.knowledge.core.extraction.GameDataType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ManifestParserTest {

    private val npcManifest = """
        {
          "Group": "Hytale",
          "Name": "NPC",
          "Version": "1.0.0",
          "Website": "https://hypixel.com/",
          "Main": "com.hypixel.hytale.server.npc.NPCPlugin",
          "ServerVersion": "*",
          "Dependencies": {
            "Hytale:AssetModule": "*",
            "Hytale:TagSet": "*",
            "Hytale:EntityModule": "*"
          },
          "OptionalDependencies": {
            "Hytale:Objectives": "*"
          },
          "SubPlugins": [
            { "Name": "Spawning", "Main": "com.hypixel.hytale.server.spawning.SpawningPlugin" },
            { "Name": "Flock", "Main": "com.hypixel.hytale.server.flock.FlockPlugin" }
          ]
        }
    """.trimIndent()

    @Test fun `parses a manifest into a plugin_manifest chunk`() {
        val chunk = ManifestParser.parse("HytaleServer/NPC/src/main/resources/manifest.json", npcManifest.toByteArray())

        assertThat(chunk).isNotNull
        chunk!!
        assertThat(chunk.type).isEqualTo(GameDataType.PLUGIN_MANIFEST)
        assertThat(chunk.id).isEqualTo("gamedata:manifest:NPC")
        assertThat(chunk.name).isEqualTo("NPC")
        assertThat(chunk.textForEmbedding).contains("com.hypixel.hytale.server.npc.NPCPlugin")
        assertThat(chunk.textForEmbedding).contains("Hytale:AssetModule")
        assertThat(chunk.textForEmbedding).contains("Spawning")
        assertThat(chunk.textForEmbedding).contains("Flock")
    }

    @Test fun `relatedIds include dependency module names`() {
        val chunk = ManifestParser.parse("HytaleServer/NPC/src/main/resources/manifest.json", npcManifest.toByteArray())

        assertThat(chunk).isNotNull
        assertThat(chunk!!.relatedIds).contains("Hytale:AssetModule", "Hytale:TagSet", "Hytale:EntityModule")
    }

    @Test fun `invalid manifest returns null`() {
        val chunk = ManifestParser.parse("HytaleServer/Foo/src/main/resources/manifest.json", "not json".toByteArray())
        assertThat(chunk).isNull()
    }
}
